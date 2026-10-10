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
	private static final int NOT_FOCUSABLE = 0x00000000;

	/**
	 * This view wants keystrokes. Use with TAKES_FOCUS_MASK when calling
	 * setFlags.
	 */
	private static final int FOCUSABLE = 0x00000001;

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

	public interface OnLayoutChangeListener {
		// TODO
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
	private int backgroundTint = 0;

	private int minWidth = 0;
	private int minHeight = 0;

	protected int paddingLeft = 0;
	protected int paddingTop = 0;
	protected int paddingRight = 0;
	protected int paddingBottom = 0;

	private boolean atl_enabled = true;
	private boolean atl_focusable = true;

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
		if (a.hasValue(com.android.internal.R.styleable.View_backgroundTint))
			backgroundTint = a.getColor(com.android.internal.R.styleable.View_backgroundTint, 0);
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
			atl_focusable = a.getBoolean(com.android.internal.R.styleable.View_focusable, true);
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
		a.recycle();
		onCreateDrawableState(0);
	}

	protected int[] onCreateDrawableState(int extraSpace) {
		return new int[0];
	}

	protected static int[] mergeDrawableStates(int[] curState, int[] newState) {
		return new int[0];
	}

	public View findViewById(int id) {
		if (this.id == id)
			return this;
		else
			return null;
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
	public void setOnScrollChangeListener(OnScrollChangeListener l) {}

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

	public void setFocusable(boolean focusable) {
		atl_focusable = focusable;
	}
	public void setFocusable(int focusable) {}
	/** AOSP: focusable in touch mode implies focusable. */
	public void setFocusableInTouchMode(boolean focusableInTouchMode) {
		tlFocusableInTouchMode = focusableInTouchMode;
		if (focusableInTouchMode)
			setFocusable(true);
	}
	private boolean tlFocusableInTouchMode;
	public final boolean requestFocus() {
		return requestFocus(View.FOCUS_DOWN);
	}
	public final boolean requestFocus(int direction) {
		return requestFocus(direction, null);
	}
	public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
		if (atl_enabled && atl_focusable && visibility == VISIBLE) {
			nativeRequestFocus(widget, direction);
			return true;
		}
		return false;
	}
	public final boolean requestFocusFromTouch() {
		// TODO: if (isInTouchMode()) leave touch mode
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

	public void setPressed(boolean pressed) {
		if (this.pressed != pressed) {
			this.pressed = pressed;
			if (background != null && background.isStateful())
				background.setState(getDrawableState());
		}
	}

	public void setSelected(boolean selected) {}

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

	public void invalidateDrawable(Drawable drawable) {
		nativeInvalidate(widget);
	}

	public void scheduleDrawable(Drawable drawable, Runnable runnable, long time) {
		postDelayed(runnable, time - SystemClock.uptimeMillis());
	}

	public void unscheduleDrawable(Drawable drawable, Runnable runnable) {
		/* TODO */
	}

	public void unscheduleDrawable(Drawable drawable) {}

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
		this.visibility = visibility;
		native_setVisibility(widget, visibility, alpha, atl_enabled);
		dispatchVisibilityChanged(this, visibility);
		if (isAttachedToWindow()) // AOSP setFlags: the aggregated visibility follows while in a window
			dispatchVisibilityAggregated(visibility == VISIBLE);
	}

	public void setPadding(int left, int top, int right, int bottom) {
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
	public final boolean getLocalVisibleRect(Rect r) { return false; }

	public final int getScrollX() {
		return scrollX;
	}
	public final int getScrollY() {
		return scrollY;
	}

	public void scrollTo(int x, int y) {
		scrollX = x;
		scrollY = y;
		invalidate();
	}

	protected int computeVerticalScrollOffset() {
		return -1;
	}

	protected void onScrollChanged(int l, int t, int oldl, int oldt) {}

	public void getLocationOnScreen(int[] location) {
		Rect rect = new Rect();
		getGlobalVisibleRect(rect);
		location[0] = rect.left;
		location[1] = rect.top;
	}

	public boolean performHapticFeedback(int feedbackConstant) {
		return performHapticFeedback(feedbackConstant, 0);
	}

	public boolean performHapticFeedback(int feedbackConstant, int flags) {
		// arbitrary; TODO: better mimic what AOSP does
		Vibrator vibrator = (Vibrator)getContext().getSystemService("vibrator");
		vibrator.vibrate(10);
		return true; // FIXME why is it not void
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

	public void postInvalidate() {
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				invalidate();
			}
		});
	}

	public void postInvalidate(final int left, final int top, final int right, final int bottom) {
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				invalidate(left, top, right, bottom);
			}
		});
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

	public void refreshDrawableState() {
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
		atl_enabled = enabled;
		native_setVisibility(widget, visibility, alpha, atl_enabled);
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

	protected int getSuggestedMinimumHeight() {
		return getMinimumHeight();
	}
	protected int getSuggestedMinimumWidth() {
		return getMinimumWidth();
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
		this.left = l;
		this.top = t;
		this.right = r;
		this.bottom = b;
		native_layout(widget, l, t, r, b);
	}

	/** Helper function to be called from GTKs LayoutManager via JNI */
	private void layoutInternal(int width, int height) {
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
		this.background = backgroundDrawable;
		if (backgroundDrawable != null) {
			backgroundDrawable.setCallback(this);
			if (backgroundTint != 0)
				backgroundDrawable.setTint(backgroundTint);
		}
		native_setBackgroundDrawable(widget, backgroundDrawable != null ? backgroundDrawable.paintable : 0);
	}

	public int getOverScrollMode() { return 0; }

	public void setFitsSystemWindows(boolean fitsSystemWindows) {}

	public void setWillNotDraw(boolean value) {}

	public void setScrollContainer(boolean isScrollContainer) {}

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

	public void setOverScrollMode(int mode) {}

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

	public void setSaveFromParentEnabled(boolean enabled) {}

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

	public void addOnLayoutChangeListener(OnLayoutChangeListener listener) {}
	public void removeOnLayoutChangeListener(OnLayoutChangeListener listener) {}

	public boolean isSelected() { return false; }

	public void sendAccessibilityEvent(int eventType) {}

	public void setMinimumHeight(int minHeight) {
		this.minHeight = minHeight;
	}
	public void setMinimumWidth(int minWidth) {
		this.minWidth = minWidth;
	}

	public void setActivated(boolean activated) {}

	public boolean isActivated() { return false; }

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

	public final int[] getDrawableState() {
		int[] state = new int[2];
		state[0] = R.attr.state_enabled;
		if (pressed) {
			state[1] = R.attr.state_pressed;
		}
		return state;
	}

	public float getRotation() { return 0.f; }

	public void bringToFront() {}

	public boolean isEnabled() { return atl_enabled; }
	public boolean hasFocus() { return false; }
	public boolean isLayoutRequested() { return layoutRequested; }
	public int getBaseline() { return -1; }
	public boolean hasFocusable() { return false; }
	private static native boolean nativeIsFocused(long widget);
	public boolean isFocused() {
		return nativeIsFocused(widget);
	}

	public void clearAnimation() {}

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
		if (parent instanceof View)
			((View)parent).native_queueAllocate(((View)parent).widget);
	}

	public void setTranslationY(float translationY) {
		this.translationY = translationY;
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
		native_setVisibility(widget, visibility, alpha, atl_enabled);
		this.alpha = alpha;
	}

	public boolean onGenericMotionEvent(MotionEvent event) { return false; }

	public boolean dispatchGenericMotionEvent(MotionEvent event) {
		return onGenericMotionEvent(event);
	}

	protected boolean awakenScrollBars() { return false; }

	protected native boolean native_getMatrix(long widget, long matrix);
	public Matrix getMatrix() {
		return Matrix.IDENTITY_MATRIX;
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

	public void clearFocus() {}

	public static View inflate(Context context, int resource, ViewGroup root) {
		LayoutInflater factory = LayoutInflater.from(context);
		return factory.inflate(resource, root);
	}

	public void saveHierarchyState(SparseArray<Parcelable> array) {}

	/** AOSP: the view takes its drawable state from its parent (see getDrawableState). */
	public void setDuplicateParentStateEnabled(boolean enabled) {
		tlDuplicateParentState = enabled;
	}
	private boolean tlDuplicateParentState;

	public boolean performClick() {
		return callOnClick();
	}

	public void playSoundEffect(int soundConstant) {}

	public void computeScroll() {}

	public void jumpDrawablesToCurrentState() {}

	public void setOnFocusChangeListener(View.OnFocusChangeListener l) {
		mOnFocusChangeListener = l;
	}

	public boolean hasWindowFocus() { return true; }

	public void setSaveEnabled(boolean enabled) {}

	public boolean willNotDraw() { return false; }

	public void setOnCreateContextMenuListener(View.OnCreateContextMenuListener l) {}

	protected void onAnimationStart() {}

	protected void onAnimationEnd() {}

	public void startAnimation(Animation animation) {
		if (animation == null)
			return;
		onAnimationStart();
		animation.start();
		onAnimationEnd();
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

	public boolean getFitsSystemWindows() { return true; }

	public void setOnApplyWindowInsetsListener(View.OnApplyWindowInsetsListener l) {}

	public final boolean isFocusable() { return atl_focusable; }
	public boolean isClickable() { return mClickable; }
	public boolean isLongClickable() { return mLongClickable; }

	public int getLayoutDirection() { return LAYOUT_DIRECTION_LTR; }

	public void setBackground(Drawable background) {
		setBackgroundDrawable(background);
	}

	private float elevation;
	public void setElevation(float elevation) {
		this.elevation = elevation;
	}
	public float getElevation() {
		return elevation;
	}

	public boolean isLaidOut() { return true; }

	public void postOnAnimation(Runnable action) {
		postDelayed(action, 1000 / 60);
	}

	public void postOnAnimationDelayed(Runnable action, long delayMillis) {
		if (delayMillis < 1000 / 60)
			delayMillis = 1000 / 60;
		postDelayed(action, delayMillis);
	}

	public void setHorizontalScrollBarEnabled(boolean enabled) {}

	public void setVerticalScrollBarEnabled(boolean enabled) {}

	public void postInvalidateOnAnimation() {
		postInvalidate();
	}

	public void setPaddingRelative(int start, int top, int end, int bottom) {}

	private native boolean nativeIsAttachedToWindow(long widget);
	public boolean isAttachedToWindow() {
		return nativeIsAttachedToWindow(widget);
	}

	public void requestApplyInsets() {}

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

	public View findFocus() { return this; }

	public int getMinimumHeight() { return minHeight; }
	public int getMinimumWidth() { return minWidth; }

	public boolean isNestedScrollingEnabled() { return false; }

	public void setClipToOutline(boolean clipToOutline) {}

	public boolean hasTransientState() { return false; }

	public final void cancelPendingInputEvents() {}

	public ViewOutlineProvider getOutlineProvider() { return new ViewOutlineProvider(); }
	public void setOutlineProvider(ViewOutlineProvider provider) {}

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

	public boolean isPaddingRelative() { return false; }

	public void setForeground(Drawable foreground) {}

	public boolean canScrollVertically(int value) { return false; }

	public boolean isInTouchMode() { return false; }

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
	}
	protected void onDetachedFromWindow() {
		/* AOSP dispatchDetachedFromWindow: a visible view becomes invisible when it leaves the window */
		if (tlIsAggregatedVisible())
			onVisibilityAggregated(false);
		if (onAttachStateChangeListener != null) {
			onAttachStateChangeListener.onViewDetachedFromWindow(this);
		}
		if (keepScreenOn)
			native_keep_screen_on(widget, false);
	}

	public void setLayerType(int layerType, Paint paint) {}

	public float getZ() {
		return getElevation() + getTranslationZ();
	}

	protected void onSizeChanged(int w, int h, int oldw, int oldh) {}

	public void setBackgroundTintList(ColorStateList tint) {}

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

	public void setAccessibilityLiveRegion(int mode) {}

	public void invalidateOutline() {}

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

	public boolean canScrollHorizontally(int direction) { return false; }

	protected native boolean native_getGlobalVisibleRect(long widget, Rect visibleRect);

	public boolean getGlobalVisibleRect(Rect visibleRect) {
		return native_getGlobalVisibleRect(widget, visibleRect);
	}

	public boolean onCheckIsTextEditor() { return false; }

	public boolean hasOnClickListeners() { return false; }

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

	public void setHapticFeedbackEnabled(boolean hapticFeedbackEnabled) {}

	public StateListAnimator getStateListAnimator() { return null; }

	public void requestFitSystemWindows() {}

	public boolean isPressed() { return pressed; }

	public native void getWindowVisibleDisplayFrame(Rect rect);

	public void setRotation(float rotation) {}
	public void setRotationX(float deg) {}
	public void setRotationY(float deg) {}

	public float getRotationX() { return 0.f; }
	public float getRotationY() { return 0.f; }

	public void setScaleX(float scaleX) {}
	public void setScaleY(float scaleY) {}

	public float getScaleX() { return 1.f; }
	public float getScaleY() { return 1.f; }

	public void setPivotX(float pivot_x) {}
	public void setPivotY(float pivot_y) {}

	public float getPivotX() { return 0.f; }
	public float getPivotY() { return 0.f; }

	public float getTranslationZ() {
		return mTranslationZ;
	}

	public void setTranslationZ(float translationZ) {
		mTranslationZ = translationZ;
	}

	public int getWindowSystemUiVisibility() {
		return 0;
	}

	public void setScrollIndicators(int indicators, int mask) {}

	public void setLayoutDirection(int layoutDirection) {}

	public ColorStateList getBackgroundTintList() { return null; }

	public PorterDuff.Mode getBackgroundTintMode() { return null; }

	public String getTransitionName() { return null; }

	public WindowId getWindowId() { return null; }

	public boolean isInLayout() {
		return false; // FIXME
	}

	public void setTextDirection(int textDirection) {}

	public Drawable getForeground() { return null; }

	public void setScrollbarFadingEnabled(boolean fadeEnabled) {}

	public void setScrollBarStyle(int style) {}

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

	public void setTransitionName(String transitionName) {}

	public Animation getAnimation() { return null; }

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

	public void setDefaultFocusHighlightEnabled(boolean enabled) {}

	public void setHorizontalFadingEdgeEnabled(boolean horizontalFadingEdgeEnabled) {}

	public Handler getHandler() {
		return new Handler(Looper.getMainLooper());
	}

	/** AOSP: true for a view attached to a hardware-accelerated window; every ATL window renders through GSK. */
	public boolean isHardwareAccelerated() {
		return isAttachedToWindow();
	}

	public void setClipBounds(Rect clipBounds) {}

	public boolean getClipToOutline() { return false; }

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

	public void setCameraDistance(float distance) {}

	public boolean requestRectangleOnScreen(Rect rectangle, boolean immediate) { return false; }

	public boolean requestRectangleOnScreen(Rect rectangle) { return false; }

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

	public WindowInsets getRootWindowInsets() { return null; }

	public PointerIcon getPointerIcon() {
		return mPointerIcon;
	}

	public void setPointerIcon(PointerIcon pointerIcon) {
		mPointerIcon = pointerIcon;
	}

	public IBinder getApplicationWindowToken() { return null; }

	public int getVerticalFadingEdgeLength() { return 0; }
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

	public void onWindowFocusChanged(boolean hasFocus) {}

	public void setAnimation(Animation animation) {}

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

	public void setAccessibilityPaneTitle(CharSequence paneTitle) {}

	public void setAccessibilityHeading(boolean heading) {}

	public WindowInsets computeSystemWindowInsets(WindowInsets insets, Rect contentInsets) { return insets; }

	public boolean isDuplicateParentStateEnabled() {
		return tlDuplicateParentState;
	}

	public void setBackgroundTintMode(PorterDuff.Mode tintMode) {}

	public void setNextFocusLeftId(int id) {}

	public void setNextFocusRightId(int id) {}

	public void setNextFocusDownId(int id) {}

	public void setNextFocusUpId(int id) {}

	public void setHasTransientState(boolean hasTransientState) {}

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

	public final boolean isKeyboardNavigationCluster() { return false; }

	public void setKeyboardNavigationCluster(boolean isCluster) {}

	public AutofillId getAutofillId() {
		if (mAutofillId == null) {
			// AOSP: created on demand; the value only has to be unique within the process
			mAutofillId = new AutofillId(getAutofillViewId());
		}
		return mAutofillId;
	}

	public AccessibilityDelegate getAccessibilityDelegate() { return null; }

	public void setScrollX(int value) {
		this.scrollX = value;
	}
	public void setScrollY(int value) {
		this.scrollY = value;
	}

	protected boolean dispatchHoverEvent(MotionEvent event) { return false; }

	public ActionMode startActionMode(ActionMode.Callback callback, int type) { return null; }

	public InputConnection onCreateInputConnection(EditorInfo outAttrs) { return null; }

	public ActionMode startActionMode(ActionMode.Callback callback) { return null; }

	public void getFocusedRect(Rect r) {}

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

	public void setVerticalFadingEdgeEnabled(boolean verticalFadingEdgeEnabled) {}

	public void setFadingEdgeLength(int length) {}

	public int getAccessibilityLiveRegion() { return 0; }

	public void setLabelFor(int id) {}

	public ArrayList<View> getFocusables(int direction) { return new ArrayList<View>(); }

	/* teslalinux: View additions used by Compose / AppCompat at first layout */
	public static final int LAYER_TYPE_NONE = 0, LAYER_TYPE_SOFTWARE = 1, LAYER_TYPE_HARDWARE = 2;
	private int tlLayerType = LAYER_TYPE_NONE;
	public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) { return onApplyWindowInsets(insets); }
	public WindowInsets onApplyWindowInsets(WindowInsets insets) { return insets; }
	public int getLayerType() { return tlLayerType; }
	public boolean isOpaque() { return false; }
	public boolean hasOverlappingRendering() { return true; }
	public long getUniqueDrawingId() { return System.identityHashCode(this); }
	public void resetPivot() {}
	public void addOnUnhandledKeyEventListener(OnUnhandledKeyEventListener listener) {}
	public void removeOnUnhandledKeyEventListener(OnUnhandledKeyEventListener listener) {}
	public android.graphics.Rect getClipBounds() { return null; }
	public boolean getClipBounds(android.graphics.Rect outRect) { return false; }
	public int getMeasuredHeightAndState() { return getMeasuredHeight(); }
	public boolean isImportantForAccessibility() { return true; }
	public boolean isImportantForAutofill() { return false; }
	public boolean isImportantForContentCapture() { return false; }
	public int getImportantForContentCapture() { return 0; }
	public void setImportantForContentCapture(int mode) {}
	public CharSequence getAccessibilityClassName() { return View.class.getName(); }
	public CharSequence getAccessibilityPaneTitle() { return null; }
	public CharSequence getStateDescription() { return null; }
	public void setStateDescription(CharSequence stateDescription) {}
	public android.window.OnBackInvokedDispatcher findOnBackInvokedDispatcher() { return null; }
	public boolean hasExplicitFocusable() { return isFocusable(); }
	public boolean isFocusedByDefault() { return false; }
	public void setFocusedByDefault(boolean isFocusedByDefault) {}
	public boolean isAccessibilityHeading() { return false; }
	public boolean isAccessibilityFocused() { return false; }
	public java.util.List<android.graphics.Rect> getSystemGestureExclusionRects() { return new java.util.ArrayList<>(); }
	public void setSystemGestureExclusionRects(java.util.List<android.graphics.Rect> rects) {}
	public java.util.List<android.graphics.Rect> getPreferKeepClearRects() { return new java.util.ArrayList<>(); }
	public void setPreferKeepClearRects(java.util.List<android.graphics.Rect> rects) {}
	public int getScrollIndicators() { return 0; }
	public void setScrollIndicators(int indicators) {}
	public int getLabelFor() { return -1; }
	public void dispatchStartTemporaryDetach() {}
	public void dispatchFinishTemporaryDetach() {}
	public void dispatchConfigurationChanged(android.content.res.Configuration newConfig) {}
	public void drawableHotspotChanged(float x, float y) {}
	public boolean restoreDefaultFocus() { return requestFocus(); }
	public void cancelDragAndDrop() {}
	public void addKeyboardNavigationClusters(java.util.Collection<View> views, int direction) {}
	public void addTouchables(java.util.ArrayList<View> views) {}
	public View keyboardNavigationClusterSearch(View currentCluster, int direction) { return null; }
	public ViewParent getParentForAccessibility() { return getParent(); }
	public void setAllowClickWhenDisabled(boolean clickableWhenDisabled) {}
	public boolean isPivotSet() { return false; }
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

	public boolean isFocusableInTouchMode() {
		return isFocusable();
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
}
