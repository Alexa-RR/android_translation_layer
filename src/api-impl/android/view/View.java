package android.view;

import android.R;
import android.animation.AnimatorInflater;
import android.animation.StateListAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.atl.GskCanvas;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.LayoutDirection;
import android.util.Property;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.animation.Animation;
import android.view.autofill.AutofillId;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.translation.ViewTranslationCallback;
import java.lang.CharSequence;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

class WindowId {}

public class View implements Drawable.Callback {

	// --- constants from android source

	/**
	 * The logging tag used by this class with android.util.Log.
	 */
	protected static final String TAG = "View";

	/**
	 * When set to true, apps will draw debugging information about their layouts.
	 *
	 * @hide
	 */
	public static final String DEBUG_LAYOUT_PROPERTY = "debug.layout";

	/**
	 * Used to mark a View that has no ID.
	 */
	public static final int NO_ID = -1;

	/**
	 * Signals that compatibility booleans have been initialized according to
	 * target SDK versions.
	 */
	private static boolean sCompatibilityDone = false;

	/**
	 * Use the old (broken) way of building MeasureSpecs.
	 */
	private static boolean sUseBrokenMakeMeasureSpec = false;

	/**
	 * Ignore any optimizations using the measure cache.
	 */
	private static boolean sIgnoreMeasureCache = false;

	/**
	 * This view does not want keystrokes. Use with TAKES_FOCUS_MASK when
	 * calling setFlags.
	 */
	public static final int NOT_FOCUSABLE = 0x00000000;

	/**
	 * This view wants keystrokes. Use with TAKES_FOCUS_MASK when calling
	 * setFlags.
	 */
	public static final int FOCUSABLE = 0x00000001;
	/** AOSP (API 26): the view decides itself; focusable when clickable (tl_focus_model) */
	public static final int FOCUSABLE_AUTO = 0x00000010;

	/**
	 * Mask for use with setFlags indicating bits used for focus.
	 */
	private static final int FOCUSABLE_MASK = 0x00000001;

	/**
	 * This view will adjust its padding to fit sytem windows (e.g. status bar)
	 */
	private static final int FITS_SYSTEM_WINDOWS = 0x00000002;

	/**
	 * This view is visible.
	 * Use with {@link #setVisibility} and <a href="#attr_android:visibility">{@code
	 * android:visibility}.
	 */
	public static final int VISIBLE = 0x00000000;

	/**
	 * This view is invisible, but it still takes up space for layout purposes.
	 * Use with {@link #setVisibility} and <a href="#attr_android:visibility">{@code
	 * android:visibility}.
	 */
	public static final int INVISIBLE = 0x00000004;

	/**
	 * This view is invisible, and it doesn't take any space for layout
	 * purposes. Use with {@link #setVisibility} and <a href="#attr_android:visibility">{@code
	 * android:visibility}.
	 */
	public static final int GONE = 0x00000008;

	/**
	 * Mask for use with setFlags indicating bits used for visibility.
	 * {@hide}
	 */
	static final int VISIBILITY_MASK = 0x0000000C;

	private static final int[] VISIBILITY_FLAGS = {VISIBLE, INVISIBLE, GONE};

	/**
	 * This view is enabled. Interpretation varies by subclass.
	 * Use with ENABLED_MASK when calling setFlags.
	 * {@hide}
	 */
	static final int ENABLED = 0x00000000;

	/**
	 * This view is disabled. Interpretation varies by subclass.
	 * Use with ENABLED_MASK when calling setFlags.
	 * {@hide}
	 */
	static final int DISABLED = 0x00000020;

	/**
	 * Mask for use with setFlags indicating bits used for indicating whether
	 * this view is enabled
	 * {@hide}
	 */
	static final int ENABLED_MASK = 0x00000020;

	/**
	 * This view won't draw. {@link #onDraw(android.graphics.Canvas)} won't be
	 * called and further optimizations will be performed. It is okay to have
	 * this flag set and a background. Use with DRAW_MASK when calling setFlags.
	 * {@hide}
	 */
	static final int WILL_NOT_DRAW = 0x00000080;

	/**
	 * Mask for use with setFlags indicating bits used for indicating whether
	 * this view is will draw
	 * {@hide}
	 */
	static final int DRAW_MASK = 0x00000080;

	/**
	 * <p>This view doesn't show scrollbars.</p>
	 * {@hide}
	 */
	static final int SCROLLBARS_NONE = 0x00000000;

	/**
	 * <p>This view shows horizontal scrollbars.</p>
	 * {@hide}
	 */
	static final int SCROLLBARS_HORIZONTAL = 0x00000100;

	/**
	 * <p>This view shows vertical scrollbars.</p>
	 * {@hide}
	 */
	static final int SCROLLBARS_VERTICAL = 0x00000200;

	/**
	 * <p>Mask for use with setFlags indicating bits used for indicating which
	 * scrollbars are enabled.</p>
	 * {@hide}
	 */
	static final int SCROLLBARS_MASK = 0x00000300;

	/**
	 * Indicates that the view should filter touches when its window is obscured.
	 * Refer to the class comments for more information about this security feature.
	 * {@hide}
	 */
	static final int FILTER_TOUCHES_WHEN_OBSCURED = 0x00000400;

	/**
	 * Set for framework elements that use FITS_SYSTEM_WINDOWS, to indicate
	 * that they are optional and should be skipped if the window has
	 * requested system UI flags that ignore those insets for layout.
	 */
	static final int OPTIONAL_FITS_SYSTEM_WINDOWS = 0x00000800;

	/**
	 * <p>This view doesn't show fading edges.</p>
	 * {@hide}
	 */
	static final int FADING_EDGE_NONE = 0x00000000;

	/**
	 * <p>This view shows horizontal fading edges.</p>
	 * {@hide}
	 */
	static final int FADING_EDGE_HORIZONTAL = 0x00001000;

	/**
	 * <p>This view shows vertical fading edges.</p>
	 * {@hide}
	 */
	static final int FADING_EDGE_VERTICAL = 0x00002000;

	/**
	 * <p>Mask for use with setFlags indicating bits used for indicating which
	 * fading edges are enabled.</p>
	 * {@hide}
	 */
	static final int FADING_EDGE_MASK = 0x00003000;

	/**
	 * <p>Indicates this view can be clicked. When clickable, a View reacts
	 * to clicks by notifying the OnClickListener.<p>
	 * {@hide}
	 */
	static final int CLICKABLE = 0x00004000;

	/**
	 * <p>Indicates this view is caching its drawing into a bitmap.</p>
	 * {@hide}
	 */
	static final int DRAWING_CACHE_ENABLED = 0x00008000;

	/**
	 * <p>Indicates that no icicle should be saved for this view.<p>
	 * {@hide}
	 */
	static final int SAVE_DISABLED = 0x000010000;

	/**
	 * <p>Mask for use with setFlags indicating bits used for the saveEnabled
	 * property.</p>
	 * {@hide}
	 */
	static final int SAVE_DISABLED_MASK = 0x000010000;

	/**
	 * <p>Indicates that no drawing cache should ever be created for this view.<p>
	 * {@hide}
	 */
	static final int WILL_NOT_CACHE_DRAWING = 0x000020000;

	/**
	 * <p>Indicates this view can take / keep focus when int touch mode.</p>
	 * {@hide}
	 */
	static final int FOCUSABLE_IN_TOUCH_MODE = 0x00040000;

	/**
	 * <p>Enables low quality mode for the drawing cache.</p>
	 */
	public static final int DRAWING_CACHE_QUALITY_LOW = 0x00080000;

	/**
	 * <p>Enables high quality mode for the drawing cache.</p>
	 */
	public static final int DRAWING_CACHE_QUALITY_HIGH = 0x00100000;

	/**
	 * <p>Enables automatic quality mode for the drawing cache.</p>
	 */
	public static final int DRAWING_CACHE_QUALITY_AUTO = 0x00000000;

	private static final int[] DRAWING_CACHE_QUALITY_FLAGS = {
		DRAWING_CACHE_QUALITY_AUTO, DRAWING_CACHE_QUALITY_LOW, DRAWING_CACHE_QUALITY_HIGH
	};

	/**
	 * <p>Mask for use with setFlags indicating bits used for the cache
	 * quality property.</p>
	 * {@hide}
	 */
	static final int DRAWING_CACHE_QUALITY_MASK = 0x00180000;

	/**
	 * <p>
	 * Indicates this view can be long clicked. When long clickable, a View
	 * reacts to long clicks by notifying the OnLongClickListener or showing a
	 * context menu.
	 * </p>
	 * {@hide}
	 */
	static final int LONG_CLICKABLE = 0x00200000;

	/**
	 * <p>Indicates that this view gets its drawable states from its direct parent
	 * and ignores its original internal states.</p>
	 *
	 * @hide
	 */
	static final int DUPLICATE_PARENT_STATE = 0x00400000;

	/**
	 * The scrollbar style to display the scrollbars inside the content area,
	 * without increasing the padding. The scrollbars will be overlaid with
	 * translucency on the view's content.
	 */
	public static final int SCROLLBARS_INSIDE_OVERLAY = 0;

	/**
	 * The scrollbar style to display the scrollbars inside the padded area,
	 * increasing the padding of the view. The scrollbars will not overlap the
	 * content area of the view.
	 */
	public static final int SCROLLBARS_INSIDE_INSET = 0x01000000;

	/**
	 * The scrollbar style to display the scrollbars at the edge of the view,
	 * without increasing the padding. The scrollbars will be overlaid with
	 * translucency.
	 */
	public static final int SCROLLBARS_OUTSIDE_OVERLAY = 0x02000000;

	/**
	 * The scrollbar style to display the scrollbars at the edge of the view,
	 * increasing the padding of the view. The scrollbars will only overlap the
	 * background, if any.
	 */
	public static final int SCROLLBARS_OUTSIDE_INSET = 0x03000000;

	/**
	 * Mask to check if the scrollbar style is overlay or inset.
	 * {@hide}
	 */
	static final int SCROLLBARS_INSET_MASK = 0x01000000;

	/**
	 * Mask to check if the scrollbar style is inside or outside.
	 * {@hide}
	 */
	static final int SCROLLBARS_OUTSIDE_MASK = 0x02000000;

	/**
	 * Mask for scrollbar style.
	 * {@hide}
	 */
	static final int SCROLLBARS_STYLE_MASK = 0x03000000;

	/**
	 * View flag indicating that the screen should remain on while the
	 * window containing this view is visible to the user.  This effectively
	 * takes care of automatically setting the WindowManager's
	 * {@link WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON}.
	 */
	public static final int KEEP_SCREEN_ON = 0x04000000;

	/**
	 * View flag indicating whether this view should have sound effects enabled
	 * for events such as clicking and touching.
	 */
	public static final int SOUND_EFFECTS_ENABLED = 0x08000000;

	/**
	 * View flag indicating whether this view should have haptic feedback
	 * enabled for events such as long presses.
	 */
	public static final int HAPTIC_FEEDBACK_ENABLED = 0x10000000;

	/**
	 * <p>Indicates that the view hierarchy should stop saving state when
	 * it reaches this view.  If state saving is initiated immediately at
	 * the view, it will be allowed.
	 * {@hide}
	 */
	static final int PARENT_SAVE_DISABLED = 0x20000000;

	/**
	 * <p>Mask for use with setFlags indicating bits used for PARENT_SAVE_DISABLED.</p>
	 * {@hide}
	 */
	static final int PARENT_SAVE_DISABLED_MASK = 0x20000000;

	/**
	 * View flag indicating whether {@link #addFocusables(ArrayList, int, int)}
	 * should add all focusable Views regardless if they are focusable in touch mode.
	 */
	public static final int FOCUSABLES_ALL = 0x00000000;

	/**
	 * View flag indicating whether {@link #addFocusables(ArrayList, int, int)}
	 * should add only Views focusable in touch mode.
	 */
	public static final int FOCUSABLES_TOUCH_MODE = 0x00000001;

	/**
	 * Use with {@link #focusSearch(int)}. Move focus to the previous selectable
	 * item.
	 */
	public static final int FOCUS_BACKWARD = 0x00000001;

	/**
	 * Use with {@link #focusSearch(int)}. Move focus to the next selectable
	 * item.
	 */
	public static final int FOCUS_FORWARD = 0x00000002;

	/**
	 * Use with {@link #focusSearch(int)}. Move focus to the left.
	 */
	public static final int FOCUS_LEFT = 0x00000011;

	/**
	 * Use with {@link #focusSearch(int)}. Move focus up.
	 */
	public static final int FOCUS_UP = 0x00000021;

	/**
	 * Use with {@link #focusSearch(int)}. Move focus to the right.
	 */
	public static final int FOCUS_RIGHT = 0x00000042;

	/**
	 * Use with {@link #focusSearch(int)}. Move focus down.
	 */
	public static final int FOCUS_DOWN = 0x00000082;

	/**
	 * Bits of {@link #getMeasuredWidthAndState()} and
	 * {@link #getMeasuredWidthAndState()} that provide the actual measured size.
	 */
	public static final int MEASURED_SIZE_MASK = 0x00ffffff;

	/**
	 * Bits of {@link #getMeasuredWidthAndState()} and
	 * {@link #getMeasuredWidthAndState()} that provide the additional state bits.
	 */
	public static final int MEASURED_STATE_MASK = 0xff000000;

	/**
	 * Bit shift of {@link #MEASURED_STATE_MASK} to get to the height bits
	 * for functions that combine both width and height into a single int,
	 * such as {@link #getMeasuredState()} and the childState argument of
	 * {@link #resolveSizeAndState(int, int, int)}.
	 */
	public static final int MEASURED_HEIGHT_STATE_SHIFT = 16;

	/**
	 * Bit of {@link #getMeasuredWidthAndState()} and
	 * {@link #getMeasuredWidthAndState()} that indicates the measured size
	 * is smaller that the space the view would like to have.
	 */
	public static final int MEASURED_STATE_TOO_SMALL = 0x01000000;

	// --- and some flags

	/**
	 * Indicates that this view has reported that it can accept the current drag's content.
	 * Cleared when the drag operation concludes.
	 * @hide
	 */
	static final int PFLAG2_DRAG_CAN_ACCEPT = 0x00000001;

	/**
	 * Indicates that this view is currently directly under the drag location in a
	 * drag-and-drop operation involving content that it can accept.  Cleared when
	 * the drag exits the view, or when the drag operation concludes.
	 * @hide
	 */
	static final int PFLAG2_DRAG_HOVERED = 0x00000002;

	/**
	 * Horizontal layout direction of this view is from Left to Right.
	 * Use with {@link #setLayoutDirection}.
	 */
	public static final int LAYOUT_DIRECTION_LTR = LayoutDirection.LTR;

	/**
	 * Horizontal layout direction of this view is from Right to Left.
	 * Use with {@link #setLayoutDirection}.
	 */
	public static final int LAYOUT_DIRECTION_RTL = LayoutDirection.RTL;

	/**
	 * Horizontal layout direction of this view is inherited from its parent.
	 * Use with {@link #setLayoutDirection}.
	 */
	public static final int LAYOUT_DIRECTION_INHERIT = LayoutDirection.INHERIT;

	/**
	 * Horizontal layout direction of this view is from deduced from the default language
	 * script for the locale. Use with {@link #setLayoutDirection}.
	 */
	public static final int LAYOUT_DIRECTION_LOCALE = LayoutDirection.LOCALE;

	/**
	 * Bit shift to get the horizontal layout direction. (bits after DRAG_HOVERED)
	 * @hide
	 */
	static final int PFLAG2_LAYOUT_DIRECTION_MASK_SHIFT = 2;

	/**
	 * Mask for use with private flags indicating bits used for horizontal layout direction.
	 * @hide
	 */
	static final int PFLAG2_LAYOUT_DIRECTION_MASK = 0x00000003 << PFLAG2_LAYOUT_DIRECTION_MASK_SHIFT;

	/**
	 * Indicates whether the view horizontal layout direction has been resolved and drawn to the
	 * right-to-left direction.
	 * @hide
	 */
	static final int PFLAG2_LAYOUT_DIRECTION_RESOLVED_RTL = 4 << PFLAG2_LAYOUT_DIRECTION_MASK_SHIFT;

	/**
	 * Indicates whether the view horizontal layout direction has been resolved.
	 * @hide
	 */
	static final int PFLAG2_LAYOUT_DIRECTION_RESOLVED = 8 << PFLAG2_LAYOUT_DIRECTION_MASK_SHIFT;

	/**
	 * Mask for use with private flags indicating bits used for resolved horizontal layout direction.
	 * @hide
	 */
	static final int PFLAG2_LAYOUT_DIRECTION_RESOLVED_MASK = 0x0000000C << PFLAG2_LAYOUT_DIRECTION_MASK_SHIFT;

	/**
	 * Indicates no axis of view scrolling.
	 */
	public static final int SCROLL_AXIS_NONE = 0;

	/**
	 * Indicates scrolling along the horizontal axis.
	 */
	public static final int SCROLL_AXIS_HORIZONTAL = 1 << 0;

	/**
	 * Indicates scrolling along the vertical axis.
	 */
	public static final int SCROLL_AXIS_VERTICAL = 1 << 1;

	/**
	 * Always allow a user to over-scroll this view, provided it is a
	 * view that can scroll.
	 *
	 * @see #getOverScrollMode()
	 * @see #setOverScrollMode(int)
	 */
	public static final int OVER_SCROLL_ALWAYS = 0;

	/**
	 * Allow a user to over-scroll this view only if the content is large
	 * enough to meaningfully scroll, provided it is a view that can scroll.
	 *
	 * @see #getOverScrollMode()
	 * @see #setOverScrollMode(int)
	 */
	public static final int OVER_SCROLL_IF_CONTENT_SCROLLS = 1;

	/**
	 * Never allow a user to over-scroll this view.
	 *
	 * @see #getOverScrollMode()
	 * @see #setOverScrollMode(int)
	 */
	public static final int OVER_SCROLL_NEVER = 2;

	// --- apparently there's more...

	@Deprecated
	public static final int STATUS_BAR_HIDDEN = 1; // 0x1

	@Deprecated
	public static final int STATUS_BAR_VISIBLE = 0; // 0x0

	public static final int SYSTEM_UI_FLAG_FULLSCREEN = 4; // 0x4

	public static final int SYSTEM_UI_FLAG_HIDE_NAVIGATION = 2; // 0x2

	public static final int SYSTEM_UI_FLAG_IMMERSIVE = 2048; // 0x800

	public static final int SYSTEM_UI_FLAG_IMMERSIVE_STICKY = 4096; // 0x1000

	public static final int SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN = 1024; // 0x400

	public static final int SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION = 512; // 0x200

	public static final int SYSTEM_UI_FLAG_LAYOUT_STABLE = 256; // 0x100

	public static final int SYSTEM_UI_FLAG_LOW_PROFILE = 1; // 0x1

	public static final int SYSTEM_UI_FLAG_VISIBLE = 0; // 0x0

	public static final int SYSTEM_UI_LAYOUT_FLAGS = 1536; // 0x600

	public static final int TEXT_ALIGNMENT_CENTER = 4; // 0x4

	public static final int TEXT_ALIGNMENT_GRAVITY = 1; // 0x1

	public static final int TEXT_ALIGNMENT_INHERIT = 0; // 0x0

	public static final int TEXT_ALIGNMENT_TEXT_END = 3; // 0x3

	public static final int TEXT_ALIGNMENT_TEXT_START = 2; // 0x2

	public static final int TEXT_ALIGNMENT_VIEW_END = 6; // 0x6

	public static final int TEXT_ALIGNMENT_VIEW_START = 5; // 0x5

	public static final int TEXT_DIRECTION_ANY_RTL = 2; // 0x2

	public static final int TEXT_DIRECTION_FIRST_STRONG = 1; // 0x1

	public static final int TEXT_DIRECTION_INHERIT = 0; // 0x0

	public static final int TEXT_DIRECTION_LOCALE = 5; // 0x5

	public static final int TEXT_DIRECTION_LTR = 3; // 0x3

	public static final int TEXT_DIRECTION_RTL = 4; // 0x4

	public static final Property<View, Float> SCALE_X = new FloatProperty<View>("scaleX") {
		@Override
		public void setValue(View object, float value) {
			object.setScaleX(value);
		}
		@Override
		public Float get(View object) {
			return object.getScaleX();
		}
	};

	public static final Property<View, Float> SCALE_Y = new FloatProperty<View>("scaleY") {
		@Override
		public void setValue(View object, float value) {
			object.setScaleY(value);
		}
		@Override
		public Float get(View object) {
			return object.getScaleY();
		}
	};

	// --- end of constants from android source

	// --- interfaces from android source
	public interface OnTouchListener {
		/**
		 * Called when a touch event is dispatched to a view. This allows listeners to
		 * get a chance to respond before the target view.
		 *
		 * @param v The view the touch event has been dispatched to.
		 * @param event The MotionEvent object containing full information about
		 *        the event.
		 * @return True if the listener has consumed the event, false otherwise.
		 */
		boolean onTouch(View v, MotionEvent event);
	}

	public interface OnScrollChangeListener {
		void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY);
	}

	public interface OnClickListener {
		void onClick(View v);
	}

	public interface OnAttachStateChangeListener {
		public void onViewAttachedToWindow(View v);
		public void onViewDetachedFromWindow(View v);
	}

	public interface OnGenericMotionListener {
		// TODO
	}

	public interface OnSystemUiVisibilityChangeListener {
		// TODO
	}

	public static interface OnKeyListener {
		boolean onKey(View v, int keyCode, KeyEvent event);
	}

	public interface OnLongClickListener {
		public boolean onLongClick(View v);
	}

	public interface OnHoverListener {
		// TODO
	}

	public interface OnApplyWindowInsetsListener {
		/** When set on a View, called instead of the view's own onApplyWindowInsets. */
		WindowInsets onApplyWindowInsets(View v, WindowInsets insets);
	}

	/** AOSP: called when the layout bounds of a view change because of layout processing. */
	public interface OnLayoutChangeListener {
		void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom);
	}

	public interface OnUnhandledKeyEventListener {
		// TODO
	}

	public interface OnFocusChangeListener {
		/** Called when the focus state of a view has changed. */
		void onFocusChange(View v, boolean hasFocus);
	}

	public interface OnCreateContextMenuListener {
		/**
		 * Called when the context menu for this view is being built. It is not
		 * safe to hold onto the menu after this method returns.
		 *
		 * @param menu The context menu that is being built
		 * @param v The view for which the context menu is being built
		 * @param menuInfo Extra information about the item for which the
		 *            context menu should be shown. This information will vary
		 *            depending on the class of v.
		 */
		//void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo);
	}

	public interface OnDragListener {}
	// --- end of interfaces

	// --- subclasses

	/**
	 * A MeasureSpec encapsulates the layout requirements passed from parent to child.
	 * Each MeasureSpec represents a requirement for either the width or the height.
	 * A MeasureSpec is comprised of a size and a mode. There are three possible
	 * modes:
	 * <dl>
	 * <dt>UNSPECIFIED</dt>
	 * <dd>
	 * The parent has not imposed any constraint on the child. It can be whatever size
	 * it wants.
	 * </dd>
	 *
	 * <dt>EXACTLY</dt>
	 * <dd>
	 * The parent has determined an exact size for the child. The child is going to be
	 * given those bounds regardless of how big it wants to be.
	 * </dd>
	 *
	 * <dt>AT_MOST</dt>
	 * <dd>
	 * The child can be as large as it wants up to the specified size.
	 * </dd>
	 * </dl>
	 *
	 * MeasureSpecs are implemented as ints to reduce object allocation. This class
	 * is provided to pack and unpack the &lt;size, mode&gt; tuple into the int.
	 */
	public static class MeasureSpec {
		private static final int MODE_SHIFT = 30;
		private static final int MODE_MASK = 0x3 << MODE_SHIFT;

		/**
		 * Measure specification mode: The parent has not imposed any constraint
		 * on the child. It can be whatever size it wants.
		 */
		public static final int UNSPECIFIED = 0 << MODE_SHIFT;

		/**
		 * Measure specification mode: The parent has determined an exact size
		 * for the child. The child is going to be given those bounds regardless
		 * of how big it wants to be.
		 */
		public static final int EXACTLY = 1 << MODE_SHIFT;

		/**
		 * Measure specification mode: The child can be as large as it wants up
		 * to the specified size.
		 */
		public static final int AT_MOST = 2 << MODE_SHIFT;

		/**
		 * Creates a measure specification based on the supplied size and mode.
		 *
		 * The mode must always be one of the following:
		 * <ul>
		 *  <li>{@link android.view.View.MeasureSpec#UNSPECIFIED}</li>
		 *  <li>{@link android.view.View.MeasureSpec#EXACTLY}</li>
		 *  <li>{@link android.view.View.MeasureSpec#AT_MOST}</li>
		 * </ul>
		 *
		 * <p><strong>Note:</strong> On API level 17 and lower, makeMeasureSpec's
		 * implementation was such that the order of arguments did not matter
		 * and overflow in either value could impact the resulting MeasureSpec.
		 * {@link android.widget.RelativeLayout} was affected by this bug.
		 * Apps targeting API levels greater than 17 will get the fixed, more strict
		 * behavior.</p>
		 *
		 * @param size the size of the measure specification
		 * @param mode the mode of the measure specification
		 * @return the measure specification based on size and mode
		 */
		public static int makeMeasureSpec(int size, int mode) {
			if (sUseBrokenMakeMeasureSpec) {
				return size + mode;
			} else {
				return (size & ~MODE_MASK) | (mode & MODE_MASK);
			}
		}

		/**
		 * Extracts the mode from the supplied measure specification.
		 *
		 * @param measureSpec the measure specification to extract the mode from
		 * @return {@link android.view.View.MeasureSpec#UNSPECIFIED},
		 *		 {@link android.view.View.MeasureSpec#AT_MOST} or
		 *		 {@link android.view.View.MeasureSpec#EXACTLY}
		 */
		public static int getMode(int measureSpec) {
			return (measureSpec & MODE_MASK);
		}

		/**
		 * Extracts the size from the supplied measure specification.
		 *
		 * @param measureSpec the measure specification to extract the size from
		 * @return the size in pixels defined in the supplied measure specification
		 */
		public static int getSize(int measureSpec) {
			return (measureSpec & ~MODE_MASK);
		}

		static int adjust(int measureSpec, int delta) {
			return makeMeasureSpec(getSize(measureSpec + delta), getMode(measureSpec));
		}

		/**
		 * Returns a String representation of the specified measure
		 * specification.
		 *
		 * @param measureSpec the measure specification to convert to a String
		 * @return a String with the following format: "MeasureSpec: MODE SIZE"
		 */
		public static String toString(int measureSpec) {
			int mode = getMode(measureSpec);
			int size = getSize(measureSpec);

			StringBuilder sb = new StringBuilder("MeasureSpec: ");

			if (mode == UNSPECIFIED)
				sb.append("UNSPECIFIED ");
			else if (mode == EXACTLY)
				sb.append("EXACTLY ");
			else if (mode == AT_MOST)
				sb.append("AT_MOST ");
			else
				sb.append(mode).append(" ");

			sb.append(size);
			return sb.toString();
		}
	}

	/* Copyright (C) 2006 The Android Open Source Project */
	public static class DragShadowBuilder {
		private final WeakReference<View> mView;

		public DragShadowBuilder(View view) {
			mView = new WeakReference<View>(view);
		}

		public DragShadowBuilder() {
			mView = new WeakReference<View>(null);
		}

		final public View getView() {
			return mView.get();
		}

		public void onProvideShadowMetrics(Point outShadowSize, Point outShadowTouchPoint) {
			final View view = mView.get();
			if (view != null) {
				outShadowSize.set(view.getWidth(), view.getHeight());
				outShadowTouchPoint.set(outShadowSize.x / 2, outShadowSize.y / 2);
			} else {
				Slog.e("View", "Asked for drag thumb metrics but no view");
			}
		}

		public void onDrawShadow(Canvas canvas) {
			final View view = mView.get();
			if (view != null) {
				view.draw(canvas);
			} else {
				Slog.e("View", "Asked to draw drag shadow but no view");
			}
		}
	}

	// --- end of subclasses

	public int id = NO_ID;
	private int system_ui_visibility = 0;
	public ViewParent parent;
	public AttributeSet attrs;
	protected ViewGroup.LayoutParams layout_params;
	private Context context;
	private Map<Integer, Object> tags = new HashMap<>();
	private Object tag;
	int gravity = -1; // fallback gravity for layout children

	int measuredWidth = 0;
	int measuredHeight = 0;

	private int left = 0;
	private int top = 0;
	private int right = 0;
	private int bottom = 0;

	private float translationX = 0;
	private float translationY = 0;

	private int scrollX = 0;
	private int scrollY = 0;

	ViewTreeObserver floating_observer = null;

	public long widget; // pointer

	private int oldWidthMeasureSpec = -1;
	private int oldHeightMeasureSpec = -1;
	private boolean layoutRequested = true;
	private int oldWidth;
	private int oldHeight;
	protected boolean haveCustomMeasure = true;
	public boolean disallowIntercept = false;

	private int visibility = View.VISIBLE;
	private float alpha = 1.0f;
	private boolean pressed = false;
	private Drawable background;
	/* background tint: tlBgTintList / tlBgTintMode (View.java+bgtint.frag) */

	private int minWidth = 0;
	private int minHeight = 0;

	protected int paddingLeft = 0;
	protected int paddingTop = 0;
	protected int paddingRight = 0;
	protected int paddingBottom = 0;

	private boolean atl_enabled = true;
	/** AOSP: NOT_FOCUSABLE, FOCUSABLE or FOCUSABLE_AUTO (the default when nothing sets it) (tl_focus_model) */
	private int tlFocusableMode = FOCUSABLE_AUTO;

	public static final Property<View, Float> TRANSLATION_X = new Property<View, Float>(Float.class, "translationX") {
		@Override
		public Float get(View view) {
			return view.getTranslationX();
		}
		@Override
		public void set(View view, Float value) {
			view.setTranslationX(value);
		}
	};

	public static final Property<View, Float> TRANSLATION_Y = new Property<View, Float>(Float.class, "translationY") {
		@Override
		public Float get(View view) {
			return view.getTranslationY();
		}
		@Override
		public void set(View view, Float value) {
			view.setTranslationY(value);
		}
	};

	public static final Property<View, Float> TRANSLATION_Z = new Property<View, Float>(Float.class, "translationZ") {
		@Override
		public Float get(View view) {
			return view.getTranslationZ();
		}
		@Override
		public void set(View view, Float value) {
			view.setTranslationZ(value);
		}
	};

	public static final Property<View, Float> ALPHA = new Property<View, Float>(Float.class, "alpha") {
		@Override
		public Float get(View view) {
			return view.getAlpha();
		}
		@Override
		public void set(View view, Float value) {
			view.setAlpha(value);
		}
	};

	public View(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public View(Context context, AttributeSet attrs, int defStyle) {
		this(context, attrs, defStyle, 0);
	}

	public View(Context context, AttributeSet attrs, int defStyle, int defStyleRes) {
		this.context = context;

		widget = native_constructor(context, attrs);

		TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.View, defStyle, defStyleRes);
		this.id = a.getResourceId(com.android.internal.R.styleable.View_id, View.NO_ID);
		// AOSP: android:fitsSystemWindows (layout)
		if (a.hasValue(com.android.internal.R.styleable.View_fitsSystemWindows))
			tlFitsSystemWindows = a.getBoolean(com.android.internal.R.styleable.View_fitsSystemWindows, false);
		// AOSP View(Context, AttributeSet, ...): a colour state list, and its blending mode
		if (a.hasValue(com.android.internal.R.styleable.View_backgroundTint)) {
			tlBgTintList = a.getColorStateList(com.android.internal.R.styleable.View_backgroundTint);
			tlHasBgTintList = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_backgroundTintMode)) {
			tlBgTintMode = Drawable.parseTintMode(a.getInt(com.android.internal.R.styleable.View_backgroundTintMode, -1), null);
			tlHasBgTintMode = tlBgTintMode != null;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_background)) {
			try {
				Drawable background = a.getDrawable(com.android.internal.R.styleable.View_background);

				if (background != null) {
					if (background instanceof ColorDrawable) {
						setBackgroundColor(((ColorDrawable)background).getColor());
					} else {
						setBackgroundDrawable(background);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if (a.hasValue(com.android.internal.R.styleable.View_focusable)) {
			/* AOSP getFocusableAttribute: android:focusable is a boolean or "auto" */
			if (a.hasValue(com.android.internal.R.styleable.View_focusable)) {
				android.util.TypedValue fv = new android.util.TypedValue();
				a.getValue(com.android.internal.R.styleable.View_focusable, fv);
				if (fv.type == android.util.TypedValue.TYPE_INT_BOOLEAN)
					tlFocusableMode = fv.data == 0 ? NOT_FOCUSABLE : FOCUSABLE;
				else
					tlFocusableMode = fv.data;
			}
		}
		if (a.hasValue(com.android.internal.R.styleable.View_visibility)) {
			visibility = VISIBILITY_FLAGS[a.getInt(com.android.internal.R.styleable.View_visibility, 0)];
			native_setVisibility(widget, visibility, alpha, atl_enabled);
		}

		if (a.hasValue(com.android.internal.R.styleable.View_minWidth))
			minWidth = a.getDimensionPixelSize(com.android.internal.R.styleable.View_minWidth, 0);

		if (a.hasValue(com.android.internal.R.styleable.View_minHeight))
			minHeight = a.getDimensionPixelSize(com.android.internal.R.styleable.View_minHeight, 0);

		int padding = a.getDimensionPixelSize(com.android.internal.R.styleable.View_padding, -1);

		int paddingVertical = a.getDimensionPixelSize(com.android.internal.R.styleable.View_paddingVertical, -1);
		int paddingHorizontal = a.getDimensionPixelSize(com.android.internal.R.styleable.View_paddingHorizontal, -1);

		int paddingStart = a.getDimensionPixelSize(com.android.internal.R.styleable.View_paddingStart, -1);
		int paddingEnd = a.getDimensionPixelSize(com.android.internal.R.styleable.View_paddingEnd, -1);

		if (padding >= 0) {
			paddingLeft = padding;
			paddingTop = padding;
			paddingRight = padding;
			paddingBottom = padding;
		} else {
			if (paddingVertical >= 0) {
				paddingTop = paddingVertical;
				paddingBottom = paddingVertical;
			} else {
				paddingTop = a.getDimensionPixelSize(com.android.internal.R.styleable.View_paddingTop, 0);
				paddingBottom = a.getDimensionPixelSize(com.android.internal.R.styleable.View_paddingBottom, 0);
			}

			if (paddingHorizontal >= 0) {
				paddingLeft = paddingHorizontal;
				paddingRight = paddingHorizontal;
			} else {
				paddingLeft = a.getDimensionPixelSize(com.android.internal.R.styleable.View_paddingLeft, 0);
				paddingRight = a.getDimensionPixelSize(com.android.internal.R.styleable.View_paddingRight, 0);

				if (paddingStart >= 0)
					paddingLeft = paddingStart;

				if (paddingEnd >= 0)
					paddingRight = paddingEnd;
			}
		}

		native_setPadding(widget, paddingLeft, paddingTop, paddingRight, paddingBottom);

		if (a.hasValue(com.android.internal.R.styleable.View_tag))
			tag = a.getText(com.android.internal.R.styleable.View_tag);

		if (a.hasValue(com.android.internal.R.styleable.View_textAlignment)) {
			int textAlignment = a.getInt(com.android.internal.R.styleable.View_textAlignment, 0);
			setTextAlignment(textAlignment);
		}

		String handlerName = a.getString(com.android.internal.R.styleable.View_onClick);
		if (handlerName != null)
			setOnClickListener(new DeclaredOnClickListener(this, handlerName));

		if (a.hasValue(com.android.internal.R.styleable.View_stateListAnimator)) {
			int resId = a.getResourceId(com.android.internal.R.styleable.View_stateListAnimator, 0);
			setStateListAnimator(AnimatorInflater.loadStateListAnimator(context, resId));
		}
		/* AOSP View(Context, AttributeSet, int, int): the remaining view flags, from the attributes and the default style */
		if (a.getBoolean(com.android.internal.R.styleable.View_clickable, false))
			setClickable(true);
		if (a.getBoolean(com.android.internal.R.styleable.View_longClickable, false))
			setLongClickable(true);
		if (a.getBoolean(com.android.internal.R.styleable.View_contextClickable, false))
			setContextClickable(true);
		if (a.hasValue(com.android.internal.R.styleable.View_contentDescription))
			setContentDescription(a.getString(com.android.internal.R.styleable.View_contentDescription));
		if (a.hasValue(com.android.internal.R.styleable.View_alpha))
			setAlpha(a.getFloat(com.android.internal.R.styleable.View_alpha, 1.0f));
		if (a.hasValue(com.android.internal.R.styleable.View_importantForAccessibility))
			setImportantForAccessibility(a.getInt(com.android.internal.R.styleable.View_importantForAccessibility, IMPORTANT_FOR_ACCESSIBILITY_AUTO));
		if (a.getBoolean(com.android.internal.R.styleable.View_focusableInTouchMode, false))
			setFocusableInTouchMode(true);
		if (a.hasValue(com.android.internal.R.styleable.View_duplicateParentState))
			setDuplicateParentStateEnabled(a.getBoolean(com.android.internal.R.styleable.View_duplicateParentState, false));
		tlVcoreReadAttrs(a);
		tlReadTransformAttrs(a);
		a.recycle();
		onCreateDrawableState(0);
	}

	/** AOSP: the view's state set with extraSpace free slots at the end (for subclasses to merge into);
	 * a view with duplicateParentState takes its parent's */
	protected int[] onCreateDrawableState(int extraSpace) {
		if (isDuplicateParentStateEnabled() && parent instanceof View)
			return ((View) parent).onCreateDrawableState(extraSpace);
		return tlBaseDrawableState(extraSpace);
	}

	/** AOSP: copies newState into the free (zero) tail of curState and returns curState */
	protected static int[] mergeDrawableStates(int[] curState, int[] newState) {
		final int n = curState.length;
		int i = n - 1;
		while (i >= 0 && curState[i] == 0)
			i--;
		System.arraycopy(newState, 0, curState, i + 1, newState.length);
		return curState;
	}

	/** AOSP: NO_ID never matches; otherwise this view when the id is its own. */
	public View findViewById(int id) {
		if (id == NO_ID)
			return null;
		return this.id == id ? this : null;
	}

	public void onDraw(Canvas canvas) {
		if (canvas instanceof GskCanvas)
			native_drawContent(widget, ((GskCanvas)canvas).snapshot);
	}

	protected void dispatchDraw(Canvas canvas) {
		if (canvas instanceof GskCanvas)
			native_drawChildren(widget, ((GskCanvas)canvas).snapshot);
	}
	private static final String ATL_DEBUG_DRAW = System.getenv("ATL_DEBUG_DRAW");
	private static final boolean ATL_DEBUG_TOUCH_JAVA = System.getenv("ATL_DEBUG_TOUCH") != null;
	public void draw(Canvas canvas) {
		if (ATL_DEBUG_DRAW != null && getClass().getName().contains(ATL_DEBUG_DRAW))
			android.util.Log.i("View", "[draw] " + getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(this)) + " " + getWidth() + "x" + getHeight() + " canvas=" + canvas.getClass().getSimpleName() + " parent=" + (parent == null ? "null" : parent.getClass().getSimpleName()));
		if (canvas instanceof GskCanvas)
			native_drawBackground(widget, ((GskCanvas)canvas).snapshot);
		onDraw(canvas);
		// HACK: catch non critical exceptions happening in some composeUI apps
		try {
			dispatchDraw(canvas);
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		}
	}

	public View(Context context) {
		this(context, null);
	}

	public final ViewParent getParent() {
		return parent;
	}

	public void setLayoutParams(ViewGroup.LayoutParams params) {
		if (params == null) {
			throw new NullPointerException("Layout parameters cannot be null");
		}
		params.resolveLayoutDirection(getLayoutDirection());

		int gravity = params.gravity;
		if (gravity == -1 && parent instanceof View)
			gravity = ((View)parent).gravity;

		int leftMargin = 0;
		int topMargin = 0;
		int rightMargin = 0;
		int bottomMargin = 0;
		if (params instanceof ViewGroup.MarginLayoutParams) {
			leftMargin = ((ViewGroup.MarginLayoutParams)params).leftMargin;
			topMargin = ((ViewGroup.MarginLayoutParams)params).topMargin;
			rightMargin = ((ViewGroup.MarginLayoutParams)params).rightMargin;
			bottomMargin = ((ViewGroup.MarginLayoutParams)params).bottomMargin;
		}

		native_setLayoutParams(widget, params.width, params.height, gravity, params.weight, leftMargin, topMargin, rightMargin, bottomMargin);

		layout_params = params;
	}

	public ViewGroup.LayoutParams getLayoutParams() {
		return layout_params;
	}

	protected final void setMeasuredDimension(int measuredWidth, int measuredHeight) {
		this.measuredWidth = measuredWidth;
		this.measuredHeight = measuredHeight;
	}

	public Resources getResources() {
		return this.context.getResources();
	}

	public void setGravity(int gravity) {
		this.gravity = gravity;
	}

	private OnTouchListener on_touch_listener = null;

	public boolean onTouchEventInternal(MotionEvent event, boolean handle_gestures) {
		boolean handled = false;
		if (on_touch_listener != null)
			handled = on_touch_listener.onTouch(this, event);

		tlDispatchingSynthetic = handle_gestures;
		if (!handled)
			handled = onTouchEvent(event);

		if (handle_gestures && !handled && event.getAction() == MotionEvent.ACTION_UP)
			handled = performClick();

		return handled;
	}

	/**
	 * AOSP View.onTouchEvent: the touch-to-click state machine. A clickable view takes the gesture on
	 * DOWN (pressed state, long-press timer, delayed press inside scrolling containers), performs the
	 * click on UP while still pressed, and drops the gesture on CANCEL or when the pointer leaves the
	 * view by more than the touch slop. Pointer events that come straight from GTK perform the click
	 * through the native click gesture ATL attaches to the widget, so this only performs it for
	 * events that Java code dispatched to the view (Compose interop, a parent group).
	 */
	public boolean onTouchEvent(MotionEvent event) {
		final float x = event.getX();
		final float y = event.getY();
		final int action = event.getActionMasked();
		final boolean clickable = isClickable() || isLongClickable() || isContextClickable();
		if (ATL_DEBUG_TOUCH_JAVA)
			android.util.Log.i("View", "[touch] onTouchEvent " + getClass().getName() + " action=" + action + " clickable=" + clickable + " enabled=" + isEnabled() + " pressed=" + pressed + " prepressed=" + mPrePressed + " synthetic=" + tlDispatchingSynthetic + " listener=" + (on_click_listener != null));
		if (!isEnabled()) {
			if (action == MotionEvent.ACTION_UP && pressed)
				setPressed(false);
			// a disabled view that is clickable still consumes the touch events, it just does not respond
			return clickable;
		}
		if (mTouchDelegate != null && mTouchDelegate.onTouchEvent(event))
			return true;
		if (clickable) {
			switch (action) {
				case MotionEvent.ACTION_UP:
					mHasPerformedLongPress = false;
					boolean prepressed = mPrePressed;
					if (pressed || prepressed) {
						if (prepressed) {
							// released before the delayed pressed state was shown: show it now
							setPressed(true, x, y);
						}
						if (!mHasPerformedLongPress && !mIgnoreNextUpEvent) {
							removeLongPressCallback();
							performClickInternal(); // AOSP: the click comes from the UP, never from a GTK gesture
						}
						if (mUnsetPressedState == null)
							mUnsetPressedState = new Runnable() {
								@Override
								public void run() {
									setPressed(false);
								}
							};
						if (prepressed) {
							postDelayed(mUnsetPressedState, ViewConfiguration.getPressedStateDuration());
						} else if (!post(mUnsetPressedState)) {
							mUnsetPressedState.run();
						}
						removeTapCallback();
					}
					mIgnoreNextUpEvent = false;
					break;
				case MotionEvent.ACTION_DOWN:
					mHasPerformedLongPress = false;
					if (isInScrollingContainer()) {
						mPrePressed = true;
						if (mPendingCheckForTap == null)
							mPendingCheckForTap = new Runnable() {
								@Override
								public void run() {
									mPrePressed = false;
									setPressed(true, tlTapX, tlTapY);
									checkForLongClick(ViewConfiguration.getLongPressTimeout() - ViewConfiguration.getTapTimeout(), tlTapX, tlTapY);
								}
							};
						tlTapX = x;
						tlTapY = y;
						postDelayed(mPendingCheckForTap, ViewConfiguration.getTapTimeout());
					} else {
						setPressed(true, x, y);
						checkForLongClick(ViewConfiguration.getLongPressTimeout(), x, y);
					}
					break;
				case MotionEvent.ACTION_CANCEL:
					setPressed(false);
					removeTapCallback();
					removeLongPressCallback();
					mPrePressed = false;
					mHasPerformedLongPress = false;
					mIgnoreNextUpEvent = false;
					break;
				case MotionEvent.ACTION_MOVE:
					if (!pointInView(x, y, ViewConfiguration.get(getContext()).getScaledTouchSlop())) {
						removeTapCallback();
						removeLongPressCallback();
						if (pressed)
							setPressed(false);
						mPrePressed = false;
					}
					break;
			}
			return true;
		}
		return false;
	}

	public void setOnTouchListener(OnTouchListener l) {
		nativeSetOnTouchListener(widget);
		on_touch_listener = l;
	}
	protected native void nativeSetOnTouchListener(long widget);
	private OnClickListener on_click_listener = null;
	public void setOnClickListener(OnClickListener l) {
		if (!isClickable())
			setClickable(true);
		nativeSetOnClickListener(widget);
		on_click_listener = l;
	}
	protected native void nativeSetOnClickListener(long widget);

	private OnScrollChangeListener on_scroll_change_listener = null;
	/** AOSP: called with every change of the scroll position (layout) */
	public void setOnScrollChangeListener(OnScrollChangeListener l) {
		on_scroll_change_listener = l;
	}

	public /*native*/ void setOnSystemUiVisibilityChangeListener(OnSystemUiVisibilityChangeListener l) {}
	public final int getWidth() {
		return right - left;
	}
	public final int getHeight() {
		return bottom - top;
	}

	protected native long native_constructor(Context context, AttributeSet attrs); // will create a custom GtkWidget with a custom drawing function
	public native void native_setLayoutParams(long widget, int width, int height, int gravity, float weight, int leftMargin, int topMargin, int rightMargin, int bottomMargin);
	protected native void native_destructor(long widget);
	/**
	 * We decide between simple widgets which handles MEASURE_SPEC_AT_MOST the same way as
	 * MEASURE_SPEC_EXACTLY, and complex widgets which handles MEASURE_SPEC_AT_MOST by measuring the content
	 */
	protected native void native_measure(long widget, int widthMeasureSpec, int heightMeasureSpec);
	protected native void native_layout(long widget, int l, int t, int r, int b);
	protected native void native_requestLayout(long widget);
	protected native void native_setBackgroundDrawable(long widget, long paintable);
	protected native void native_queueAllocate(long widget);

	protected native void native_addClass(long widget, String className);
	protected native void native_removeClass(long widget, String className);

	protected native void native_addClasses(long widget, String[] classNames);
	protected native void native_removeClasses(long widget, String[] classNames);

	protected native void native_drawBackground(long widget, long snapshot);
	protected native void native_drawContent(long widget, long snapshot);
	protected void native_drawChildren(long widget, long snapshot) {} // override in ViewGroup

	// --- stubs

	/** AOSP: the accessibility description of the view. */
	public void setContentDescription(CharSequence contentDescription) {
		tlContentDescription = contentDescription;
	}
	private CharSequence tlContentDescription;

	public void setId(int id) {
		this.id = id;
	}

	public void setOnKeyListener(OnKeyListener l) {
		mOnKeyListener = l;
	}

	/** AOSP: whether this view can take focus. */
	public void setFocusable(boolean focusable) {
		setFocusable(focusable ? FOCUSABLE : NOT_FOCUSABLE);
	}
	/** AOSP: NOT_FOCUSABLE, FOCUSABLE or FOCUSABLE_AUTO; a view that is no longer focusable loses focus. */
	public void setFocusable(int focusable) {
		if ((focusable & (FOCUSABLE_AUTO | FOCUSABLE)) == 0)
			tlFocusableInTouchMode = false;
		tlFocusableMode = focusable & (FOCUSABLE_AUTO | FOCUSABLE);
		if (!isFocusable() && tlFocused)
			clearFocus();
	}
	/** AOSP: the focusable setting as set (FOCUSABLE_AUTO when the view decides). */
	public int getFocusable() {
		return (tlFocusableMode & FOCUSABLE_AUTO) != 0 ? FOCUSABLE_AUTO : (isFocusable() ? FOCUSABLE : NOT_FOCUSABLE);
	}
	/**
	 * AOSP: focusable in touch mode implies focusable; giving it up while in touch mode drops the
	 * focus (setFlags: a view that cannot keep focus in touch mode).
	 */
	public void setFocusableInTouchMode(boolean focusableInTouchMode) {
		tlFocusableInTouchMode = focusableInTouchMode;
		if (focusableInTouchMode)
			setFocusable(FOCUSABLE);
		else if (tlFocused && isInTouchMode())
			clearFocus();
	}
	boolean tlFocusableInTouchMode;
	public final boolean requestFocus() {
		return requestFocus(View.FOCUS_DOWN);
	}
	public final boolean requestFocus(int direction) {
		return requestFocus(direction, null);
	}
	/**
	 * AOSP requestFocus/requestFocusNoSearch (tl_focus_model): the view takes focus when it can
	 * (visible, focusable, enabled, focusable in touch mode while in touch mode, no ancestor blocking
	 * its descendants); the focus is view state of its window, and GTK's focus follows it when the
	 * window is the focused one.
	 */
	public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
		boolean ok = tlRequestFocusNoSearch(direction, previouslyFocusedRect);
		if (TL_DEBUG_FOCUS)
			android.util.Log.i("View", "[focus] requestFocus " + getClass().getName() + " ok=" + ok + " enabled=" + atl_enabled + " focusable=" + isFocusable()
			                   + " inTouchMode=" + isFocusableInTouchMode() + " vis=" + visibility + " attached=" + isAttachedToWindow() + " windowFocus=" + hasWindowFocus(), TL_DEBUG_FOCUS_STACK ? new Throwable() : null);
		return ok;
	}
	private static final boolean TL_DEBUG_FOCUS = System.getenv("ATL_DEBUG_FOCUS") != null;
	private static final boolean TL_DEBUG_FOCUS_STACK = "stack".equals(System.getenv("ATL_DEBUG_FOCUS"));
	/** AOSP: leaves touch mode first, so a view that is focusable only outside touch mode can take focus. */
	public final boolean requestFocusFromTouch() {
		if (isInTouchMode())
			tlEnsureTouchMode(false);
		return requestFocus(View.FOCUS_DOWN);
	}
	private native void nativeRequestFocus(long widget, int direction);

	private native void nativeSetFullscreen(long widget, boolean fullscreen);

	public void setSystemUiVisibility(int visibility) {
		/* LAYOUT_FULLSCREEN mode would make the app window continue behind semi-transparent status bar.
		   Such a mode doesn't exist on desktop Linux, so we just use normal fullscreen for both modes. */
		nativeSetFullscreen(widget, (visibility & (View.SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)) != 0);
		system_ui_visibility = visibility;
	}
	public int getSystemUiVisibility() {
		return system_ui_visibility;
	};

	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		if (haveCustomMeasure) // calling native_measure here would create infinite loop
			setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec), getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec));
		else
			native_measure(widget, widthMeasureSpec, heightMeasureSpec);
	}

	/** AOSP: the pressed state; a group passes it to children that are not clickable themselves */
	public void setPressed(boolean pressed) {
		if (this.pressed != pressed) {
			this.pressed = pressed;
			refreshDrawableState();
			dispatchSetPressed(pressed);
		}
	}

	/** AOSP: the selected state (tabs, segmented controls, list rows), passed on to a group's children */
	public void setSelected(boolean selected) {
		if (tlSelected != selected) {
			tlSelected = selected;
			if (!selected && pressed)
				setPressed(false);
			invalidate();
			refreshDrawableState();
			dispatchSetSelected(selected);
		}
	}

	public native Window native_get_window(long widget);
	public ViewTreeObserver getViewTreeObserver() {
		Window window = native_get_window(widget);
		if (window != null) {
			if (window.view_tree_observer == null)
				window.view_tree_observer = new ViewTreeObserver(window);
			return window.view_tree_observer;
		} else {
			floating_observer = new ViewTreeObserver(null);
			return floating_observer;
		}
	}

	protected void onFinishInflate() {}

	/** AOSP: a drawable of this view changed: redraw, and the outline may follow the drawable */
	public void invalidateDrawable(Drawable drawable) {
		nativeInvalidate(widget);
		if (verifyDrawable(drawable))
			tlRebuildOutline();
	}

	/**
	 * AOSP: the drawable's frame runs at the given uptime; the drawable is the token, so that
	 * unscheduleDrawable(who) removes every pending frame of it (AOSP posts to the Choreographer's
	 * animation queue with the drawable as token; here the view's main-thread handler).
	 */
	public void scheduleDrawable(Drawable drawable, Runnable runnable, long time) {
		if (verifyDrawable(drawable) && runnable != null)
			tlMainHandler().postAtTime(runnable, drawable, time);
	}

	public void unscheduleDrawable(Drawable drawable, Runnable runnable) {
		if (verifyDrawable(drawable) && runnable != null)
			tlMainHandler().removeCallbacks(runnable, drawable);
	}

	public void unscheduleDrawable(Drawable drawable) {
		if (drawable != null)
			tlMainHandler().removeCallbacksAndMessages(drawable);
	}

	private static final boolean ATL_DEBUG_SURFACE = System.getenv("ATL_DEBUG_SURFACE") != null;
	public void invalidate(Rect dirty) {
		if (dirty == null) {
			invalidate();
			return;
		}
		invalidateInternal(dirty.left, dirty.top, dirty.right, dirty.bottom);
	}
	public void invalidate(int l, int t, int r, int b) {
		invalidateInternal(l, t, r, b);
	}
	public void invalidate() {
		invalidateInternal(0, 0, getWidth(), getHeight());
	}
	/**
	 * AOSP View.invalidateInternal: the view's own drawing is redone (the GTK widget is queued for a
	 * redraw) and the parent chain is told through ViewParent.invalidateChild, which is how a parent
	 * that records its children into a display list (Compose's AndroidViewHolder, hardware layers)
	 * learns that the recording is stale. Invisible views skip the parent notification, as AOSP does.
	 */
	private static int sInvalidateTrace;
	private void invalidateInternal(int l, int t, int r, int b) {
		nativeInvalidate(widget);
		if (ATL_DEBUG_SURFACE && this instanceof SurfaceView && (sInvalidateTrace++ % 100) == 0)
			android.util.Log.i("View", "[surface] invalidate #" + sInvalidateTrace + " " + getClass().getName() + " visibility=" + getVisibility() + " parent=" + (parent == null ? "null" : parent.getClass().getName()));
		if (getVisibility() != VISIBLE)
			return;
		final ViewParent p = parent;
		if (p != null && l < r && t < b) {
			p.invalidateChild(this, new Rect(l, t, r, b));
		}
	}
	private static native void nativeInvalidate(long widget);

	protected native void native_setBackgroundColor(long widget, int color);
	public void setBackgroundColor(int color) {
		background = new ColorDrawable(color);
		native_setBackgroundColor(widget, color);
	}

	native void native_setVisibility(long widget, int visibility, float alpha, boolean sensitive);

	protected void onVisibilityChanged(View changedView, int visibility) {
	}

	protected void dispatchVisibilityChanged(View changedView, int visibility) {
		onVisibilityChanged(changedView, visibility);
	}

	public void setVisibility(int visibility) {
		if (visibility == this.visibility)
			return;
		if ((visibility == View.GONE) != (this.visibility == View.GONE) && parent instanceof ViewGroup) {
			((ViewGroup)parent).requestLayout();
		}
		/* AOSP setFlags: a view that stops being visible gives up focus */
		if (visibility != VISIBLE && hasFocus())
			clearFocus();
		this.visibility = visibility;
		native_setVisibility(widget, visibility, alpha, atl_enabled);
		dispatchVisibilityChanged(this, visibility);
		if (isAttachedToWindow()) // AOSP setFlags: the aggregated visibility follows while in a window
			dispatchVisibilityAggregated(visibility == VISIBLE);
	}

	public void setPadding(int left, int top, int right, int bottom) {
		tlPaddingRelative = false; // AOSP: absolute padding replaces the relative (start/end) one
		if (tlOutlineProvider == ViewOutlineProvider.PADDED_BOUNDS && (paddingLeft != left || paddingTop != top || paddingRight != right || paddingBottom != bottom))
			tlPostRebuildOutline();
		paddingLeft = left;
		paddingTop = top;
		paddingRight = right;
		paddingBottom = bottom;
		native_setPadding(widget, left, top, right, bottom);
	}
	public native void native_setPadding(long widget, int left, int top, int right, int bottom);

	public void setBackgroundResource(int resid) {
		setBackgroundDrawable(resid == 0 ? null : getContext().getDrawable(resid));
	}

	public void getHitRect(Rect outRect) {
		outRect.set(left, top, right, bottom);
	}
	/** AOSP: the visible part of the view in its own coordinates; false when nothing of it is visible. */
	public final boolean getLocalVisibleRect(Rect r) {
		final android.graphics.Point offset = new android.graphics.Point();
		if (getGlobalVisibleRect(r, offset)) {
			r.offset(-offset.x, -offset.y);
			return true;
		}
		return false;
	}

	public final int getScrollX() {
		return scrollX;
	}
	public final int getScrollY() {
		return scrollY;
	}

	/**
	 * AOSP: a new scroll position is reported through onScrollChanged (and so the
	 * OnScrollChangeListener); ScrollView, HorizontalScrollView and the AndroidX scroll containers
	 * rely on it (layout). The view is invalidated either way, which re-applies the scroll offset
	 * of a view that implements computeScroll.
	 */
	public void scrollTo(int x, int y) {
		final int oldX = scrollX;
		final int oldY = scrollY;
		scrollX = x;
		scrollY = y;
		if (oldX != x || oldY != y)
			onScrollChanged(x, y, oldX, oldY);
		invalidate();
	}

	/** AOSP: the vertical scroll position (scroll containers report their content offset) (layout) */
	protected int computeVerticalScrollOffset() {
		return scrollY;
	}

	/** AOSP: the scroll position changed; the OnScrollChangeListener hears it (layout) */
	protected void onScrollChanged(int l, int t, int oldl, int oldt) {
		if (on_scroll_change_listener != null)
			on_scroll_change_listener.onScrollChange(this, l, t, oldl, oldt);
	}

	public void getLocationOnScreen(int[] location) {
		Rect rect = new Rect();
		getGlobalVisibleRect(rect);
		location[0] = rect.left;
		location[1] = rect.top;
	}

	public boolean performHapticFeedback(int feedbackConstant) {
		return performHapticFeedback(feedbackConstant, 0);
	}

	/**
	 * AOSP: haptic feedback for this view. Nothing is performed (false) for NO_HAPTICS, a view that is
	 * not in a window, or haptics disabled on the view (unless FLAG_IGNORE_VIEW_SETTING); the window
	 * manager then vibrates the device's vibrator, and reports false when the device has none.
	 */
	public boolean performHapticFeedback(int feedbackConstant, int flags) {
		final int NO_HAPTICS = -1; // HapticFeedbackConstants.NO_HAPTICS
		final int FLAG_IGNORE_VIEW_SETTING = 0x0001; // HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
		if (feedbackConstant == NO_HAPTICS || !isAttachedToWindow())
			return false;
		if ((flags & FLAG_IGNORE_VIEW_SETTING) == 0 && !isHapticFeedbackEnabled())
			return false;
		Vibrator vibrator = (Vibrator)getContext().getSystemService("vibrator");
		if (vibrator == null || !vibrator.hasVibrator())
			return false;
		vibrator.vibrate(tlHapticDuration(feedbackConstant));
		return true;
	}

	public int getPaddingLeft() {
		return paddingLeft;
	}

	public int getPaddingRight() {
		return paddingRight;
	}

	public int getPaddingTop() {
		return paddingTop;
	}

	public int getPaddingBottom() {
		return paddingBottom;
	}

	public int getPaddingStart() {
		return paddingLeft;
	}

	public int getPaddingEnd() {
		return paddingRight;
	}

	/** AOSP: invalidate() from any thread, on the next event loop pass */
	public void postInvalidate() {
		postInvalidateDelayed(0);
	}

	/** AOSP: invalidate(l, t, r, b) from any thread, on the next event loop pass */
	public void postInvalidate(final int left, final int top, final int right, final int bottom) {
		postInvalidateDelayed(0, left, top, right, bottom);
	}

	/** AOSP: invalidate() after delayMilliseconds (from any thread) */
	public void postInvalidateDelayed(long delayMilliseconds) {
		tlMainHandler().postDelayed(new Runnable() {
			@Override
			public void run() {
				invalidate();
			}
		}, delayMilliseconds);
	}

	/** AOSP: invalidate(l, t, r, b) after delayMilliseconds (from any thread) */
	public void postInvalidateDelayed(long delayMilliseconds, final int left, final int top, final int right, final int bottom) {
		tlMainHandler().postDelayed(new Runnable() {
			@Override
			public void run() {
				invalidate(left, top, right, bottom);
			}
		}, delayMilliseconds);
	}

	public void setOnGenericMotionListener(View.OnGenericMotionListener l) {}

	public IBinder getWindowToken() {
		return new Binder();
	}

	public void getLocationInWindow(int[] location) {
		getLocationOnScreen(location);
	}

	public void addOnAttachStateChangeListener(OnAttachStateChangeListener l) {
		onAttachStateChangeListener = l;
	}

	public Context getContext() {
		return this.context;
	}

	/** AOSP: the state set is rebuilt; drawables and colours follow, and the parent hears of it */
	public void refreshDrawableState() {
		tlDrawableStateDirty = true;
		drawableStateChanged();
		if (parent instanceof ViewGroup)
			((ViewGroup) parent).childDrawableStateChanged(this);
	}

	public void setDescendantFocusability(int value) {}

	public void setAccessibilityDelegate(AccessibilityDelegate delegate) {}

	public static class AccessibilityDelegate {
		public void sendAccessibilityEvent(View view, int eventType) {}
	}

	public Drawable getBackground() {
		return background;
	}

	/** AOSP: a clickable view consumes touches (it handles them in onTouchEvent), so attach the touch path. */
	public void setClickable(boolean clickable) {
		mClickable = clickable;
		if (clickable && widget != 0)
			nativeSetOnTouchListener(widget);
	}

	public void setEnabled(boolean enabled) {
		if (atl_enabled == enabled)
			return;
		atl_enabled = enabled;
		native_setVisibility(widget, visibility, alpha, atl_enabled);
		// AOSP: a disabled view is not pressed, and state_enabled changes
		if (!enabled && pressed)
			setPressed(false);
		refreshDrawableState();
		invalidate();
	}

	public CharSequence getContentDescription() {
		return null;
	}

	private OnLongClickListener on_long_click_listener = null;
	public boolean performLongClick(float x, float y) {
		if (on_long_click_listener != null) {
			return on_long_click_listener.onLongClick(this);
		}
		return false;
	}
	public void setOnLongClickListener(OnLongClickListener listener) {
		if (!isLongClickable())
			setLongClickable(true);
		nativeSetOnLongClickListener(widget);
		on_long_click_listener = listener;
	}
	protected native void nativeSetOnLongClickListener(long widget);

	public void setLongClickable(boolean longClickable) {
		mLongClickable = longClickable;
		if (longClickable && widget != 0)
			nativeSetOnTouchListener(widget);
	}

	public void setOnHoverListener(OnHoverListener listener) {}

	public final void measure(int widthMeasureSpec, int heightMeasureSpec) {
		if (layoutRequested || widthMeasureSpec != oldWidthMeasureSpec || heightMeasureSpec != oldHeightMeasureSpec) {
			layoutRequested = false;
			oldWidthMeasureSpec = widthMeasureSpec;
			oldHeightMeasureSpec = heightMeasureSpec;
			onMeasure(widthMeasureSpec, heightMeasureSpec);
		}
	}

	/** AOSP: the measured state bits of both dimensions (MEASURED_STATE_TOO_SMALL etc). */
	public final int getMeasuredState() {
		return (measuredWidth & MEASURED_STATE_MASK)
				| ((measuredHeight >> MEASURED_HEIGHT_STATE_SHIFT) & (MEASURED_STATE_MASK >> MEASURED_HEIGHT_STATE_SHIFT));
	}

	public static int combineMeasuredStates(int curState, int newState) {
		return curState | newState;
	}

	/** AOSP: the larger of the view's minimum height and its background's minimum height (a view is
	 * never smaller than its background image: Waze's speedometer circles are only backgrounds) */
	protected int getSuggestedMinimumHeight() {
		return (background == null) ? getMinimumHeight() : Math.max(getMinimumHeight(), background.getMinimumHeight());
	}
	/** AOSP: the larger of the view's minimum width and its background's minimum width */
	protected int getSuggestedMinimumWidth() {
		return (background == null) ? getMinimumWidth() : Math.max(getMinimumWidth(), background.getMinimumWidth());
	}

	/**
	 * Utility to reconcile a desired size and state, with constraints imposed
	 * by a MeasureSpec.  Will take the desired size, unless a different size
	 * is imposed by the constraints.  The returned value is a compound integer,
	 * with the resolved size in the {@link #MEASURED_SIZE_MASK} bits and
	 * optionally the bit {@link #MEASURED_STATE_TOO_SMALL} set if the resulting
	 * size is smaller than the size the view wants to be.
	 *
	 * @param size How big the view wants to be
	 * @param measureSpec Constraints imposed by the parent
	 * @return Size information bit mask as defined by
	 * {@link #MEASURED_SIZE_MASK} and {@link #MEASURED_STATE_TOO_SMALL}.
	 */
	public static int resolveSizeAndState(int size, int measureSpec, int childMeasuredState) {
		int result = size;
		int specMode = MeasureSpec.getMode(measureSpec);
		int specSize = MeasureSpec.getSize(measureSpec);
		switch (specMode) {
			case MeasureSpec.UNSPECIFIED:
				result = size;
				break;
			case MeasureSpec.AT_MOST:
				if (specSize < size) {
					result = specSize | MEASURED_STATE_TOO_SMALL;
				} else {
					result = size;
				}
				break;
			case MeasureSpec.EXACTLY:
				result = specSize;
				break;
		}
		return result | (childMeasuredState & MEASURED_STATE_MASK);
	}

	public static int resolveSize(int size, int measureSpec) {
		return resolveSizeAndState(size, measureSpec, 0) & MEASURED_SIZE_MASK;
	}

	public final int getMeasuredWidth() {
		return this.measuredWidth & MEASURED_SIZE_MASK;
	}

	public final int getMeasuredHeight() {
		return this.measuredHeight & MEASURED_SIZE_MASK;
	}

	protected void onLayout(boolean changed, int l, int t, int r, int b) {}

	public void layout(int l, int t, int r, int b) {
		tlLayoutDepth++;
		try {
			tlLayoutImpl(l, t, r, b);
		} finally {
			tlLayoutDepth--;
		}
		tlLaidOut = true;
		tlFrameMaybeChanged();
	}

	private void tlLayoutImpl(int l, int t, int r, int b) {
		this.left = l;
		this.top = t;
		this.right = r;
		this.bottom = b;
		// a default (centred) pivot follows the size: recompose before the allocation reads the matrix
		if (!tlPivotSet && tlLayerMatrix != null && (r - l != tlLayerW || b - t != tlLayerH))
			tlComputeLayerMatrix();
		native_layout(widget, l, t, r, b);
	}

	/** Helper function to be called from GTKs LayoutManager via JNI */
	private void layoutInternal(int width, int height) {
		tlLayoutDepth++;
		try {
			tlLayoutInternalImpl(width, height);
		} finally {
			tlLayoutDepth--;
		}
		tlLaidOut = true;
		tlFrameMaybeChanged();
	}

	private void tlLayoutInternalImpl(int width, int height) {
		// if the layout is triggered from a native widget, we might not have measured yet
		if (!(parent instanceof ViewGroup) && (width != getMeasuredWidth() || height != getMeasuredHeight())) {
			requestLayout();
			measure(width | MeasureSpec.EXACTLY, height | MeasureSpec.EXACTLY);
		}
		boolean changed = oldWidth != width || oldHeight != height;
		if (changed)
			onSizeChanged(width, height, oldWidth, oldHeight);
		bottom = top + height;
		right = left + width;
		onLayout(changed, 0, 0, width, height);
		oldWidth = width;
		oldHeight = height;
		tlDispatchLayoutChange();
	}

	/** Prefill the measureSpec cache, so that AndroidLayout can use it from native code if GTK measure spec is incomplete */
	public void internalSetDefaultMeasureSpec(int widthMeasureSpec, int heightMeasureSpec) {
		this.oldWidthMeasureSpec = widthMeasureSpec;
		this.oldHeightMeasureSpec = heightMeasureSpec;
		requestLayout();
	}

	public int getLeft() {
		return left;
	}
	public int getTop() {
		return top;
	}
	public int getRight() {
		return right;
	}
	public int getBottom() {
		return bottom;
	}

	public void offsetTopAndBottom(int offset) {
		layout(left, top + offset, right, bottom + offset);
	}

	public void offsetLeftAndRight(int offset) {
		layout(left + offset, top, right + offset, bottom);
	}

	public void setBackgroundDrawable(Drawable backgroundDrawable) {
		if (this.background != backgroundDrawable)
			tlPostRebuildOutline();
		this.background = backgroundDrawable;
		if (backgroundDrawable != null) {
			backgroundDrawable.setCallback(this);
			// AOSP setBackgroundDrawable(): the view's background tint applies to every new background
			applyBackgroundTint();
		}
		native_setBackgroundDrawable(widget, this.background != null ? this.background.paintable : 0);
	}

	// (layout) getOverScrollMode: see View+layout.frag

	// (layout) setFitsSystemWindows: see View+layout.frag (AOSP window insets)

	/** AOSP: WILL_NOT_DRAW, the view promises it draws nothing itself (drawing skips onDraw) */
	public void setWillNotDraw(boolean value) {
		tlWillNotDraw = value;
	}

	/** AOSP: a scroll container may be resized for the soft input window */
	public void setScrollContainer(boolean isScrollContainer) {
		tlScrollContainer = isScrollContainer;
	}

	/** AOSP: removes a pending post/postDelayed of this view (one shared main-thread handler). */
	public boolean removeCallbacks(Runnable action) {
		if (action != null)
			tlMainHandler().removeCallbacks(action);
		return true;
	}

	/**
	 * AOSP: marks this view for a new measure/layout pass and asks the parent to do the same, through
	 * the parent's own requestLayout() (overridden by RecyclerView, Compose's AndroidViewsHandler and
	 * others) unless the parent already has a layout pending.
	 */
	public void requestLayout() {
		native_requestLayout(widget);
		layoutRequested = true;
		if (TL_DEBUG_LAYOUT && parent != null && parent.getClass().getSimpleName().equals("AndroidViewsHandler"))
			android.util.Log.i("View", "[layout] requestLayout reaches the compose views handler", new Throwable());
		if (parent instanceof View && !((View) parent).isLayoutRequested())
			((View) parent).requestLayout();
	}

	private static final boolean TL_DEBUG_LAYOUT = System.getenv("ATL_DEBUG_LAYOUT") != null;

	// (layout) setOverScrollMode: see View+layout.frag

	public int getId() { return id; }
	public String getIdName() {
		if (this.id == View.NO_ID) {
			return "NO_ID";
		}

		try {
			return getResources().getResourceName(this.id);
		} catch (Resources.NotFoundException e) {
			return "NOT_FOUND";
		}
	}
	public String getAllSuperClasses() {
		StringBuilder sb = new StringBuilder();
		Class<?> currentClass = getClass();

		sb.append(currentClass.getCanonicalName());
		currentClass = currentClass.getSuperclass();
		while (currentClass != null) {
			sb.append(" << ").append(currentClass.getCanonicalName());
			currentClass = currentClass.getSuperclass();
		}
		return sb.toString();
	}

	public boolean postDelayed(Runnable action, long delayMillis) {
		return tlMainHandler().postDelayed(action, delayMillis);
	}

	public boolean post(Runnable action) {
		return tlMainHandler().post(action);
	}

	/** AOSP: whether the parent's dispatchSaveInstanceState/dispatchRestoreInstanceState reach this view */
	public void setSaveFromParentEnabled(boolean enabled) {
		tlSaveFromParentEnabled = enabled;
	}

	public void setTag(int key, Object tag) {
		tags.put(key, tag);
	}
	public Object getTag(int key) {
		return tags.get(key);
	}

	public void setTag(Object tag) {
		this.tag = tag;
	}
	public Object getTag() {
		return tag;
	}

	/** AOSP: the listener hears about every layout of this view (tl_layout_change). */
	public void addOnLayoutChangeListener(OnLayoutChangeListener listener) {
		if (tlLayoutChangeListeners == null)
			tlLayoutChangeListeners = new java.util.ArrayList<OnLayoutChangeListener>();
		if (!tlLayoutChangeListeners.contains(listener))
			tlLayoutChangeListeners.add(listener);
	}
	public void removeOnLayoutChangeListener(OnLayoutChangeListener listener) {
		if (tlLayoutChangeListeners != null)
			tlLayoutChangeListeners.remove(listener);
	}

	public boolean isSelected() { return tlSelected; }

	public void sendAccessibilityEvent(int eventType) {}

	public void setMinimumHeight(int minHeight) {
		this.minHeight = minHeight;
	}
	public void setMinimumWidth(int minWidth) {
		this.minWidth = minWidth;
	}

	/** AOSP: the activated state (the current item of a list in an activated-choice mode) */
	public void setActivated(boolean activated) {
		if (tlActivated != activated) {
			tlActivated = activated;
			invalidate();
			refreshDrawableState();
			dispatchSetActivated(activated);
		}
	}

	public boolean isActivated() { return tlActivated; }

	public int getVisibility() { return visibility; }

	public boolean isInEditMode() { return false; }

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() throws Throwable {
		try {
			native_destructor(widget);
		} finally {
			super.finalize();
		}
	}

	/** AOSP: the cached state set, rebuilt through onCreateDrawableState(0) after refreshDrawableState() */
	public final int[] getDrawableState() {
		if (tlDrawableState != null && !tlDrawableStateDirty)
			return tlDrawableState;
		tlDrawableState = onCreateDrawableState(0);
		tlDrawableStateDirty = false;
		return tlDrawableState;
	}

	public float getRotation() { return tlRotation; }

	public void bringToFront() {}

	public boolean isEnabled() { return atl_enabled; }
	/** AOSP: whether this view is the focused view of its window (ViewGroup: or contains it). */
	public boolean hasFocus() { return tlFocused; }
	public boolean isLayoutRequested() { return layoutRequested; }
	public int getBaseline() { return -1; }
	/**
	 * AOSP: whether this view (a ViewGroup: or a descendant) can take focus: visible, enabled and
	 * focusable (auto focusability included, as for apps targeting O and later).
	 */
	public boolean hasFocusable() {
		if (getVisibility() != VISIBLE || !isEnabled())
			return false;
		return isFocusable();
	}
	private static native boolean nativeIsFocused(long widget);
	/** AOSP: whether this view is the focused view of its window. */
	public boolean isFocused() {
		return tlFocused;
	}

	/** AOSP: detaches the animation (its end listener fires if it was running) and drops its transformation */
	public void clearAnimation() {
		final Animation a = tlAnimation;
		tlAnimation = null;
		if (a != null)
			a.detach();
		if (tlAnimApplied || tlAnimAlpha != 1f) {
			tlAnimApplied = false;
			tlAnimAlpha = 1f;
			tlAnimTransformation.clear();
			tlTransformChanged();
			tlApplyAnimAlpha();
		}
	}

	private ViewPropertyAnimator viewPropertyAnimator;
	public ViewPropertyAnimator animate() {
		if (viewPropertyAnimator == null)
			viewPropertyAnimator = new ViewPropertyAnimator(this);
		return viewPropertyAnimator;
	}

	public float getTranslationX() {
		return translationX;
	}

	public float getTranslationY() {
		return translationY;
	}

	public void setTranslationX(float translationX) {
		this.translationX = translationX;
		if (tlLayerMatrix != null) {
			tlTransformChanged(); // the published matrix carries the translation
			return;
		}
		if (parent instanceof View)
			((View)parent).native_queueAllocate(((View)parent).widget);
	}

	public void setTranslationY(float translationY) {
		this.translationY = translationY;
		if (tlLayerMatrix != null) {
			tlTransformChanged();
			return;
		}
		if (parent instanceof View)
			((View)parent).native_queueAllocate(((View)parent).widget);
	}

	public void setX(float x) {
		setTranslationX(x - left);
	}

	public void setY(float y) {
		setTranslationY(y - top);
	}

	public void setAlpha(float alpha) {
		this.alpha = alpha;
		// a running view animation's alpha (and the transition alpha) multiply it, as in AOSP
		native_setVisibility(widget, visibility, alpha * getTransitionAlpha() * tlAnimAlpha, atl_enabled);
	}

	public boolean onGenericMotionEvent(MotionEvent event) { return false; }

	public boolean dispatchGenericMotionEvent(MotionEvent event) {
		return onGenericMotionEvent(event);
	}

	/**
	 * AOSP: shows the scroll bars for a while; false when no scroll bar is drawn (the view has none
	 * enabled), which tells callers to invalidate themselves. Scroll bars are not drawn on this
	 * platform yet (TODO-real), so the scroll bars never awaken and callers redraw the view.
	 */
	protected boolean awakenScrollBars() {
		return false;
	}

	protected native boolean native_getMatrix(long widget, long matrix);
	public Matrix getMatrix() {
		tlCheckPivotForSize();
		final Matrix m = new Matrix();
		tlPropertyMatrix(m);
		return m;
	}

	protected static final int[] EMPTY_STATE_SET = new int[0];

	protected static final int[] ENABLED_STATE_SET = new int[] {R.attr.state_enabled};

	protected static final int[] PRESSED_ENABLED_STATE_SET = new int[] {R.attr.state_pressed, R.attr.state_enabled};

	protected static final int[] SELECTED_STATE_SET = new int[] {R.attr.state_selected};

	/**
	 * Utility to return a default size. Uses the supplied size if the
	 * MeasureSpec imposed no constraints. Will get larger if allowed
	 * by the MeasureSpec.
	 *
	 * @param size Default size for this view
	 * @param measureSpec Constraints imposed by the parent
	 * @return The size this view should be.
	 */
	public static int getDefaultSize(int size, int measureSpec) {
		int result = size;
		int specMode = MeasureSpec.getMode(measureSpec);
		int specSize = MeasureSpec.getSize(measureSpec);
		switch (specMode) {
			case MeasureSpec.UNSPECIFIED:
				result = size;
				break;
			case MeasureSpec.AT_MOST:
			case MeasureSpec.EXACTLY:
				result = specSize;
				break;
		}
		return result;
	}

	/** Base class for derived classes that want to save and restore their own state in {@link #onSaveInstanceState()}. (AOSP) */
	public static class BaseSavedState extends AbsSavedState {
		public BaseSavedState(android.os.Parcel source) {
			this(source, null);
		}

		public BaseSavedState(android.os.Parcel source, ClassLoader loader) {
			super(source, loader);
		}

		public BaseSavedState(android.os.Parcelable superState) {
			super(superState);
		}

		public static final android.os.Parcelable.Creator<BaseSavedState> CREATOR = new android.os.Parcelable.ClassLoaderCreator<BaseSavedState>() {
			@Override
			public BaseSavedState createFromParcel(android.os.Parcel in) {
				return new BaseSavedState(in);
			}

			@Override
			public BaseSavedState createFromParcel(android.os.Parcel in, ClassLoader loader) {
				return new BaseSavedState(in, loader);
			}

			@Override
			public BaseSavedState[] newArray(int size) {
				return new BaseSavedState[size];
			}
		};
	}

	/** AOSP: gives up focus; outside touch mode the root view then picks a new focused view (tl_touch_mode). */
	public void clearFocus() {
		final boolean refocus = tlAlwaysAssignFocus() || !isInTouchMode();
		tlClearFocusInternal(true, refocus);
	}

	public static View inflate(Context context, int resource, ViewGroup root) {
		LayoutInflater factory = LayoutInflater.from(context);
		return factory.inflate(resource, root);
	}

	/** AOSP: stores the frozen state of this view (and, for a ViewGroup, its children) in container */
	public void saveHierarchyState(SparseArray<Parcelable> array) {
		dispatchSaveInstanceState(array);
	}

	/** AOSP: a view with an id and saving enabled stores onSaveInstanceState() under its id */
	protected void dispatchSaveInstanceState(SparseArray<Parcelable> container) {
		if (getId() != NO_ID && tlSaveEnabled) {
			Parcelable state = onSaveInstanceState();
			if (state != null) {
				container.put(getId(), state);
			}
		}
	}

	/** AOSP: the view with an id gets back the state stored under it */
	protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
		if (getId() != NO_ID) {
			Parcelable state = container.get(getId());
			if (state != null) {
				onRestoreInstanceState(state);
			}
		}
	}

	/** AOSP: the view takes its drawable state from its parent (see getDrawableState). */
	public void setDuplicateParentStateEnabled(boolean enabled) {
		tlDuplicateParentState = enabled;
	}
	private boolean tlDuplicateParentState;

	public boolean performClick() {
		return callOnClick();
	}

	/**
	 * AOSP: plays a sound effect for this view when it is in a window and its sound effects are
	 * enabled; the window passes it to AudioManager.playSoundEffect, which plays it when the system's
	 * sound effects are on.
	 */
	public void playSoundEffect(int soundConstant) {
		if (!isAttachedToWindow() || !isSoundEffectsEnabled())
			return;
		android.media.AudioManager am = (android.media.AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE);
		if (am != null)
			am.playSoundEffect(soundConstant);
	}

	public void computeScroll() {}

	/** AOSP: the background (and foreground) skip their state-change animations */
	public void jumpDrawablesToCurrentState() {
		final Drawable bg = getBackground();
		if (bg != null)
			bg.jumpToCurrentState();
		final Drawable fg = getForeground();
		if (fg != null)
			fg.jumpToCurrentState();
	}

	public void setOnFocusChangeListener(View.OnFocusChangeListener l) {
		mOnFocusChangeListener = l;
	}

	/** AOSP: whether this view's window currently has focus (recorded on the window's root view). */
	public boolean hasWindowFocus() {
		View v = this;
		while (v.getParent() instanceof View)
			v = (View) v.getParent();
		return v.tlWindowFocus;
	}

	/** AOSP: whether dispatchSaveInstanceState stores this view's state (on by default) */
	public void setSaveEnabled(boolean enabled) {
		tlSaveEnabled = enabled;
	}

	public boolean willNotDraw() { return tlWillNotDraw; }

	/** AOSP: the listener fills the context menu shown on a long press, which makes the view long-clickable */
	public void setOnCreateContextMenuListener(View.OnCreateContextMenuListener l) {
		if (!isLongClickable())
			setLongClickable(true);
		tlOnCreateContextMenuListener = l;
	}

	protected void onAnimationStart() {}

	protected void onAnimationEnd() {}

	/** AOSP: starts the animation on the next frame; the view samples it every frame until it ends */
	public void startAnimation(Animation animation) {
		if (animation == null)
			return;
		animation.setStartTime(Animation.START_ON_FIRST_FRAME);
		setAnimation(animation);
		invalidate();
	}

	public void getDrawingRect(Rect rect) {
		rect.left = getScrollX();
		rect.top = getScrollY();
		rect.right = getScrollX() + getWidth();
		rect.bottom = getScrollY() + getHeight();
	}

	public Display getDisplay() {
		return new Display();
	}

	private int importantForAccessibility;
	public void setImportantForAccessibility(int importantForAccessibility) {
		this.importantForAccessibility = importantForAccessibility;
	}
	public int getImportantForAccessibility() {
		return importantForAccessibility;
	}

	// (layout) getFitsSystemWindows: see View+layout.frag (AOSP window insets)

	// (layout) setOnApplyWindowInsetsListener: see View+layout.frag (AOSP window insets)

	/** AOSP: FOCUSABLE, or FOCUSABLE_AUTO on a clickable view. */
	public final boolean isFocusable() {
		if ((tlFocusableMode & FOCUSABLE_AUTO) != 0)
			return isClickable();
		return (tlFocusableMode & FOCUSABLE) != 0;
	}
	public boolean isClickable() { return mClickable; }
	public boolean isLongClickable() { return mLongClickable; }

	public int getLayoutDirection() { return LAYOUT_DIRECTION_LTR; }

	public void setBackground(Drawable background) {
		setBackgroundDrawable(background);
	}

	private float elevation;
	/** AOSP: the base Z of the view; siblings are drawn in Z order */
	public void setElevation(float elevation) {
		if (this.elevation != elevation) {
			this.elevation = elevation;
			tlZChanged();
			invalidate();
		}
	}
	public float getElevation() {
		return elevation;
	}

	/** AOSP: the view went through at least one layout since it was last attached to a window */
	public boolean isLaidOut() { return tlLaidOut; }

	public void postOnAnimation(Runnable action) {
		postDelayed(action, 1000 / 60);
	}

	public void postOnAnimationDelayed(Runnable action, long delayMillis) {
		if (delayMillis < 1000 / 60)
			delayMillis = 1000 / 60;
		postDelayed(action, delayMillis);
	}

	/** AOSP: the horizontal scroll bar is drawn when the view scrolls (state; drawing is TODO-real) */
	public void setHorizontalScrollBarEnabled(boolean enabled) {
		tlHorizontalScrollBar = enabled;
	}

	/** AOSP: the vertical scroll bar is drawn when the view scrolls (state; drawing is TODO-real) */
	public void setVerticalScrollBarEnabled(boolean enabled) {
		tlVerticalScrollBar = enabled;
	}

	public void postInvalidateOnAnimation() {
		postInvalidate();
	}

	/** AOSP: start/end padding; the layout direction is always LTR here, so start is left */
	public void setPaddingRelative(int start, int top, int end, int bottom) {
		setPadding(start, top, end, bottom);
		tlPaddingRelative = true;
	}

	private native boolean nativeIsAttachedToWindow(long widget);
	public boolean isAttachedToWindow() {
		return nativeIsAttachedToWindow(widget);
	}

	// (layout) requestApplyInsets: see View+layout.frag (AOSP window insets)

	public View getRootView() {
		View view = this;
		while (view.parent instanceof View) {
			view = (View)view.parent;
		}
		return view;
	}

	/**
	 * AOSP: visible, with every ancestor visible, and attached to a window. ATL's content view has
	 * no parent view, so reaching a parentless view means attached-to-window decides.
	 */
	public boolean isShown() {
		View current = this;
		do {
			if (current.getVisibility() != VISIBLE)
				return false;
			ViewParent parent = current.getParent();
			if (parent == null)
				return current.isAttachedToWindow();
			if (!(parent instanceof View))
				return true;
			current = (View) parent;
		} while (current != null);
		return false;
	}

	public int getWindowVisibility() { return VISIBLE; }

	public float getAlpha() { return alpha; }

	/** AOSP: this view when it is focused (ViewGroup: the focused view in its subtree), else null. */
	public View findFocus() { return tlFocused ? this : null; }

	public int getMinimumHeight() { return minHeight; }
	public int getMinimumWidth() { return minWidth; }

	public boolean isNestedScrollingEnabled() { return false; }

	/** AOSP: the view's drawing (background included) is clipped to its outline */
	public void setClipToOutline(boolean clipToOutline) {
		if (tlClipToOutline == clipToOutline)
			return;
		tlClipToOutline = clipToOutline;
		tlRebuildOutline();
		invalidate();
	}

	/** AOSP: transient state (an animation, a selection being edited) is counted; true while held */
	public boolean hasTransientState() { return tlTransientStateCount > 0; }

	/** AOSP: drops the click and long press this view has pending from earlier input */
	public final void cancelPendingInputEvents() {
		dispatchCancelPendingInputEvents();
	}

	void dispatchCancelPendingInputEvents() {
		onCancelPendingInputEvents();
	}

	/** AOSP: removes the pending tap/long-press checks (and a pending click) */
	public void onCancelPendingInputEvents() {
		removeTapCallback();
		removeLongPressCallback();
	}

	/** AOSP: ViewOutlineProvider.BACKGROUND unless set */
	public ViewOutlineProvider getOutlineProvider() { return tlOutlineProvider; }
	public void setOutlineProvider(ViewOutlineProvider provider) {
		tlOutlineProvider = provider;
		invalidateOutline();
	}

	public void setStateListAnimator(StateListAnimator stateListAnimator) {
		if (stateListAnimator != null && stateListAnimator.enabledAnimator != null) {
			stateListAnimator.enabledAnimator.setTarget(this);
			stateListAnimator.enabledAnimator.start();
		}
	}

	private static final AtomicInteger nextGeneratedId = new AtomicInteger(1);
	/**
     * Generate a value suitable for use in {@link #setId(int)}.
     * This value will not collide with ID values generated at build time by aapt for R.id.
     *
     * @return a generated ID value
     */
	public static int generateViewId() {
		for (;;) {
			final int result = nextGeneratedId.get();
			// aapt-generated IDs have the high byte nonzero; clamp to the range under that.
			int newValue = result + 1;
			if (newValue > 0x00FFFFFF)
				newValue = 1; // Roll over to 1, not 0.
			if (nextGeneratedId.compareAndSet(result, newValue)) {
				return result;
			}
		}
	}

	public boolean isLayoutDirectionResolved() { return true; }

	/** AOSP: the padding was set as start/end (setPaddingRelative, paddingStart/End attributes) */
	public boolean isPaddingRelative() { return tlPaddingRelative; }

	public void setForeground(Drawable foreground) {}

	// (layout) canScrollVertically: see View+layout.frag

	/** AOSP: the display's touch mode (tl_touch_mode). */
	public boolean isInTouchMode() { return tlTouchModeState(); }

	public void stopNestedScroll() {
		if (mNestedScrollingParent != null) {
			mNestedScrollingParent.onStopNestedScroll(this);
			mNestedScrollingParent = null;
		}
	}

	public long getDrawingTime() {
		return System.currentTimeMillis();
	}

	private boolean keepScreenOn = false;
	private static native void native_keep_screen_on(long widget, boolean keepScreenOn);
	public void setKeepScreenOn(boolean screenOn) {
		if (isAttachedToWindow() && keepScreenOn != screenOn)
			native_keep_screen_on(widget, screenOn);
		keepScreenOn = screenOn;
	}

	protected void onAttachedToWindow() {
		if (System.getenv("ATL_DEBUG_ATTACH") != null)
			android.util.Log.i("View", "onAttachedToWindow " + getClass().getName() + " " + getWidth() + "x" + getHeight());
		if (onAttachStateChangeListener != null) {
			onAttachStateChangeListener.onViewAttachedToWindow(this);
		}
		if (keepScreenOn)
			native_keep_screen_on(widget, true);
		if (floating_observer != null) {
			getViewTreeObserver().merge(floating_observer);
			floating_observer = null;
		}
		/* AOSP dispatchAttachedToWindow: a shown view learns it is visible once it is in a window */
		if (isShown())
			onVisibilityAggregated(true);
		/* AOSP ViewRootImpl: a window root gets the window insets on its first traversal (layout) */
		if (!(parent instanceof View))
			tlScheduleApplyInsets();
	}
	protected void onDetachedFromWindow() {
		tlLaidOut = false; // AOSP dispatchDetachedFromWindow clears PFLAG3_IS_LAID_OUT
		/* AOSP dispatchDetachedFromWindow: a visible view becomes invisible when it leaves the window */
		if (tlIsAggregatedVisible())
			onVisibilityAggregated(false);
		if (onAttachStateChangeListener != null) {
			onAttachStateChangeListener.onViewDetachedFromWindow(this);
		}
		if (keepScreenOn)
			native_keep_screen_on(widget, false);
	}

	/**
	 * AOSP: the view renders into an offscreen layer of the given type, composited with the layer
	 * paint's alpha, mode and colour filter.
	 */
	public void setLayerType(int layerType, Paint paint) {
		if (layerType < LAYER_TYPE_NONE || layerType > LAYER_TYPE_HARDWARE) {
			throw new IllegalArgumentException("Layer type can only be one of: LAYER_TYPE_NONE, LAYER_TYPE_SOFTWARE or LAYER_TYPE_HARDWARE");
		}
		if (layerType == tlLayerType) {
			setLayerPaint(paint);
			return;
		}
		tlLayerType = layerType;
		mLayerPaint = layerType == LAYER_TYPE_NONE ? null : paint;
		invalidate();
	}

	public float getZ() {
		return getElevation() + getTranslationZ();
	}

	protected void onSizeChanged(int w, int h, int oldw, int oldh) {}

	/** AOSP: the background's tint list (kept across setBackground*), applied through applyBackgroundTint(). */
	public void setBackgroundTintList(ColorStateList tint) {
		tlBgTintList = tint;
		tlHasBgTintList = true;
		applyBackgroundTint();
	}

	protected int computeHorizontalScrollRange() {
		return getWidth();
	}

	protected int computeHorizontalScrollExtent() {
		return getWidth();
	}

	protected int computeVerticalScrollRange() {
		return getHeight();
	}

	protected int computeVerticalScrollExtent() {
		return getHeight();
	}

	/** AOSP: how accessibility services hear about changes of this view (stored; no service here) */
	public void setAccessibilityLiveRegion(int mode) {
		tlAccessibilityLiveRegion = mode;
	}

	/** AOSP: the outline provider is asked again (size, padding or drawable changed) */
	public void invalidateOutline() {
		tlRebuildOutline();
		invalidate();
	}

	public int getMeasuredWidthAndState() {
		return measuredWidth;
	}

	public void forceLayout() {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			requestLayout();
		} else {
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					requestLayout();
				}
			});
		}
	}

	private OnAttachStateChangeListener onAttachStateChangeListener;
	public void removeOnAttachStateChangeListener(OnAttachStateChangeListener listener) {
		if (onAttachStateChangeListener == listener) {
			onAttachStateChangeListener = null;
		}
	}

	public boolean onInterceptTouchEvent(MotionEvent event) { return false; }

	/**
	 * AOSP View.dispatchTouchEvent: the touch listener first, then onTouchEvent. Reached for events that
	 * Java code dispatches to a view (a group whose children declined, Compose interop); the native
	 * side records whether the event is synthetic so the click is performed here rather than by the
	 * GTK click gesture.
	 */
	public boolean dispatchTouchEvent(MotionEvent event) {
		return onTouchEventInternal(event, tlNativeDispatchSynthetic);
	}

	// (layout) canScrollHorizontally: see View+layout.frag

	protected native boolean native_getGlobalVisibleRect(long widget, Rect visibleRect);

	public boolean getGlobalVisibleRect(Rect visibleRect) {
		return native_getGlobalVisibleRect(widget, visibleRect);
	}

	public boolean onCheckIsTextEditor() { return false; }

	/** AOSP: an OnClickListener is attached */
	public boolean hasOnClickListeners() { return on_click_listener != null; }

	public void setTextAlignment(int textAlignment) {
		String[] classesToRemove = {"ATL-text-align-left", "ATL-text-align-center", "ATL-text-align-right"};
		native_removeClasses(widget, classesToRemove);

		switch (textAlignment) {
			case TEXT_ALIGNMENT_CENTER:
				native_addClass(widget, "ATL-text-align-center");
				break;
			case TEXT_ALIGNMENT_TEXT_START:
			case TEXT_ALIGNMENT_VIEW_START:
				native_addClass(widget, "ATL-text-align-left");
				break;
			case TEXT_ALIGNMENT_TEXT_END:
			case TEXT_ALIGNMENT_VIEW_END:
				native_addClass(widget, "ATL-text-align-right");
				break;
		}
	}

	/** AOSP: haptic feedback for events such as long presses (on by default) */
	public void setHapticFeedbackEnabled(boolean hapticFeedbackEnabled) {
		tlHapticFeedbackEnabled = hapticFeedbackEnabled;
	}

	public StateListAnimator getStateListAnimator() { return null; }

	// (layout) requestFitSystemWindows: see View+layout.frag

	public boolean isPressed() { return pressed; }

	public native void getWindowVisibleDisplayFrame(Rect rect);

	public void setRotation(float rotation) {
		if (tlRotation != rotation) {
			tlRotation = rotation;
			tlTransformChanged();
		}
	}

	public void setRotationX(float deg) {
		if (tlRotationX != deg) {
			tlRotationX = deg;
			tlTransformChanged();
		}
	}

	public void setRotationY(float deg) {
		if (tlRotationY != deg) {
			tlRotationY = deg;
			tlTransformChanged();
		}
	}

	public float getRotationX() { return tlRotationX; }
	public float getRotationY() { return tlRotationY; }

	public void setScaleX(float scaleX) {
		if (tlScaleX != scaleX) {
			tlScaleX = scaleX;
			tlTransformChanged();
		}
	}

	public void setScaleY(float scaleY) {
		if (tlScaleY != scaleY) {
			tlScaleY = scaleY;
			tlTransformChanged();
		}
	}

	public float getScaleX() { return tlScaleX; }
	public float getScaleY() { return tlScaleY; }

	public void setPivotX(float pivot_x) {
		if (!tlPivotSet || tlPivotX != pivot_x) {
			tlPivotSet = true;
			tlPivotX = pivot_x;
			tlTransformChanged();
		}
	}

	public void setPivotY(float pivot_y) {
		if (!tlPivotSet || tlPivotY != pivot_y) {
			tlPivotSet = true;
			tlPivotY = pivot_y;
			tlTransformChanged();
		}
	}

	/** AOSP: the centre of the view unless a pivot was set */
	public float getPivotX() { return tlPivotSet ? tlPivotX : getWidth() / 2f; }
	public float getPivotY() { return tlPivotSet ? tlPivotY : getHeight() / 2f; }

	public float getTranslationZ() {
		return mTranslationZ;
	}

	/** AOSP: Z relative to the elevation (animated by state list animators); siblings are drawn in Z order */
	public void setTranslationZ(float translationZ) {
		if (mTranslationZ != translationZ) {
			mTranslationZ = translationZ;
			tlZChanged();
			invalidate();
		}
	}

	public int getWindowSystemUiVisibility() {
		return 0;
	}

	/** AOSP: the scroll indicators in mask are set to indicators (drawing is TODO-real, like scroll bars) */
	public void setScrollIndicators(int indicators, int mask) {
		mask &= SCROLL_INDICATORS_ALL;
		indicators &= mask;
		tlScrollIndicators = (tlScrollIndicators & ~mask) | indicators;
	}

	/**
	 * AOSP: stores the raw layout direction (INHERIT by default). teslalinux: resolving it against
	 * the parent and the locale (RTL mirroring) is not done yet (TODO-real.md); getLayoutDirection
	 * still resolves to LTR.
	 */
	public void setLayoutDirection(int layoutDirection) {
		tlRawLayoutDirection = layoutDirection;
	}

	public ColorStateList getBackgroundTintList() { return tlBgTintList; }

	public PorterDuff.Mode getBackgroundTintMode() { return tlBgTintMode; }

	/** AOSP: the name that identifies the view in a shared element transition */
	public String getTransitionName() { return tlTransitionName; }

	public WindowId getWindowId() { return null; }

	/** AOSP: a layout pass of the window is running (here: a layout() or layoutInternal() is on the stack) */
	public boolean isInLayout() {
		return tlLayoutDepth > 0;
	}

	public void setTextDirection(int textDirection) {}

	public Drawable getForeground() { return null; }

	public void setScrollbarFadingEnabled(boolean fadeEnabled) {}

	/** AOSP: where the scroll bars sit (inside/outside the padding, overlay/inset) */
	public void setScrollBarStyle(int style) {
		tlScrollBarStyle = style & SCROLLBARS_STYLE_MASK;
	}

	public void setVerticalScrollbarPosition(int position) {}

	public void setNestedScrollingEnabled(boolean enabled) {
		if (enabled) {
			mNestedScrollingEnabled = true;
		} else {
			stopNestedScroll();
			mNestedScrollingEnabled = false;
		}
	}

	public void setTouchDelegate(TouchDelegate touchDelegate) {
		mTouchDelegate = touchDelegate;
	}
	public TouchDelegate getTouchDelegate() {
		return mTouchDelegate;
	}

	public void setOnDragListener(OnDragListener onDragListener) {}

	public void setTransitionName(String transitionName) {
		tlTransitionName = transitionName;
	}

	public Animation getAnimation() { return tlAnimation; }

	public ViewOverlay getOverlay() {
		return new ViewOverlay();
	}

	public void cancelLongPress() {}

	public int getTextAlignment() { return 0; }

	public View findViewWithTag(Object tag) {
		if (Objects.equals(tag, this.tag))
			return this;
		else
			return null;
	}

	public void setTooltipText(CharSequence tooltip) {}

	public int getImportantForAutofill() { return 0; }

	public void setImportantForAutofill(int flag) {}

	/** AOSP: whether a focused view without a focused state drawable gets the default highlight */
	public void setDefaultFocusHighlightEnabled(boolean enabled) {
		tlDefaultFocusHighlightEnabled = enabled;
	}

	public boolean getDefaultFocusHighlightEnabled() {
		return tlDefaultFocusHighlightEnabled;
	}

	/** AOSP: the horizontal edges fade when the view is scrolled (state; drawing is TODO-real) */
	public void setHorizontalFadingEdgeEnabled(boolean horizontalFadingEdgeEnabled) {
		tlHorizontalFadingEdge = horizontalFadingEdgeEnabled;
		if (horizontalFadingEdgeEnabled && tlFadingEdgeLength < 0)
			tlFadingEdgeLength = ViewConfiguration.get(getContext()).getScaledFadingEdgeLength();
	}

	public Handler getHandler() {
		return new Handler(Looper.getMainLooper());
	}

	/** AOSP: true for a view attached to a hardware-accelerated window; every ATL window renders through GSK. */
	public boolean isHardwareAccelerated() {
		return isAttachedToWindow();
	}

	/** AOSP: the view (background included) draws only inside clipBounds; null removes the clip */
	public void setClipBounds(Rect clipBounds) {
		if (clipBounds == tlClipBounds || (clipBounds != null && clipBounds.equals(tlClipBounds)))
			return;
		if (clipBounds != null) {
			if (tlClipBounds == null)
				tlClipBounds = new Rect(clipBounds);
			else
				tlClipBounds.set(clipBounds);
		} else {
			tlClipBounds = null;
		}
		tlNativeClipBounds();
		invalidate();
	}

	public boolean getClipToOutline() { return tlClipToOutline; }

	public void setLeft(int left) {
		layout(left, top, right, bottom);
	}

	public void setTop(int top) {
		layout(left, top, right, bottom);
	}

	public void setRight(int right) {
		layout(left, top, right, bottom);
	}

	public void setBottom(int bottom) {
		layout(left, top, right, bottom);
	}

	/** AOSP: the camera's distance in pixels (positive; the sign is ignored), for rotationX/Y */
	public void setCameraDistance(float distance) {
		tlCameraDistance = Math.abs(distance);
		if (tlRotationX != 0f || tlRotationY != 0f)
			tlTransformChanged();
	}

	/**
	 * AOSP: asks the ancestors to scroll so that rectangle (in this view's coordinates) is visible;
	 * each parent gets it in its child's coordinates. True when one of them scrolled.
	 */
	public boolean requestRectangleOnScreen(Rect rectangle, boolean immediate) {
		View child = this;
		ViewParent parent = getParent();
		android.graphics.RectF position = new android.graphics.RectF(rectangle);
		boolean scrolled = false;
		while (parent != null) {
			rectangle.set((int)position.left, (int)position.top, (int)position.right, (int)position.bottom);
			// ATL's ViewParent interface lacks requestChildRectangleOnScreen; its groups have it, and the
			// window root (AOSP ViewRootImpl: scrolls only for the soft input) has nothing to scroll
			if (parent instanceof ViewGroup)
				scrolled |= ((ViewGroup)parent).requestChildRectangleOnScreen(child, rectangle, immediate);
			if (!(parent instanceof View))
				break;
			// move it from child's content coordinate space to parent's content coordinate space
			position.offset(child.getLeft() - child.getScrollX(), child.getTop() - child.getScrollY());
			child = (View)parent;
			parent = child.getParent();
		}
		return scrolled;
	}

	public boolean requestRectangleOnScreen(Rect rectangle) {
		return requestRectangleOnScreen(rectangle, false);
	}

	public boolean onKeyDown(int keyCode, KeyEvent event) {
		return false;
	}

	public boolean dispatchKeyEvent(KeyEvent event) {
		if (mOnKeyListener != null && mOnKeyListener.onKey(this, event.getKeyCode(), event))
			return true;
		switch (event.getAction()) {
			case KeyEvent.ACTION_DOWN:
				return onKeyDown(event.getKeyCode(), event);
			case KeyEvent.ACTION_UP:
				return onKeyUp(event.getKeyCode(), event);
			case KeyEvent.ACTION_MULTIPLE:
				return onKeyMultiple(event.getKeyCode(), event.getRepeatCount(), event);
		}
		return false;
	}

	/**
	 * AOSP: the insets the window's root view got; null only while the view is not attached. This
	 * platform has no status or navigation bars and no cutout, so they are empty. teslalinux: the
	 * on-screen keyboard (native-osk.sh) is not reported as an IME inset yet (TODO-real.md).
	 */
	// (layout) getRootWindowInsets: see View+layout.frag (AOSP window insets)

	public PointerIcon getPointerIcon() {
		return mPointerIcon;
	}

	public void setPointerIcon(PointerIcon pointerIcon) {
		mPointerIcon = pointerIcon;
	}

	/**
	 * AOSP: the token of the application window this view is attached to (a panel's or dialog's
	 * parent window token), null when detached. All views of the app window share the window token.
	 */
	public IBinder getApplicationWindowToken() {
		return isAttachedToWindow() ? getWindowToken() : null;
	}

	/** AOSP: the fading edge length when vertical fading edges are enabled, else 0 */
	public int getVerticalFadingEdgeLength() {
		return tlVerticalFadingEdge ? Math.max(tlFadingEdgeLength, 0) : 0;
	}
	public int getVerticalScrollbarWidth() { return 0; }

	public void saveAttributeDataForStyleable(Context ctxt, int[] styleable, AttributeSet attrs, TypedArray t, int defStyleAttr, int defStyleRes) {}

	public final View requireViewById(int id) {
		View view = findViewById(id);
		if (view == null)
			throw new IllegalArgumentException("ID does not reference a View inside this View");
		return view;
	}
	public float getTransitionAlpha() {
		return mTransitionAlpha;
	}

	/** AOSP: losing window focus ends a press; state_window_focused changes */
	public void onWindowFocusChanged(boolean hasFocus) {
		if (!hasFocus && pressed)
			setPressed(false);
		refreshDrawableState();
	}

	/** AOSP: attaches the animation; it runs from its start time (START_ON_FIRST_FRAME: the next frame) */
	public void setAnimation(Animation animation) {
		if (tlAnimation == animation)
			return;
		if (tlAnimation != null)
			clearAnimation();
		tlAnimation = animation;
		tlAnimStartReported = false;
		if (animation != null)
			tlScheduleAnimationFrame();
	}

	/** AOSP: the action goes to performAccessibilityActionInternal (an accessibility delegate would see it first). */
	public boolean performAccessibilityAction(int action, Bundle arguments) {
		return performAccessibilityActionInternal(action, arguments);
	}

	public boolean isDirty() { return false; }

	public float getX() {
		return getLeft() + getTranslationX();
	}
	public float getY() {
		return getTop() + getTranslationY();
	}

	public boolean getGlobalVisibleRect(Rect visibleRect, Point globalOffset) {
		boolean result = native_getGlobalVisibleRect(widget, visibleRect);
		globalOffset.set(visibleRect.left, visibleRect.top);
		return result;
	}

	public void restoreHierarchyState(SparseArray<Parcelable> container) {}

	public boolean isHovered() { return false; }

	public void scrollBy(int x, int y) {
		scrollTo(scrollX + x, scrollY + y);
	}

	/** AOSP: the view is an accessibility pane with this title (stored; no accessibility service here) */
	public void setAccessibilityPaneTitle(CharSequence paneTitle) {
		tlAccessibilityPaneTitle = paneTitle;
	}

	/** AOSP: the view is a heading for accessibility (stored; no accessibility service here) */
	public void setAccessibilityHeading(boolean heading) {
		tlAccessibilityHeading = heading;
	}

	// (layout) computeSystemWindowInsets: see View+layout.frag (AOSP window insets)

	public boolean isDuplicateParentStateEnabled() {
		return tlDuplicateParentState;
	}

	/** AOSP: the blending mode of the background tint (SRC_IN when only a tint list is given). */
	public void setBackgroundTintMode(PorterDuff.Mode tintMode) {
		tlBgTintMode = tintMode;
		tlHasBgTintMode = true;
		applyBackgroundTint();
	}

	/** AOSP: the view focused by FOCUS_LEFT from this one (attribute nextFocusLeft) */
	public void setNextFocusLeftId(int id) { tlNextFocusLeftId = id; }

	/** AOSP: the view focused by FOCUS_RIGHT from this one (attribute nextFocusRight) */
	public void setNextFocusRightId(int id) { tlNextFocusRightId = id; }

	/** AOSP: the view focused by FOCUS_DOWN from this one (attribute nextFocusDown) */
	public void setNextFocusDownId(int id) { tlNextFocusDownId = id; }

	/** AOSP: the view focused by FOCUS_UP from this one (attribute nextFocusUp) */
	public void setNextFocusUpId(int id) { tlNextFocusUpId = id; }

	/** AOSP: a nested count: every true must be matched by a false */
	public void setHasTransientState(boolean hasTransientState) {
		final int oldCount = tlTransientStateCount;
		tlTransientStateCount = hasTransientState ? tlTransientStateCount + 1 : tlTransientStateCount - 1;
		if (tlTransientStateCount < 0) {
			tlTransientStateCount = 0;
			android.util.Log.e("View", "hasTransientState decremented below 0: unmatched pair of setHasTransientState calls");
		}
		// AOSP also tells the parent (ViewParent.childHasTransientStateChanged), which ATL's groups lack
	}

	protected void onConfigurationChanged(Configuration newConfig) {}

	public boolean isDrawingCacheEnabled() { return false; }

	public void setDrawingCacheEnabled(boolean enabled) {}

	public void buildDrawingCache(boolean autoScale) {}

	public Bitmap getDrawingCache() { return null; }

	public void announceForAccessibility(CharSequence text) {}

	public WindowInsetsController getWindowInsetsController() {
		/* teslalinux: the decor view has no native window yet when AndroidX's EdgeToEdge asks during onCreate */
		Window tlw = native_get_window(widget);
		return tlw != null ? tlw.getInsetsController() : new InsetsController();
	}

	/** AOSP: the view is the root of a keyboard navigation cluster (attribute keyboardNavigationCluster) */
	public final boolean isKeyboardNavigationCluster() { return tlKeyboardNavigationCluster; }

	public void setKeyboardNavigationCluster(boolean isCluster) {
		tlKeyboardNavigationCluster = isCluster;
	}

	public AutofillId getAutofillId() {
		if (mAutofillId == null) {
			// AOSP: created on demand; the value only has to be unique within the process
			mAutofillId = new AutofillId(getAutofillViewId());
		}
		return mAutofillId;
	}

	public AccessibilityDelegate getAccessibilityDelegate() { return null; }

	/** AOSP: scrollTo(value, scrollY) */
	public void setScrollX(int value) {
		scrollTo(value, scrollY);
	}
	/** AOSP: scrollTo(scrollX, value) */
	public void setScrollY(int value) {
		scrollTo(scrollX, value);
	}

	protected boolean dispatchHoverEvent(MotionEvent event) { return false; }

	public ActionMode startActionMode(ActionMode.Callback callback, int type) { return null; }

	public InputConnection onCreateInputConnection(EditorInfo outAttrs) { return null; }

	public ActionMode startActionMode(ActionMode.Callback callback) { return null; }

	/** AOSP: the focused area of the view, its drawing rect (scrolled bounds) by default */
	public void getFocusedRect(Rect r) {
		getDrawingRect(r);
	}

	/**
	* An implementation of OnClickListener that attempts to lazily load a
	* named click handling method from a parent or ancestor context.
	* See AOSP commit d13f0e21bf8059779c9330a5993907ec88c8e781
	* Copyright (C) 2006 The Android Open Source Project
	*/
	private static class DeclaredOnClickListener implements OnClickListener {
		private final View mHostView;
		private final String mMethodName;
		private Method mResolvedMethod;
		private Context mResolvedContext;
		public DeclaredOnClickListener(@NonNull View hostView, @NonNull String methodName) {
			mHostView = hostView;
			mMethodName = methodName;
		}
		@Override
		public void onClick(@NonNull View v) {
			if (mResolvedMethod == null)
				resolveMethod(mHostView.getContext(), mMethodName);
			try {
				mResolvedMethod.invoke(mResolvedContext, v);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(
				    "Could not execute non-public method for android:onClick", e);
			} catch (InvocationTargetException e) {
				throw new IllegalStateException(
				    "Could not execute method for android:onClick", e);
			}
		}

		@NonNull
		private void resolveMethod(@Nullable Context context, @NonNull String name) {
			while (context != null) {
				try {
					if (!context.isRestricted()) {
						final Method method = context.getClass().getMethod(mMethodName, View.class);
						if (method != null) {
							mResolvedMethod = method;
							mResolvedContext = context;
							return;
						}
					}
				} catch (NoSuchMethodException e) {
					// Failed to find method, keep searching up the hierarchy.
				}
				if (context instanceof ContextWrapper) {
					context = ((ContextWrapper)context).getBaseContext();
				} else {
					// Can't search up the hierarchy, null out and fail.
					context = null;
				}
			}

			final int id = mHostView.getId();
			final String idText = id == NO_ID ? "" : " with id '" + mHostView.getResources().getResourceEntryName(id) + "'";
			throw new IllegalStateException("Could not find method " + mMethodName
			                                + "(View) in a parent or ancestor Context for android:onClick "
			                                + "attribute defined on view " + mHostView.getClass() + idText);
		}
	}
	public void setForceDarkAllowed(boolean forceDark) {}
	public void setWindowInsetsAnimationCallback(WindowInsetsAnimation.Callback callback) {}
	public void setViewTranslationCallback(ViewTranslationCallback callback) {}
	public void clearViewTranslationCallback() {}
	public void transformMatrixToGlobal(Matrix matrix) {
		final ViewParent p = getParent();
		if (p instanceof View) {
			final View vp = (View) p;
			vp.transformMatrixToGlobal(matrix);
			matrix.preTranslate(-vp.getScrollX(), -vp.getScrollY());
		}
		matrix.preTranslate(getLeft(), getTop());
		matrix.preConcat(getMatrix());
	}
	/** AOSP: the "Show layout bounds" developer option (debug.layout). ATL_SHOW_LAYOUT_BOUNDS=1 turns it on here. */
	public boolean isShowingLayoutBounds() {
		return System.getenv("ATL_SHOW_LAYOUT_BOUNDS") != null;
	}

	public boolean callOnClick() {
		if (on_click_listener != null) {
			on_click_listener.onClick(this);
			return true;
		} else {
			return false;
		}
	}

	public void destroyDrawingCache() {}

	public void setHovered(boolean isHovered) {}

	/** AOSP: the vertical edges fade when the view is scrolled (state; drawing is TODO-real) */
	public void setVerticalFadingEdgeEnabled(boolean verticalFadingEdgeEnabled) {
		tlVerticalFadingEdge = verticalFadingEdgeEnabled;
		if (verticalFadingEdgeEnabled && tlFadingEdgeLength < 0)
			tlFadingEdgeLength = ViewConfiguration.get(getContext()).getScaledFadingEdgeLength();
	}

	/** AOSP: the fading edge length in pixels */
	public void setFadingEdgeLength(int length) {
		tlFadingEdgeLength = length;
	}

	public int getAccessibilityLiveRegion() { return tlAccessibilityLiveRegion; }

	/** AOSP: the id of the view this one labels for accessibility */
	public void setLabelFor(int id) {
		tlLabelFor = id;
	}

	/** AOSP: the focusable views of this view (and, for a ViewGroup, its descendants) for a direction */
	public ArrayList<View> getFocusables(int direction) {
		ArrayList<View> result = new ArrayList<View>(24);
		addFocusables(result, direction);
		return result;
	}

	/* teslalinux: View additions used by Compose / AppCompat at first layout */
	public static final int LAYER_TYPE_NONE = 0, LAYER_TYPE_SOFTWARE = 1, LAYER_TYPE_HARDWARE = 2;
	private int tlLayerType = LAYER_TYPE_NONE;
	// (layout) dispatchApplyWindowInsets: see View+layout.frag (AOSP window insets)
	// (layout) onApplyWindowInsets: see View+layout.frag (AOSP window insets)
	public int getLayerType() { return tlLayerType; }
	/**
	 * AOSP: the view fills its bounds with opaque content: an opaque background, full alpha (no
	 * scroll bars are drawn over the padding here).
	 */
	public boolean isOpaque() {
		final Drawable bg = getBackground();
		return bg != null && bg.getOpacity() == android.graphics.PixelFormat.OPAQUE && getAlpha() >= 1.0f;
	}
	public boolean hasOverlappingRendering() { return true; }
	public long getUniqueDrawingId() { return System.identityHashCode(this); }
	public void resetPivot() {
		if (tlPivotSet) {
			tlPivotSet = false;
			tlTransformChanged();
		}
	}
	public void addOnUnhandledKeyEventListener(OnUnhandledKeyEventListener listener) {}
	public void removeOnUnhandledKeyEventListener(OnUnhandledKeyEventListener listener) {}
	/** AOSP: a copy of the clip bounds, null when there are none */
	public android.graphics.Rect getClipBounds() { return tlClipBounds != null ? new Rect(tlClipBounds) : null; }
	/** AOSP: the clip bounds into outRect; false when there are none */
	public boolean getClipBounds(android.graphics.Rect outRect) {
		if (tlClipBounds != null) {
			outRect.set(tlClipBounds);
			return true;
		}
		return false;
	}
	public int getMeasuredHeightAndState() { return getMeasuredHeight(); }
	public boolean isImportantForAccessibility() { return true; }
	public boolean isImportantForAutofill() { return false; }
	public boolean isImportantForContentCapture() { return false; }
	public int getImportantForContentCapture() { return 0; }
	public void setImportantForContentCapture(int mode) {}
	public CharSequence getAccessibilityClassName() { return View.class.getName(); }
	public CharSequence getAccessibilityPaneTitle() { return tlAccessibilityPaneTitle; }
	/** AOSP: the state text accessibility announces (a switch's "on"); stored, no service here */
	public CharSequence getStateDescription() { return tlStateDescription; }
	public void setStateDescription(CharSequence stateDescription) {
		tlStateDescription = stateDescription;
	}
	public android.window.OnBackInvokedDispatcher findOnBackInvokedDispatcher() { return null; }
	public boolean hasExplicitFocusable() { return isFocusable(); }
	/** AOSP: this view takes focus when its window (or a group's restoreDefaultFocus) picks a default */
	public boolean isFocusedByDefault() { return tlFocusedByDefault; }
	public void setFocusedByDefault(boolean isFocusedByDefault) {
		tlFocusedByDefault = isFocusedByDefault;
	}
	public boolean isAccessibilityHeading() { return tlAccessibilityHeading; }
	public boolean isAccessibilityFocused() { return false; }
	/**
	 * AOSP: areas where the app takes the system's back/edge gestures for itself; kept as AOSP keeps
	 * them (the window has no system gestures on this platform, so nothing is excluded from any).
	 */
	public java.util.List<android.graphics.Rect> getSystemGestureExclusionRects() {
		return tlGestureExclusionRects != null ? new java.util.ArrayList<>(tlGestureExclusionRects) : java.util.Collections.<android.graphics.Rect>emptyList();
	}
	public void setSystemGestureExclusionRects(java.util.List<android.graphics.Rect> rects) {
		tlGestureExclusionRects = rects == null || rects.isEmpty() ? null : new java.util.ArrayList<>(rects);
	}
	/** AOSP: areas floating windows should keep clear of (kept; no floating system windows here) */
	public java.util.List<android.graphics.Rect> getPreferKeepClearRects() {
		return tlKeepClearRects != null ? new java.util.ArrayList<>(tlKeepClearRects) : java.util.Collections.<android.graphics.Rect>emptyList();
	}
	public void setPreferKeepClearRects(java.util.List<android.graphics.Rect> rects) {
		tlKeepClearRects = rects == null || rects.isEmpty() ? null : new java.util.ArrayList<>(rects);
	}
	public int getScrollIndicators() { return tlScrollIndicators; }
	/** AOSP: the scroll indicators shown (SCROLL_INDICATOR_* bits) */
	public void setScrollIndicators(int indicators) {
		setScrollIndicators(indicators, SCROLL_INDICATORS_ALL);
	}
	public int getLabelFor() { return tlLabelFor; }
	/** AOSP: the view is about to be detached temporarily (a list recycling it); a ViewGroup forwards it */
	public void dispatchStartTemporaryDetach() {
		tlTemporarilyDetached = true;
		onStartTemporaryDetach();
	}
	/** AOSP: the temporary detach is over */
	public void dispatchFinishTemporaryDetach() {
		tlTemporarilyDetached = false;
		onFinishTemporaryDetach();
	}
	/** AOSP: the configuration reaches onConfigurationChanged (a ViewGroup forwards it to its children) */
	public void dispatchConfigurationChanged(android.content.res.Configuration newConfig) {
		onConfigurationChanged(newConfig);
	}
	/** AOSP: the hotspot moved (a touch): the background and foreground ripples follow it */
	public void drawableHotspotChanged(float x, float y) {
		final Drawable bg = getBackground();
		if (bg != null)
			bg.setHotspot(x, y);
		final Drawable fg = getForeground();
		if (fg != null)
			fg.setHotspot(x, y);
		dispatchDrawableHotspotChanged(x, y);
	}
	public boolean restoreDefaultFocus() { return requestFocus(); }
	public void cancelDragAndDrop() {}
	/** AOSP: a cluster root with something focusable adds itself */
	public void addKeyboardNavigationClusters(java.util.Collection<View> views, int direction) {
		if (!isKeyboardNavigationCluster())
			return;
		if (!hasFocusable())
			return;
		views.add(this);
	}
	/** AOSP: an enabled clickable, long-clickable or context-clickable view is a touchable */
	public void addTouchables(java.util.ArrayList<View> views) {
		if ((isClickable() || isLongClickable() || isContextClickable()) && isEnabled()) {
			views.add(this);
		}
	}

	/** AOSP: the touchable views of this view (and, for a ViewGroup, its descendants) */
	public ArrayList<View> getTouchables() {
		ArrayList<View> result = new ArrayList<View>();
		addTouchables(result);
		return result;
	}
	/**
	 * AOSP: the next keyboard navigation cluster after currentCluster; AOSP walks up to the root
	 * namespace (the window's decor), which asks the FocusFinder over the whole window.
	 */
	public View keyboardNavigationClusterSearch(View currentCluster, int direction) {
		if (isKeyboardNavigationCluster())
			currentCluster = this;
		return FocusFinder.getInstance().findNextKeyboardNavigationCluster(getRootView(), currentCluster, direction);
	}
	public ViewParent getParentForAccessibility() { return getParent(); }
	public void setAllowClickWhenDisabled(boolean clickableWhenDisabled) {}
	public boolean isPivotSet() { return tlPivotSet; }
	public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() { return null; }

	/* ---- content capture (AOSP View.java, API 29). The content capture system service does not
	 * exist in this runtime, so the context has no ContentCaptureManager and the default session is
	 * null, exactly as on a device where content capture is unavailable. ---- */
	private android.view.contentcapture.ContentCaptureSession mContentCaptureSession;
	private boolean mContentCaptureSessionCached;

	public void setContentCaptureSession(android.view.contentcapture.ContentCaptureSession contentCaptureSession) {
		mContentCaptureSession = contentCaptureSession;
	}

	public final android.view.contentcapture.ContentCaptureSession getContentCaptureSession() {
		if (mContentCaptureSessionCached) {
			return mContentCaptureSession;
		}
		mContentCaptureSession = getAndCacheContentCaptureSession();
		mContentCaptureSessionCached = true;
		return mContentCaptureSession;
	}

	private android.view.contentcapture.ContentCaptureSession getAndCacheContentCaptureSession() {
		// First try the session explicitly set by setContentCaptureSession()
		if (mContentCaptureSession != null) {
			return mContentCaptureSession;
		}
		// Then the session explicitly set in an ancestor
		android.view.contentcapture.ContentCaptureSession session = null;
		if (parent instanceof View) {
			session = ((View) parent).getContentCaptureSession();
		}
		// Finally, if no session was explicitly set, use the context default session (none here:
		// Context.CONTENT_CAPTURE_MANAGER_SERVICE resolves to no service).
		if (session == null) {
			Object ccm = getContext().getSystemService(android.content.Context.CONTENT_CAPTURE_MANAGER_SERVICE);
			return ccm == null ? null : session;
		}
		return session;
	}

	/* ---- teslalinux: View members from AOSP that ATL lacked (focus, keys, nested scrolling, state,
	 * transitions, drawable state, pointer icon, autofill ids, misc property setters) ---- */
	private boolean mNestedScrollingEnabled;
	private ViewParent mNestedScrollingParent;
	private int[] mTempNestedScrollConsumed;
	private OnKeyListener mOnKeyListener;
	private OnFocusChangeListener mOnFocusChangeListener;
	private KeyEvent.DispatcherState mKeyDispatchState;
	private android.graphics.Matrix mAnimationMatrix;
	private String[] mAutofillHints;
	private android.view.autofill.AutofillId mAutofillId;
	private int mContentSensitivity;
	private float mFrameContentVelocity = -1f;
	private android.graphics.Paint mLayerPaint;
	private int mNextClusterForwardId = NO_ID;
	private int mOutlineAmbientShadowColor = 0xFF000000;
	private int mOutlineSpotShadowColor = 0xFF000000;
	private android.graphics.RenderEffect mRenderEffect;
	private float mRequestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_DEFAULT;
	private boolean mScreenReaderFocusable;
	private float mTransitionAlpha = 1f;
	private float mTranslationZ;
	private PointerIcon mPointerIcon;
	private OnReceiveContentListener mReceiveContentListener;
	private String[] mReceiveContentMimeTypes;

	public static final float REQUESTED_FRAME_RATE_CATEGORY_DEFAULT = Float.NaN;
	public static final float REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE = -1;
	public static final float REQUESTED_FRAME_RATE_CATEGORY_LOW = -2;
	public static final float REQUESTED_FRAME_RATE_CATEGORY_NORMAL = -3;
	public static final float REQUESTED_FRAME_RATE_CATEGORY_HIGH = -4;
	public static final int AUTOFILL_TYPE_NONE = 0;
	public static final int AUTOFILL_TYPE_TEXT = 1;
	public static final int AUTOFILL_TYPE_TOGGLE = 2;
	public static final int AUTOFILL_TYPE_LIST = 3;
	public static final int AUTOFILL_TYPE_DATE = 4;
	public static final int IMPORTANT_FOR_AUTOFILL_AUTO = 0x0;
	public static final int IMPORTANT_FOR_AUTOFILL_YES = 0x1;
	public static final int IMPORTANT_FOR_AUTOFILL_NO = 0x2;
	public static final int IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS = 0x4;
	public static final int IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS = 0x8;
	public static final int LAST_APP_AUTOFILL_ID = Integer.MAX_VALUE / 2;
	public static final int CONTENT_SENSITIVITY_AUTO = 0;
	public static final int CONTENT_SENSITIVITY_SENSITIVE = 1;
	public static final int CONTENT_SENSITIVITY_NOT_SENSITIVE = 2;

	// --- nested scrolling child protocol (AOSP) ---

	public boolean startNestedScroll(int axes) {
		if (hasNestedScrollingParent()) {
			// Already in progress
			return true;
		}
		if (isNestedScrollingEnabled()) {
			ViewParent p = getParent();
			View child = this;
			while (p != null) {
				if (p.onStartNestedScroll(child, this, axes)) {
					mNestedScrollingParent = p;
					p.onNestedScrollAccepted(child, this, axes);
					return true;
				}
				if (p instanceof View) {
					child = (View) p;
				}
				p = p.getParent();
			}
		}
		return false;
	}

	public boolean hasNestedScrollingParent() {
		return mNestedScrollingParent != null;
	}

	public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed, int[] offsetInWindow) {
		if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
			if (dx != 0 || dy != 0) {
				int startX = 0;
				int startY = 0;
				if (offsetInWindow != null) {
					getLocationInWindow(offsetInWindow);
					startX = offsetInWindow[0];
					startY = offsetInWindow[1];
				}
				if (consumed == null) {
					if (mTempNestedScrollConsumed == null) {
						mTempNestedScrollConsumed = new int[2];
					}
					consumed = mTempNestedScrollConsumed;
				}
				consumed[0] = 0;
				consumed[1] = 0;
				mNestedScrollingParent.onNestedPreScroll(this, dx, dy, consumed);
				if (offsetInWindow != null) {
					getLocationInWindow(offsetInWindow);
					offsetInWindow[0] -= startX;
					offsetInWindow[1] -= startY;
				}
				return consumed[0] != 0 || consumed[1] != 0;
			} else if (offsetInWindow != null) {
				offsetInWindow[0] = 0;
				offsetInWindow[1] = 0;
			}
		}
		return false;
	}

	public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int[] offsetInWindow) {
		if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
			if (dxConsumed != 0 || dyConsumed != 0 || dxUnconsumed != 0 || dyUnconsumed != 0) {
				int startX = 0;
				int startY = 0;
				if (offsetInWindow != null) {
					getLocationInWindow(offsetInWindow);
					startX = offsetInWindow[0];
					startY = offsetInWindow[1];
				}
				mNestedScrollingParent.onNestedScroll(this, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed);
				if (offsetInWindow != null) {
					getLocationInWindow(offsetInWindow);
					offsetInWindow[0] -= startX;
					offsetInWindow[1] -= startY;
				}
				return true;
			} else if (offsetInWindow != null) {
				offsetInWindow[0] = 0;
				offsetInWindow[1] = 0;
			}
		}
		return false;
	}

	public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
		if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
			return mNestedScrollingParent.onNestedFling(this, velocityX, velocityY, consumed);
		}
		return false;
	}

	public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
		if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
			return mNestedScrollingParent.onNestedPreFling(this, velocityX, velocityY);
		}
		return false;
	}

	// --- keys ---
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_SPACE) {
			if (!isClickable()) {
				return false;
			}
			if (isPressed()) {
				setPressed(false);
				performClick();
				return true;
			}
		}
		return false;
	}

	public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
		return false;
	}

	public boolean onKeyPreIme(int keyCode, KeyEvent event) {
		return false;
	}

	public boolean dispatchKeyEventPreIme(KeyEvent event) {
		return onKeyPreIme(event.getKeyCode(), event);
	}

	/** AOSP keeps one DispatcherState per window (attach info); here it lives on the root view. */
	public KeyEvent.DispatcherState getKeyDispatcherState() {
		View root = getRootView();
		if (root == null) {
			return null;
		}
		if (root.mKeyDispatchState == null) {
			root.mKeyDispatchState = new KeyEvent.DispatcherState();
		}
		return root.mKeyDispatchState;
	}

	// --- focus ---
	public void addFocusables(java.util.ArrayList<View> views, int direction) {
		addFocusables(views, direction, isInTouchMode() ? FOCUSABLES_TOUCH_MODE : FOCUSABLES_ALL);
	}

	public void addFocusables(java.util.ArrayList<View> views, int direction, int focusableMode) {
		if (views == null) {
			return;
		}
		if (!isFocusable() || getVisibility() != VISIBLE || !isEnabled()) {
			return;
		}
		if ((focusableMode & FOCUSABLES_TOUCH_MODE) == FOCUSABLES_TOUCH_MODE && !isFocusableInTouchMode()) {
			return;
		}
		views.add(this);
	}

	/** AOSP: whether this view can take focus while the display is in touch mode. */
	public boolean isFocusableInTouchMode() {
		return tlFocusableInTouchMode && isFocusable();
	}

	/** Called by the view system when the focus state of this view changes (AOSP: notifies the listener and drops the pressed state). */
	protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
		if (!gainFocus) {
			if (isPressed()) {
				setPressed(false);
			}
		}
		if (mOnFocusChangeListener != null) {
			mOnFocusChangeListener.onFocusChange(this, gainFocus);
		}
		refreshDrawableState();
	}

	// --- hover / pointer ---
	public boolean onHoverEvent(MotionEvent event) {
		// AOSP: hoverable views (clickable or long-clickable) consume hover events to track the hovered state
		if (isClickable() || isLongClickable()) {
			return true;
		}
		return false;
	}

	public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
		return mPointerIcon;
	}

	// --- saved state (AOSP) ---
	protected android.os.Parcelable onSaveInstanceState() {
		return BaseSavedState.EMPTY_STATE;
	}

	protected void onRestoreInstanceState(android.os.Parcelable state) {
		if (state != null && !(state instanceof AbsSavedState)) {
			throw new IllegalArgumentException("Wrong state class, expecting View State but received " + state.getClass().toString()
					+ " instead. This usually happens when two views of different type have the same id in the same hierarchy."
					+ " The id of this view is " + getId() + ". Make sure other views do not use the same id.");
		}
	}

	// --- layout direction / window state callbacks (AOSP defaults) ---
	public void onRtlPropertiesChanged(int layoutDirection) {
	}

	public void onWindowSystemUiVisibilityChanged(int visible) {
	}

	protected void onWindowVisibilityChanged(int visibility) {
	}

	// --- drawable state (AOSP) ---
	protected boolean verifyDrawable(android.graphics.drawable.Drawable who) {
		return who == getBackground() || who == getForeground();
	}

	protected void drawableStateChanged() {
		final int[] state = getDrawableState();
		final android.graphics.drawable.Drawable bg = getBackground();
		if (bg != null && bg.isStateful() && bg.setState(state)) {
			invalidateDrawable(bg);
		}
		final android.graphics.drawable.Drawable fg = getForeground();
		if (fg != null && fg.isStateful() && fg.setState(state)) {
			invalidateDrawable(fg);
		}
	}


	// --- invalidation / animation matrix ---
	public void postInvalidateOnAnimation(int left, int top, int right, int bottom) {
		postInvalidate(left, top, right, bottom);
	}

	public void setAnimationMatrix(android.graphics.Matrix matrix) {
		mAnimationMatrix = matrix == null ? null : new android.graphics.Matrix(matrix);
		invalidate();
	}

	public android.graphics.Matrix getAnimationMatrix() {
		return mAnimationMatrix;
	}

	// --- autofill ids / hints ---
	public void setAutofillHints(String... autofillHints) {
		if (autofillHints == null || autofillHints.length == 0) {
			mAutofillHints = null;
		} else {
			mAutofillHints = autofillHints;
		}
	}

	public String[] getAutofillHints() {
		return mAutofillHints;
	}

	/** AOSP: autofill ids are unique per process; the counter wraps at LAST_APP_AUTOFILL_ID like ContextImpl.getNextAutofillId. */
	private static final java.util.concurrent.atomic.AtomicInteger sNextAutofillId = new java.util.concurrent.atomic.AtomicInteger(NO_ID);
	private int mAutofillViewId = NO_ID;
	private boolean mAutofillIdExplicitlySet;

	public int getAutofillViewId() {
		if (mAutofillViewId == NO_ID) {
			int id = sNextAutofillId.incrementAndGet();
			if (id >= LAST_APP_AUTOFILL_ID) {
				sNextAutofillId.set(NO_ID);
				id = sNextAutofillId.incrementAndGet();
			}
			mAutofillViewId = id;
		}
		return mAutofillViewId;
	}

	public void setAutofillId(android.view.autofill.AutofillId id) {
		if (isAttachedToWindow()) {
			throw new IllegalStateException("Cannot set autofill id when view is attached");
		}
		if (id != null && !id.isNonVirtual()) {
			throw new IllegalStateException("Cannot set autofill id assigned to virtual views");
		}
		if (id == null && !mAutofillIdExplicitlySet) {
			return;
		}
		mAutofillId = id;
		if (id != null) {
			mAutofillViewId = id.getViewId();
			mAutofillIdExplicitlySet = true;
		} else {
			mAutofillViewId = NO_ID;
			mAutofillIdExplicitlySet = false;
		}
	}


	// --- API 35 content sensitivity / frame rate hints (recorded; no screen-capture protection or frame pacing here) ---
	public void setContentSensitivity(int mode) {
		mContentSensitivity = mode;
	}

	public int getContentSensitivity() {
		return mContentSensitivity;
	}

	public void setFrameContentVelocity(float pixelsPerSecond) {
		mFrameContentVelocity = Math.abs(pixelsPerSecond);
	}

	public float getFrameContentVelocity() {
		return mFrameContentVelocity;
	}

	public void setRequestedFrameRate(float frameRate) {
		mRequestedFrameRate = frameRate;
	}

	public float getRequestedFrameRate() {
		return mRequestedFrameRate;
	}

	// --- layers / outline / render effect ---
	public void setLayerPaint(android.graphics.Paint paint) {
		if (getLayerType() != LAYER_TYPE_NONE) {
			mLayerPaint = paint;
			invalidate();
		}
	}

	public android.graphics.Paint getLayerPaint() {
		return mLayerPaint;
	}

	public void setOutlineAmbientShadowColor(int color) {
		mOutlineAmbientShadowColor = color;
		invalidate();
	}

	public int getOutlineAmbientShadowColor() {
		return mOutlineAmbientShadowColor;
	}

	public void setOutlineSpotShadowColor(int color) {
		mOutlineSpotShadowColor = color;
		invalidate();
	}

	public int getOutlineSpotShadowColor() {
		return mOutlineSpotShadowColor;
	}

	/** The effect is kept on the view; the GSK renderer does not apply it yet (TODO-real.md). */
	public void setRenderEffect(android.graphics.RenderEffect renderEffect) {
		mRenderEffect = renderEffect;
		invalidate();
	}

	public android.graphics.RenderEffect getRenderEffect() {
		return mRenderEffect;
	}

	// --- geometry (AOSP setFrame through the individual edge setters) ---
	public final void setLeftTopRightBottom(int left, int top, int right, int bottom) {
		setLeft(left);
		setTop(top);
		setRight(right);
		setBottom(bottom);
	}

	public void setNextClusterForwardId(int nextClusterForwardId) {
		mNextClusterForwardId = nextClusterForwardId;
	}

	public int getNextClusterForwardId() {
		return mNextClusterForwardId;
	}

	public void setScreenReaderFocusable(boolean screenReaderFocusable) {
		mScreenReaderFocusable = screenReaderFocusable;
	}

	public boolean isScreenReaderFocusable() {
		return mScreenReaderFocusable;
	}

	/** Transition alpha multiplies the view alpha while a Transition runs (AOSP); here it is applied to the GTK opacity. */
	public void setTransitionAlpha(float alpha) {
		mTransitionAlpha = alpha;
		native_setVisibility(widget, visibility, getAlpha() * mTransitionAlpha, atl_enabled);
	}


	/** Changes the visibility without the usual change notifications (used by Transitions). */
	public void setTransitionVisibility(int visibility) {
		setVisibility(visibility);
	}

	public void setZ(float z) {
		setTranslationZ(z - getElevation());
	}


	// --- drag and drop: no drag session service in this runtime; AOSP returns false when a drag cannot start ---
	public final boolean startDragAndDrop(android.content.ClipData data, DragShadowBuilder shadowBuilder, Object myLocalState, int flags) {
		android.util.Log.w("View", "startDragAndDrop: drag and drop is not available in this runtime");
		return false;
	}

	public final boolean updateDragShadow(DragShadowBuilder shadowBuilder) {
		return false;
	}

	// --- receive content (AOSP: the listener, else the default handling which inserts nothing for a plain View) ---
	public void setOnReceiveContentListener(String[] mimeTypes, OnReceiveContentListener listener) {
		if (listener != null) {
			if (mimeTypes == null || mimeTypes.length == 0) {
				throw new IllegalArgumentException("When the listener is set, MIME types must also be set");
			}
		}
		if (mimeTypes != null) {
			for (String mimeType : mimeTypes) {
				if (mimeType.startsWith("*/")) {
					throw new IllegalArgumentException("A MIME type set here must not start with */: " + mimeType);
				}
			}
		}
		mReceiveContentMimeTypes = mimeTypes != null && mimeTypes.length > 0 ? mimeTypes.clone() : null;
		mReceiveContentListener = listener;
	}

	public String[] getReceiveContentMimeTypes() {
		return mReceiveContentMimeTypes;
	}

	public ContentInfo performReceiveContent(ContentInfo payload) {
		if (mReceiveContentListener != null) {
			return mReceiveContentListener.onReceiveContent(this, payload);
		}
		return onReceiveContent(payload);
	}

	public ContentInfo onReceiveContent(ContentInfo payload) {
		return payload;
	}

	// --- matrix transforms (AOSP hidden API used by transition and tooltip code) ---

	public void transformMatrixToLocal(android.graphics.Matrix matrix) {
		final ViewParent parent = getParent();
		if (parent instanceof View) {
			final View vp = (View) parent;
			vp.transformMatrixToLocal(matrix);
			matrix.postTranslate(vp.getScrollX(), vp.getScrollY());
		}
		matrix.postTranslate(-getLeft(), -getTop());
		android.graphics.Matrix inverse = new android.graphics.Matrix();
		if (getMatrix().invert(inverse)) {
			matrix.postConcat(inverse);
		}
	}

	/* ---- teslalinux: AOSP touch-to-click state (View.onTouchEvent) ---- */
	private boolean mClickable;
	private boolean mLongClickable;
	private boolean mPrePressed;
	private boolean mHasPerformedLongPress;
	private boolean mIgnoreNextUpEvent;
	private Runnable mPendingCheckForTap;
	private Runnable mPendingCheckForLongPress;
	private Runnable mUnsetPressedState;
	private float tlTapX, tlTapY;
	/** The event being handled was dispatched by Java code rather than by a GTK controller. */
	private boolean tlDispatchingSynthetic;
	private TouchDelegate mTouchDelegate;

	/* AOSP: context clickable (stylus button / mouse right button), set by setContextClickable or a context click listener */
	private boolean tlContextClickable;

	public boolean isContextClickable() {
		return tlContextClickable;
	}

	/** AOSP: pressed with a hotspot for the background drawable. */
	private void setPressed(boolean pressed, float x, float y) {
		if (pressed && background != null)
			background.setHotspot(x, y);
		setPressed(pressed);
	}

	/** AOSP: the click, through the listener. */
	private boolean performClickInternal() {
		return performClick();
	}

	/** AOSP: whether an ancestor delays the pressed state (scrolling containers). */
	public boolean isInScrollingContainer() {
		ViewParent p = getParent();
		while (p != null && p instanceof ViewGroup) {
			if (((ViewGroup) p).shouldDelayChildPressedState())
				return true;
			p = p.getParent();
		}
		return false;
	}

	/** AOSP: whether the point (local coordinates) is inside the view, grown by slop. */
	public boolean pointInView(float localX, float localY, float slop) {
		return localX >= -slop && localY >= -slop && localX < ((getWidth()) + slop) && localY < ((getHeight()) + slop);
	}

	private void checkForLongClick(long delay, final float x, final float y) {
		if (isLongClickable()) {
			mHasPerformedLongPress = false;
			if (mPendingCheckForLongPress == null)
				mPendingCheckForLongPress = new Runnable() {
					@Override
					public void run() {
						if (pressed && getParent() != null) {
							if (performLongClick(x, y))
								mHasPerformedLongPress = true;
						}
					}
				};
			postDelayed(mPendingCheckForLongPress, delay);
		}
	}

	private void removeLongPressCallback() {
		if (mPendingCheckForLongPress != null)
			removeCallbacks(mPendingCheckForLongPress);
	}

	private void removeTapCallback() {
		if (mPendingCheckForTap != null) {
			mPrePressed = false;
			removeCallbacks(mPendingCheckForTap);
		}
	}

	/** The main-thread handler every View shares for post/postDelayed/removeCallbacks (ViewRootImpl's in AOSP). */
	private static Handler sTlMainHandler;
	private static Handler tlMainHandler() {
		if (sTlMainHandler == null)
			sTlMainHandler = new Handler(Looper.getMainLooper());
		return sTlMainHandler;
	}
	/** Set by the native dispatcher before calling a custom dispatchTouchEvent: the event is synthetic (not from GTK). */
	static boolean tlNativeDispatchSynthetic;

	/** AOSP: forces the view's layer to be created and drawn. There is no cached layer here (GTK renders
	 * the widget tree directly), so only the precondition remains. */
	public void buildLayer() {
		if (getLayerType() == LAYER_TYPE_NONE)
			throw new IllegalStateException("Cannot build a layer if the layer type is set to LAYER_TYPE_NONE");
	}
	/* AOSP: accessibility live regions */
	public static final int ACCESSIBILITY_LIVE_REGION_NONE = 0x00000000;
	public static final int ACCESSIBILITY_LIVE_REGION_POLITE = 0x00000001;
	public static final int ACCESSIBILITY_LIVE_REGION_ASSERTIVE = 0x00000002;
	/* AOSP (API 34): whether accessibility services may see this view's data */
	public static final int ACCESSIBILITY_DATA_SENSITIVE_AUTO = 0x00000000;
	public static final int ACCESSIBILITY_DATA_SENSITIVE_YES = 0x00000001;
	public static final int ACCESSIBILITY_DATA_SENSITIVE_NO = 0x00000002;
	private int mAccessibilityDataSensitive = ACCESSIBILITY_DATA_SENSITIVE_AUTO;
	/* AOSP: touches are discarded while another window obscures this one; the compositor never
	 * reports MotionEvent.FLAG_WINDOW_IS_OBSCURED to ATL, so the flag only records the request */
	private boolean mFilterTouchesWhenObscured;

	public void setFilterTouchesWhenObscured(boolean enabled) {
		mFilterTouchesWhenObscured = enabled;
	}

	public boolean getFilterTouchesWhenObscured() {
		return mFilterTouchesWhenObscured;
	}
	private static int sNextAccessibilityViewId = 1;
	private int mAccessibilityViewId = NO_ID;

	/** AOSP (hidden): the id accessibility uses for this view, assigned on first use. */
	public int getAccessibilityViewId() {
		if (mAccessibilityViewId == NO_ID)
			mAccessibilityViewId = sNextAccessibilityViewId++;
		return mAccessibilityViewId;
	}

	/**
	 * AOSP (hidden): the accessibility id of the window; the accessibility manager service assigns
	 * one when a service is running, otherwise it stays undefined, which is the case here.
	 */
	public int getAccessibilityWindowId() {
		return android.view.accessibility.AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
	}

	public void setAccessibilityDataSensitive(int accessibilityDataSensitive) {
		mAccessibilityDataSensitive = accessibilityDataSensitive;
	}

	public int getAccessibilityDataSensitive() {
		return mAccessibilityDataSensitive;
	}

	/** AOSP: AUTO resolves to whether touches are filtered when the view is obscured. */
	public boolean isAccessibilityDataSensitive() {
		if (mAccessibilityDataSensitive == ACCESSIBILITY_DATA_SENSITIVE_AUTO)
			return getFilterTouchesWhenObscured();
		return mAccessibilityDataSensitive == ACCESSIBILITY_DATA_SENSITIVE_YES;
	}
	/** AOSP: a resolved layout direction, LAYOUT_DIRECTION_LTR or LAYOUT_DIRECTION_RTL. */
	@android.annotation.IntDef({LAYOUT_DIRECTION_LTR, LAYOUT_DIRECTION_RTL})
	@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
	public @interface ResolvedLayoutDir {}
	/* teslalinux debugging: the specs of the last measure pass (android.atl.ViewDump) */
	public int tlLastWidthMeasureSpec() {
		return oldWidthMeasureSpec;
	}

	public int tlLastHeightMeasureSpec() {
		return oldHeightMeasureSpec;
	}
	/** AOSP: the drawable state of a checked view (CompoundButton, Checkable items). */
	protected static final int[] CHECKED_STATE_SET = new int[] {com.android.internal.R.attr.state_checked};
	/** AOSP: the autofill type of a view (TOGGLE for compound buttons, TEXT for editors); plain views have none. */
	@android.annotation.IntDef(prefix = {"AUTOFILL_TYPE_"}, value = {AUTOFILL_TYPE_NONE, AUTOFILL_TYPE_TEXT, AUTOFILL_TYPE_TOGGLE, AUTOFILL_TYPE_LIST, AUTOFILL_TYPE_DATE})
	@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
	public @interface AutofillType {}

	public @AutofillType int getAutofillType() {
		return AUTOFILL_TYPE_NONE;
	}

	public android.view.autofill.AutofillValue getAutofillValue() {
		return null;
	}

	/** AOSP: a view that is not autofillable ignores the value. */
	public void autofill(android.view.autofill.AutofillValue value) {
	}

	/** AOSP: resolved layout direction is RTL. */
	public boolean isLayoutRtl() {
		return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
	}

	/** AOSP: drawables get the resolved layout direction here; the base view has none to resolve. */
	public void onResolveDrawables(int layoutDirection) {
	}

	/* AOSP accessibility population: the public methods delegate to the internal ones so a delegate can wrap them */
	public void onInitializeAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
		onInitializeAccessibilityEventInternal(event);
	}

	public void onInitializeAccessibilityEventInternal(android.view.accessibility.AccessibilityEvent event) {
		event.setSource(this);
		event.setClassName(getAccessibilityClassName());
		event.setPackageName(getContext().getPackageName());
		event.setEnabled(isEnabled());
		event.setContentDescription(getContentDescription());
		event.setScrollX(getScrollX());
		event.setScrollY(getScrollY());
	}

	public void onPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
		onPopulateAccessibilityEventInternal(event);
	}

	public void onPopulateAccessibilityEventInternal(android.view.accessibility.AccessibilityEvent event) {
	}

	public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
		onInitializeAccessibilityNodeInfoInternal(info);
	}

	public void onInitializeAccessibilityNodeInfoInternal(android.view.accessibility.AccessibilityNodeInfo info) {
		android.graphics.Rect bounds = new android.graphics.Rect(0, 0, getWidth(), getHeight());
		info.setBoundsInParent(bounds);
		int[] location = new int[2];
		getLocationOnScreen(location);
		bounds.offset(location[0], location[1]);
		info.setBoundsInScreen(bounds);
		info.setVisibleToUser(isShown());
		info.setPackageName(getContext().getPackageName());
		info.setClassName(getAccessibilityClassName());
		info.setContentDescription(getContentDescription());
		info.setEnabled(isEnabled());
		info.setClickable(isClickable());
		info.setFocusable(isFocusable());
		info.setFocused(isFocused());
		info.setSelected(isSelected());
		info.setLongClickable(isLongClickable());
	}
	/* AOSP: accessibility importance modes */
	public static final int IMPORTANT_FOR_ACCESSIBILITY_AUTO = 0x00000000;
	public static final int IMPORTANT_FOR_ACCESSIBILITY_YES = 0x00000001;
	public static final int IMPORTANT_FOR_ACCESSIBILITY_NO = 0x00000002;
	public static final int IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 0x00000004;

	/**
	 * AOSP: whether the layout direction can be resolved (INHERIT with no parent cannot). ATL resolves
	 * every view's direction (getLayoutDirection/isLayoutDirectionResolved), so it always can.
	 */
	public boolean canResolveLayoutDirection() {
		return true;
	}

	/* AOSP aggregated visibility: a view learns whether it is actually visible (own visibility, ancestors, window) */
	private boolean tlAggregatedVisible;

	/**
	 * AOSP: called when the user-visibility of this View has changed, whether through its own
	 * visibility, an ancestor's, or the window's. The background drawable follows it; ProgressBar
	 * starts and stops its indeterminate animation here.
	 */
	public void onVisibilityAggregated(boolean isVisible) {
		tlAggregatedVisible = isVisible;
		final android.graphics.drawable.Drawable dr = getBackground();
		if (dr != null && isVisible != dr.isVisible()) {
			dr.setVisible(isVisible, false);
		}
		final android.graphics.drawable.Drawable fg = getForeground();
		if (fg != null && isVisible != fg.isVisible()) {
			fg.setVisible(isVisible, false);
		}
	}

	/** AOSP: delivers the aggregated visibility to this view (ViewGroup continues to its children). */
	public boolean dispatchVisibilityAggregated(boolean isVisible) {
		final boolean thisVisible = getVisibility() == VISIBLE;
		if (thisVisible || !isVisible) {
			onVisibilityAggregated(isVisible);
		}
		return thisVisible && isVisible;
	}

	/** teslalinux: the last aggregated visibility this view was told about. */
	boolean tlIsAggregatedVisible() {
		return tlAggregatedVisible;
	}

	/**
	 * AOSP: performs an accessibility action on this view; the standard actions map to the view's
	 * own click/focus/selection behaviour. Accessibility focus needs an accessibility service, which
	 * this platform has none of, so those actions report failure the way AOSP does without one.
	 */
	public boolean performAccessibilityActionInternal(int action, android.os.Bundle arguments) {
		switch (action) {
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK: {
				if (isClickable()) {
					performClickInternal();
					return true;
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK: {
				if (isLongClickable()) {
					performLongClick();
					return true;
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS: {
				if (!hasFocus()) {
					// Get out of touch mode since accessibility
					// wants to move focus around.
					return requestFocus();
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_FOCUS: {
				if (hasFocus()) {
					clearFocus();
					return !isFocused();
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT: {
				if (!isSelected()) {
					setSelected(true);
					return isSelected();
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_SELECTION: {
				if (isSelected()) {
					setSelected(false);
					return !isSelected();
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
				return false;
			case com.android.internal.R.id.accessibilityActionShowOnScreen: {
				if (isAttachedToWindow()) {
					final android.graphics.Rect r = new android.graphics.Rect();
					getDrawingRect(r);
					return requestRectangleOnScreen(r, true);
				}
			} break;
			case com.android.internal.R.id.accessibilityActionContextClick: {
				if (isContextClickable()) {
					performContextClick();
					return true;
				}
			} break;
		}
		return false;
	}
	/** AOSP: a long click not tied to a touch position (the context menu anchors to the view). */
	public boolean performLongClick() {
		return performLongClick(Float.NaN, Float.NaN);
	}
	/* AOSP: a context click (stylus button, mouse right button) goes to the context click listener */
	private OnContextClickListener tlOnContextClickListener;

	public void setOnContextClickListener(OnContextClickListener l) {
		if (!isContextClickable()) {
			setContextClickable(true);
		}
		tlOnContextClickListener = l;
	}

	public boolean performContextClick() {
		boolean handled = false;
		if (tlOnContextClickListener != null) {
			handled = tlOnContextClickListener.onContextClick(this);
		}
		return handled;
	}
	/** AOSP: interface definition for a callback to be invoked when a view is context clicked. */
	public interface OnContextClickListener {
		boolean onContextClick(View v);
	}
	public void setContextClickable(boolean contextClickable) {
		tlContextClickable = contextClickable;
	}
	/**
	 * AOSP: invoked when the alpha changes; a subclass that applies the alpha itself returns true
	 * (ImageButton/ImageView apply it to their drawable), otherwise the view is drawn through an alpha layer.
	 */
	protected boolean onSetAlpha(int alpha) {
		return false;
	}
	/* AOSP window focus: the root view of a window tree records it; every view can ask hasWindowFocus() */
	private boolean tlWindowFocus;

	/** AOSP: called by the window when it gains or loses focus; propagates to onWindowFocusChanged (and a ViewGroup's children). */
	public void dispatchWindowFocusChanged(boolean hasFocus) {
		tlWindowFocus = hasFocus;
		onWindowFocusChanged(hasFocus);
	}

	/* ---- teslalinux: view focus state and touch mode (tl_touch_mode) ----
	 * Focus: the focused view of a window is the view whose widget holds the window's GTK focus
	 * (gtk_root_get_focus; dialogs are layers of the main GtkWindow and share it). hasFocus,
	 * findFocus and clearFocus read and change that state (native-focus-state.sh) with AOSP's
	 * meaning: a view "has focus" when it or one of its descendants is the focused view.
	 * Touch mode: AOSP keeps it per display (WindowManagerService, Android 13+) and every window of
	 * the display reads it from AttachInfo.mInTouchMode. This platform has one display, so the state
	 * is process-wide. It starts at config_defaultInTouchMode. The main window's GTK controllers
	 * call in before an event reaches any view (native-touch-mode.sh): a pointer DOWN or SCROLL
	 * enters touch mode (ViewRootImpl.EarlyPostImeInputStage.processPointerEvent), a navigation or
	 * typing key leaves it (ViewRootImpl.checkForLeavingTouchModeAndConsume). A change reaches the
	 * OnTouchModeChangeListeners of every window and then adjusts that window's focus
	 * (ViewRootImpl.enterTouchMode / leaveTouchMode). Compose derives its InputModeManager from it,
	 * which decides whether clickables can take focus (Focusability.SystemDefined). */
	private static final boolean TL_DEBUG_TOUCH_MODE = System.getenv("ATL_DEBUG_FOCUS") != null;
	/** 1 in touch mode, 0 not, -1 before the display default was read */
	private static int tlTouchMode = -1;

	private static boolean tlTouchModeState() {
		if (tlTouchMode < 0)
			tlTouchMode = android.content.res.Resources.getSystem().getBoolean(com.android.internal.R.bool.config_defaultInTouchMode) ? 1 : 0;
		return tlTouchMode == 1;
	}

	/** AOSP ViewRootImpl.ensureTouchMode for every window of the display; true when focus changed as a result. */
	static boolean tlEnsureTouchMode(boolean inTouchMode) {
		if (tlTouchModeState() == inTouchMode)
			return false;
		tlTouchMode = inTouchMode ? 1 : 0;
		if (TL_DEBUG_TOUCH_MODE)
			android.util.Log.i("View", "[focus] touch mode -> " + inTouchMode);
		boolean focusChanged = false;
		for (Window window : Window.tlLiveWindows()) {
			View root = window.getDecorView();
			if (root == null || !root.isAttachedToWindow())
				continue;
			/* AOSP ensureTouchModeLocally: the window's listeners first, then its focus */
			if (window.view_tree_observer != null)
				window.view_tree_observer.dispatchOnTouchModeChanged(inTouchMode);
			focusChanged |= inTouchMode ? tlEnterTouchMode(root) : tlLeaveTouchMode(root);
		}
		return focusChanged;
	}

	/** AOSP ViewRootImpl.enterTouchMode: a focused view that cannot keep focus in touch mode gives it up. */
	private static boolean tlEnterTouchMode(View root) {
		if (root.hasFocus()) {
			final View focused = root.findFocus();
			if (focused != null && !focused.isFocusableInTouchMode()) {
				final ViewGroup ancestorToTakeFocus = tlFindAncestorToTakeFocusInTouchMode(focused);
				if (ancestorToTakeFocus != null)
					return ancestorToTakeFocus.requestFocus();
				focused.tlClearFocusInternal(true, false);
				return true;
			}
		}
		return false;
	}

	/** AOSP ViewRootImpl.findAncestorToTakeFocusInTouchMode (the walk ends at the window's root view). */
	private static ViewGroup tlFindAncestorToTakeFocusInTouchMode(View focused) {
		ViewParent parent = focused.getParent();
		while (parent instanceof ViewGroup) {
			final ViewGroup vgParent = (ViewGroup) parent;
			if (vgParent.getDescendantFocusability() == ViewGroup.FOCUS_AFTER_DESCENDANTS && vgParent.isFocusableInTouchMode())
				return vgParent;
			parent = vgParent.getParent();
		}
		return null;
	}

	/** AOSP ViewRootImpl.leaveTouchMode: without touch, some view should hold focus for the keys. */
	private static boolean tlLeaveTouchMode(View root) {
		if (root.hasFocus()) {
			View focusedView = root.findFocus();
			if (!(focusedView instanceof ViewGroup))
				return false; /* some view has focus, let it keep it */
			else if (((ViewGroup) focusedView).getDescendantFocusability() != ViewGroup.FOCUS_AFTER_DESCENDANTS)
				return false; /* a group has focus and does not prefer its children over itself */
		}
		return root.restoreDefaultFocus();
	}

	private static boolean tlIsNavigationKey(KeyEvent keyEvent) {
		switch (keyEvent.getKeyCode()) {
			case KeyEvent.KEYCODE_DPAD_LEFT:
			case KeyEvent.KEYCODE_DPAD_RIGHT:
			case KeyEvent.KEYCODE_DPAD_UP:
			case KeyEvent.KEYCODE_DPAD_DOWN:
			case KeyEvent.KEYCODE_DPAD_CENTER:
			case KeyEvent.KEYCODE_PAGE_UP:
			case KeyEvent.KEYCODE_PAGE_DOWN:
			case KeyEvent.KEYCODE_MOVE_HOME:
			case KeyEvent.KEYCODE_MOVE_END:
			case KeyEvent.KEYCODE_TAB:
			case KeyEvent.KEYCODE_SPACE:
			case KeyEvent.KEYCODE_ENTER:
				return true;
		}
		return false;
	}

	/**
	 * AOSP ViewRootImpl.checkForLeavingTouchModeAndConsume, called by the main window's key controller
	 * before any view sees the key; true when the key only served to leave touch mode (consumed).
	 */
	static boolean tlCheckForLeavingTouchModeAndConsume(KeyEvent event) {
		if (!tlTouchModeState())
			return false;
		final int action = event.getAction();
		if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_MULTIPLE)
			return false;
		if ((event.getFlags() & KeyEvent.FLAG_KEEP_TOUCH_MODE) != 0)
			return false;
		if (event.hasNoModifiers() && tlIsNavigationKey(event))
			return tlEnsureTouchMode(false);
		if (event.getUnicodeChar() > 0) {
			tlEnsureTouchMode(false); /* typing keys always reach the view */
			return false;
		}
		return false;
	}

	/**
	 * A pointer DOWN or SCROLL from any device at (x, y) in the main window, before it reaches a view
	 * (AOSP processPointerEvent). App windows that watch outside touches get theirs (ACTION_OUTSIDE).
	 */
	static void tlPointerDown(float x, float y) {
		tlEnsureTouchMode(true);
		WindowManagerImpl.tlDispatchOutsideTouch(x, y);
	}

	/* ---- teslalinux: the AOSP focus model (tl_focus_model) ----
	 * Focus is view state, per window: a focused view has tlFocused (AOSP PFLAG_FOCUSED) and every
	 * group on its path to the window's root has it as tlFocusedChild (mFocused). GTK has one focus
	 * per toplevel and every window here (activity, dialogs, app windows) is a layer of one GtkWindow,
	 * so GTK's focus is a projection: it shows the focused view of the focused window (the topmost
	 * dialog or focusable app window, else the activity's). Focus that GTK moves on its own (a tap
	 * into an EditText's GtkText) comes back through tlGtkFocusChanged. */
	boolean tlFocused;

	/** AOSP canTakeFocus */
	private boolean tlCanTakeFocus() {
		return visibility == VISIBLE && isFocusable() && isEnabled();
	}

	/** AOSP hasAncestorThatBlocksDescendantFocus */
	private boolean tlHasAncestorThatBlocksDescendantFocus() {
		ViewParent ancestor = parent;
		while (ancestor instanceof ViewGroup) {
			final ViewGroup vgAncestor = (ViewGroup) ancestor;
			if (vgAncestor.getDescendantFocusability() == ViewGroup.FOCUS_BLOCK_DESCENDANTS)
				return true;
			ancestor = vgAncestor.getParent();
		}
		return false;
	}

	/** AOSP requestFocusNoSearch */
	boolean tlRequestFocusNoSearch(int direction, Rect previouslyFocusedRect) {
		if (!tlCanTakeFocus())
			return false;
		if (isInTouchMode() && !isFocusableInTouchMode())
			return false;
		if (tlHasAncestorThatBlocksDescendantFocus())
			return false;
		tlHandleFocusGainInternal(direction, previouslyFocusedRect);
		return true;
	}

	/** AOSP handleFocusGainInternal: this view becomes its window's focused view. */
	void tlHandleFocusGainInternal(int direction, Rect previouslyFocusedRect) {
		if (tlFocused)
			return;
		tlFocused = true;
		final View oldFocus = getRootView().findFocus();
		if (parent instanceof ViewGroup)
			((ViewGroup) parent).requestChildFocus(this, this);
		if (isAttachedToWindow())
			getViewTreeObserver().dispatchOnGlobalFocusChange(oldFocus, this);
		onFocusChanged(true, direction, previouslyFocusedRect);
		refreshDrawableState();
		tlProjectFocus(direction);
	}

	/** AOSP unFocus: loses focus without telling the parents (they move it themselves). */
	void tlUnFocus(View focused) {
		tlClearFocusInternal(focused, false, false);
	}

	/**
	 * AOSP clearFocusInternal: the view gives up focus, is told so (onFocusChanged), and with
	 * refocus the window's root view picks the next focused view; otherwise the window's global
	 * focus listeners learn that no view has focus.
	 */
	void tlClearFocusInternal(View focused, boolean propagate, boolean refocus) {
		if (!tlFocused)
			return;
		tlFocused = false;
		if (propagate && parent instanceof ViewGroup)
			((ViewGroup) parent).clearChildFocus(this);
		onFocusChanged(false, 0, null);
		refreshDrawableState();
		if (widget != 0)
			nativeClearFocus(widget); /* GTK's focus leaves the widget when it had it */
		if (propagate && (!refocus || !tlRootViewRequestFocus()) && isAttachedToWindow())
			getViewTreeObserver().dispatchOnGlobalFocusChange(this, null);
	}

	void tlClearFocusInternal(boolean propagate, boolean refocus) {
		tlClearFocusInternal(null, propagate, refocus);
	}

	/* ---- the focused window and GTK's focus ---- */
	/** windows above the activity that take focus (showing dialogs, focusable app windows), topmost last */
	private static final java.util.ArrayList<View> tlFocusWindows = new java.util.ArrayList<>();

	private static View tlFocusedWindowRoot() {
		return tlFocusWindows.isEmpty() ? null : tlFocusWindows.get(tlFocusWindows.size() - 1);
	}

	/** whether this view's window is the focused one (an activity window is, when nothing is above it) */
	boolean tlInFocusedWindow() {
		View top = tlFocusedWindowRoot();
		return top == null ? !tlFocusWindows.contains(getRootView()) : getRootView() == top;
	}

	/** GTK's focus shows this view when its window is the focused window */
	private void tlProjectFocus(int direction) {
		if (tlFocusWindows.isEmpty())
			tlActivityRoot = new java.lang.ref.WeakReference<>(getRootView());
		if (widget != 0 && !tlFocusFromGtk && tlInFocusedWindow())
			nativeRequestFocus(widget, direction);
	}
	/** the root of the activity window, whose focused view gets GTK's focus back when the windows above go */
	private static java.lang.ref.WeakReference<View> tlActivityRoot;
	private static boolean tlFocusFromGtk;

	/**
	 * A window that takes focus was shown (shown=true) or went away: the focused window changes, and
	 * GTK's focus shows its focused view, or none (keys then reach the window itself, as on AOSP when
	 * the focused window has no focused view).
	 */
	public static void tlFocusWindowChanged(View root, boolean shown) {
		if (root == null)
			return;
		tlFocusWindows.remove(root);
		if (shown)
			tlFocusWindows.add(root);
		View top = tlFocusedWindowRoot();
		if (top == null)
			top = tlActivityRoot != null ? tlActivityRoot.get() : null;
		View focused = top != null ? top.findFocus() : null;
		if (focused != null && focused.widget != 0)
			focused.nativeRequestFocus(focused.widget, FOCUS_DOWN);
		else
			nativeDropWindowFocus();
	}
	/**
	 * GTK moved its focus to a widget of this view (a tap into a GtkText, GTK's own focus
	 * navigation): the view becomes its window's focused view, as if it had requested it.
	 */
	static void tlGtkFocusChanged(View view) {
		if (view == null || view.tlFocused)
			return;
		tlFocusFromGtk = true;
		try {
			view.tlHandleFocusGainInternal(FOCUS_DOWN, null);
		} finally {
			tlFocusFromGtk = false;
		}
	}


	/** AOSP rootViewRequestFocus */
	boolean tlRootViewRequestFocus() {
		final View root = getRootView();
		return root != null && root.requestFocus();
	}

	/** AOSP sAlwaysAssignFocus: apps targeting API < 28 expect a view to be focused again after clearFocus. */
	boolean tlAlwaysAssignFocus() {
		android.content.pm.ApplicationInfo ai = getContext() != null ? getContext().getApplicationInfo() : null;
		return ai != null && ai.targetSdkVersion < android.os.Build.VERSION_CODES.P;
	}

	/** drops GTK's focus (and a deferred focus request) when this view's widget has it; true if it had */
	private static native boolean nativeClearFocus(long widget);
	/** GTK's focus goes to no widget: keys reach the window-level dispatch */
	private static native void nativeDropWindowFocus();

	/* ---- teslalinux: layout change listeners, root namespace, window frames (tl_layout_change) ---- */
	private java.util.ArrayList<OnLayoutChangeListener> tlLayoutChangeListeners;
	private int tlLaidOutLeft, tlLaidOutTop, tlLaidOutRight, tlLaidOutBottom;

	/** AOSP View.layout: after onLayout, the layout change listeners get the new and the previous bounds. */
	private void tlDispatchLayoutChange() {
		final int oldL = tlLaidOutLeft, oldT = tlLaidOutTop, oldR = tlLaidOutRight, oldB = tlLaidOutBottom;
		tlLaidOutLeft = left;
		tlLaidOutTop = top;
		tlLaidOutRight = right;
		tlLaidOutBottom = bottom;
		if (tlLayoutChangeListeners == null || tlLayoutChangeListeners.isEmpty())
			return;
		java.util.ArrayList<OnLayoutChangeListener> listeners = new java.util.ArrayList<OnLayoutChangeListener>(tlLayoutChangeListeners);
		for (OnLayoutChangeListener l : listeners)
			l.onLayoutChange(this, left, top, right, bottom, oldL, oldT, oldR, oldB);
	}

	private int tlRawLayoutDirection = LAYOUT_DIRECTION_INHERIT;

	/** AOSP (hidden): the layout direction as set, before resolution. */
	public int getRawLayoutDirection() {
		return tlRawLayoutDirection;
	}

	private boolean tlIsRootNamespace;

	/** AOSP (hidden): marks the root of a namespace for findViewById and focus searches (window roots). */
	public void setIsRootNamespace(boolean isRoot) {
		tlIsRootNamespace = isRoot;
	}

	public boolean isRootNamespace() {
		return tlIsRootNamespace;
	}

	/** AOSP (hidden): the view's location on screen as a new array. */
	public int[] getLocationOnScreen() {
		int[] location = new int[2];
		getLocationOnScreen(location);
		return location;
	}

	/**
	 * AOSP (hidden): the frame of the display area the window is laid out in. There are no system
	 * bars here, so it is the window's visible display frame.
	 */
	public void getWindowDisplayFrame(android.graphics.Rect outRect) {
		getWindowVisibleDisplayFrame(outRect);
	}

	/**
	 * AOSP (hidden): converts an event in this view's coordinates to screen coordinates (through the
	 * parents, then the window's position on screen, which ViewRootImpl adds on Android); false when
	 * the view is not attached. Used to forward touches between windows (ForwardingListener).
	 */
	public boolean toGlobalMotionEvent(MotionEvent ev) {
		if (!isAttachedToWindow())
			return false;
		final android.graphics.Matrix m = new android.graphics.Matrix();
		transformMatrixToGlobal(m);
		final int[] window = getRootView().getLocationOnScreen();
		m.postTranslate(window[0], window[1]);
		ev.transform(m);
		return true;
	}

	/** AOSP (hidden): converts an event in screen coordinates to this view's coordinates. */
	public boolean toLocalMotionEvent(MotionEvent ev) {
		if (!isAttachedToWindow())
			return false;
		final int[] window = getRootView().getLocationOnScreen();
		final android.graphics.Matrix m = new android.graphics.Matrix();
		m.setTranslate(-window[0], -window[1]);
		transformMatrixToLocal(m);
		ev.transform(m);
		return true;
	}

	/* AOSP View.TintInfo of the background (frameworks/base View.java): from android:backgroundTint /
	 * android:backgroundTintMode or setBackgroundTintList()/setBackgroundTintMode(), applied to every
	 * background drawable, mutated first so a drawable shared through its constant state keeps its own
	 * colours (Material and Waze buttons colour one ripple/shape resource this way). */
	private android.content.res.ColorStateList tlBgTintList;
	private android.graphics.PorterDuff.Mode tlBgTintMode;
	private boolean tlHasBgTintList;
	private boolean tlHasBgTintMode;

	/** AOSP View.applyBackgroundTint() */
	private void applyBackgroundTint() {
		final android.graphics.drawable.Drawable bg = background;
		if (bg == null || !(tlHasBgTintList || tlHasBgTintMode))
			return;
		final android.graphics.drawable.Drawable m = bg.mutate();
		if (tlHasBgTintList)
			m.setTintList(tlBgTintList);
		if (tlHasBgTintMode)
			m.setTintMode(tlBgTintMode);
		// the tint list resolves against the drawable state; a stateful background must know it
		if (m.isStateful())
			m.setState(getDrawableState());
		if (m != bg) {
			background = m;
			m.setCallback(this);
			native_setBackgroundDrawable(widget, m.paintable);
		}
		invalidate();
	}

	/** AOSP View.setBackgroundTintBlendMode() (API 29): the same tint, as a BlendMode */
	public void setBackgroundTintBlendMode(android.graphics.BlendMode blendMode) {
		setBackgroundTintMode(android.graphics.BlendMode.blendModeToPorterDuffMode(blendMode));
	}

	/** AOSP View.getBackgroundTintBlendMode() */
	public android.graphics.BlendMode getBackgroundTintBlendMode() {
		if (tlBgTintMode == null)
			return null;
		for (android.graphics.BlendMode b : android.graphics.BlendMode.values())
			if (android.graphics.BlendMode.blendModeToPorterDuffMode(b) == tlBgTintMode)
				return b;
		return null;
	}

	/* ---- teslalinux: AOSP drawable state (View.java) ----
	 * The view's state set (window_focused, selected, focused, enabled, pressed, activated, plus what
	 * subclasses add in onCreateDrawableState: checked, ...) is built from its real flags, cached, and
	 * rebuilt by refreshDrawableState(), which tells drawableStateChanged() so state-list drawables,
	 * colour state lists (TextView colours, tints) and the parent (addStatesFromChildren) follow it. */
	private int[] tlDrawableState;
	private boolean tlDrawableStateDirty = true;
	private boolean tlSelected;
	private boolean tlActivated;

	/** AOSP View.dispatchSetSelected: a leaf view has no children (ViewGroup passes the selection on) */
	protected void dispatchSetSelected(boolean selected) {
	}

	/** AOSP View.dispatchSetPressed: a leaf view has no children to pass the press to (ViewGroup overrides) */
	protected void dispatchSetPressed(boolean pressed) {
	}

	/** AOSP View.dispatchSetActivated: a leaf view has no children (ViewGroup passes it on) */
	protected void dispatchSetActivated(boolean activated) {
	}

	/** AOSP: the states this view would show, before subclasses add theirs */
	private int[] tlBaseDrawableState(int extraSpace) {
		int n = 0;
		final int[] s = new int[6 + extraSpace];
		if (hasWindowFocus())
			s[n++] = R.attr.state_window_focused;
		if (tlSelected)
			s[n++] = R.attr.state_selected;
		if (isFocused())
			s[n++] = R.attr.state_focused;
		if (isEnabled())
			s[n++] = R.attr.state_enabled;
		if (pressed)
			s[n++] = R.attr.state_pressed;
		if (tlActivated)
			s[n++] = R.attr.state_activated;
		final int[] out = new int[n + extraSpace];
		System.arraycopy(s, 0, out, 0, n);
		return out;
	}

	/* ---- teslalinux (layout): AOSP window insets dispatch, fitsSystemWindows, requestApplyInsets ----
	 * Copyright (C) 2006 The Android Open Source Project, Apache License 2.0 (logic ported from
	 * frameworks/base core/java/android/view/View.java and ViewRootImpl.java, android14-release).
	 * The window's insets come from Window.tlGetWindowInsets: the car screen has no status bar,
	 * navigation bar, caption bar or display cutout, so every inset type is zero and not visible,
	 * which is what AOSP reports for a window without inset sources. The dispatch itself is AOSP's:
	 * the window root gets the insets when it is attached and whenever a view requests them again,
	 * fitsSystemWindows views take the system window insets as padding, listeners and
	 * onApplyWindowInsets overrides see every dispatch. */

	/** AOSP FITS_SYSTEM_WINDOWS (android:fitsSystemWindows) */
	private boolean tlFitsSystemWindows;
	/** AOSP OPTIONAL_FITS_SYSTEM_WINDOWS */
	private boolean tlOptionalFitsSystemWindows;
	/** AOSP PFLAG4_FRAMEWORK_OPTIONAL_FITS_SYSTEM_WINDOWS */
	private boolean tlFrameworkOptionalFitsSystemWindows;
	/** AOSP PFLAG3_APPLYING_INSETS / PFLAG3_FITTING_SYSTEM_WINDOWS */
	private boolean tlApplyingInsets;
	private boolean tlFittingSystemWindows;
	private View.OnApplyWindowInsetsListener tlOnApplyWindowInsetsListener;
	/** a dispatch from this window root is posted (ViewRootImpl.mApplyInsetsRequested) */
	private boolean tlApplyInsetsRequested;

	/**
	 * AOSP: whether the default fitSystemWindows/onApplyWindowInsets applies the system window
	 * insets to this view as padding.
	 */
	public void setFitsSystemWindows(boolean fitSystemWindows) {
		tlFitsSystemWindows = fitSystemWindows;
	}

	public boolean getFitsSystemWindows() {
		return tlFitsSystemWindows;
	}

	/** @hide AOSP */
	public boolean fitsSystemWindows() {
		return getFitsSystemWindows();
	}

	/** AOSP: the listener is called instead of onApplyWindowInsets */
	public void setOnApplyWindowInsetsListener(View.OnApplyWindowInsetsListener listener) {
		tlOnApplyWindowInsetsListener = listener;
	}

	/**
	 * AOSP: asks for a new dispatch of the window insets. The request travels to the window root
	 * (ViewRootImpl), which dispatches on its next traversal; here the root posts the dispatch, so
	 * the requests of one pass are served by a single dispatch.
	 */
	@Deprecated
	public void requestFitSystemWindows() {
		getRootView().tlScheduleApplyInsets();
	}

	public void requestApplyInsets() {
		requestFitSystemWindows();
	}

	/** AOSP ViewRootImpl.dispatchApplyInsets, scheduled for this window root */
	void tlScheduleApplyInsets() {
		if (tlApplyInsetsRequested)
			return;
		tlApplyInsetsRequested = true;
		post(new Runnable() {
			@Override
			public void run() {
				tlApplyInsetsRequested = false;
				// the root of a window: no parent view (a view moved under another root meanwhile
				// has that root dispatch to it)
				if (!(parent instanceof View) && isAttachedToWindow())
					dispatchApplyWindowInsets(getRootWindowInsets());
			}
		});
	}

	/** @hide AOSP: the framework applies the content insets policy of the window to this view */
	public void makeOptionalFitsSystemWindows() {
		tlOptionalFitsSystemWindows = true;
	}

	/** @hide AOSP */
	public void makeFrameworkOptionalFitsSystemWindows() {
		tlFrameworkOptionalFitsSystemWindows = true;
	}

	/** @hide AOSP */
	public boolean isFrameworkOptionalFitsSystemWindows() {
		return tlFrameworkOptionalFitsSystemWindows;
	}

	/** AOSP: the legacy insets entry point; it runs the newer dispatch unless called from it */
	@Deprecated
	protected boolean fitSystemWindows(Rect insets) {
		if (!tlApplyingInsets) {
			if (insets == null) {
				// Null insets by definition have already been consumed.
				return false;
			}
			// not dispatching the newer apply insets call: dispatch into it
			try {
				tlFittingSystemWindows = true;
				return dispatchApplyWindowInsets(new WindowInsets(insets)).isConsumed();
			} finally {
				tlFittingSystemWindows = false;
			}
		} else {
			// called from the newer apply insets path: the standard fallback behaviour
			return tlFitSystemWindowsInt(insets);
		}
	}

	private boolean tlFitSystemWindowsInt(Rect insets) {
		if (tlFitsSystemWindows) {
			Rect localInsets = new Rect();
			boolean res = computeFitSystemWindows(insets, localInsets);
			tlApplyInsets(localInsets);
			return res;
		}
		return false;
	}

	/** AOSP applyInsets/internalSetPadding: the insets become the view's padding (a layout only when it changes) */
	private void tlApplyInsets(Rect insets) {
		if (paddingLeft != insets.left || paddingTop != insets.top || paddingRight != insets.right || paddingBottom != insets.bottom)
			setPadding(insets.left, insets.top, insets.right, insets.bottom);
	}

	/** AOSP: the part of the insets this view applies, and whether the system window insets got consumed */
	protected boolean computeFitSystemWindows(Rect inoutInsets, Rect outLocalInsets) {
		WindowInsets innerInsets = computeSystemWindowInsets(new WindowInsets(inoutInsets), outLocalInsets);
		inoutInsets.set(innerInsets.getSystemWindowInsetsAsRect());
		return innerInsets.isSystemWindowInsetsConsumed();
	}

	/**
	 * AOSP: the insets this view applies (outLocalInsets) and what stays for its children. A view
	 * the framework fits optionally follows the window's content policy (Window
	 * setDecorFitsSystemWindows): fitting, it takes the system window insets; otherwise the app
	 * handles them itself.
	 */
	public WindowInsets computeSystemWindowInsets(WindowInsets in, Rect outLocalInsets) {
		final boolean isOptionalFitSystemWindows = tlOptionalFitsSystemWindows || tlFrameworkOptionalFitsSystemWindows;
		if (isOptionalFitSystemWindows && isAttachedToWindow()) {
			final Window window = native_get_window(widget);
			if (window == null || !window.tlDecorFitsSystemWindows()) {
				// The application wants to take care of fitting system window for the content.
				outLocalInsets.setEmpty();
				return in;
			}
			// AOSP ViewRootImpl sDefaultContentInsetsApplier
			final android.graphics.Insets insetsToApply = in.getSystemWindowInsets();
			outLocalInsets.set(insetsToApply.left, insetsToApply.top, insetsToApply.right, insetsToApply.bottom);
			return in.inset(insetsToApply).consumeSystemWindowInsets();
		} else {
			outLocalInsets.set(in.getSystemWindowInsetsAsRect());
			return in.consumeSystemWindowInsets().inset(outLocalInsets);
		}
	}

	/** AOSP: by default a fitsSystemWindows view takes the system window insets as its padding */
	public WindowInsets onApplyWindowInsets(WindowInsets insets) {
		if (tlFrameworkOptionalFitsSystemWindows && tlFitsSystemWindows) {
			Rect localInsets = new Rect();
			WindowInsets result = computeSystemWindowInsets(insets, localInsets);
			tlApplyInsets(localInsets);
			return result;
		}
		if (!tlFittingSystemWindows) {
			// not called from within a direct call to fitSystemWindows: call into it as a
			// fallback, in case this is a class that overrides it
			if (fitSystemWindows(insets.getSystemWindowInsetsAsRect()))
				return insets.consumeSystemWindowInsets();
		} else {
			if (tlFitSystemWindowsInt(insets.getSystemWindowInsetsAsRect()))
				return insets.consumeSystemWindowInsets();
		}
		return insets;
	}

	/** AOSP: the listener, or else onApplyWindowInsets, applies the insets to this view */
	public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
		try {
			tlApplyingInsets = true;
			if (tlOnApplyWindowInsetsListener != null)
				return tlOnApplyWindowInsetsListener.onApplyWindowInsets(this, insets);
			return onApplyWindowInsets(insets);
		} finally {
			tlApplyingInsets = false;
		}
	}

	/** AOSP: the insets the window got (before any view consumed some), null when not attached */
	public WindowInsets getRootWindowInsets() {
		if (!isAttachedToWindow())
			return null;
		final Window window = native_get_window(widget);
		return window != null ? window.tlGetWindowInsets() : Window.tlWindowInsetsFor(getContext());
	}

	/** AOSP ViewGroup.cleanupLayoutState: the forced layout this view asked for is dropped */
	void tlCleanupLayoutState() {
		layoutRequested = false;
	}

	/* AOSP key shortcuts and trackball events, which Window.superDispatchKeyShortcutEvent and
	 * superDispatchTrackballEvent hand to the decor view (layout; View members ATL lacked) */

	/** AOSP: a shortcut key goes to the focused view's onKeyShortcut */
	public boolean dispatchKeyShortcutEvent(KeyEvent event) {
		return onKeyShortcut(event.getKeyCode(), event);
	}

	/** AOSP: a plain view handles no shortcut (TextView and lists override it) */
	public boolean onKeyShortcut(int keyCode, KeyEvent event) {
		return false;
	}

	/** AOSP: the trackball event goes to onTrackballEvent */
	public boolean dispatchTrackballEvent(MotionEvent event) {
		return onTrackballEvent(event);
	}

	/** AOSP: a plain view does not consume trackball motion (the car has no trackball either) */
	public boolean onTrackballEvent(MotionEvent event) {
		return false;
	}

	/* AOSP scrolling basics: overscroll mode, scroll offsets, canScroll*, overScrollBy, the reveal
	 * hint; ScrollView and HorizontalScrollView (AOSP ports) build on them (layout) */

	/** AOSP initial mode: OVER_SCROLL_IF_CONTENT_SCROLLS */
	private int tlOverScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS;
	/** AOSP PFLAG3_NO_REVEAL_ON_FOCUS, inverted: a focused view asks its scroll parent to show it */
	private boolean tlRevealOnFocusHint = true;

	/** AOSP: when the view shows an overscroll effect (edge glow / stretch) */
	public void setOverScrollMode(int overScrollMode) {
		if (overScrollMode != OVER_SCROLL_ALWAYS && overScrollMode != OVER_SCROLL_IF_CONTENT_SCROLLS && overScrollMode != OVER_SCROLL_NEVER)
			throw new IllegalArgumentException("Invalid overscroll mode " + overScrollMode);
		tlOverScrollMode = overScrollMode;
	}

	public int getOverScrollMode() {
		return tlOverScrollMode;
	}

	/** AOSP: the horizontal scroll position */
	protected int computeHorizontalScrollOffset() {
		return scrollX;
	}

	/** AOSP: whether the view can scroll left (negative direction) or right (positive) */
	public boolean canScrollHorizontally(int direction) {
		final int offset = computeHorizontalScrollOffset();
		final int range = computeHorizontalScrollRange() - computeHorizontalScrollExtent();
		if (range == 0)
			return false;
		if (direction < 0)
			return offset > 0;
		else
			return offset < range - 1;
	}

	/** AOSP: whether the view can scroll up (negative direction) or down (positive) */
	public boolean canScrollVertically(int direction) {
		final int offset = computeVerticalScrollOffset();
		final int range = computeVerticalScrollRange() - computeVerticalScrollExtent();
		if (range == 0)
			return false;
		if (direction < 0)
			return offset > 0;
		else
			return offset < range - 1;
	}

	/**
	 * AOSP: scrolls with overscroll, the mode and the ranges deciding how far past the content it
	 * may go; the result goes to onOverScrolled. True when the scroll was clamped.
	 */
	protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY, int scrollRangeX, int scrollRangeY, int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
		final int overScrollMode = tlOverScrollMode;
		final boolean canScrollHorizontal = computeHorizontalScrollRange() > computeHorizontalScrollExtent();
		final boolean canScrollVertical = computeVerticalScrollRange() > computeVerticalScrollExtent();
		final boolean overScrollHorizontal = overScrollMode == OVER_SCROLL_ALWAYS || (overScrollMode == OVER_SCROLL_IF_CONTENT_SCROLLS && canScrollHorizontal);
		final boolean overScrollVertical = overScrollMode == OVER_SCROLL_ALWAYS || (overScrollMode == OVER_SCROLL_IF_CONTENT_SCROLLS && canScrollVertical);

		int newScrollX = scrollX + deltaX;
		if (!overScrollHorizontal)
			maxOverScrollX = 0;
		int newScrollY = scrollY + deltaY;
		if (!overScrollVertical)
			maxOverScrollY = 0;

		// Clamp values if at the limits and record
		final int left = -maxOverScrollX;
		final int right = maxOverScrollX + scrollRangeX;
		final int top = -maxOverScrollY;
		final int bottom = maxOverScrollY + scrollRangeY;

		boolean clampedX = false;
		if (newScrollX > right) {
			newScrollX = right;
			clampedX = true;
		} else if (newScrollX < left) {
			newScrollX = left;
			clampedX = true;
		}
		boolean clampedY = false;
		if (newScrollY > bottom) {
			newScrollY = bottom;
			clampedY = true;
		} else if (newScrollY < top) {
			newScrollY = top;
			clampedY = true;
		}
		onOverScrolled(newScrollX, newScrollY, clampedX, clampedY);
		return clampedX || clampedY;
	}

	/** AOSP: the result of overScrollBy; a plain view does nothing with it (AOSP: intentionally empty) */
	protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
	}

	/** AOSP: whether this view, once focused, asks its scrolling ancestors to bring it on screen */
	public final void setRevealOnFocusHint(boolean revealOnFocus) {
		tlRevealOnFocusHint = revealOnFocus;
	}

	public final boolean getRevealOnFocusHint() {
		return tlRevealOnFocusHint;
	}

	/* ---- teslalinux: the window's redraw for a descendant (AOSP ViewRootImpl.onDescendantInvalidated) ----
	 * AOSP's ViewRootImpl answers onDescendantInvalidated with invalidate(): the next frame redraws
	 * the window. Compose moves and fades its layers (RenderNode position, translation, alpha) and
	 * then reports only onDescendantInvalidated(ownerView, ownerView), without View.invalidate(); the
	 * window root (WindowManagerImpl) queues the redraw of that view's widget through this. */

	/** The view's widget is drawn again on the next frame (its display list re-evaluated), without
	 * telling the parent chain: the caller is the end of that chain. */
	void tlQueueWindowRedraw() {
		if (widget != 0)
			nativeInvalidate(widget);
	}

	/* ---- teslalinux: View transform properties and view animations (AOSP View + RenderNode + HWUI) ----
	 * scaleX/Y, rotation, rotationX/Y, pivot (the view's centre unless set), camera distance and the
	 * running android.view.animation.Animation's Transformation compose, with translationX/Y, into one
	 * matrix the way HWUI draws a view (RenderNode::setViewProperties: position, animation matrix,
	 * T(translation) . R/S about the pivot). The parent's native layout allocates the child's GTK widget
	 * with that matrix (native-view-transform.sh); GTK's picking honours it, so touches follow the view.
	 * While the matrix is a pure translation tlLayerMatrix stays null and the old allocation offset
	 * path is used. View animations are sampled on every Choreographer frame like ViewGroup.drawChild
	 * samples them (AOSP: applyLegacyAnimation), instead of jumping to their end. */
	private float tlScaleX = 1f;
	private float tlScaleY = 1f;
	private float tlRotation;
	private float tlRotationX;
	private float tlRotationY;
	private float tlPivotX;
	private float tlPivotY;
	private boolean tlPivotSet;
	private float tlCameraDistance;
	/** read by the native layout (native-view-transform.sh): the matrix minus left/top, null when only translated */
	private float[] tlLayerMatrix;
	private int tlLayerW = -1;
	private int tlLayerH = -1;

	private Animation tlAnimation;
	private final android.view.animation.Transformation tlAnimTransformation = new android.view.animation.Transformation();
	private boolean tlAnimApplied;
	private float tlAnimAlpha = 1f;
	private boolean tlAnimFramePending;
	private boolean tlAnimStartReported;
	private Choreographer.FrameCallback tlAnimFrame;

	/** AOSP default camera distance: 8 inches (RenderNode), in pixels at this density */
	private float tlDefaultCameraDistance() {
		return 8f * getResources().getDisplayMetrics().densityDpi;
	}

	public float getCameraDistance() {
		return tlCameraDistance != 0f ? tlCameraDistance : tlDefaultCameraDistance();
	}

	public boolean hasIdentityMatrix() {
		return getMatrix().isIdentity();
	}

	/** AOSP View.getMatrix(): translation, then rotation and scale about the pivot (no left/top, no animation) */
	private void tlPropertyMatrix(Matrix m) {
		final float px = getPivotX(), py = getPivotY();
		m.reset();
		m.setTranslate(getTranslationX(), getTranslationY());
		m.preTranslate(px, py);
		if (tlRotationX != 0f || tlRotationY != 0f) {
			// Sk3DView/Camera as HWUI uses it: rotateX, rotateY, then a perspective projection from the
			// camera; the depth is 72 points per inch of camera distance (RenderNode's unit is inches)
			final float dpi = getResources().getDisplayMetrics().densityDpi;
			final float depth = 72f * getCameraDistance() / (dpi > 0 ? dpi : 160f);
			final double ax = Math.toRadians(tlRotationX), ay = Math.toRadians(-tlRotationY);
			final float cx = (float) Math.cos(ax), sxr = (float) Math.sin(ax);
			final float cy = (float) Math.cos(ay), syr = (float) Math.sin(ay);
			// R = Ry . Rx applied to (x, y, 0): x' = cy*x + syr*sxr*y, y' = cx*y, z' = -syr*x + cy*sxr*y
			final Matrix p = new Matrix();
			p.setValues(new float[] {
				cy, syr * sxr, 0,
				0, cx, 0,
				-syr / depth, cy * sxr / depth, 1,
			});
			m.preConcat(p);
		}
		m.preRotate(tlRotation);
		m.preScale(tlScaleX, tlScaleY);
		m.preTranslate(-px, -py);
	}

	/** the composed matrix changed (or may have): publish it for the native layout and re-allocate */
	private void tlTransformChanged() {
		tlComputeLayerMatrix();
		if (parent instanceof View)
			((View) parent).native_queueAllocate(((View) parent).widget);
		invalidate();
	}

	/** composes and publishes the matrix (tlLayerMatrix) without asking for a new allocation */
	private void tlComputeLayerMatrix() {
		final Matrix m = new Matrix();
		tlPropertyMatrix(m);
		if (tlAnimApplied) {
			final Matrix anim = tlAnimTransformation.getMatrix();
			if (!anim.isIdentity())
				m.postConcat(anim);
		}
		float[] v = new float[9];
		m.getValues(v);
		final boolean onlyTranslation = v[0] == 1f && v[1] == 0f && v[3] == 0f && v[4] == 1f && v[6] == 0f && v[7] == 0f && v[8] == 1f
		                                && v[2] == getTranslationX() && v[5] == getTranslationY();
		tlLayerMatrix = onlyTranslation ? null : v;
		tlLayerW = getWidth();
		tlLayerH = getHeight();
	}

	/** the default pivot is the centre: a size change moves it (AOSP RenderNode keeps it centred) */
	private void tlCheckPivotForSize() {
		if (!tlPivotSet && tlLayerMatrix != null && (tlLayerW != getWidth() || tlLayerH != getHeight()))
			tlTransformChanged();
	}

	/** view alpha times the running animation's alpha (AOSP: transformToApply.getAlpha()) */
	private void tlApplyAnimAlpha() {
		native_setVisibility(widget, visibility, getAlpha() * getTransitionAlpha() * tlAnimAlpha, atl_enabled);
	}

	private void tlScheduleAnimationFrame() {
		if (tlAnimFrame == null) {
			tlAnimFrame = new Choreographer.FrameCallback() {
				@Override
				public void doFrame(long frameTimeNanos) {
					tlAnimFramePending = false;
					tlStepAnimation();
				}
			};
		}
		if (!tlAnimFramePending) {
			tlAnimFramePending = true;
			Choreographer.getInstance().postFrameCallback(tlAnimFrame);
		}
	}

	/** AOSP View.applyLegacyAnimation() + ViewGroup.finishAnimatingView(), once per frame */
	private void tlStepAnimation() {
		final Animation a = tlAnimation;
		if (a == null)
			return;
		if (!a.isInitialized()) {
			final View p = parent instanceof View ? (View) parent : null;
			a.initialize(getWidth(), getHeight(), p != null ? p.getWidth() : getWidth(), p != null ? p.getHeight() : getHeight());
		}
		if (!tlAnimStartReported) {
			tlAnimStartReported = true;
			onAnimationStart();
		}
		final boolean more = a.getTransformation(android.view.animation.AnimationUtils.currentAnimationTimeMillis(), tlAnimTransformation, 1f);
		if (tlAnimation != a)
			return; // a listener replaced or cleared the animation
		tlAnimApplied = true;
		final int type = tlAnimTransformation.getTransformationType();
		tlAnimAlpha = (type & android.view.animation.Transformation.TYPE_ALPHA) != 0 ? tlAnimTransformation.getAlpha() : 1f;
		tlTransformChanged();
		tlApplyAnimAlpha();
		if (more) {
			tlScheduleAnimationFrame();
			return;
		}
		// finished: AOSP keeps a fillAfter animation attached (its last frame stays applied), clears
		// any other, and tells the view
		onAnimationEnd();
		if (!a.getFillAfter() && tlAnimation == a)
			clearAnimation();
	}

	// ---- teslalinux (vcore): View state AOSP keeps in mViewFlags/mPrivateFlags* and friends ----

	/** layout() / layoutInternal() frames on the stack: a layout pass is running (isInLayout) */
	private static int tlLayoutDepth;
	/** PFLAG3_IS_LAID_OUT: set by a layout, cleared when detached */
	private boolean tlLaidOut;
	private boolean tlWillNotDraw;
	private boolean tlSaveEnabled = true;
	private boolean tlSaveFromParentEnabled = true;
	private boolean tlHapticFeedbackEnabled = true;
	private boolean tlSoundEffectsEnabled = true;
	private boolean tlPaddingRelative;
	private boolean tlScrollContainer;
	private boolean tlTemporarilyDetached;
	private int tlTransientStateCount;
	private boolean tlHorizontalFadingEdge, tlVerticalFadingEdge;
	/** ScrollabilityCache.fadingEdgeLength: -1 until a fading edge is enabled or the length set */
	private int tlFadingEdgeLength = -1;
	private boolean tlHorizontalScrollBar, tlVerticalScrollBar;
	private int tlScrollBarStyle = SCROLLBARS_INSIDE_OVERLAY;
	private Rect tlClipBounds;
	private ViewOutlineProvider tlOutlineProvider = ViewOutlineProvider.BACKGROUND;
	private boolean tlClipToOutline;
	private int tlOutlineW = -1, tlOutlineH = -1;
	private boolean tlOutlineRebuildPosted;

	/** The View attributes AOSP's constructor reads that ATL's did not. */
	private void tlVcoreReadAttrs(android.content.res.TypedArray a) {
		if (a.hasValue(com.android.internal.R.styleable.View_saveEnabled))
			tlSaveEnabled = a.getBoolean(com.android.internal.R.styleable.View_saveEnabled, true);
		if (a.hasValue(com.android.internal.R.styleable.View_hapticFeedbackEnabled))
			tlHapticFeedbackEnabled = a.getBoolean(com.android.internal.R.styleable.View_hapticFeedbackEnabled, true);
		if (a.hasValue(com.android.internal.R.styleable.View_soundEffectsEnabled))
			tlSoundEffectsEnabled = a.getBoolean(com.android.internal.R.styleable.View_soundEffectsEnabled, true);
		if (a.hasValue(com.android.internal.R.styleable.View_isScrollContainer))
			tlScrollContainer = a.getBoolean(com.android.internal.R.styleable.View_isScrollContainer, false);
		if (a.hasValue(com.android.internal.R.styleable.View_scrollbars)) {
			final int scrollbars = a.getInt(com.android.internal.R.styleable.View_scrollbars, SCROLLBARS_NONE);
			tlHorizontalScrollBar = (scrollbars & SCROLLBARS_HORIZONTAL) != 0;
			tlVerticalScrollBar = (scrollbars & SCROLLBARS_VERTICAL) != 0;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_scrollbarStyle))
			tlScrollBarStyle = a.getInt(com.android.internal.R.styleable.View_scrollbarStyle, SCROLLBARS_INSIDE_OVERLAY) & SCROLLBARS_STYLE_MASK;
		// requiresFadingEdge (and the old fadingEdge) select the fading edges, fadingEdgeLength their size
		int fading = FADING_EDGE_NONE;
		if (a.hasValue(com.android.internal.R.styleable.View_requiresFadingEdge))
			fading = a.getInt(com.android.internal.R.styleable.View_requiresFadingEdge, FADING_EDGE_NONE);
		else if (a.hasValue(com.android.internal.R.styleable.View_fadingEdge))
			fading = a.getInt(com.android.internal.R.styleable.View_fadingEdge, FADING_EDGE_NONE);
		tlHorizontalFadingEdge = (fading & FADING_EDGE_HORIZONTAL) != 0;
		tlVerticalFadingEdge = (fading & FADING_EDGE_VERTICAL) != 0;
		if (fading != FADING_EDGE_NONE || a.hasValue(com.android.internal.R.styleable.View_fadingEdgeLength)) {
			tlFadingEdgeLength = a.getDimensionPixelSize(com.android.internal.R.styleable.View_fadingEdgeLength,
				ViewConfiguration.get(getContext()).getScaledFadingEdgeLength());
		}
		if (a.hasValue(com.android.internal.R.styleable.View_scrollX) || a.hasValue(com.android.internal.R.styleable.View_scrollY)) {
			scrollTo(a.getDimensionPixelOffset(com.android.internal.R.styleable.View_scrollX, 0),
				 a.getDimensionPixelOffset(com.android.internal.R.styleable.View_scrollY, 0));
		}
		tlVcoreReadFocusAttrs(a);
	}

	/** AOSP setFrame: a new size rebuilds the outline */
	private void tlFrameMaybeChanged() {
		final int w = getWidth(), h = getHeight();
		if (w != tlOutlineW || h != tlOutlineH) {
			tlOutlineW = w;
			tlOutlineH = h;
			if (tlClipToOutline)
				tlRebuildOutline();
		}
	}

	private static boolean sTlClipNativeMissing;

	/** The clip bounds go to the native widget, which clips its whole drawing (native-vcore-view-clip.sh). */
	private void tlNativeClipBounds() {
		if (widget == 0 || sTlClipNativeMissing)
			return;
		try {
			if (tlClipBounds != null)
				nativeSetClipBounds(widget, true, tlClipBounds.left, tlClipBounds.top, tlClipBounds.right, tlClipBounds.bottom);
			else
				nativeSetClipBounds(widget, false, 0, 0, 0, 0);
		} catch (UnsatisfiedLinkError e) {
			sTlClipNativeMissing = true; // an api-impl.jar newer than the native library
		}
	}

	private static native void nativeSetClipBounds(long widget, boolean set, int left, int top, int right, int bottom);
	private static native void nativeSetOutlineClip(long widget, boolean set, int left, int top, int right, int bottom, float radius);

	/** rebuildOutline() on the next event loop pass (several changes in a row are folded) */
	private void tlPostRebuildOutline() {
		if (!tlClipToOutline || tlOutlineRebuildPosted)
			return;
		tlOutlineRebuildPosted = true;
		tlMainHandler().post(new Runnable() {
			@Override
			public void run() {
				tlOutlineRebuildPosted = false;
				tlRebuildOutline();
			}
		});
	}

	/**
	 * AOSP rebuildOutline(): the outline provider fills an Outline, which clips the view when
	 * clipToOutline is set. Round rects (rects) clip through the native widget; a path outline is not
	 * clipped (TODO-real). An empty outline would hide the view in AOSP; it is left unclipped here
	 * because ATL's Drawable.getOutline() still leaves most drawables' outlines empty.
	 */
	private void tlRebuildOutline() {
		if (widget == 0 || sTlClipNativeMissing)
			return;
		try {
			if (!tlClipToOutline || tlOutlineProvider == null) {
				nativeSetOutlineClip(widget, false, 0, 0, 0, 0, 0);
				return;
			}
			android.graphics.Outline outline = new android.graphics.Outline();
			outline.setAlpha(1.0f);
			tlOutlineProvider.getOutline(this, outline);
			if (outline.mMode == android.graphics.Outline.MODE_ROUND_RECT && !outline.mRect.isEmpty()) {
				final float radius = outline.mRadius > 0 ? outline.mRadius : 0;
				nativeSetOutlineClip(widget, true, outline.mRect.left, outline.mRect.top, outline.mRect.right, outline.mRect.bottom, radius);
			} else {
				nativeSetOutlineClip(widget, false, 0, 0, 0, 0, 0);
			}
		} catch (UnsatisfiedLinkError e) {
			sTlClipNativeMissing = true;
		}
	}

	public boolean isSaveEnabled() {
		return tlSaveEnabled;
	}

	public boolean isSaveFromParentEnabled() {
		return tlSaveFromParentEnabled;
	}

	public boolean isHapticFeedbackEnabled() {
		return tlHapticFeedbackEnabled;
	}

	/** AOSP: click/navigation sounds of this view (on by default) */
	public void setSoundEffectsEnabled(boolean soundEffectsEnabled) {
		tlSoundEffectsEnabled = soundEffectsEnabled;
	}

	public boolean isSoundEffectsEnabled() {
		return tlSoundEffectsEnabled;
	}

	public boolean isScrollContainer() {
		return tlScrollContainer;
	}

	/**
	 * The vibration of a haptic feedback constant, after AOSP's HapticFeedbackVibrationProvider
	 * defaults on a device without predefined effects: a tick for light feedback, a click for the
	 * rest, a heavier click for long presses.
	 */
	private static long tlHapticDuration(int feedbackConstant) {
		switch (feedbackConstant) {
			case 0:  // LONG_PRESS
			case 3:  // KEYBOARD_TAP (KEYBOARD_PRESS)
				return 30;
			case 4:  // CLOCK_TICK
			case 8:  // TEXT_HANDLE_MOVE
			case 9:  // ENTRY_BUMP
				return 5;
			default: // VIRTUAL_KEY, CONTEXT_CLICK, CONFIRM, REJECT, ...
				return 12;
		}
	}

	public boolean isTemporarilyDetached() {
		return tlTemporarilyDetached;
	}

	/** AOSP: called when the view is detached temporarily (a ListView recycles it); empty by default */
	public void onStartTemporaryDetach() {
	}

	/** AOSP: called when the temporary detach is over; empty by default */
	public void onFinishTemporaryDetach() {
	}

	/** AOSP: a ViewGroup passes the hotspot on to children that duplicate its state; View: nothing */
	public void dispatchDrawableHotspotChanged(float x, float y) {
	}

	public boolean isHorizontalFadingEdgeEnabled() {
		return tlHorizontalFadingEdge;
	}

	public boolean isVerticalFadingEdgeEnabled() {
		return tlVerticalFadingEdge;
	}

	public int getHorizontalFadingEdgeLength() {
		return tlHorizontalFadingEdge ? Math.max(tlFadingEdgeLength, 0) : 0;
	}

	/** AOSP: how strongly the top edge fades, 1 while the content is scrolled down */
	protected float getTopFadingEdgeStrength() {
		return computeVerticalScrollOffset() > 0 ? 1.0f : 0.0f;
	}

	/** AOSP: how strongly the bottom edge fades, 1 while there is more content below */
	protected float getBottomFadingEdgeStrength() {
		return computeVerticalScrollOffset() + computeVerticalScrollExtent() < computeVerticalScrollRange() ? 1.0f : 0.0f;
	}

	/** AOSP: how strongly the left edge fades, 1 while the content is scrolled right */
	protected float getLeftFadingEdgeStrength() {
		// AOSP: computeHorizontalScrollOffset() > 0; View's default offset is the scroll position
		return getScrollX() > 0 ? 1.0f : 0.0f;
	}

	/** AOSP: how strongly the right edge fades, 1 while there is more content to the right */
	protected float getRightFadingEdgeStrength() {
		return getScrollX() + computeHorizontalScrollExtent() < computeHorizontalScrollRange() ? 1.0f : 0.0f;
	}

	/** AOSP: the view's known solid background colour for fading edge drawing, 0 when unknown */
	public int getSolidColor() {
		return 0;
	}

	public boolean isHorizontalScrollBarEnabled() {
		return tlHorizontalScrollBar;
	}

	public boolean isVerticalScrollBarEnabled() {
		return tlVerticalScrollBar;
	}

	public int getScrollBarStyle() {
		return tlScrollBarStyle;
	}


	// ---- teslalinux (vcore): user-set focus order, as AOSP's View keeps it ----
	private int tlNextFocusLeftId = NO_ID, tlNextFocusRightId = NO_ID, tlNextFocusUpId = NO_ID, tlNextFocusDownId = NO_ID;
	private int tlNextFocusForwardId = NO_ID;
	private boolean tlKeyboardNavigationCluster;
	private boolean tlFocusedByDefault;

	public int getNextFocusLeftId() { return tlNextFocusLeftId; }
	public int getNextFocusRightId() { return tlNextFocusRightId; }
	public int getNextFocusUpId() { return tlNextFocusUpId; }
	public int getNextFocusDownId() { return tlNextFocusDownId; }
	/** AOSP: the view focused by FOCUS_FORWARD from this one (attribute nextFocusForward) */
	public int getNextFocusForwardId() { return tlNextFocusForwardId; }
	public void setNextFocusForwardId(int nextFocusForwardId) { tlNextFocusForwardId = nextFocusForwardId; }

	/** The focus attributes of AOSP's View constructor. */
	private void tlVcoreReadFocusAttrs(android.content.res.TypedArray a) {
		tlNextFocusLeftId = a.getResourceId(com.android.internal.R.styleable.View_nextFocusLeft, tlNextFocusLeftId);
		tlNextFocusRightId = a.getResourceId(com.android.internal.R.styleable.View_nextFocusRight, tlNextFocusRightId);
		tlNextFocusUpId = a.getResourceId(com.android.internal.R.styleable.View_nextFocusUp, tlNextFocusUpId);
		tlNextFocusDownId = a.getResourceId(com.android.internal.R.styleable.View_nextFocusDown, tlNextFocusDownId);
		tlNextFocusForwardId = a.getResourceId(com.android.internal.R.styleable.View_nextFocusForward, tlNextFocusForwardId);
		if (a.hasValue(com.android.internal.R.styleable.View_nextClusterForward))
			setNextClusterForwardId(a.getResourceId(com.android.internal.R.styleable.View_nextClusterForward, NO_ID));
		if (a.hasValue(com.android.internal.R.styleable.View_keyboardNavigationCluster))
			tlKeyboardNavigationCluster = a.getBoolean(com.android.internal.R.styleable.View_keyboardNavigationCluster, false);
		if (a.hasValue(com.android.internal.R.styleable.View_focusedByDefault))
			tlFocusedByDefault = a.getBoolean(com.android.internal.R.styleable.View_focusedByDefault, false);
	}

	/** AOSP: the view a focus move in this direction goes to by the user-set ids, null when none is set */
	View findUserSetNextFocus(View root, int direction) {
		switch (direction) {
			case FOCUS_LEFT:
				if (tlNextFocusLeftId == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, tlNextFocusLeftId);
			case FOCUS_RIGHT:
				if (tlNextFocusRightId == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, tlNextFocusRightId);
			case FOCUS_UP:
				if (tlNextFocusUpId == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, tlNextFocusUpId);
			case FOCUS_DOWN:
				if (tlNextFocusDownId == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, tlNextFocusDownId);
			case FOCUS_FORWARD:
				if (tlNextFocusForwardId == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, tlNextFocusForwardId);
			case FOCUS_BACKWARD: {
				if (getId() == View.NO_ID)
					return null;
				final View rootView = root;
				final View startView = this;
				// Since we have forward links but no backward links, we need to find the view that
				// forward links to this view. We can't just find the view with the specified ID
				// because view IDs need not be unique throughout the tree.
				return root.findViewByPredicateInsideOut(startView, new java.util.function.Predicate<View>() {
					@Override
					public boolean test(View t) {
						return t.tlNextFocusForwardId != View.NO_ID && tlFindViewInsideOutShouldExist(rootView, t, t.tlNextFocusForwardId) == startView;
					}
				});
			}
		}
		return null;
	}

	/** AOSP: the next cluster by the user-set id (FOCUS_FORWARD) or the cluster that forward-links here */
	View findUserSetNextKeyboardNavigationCluster(View root, int direction) {
		switch (direction) {
			case FOCUS_FORWARD:
				if (getNextClusterForwardId() == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, getNextClusterForwardId());
			case FOCUS_BACKWARD: {
				if (getId() == View.NO_ID)
					return null;
				final int id = getId();
				return root.findViewByPredicateInsideOut(this, new java.util.function.Predicate<View>() {
					@Override
					public boolean test(View t) {
						return t.getNextClusterForwardId() == id;
					}
				});
			}
		}
		return null;
	}

	private static View tlFindViewInsideOutShouldExist(View root, View start, final int id) {
		View result = root.findViewByPredicateInsideOut(start, new java.util.function.Predicate<View>() {
			@Override
			public boolean test(View v) {
				return v.getId() == id;
			}
		});
		if (result == null)
			android.util.Log.w("View", "couldn't find view with id " + id);
		return result;
	}

	/**
	 * AOSP: looks for a view matching the predicate, first in start's subtree, then in each ancestor's
	 * subtree (skipping the subtree already searched), up to this view.
	 */
	public final <T extends View> T findViewByPredicateInsideOut(View start, java.util.function.Predicate<View> predicate) {
		View childToSkip = null;
		for (;;) {
			T view = tlFindViewByPredicateTraversal(start, predicate, childToSkip);
			if (view != null || start == this) {
				return view;
			}
			ViewParent parent = start.getParent();
			if (parent == null || !(parent instanceof View)) {
				return null;
			}
			childToSkip = start;
			start = (View)parent;
		}
	}

	/** AOSP: the first view of this subtree (this view first) matching the predicate */
	public final <T extends View> T findViewByPredicate(java.util.function.Predicate<View> predicate) {
		return tlFindViewByPredicateTraversal(this, predicate, null);
	}

	/** AOSP View/ViewGroup.findViewByPredicateTraversal: the view, then its children in order */
	@SuppressWarnings("unchecked")
	private static <T extends View> T tlFindViewByPredicateTraversal(View v, java.util.function.Predicate<View> predicate, View childToSkip) {
		if (predicate.test(v))
			return (T)v;
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup)v;
			final int n = g.getChildCount();
			for (int i = 0; i < n; i++) {
				View child = g.getChildAt(i);
				if (child == null || child == childToSkip)
					continue;
				T found = tlFindViewByPredicateTraversal(child, predicate, null);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	// ---- teslalinux (vcore): plain View state AOSP keeps ----
	/* FOCUSABLE_AUTO, setFocusable(int) and getFocusable() are the focus model's (View.java.repl, tl_focus_model) */
	public static final int SCROLL_INDICATOR_TOP = 0x1;
	public static final int SCROLL_INDICATOR_BOTTOM = 0x2;
	public static final int SCROLL_INDICATOR_LEFT = 0x4;
	public static final int SCROLL_INDICATOR_RIGHT = 0x8;
	public static final int SCROLL_INDICATOR_START = 0x10;
	public static final int SCROLL_INDICATOR_END = 0x20;
	static final int SCROLL_INDICATORS_ALL = SCROLL_INDICATOR_TOP | SCROLL_INDICATOR_BOTTOM | SCROLL_INDICATOR_LEFT
						 | SCROLL_INDICATOR_RIGHT | SCROLL_INDICATOR_START | SCROLL_INDICATOR_END;
	private View.OnCreateContextMenuListener tlOnCreateContextMenuListener;
	private int tlAccessibilityLiveRegion;
	private int tlScrollIndicators;
	private String tlTransitionName;
	private boolean tlDefaultFocusHighlightEnabled = true;
	private CharSequence tlAccessibilityPaneTitle;
	private boolean tlAccessibilityHeading;
	private int tlLabelFor = NO_ID;
	private CharSequence tlStateDescription;
	private java.util.ArrayList<android.graphics.Rect> tlGestureExclusionRects;
	private java.util.ArrayList<android.graphics.Rect> tlKeepClearRects;

	/* ---- teslalinux: transform attributes from XML and Z order (AOSP View constructor + RenderNode Z) ----
	 * AOSP's View constructor reads translationX/Y/Z, elevation, rotation, rotationX/Y, scaleX/Y and
	 * transformPivotX/Y; ATL read none of them, so a layout that stacks siblings by translationZ (a
	 * segmented control's labels above its sliding bubble) drew them in child order. A Z change tells
	 * the parent, which draws (and picks touches from) its children ordered by Z. */

	/** AOSP View(Context, AttributeSet, int, int): the transform part of the attribute loop */
	private void tlReadTransformAttrs(TypedArray a) {
		boolean transformSet = false;
		float tx = 0, ty = 0, tz = 0, elev = 0, rot = 0, rotX = 0, rotY = 0, sx = 1f, sy = 1f;
		if (a.hasValue(com.android.internal.R.styleable.View_translationX)) {
			tx = a.getDimension(com.android.internal.R.styleable.View_translationX, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_translationY)) {
			ty = a.getDimension(com.android.internal.R.styleable.View_translationY, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_translationZ)) {
			tz = a.getDimension(com.android.internal.R.styleable.View_translationZ, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_elevation)) {
			elev = a.getDimension(com.android.internal.R.styleable.View_elevation, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_rotation)) {
			rot = a.getFloat(com.android.internal.R.styleable.View_rotation, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_rotationX)) {
			rotX = a.getFloat(com.android.internal.R.styleable.View_rotationX, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_rotationY)) {
			rotY = a.getFloat(com.android.internal.R.styleable.View_rotationY, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_scaleX)) {
			sx = a.getFloat(com.android.internal.R.styleable.View_scaleX, 1f);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_scaleY)) {
			sy = a.getFloat(com.android.internal.R.styleable.View_scaleY, 1f);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_transformPivotX))
			setPivotX(a.getDimension(com.android.internal.R.styleable.View_transformPivotX, 0));
		if (a.hasValue(com.android.internal.R.styleable.View_transformPivotY))
			setPivotY(a.getDimension(com.android.internal.R.styleable.View_transformPivotY, 0));
		if (transformSet) {
			setTranslationX(tx);
			setTranslationY(ty);
			setTranslationZ(tz);
			setElevation(elev);
			setRotation(rot);
			setRotationX(rotX);
			setRotationY(rotY);
			setScaleX(sx);
			setScaleY(sy);
		}
	}

	/** the view's Z (elevation + translationZ) changed: the parent re-orders what it draws */
	private void tlZChanged() {
		if (parent instanceof ViewGroup)
			((ViewGroup) parent).tlRestackChildren();
	}
}
