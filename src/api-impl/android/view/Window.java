package android.view;

import android.R;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.transition.Transition;
import android.widget.FrameLayout;
import android.widget.Toolbar;

public class Window {
	public static final int FEATURE_OPTIONS_PANEL = 0;
	public static final int FEATURE_NO_TITLE = 1;

	public ViewTreeObserver view_tree_observer = null;
	private WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0, 0);

	public static interface Callback {
		public void onContentChanged();

		public abstract boolean onCreatePanelMenu(int featureId, Menu menu);

		public View onCreatePanelView(int featureId);

		public boolean onPreparePanel(int featureId, View view, Menu menu);

		public boolean onMenuItemSelected(int featureId, MenuItem item);

		public void onPanelClosed(int featureId, Menu menu);

		public boolean onMenuOpened(int featureId, Menu menu);
	
		/* teslalinux: AOSP Window.Callback members ATL lacked; defaults so ATL's own implementers keep compiling. */
		default boolean dispatchKeyEvent(KeyEvent event) { return false; }
		default boolean dispatchKeyShortcutEvent(KeyEvent event) { return false; }
		default boolean dispatchTouchEvent(MotionEvent event) { return false; }
		default boolean dispatchTrackballEvent(MotionEvent event) { return false; }
		default boolean dispatchGenericMotionEvent(MotionEvent event) { return false; }
		default void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {}
		default void onAttachedToWindow() {}
		default void onDetachedFromWindow() {}
		default void onWindowFocusChanged(boolean hasFocus) {}
		default boolean onSearchRequested() { return false; }
		default ActionMode onWindowStartingActionMode(ActionMode.Callback callback) { return null; }
		default ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) { return null; }
		default void onActionModeStarted(ActionMode mode) {}
		default void onActionModeFinished(ActionMode mode) {}
		default void onPointerCaptureChanged(boolean hasCapture) {}
}

	public static interface OnFrameMetricsAvailableListener {}

	public long native_window;
	private ViewGroup decorView;

	private Window.Callback callback;
	private Context context;

	public Window(Context context, Window.Callback callback) {
		this.callback = callback;
		this.context = context;
		decorView = new FrameLayout(context);
		decorView.setId(android.R.id.content);
	}

	public void set_native_window(long native_window) {
		this.native_window = native_window;
		set_jobject(native_window, this);
	}

	/* teslalinux: AOSP Window flag handling (the flags live in the window attributes). */
	private int mForcedWindowFlags = 0;

	public void addFlags(int flags) {
		setFlags(flags, flags);
	}

	public void clearFlags(int flags) {
		setFlags(0, flags);
	}

	public void setFlags(int flags, int mask) {
		final WindowManager.LayoutParams attrs = getAttributes();
		attrs.flags = (attrs.flags & ~mask) | (flags & mask);
		mForcedWindowFlags |= mask;
	}

	/** @hide AOSP: flags the app set explicitly through setFlags/addFlags/clearFlags. */
	public final int getForcedWindowFlags() {
		return mForcedWindowFlags;
	}

	/** @hide AOSP */
	public final boolean hasSoftInputMode() {
		return getAttributes().softInputMode != 0;
	}

	public final Callback getCallback() {
		return this.callback;
	}
	public void setCallback(Window.Callback callback) {
		this.callback = callback;
	}

	public void setContentView(View view) {
		if (decorView.getBackground() == null) {
			TypedArray ta = context.obtainStyledAttributes(new int[] {R.attr.windowBackground});
			if (ta.hasValue(0))
				setBackgroundDrawable(ta.getDrawable(0));
			ta.recycle();
		}
		decorView.removeAllViews();
		decorView.addView(view);
		if (view != null) {
			set_widget_as_root(native_window, decorView.widget);
		}
	}

	public View getDecorView() {
		return decorView;
	}

	public void takeInputQueue(InputQueue.Callback callback) {
		take_input_queue(native_window, callback, new InputQueue());
	}

	public boolean requestFeature(int featureId) {
		return false;
	}

	public View findViewById(int id) {
		if (id == com.android.internal.R.id.action_bar)
			return new Toolbar(context);
		return decorView.findViewById(id);
	}

	public View peekDecorView() {
		return null;
	}

	public WindowManager.LayoutParams getAttributes() {
		return params;
	}

	public void setBackgroundDrawable(Drawable drawable) {
		// HACK: disable transparent background for WhatsApp dialogs
		// For some unknown reason, the language picker in WhatsApp doesn't render the BottomSheet background currently.
		if (!"com.whatsapp".equals(context.getPackageName()))
			remove_gtk_background(native_window);
		decorView.setBackgroundDrawable(drawable);
	}

	public void setAttributes(WindowManager.LayoutParams params) {
		if (params.screenBrightness != -1)
			set_screen_brightness(params.screenBrightness);
		this.params = params;
		setLayout(params.width, params.height);
	}

	public void takeSurface(SurfaceHolder.Callback2 callback) {}

	/* teslalinux: AOSP keeps the bar colours in the window attributes (default from the theme, black here
	 * as there is no system bar to tint); the old placeholder answered a constant red that apps painted. */
	private int tlStatusBarColor = 0xFF000000;
	private int tlNavigationBarColor = 0xFF000000;

	public void setStatusBarColor(int color) {
		tlStatusBarColor = color;
	}

	public void setNavigationBarColor(int color) {
		tlNavigationBarColor = color;
	}

	public void setFormat(int format) {}

	public void setLayout(int width, int height) {
		params.width = width;
		params.height = height;
		set_layout(native_window, width, height);
	}

	public WindowManager getWindowManager() {
		return new WindowManagerImpl();
	}

	public void setSoftInputMode(int dummy) {}

	public int getNavigationBarColor() {
		return tlNavigationBarColor;
	}

	public void setBackgroundDrawableResource(int resId) {
		setBackgroundDrawable(context.getDrawable(resId));
	}

	public int getStatusBarColor() { return tlStatusBarColor; }

	public Context getContext() {
		return context;
	}

	public boolean hasFeature(int featureId) {
		return false;
	}

	public void setTitle(CharSequence title) {
		set_title(native_window, title != null ? title.toString() : context.getPackageName());
	}

	public Transition getSharedElementEnterTransition() {
		return new Transition();
	}

	public void setSharedElementExitTransition(Transition transition) {}

	public void setSharedElementReenterTransition(Transition transition) {}

	public void setSharedElementReturnTransition(Transition transition) {}

	public Transition getSharedElementExitTransition() {
		return new Transition();
	}

	public Transition getSharedElementReenterTransition() {
		return new Transition();
	}

	public void setReturnTransition(Transition transition) {}

	public void setEnterTransition(Transition transition) {}

	public void setGravity(int gravity) {}

	public void setDecorFitsSystemWindows(boolean fits) {}

	public WindowInsetsController getInsetsController() {
		return new InsetsController();
	}

	public void setStatusBarContrastEnforced(boolean enforced) {}

	public void setNavigationBarContrastEnforced(boolean enforced) {}

	public native void set_widget_as_root(long native_window, long widget);
	private native void set_title(long native_window, String title);
	public native void take_input_queue(long native_window, InputQueue.Callback callback, InputQueue queue);
	public native void set_layout(long native_window, int width, int height);
	private static native void set_jobject(long ptr, Window obj);
	private native void remove_gtk_background(long native_window);
	private native void set_screen_brightness(float brightness);

	/* teslalinux: Window additions */
	public void addOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener, android.os.Handler handler) {}
	public void removeOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener) {}
	@SuppressWarnings("unchecked")
	public <T extends View> T requireViewById(int id) {
		View v = findViewById(id);
		if (v == null) throw new IllegalArgumentException("ID does not reference a View inside this Window");
		return (T) v;
	}
	public void setBackgroundBlurRadius(int blurRadius) {}
	public void setDimAmount(float amount) {}
	public void setWindowAnimations(int resId) {}
	public boolean superDispatchKeyEvent(KeyEvent event) { return false; }
	public boolean superDispatchKeyShortcutEvent(KeyEvent event) { return false; }
	public boolean superDispatchTouchEvent(MotionEvent event) { return false; }
	public boolean superDispatchTrackballEvent(MotionEvent event) { return false; }
	public boolean superDispatchGenericMotionEvent(MotionEvent event) { return false; }
	public void setHideOverlayWindows(boolean hide) {}
	public boolean isNavigationBarContrastEnforced() { return false; }
	public boolean isStatusBarContrastEnforced() { return false; }
	public int getColorMode() { return 0; }
	public void setColorMode(int colorMode) {}
	public boolean isWideColorGamut() { return false; }
	public void setPreferMinimalPostProcessing(boolean isPreferred) {}
	public void setSustainedPerformanceMode(boolean enable) {}
	/** teslalinux: removes the decor from the window (a destroyed activity's window is gone in AOSP). */
	public native void tlRemoveRoot(long native_window, long widget);
}
