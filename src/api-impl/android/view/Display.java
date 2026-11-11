package android.view;

import android.atl.annotation.Export;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.DisplayMetrics;

@Export
public final class Display {

	public static int window_width = 960;
	public static int window_height = 540;

	/* teslalinux: a virtual display (DisplayManager.createVirtualDisplay) has its own id, name, size,
	 * density and flags; the default display (id 0) is the app window's. */
	private final int mDisplayId;
	private final boolean mVirtual;
	private final String mName;
	private final int mFlags;
	private volatile int mWidth, mHeight, mDensityDpi;
	private volatile boolean mOn = true;
	private volatile boolean mValid = true;

	/** the default display: the window the app runs in */
	public Display() {
		mDisplayId = DEFAULT_DISPLAY;
		mVirtual = false;
		mName = "atl";
		mFlags = 0;
	}

	private Display(int displayId, String name, int width, int height, int densityDpi, int flags, boolean on) {
		mDisplayId = displayId;
		mVirtual = true;
		mName = name;
		mFlags = flags;
		mWidth = width;
		mHeight = height;
		mDensityDpi = densityDpi;
		mOn = on;
	}

	/** @hide DisplayManager: a new virtual display (on while it has a surface, as AOSP's) */
	public static Display tlCreateVirtual(int displayId, String name, int width, int height, int densityDpi, int flags, boolean on) {
		return new Display(displayId, name, width, height, densityDpi, flags, on);
	}

	/** @hide VirtualDisplay.resize */
	public void tlResize(int width, int height, int densityDpi) {
		mWidth = width;
		mHeight = height;
		mDensityDpi = densityDpi;
	}

	/** @hide VirtualDisplay.setSurface: a virtual display is on while it has a surface */
	public void tlSetOn(boolean on) {
		mOn = on;
	}

	/** @hide VirtualDisplay.release: the display no longer exists */
	public void tlInvalidate() {
		mValid = false;
	}

	/**
	 * AOSP DisplayInfo.getMetricsWithSize: the size (the app window, which fills the display here),
	 * the display density and dpi; scaledDensity is the density (no font scale on Display metrics).
	 */
	public void getMetrics(DisplayMetrics outMetrics) {
		outMetrics.setToDefaults();
		if (mVirtual) {
			outMetrics.widthPixels = mWidth;
			outMetrics.heightPixels = mHeight;
			outMetrics.densityDpi = mDensityDpi;
			outMetrics.density = mDensityDpi / (float)DisplayMetrics.DENSITY_DEFAULT;
			outMetrics.scaledDensity = outMetrics.density;
			outMetrics.xdpi = mDensityDpi;
			outMetrics.ydpi = mDensityDpi;
			/* AOSP DisplayInfo.getMetricsWithSize: the compatibility-free values are the same */
			outMetrics.noncompatWidthPixels = outMetrics.widthPixels;
			outMetrics.noncompatHeightPixels = outMetrics.heightPixels;
			outMetrics.noncompatDensity = outMetrics.density;
			outMetrics.noncompatDensityDpi = outMetrics.densityDpi;
			outMetrics.noncompatScaledDensity = outMetrics.scaledDensity;
			outMetrics.noncompatXdpi = outMetrics.xdpi;
			outMetrics.noncompatYdpi = outMetrics.ydpi;
			return;
		}
		outMetrics.widthPixels = window_width;
		outMetrics.heightPixels = window_height;
	}

	/** AOSP: the full display; no area of either kind of display is reserved for system decorations */
	public void getRealMetrics(DisplayMetrics outMetrics) {
		getMetrics(outMetrics);
	}

	public int getWidth() {
		return mVirtual ? mWidth : window_width;
	}

	public int getHeight() {
		return mVirtual ? mHeight : window_height;
	}

	/** AOSP (hidden): the panel size; the same as the display size here (no rotation, no insets) */
	public int getRawWidth() {
		return getWidth();
	}

	public int getRawHeight() {
		return getHeight();
	}

	public int getRotation() {
		return 0 /*ROTATION_0*/;
	}

	/**
	 * AOSP: the refresh rate of the display's active mode. Here the monitor that shows the app window
	 * (native-vcore-display-refresh.sh); 60 Hz when the compositor announced no rate (the Wayland
	 * default) or before the native side has it. A virtual display runs at 60 Hz, as AOSP's.
	 */
	public float getRefreshRate() {
		if (mVirtual)
			return 60.0f;
		float rate = 0;
		try {
			rate = nativeGetRefreshRate();
		} catch (UnsatisfiedLinkError e) {
			// an api-impl.jar newer than the native library: no rate from the compositor
		}
		return rate > 0 ? rate : 60.0f;
	}
	private static native float nativeGetRefreshRate();

	public long getAppVsyncOffsetNanos() {
		return 0; // what else would we return here?
	}

	public int getDisplayId() {
		return mDisplayId;
	}

	public long getPresentationDeadlineNanos() {
		return 0; // what else...
	}

	public void getSize(Point size) {
		size.set(getWidth(), getHeight());
	}

	public void getRealSize(Point size) {
		getSize(size);
	}

	public void getRectSize(Rect rect) {
		rect.set(0, 0, getWidth(), getHeight());
	}

	/** AOSP: the display's cutout, null when the display has none (this one is a plain rectangle) */
	public DisplayCutout getCutout() {
		return null;
	}

	/* teslalinux: Display additions */
	public static final int STATE_UNKNOWN = 0, STATE_OFF = 1, STATE_ON = 2, STATE_DOZE = 3, STATE_DOZE_SUSPEND = 4, STATE_VR = 5, STATE_ON_SUSPEND = 6;
	/* AOSP Display flags */
	public static final int FLAG_SUPPORTS_PROTECTED_BUFFERS = 1 << 0;
	public static final int FLAG_SECURE = 1 << 1;
	public static final int FLAG_PRIVATE = 1 << 2;
	public static final int FLAG_PRESENTATION = 1 << 3;
	public static final int FLAG_ROUND = 1 << 4;
	public static final class Mode {
		private final int id, w, h; private final float rate;
		public Mode(int id, int w, int h, float rate) { this.id = id; this.w = w; this.h = h; this.rate = rate; }
		public int getModeId() { return id; }
		public int getPhysicalWidth() { return w; }
		public int getPhysicalHeight() { return h; }
		public float getRefreshRate() { return rate; }
		public float[] getAlternativeRefreshRates() { return new float[0]; }
		public int[] getSupportedHdrTypes() { return new int[0]; }
		@Override public boolean equals(Object o) { return o instanceof Mode && ((Mode) o).id == id; }
		@Override public int hashCode() { return id; }
	}
	public static final class HdrCapabilities {
		public static final int HDR_TYPE_DOLBY_VISION = 1, HDR_TYPE_HDR10 = 2, HDR_TYPE_HLG = 3, HDR_TYPE_HDR10_PLUS = 4;
		public int[] getSupportedHdrTypes() { return new int[0]; }
		public float getDesiredMaxLuminance() { return 500f; }
		public float getDesiredMaxAverageLuminance() { return 300f; }
		public float getDesiredMinLuminance() { return 0.1f; }
	}
	public Mode getMode() { return new Mode(1, getWidth(), getHeight(), getRefreshRate()); }
	public Mode[] getSupportedModes() { return new Mode[] { getMode() }; }
	/** AOSP: the default display is on; a virtual display is on while it has a surface */
	public int getState() { return mOn ? STATE_ON : STATE_OFF; }
	public boolean isHdr() { return false; }
	public HdrCapabilities getHdrCapabilities() { return new HdrCapabilities(); }
	public boolean isWideColorGamut() { return false; }
	public boolean isMinimalPostProcessingSupported() { return false; }
	public float[] getSupportedRefreshRates() { return new float[] { getRefreshRate() }; }
	public int getFlags() { return mFlags; }
	public String getName() { return mName; }
	/** AOSP: false once the display was removed (a released virtual display) */
	public boolean isValid() { return mValid; }
	/** Invalid display id (AOSP). */
	public static final int INVALID_DISPLAY = -1;

	/**
	 * AOSP: the display's rounded corner at a position (RoundedCorner.POSITION_*), or null when that
	 * corner is not rounded. The car screen is a browser viewport with square corners, so every
	 * position reports null, as AOSP does for a display without rounded corners.
	 */
	public RoundedCorner getRoundedCorner(int position) {
		if (position < RoundedCorner.POSITION_TOP_LEFT || position > RoundedCorner.POSITION_BOTTOM_LEFT)
			throw new IllegalArgumentException("unknown rounded corner position " + position);
		return null;
	}

	@Override
	public String toString() {
		return "Display id " + mDisplayId + ": \"" + mName + "\", " + getWidth() + " x " + getHeight()
		    + (mVirtual ? ", " + mDensityDpi + " dpi, virtual" : "") + (mValid ? "" : ", removed");
	}

	/** AOSP: the default display, the one the app runs on (getDisplayId() reports it) */
	public static final int DEFAULT_DISPLAY = 0;
}
