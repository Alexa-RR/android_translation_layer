package android.location;
import android.os.Bundle;

public class Location {

	private double latitude;
	private double longitude;
	private double altitude;
	private double accuracy;
	private double speed;
	private double bearing;
	private long timestamp;

	public Location(String provider) {}

	/* for internal use */
	public Location(double latitude,
	                double longitude,
	                double altitude,
	                double accuracy,
	                double speed,
	                double bearing,
	                long timestamp) {
		this.latitude = latitude;
		this.longitude = longitude;
		this.altitude = altitude;
		this.accuracy = accuracy;
		this.speed = speed;
		this.bearing = bearing;
		this.timestamp = timestamp;
	}

	public double getLatitude() {
		return latitude;
	}

	public void setLatitude(double latitude) {
		this.latitude = latitude;
	}

	public double getLongitude() {
		return longitude;
	}

	public void setLongitude(double longitude) {
		this.longitude = longitude;
	}

	public boolean hasAltitude() {
		return altitude != -Double.MAX_VALUE;
	}

	public double getAltitude() {
		return altitude;
	}

	public boolean hasAccuracy() {
		return true;
	}

	public float getAccuracy() {
		return (float)accuracy;
	}

	public boolean hasSpeed() {
		return speed != -1;
	}

	public float getSpeed() {
		return (float)speed;
	}

	public boolean hasBearing() {
		return bearing != -1;
	}

	public float getBearing() {
		return (float)bearing;
	}

	public long getTime() {
		return timestamp;
	}

	public String getProvider() {
		return "fused";
	}


	/* teslalinux: the rest of android.location.Location (API 26-34) as used by Waze */
	private float tlVerticalAccuracy, tlSpeedAccuracy, tlBearingAccuracy;
	private boolean tlHasVerticalAccuracy, tlHasSpeedAccuracy, tlHasBearingAccuracy, tlMock;
	private long tlElapsedRealtimeNanos;
	private double tlElapsedRealtimeUncertaintyNanos = -1;
	private double tlMslAltitude; private boolean tlHasMslAltitude;
	private float tlMslAltitudeAccuracy; private boolean tlHasMslAltitudeAccuracy;
	private Bundle tlExtras;
	private String tlProvider;

	public Location(Location l) {
		this(l.latitude, l.longitude, l.altitude, l.accuracy, l.speed, l.bearing, l.timestamp);
		tlVerticalAccuracy = l.tlVerticalAccuracy; tlSpeedAccuracy = l.tlSpeedAccuracy; tlBearingAccuracy = l.tlBearingAccuracy;
		tlHasVerticalAccuracy = l.tlHasVerticalAccuracy; tlHasSpeedAccuracy = l.tlHasSpeedAccuracy; tlHasBearingAccuracy = l.tlHasBearingAccuracy;
		tlMock = l.tlMock; tlElapsedRealtimeNanos = l.tlElapsedRealtimeNanos; tlExtras = l.tlExtras; tlProvider = l.tlProvider;
	}
	public void set(Location l) {
		latitude = l.latitude; longitude = l.longitude; altitude = l.altitude; accuracy = l.accuracy; speed = l.speed; bearing = l.bearing; timestamp = l.timestamp;
		tlVerticalAccuracy = l.tlVerticalAccuracy; tlSpeedAccuracy = l.tlSpeedAccuracy; tlBearingAccuracy = l.tlBearingAccuracy;
		tlHasVerticalAccuracy = l.tlHasVerticalAccuracy; tlHasSpeedAccuracy = l.tlHasSpeedAccuracy; tlHasBearingAccuracy = l.tlHasBearingAccuracy;
		tlMock = l.tlMock; tlElapsedRealtimeNanos = l.tlElapsedRealtimeNanos; tlExtras = l.tlExtras; tlProvider = l.tlProvider;
	}
	public void reset() { latitude = 0; longitude = 0; altitude = 0; accuracy = 0; speed = 0; bearing = 0; timestamp = 0; tlHasVerticalAccuracy = tlHasSpeedAccuracy = tlHasBearingAccuracy = false; tlExtras = null; }
	public void setAltitude(double altitude) { this.altitude = altitude; }
	public void removeAltitude() { this.altitude = 0; }
	public void setBearing(float bearing) { this.bearing = bearing; }
	public void removeBearing() { this.bearing = 0; }
	public void setSpeed(float speed) { this.speed = speed; }
	public void removeSpeed() { this.speed = 0; }
	public void setTime(long time) { this.timestamp = time; }
	public void setProvider(String provider) { this.tlProvider = provider; }
	public Bundle getExtras() { return tlExtras; }
	public void setExtras(Bundle extras) { tlExtras = extras; }
	public boolean hasVerticalAccuracy() { return tlHasVerticalAccuracy; }
	public float getVerticalAccuracyMeters() { return tlVerticalAccuracy; }
	public void setVerticalAccuracyMeters(float v) { tlVerticalAccuracy = v; tlHasVerticalAccuracy = true; }
	public void removeVerticalAccuracy() { tlHasVerticalAccuracy = false; }
	public boolean hasSpeedAccuracy() { return tlHasSpeedAccuracy; }
	public float getSpeedAccuracyMetersPerSecond() { return tlSpeedAccuracy; }
	public void setSpeedAccuracyMetersPerSecond(float v) { tlSpeedAccuracy = v; tlHasSpeedAccuracy = true; }
	public void removeSpeedAccuracy() { tlHasSpeedAccuracy = false; }
	public boolean hasBearingAccuracy() { return tlHasBearingAccuracy; }
	public float getBearingAccuracyDegrees() { return tlBearingAccuracy; }
	public void setBearingAccuracyDegrees(float v) { tlBearingAccuracy = v; tlHasBearingAccuracy = true; }
	public void removeBearingAccuracy() { tlHasBearingAccuracy = false; }
	public long getElapsedRealtimeNanos() { return tlElapsedRealtimeNanos != 0 ? tlElapsedRealtimeNanos : timestamp * 1000000L; }
	public void setElapsedRealtimeNanos(long v) { tlElapsedRealtimeNanos = v; }
	public long getElapsedRealtimeMillis() { return getElapsedRealtimeNanos() / 1000000L; }
	public long getElapsedRealtimeAgeMillis() { return 0; }
	public boolean hasElapsedRealtimeUncertaintyNanos() { return tlElapsedRealtimeUncertaintyNanos >= 0; }
	public double getElapsedRealtimeUncertaintyNanos() { return tlElapsedRealtimeUncertaintyNanos; }
	public void setElapsedRealtimeUncertaintyNanos(double v) { tlElapsedRealtimeUncertaintyNanos = v; }
	public void removeElapsedRealtimeUncertaintyNanos() { tlElapsedRealtimeUncertaintyNanos = -1; }
	public boolean isFromMockProvider() { return tlMock; }
	public boolean isMock() { return tlMock; }
	public void setMock(boolean mock) { tlMock = mock; }
	public boolean hasMslAltitude() { return tlHasMslAltitude; }
	public double getMslAltitudeMeters() { return tlMslAltitude; }
	public void setMslAltitudeMeters(double v) { tlMslAltitude = v; tlHasMslAltitude = true; }
	public void removeMslAltitude() { tlHasMslAltitude = false; }
	public boolean hasMslAltitudeAccuracy() { return tlHasMslAltitudeAccuracy; }
	public float getMslAltitudeAccuracyMeters() { return tlMslAltitudeAccuracy; }
	public void setMslAltitudeAccuracyMeters(float v) { tlMslAltitudeAccuracy = v; tlHasMslAltitudeAccuracy = true; }
	public void removeMslAltitudeAccuracy() { tlHasMslAltitudeAccuracy = false; }
	public void dump(android.util.Printer pw, String prefix) {}

	public static void distanceBetween(double startLatitude, double startLongitude, double endLatitude, double endLongitude, float[] results) {
		double R = 6371008.8;
		double p1 = Math.toRadians(startLatitude), p2 = Math.toRadians(endLatitude);
		double dp = p2 - p1, dl = Math.toRadians(endLongitude - startLongitude);
		double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
		double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
		results[0] = (float) (R * c);
		if (results.length > 1) {
			double y = Math.sin(dl) * Math.cos(p2);
			double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
			results[1] = (float) Math.toDegrees(Math.atan2(y, x));
			if (results.length > 2) {
				double y2 = Math.sin(-dl) * Math.cos(p1);
				double x2 = Math.cos(p2) * Math.sin(p1) - Math.sin(p2) * Math.cos(p1) * Math.cos(-dl);
				results[2] = (float) ((Math.toDegrees(Math.atan2(y2, x2)) + 180) % 360);
			}
		}
	}
	public float distanceTo(Location dest) { float[] r = new float[1]; distanceBetween(latitude, longitude, dest.latitude, dest.longitude, r); return r[0]; }
	public float bearingTo(Location dest) { float[] r = new float[2]; distanceBetween(latitude, longitude, dest.latitude, dest.longitude, r); return r[1]; }
	public static String convert(double coordinate, int outputType) { return Double.toString(coordinate); }
	public static double convert(String coordinate) { return Double.parseDouble(coordinate); }

	public static final android.os.Parcelable.Creator<Location> CREATOR = new android.os.Parcelable.Creator<Location>() {
		public Location createFromParcel(android.os.Parcel in) { return new Location("gps"); }
		public Location[] newArray(int size) { return new Location[size]; }
	};
	public int describeContents() { return 0; }
	public void writeToParcel(android.os.Parcel parcel, int flags) {}
}
