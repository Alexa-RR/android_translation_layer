package android.hardware;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import java.util.Arrays;
import java.util.List;

public class SensorManager {

	/* teslalinux: AOSP SensorManager semantics over the sensors this runtime really has:
	 *  - TYPE_ACCELEROMETER when an IIO accelerometer is present (ATL native source, /sys/bus/iio),
	 *  - TYPE_ORIENTATION (deprecated API) derived from the GPS bearing (ATL's existing mapping),
	 *  - nothing else: getDefaultSensor() returns null for sensors the device does not have, as on a
	 *    phone without that sensor, so apps take their no-sensor paths instead of waiting for events. */
	public static final float GRAVITY_EARTH = 9.80665f;
	public static final float GRAVITY_SUN = 275.0f;
	public static final float GRAVITY_MOON = 1.6f;
	public static final float STANDARD_GRAVITY = 9.80665f;
	public static final float MAGNETIC_FIELD_EARTH_MAX = 60.0f;
	public static final float MAGNETIC_FIELD_EARTH_MIN = 30.0f;
	public static final float PRESSURE_STANDARD_ATMOSPHERE = 1013.25f;
	public static final float LIGHT_SUNLIGHT_MAX = 120000.0f;
	public static final float LIGHT_SUNLIGHT = 110000.0f;
	public static final float LIGHT_SHADE = 20000.0f;
	public static final float LIGHT_OVERCAST = 10000.0f;
	public static final float LIGHT_SUNRISE = 400.0f;
	public static final float LIGHT_CLOUDY = 100.0f;
	public static final float LIGHT_FULLMOON = 0.25f;
	public static final float LIGHT_NO_MOON = 0.001f;
	public static final int SENSOR_DELAY_FASTEST = 0;
	public static final int SENSOR_DELAY_GAME = 1;
	public static final int SENSOR_DELAY_UI = 2;
	public static final int SENSOR_DELAY_NORMAL = 3;
	public static final int SENSOR_STATUS_NO_CONTACT = -1;
	public static final int SENSOR_STATUS_UNRELIABLE = 0;
	public static final int SENSOR_STATUS_ACCURACY_LOW = 1;
	public static final int SENSOR_STATUS_ACCURACY_MEDIUM = 2;
	public static final int SENSOR_STATUS_ACCURACY_HIGH = 3;

	/** One active registration: the listener plus the source it is wired to. */
	private static final class Registration {
		final SensorEventListener listener;
		final Sensor sensor;
		final Handler handler;
		final LocationListener locationListener;
		volatile boolean active = true;

		Registration(SensorEventListener listener, Sensor sensor, Handler handler, LocationListener locationListener) {
			this.listener = listener;
			this.sensor = sensor;
			this.handler = handler;
			this.locationListener = locationListener;
		}

		void deliver(final SensorEvent event) {
			if (!active)
				return;
			if (handler != null && handler.getLooper() != android.os.Looper.myLooper()) {
				handler.post(new Runnable() {
					@Override
					public void run() {
						if (active)
							listener.onSensorChanged(event);
					}
				});
			} else {
				listener.onSensorChanged(event);
			}
		}
	}

	private static final java.util.concurrent.CopyOnWriteArrayList<Registration> sRegistrations = new java.util.concurrent.CopyOnWriteArrayList<>();
	private static java.util.Map<Integer, Sensor> sSensors;

	private static synchronized java.util.Map<Integer, Sensor> availableSensors() {
		if (sSensors == null) {
			sSensors = new java.util.LinkedHashMap<>();
			if (hasIioAccelerometer())
				sSensors.put(Sensor.TYPE_ACCELEROMETER, new Sensor(Sensor.TYPE_ACCELEROMETER));
			if (System.getenv("ATL_UGLY_ENABLE_LOCATION") != null || System.getenv("ATL_LOCATION_SOURCE") != null)
				sSensors.put(Sensor.TYPE_ORIENTATION, new Sensor(Sensor.TYPE_ORIENTATION));
		}
		return sSensors;
	}

	private static boolean hasIioAccelerometer() {
		java.io.File[] devices = new java.io.File("/sys/bus/iio/devices").listFiles();
		if (devices == null)
			return false;
		for (java.io.File d : devices) {
			if (new java.io.File(d, "in_accel_x_raw").isFile() && new java.io.File(d, "in_mount_matrix").isFile())
				return true;
		}
		return false;
	}

	public Sensor getDefaultSensor(int type) {
		return availableSensors().get(type);
	}

	public Sensor getDefaultSensor(int type, boolean wakeUp) {
		// none of the sensors here is a wake-up sensor
		return wakeUp ? null : getDefaultSensor(type);
	}

	public List<Sensor> getSensorList(int type) {
		if (type == Sensor.TYPE_ALL)
			return new java.util.ArrayList<>(availableSensors().values());
		Sensor s = getDefaultSensor(type);
		return s == null ? java.util.Collections.<Sensor>emptyList() : java.util.Collections.singletonList(s);
	}

	public List<Sensor> getDynamicSensorList(int type) {
		return java.util.Collections.emptyList();
	}

	public boolean isDynamicSensorDiscoverySupported() {
		return false;
	}

	public boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs) {
		return registerListener(listener, sensor, samplingPeriodUs, 0, null);
	}

	public boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs, Handler handler) {
		return registerListener(listener, sensor, samplingPeriodUs, 0, handler);
	}

	public boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs, int maxReportLatencyUs) {
		return registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs, null);
	}

	public boolean registerListener(final SensorEventListener listener, final Sensor sensor, int samplingPeriodUs, int maxReportLatencyUs, Handler handler) {
		if (listener == null || sensor == null) {
			return false;
		}
		if (sensor.getType() == Sensor.TYPE_SIGNIFICANT_MOTION) {
			throw new IllegalArgumentException("Trigger Sensors should use the requestTriggerSensor.");
		}
		if (getDefaultSensor(sensor.getType()) == null) {
			return false;
		}
		for (Registration r : sRegistrations) {
			if (r.listener == listener && r.sensor.getType() == sensor.getType())
				return true; // already registered for this sensor
		}
		final Handler h = handler != null ? handler : new Handler(android.os.Looper.myLooper() != null ? android.os.Looper.myLooper() : android.os.Looper.getMainLooper());
		switch (sensor.getType()) {
			case Sensor.TYPE_ORIENTATION: {
				final Registration[] holder = new Registration[1];
				LocationListener ll = new LocationListener() {
					@Override
					public void onLocationChanged(Location location) {
						if (holder[0] != null)
							holder[0].deliver(new SensorEvent(new float[] { location.getBearing(), 0f, 0f }, sensor));
					}
				};
				holder[0] = new Registration(listener, sensor, h, ll);
				sRegistrations.add(holder[0]);
				long minTime = samplingPeriodUs > 3 ? samplingPeriodUs / 1000 : 0;
				new LocationManager().requestLocationUpdates(LocationManager.GPS_PROVIDER, minTime, 0, ll, android.os.Looper.getMainLooper());
				return true;
			}
			case Sensor.TYPE_ACCELEROMETER: {
				final Registration r = new Registration(listener, sensor, h, null);
				sRegistrations.add(r);
				register_accelerometer_listener_native(new SensorEventListener() {
					@Override
					public void onSensorChanged(SensorEvent event) {
						r.deliver(event);
					}

					@Override
					public void onAccuracyChanged(Sensor s, int accuracy) {
						if (r.active)
							listener.onAccuracyChanged(s, accuracy);
					}
				}, sensor, samplingPeriodUs);
				return true;
			}
			default:
				return false;
		}
	}

	public void unregisterListener(SensorEventListener listener) {
		unregisterListener(listener, null);
	}

	public void unregisterListener(SensorEventListener listener, Sensor sensor) {
		if (listener == null)
			return;
		for (Registration r : sRegistrations) {
			if (r.listener == listener && (sensor == null || r.sensor.getType() == sensor.getType())) {
				r.active = false;
				if (r.locationListener != null)
					new LocationManager().removeUpdates(r.locationListener);
				sRegistrations.remove(r);
			}
		}
	}

	/** Flushes the FIFO of the sensors registered for this listener; events are delivered as they arrive here, so there is nothing buffered. */
	public boolean flush(SensorEventListener listener) {
		if (listener == null)
			return false;
		for (Registration r : sRegistrations) {
			if (r.listener == listener)
				return true;
		}
		return false;
	}

	public boolean requestTriggerSensor(TriggerEventListener listener, Sensor sensor) {
		return false;
	}

	public boolean cancelTriggerSensor(TriggerEventListener listener, Sensor sensor) {
		return false;
	}

	public void registerDynamicSensorCallback(DynamicSensorCallback callback) {}
	public void registerDynamicSensorCallback(DynamicSensorCallback callback, Handler handler) {}
	public void unregisterDynamicSensorCallback(DynamicSensorCallback callback) {}

	/** Callback for dynamic sensor connections (none here). */
	public static abstract class DynamicSensorCallback {
		public void onDynamicSensorConnected(Sensor sensor) {}
		public void onDynamicSensorDisconnected(Sensor sensor) {}
	}

	native void register_accelerometer_listener_native(SensorEventListener listener, Sensor sensor, int sampling_period);


	/* teslalinux: orientation math from AOSP SensorManager (pure Java) */
	public static final int AXIS_X = 1, AXIS_Y = 2, AXIS_Z = 3, AXIS_MINUS_X = AXIS_X | 0x80, AXIS_MINUS_Y = AXIS_Y | 0x80, AXIS_MINUS_Z = AXIS_Z | 0x80;

	public static boolean getRotationMatrix(float[] R, float[] I, float[] gravity, float[] geomagnetic) {
		float Ax = gravity[0], Ay = gravity[1], Az = gravity[2];
		final float normsqA = (Ax * Ax + Ay * Ay + Az * Az);
		final float g = 9.81f;
		final float freeFallGravitySquared = 0.01f * g * g;
		if (normsqA < freeFallGravitySquared) return false;
		final float Ex = geomagnetic[0], Ey = geomagnetic[1], Ez = geomagnetic[2];
		float Hx = Ey * Az - Ez * Ay, Hy = Ez * Ax - Ex * Az, Hz = Ex * Ay - Ey * Ax;
		final float normH = (float) Math.sqrt(Hx * Hx + Hy * Hy + Hz * Hz);
		if (normH < 0.1f) return false;
		final float invH = 1.0f / normH;
		Hx *= invH; Hy *= invH; Hz *= invH;
		final float invA = 1.0f / (float) Math.sqrt(Ax * Ax + Ay * Ay + Az * Az);
		Ax *= invA; Ay *= invA; Az *= invA;
		final float Mx = Ay * Hz - Az * Hy, My = Az * Hx - Ax * Hz, Mz = Ax * Hy - Ay * Hx;
		if (R != null) {
			if (R.length == 9) {
				R[0] = Hx; R[1] = Hy; R[2] = Hz;
				R[3] = Mx; R[4] = My; R[5] = Mz;
				R[6] = Ax; R[7] = Ay; R[8] = Az;
			} else if (R.length == 16) {
				R[0] = Hx; R[1] = Hy; R[2] = Hz; R[3] = 0;
				R[4] = Mx; R[5] = My; R[6] = Mz; R[7] = 0;
				R[8] = Ax; R[9] = Ay; R[10] = Az; R[11] = 0;
				R[12] = 0; R[13] = 0; R[14] = 0; R[15] = 1;
			}
		}
		if (I != null) {
			final float invE = 1.0f / (float) Math.sqrt(Ex * Ex + Ey * Ey + Ez * Ez);
			final float c = (Ex * Mx + Ey * My + Ez * Mz) * invE;
			final float s = (Ex * Ax + Ey * Ay + Ez * Az) * invE;
			if (I.length == 9) {
				I[0] = 1; I[1] = 0; I[2] = 0;
				I[3] = 0; I[4] = c; I[5] = s;
				I[6] = 0; I[7] = -s; I[8] = c;
			} else if (I.length == 16) {
				I[0] = 1; I[1] = 0; I[2] = 0;
				I[4] = 0; I[5] = c; I[6] = s;
				I[8] = 0; I[9] = -s; I[10] = c;
				I[3] = I[7] = I[11] = I[12] = I[13] = I[14] = 0; I[15] = 1;
			}
		}
		return true;
	}

	public static float[] getOrientation(float[] R, float[] values) {
		if (R.length == 9) {
			values[0] = (float) Math.atan2(R[1], R[4]);
			values[1] = (float) Math.asin(-R[7]);
			values[2] = (float) Math.atan2(-R[6], R[8]);
		} else {
			values[0] = (float) Math.atan2(R[1], R[5]);
			values[1] = (float) Math.asin(-R[9]);
			values[2] = (float) Math.atan2(-R[8], R[10]);
		}
		return values;
	}

	public static boolean remapCoordinateSystem(float[] inR, int X, int Y, float[] outR) {
		if (inR == outR) {
			final float[] temp = new float[16];
			if (remapCoordinateSystemImpl(inR, X, Y, temp)) { System.arraycopy(temp, 0, outR, 0, outR.length); return true; }
			return false;
		}
		return remapCoordinateSystemImpl(inR, X, Y, outR);
	}

	private static boolean remapCoordinateSystemImpl(float[] inR, int X, int Y, float[] outR) {
		final int length = outR.length;
		if (inR.length != length) return false;
		if ((X & 0x7C) != 0 || (Y & 0x7C) != 0) return false;
		if (((X & 0x3) == 0) || ((Y & 0x3) == 0)) return false;
		if ((X & 0x3) == (Y & 0x3)) return false;
		final int Z = X ^ Y;
		final int x = (X & 0x3) - 1, y = (Y & 0x3) - 1, z = (Z & 0x3) - 1;
		final int axis_y = (z + 1) % 3, axis_z = (z + 2) % 3;
		int Zf = Z;
		if (((x ^ axis_y) | (y ^ axis_z)) != 0) Zf ^= 0x80;
		final boolean sx = (X >= 0x80), sy = (Y >= 0x80), sz = (Zf >= 0x80);
		final int rowLength = ((length == 16) ? 4 : 3);
		for (int j = 0; j < 3; j++) {
			final int offset = j * rowLength;
			for (int i = 0; i < 3; i++) {
				if (x == i) outR[offset + i] = sx ? -inR[offset + 0] : inR[offset + 0];
				if (y == i) outR[offset + i] = sy ? -inR[offset + 1] : inR[offset + 1];
				if (z == i) outR[offset + i] = sz ? -inR[offset + 2] : inR[offset + 2];
			}
		}
		if (length == 16) { outR[3] = outR[7] = outR[11] = outR[12] = outR[13] = outR[14] = 0; outR[15] = 1; }
		return true;
	}

	public static void getRotationMatrixFromVector(float[] R, float[] rotationVector) {
		float q0, q1 = rotationVector[0], q2 = rotationVector[1], q3 = rotationVector[2];
		if (rotationVector.length >= 4) q0 = rotationVector[3];
		else { q0 = 1 - q1 * q1 - q2 * q2 - q3 * q3; q0 = (q0 > 0) ? (float) Math.sqrt(q0) : 0; }
		float sq_q1 = 2 * q1 * q1, sq_q2 = 2 * q2 * q2, sq_q3 = 2 * q3 * q3;
		float q1_q2 = 2 * q1 * q2, q3_q0 = 2 * q3 * q0, q1_q3 = 2 * q1 * q3, q2_q0 = 2 * q2 * q0, q2_q3 = 2 * q2 * q3, q1_q0 = 2 * q1 * q0;
		if (R.length == 9) {
			R[0] = 1 - sq_q2 - sq_q3; R[1] = q1_q2 - q3_q0; R[2] = q1_q3 + q2_q0;
			R[3] = q1_q2 + q3_q0; R[4] = 1 - sq_q1 - sq_q3; R[5] = q2_q3 - q1_q0;
			R[6] = q1_q3 - q2_q0; R[7] = q2_q3 + q1_q0; R[8] = 1 - sq_q1 - sq_q2;
		} else if (R.length == 16) {
			R[0] = 1 - sq_q2 - sq_q3; R[1] = q1_q2 - q3_q0; R[2] = q1_q3 + q2_q0; R[3] = 0;
			R[4] = q1_q2 + q3_q0; R[5] = 1 - sq_q1 - sq_q3; R[6] = q2_q3 - q1_q0; R[7] = 0;
			R[8] = q1_q3 - q2_q0; R[9] = q2_q3 + q1_q0; R[10] = 1 - sq_q1 - sq_q2; R[11] = 0;
			R[12] = R[13] = R[14] = 0; R[15] = 1;
		}
	}
	public static float getAltitude(float p0, float p) { return 44330.0f * (1.0f - (float) Math.pow(p / p0, 1.0f / 5.255f)); }
	public static float getInclination(float[] I) { return I.length == 9 ? (float) Math.atan2(I[5], I[4]) : (float) Math.atan2(I[6], I[5]); }
}
