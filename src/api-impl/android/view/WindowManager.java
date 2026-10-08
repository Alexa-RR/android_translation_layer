package android.view;

import android.os.IBinder;

public interface WindowManager {
	public android.view.Display getDefaultDisplay();

	public void addView(View view, ViewGroup.LayoutParams params);

	public void updateViewLayout(View view, ViewGroup.LayoutParams params);

	public void removeView(View view);

	public void removeViewImmediate(View view);

	public class LayoutParams extends ViewGroup.LayoutParams {
		public static final int FLAG_KEEP_SCREEN_ON = 0;
		public static final int FLAG_DIM_BEHIND = 2;
		public static final int FLAG_NOT_FOCUSABLE = 8;

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

		public LayoutParams(int w, int h, int type, int flags, int format) {
			super(w, h);
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
}
