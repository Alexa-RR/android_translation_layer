package android.atl.carhost;

import java.util.Objects;

/**
 * One reading of the app's speedometer: the speed the app shows, the unit it shows it in, the
 * speed limit of the road it knows the car is on (in the same unit), and the speed in km/h. A car
 * head unit that draws a speedometer itself (the car page, over the app's map) shows these, the
 * way the app would have drawn them into its map.
 *
 * <p>There is no Android API through which an app tells its host its speedometer: an app draws
 * its own into the map surface. ATL takes the readings the app makes for itself instead: an app's
 * value class for a reading extends this class (teslalinux's launcher has a verified app version's
 * own reading class loaded as a subclass of this one, tools/apps/waze-speedometer.py), so every
 * reading the app makes is constructed here, and constructing one reports it to the car-app host
 * ({@link ATLCarHost#onSpeedometer}), which sends it to the renderers when it changed. The class
 * keeps the value semantics of such a reading: final fields, equals and hashCode over all four
 * (a speed of -0.0 km/h and 0.0 km/h differ, as Double.compare has it), a toString naming them.
 *
 * <p>A speed below 0 means the app has no speed (no position), a speed limit of 0 or below that it
 * knows none for the road, a speed in km/h below 0 that it has none.
 */
public class ATLCarSpeedometer {
	private final int speed;
	private final String units;
	private final int speedLimit;
	private final double speedKph;

	public ATLCarSpeedometer(int speed, String units, int speedLimit, double speedKph) {
		this.speed = speed;
		this.units = Objects.requireNonNull(units, "units");
		this.speedLimit = speedLimit;
		this.speedKph = speedKph;
		ATLCarHost.onSpeedometer(this);
	}

	/** the speed the app shows, in {@link #getUnits}; below 0: none */
	public final int getSpeed() {
		return speed;
	}

	/** the unit the app shows speeds in, as the app names it (for example "km/h", "mph") */
	public final String getUnits() {
		return units;
	}

	/** the road's speed limit in {@link #getUnits}; 0 or below: none known */
	public final int getSpeedLimit() {
		return speedLimit;
	}

	/** the speed in km/h; below 0: none */
	public final double getSpeedKph() {
		return speedKph;
	}

	/**
	 * whether the app's unit is miles per hour: from 10 on, the shown (whole) speed is the km/h
	 * one in miles (whatever language the unit is named in); slower, the unit's English name
	 */
	public final boolean isMiles() {
		if (speed >= 10 && speedKph > 0) {
			double ratio = speed / speedKph;
			return Math.abs(ratio - 0.621371) < Math.abs(ratio - 1.0);
		}
		return units.toLowerCase(java.util.Locale.ROOT).contains("mph");
	}

	/** the reading as the car-app channel's "speedometer" message (docs/UPSTREAM-NOTES.md) */
	final String toJson() {
		StringBuilder sb = new StringBuilder("{\"type\":\"speedometer\",\"speed\":").append(speed);
		sb.append(",\"units\":").append(org.json.JSONObject.quote(units));
		sb.append(",\"speedLimit\":").append(speedLimit);
		sb.append(",\"speedKph\":");
		if (Double.isNaN(speedKph) || Double.isInfinite(speedKph))
			sb.append(-1);
		else
			sb.append(Math.round(speedKph * 10) / 10.0);
		sb.append(",\"miles\":").append(isMiles());
		return sb.append('}').toString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || o.getClass() != getClass())
			return false;
		ATLCarSpeedometer r = (ATLCarSpeedometer)o;
		return speed == r.speed && units.equals(r.units) && speedLimit == r.speedLimit
		    && Double.compare(speedKph, r.speedKph) == 0;
	}

	@Override
	public int hashCode() {
		return (((speed * 31 + units.hashCode()) * 31 + speedLimit) * 31) + Double.hashCode(speedKph);
	}

	@Override
	public String toString() {
		return "Speedometer(speed=" + speed + ", units=" + units + ", speedLimit=" + speedLimit
		    + ", speedKph=" + speedKph + ")";
	}
}
