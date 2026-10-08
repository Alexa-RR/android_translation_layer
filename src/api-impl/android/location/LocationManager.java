/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.location;

import android.app.PendingIntent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides access to the system location services.
 *
 * teslalinux: the AOSP API over a small provider model. Fixes reach the "gps" provider from
 * whichever sources this runtime has:
 * <ul>
 * <li>the desktop location portal (ATL's existing path, ATL_UGLY_ENABLE_LOCATION),</li>
 * <li>ATL_LOCATION_SOURCE=fix:LAT,LON[,ALT] - a fixed position reported once a second, the role the
 *     emulator's "geo fix" plays (used by the test VM),</li>
 * <li>ATL_LOCATION_SOURCE=tesla:[HOST:]PORT - the car: the Tesla browser reports its GPS to the
 *     teslalinux gps bridge (services/gps/gps.py, WebSocket 9998), which re-emits it as a line feed
 *     (default port 9997) that this source reads,</li>
 * <li>test providers created by the app through addTestProvider / setTestProviderLocation, exactly
 *     as on Android.</li>
 * </ul>
 * "network" and "fused" mirror the best available fix; "passive" receives everything. Listener
 * delivery honours the requested minimum time and distance and the requested Looper / Executor.
 */
public class LocationManager {
	private static final String TAG = "LocationManager";

	public static final String NETWORK_PROVIDER = "network";
	public static final String GPS_PROVIDER = "gps";
	public static final String PASSIVE_PROVIDER = "passive";
	public static final String FUSED_PROVIDER = "fused";

	public static final String KEY_PROXIMITY_ENTERING = "entering";
	public static final String KEY_STATUS_CHANGED = "status";
	public static final String KEY_PROVIDER_ENABLED = "providerEnabled";
	public static final String KEY_LOCATION_CHANGED = "location";
	public static final String KEY_LOCATIONS = "locations";
	public static final String KEY_FLUSH_COMPLETE = "flushComplete";
	public static final String PROVIDERS_CHANGED_ACTION = "android.location.PROVIDERS_CHANGED";
	public static final String MODE_CHANGED_ACTION = "android.location.MODE_CHANGED";
	public static final String EXTRA_PROVIDER_NAME = "android.location.extra.PROVIDER_NAME";
	public static final String EXTRA_PROVIDER_ENABLED = "android.location.extra.PROVIDER_ENABLED";
	public static final String EXTRA_LOCATION_ENABLED = "android.location.extra.LOCATION_ENABLED";
	public static final String ACTION_GNSS_CAPABILITIES_CHANGED = "android.location.action.GNSS_CAPABILITIES_CHANGED";
	public static final String EXTRA_GNSS_CAPABILITIES = "android.location.extra.GNSS_CAPABILITIES";

	/** A registration made through one of the requestLocationUpdates overloads. */
	private static final class Request {
		final String provider;
		final long minTimeMs;
		final float minDistanceM;
		final LocationListener listener;
		final Executor executor;
		final Handler handler;
		long lastDeliveryUptime = Long.MIN_VALUE;
		Location lastDelivered;

		Request(String provider, long minTimeMs, float minDistanceM, LocationListener listener, Executor executor, Looper looper) {
			this.provider = provider;
			this.minTimeMs = Math.max(0, minTimeMs);
			this.minDistanceM = Math.max(0, minDistanceM);
			this.listener = listener;
			this.executor = executor;
			this.handler = executor == null ? new Handler(looper != null ? looper : (Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper())) : null;
		}

		void deliver(final Location location) {
			final long now = SystemClock.uptimeMillis();
			if (lastDelivered != null) {
				if (minTimeMs > 0 && now - lastDeliveryUptime < minTimeMs)
					return;
				if (minDistanceM > 0 && lastDelivered.distanceTo(location) < minDistanceM)
					return;
			}
			lastDeliveryUptime = now;
			lastDelivered = location;
			final Location copy = new Location(location);
			Runnable r = new Runnable() {
				@Override
				public void run() {
					listener.onLocationChanged(copy);
				}
			};
			if (executor != null)
				executor.execute(r);
			else
				handler.post(r);
		}
	}

	/** State of one provider: enabled flag, last fix, whether it is an app-created test provider. */
	private static final class ProviderState {
		final String name;
		boolean enabled;
		boolean test;
		Location last;

		ProviderState(String name, boolean enabled, boolean test) {
			this.name = name;
			this.enabled = enabled;
			this.test = test;
		}
	}

	private static final Object sLock = new Object();
	private static final Map<String, ProviderState> sProviders = new HashMap<>();
	private static final CopyOnWriteArrayList<Request> sRequests = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<Object[]> sGnssCallbacks = new CopyOnWriteArrayList<>();
	private static boolean sSourcesStarted;
	private static boolean sPortalStarted;

	static {
		boolean haveLocation = System.getenv("ATL_UGLY_ENABLE_LOCATION") != null || System.getenv("ATL_LOCATION_SOURCE") != null;
		sProviders.put(GPS_PROVIDER, new ProviderState(GPS_PROVIDER, haveLocation, false));
		sProviders.put(NETWORK_PROVIDER, new ProviderState(NETWORK_PROVIDER, haveLocation, false));
		sProviders.put(FUSED_PROVIDER, new ProviderState(FUSED_PROVIDER, haveLocation, false));
		sProviders.put(PASSIVE_PROVIDER, new ProviderState(PASSIVE_PROVIDER, true, false));
	}

	public LocationManager() {}

	// ---- providers ------------------------------------------------------------------------------

	public List<String> getAllProviders() {
		synchronized (sLock) {
			return new ArrayList<>(sProviders.keySet());
		}
	}

	public List<String> getProviders(boolean enabledOnly) {
		synchronized (sLock) {
			List<String> out = new ArrayList<>();
			for (ProviderState p : sProviders.values()) {
				if (!enabledOnly || p.enabled)
					out.add(p.name);
			}
			return out;
		}
	}

	public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
		return getProviders(enabledOnly);
	}

	public boolean hasProvider(String provider) {
		synchronized (sLock) {
			return sProviders.containsKey(provider);
		}
	}

	public LocationProvider getProvider(String name) {
		return hasProvider(name) ? new LocationProvider(name) : null;
	}

	/** AOSP: the provider that best fits the criteria; here gps when enabled, otherwise the fused/network mirror. */
	public String getBestProvider(Criteria criteria, boolean enabledOnly) {
		if (isProviderEnabled(GPS_PROVIDER) || !enabledOnly)
			return GPS_PROVIDER;
		if (isProviderEnabled(FUSED_PROVIDER))
			return FUSED_PROVIDER;
		if (isProviderEnabled(NETWORK_PROVIDER))
			return NETWORK_PROVIDER;
		return PASSIVE_PROVIDER;
	}

	public boolean isProviderEnabled(String provider) {
		synchronized (sLock) {
			ProviderState p = sProviders.get(provider);
			return p != null && p.enabled;
		}
	}

	public boolean isLocationEnabled() {
		return isProviderEnabled(GPS_PROVIDER) || isProviderEnabled(NETWORK_PROVIDER) || isProviderEnabled(FUSED_PROVIDER);
	}

	public Location getLastKnownLocation(String provider) {
		synchronized (sLock) {
			ProviderState p = sProviders.get(provider);
			return p == null || p.last == null ? null : new Location(p.last);
		}
	}

	// ---- requests -------------------------------------------------------------------------------

	public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceM, LocationListener listener) {
		requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, null);
	}

	public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceM, LocationListener listener, Looper looper) {
		addRequest(new Request(provider, minTimeMs, minDistanceM, listener, null, looper));
	}

	public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceM, Executor executor, LocationListener listener) {
		addRequest(new Request(provider, minTimeMs, minDistanceM, listener, executor, null));
	}

	public void requestLocationUpdates(String provider, LocationRequest request, Executor executor, LocationListener listener) {
		addRequest(new Request(provider, request.getIntervalMillis(), request.getMinUpdateDistanceMeters(), listener, executor, null));
	}

	public void requestLocationUpdates(long minTimeMs, float minDistanceM, Criteria criteria, LocationListener listener, Looper looper) {
		requestLocationUpdates(getBestProvider(criteria, true), minTimeMs, minDistanceM, listener, looper);
	}

	public void requestLocationUpdates(long minTimeMs, float minDistanceM, Criteria criteria, Executor executor, LocationListener listener) {
		requestLocationUpdates(getBestProvider(criteria, true), minTimeMs, minDistanceM, executor, listener);
	}

	public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceM, PendingIntent intent) {
		Log.w(TAG, "requestLocationUpdates(PendingIntent) is not supported: no intent delivery service in this runtime");
	}

	public void requestSingleUpdate(String provider, LocationListener listener, Looper looper) {
		requestLocationUpdates(provider, 0, 0, listener, looper);
	}

	public void requestSingleUpdate(Criteria criteria, LocationListener listener, Looper looper) {
		requestSingleUpdate(getBestProvider(criteria, true), listener, looper);
	}

	public void getCurrentLocation(String provider, CancellationSignal signal, Executor executor, final Consumer<Location> consumer) {
		final Location l = getLastKnownLocation(provider);
		executor.execute(new Runnable() {
			@Override
			public void run() {
				consumer.accept(l);
			}
		});
	}

	public void getCurrentLocation(String provider, LocationRequest request, CancellationSignal signal, Executor executor, Consumer<Location> consumer) {
		getCurrentLocation(provider, signal, executor, consumer);
	}

	private void addRequest(Request r) {
		if (r.listener == null)
			throw new IllegalArgumentException("invalid null listener");
		if (!hasProvider(r.provider))
			throw new IllegalArgumentException("provider doesn't exist: " + r.provider);
		sRequests.add(r);
		startSources();
		// AOSP delivers the last known location of the provider straight away when it is fresh
		Location last = getLastKnownLocation(r.provider);
		if (last != null)
			r.deliver(last);
	}

	public void removeUpdates(LocationListener listener) {
		for (Request r : sRequests) {
			if (r.listener == listener)
				sRequests.remove(r);
		}
	}

	public void removeUpdates(PendingIntent intent) {}

	public boolean sendExtraCommand(String provider, String command, Bundle extras) {
		return true;
	}

	// ---- GNSS status ----------------------------------------------------------------------------

	public boolean registerGnssStatusCallback(GnssStatus.Callback callback) {
		return registerGnssStatusCallback(callback, null);
	}

	public boolean registerGnssStatusCallback(final GnssStatus.Callback callback, Handler handler) {
		final Handler h = handler != null ? handler : new Handler(Looper.getMainLooper());
		sGnssCallbacks.add(new Object[] { callback, h });
		h.post(new Runnable() {
			@Override
			public void run() {
				callback.onStarted();
				callback.onSatelliteStatusChanged(new GnssStatus());
			}
		});
		return true;
	}

	public boolean registerGnssStatusCallback(Executor executor, GnssStatus.Callback callback) {
		return registerGnssStatusCallback(callback, null);
	}

	public void unregisterGnssStatusCallback(GnssStatus.Callback callback) {
		for (Object[] e : sGnssCallbacks) {
			if (e[0] == callback)
				sGnssCallbacks.remove(e);
		}
	}

	public boolean registerGnssMeasurementsCallback(GnssMeasurementsEvent.Callback callback) { return false; }
	public boolean registerGnssMeasurementsCallback(GnssMeasurementsEvent.Callback callback, Handler handler) { return false; }
	public boolean registerGnssMeasurementsCallback(Executor executor, GnssMeasurementsEvent.Callback callback) { return false; }
	public void unregisterGnssMeasurementsCallback(GnssMeasurementsEvent.Callback callback) {}
	public boolean addNmeaListener(Executor executor, OnNmeaMessageListener listener) { return false; }
	public void removeNmeaListener(OnNmeaMessageListener listener) {}

	public String getGnssHardwareModelName() {
		return "atl";
	}

	public int getGnssYearOfHardware() {
		return 2020;
	}

	// ---- test providers (AOSP mock location API) -----------------------------------------------

	public void addTestProvider(String provider, boolean requiresNetwork, boolean requiresSatellite, boolean requiresCell, boolean hasMonetaryCost,
	                            boolean supportsAltitude, boolean supportsSpeed, boolean supportsBearing, int powerUsage, int accuracy) {
		synchronized (sLock) {
			ProviderState p = sProviders.get(provider);
			if (p == null) {
				sProviders.put(provider, new ProviderState(provider, false, true));
			} else {
				p.test = true;
			}
		}
	}

	public void addTestProvider(String provider, ProviderProperties properties) {
		addTestProvider(provider, false, false, false, false, false, false, false, 0, 0);
	}

	public void removeTestProvider(String provider) {
		synchronized (sLock) {
			ProviderState p = sProviders.get(provider);
			if (p != null && p.test) {
				if (GPS_PROVIDER.equals(provider) || NETWORK_PROVIDER.equals(provider) || FUSED_PROVIDER.equals(provider) || PASSIVE_PROVIDER.equals(provider)) {
					p.test = false;
				} else {
					sProviders.remove(provider);
				}
			}
		}
	}

	public void setTestProviderEnabled(String provider, boolean enabled) {
		synchronized (sLock) {
			ProviderState p = sProviders.get(provider);
			if (p == null || !p.test)
				throw new IllegalArgumentException("provider doesn't exist: " + provider);
			p.enabled = enabled;
		}
	}

	public void setTestProviderLocation(String provider, Location location) {
		synchronized (sLock) {
			ProviderState p = sProviders.get(provider);
			if (p == null || !p.test)
				throw new IllegalArgumentException("provider doesn't exist: " + provider);
		}
		Location l = new Location(location);
		l.setProvider(provider);
		reportLocation(provider, l);
	}

	@Deprecated
	public void setTestProviderStatus(String provider, int status, Bundle extras, long updateTime) {}

	@Deprecated
	public void clearTestProviderLocation(String provider) {}

	@Deprecated
	public void clearTestProviderEnabled(String provider) {}

	@Deprecated
	public void clearTestProviderStatus(String provider) {}

	// ---- fix sources ----------------------------------------------------------------------------

	/** Entry point for every fix: records it on its provider (and the mirrors) and dispatches it. */
	static void reportLocation(String provider, Location location) {
		List<String> targets = new ArrayList<>();
		synchronized (sLock) {
			ProviderState p = sProviders.get(provider);
			if (p == null)
				return;
			p.last = location;
			targets.add(provider);
			if (GPS_PROVIDER.equals(provider) || NETWORK_PROVIDER.equals(provider)) {
				for (String mirror : new String[] { FUSED_PROVIDER, NETWORK_PROVIDER, PASSIVE_PROVIDER }) {
					ProviderState m = sProviders.get(mirror);
					if (m != null && !m.test && !mirror.equals(provider)) {
						m.last = location;
						targets.add(mirror);
					}
				}
			} else {
				ProviderState passive = sProviders.get(PASSIVE_PROVIDER);
				if (passive != null) {
					passive.last = location;
					targets.add(PASSIVE_PROVIDER);
				}
			}
		}
		for (Request r : sRequests) {
			if (targets.contains(r.provider))
				r.deliver(location);
		}
	}

	private static Location makeFix(String provider, double lat, double lon, double alt, double accuracy, double speed, double bearing, long timeMs) {
		Location l = new Location(lat, lon, alt, accuracy, speed, bearing, timeMs);
		l.setProvider(provider);
		l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
		return l;
	}

	/** Callback from the desktop location portal (api-impl-jni/location). */
	private static void locationUpdated(double latitude, double longitude, double altitude, double accuracy, double speed, double bearing, long timestamp) {
		reportLocation(GPS_PROVIDER, makeFix(GPS_PROVIDER, latitude, longitude, altitude, accuracy, speed, bearing, timestamp > 0 ? timestamp : System.currentTimeMillis()));
	}

	private native void nativeGetLocation();

	private void startSources() {
		synchronized (sLock) {
			if (sSourcesStarted)
				return;
			sSourcesStarted = true;
		}
		String source = System.getenv("ATL_LOCATION_SOURCE");
		if (source != null && source.startsWith("fix:")) {
			startFixedSource(source.substring(4));
		} else if (source != null && source.startsWith("tesla:")) {
			startTeslaFeedSource(source.substring(6));
		} else if (source != null) {
			Log.e(TAG, "ATL_LOCATION_SOURCE not understood: " + source + " (expected fix:LAT,LON[,ALT] or tesla:[HOST:]PORT)");
		}
		if (System.getenv("ATL_UGLY_ENABLE_LOCATION") != null && !sPortalStarted) {
			sPortalStarted = true;
			try {
				nativeGetLocation();
			} catch (UnsatisfiedLinkError e) {
				Log.w(TAG, "location portal not available: " + e);
			}
		}
	}

	/** fix:LAT,LON[,ALT]: a stationary GNSS receiver, one fix per second at 5 m accuracy. */
	private static void startFixedSource(String spec) {
		final String[] parts = spec.split(",");
		if (parts.length < 2) {
			Log.e(TAG, "ATL_LOCATION_SOURCE=fix: needs LAT,LON[,ALT]");
			return;
		}
		final double lat = Double.parseDouble(parts[0].trim());
		final double lon = Double.parseDouble(parts[1].trim());
		final double alt = parts.length > 2 ? Double.parseDouble(parts[2].trim()) : 0;
		Thread t = new Thread("atl-location-fix") {
			@Override
			public void run() {
				while (true) {
					reportLocation(GPS_PROVIDER, makeFix(GPS_PROVIDER, lat, lon, alt, 5.0, 0, 0, System.currentTimeMillis()));
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						return;
					}
				}
			}
		};
		t.setDaemon(true);
		t.start();
		Log.i(TAG, "location source: fixed position " + lat + "," + lon);
	}

	/**
	 * tesla:[HOST:]PORT - the teslalinux GPS bridge (services/gps/gps.py): the Tesla browser posts its
	 * geolocation over WebSocket 9998 and the bridge re-emits one line per fix on a TCP port:
	 * "lat lon speed_mps bearing_deg accuracy_m timestamp_ms", with "nan" for unknown fields.
	 */
	private static void startTeslaFeedSource(String spec) {
		final String host = spec.contains(":") ? spec.substring(0, spec.lastIndexOf(':')) : "127.0.0.1";
		final int port = Integer.parseInt((spec.contains(":") ? spec.substring(spec.lastIndexOf(':') + 1) : spec).trim());
		Thread t = new Thread("atl-location-tesla") {
			@Override
			public void run() {
				while (true) {
					try (Socket s = new Socket(host, port)) {
						BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
						String line;
						while ((line = r.readLine()) != null) {
							String[] f = line.trim().split("\\s+");
							if (f.length < 2)
								continue;
							double lat = Double.parseDouble(f[0]);
							double lon = Double.parseDouble(f[1]);
							if (Double.isNaN(lat) || Double.isNaN(lon))
								continue;
							double speed = f.length > 2 ? Double.parseDouble(f[2]) : Double.NaN;
							double bearing = f.length > 3 ? Double.parseDouble(f[3]) : Double.NaN;
							double acc = f.length > 4 ? Double.parseDouble(f[4]) : Double.NaN;
							long time = System.currentTimeMillis();
							if (f.length > 5) {
								try {
									time = (long) Double.parseDouble(f[5]);
								} catch (NumberFormatException e) {
									// keep wall clock
								}
							}
							Location l = makeFix(GPS_PROVIDER, lat, lon, 0, Double.isNaN(acc) ? 10 : acc, Double.isNaN(speed) ? 0 : speed, Double.isNaN(bearing) ? 0 : bearing, time);
							if (Double.isNaN(speed))
								l.removeSpeed();
							if (Double.isNaN(bearing))
								l.removeBearing();
							l.removeAltitude();
							reportLocation(GPS_PROVIDER, l);
						}
					} catch (Exception e) {
						Log.w(TAG, "gps bridge " + host + ":" + port + ": " + e + " (retrying in 5 s)");
					}
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {
						return;
					}
				}
			}
		};
		t.setDaemon(true);
		t.start();
		Log.i(TAG, "location source: tesla gps bridge " + host + ":" + port);
	}
}
