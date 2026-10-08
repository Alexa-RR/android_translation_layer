package android.view;

import android.os.IBinder;

public interface WindowManager {
	public android.view.Display getDefaultDisplay();

	public void addView(View view, ViewGroup.LayoutParams params);

	public void updateViewLayout(View view, ViewGroup.LayoutParams params);

	public void removeView(View view);

	public void removeViewImmediate(View view);

	public class LayoutParams extends ViewGroup.LayoutParams {
		/* teslalinux: AOSP WindowManager.LayoutParams flag values (ATL had FLAG_KEEP_SCREEN_ON as 0,
		 * which made addFlags(FLAG_KEEP_SCREEN_ON) a no-op and indistinguishable from "no flag"). */
		public static final int FLAG_ALLOW_LOCK_WHILE_SCREEN_ON = 0x00000001;
		public static final int FLAG_DIM_BEHIND = 0x00000002;
		public static final int FLAG_BLUR_BEHIND = 0x00000004;
		public static final int FLAG_NOT_FOCUSABLE = 0x00000008;
		public static final int FLAG_NOT_TOUCHABLE = 0x00000010;
		public static final int FLAG_NOT_TOUCH_MODAL = 0x00000020;
		public static final int FLAG_TOUCHABLE_WHEN_WAKING = 0x00000040;
		public static final int FLAG_KEEP_SCREEN_ON = 0x00000080;
		public static final int FLAG_LAYOUT_IN_SCREEN = 0x00000100;
		public static final int FLAG_LAYOUT_NO_LIMITS = 0x00000200;
		public static final int FLAG_FULLSCREEN = 0x00000400;
		public static final int FLAG_FORCE_NOT_FULLSCREEN = 0x00000800;
		public static final int FLAG_DITHER = 0x00001000;
		public static final int FLAG_SECURE = 0x00002000;
		public static final int FLAG_SCALED = 0x00004000;
		public static final int FLAG_IGNORE_CHEEK_PRESSES = 0x00008000;
		public static final int FLAG_LAYOUT_INSET_DECOR = 0x00010000;
		public static final int FLAG_ALT_FOCUSABLE_IM = 0x00020000;
		public static final int FLAG_WATCH_OUTSIDE_TOUCH = 0x00040000;
		public static final int FLAG_SHOW_WHEN_LOCKED = 0x00080000;
		public static final int FLAG_SHOW_WALLPAPER = 0x00100000;
		public static final int FLAG_TURN_SCREEN_ON = 0x00200000;
		public static final int FLAG_DISMISS_KEYGUARD = 0x00400000;
		public static final int FLAG_SPLIT_TOUCH = 0x00800000;
		public static final int FLAG_HARDWARE_ACCELERATED = 0x01000000;
		public static final int FLAG_LAYOUT_IN_OVERSCAN = 0x02000000;
		public static final int FLAG_TRANSLUCENT_STATUS = 0x04000000;
		public static final int FLAG_TRANSLUCENT_NAVIGATION = 0x08000000;
		public static final int FLAG_LOCAL_FOCUS_MODE = 0x10000000;
		public static final int FLAG_SLIPPERY = 0x20000000;
		public static final int FLAG_LAYOUT_ATTACHED_IN_DECOR = 0x40000000;
		public static final int FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS = 0x80000000;

		public float screenBrightness = -1;
		public int softInputMode;
		public int x;
		public int y;
		public int windowAnimations;
		public int flags;
		public float alpha;
		public int type;
		public IBinder token;
		public int format;
		public int layoutInDisplayCutoutMode;
		public String packageName;

		public LayoutParams(int w, int h, int _type, int _flags, int _format) {
			super(w, h);
			type = _type;
			flags = _flags;
			format = _format;
		}

		public LayoutParams(int w, int h, int xpos, int ypos, int _type, int _flags, int _format) {
			super(w, h);
			x = xpos;
			y = ypos;
			type = _type;
			flags = _flags;
			format = _format;
		}

		public LayoutParams() {}

		public void setTitle(CharSequence title) {}
	
		/* teslalinux: WindowManager.LayoutParams additions */
		public int preferredDisplayModeId;
		public float preferredRefreshRate;
		public int blurBehindRadius;
		public float dimAmount = 1.0f;
		public int gravity;
		public void setBlurBehindRadius(int r) { blurBehindRadius = r; }
		public int getBlurBehindRadius() { return blurBehindRadius; }
		public void setFitInsetsSides(int sides) {}
		public void setFitInsetsTypes(int types) {}
		public void setFitInsetsIgnoringVisibility(boolean ignore) {}
		public int getFitInsetsSides() { return 15; }
		public int getFitInsetsTypes() { return 7; }
		public boolean isFitInsetsIgnoringVisibility() { return false; }
		public int copyFrom(LayoutParams o) { width = o.width; height = o.height; preferredDisplayModeId = o.preferredDisplayModeId; return 0; }
		public void setColorMode(int colorMode) {}
		public int getColorMode() { return 0; }
}

	/* teslalinux: WindowManager additions (default methods: WindowManagerImpl keeps compiling) */
	default WindowMetrics getCurrentWindowMetrics() {
		Display d = getDefaultDisplay();
		return new WindowMetrics(new android.graphics.Rect(0, 0, d.getWidth(), d.getHeight()), new WindowInsets());
	}
	default WindowMetrics getMaximumWindowMetrics() { return getCurrentWindowMetrics(); }
	default boolean isCrossWindowBlurEnabled() { return false; }
	default void addCrossWindowBlurEnabledListener(java.util.function.Consumer<Boolean> listener) {}
	default void addCrossWindowBlurEnabledListener(java.util.concurrent.Executor executor, java.util.function.Consumer<Boolean> listener) {}
	default void removeCrossWindowBlurEnabledListener(java.util.function.Consumer<Boolean> listener) {}

	/** Thrown when a window token is invalid (AOSP). */
	public static class BadTokenException extends RuntimeException {
		public BadTokenException() {}
		public BadTokenException(String name) { super(name); }
	}

	/** Thrown when the display for a window is invalid (AOSP). */
	public static class InvalidDisplayException extends RuntimeException {
		public InvalidDisplayException() {}
		public InvalidDisplayException(String name) { super(name); }
	}
}
