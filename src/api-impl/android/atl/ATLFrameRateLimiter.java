package android.atl;

import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

/**
 * A frame limiter for an app's own render loop that lets frames come at the display's refresh rate
 * while the content moves, and at the app's own rate while it is still.
 *
 * <p>Apps written for phones often cap their own render loop to save the battery: they draw at most
 * so many frames a second, and more for a few seconds after a touch on their own view. On a car
 * display the content also moves without that view being touched: the car drives (its position
 * changes every fix), the driver pans and zooms the map through the car host, or taps the car
 * screen's controls (a route preview, the zoom buttons), and the app's own boost never sees those.
 * Android has no API through which a system tells an app's own limiter that; SurfaceFlinger's touch
 * boost (the display at its top rate for a while after the user touches the screen) is the platform's
 * own form of it, for the display's refresh rate. This class is that boost for an app's limiter: an
 * app's limiter class extends it (teslalinux replaces the limiter class of verified Waze versions
 * with one that extends this, tools/apps/waze-map-pacing.py "auto"), and the app's own rate holds
 * except within {@link #BOOST_MS} after ATL saw the content move ({@link #contentMoved}), when the
 * display's refresh rate does.
 *
 * <p>The content moves, for ATL, when: the car-app host's map surface is touched (pan, zoom, tap),
 * the car-app renderer sends the driver's input or a layout change, the app shows a new car template,
 * and a location fix reports the device moving (a speed of {@link #MOVING_MPS} or more, or, without
 * a speed, a fix that far from the last one a second later). The app's own boost (its higher rate
 * after a touch on its view) works as before: the faster of the two applies.
 *
 * <p>ATL_FRAME_RATE chooses the policy, read once when the class loads:
 * <ul>
 * <li>"boost" (the default): as above, the app's own rate, the display's for {@link #BOOST_MS} after
 *     the content moved;</li>
 * <li>"display": the display's refresh rate all the time (the app's own rate only where it is
 *     faster, and no limit where the app sets none), what the app's limiter would do with its
 *     battery-saving rate raised to the display's;</li>
 * <li>"app": the app's own limiter exactly, as if this class were not there: its rate and its own
 *     boosts, and nothing ATL sees moving changes it.</li>
 * </ul>
 * The policy is the device's choice at the app's start, not part of the class: the same subclass of
 * this one serves every policy, so the app's class path (and with it the code ART compiled for it,
 * whose class loader context names every dex in front of the app's own) stays the same whichever
 * one the device chooses.
 *
 * <p>The limiter's contract is the app's: {@link #isTooSoon} is asked before each frame, and the app
 * tells {@link #setFrameStarted} when it drew one; a rate of 0 or below means no limit.
 * ATL_FRAME_BOOST_MS sets the boost's length (milliseconds, 0 turns it off);
 * ATL_DEBUG_FRAME_RATE=1 logs every change of the rate in effect.
 */
public class ATLFrameRateLimiter {
	private static final String TAG = "ATLFrameRateLimiter";

	/** how long frames may come at the display's rate after the content moved (Waze's own touch boost is as long) */
	public static final long BOOST_MS = boostMs();
	/** a location fix this fast (m/s, 3.6 km/h) or faster moves the map */
	public static final float MOVING_MPS = 1.0f;

	/** ATL_FRAME_RATE: the display's rate after the content moved, else the app's own */
	public static final int POLICY_BOOST = 0;
	/** ATL_FRAME_RATE=display: the display's rate all the time */
	public static final int POLICY_DISPLAY = 1;
	/** ATL_FRAME_RATE=app: the app's own limiter, unchanged */
	public static final int POLICY_APP = 2;
	/** the policy in effect (ATL_FRAME_RATE) */
	public static final int POLICY = policy();

	private static final boolean DEBUG = "1".equals(System.getenv("ATL_DEBUG_FRAME_RATE"));

	/* the end of the current boost (SystemClock.uptimeMillis), and the display's frame interval then */
	private static volatile long boostUntil = Long.MIN_VALUE;
	private static volatile long boostIntervalMs = 16;
	private static volatile String lastReason;
	/* "display": the display's frame interval, asked again once a second (the compositor can change
	 * its rate) instead of before every frame */
	private static volatile long displayIntervalMs = -1;
	private static volatile long displayIntervalAt = Long.MIN_VALUE;

	/* the app's own interval in ms (-1: no limit), the start of the last frame drawn (elapsedRealtime) */
	private volatile long appIntervalMs;
	private volatile long lastFrameMs = SystemClock.elapsedRealtime();
	private volatile long debugInterval = Long.MIN_VALUE;

	/** with the app's own starting rate in frames a second (0 or below: no limit) */
	public ATLFrameRateLimiter(int appFramesPerSecond) {
		appIntervalMs = interval(appFramesPerSecond);
	}

	private static long boostMs() {
		String v = System.getenv("ATL_FRAME_BOOST_MS");
		if (v != null) {
			try {
				return Math.max(0, Long.parseLong(v.trim()));
			} catch (NumberFormatException e) {
				Log.w(TAG, "ATL_FRAME_BOOST_MS=" + v + " is not a number of milliseconds; using 5000");
			}
		}
		return 5000;
	}

	private static int policy() {
		String v = System.getenv("ATL_FRAME_RATE");
		if (v == null || v.isEmpty() || "boost".equals(v))
			return POLICY_BOOST;
		if ("display".equals(v))
			return POLICY_DISPLAY;
		if ("app".equals(v))
			return POLICY_APP;
		Log.w(TAG, "ATL_FRAME_RATE=" + v + " is not boost, display or app; using boost");
		return POLICY_BOOST;
	}

	private static long interval(int fps) {
		return fps > 0 ? 1000 / fps : -1;
	}

	/* the display's frame interval in ms, as its refresh rate is now */
	private static long displayInterval() {
		float rate = new Display().getRefreshRate();
		return rate > 0 ? (long)(1000 / rate) : 16;
	}

	private static long displayIntervalNow() {
		long now = SystemClock.uptimeMillis();
		if (displayIntervalMs < 0 || now - displayIntervalAt >= 1000) {
			displayIntervalMs = displayInterval();
			displayIntervalAt = now;
		}
		return displayIntervalMs;
	}

	/** the app sets its own rate (its battery-saving rate, its own boost after a touch on its view) */
	protected final void setAppFrameRate(int framesPerSecond) {
		appIntervalMs = interval(framesPerSecond);
	}

	/** the shortest time between frames now, in ms; -1 when frames are not limited */
	public final long frameIntervalMs() {
		long app = appIntervalMs;
		if (app < 0 || POLICY == POLICY_APP)
			return app;
		if (POLICY == POLICY_DISPLAY)
			return Math.min(app, displayIntervalNow());
		if (SystemClock.uptimeMillis() >= boostUntil)
			return app;
		return Math.min(app, boostIntervalMs);
	}

	/** whether a frame now would come sooner after the last one than the rate in effect allows */
	public final boolean isTooSoon() {
		long interval = frameIntervalMs();
		if (DEBUG && interval != debugInterval) {
			debugInterval = interval;
			Log.i(TAG, "frame interval " + interval + " ms (app's own " + appIntervalMs + " ms"
			      + (interval == appIntervalMs ? "" : POLICY == POLICY_DISPLAY ? ", the display's rate" : ", boosted: " + lastReason) + ")");
		}
		return interval >= 0 && SystemClock.elapsedRealtime() - lastFrameMs < interval;
	}

	/** the app started drawing a frame at this SystemClock.elapsedRealtime() */
	public final void setFrameStarted(long elapsedRealtimeMs) {
		lastFrameMs = elapsedRealtimeMs;
	}

	/**
	 * The content on the display moves or is about to (REASON says why, for the debug log): app
	 * limiters let frames come at the display's refresh rate for the next {@link #BOOST_MS}. Any
	 * thread.
	 *
	 * @hide
	 */
	public static void contentMoved(String reason) {
		/* only the boost policy boosts ("display" is at the display's rate anyway, "app" never) */
		if (BOOST_MS <= 0 || POLICY != POLICY_BOOST)
			return;
		long until = SystemClock.uptimeMillis() + BOOST_MS;
		if (until <= boostUntil)
			return;
		if (SystemClock.uptimeMillis() >= boostUntil) {
			/* a new boost: the display's rate as it is now (the compositor can change it) */
			boostIntervalMs = displayInterval();
		}
		lastReason = reason;
		boostUntil = until;
	}

	/**
	 * A location fix was delivered (LocationManager): it moves the map when the device moves. PREVIOUS
	 * is the provider's fix before it, or null.
	 *
	 * @hide
	 */
	public static void locationFix(android.location.Location fix, android.location.Location previous) {
		if (fix == null)
			return;
		if (fix.hasSpeed()) {
			if (fix.getSpeed() >= MOVING_MPS)
				contentMoved("location at " + fix.getSpeed() + " m/s");
			return;
		}
		if (previous == null)
			return;
		/* no speed: the distance covered since the previous fix, as a speed */
		long dt = (fix.getElapsedRealtimeNanos() - previous.getElapsedRealtimeNanos()) / 1000000;
		float distance = fix.distanceTo(previous);
		if (dt > 0 && distance * 1000 / dt >= MOVING_MPS)
			contentMoved("location moved " + distance + " m in " + dt + " ms");
	}
}
