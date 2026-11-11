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

		/* teslalinux (layout): the AOSP Window.Callback members ATL lacked */

		/** AOSP default: the callback adds no keyboard shortcuts of its own */
		default void onProvideKeyboardShortcuts(java.util.List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {}

		/**
		 * AOSP: the search key or a search request, with the event that caused it; Activity keeps the
		 * event for getSearchEvent and runs onSearchRequested(), which a callback without its own
		 * handling of the event does here.
		 */
		default boolean onSearchRequested(SearchEvent searchEvent) {
			return onSearchRequested();
		}

		/**
		 * AOSP: fills an accessibility event about the window (Activity: its title, class and
		 * package). A callback that does not describe its window returns false, as View does for a
		 * view with nothing to add.
		 */
		default boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
			return false;
		}
}

	public static interface OnFrameMetricsAvailableListener {}

	public long native_window;
	private ViewGroup decorView;

	private Window.Callback callback;
	private Context context;

	public Window(Context context, Window.Callback callback) {
		this.callback = callback;
		this.context = context;
		decorView = new TlDecorView(context);
		decorView.setId(android.R.id.content);
		// AOSP PhoneWindow: the decor applies the window's content insets policy (layout)
		decorView.setFitsSystemWindows(true);
		decorView.makeFrameworkOptionalFitsSystemWindows();
		tlRegisterWindow(this);
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
		tlContentParentExplicitlySet = true; // AOSP PhoneWindow.setContentView (layout)
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

	// (layout) requestFeature: see Window+layout.frag

	public View findViewById(int id) {
		if (id == com.android.internal.R.id.action_bar)
			return new Toolbar(context);
		return decorView.findViewById(id);
	}

	/** AOSP: the decor view if it was created; ATL creates it with the window. */
	public View peekDecorView() {
		return decorView;
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

	/** AOSP: the window soft input mode (WindowManager.LayoutParams.SOFT_INPUT_*). */
	public void setSoftInputMode(int dummy) {
		tlSoftInputMode = dummy;
	}

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

	// (layout) hasFeature: see Window+layout.frag

	public void setTitle(CharSequence title) {
		set_title(native_window, title != null ? title.toString() : context.getPackageName());
	}

	/* AOSP PhoneWindow: the activity transitions the app set; android.transition does not run them
	 * here (TODO-real.md), the values are kept for the app to read back. Unset shared element
	 * transitions read as an empty Transition, as before. */
	private Transition tlEnterTransition;
	private Transition tlReturnTransition;
	private Transition tlSharedElementEnterTransition;
	private Transition tlSharedElementExitTransition;
	private Transition tlSharedElementReenterTransition;
	private Transition tlSharedElementReturnTransition;

	public Transition getSharedElementEnterTransition() {
		return tlSharedElementEnterTransition != null ? tlSharedElementEnterTransition : new Transition();
	}

	public void setSharedElementExitTransition(Transition transition) {
		tlSharedElementExitTransition = transition;
	}

	public void setSharedElementReenterTransition(Transition transition) {
		tlSharedElementReenterTransition = transition;
	}

	public void setSharedElementReturnTransition(Transition transition) {
		tlSharedElementReturnTransition = transition;
	}

	public Transition getSharedElementExitTransition() {
		return tlSharedElementExitTransition != null ? tlSharedElementExitTransition : new Transition();
	}

	public Transition getSharedElementReenterTransition() {
		return tlSharedElementReenterTransition != null ? tlSharedElementReenterTransition : new Transition();
	}

	/** AOSP: the shared element return transition, the enter one when none was set */
	public Transition getSharedElementReturnTransition() {
		return tlSharedElementReturnTransition != null ? tlSharedElementReturnTransition : getSharedElementEnterTransition();
	}

	public void setReturnTransition(Transition transition) {
		tlReturnTransition = transition;
	}

	/** AOSP: the return transition, the enter one when none was set */
	public Transition getReturnTransition() {
		return tlReturnTransition != null ? tlReturnTransition : tlEnterTransition;
	}

	public void setEnterTransition(Transition transition) {
		tlEnterTransition = transition;
	}

	/** AOSP: the enter transition the app set, null when none (no theme transition is inflated) */
	public Transition getEnterTransition() {
		return tlEnterTransition;
	}

	public void setSharedElementEnterTransition(Transition transition) {
		tlSharedElementEnterTransition = transition;
	}

	// (layout) setGravity: see Window+layout.frag

	// (layout) setDecorFitsSystemWindows: see Window+layout.frag

	/** AOSP: the window's one insets controller (layout) */
	private InsetsController tlInsetsController;

	public WindowInsetsController getInsetsController() {
		if (tlInsetsController == null)
			tlInsetsController = new InsetsController(this);
		return tlInsetsController;
	}

	// (layout) setStatusBarContrastEnforced: see Window+layout.frag

	// (layout) setNavigationBarContrastEnforced: see Window+layout.frag

	public native void set_widget_as_root(long native_window, long widget);
	private native void set_title(long native_window, String title);
	public native void take_input_queue(long native_window, InputQueue.Callback callback, InputQueue queue);
	public native void set_layout(long native_window, int width, int height);
	private static native void set_jobject(long ptr, Window obj);
	private native void remove_gtk_background(long native_window);
	private native void set_screen_brightness(float brightness);

	/* teslalinux: Window additions */
	@SuppressWarnings("unchecked")
	public <T extends View> T requireViewById(int id) {
		View v = findViewById(id);
		if (v == null) throw new IllegalArgumentException("ID does not reference a View inside this Window");
		return (T) v;
	}
	public void setHideOverlayWindows(boolean hide) {}
	public int getColorMode() { return 0; }
	public void setColorMode(int colorMode) {}
	public boolean isWideColorGamut() { return false; }
	public void setPreferMinimalPostProcessing(boolean isPreferred) {}
	public void setSustainedPerformanceMode(boolean enable) {}
	/** teslalinux: removes the decor from the window (a destroyed activity's window is gone in AOSP). */
	public native void tlRemoveRoot(long native_window, long widget);
	private int tlSoftInputMode;

	public int tlGetSoftInputMode() {
		return tlSoftInputMode;
	}

	/* ---- teslalinux: the process's windows (tl_touch_mode) ----
	 * AOSP's WindowManagerGlobal knows every window root of the process (it reports a display-wide
	 * change such as touch mode to each ViewRootImpl). Windows are held weakly; a window whose
	 * activity or dialog is gone drops out once collected. */
	private static final java.util.ArrayList<java.lang.ref.WeakReference<Window>> tlWindows = new java.util.ArrayList<>();

	private static void tlRegisterWindow(Window window) {
		synchronized (tlWindows) {
			tlWindows.add(new java.lang.ref.WeakReference<>(window));
		}
	}

	/** the live windows of the process, oldest first */
	public static java.util.List<Window> tlLiveWindows() {
		java.util.ArrayList<Window> live = new java.util.ArrayList<>();
		synchronized (tlWindows) {
			for (java.util.Iterator<java.lang.ref.WeakReference<Window>> it = tlWindows.iterator(); it.hasNext();) {
				Window w = it.next().get();
				if (w == null)
					it.remove();
				else
					live.add(w);
			}
		}
		return live;
	}

	/* ---- teslalinux: the decor view answers for ViewRootImpl at the top of the chain ----
	 * AOSP's decor view has the window's ViewRootImpl as parent, and ViewRootImpl answers
	 * onDescendantInvalidated with invalidate(): the next frame redraws the window. Here an
	 * activity's decor is the root of a GTK window with no ViewParent above it (a window added
	 * through WindowManagerImpl gets that class's root parent instead), so the chain ended at the
	 * decor and nothing was redrawn. Compose moves and fades its layers (RenderNode position,
	 * translation, alpha: an AnimatedVisibility slide, a drawer) and reports only
	 * onDescendantInvalidated(ownerView, ownerView), so those changes showed only when something
	 * else redrew: Waze's menu flashed open at its end position and vanished at once on close. */
	private static final class TlDecorView extends FrameLayout {
		TlDecorView(Context context) {
			super(context);
		}

		@Override
		public void onDescendantInvalidated(View child, View target) {
			if (getParent() != null) {
				super.onDescendantInvalidated(child, target);
				return;
			}
			// ViewRootImpl.invalidate(): the target's widget is drawn again on the next frame
			if (target != null)
				target.tlQueueWindowRedraw();
			else if (child != null)
				child.tlQueueWindowRedraw();
		}
	}

	/* ---- teslalinux (layout): AOSP Window features, attributes, insets policy, frame metrics ----
	 * Copyright (C) 2006 The Android Open Source Project, Apache License 2.0 (logic ported from
	 * frameworks/base core/java/android/view/Window.java and PhoneWindow.java). */

	public static final int FEATURE_PROGRESS = 2;
	public static final int FEATURE_LEFT_ICON = 3;
	public static final int FEATURE_RIGHT_ICON = 4;
	public static final int FEATURE_INDETERMINATE_PROGRESS = 5;
	public static final int FEATURE_CONTEXT_MENU = 6;
	public static final int FEATURE_CUSTOM_TITLE = 7;
	public static final int FEATURE_ACTION_BAR = 8;
	public static final int FEATURE_ACTION_BAR_OVERLAY = 9;
	public static final int FEATURE_ACTION_MODE_OVERLAY = 10;
	public static final int FEATURE_SWIPE_TO_DISMISS = 11;
	public static final int FEATURE_CONTENT_TRANSITIONS = 12;
	public static final int FEATURE_ACTIVITY_TRANSITIONS = 13;
	/** @hide */
	public static final int FEATURE_MAX = FEATURE_ACTIVITY_TRANSITIONS;
	public static final int ID_ANDROID_CONTENT = android.R.id.content;
	/** AOSP PhoneWindow: the features a custom title can be combined with */
	private static final int CUSTOM_TITLE_COMPATIBLE_FEATURES = (1 << FEATURE_CUSTOM_TITLE) | (1 << FEATURE_CONTENT_TRANSITIONS) | (1 << FEATURE_ACTIVITY_TRANSITIONS) | (1 << FEATURE_ACTION_MODE_OVERLAY);

	/** AOSP Window.mFeatures / mLocalFeatures: from getDefaultFeatures, then requestFeature */
	private int tlFeatures = -1;
	private int tlLocalFeatures;
	/** AOSP PhoneWindow.mContentParentExplicitlySet */
	private boolean tlContentParentExplicitlySet;
	/** AOSP PhoneWindow.mDecorFitsSystemWindows (the content insets policy of the window) */
	private boolean tlDecorFitsSystemWindows = true;
	/** AOSP Window.mHaveDimAmount / mHaveWindowAnimations-style bookkeeping */
	private boolean tlHaveDimAmount;
	/** AOSP PhoneWindow.mBackgroundBlurRadius (the requested one) */
	private int tlBackgroundBlurRadius;
	private boolean tlStatusBarContrastEnforced = true;
	private boolean tlNavigationBarContrastEnforced = true;
	private final java.util.ArrayList<OnFrameMetricsAvailableListener> tlFrameMetricsListeners = new java.util.ArrayList<>();

	/** AOSP: the features every window of the app starts with (both configs are on for phones) */
	public static int getDefaultFeatures(Context context) {
		return (1 << FEATURE_OPTIONS_PANEL) | (1 << FEATURE_CONTEXT_MENU);
	}

	private void tlEnsureFeatures() {
		if (tlFeatures == -1) {
			tlFeatures = tlLocalFeatures = getDefaultFeatures(context);
		}
	}

	/**
	 * AOSP PhoneWindow.requestFeature: features are requested before the content is set; no title
	 * dominates the action bar, a custom title combines with few features.
	 */
	public boolean requestFeature(int featureId) {
		if (tlContentParentExplicitlySet) {
			throw new android.util.AndroidRuntimeException("requestFeature() must be called before adding content");
		}
		tlEnsureFeatures();
		final int features = getFeatures();
		final int newFeatures = features | (1 << featureId);
		if ((newFeatures & (1 << FEATURE_CUSTOM_TITLE)) != 0 && (newFeatures & ~CUSTOM_TITLE_COMPATIBLE_FEATURES) != 0) {
			// Another feature is enabled and the user is trying to enable the custom title feature
			// or custom title feature is enabled and the user is trying to enable another feature
			throw new android.util.AndroidRuntimeException("You cannot combine custom titles with other title features");
		}
		if ((features & (1 << FEATURE_NO_TITLE)) != 0 && featureId == FEATURE_ACTION_BAR) {
			return false; // Ignore. No title dominates.
		}
		if ((features & (1 << FEATURE_ACTION_BAR)) != 0 && featureId == FEATURE_NO_TITLE) {
			// Remove the action bar feature if we have no title. No title dominates.
			removeFeature(FEATURE_ACTION_BAR);
		}
		final int flag = 1 << featureId;
		tlFeatures |= flag;
		tlLocalFeatures |= flag;
		return (tlFeatures & flag) != 0;
	}

	/** AOSP */
	protected void removeFeature(int featureId) {
		tlEnsureFeatures();
		final int flag = 1 << featureId;
		tlFeatures &= ~flag;
		tlLocalFeatures &= ~flag;
	}

	/** AOSP: the enabled features (FEATURE_* bits) */
	protected final int getFeatures() {
		tlEnsureFeatures();
		return tlFeatures;
	}

	/** AOSP: the features requested on this window itself */
	protected final int getLocalFeatures() {
		tlEnsureFeatures();
		return tlLocalFeatures;
	}

	public boolean hasFeature(int feature) {
		return (getFeatures() & (1 << feature)) != 0;
	}

	/** @hide AOSP: the features any window may request (all of them) */
	public static int getAvailableFeatures() {
		return (1 << (FEATURE_MAX + 1)) - 1;
	}

	/** AOSP: the attributes changed; the callback (the activity or dialog) is told */
	protected void dispatchWindowAttributesChanged(WindowManager.LayoutParams attrs) {
		if (getCallback() != null)
			getCallback().onWindowAttributesChanged(attrs);
	}

	/** AOSP: how much the content behind the window is dimmed (with FLAG_DIM_BEHIND) */
	public void setDimAmount(float amount) {
		final WindowManager.LayoutParams attrs = getAttributes();
		attrs.dimAmount = amount;
		tlHaveDimAmount = true;
		dispatchWindowAttributesChanged(attrs);
	}

	/** @hide AOSP: back to the theme's dim amount */
	public void clearDimAmount() {
		tlHaveDimAmount = false;
	}

	/** @hide AOSP */
	public boolean tlHaveDimAmount() {
		return tlHaveDimAmount;
	}

	/** AOSP: the window's enter/exit animation style */
	public void setWindowAnimations(int resId) {
		final WindowManager.LayoutParams attrs = getAttributes();
		attrs.windowAnimations = resId;
		dispatchWindowAttributesChanged(attrs);
	}

	/** AOSP: where a window smaller than the screen sits */
	public void setGravity(int gravity) {
		final WindowManager.LayoutParams attrs = getAttributes();
		attrs.gravity = gravity;
		dispatchWindowAttributesChanged(attrs);
	}

	/**
	 * AOSP PhoneWindow: the blur radius behind the window's background. DecorView applies it only
	 * while cross-window blur is enabled, which it never is here (WindowManager
	 * isCrossWindowBlurEnabled: no compositor blur), so the requested radius is kept and the
	 * applied one stays 0, as on a device without blur support.
	 */
	public void setBackgroundBlurRadius(int blurRadius) {
		tlBackgroundBlurRadius = blurRadius;
	}

	/** @hide the requested background blur radius */
	public int tlGetBackgroundBlurRadius() {
		return tlBackgroundBlurRadius;
	}

	/**
	 * AOSP: whether the framework fits the content to the system bars (true) or the app handles
	 * the insets itself (false, edge to edge); the views see the change through a new insets
	 * dispatch.
	 */
	public void setDecorFitsSystemWindows(boolean decorFitsSystemWindows) {
		if (tlDecorFitsSystemWindows == decorFitsSystemWindows)
			return;
		tlDecorFitsSystemWindows = decorFitsSystemWindows;
		getDecorView().requestApplyInsets();
	}

	/** @hide the window's content insets policy (AOSP ViewRootImpl content insets listener set) */
	public boolean tlDecorFitsSystemWindows() {
		return tlDecorFitsSystemWindows;
	}

	/**
	 * @hide The insets of this window: the car screen has no status bar, navigation bar, caption
	 * bar, gesture area or cutout, so every type is zero and none is visible (AOSP InsetsState of
	 * a window without inset sources); the display shape is the screen's rectangle.
	 */
	public WindowInsets tlGetWindowInsets() {
		return tlWindowInsetsFor(context);
	}

	/** @hide the window insets of a root without a Window (popups, overlays) */
	public static WindowInsets tlWindowInsetsFor(Context context) {
		final android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
		final android.graphics.Insets[] typeInsets = new android.graphics.Insets[WindowInsets.Type.SIZE];
		final android.graphics.Insets[] typeMaxInsets = new android.graphics.Insets[WindowInsets.Type.SIZE];
		java.util.Arrays.fill(typeInsets, android.graphics.Insets.NONE);
		java.util.Arrays.fill(typeMaxInsets, android.graphics.Insets.NONE);
		return new WindowInsets(typeInsets, typeMaxInsets, new boolean[WindowInsets.Type.SIZE],
		                        (context.getResources().getConfiguration().screenLayout & 0x300) == 0x200 /* SCREENLAYOUT_ROUND_YES */, false, 0,
		                        DisplayCutout.NO_CUTOUT, RoundedCorners.NO_ROUNDED_CORNERS, new PrivacyIndicatorBounds(),
		                        DisplayShape.createDefaultDisplayShape(dm.widthPixels, dm.heightPixels, false),
		                        WindowInsets.Type.systemBars(), false);
	}

	/**
	 * AOSP: the listener gets the timing of every frame the window's HWUI renderer draws. This
	 * window is drawn by GTK, not by a ThreadedRenderer; AOSP keeps the listeners of such a window
	 * (a software-rendered one) registered without frames to report, which is the case here.
	 */
	public void addOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener, android.os.Handler handler) {
		final View decorView = getDecorView();
		if (decorView == null)
			throw new IllegalStateException("can't observe a Window without an attached view");
		if (listener == null)
			throw new NullPointerException("listener cannot be null");
		synchronized (tlFrameMetricsListeners) {
			tlFrameMetricsListeners.add(listener);
		}
	}

	public void removeOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener) {
		synchronized (tlFrameMetricsListeners) {
			if (!tlFrameMetricsListeners.remove(listener))
				throw new IllegalArgumentException("attempt to remove OnFrameMetricsAvailableListener that was never added");
		}
	}

	/**
	 * AOSP: whether the system draws a scrim behind a transparent status bar. There is no status
	 * bar on the car screen; the setting is kept for the app as AOSP keeps it.
	 */
	public void setStatusBarContrastEnforced(boolean ensureContrast) {
		tlStatusBarContrastEnforced = ensureContrast;
	}

	public boolean isStatusBarContrastEnforced() {
		return tlStatusBarContrastEnforced;
	}

	/** AOSP: as setStatusBarContrastEnforced, for the (absent) navigation bar */
	public void setNavigationBarContrastEnforced(boolean enforceContrast) {
		tlNavigationBarContrastEnforced = enforceContrast;
	}

	public boolean isNavigationBarContrastEnforced() {
		return tlNavigationBarContrastEnforced;
	}

	/** AOSP PhoneWindow.superDispatchKeyEvent: the decor view (and so the focused view) gets the key */
	public boolean superDispatchKeyEvent(KeyEvent event) {
		return decorView.dispatchKeyEvent(event);
	}

	/** AOSP PhoneWindow: the decor view gets the shortcut */
	public boolean superDispatchKeyShortcutEvent(KeyEvent event) {
		return decorView.dispatchKeyShortcutEvent(event);
	}

	/** AOSP PhoneWindow: the decor view dispatches the touch to the view tree */
	public boolean superDispatchTouchEvent(MotionEvent event) {
		return decorView.dispatchTouchEvent(event);
	}

	/** AOSP PhoneWindow */
	public boolean superDispatchTrackballEvent(MotionEvent event) {
		return decorView.dispatchTrackballEvent(event);
	}

	/** AOSP PhoneWindow */
	public boolean superDispatchGenericMotionEvent(MotionEvent event) {
		return decorView.dispatchGenericMotionEvent(event);
	}
}
