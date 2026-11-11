package android.atl.carhost;

import android.atl.car.ATLCarService;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsConfiguration;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

/**
 * The car the host projects to, as a head unit tells its host about it: the driving state and the
 * UX restrictions that follow from it, and whether it is day or night (the head unit's night mode,
 * which the host passes to the app as the configuration's night mode, CarContext.isDarkMode).
 *
 * <ul>
 * <li>Driving state: from the vehicle speed, here the speed of the location feed (the car's GPS):
 *     moving above zero, idling at zero, as AOSP's CarDrivingStateService without gear and
 *     parking brake signals. The restrictions are AOSP's default map
 *     ({@link ATLCarService#defaultUxRestrictionsConfiguration}). Until a speed is known the car
 *     is not restricted: a head unit that reports no speed does not lock its host's input.</li>
 * <li>Night: the sun below the horizon (the elevation of sunrise and sunset, -0.833 degrees) at
 *     the car's position, checked every minute; the position is the last fix of the location
 *     feed. Without any position the host keeps day. ATL_CAR_DAY_NIGHT=day|night fixes it, and
 *     the renderer can choose too ({@link #setMode}).</li>
 * </ul>
 * All calls and callbacks run on the host thread (the handler given).
 */
final class ATLCarVehicle implements LocationListener {
	private static final String TAG = "ATLCarVehicle";
	/** the sun's elevation at sunrise and sunset (refraction and the sun's radius) */
	private static final double SUNSET_ELEVATION = -0.833;
	private static final long NIGHT_CHECK_MS = 60 * 1000;

	interface Listener {
		void onRestrictionsChanged(CarUxRestrictions restrictions);

		void onNightChanged(boolean night);
	}

	private final Handler handler;
	private final Listener listener;
	private final CarUxRestrictionsConfiguration config = ATLCarService.defaultUxRestrictionsConfiguration();
	private CarUxRestrictions restrictions = new CarUxRestrictions.Builder(false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE, SystemClock.elapsedRealtimeNanos()).build();
	private boolean night;
	private double latitude = Double.NaN;
	private double longitude = Double.NaN;
	/* "auto" (the sun), "day" or "night" */
	private String mode;

	private final Runnable nightCheck = new Runnable() {
		@Override
		public void run() {
			updateNight();
			handler.postDelayed(this, NIGHT_CHECK_MS);
		}
	};

	ATLCarVehicle(Handler handler, Listener listener) {
		this.handler = handler;
		this.listener = listener;
		String m = System.getenv("ATL_CAR_DAY_NIGHT");
		mode = "day".equals(m) || "night".equals(m) ? m : "auto";
	}

	void start(Context context) {
		LocationManager lm = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
		if (lm != null) {
			try {
				Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
				if (last != null) {
					latitude = last.getLatitude();
					longitude = last.getLongitude();
				}
				lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this, handler.getLooper());
			} catch (RuntimeException e) {
				Log.w(TAG, "no location feed: no speed and no position for the car", e);
			}
		}
		nightCheck.run();
	}

	CarUxRestrictions restrictions() {
		return restrictions;
	}

	boolean isNight() {
		return night;
	}

	/** day or night chosen by the driver ("day", "night"), or by the sun again ("auto") */
	void setMode(String m) {
		mode = "day".equals(m) || "night".equals(m) ? m : "auto";
		updateNight();
	}

	@Override
	public void onLocationChanged(Location location) {
		latitude = location.getLatitude();
		longitude = location.getLongitude();
		if (location.hasSpeed()) {
			float speed = Math.max(0f, location.getSpeed());
			int state = speed > 0f ? CarDrivingStateEvent.DRIVING_STATE_MOVING : CarDrivingStateEvent.DRIVING_STATE_IDLING;
			CarUxRestrictions next = config.getUxRestrictions(state, speed, "baseline");
			if (!next.isSameRestrictions(restrictions)) {
				restrictions = next;
				listener.onRestrictionsChanged(next);
			}
		}
		updateNight();
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {}

	@Override
	public void onProviderEnabled(String provider) {}

	@Override
	public void onProviderDisabled(String provider) {}

	private void updateNight() {
		boolean next;
		if ("day".equals(mode))
			next = false;
		else if ("night".equals(mode))
			next = true;
		else if (Double.isNaN(latitude))
			next = night;
		else
			next = sunElevation(latitude, longitude, System.currentTimeMillis()) < SUNSET_ELEVATION;
		if (next != night) {
			night = next;
			listener.onNightChanged(next);
		}
	}

	/**
	 * The sun's elevation in degrees at a place and time: the Astronomical Almanac's low-precision
	 * solar coordinates (good to about 0.01 degrees between 1950 and 2050) and the local hour angle.
	 */
	static double sunElevation(double latitude, double longitude, long millis) {
		double n = millis / 86400000.0 + 2440587.5 - 2451545.0; // days since J2000.0
		double meanLongitude = 280.460 + 0.9856474 * n;
		double g = Math.toRadians(357.528 + 0.9856003 * n); // mean anomaly
		double lambda = Math.toRadians(meanLongitude + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
		double epsilon = Math.toRadians(23.439 - 0.0000004 * n); // obliquity of the ecliptic
		double ra = Math.atan2(Math.cos(epsilon) * Math.sin(lambda), Math.cos(lambda));
		double dec = Math.asin(Math.sin(epsilon) * Math.sin(lambda));
		double gmstHours = 18.697374558 + 24.06570982441908 * n;
		double hourAngle = Math.toRadians(gmstHours * 15 + longitude) - ra;
		double lat = Math.toRadians(latitude);
		double sinElevation = Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(hourAngle);
		return Math.toDegrees(Math.asin(sinElevation));
	}
}
