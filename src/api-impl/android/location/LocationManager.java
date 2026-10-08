package android.location;
import android.os.CancellationSignal;
import java.util.function.Consumer;
import java.util.concurrent.Executor;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.lang.Runnable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LocationManager {

	static Set<LocationListener> listeners = new HashSet<>();

	public String getBestProvider(Criteria criteria, boolean enabledOnly) {
		return "xdgportal";
	}

	public Location getLastKnownLocation(String provider) {
		return null;
	}

	public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceM, LocationListener listener) {
		listeners.add(listener);
		nativeGetLocation();
	}

	private native void nativeGetLocation();

	private static void locationUpdated(double latitude,
	                                    double longitude,
	                                    double altitude,
	                                    double accuracy,
	                                    double speed,
	                                    double bearing,
	                                    long timestamp) {
		for (LocationListener locationListener : listeners) {
			locationListener.onLocationChanged(new Location(latitude, longitude, altitude, accuracy, speed, bearing, timestamp));
		}
	}

	public boolean sendExtraCommand(String provider, String command, Bundle extras) {
		return true;
	}

	public void removeUpdates(LocationListener listener) {
	}

	public List<String> getAllProviders() {
		return Collections.emptyList();
	}

	public List<String> getProviders(boolean enabledOnly) {
		return Collections.emptyList();
	}

	public boolean registerGnssStatusCallback(GnssStatus.Callback callback, Handler handler) {
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				callback.onSatelliteStatusChanged(new GnssStatus());
			}
		});
		return true;
	}

	public void unregisterGnssStatusCallback(GnssStatus.Callback callback) {
	}

	/* teslalinux-frag 00d0b731e56d */
	/* teslalinux: LocationManager additions (API 28-34) */
	public boolean isLocationEnabled() { return true; }
	public boolean hasProvider(String provider) { return "gps".equals(provider) || "network".equals(provider) || "passive".equals(provider) || "fused".equals(provider); }
	public LocationProvider getProvider(String name) { return hasProvider(name) ? new LocationProvider(name) : null; }
	public void getCurrentLocation(String provider, CancellationSignal signal, Executor executor, final Consumer<Location> consumer) {
		final Location l = getLastKnownLocation(provider);
		executor.execute(new Runnable() { public void run() { consumer.accept(l); } });
	}
	public void getCurrentLocation(String provider, LocationRequest request, CancellationSignal signal, Executor executor, Consumer<Location> consumer) { getCurrentLocation(provider, signal, executor, consumer); }
	public boolean registerGnssMeasurementsCallback(GnssMeasurementsEvent.Callback callback) { return false; }
	public boolean registerGnssMeasurementsCallback(GnssMeasurementsEvent.Callback callback, android.os.Handler handler) { return false; }
	public boolean registerGnssMeasurementsCallback(Executor executor, GnssMeasurementsEvent.Callback callback) { return false; }
	public void unregisterGnssMeasurementsCallback(GnssMeasurementsEvent.Callback callback) {}
	public boolean registerGnssStatusCallback(Executor executor, GnssStatus.Callback callback) { return registerGnssStatusCallback(callback, null); }
	public String getGnssHardwareModelName() { return "atl"; }
	public int getGnssYearOfHardware() { return 2020; }
	public void requestLocationUpdates(String provider, LocationRequest request, Executor executor, LocationListener listener) { requestLocationUpdates(provider, request.getIntervalMillis(), request.getMinUpdateDistanceMeters(), listener); }
	public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceM, Executor executor, LocationListener listener) { requestLocationUpdates(provider, minTimeMs, minDistanceM, listener); }
	public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceM, LocationListener listener, android.os.Looper looper) { requestLocationUpdates(provider, minTimeMs, minDistanceM, listener); }
	public void requestLocationUpdates(long minTimeMs, float minDistanceM, Criteria criteria, LocationListener listener, android.os.Looper looper) { requestLocationUpdates("gps", minTimeMs, minDistanceM, listener); }
	public void requestLocationUpdates(long minTimeMs, float minDistanceM, Criteria criteria, Executor executor, LocationListener listener) { requestLocationUpdates("gps", minTimeMs, minDistanceM, listener); }
	public void requestSingleUpdate(String provider, LocationListener listener, android.os.Looper looper) { requestLocationUpdates(provider, 0, 0, listener); }
	public void removeUpdates(android.app.PendingIntent intent) {}
	public boolean addNmeaListener(Executor executor, OnNmeaMessageListener listener) { return false; }
	public void removeNmeaListener(OnNmeaMessageListener listener) {}
}
