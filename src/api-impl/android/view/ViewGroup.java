package android.view;

import android.R;
import android.animation.LayoutTransition;
import android.annotation.UnsupportedAppUsage;
import android.atl.GskCanvas;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Slog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;

public class ViewGroup extends View implements ViewParent, ViewManager {
	/* clang-format off */
	static final int FLAG_CLIP_CHILDREN        = (1 << 0);
	static final int FLAG_INVALIDATE_REQUIRED  = (1 << 2);
	static final int FLAG_ANIMATION_DONE       = (1 << 4);
	static final int FLAG_OPTIMIZE_INVALIDATE  = (1 << 7);
	static final int FLAG_CLEAR_TRANSFORMATION = (1 << 8);
	@UnsupportedAppUsage
	protected static final int FLAG_USE_CHILD_DRAWING_ORDER = (1 << 10);
	@UnsupportedAppUsage
	protected static final int FLAG_SUPPORT_STATIC_TRANSFORMATIONS = (1 << 11);
	private static final int FLAG_MASK_FOCUSABILITY  = (3 << 17);
	public static final int FOCUS_BEFORE_DESCENDANTS = (1 << 17);
	public static final int FOCUS_AFTER_DESCENDANTS  = (2 << 17);
	public static final int FOCUS_BLOCK_DESCENDANTS  = (3 << 17);
	@UnsupportedAppUsage
	protected static final int FLAG_DISALLOW_INTERCEPT = (1 << 19);
	static final int FLAG_IS_TRANSITION_GROUP          = (1 << 24);
	static final int FLAG_IS_TRANSITION_GROUP_SET      = (1 << 25);
	static final int FLAG_TOUCHSCREEN_BLOCKS_FOCUS     = (1 << 26);
	/* clang-format on */

	public ArrayList<View> children;
	private ArrayList<View> detachedChildren;
	private boolean pendingHideDetachedChildren = false;
	private OnHierarchyChangeListener onHierarchyChangeListener;
	private LayoutTransition transition;
	private ViewGroupOverlay viewGroupOverlay;

	public ViewGroup(Context context) {
		this(context, null);
	}

	public ViewGroup(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public ViewGroup(Context context, AttributeSet attrs, int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public ViewGroup(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr);

		children = new ArrayList<View>();
		detachedChildren = new ArrayList<View>();
		/* AOSP initFromAttributes: android:descendantFocusability (tl_touch_mode) */
		if (attrs != null) {
			android.content.res.TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.ViewGroup, defStyleAttr, defStyleRes);
			if (a.hasValue(com.android.internal.R.styleable.ViewGroup_descendantFocusability))
				setDescendantFocusability(TL_DESCENDANT_FOCUSABILITY_FLAGS[a.getInt(com.android.internal.R.styleable.ViewGroup_descendantFocusability, 0)]);
			tlInitFromAttributes(a); // AOSP initFromAttributes: clipChildren, clipToPadding & co. (layout)
			a.recycle();
		}
		tlInitViewGroup();
	}

	public void addView(View child) {
		addView(child, -1);
	}

	public void addView(View child, int index) {
		LayoutParams params = child.getLayoutParams();
		if (params == null) {
			params = generateDefaultLayoutParams();
		}
		addView(child, index, params);
	}

	public void addView(View child, LayoutParams params) {
		addView(child, -1, params);
	}

	public void addView(View child, int width, int height) {
		final LayoutParams params = generateDefaultLayoutParams();
		params.width = width;
		params.height = height;
		addView(child, params);
	}

	public void addView(View child, int index, LayoutParams params) {
		addViewInternal(child, index, params, true);
		requestLayout();
	}

	public void onViewAdded(View view) {}
	public void onViewRemoved(View view) {}

	protected void addViewInternal(View child, int index, LayoutParams params, boolean callOnViewAdded) {
		if (child.parent == this)
			return;
		if (!checkLayoutParams(params))
			params = generateLayoutParams(params);

		tlBeforeChildAdded(child);
		child.parent = this;
		child.setLayoutParams(params);
		if (index < 0)
			index = children.size();
		children.add(index, child);
		// the widget goes in at the child index (the drawing order while no child has a Z); with a
		// Z anywhere the widgets are re-stacked in AOSP's Z order (tlRestackChildren)
		native_addView(widget, child.widget, index, params);
		if (tlAnyChildZ())
			tlRestackChildren();
		tlChildAdded(child);
		if (tlChildrenDrawingOrder)
			tlScheduleDrawingOrder();
		if (callOnViewAdded) {
			onViewAdded(child);
			if (onHierarchyChangeListener != null)
				onHierarchyChangeListener.onChildViewAdded(this, child);
		}
	}

	/* We never call this ourselves */
	@Override
	/**
	 * AOSP ViewGroup.dispatchTouchEvent: the children under the point get the event first (the native
	 * side picks the widget and propagates through the Java views in between, in their own
	 * coordinates); when none takes it, the group handles it itself (super.dispatchTouchEvent).
	 */
	public boolean dispatchTouchEvent(MotionEvent event) {
		if (System.getenv("ATL_DEBUG_TOUCH") != null)
			android.util.Log.i("ViewGroup", "[touch] dispatchTouchEvent " + getClass().getName() + " action=" + event.getActionMasked() + " at " + event.getX() + "," + event.getY() + " size " + getWidth() + "x" + getHeight());
		boolean tlHandled = native_dispatchTouchEvent(widget, event, event.getX(), event.getY());
		if (!tlHandled)
			tlHandled = super.dispatchTouchEvent(event);
		if (System.getenv("ATL_DEBUG_TOUCH") != null)
			android.util.Log.i("ViewGroup", "[touch] dispatchTouchEvent " + getClass().getName() + " action=" + event.getActionMasked() + " -> " + tlHandled);
		return tlHandled;
	}

	protected boolean addViewInLayout(View child, int index, LayoutParams params) {
		addViewInternal(child, index, params, true);
		return true;
	}

	protected boolean addViewInLayout(View child, int index, LayoutParams params, boolean preventRequestLayout) {
		addViewInternal(child, index, params, true);
		if (!preventRequestLayout)
			requestLayout();
		return true;
	}

	// This internal method is used to share code between removeView and removeViewInLayout.
	// Reusing removeView in removeViewInLayout is not possible, because e.g.
	// ViewPager overrides removeView to call removeViewInLayout
	protected void removeViewInternal(View child) {
		if (child == null || child.parent != this)
			return;
		/* AOSP removeViewInternal: a removed child takes its focus with it */
		boolean clearChildFocus = false;
		if (child == tlFocusedChild) {
			child.tlUnFocus(null);
			clearChildFocus = true;
		}
		if (clearChildFocus) {
			clearChildFocus(child);
			if (!tlRootViewRequestFocus() && isAttachedToWindow())
				getViewTreeObserver().dispatchOnGlobalFocusChange(child, null);
		}
		child.parent = null;
		children.remove(child);
		tlRemoveChildWidget(child);
		onViewRemoved(child);
		if (onHierarchyChangeListener != null) {
			onHierarchyChangeListener.onChildViewRemoved(this, child);
		}
	}

	public void removeView(View child) {
		removeViewInternal(child);
		requestLayout();
	}

	public void removeViewInLayout(View child) {
		removeViewInternal(child);
	}

	public void removeViewAt(int index) {
		removeView(children.get(index));
	}

	public void removeAllViews() {
		/* AOSP removeAllViewsInLayout: the focused child loses its focus first */
		View focused = tlFocusedChild;
		if (focused != null) {
			clearChildFocus(focused);
			focused.tlUnFocus(null);
			if (!tlRootViewRequestFocus() && isAttachedToWindow())
				getViewTreeObserver().dispatchOnGlobalFocusChange(focused, null);
		}
		for (Iterator<View> it = children.iterator(); it.hasNext();) {
			View child = it.next();
			child.parent = null;
			it.remove();
			tlRemoveChildWidget(child);
			onViewRemoved(child);
			if (onHierarchyChangeListener != null) {
				onHierarchyChangeListener.onChildViewRemoved(this, child);
			}
		}
		requestLayout();
	}

	public void detachViewFromParent(int index) {
		detachViewFromParent(getChildAt(index));
	}

	public void attachViewToParent(View view, int index, LayoutParams params) {
		if (!detachedChildren.remove(view)) {
			addViewInternal(view, index, params, false);
		}
		if (!checkLayoutParams(params))
			params = generateLayoutParams(params);

		view.parent = this;
		view.setLayoutParams(params);
		if (index < 0)
			index = children.size();
		children.add(index, view);
		view.native_setVisibility(view.widget, view.getVisibility(), view.getAlpha(), view.isEnabled());
	}

	protected void removeDetachedView(View child, boolean animate) {
		if (!detachedChildren.remove(child))
			return;
		if (child == tlFocusedChild)
			child.clearFocus(); /* AOSP removeDetachedView */
		child.parent = null;
		tlRemoveChildWidget(child);
		if (onHierarchyChangeListener != null) {
			onHierarchyChangeListener.onChildViewRemoved(this, child);
		}
	}

	@Override
	protected void dispatchVisibilityChanged(View changedView, int visibility) {
		if (children == null) // happens if this gets called during super constructor
			return;

		for (View child : children) {
			child.dispatchVisibilityChanged(changedView, visibility);
		}
	}

	protected native void native_addView(long widget, long child, int index, LayoutParams params);
	protected native void native_removeView(long widget, long child);
	@Override
	protected native void native_drawChildren(long widget, long snapshot);
	protected native void native_drawChild(long widget, long child, long snapshot);
	@Override
	protected void native_drawContent(long widget, long snapshot) {}

	public View getChildAt(int index) {
		try {
			return children.get(index);
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
	}

	public int indexOfChild(View child) {
		return children.indexOf(child);
	}

	public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
		view.setLayoutParams(params);
		view.requestLayout();
	}

	public LayoutParams generateLayoutParams(AttributeSet attrs) {
		return new LayoutParams(getContext(), attrs);
	}
	protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
		return p;
	}

	/** AOSP: moves the child to the end of the child list (drawn last among equal Z), then lays out again */
	public void bringChildToFront(View child) {
		final int index = children.indexOf(child);
		if (index >= 0 && index != children.size() - 1) {
			children.remove(index);
			children.add(child);
			tlRestackChildren();
			requestLayout();
			invalidate();
		}
	}

	/**
	 * Returns the number of children in the group.
	 *
	 * @return a positive integer representing the number of children in
	 *         the group
	 */
	public int getChildCount() {
		return children.size();
	}

	// (layout) setMotionEventSplittingEnabled: see ViewGroup+layout.frag

	public void setOnHierarchyChangeListener(OnHierarchyChangeListener listener) {
		this.onHierarchyChangeListener = listener;
	}

	// (layout) checkLayoutParams: see ViewGroup+layout.frag

	/** AOSP: the transition set with setLayoutTransition (or android:animateLayoutChanges), null by default */
	public LayoutTransition getLayoutTransition() {
		return transition;
	}

	public static int getChildMeasureSpec(int spec, int padding, int childDimension) {
		int specMode = MeasureSpec.getMode(spec);
		int specSize = MeasureSpec.getSize(spec);
		int size = Math.max(0, specSize - padding);
		int resultSize = 0;
		int resultMode = 0;
		switch (specMode) {
			// Parent has imposed an exact size on us
			case MeasureSpec.EXACTLY:
				if (childDimension >= 0) {
					resultSize = childDimension;
					resultMode = MeasureSpec.EXACTLY;
				} else if (childDimension == LayoutParams.MATCH_PARENT) {
					// Child wants to be our size. So be it.
					resultSize = size;
					resultMode = MeasureSpec.EXACTLY;
				} else if (childDimension == LayoutParams.WRAP_CONTENT) {
					// Child wants to determine its own size. It can't be
					// bigger than us.
					resultSize = size;
					resultMode = MeasureSpec.AT_MOST;
				}
				break;
			// Parent has imposed a maximum size on us
			case MeasureSpec.AT_MOST:
				if (childDimension >= 0) {
					// Child wants a specific size... so be it
					resultSize = childDimension;
					resultMode = MeasureSpec.EXACTLY;
				} else if (childDimension == LayoutParams.MATCH_PARENT) {
					// Child wants to be our size, but our size is not fixed.
					// Constrain child to not be bigger than us.
					resultSize = size;
					resultMode = MeasureSpec.AT_MOST;
				} else if (childDimension == LayoutParams.WRAP_CONTENT) {
					// Child wants to determine its own size. It can't be
					// bigger than us.
					resultSize = size;
					resultMode = MeasureSpec.AT_MOST;
				}
				break;
			// Parent asked to see how big we want to be
			case MeasureSpec.UNSPECIFIED:
				if (childDimension >= 0) {
					// Child wants a specific size... let them have it
					resultSize = childDimension;
					resultMode = MeasureSpec.EXACTLY;
				} else if (childDimension == LayoutParams.MATCH_PARENT) {
					// Child wants to be our size... find out how big it should
					// be
					resultSize = 0; // View.sUseZeroUnspecifiedMeasureSpec ? 0 : size;
					resultMode = MeasureSpec.UNSPECIFIED;
				} else if (childDimension == LayoutParams.WRAP_CONTENT) {
					// Child wants to determine its own size.... find out how
					// big it should be
					resultSize = 0; // View.sUseZeroUnspecifiedMeasureSpec ? 0 : size;
					resultMode = MeasureSpec.UNSPECIFIED;
				}
				break;
		}
		// noinspection ResourceType
		return MeasureSpec.makeMeasureSpec(resultSize, resultMode);
	}

	protected void measureChildWithMargins(View child,
	                                       int parentWidthMeasureSpec, int widthUsed,
	                                       int parentHeightMeasureSpec, int heightUsed) {
		final MarginLayoutParams lp = (MarginLayoutParams)child.getLayoutParams();
		final int childWidthMeasureSpec = getChildMeasureSpec(parentWidthMeasureSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + widthUsed, lp.width);
		final int childHeightMeasureSpec = getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed, lp.height);
		child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
	}

	protected void measureChild(View child, int parentWidthMeasureSpec,
	                            int parentHeightMeasureSpec) {
		final LayoutParams lp = child.getLayoutParams();
		final int childWidthMeasureSpec = getChildMeasureSpec(parentWidthMeasureSpec, paddingLeft + paddingRight, lp.width);
		final int childHeightMeasureSpec = getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom, lp.height);
		child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
	}

	protected void measureChildren(int widthMeasureSpec, int heightMeasureSpec) {
		for (View child : children) {
			measureChild(child, widthMeasureSpec, heightMeasureSpec);
		}
	}

	public void setAddStatesFromChildren(boolean addsStates) {
		if (tlAddStatesFromChildren != addsStates) {
			tlAddStatesFromChildren = addsStates;
			refreshDrawableState();
		}
	}

	/** AOSP: the direct child that is or contains the window's focused view (mFocused, tl_focus_model). */
	public View getFocusedChild() {
		return tlFocusedChild;
	}

	/** AOSP: how this group shares focus with its descendants (tl_touch_mode). */
	public int getDescendantFocusability() { return tlDescendantFocusability; }

	// (layout) startViewTransition: see ViewGroup+layout.frag

	protected LayoutParams generateDefaultLayoutParams() {
		return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
	}

	// (layout) focusableViewAvailable: see ViewGroup+layout.frag

	@Override
	public void setGravity(int gravity) {
		super.setGravity(gravity);
		// update children as necessary
		for (View child : children) {
			LayoutParams params = child.getLayoutParams();
			if (params.gravity == -1)
				child.setLayoutParams(params);
		}
	}

	// (layout) setChildrenDrawingOrderEnabled: see ViewGroup+layout.frag

	public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
		for (ViewGroup iter = this; iter != null; iter = iter.parent instanceof ViewGroup ? (ViewGroup)iter.parent : null)
			iter.disallowIntercept = disallowIntercept;
	}

	// (layout) isChildrenDrawingOrderEnabled: see ViewGroup+layout.frag

	@Override
	public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
		return false;
	}

	@Override
	public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
		return false;
	}

	@Override
	public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
		return false;
	}

	// (layout) setClipToPadding: see ViewGroup+layout.frag

	/**
	 * AOSP findViewById / ViewGroup.findViewTraversal: this group, then its children in order.
	 * teslalinux: ATL built a verbose log line on every call (resource name lookup, toString),
	 * which cost time on each lookup and called getResources() of view subclasses whose
	 * constructors look up children before their own fields exist (Waze's destination cells).
	 */
	public View findViewById(int id) {
		if (id == NO_ID)
			return null;
		if (this.id == id)
			return this;
		for (View child : children) {
			View result = child.findViewById(id);
			if (result != null)
				return result;
		}
		return null;
	}

	public void detachViewFromParent(View view) {
		children.remove(view);
		view.parent = null;
		detachedChildren.add(view);
		if (!pendingHideDetachedChildren) {
			pendingHideDetachedChildren = true;
			post(new Runnable() {
				@Override
				public void run() {
					pendingHideDetachedChildren = false;
					for (View child : detachedChildren)
						child.native_setVisibility(child.widget, GONE, 0, false);
				}
			});
		}
	}

	// (layout) setTouchscreenBlocksFocus: see ViewGroup+layout.frag

	// (layout) setClipChildren: see ViewGroup+layout.frag

	/** AOSP: children that are clickable on their own do not show their parent's press */
	@Override
	protected void dispatchSetPressed(boolean pressed) {
		for (int i = 0; i < getChildCount(); i++) {
			final View child = getChildAt(i);
			if (!pressed || (!child.isClickable() && !child.isLongClickable()))
				child.setPressed(pressed);
		}
	}

	@Override
	public View findViewWithTag(Object tag) {
		if (Objects.equals(tag, getTag()))
			return this;
		for (View child : children) {
			View result = child.findViewWithTag(tag);
			if (result != null)
				return result;
		}
		return null;
	}

	public void setLayoutTransition(LayoutTransition transition) {
		this.transition = transition;
	}

	private static int sDrawChildTrace;
	public boolean drawChild(Canvas canvas, View child, long drawingTime) {
		if (System.getenv("ATL_DEBUG_SURFACE") != null && child instanceof SurfaceView && (sDrawChildTrace++ % 100) == 0)
			android.util.Log.i("ViewGroup", "[surface] drawChild #" + sDrawChildTrace + " " + getClass().getName() + " -> " + child.getClass().getName() + " canvas=" + canvas.getClass().getName());
		if (canvas instanceof GskCanvas)
			native_drawChild(widget, child.widget, ((GskCanvas)canvas).snapshot);
		return false;
	}

	// (layout) cleanupLayoutState: see ViewGroup+layout.frag

	// (layout) shouldDelayChildPressedState: see ViewGroup+layout.frag

	public void removeAllViewsInLayout() {
		for (int i = getChildCount() - 1; i >= 0; i--) {
			removeViewInLayout(getChildAt(i));
		}
	}

	// (layout) offsetChildrenTopAndBottom: see ViewGroup+layout.frag

	// (layout) offsetDescendantRectToMyCoords: see ViewGroup+layout.frag

	// (layout) getClipToPadding: see ViewGroup+layout.frag

	public boolean isTransitionGroup() {
		if ((mGroupFlags & FLAG_IS_TRANSITION_GROUP_SET) != 0) {
			return ((mGroupFlags & FLAG_IS_TRANSITION_GROUP) != 0);
		} else {
			// AOSP: a group is a transition group if it has a background or a transition name
			return getBackground() != null || getTransitionName() != null;
		}
	}

	public static class LayoutParams {
		public static final int FILL_PARENT = -1;
		public static final int MATCH_PARENT = -1;
		public static final int WRAP_CONTENT = -2;

		public int width = 0;
		public int height = 0;
		public float weight = 0;
		public int gravity = -1;

		/** AOSP: the no-argument params of subclasses, which set their own fields */
		public LayoutParams() {
		}

		public LayoutParams(int width, int height) {
			this.width = width;
			this.height = height;
		}

		public LayoutParams(int width, int height, float weight) {
			this.width = width;
			this.height = height;
			this.weight = weight;
		}

		public LayoutParams(Context context, AttributeSet attrs) {
			TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ViewGroup_Layout);
			setBaseAttributes(a, R.styleable.ViewGroup_Layout_layout_width, R.styleable.ViewGroup_Layout_layout_height);
			a.recycle();
			a = context.obtainStyledAttributes(attrs, new int[] {android.R.attr.layout_gravity});
			gravity = a.getInt(0, -1);
			a.recycle();
		}

		protected void setBaseAttributes(TypedArray a, int widthAttr, int heightAttr) {
			width = a.getLayoutDimension(widthAttr, "layout_width");
			height = a.getLayoutDimension(heightAttr, "layout_height");
		}

		public void resolveLayoutDirection(int layoutDirection) {}

		/**
		 * Used to animate layouts.
		 */
		//public LayoutAnimationController.AnimationParameters layoutAnimationParameters;
	
		/** AOSP: used by LayoutAnimationController to stagger child animations. */
		public android.view.animation.LayoutAnimationController.AnimationParameters layoutAnimationParameters;
}

	public static class MarginLayoutParams extends ViewGroup.LayoutParams {
		public int leftMargin;
		public int topMargin;
		public int rightMargin;
		public int bottomMargin;

		public MarginLayoutParams() {
			super();
		}

		public MarginLayoutParams(LayoutParams params) {
			super();
			width = params.width;
			height = params.height;
			// AOSP: no margin from the source; left and right count as undefined (RTL compatibility)
			mMarginFlags |= LEFT_MARGIN_UNDEFINED_MASK;
			mMarginFlags |= RIGHT_MARGIN_UNDEFINED_MASK;
		}

		public MarginLayoutParams(int width, int height) {
			super(width, height);
		}

		public MarginLayoutParams(int width, int height, float weight) {
			super(width, height, weight);
		}

		public MarginLayoutParams(Context context, AttributeSet attributeSet) {
			super(context, attributeSet);
			TypedArray a = context.obtainStyledAttributes(attributeSet, com.android.internal.R.styleable.ViewGroup_MarginLayout);

			// AOSP MarginLayoutParams(Context, AttributeSet): margin, marginHorizontal and marginVertical
			// win over the single sides; a side that is not given stays undefined (RTL compatibility)
			int margin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_margin, -1);
			if (margin >= 0) {
				leftMargin = margin;
				topMargin = margin;
				rightMargin = margin;
				bottomMargin = margin;
			} else {
				int horizontalMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginHorizontal, -1);
				int verticalMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginVertical, -1);
				if (horizontalMargin >= 0) {
					leftMargin = horizontalMargin;
					rightMargin = horizontalMargin;
				} else {
					leftMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginLeft, UNDEFINED_MARGIN);
					if (leftMargin == UNDEFINED_MARGIN) {
						mMarginFlags |= LEFT_MARGIN_UNDEFINED_MASK;
						leftMargin = DEFAULT_MARGIN_RESOLVED;
					}
					rightMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginRight, UNDEFINED_MARGIN);
					if (rightMargin == UNDEFINED_MARGIN) {
						mMarginFlags |= RIGHT_MARGIN_UNDEFINED_MASK;
						rightMargin = DEFAULT_MARGIN_RESOLVED;
					}
				}
				startMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginStart, DEFAULT_MARGIN_RELATIVE);
				endMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginEnd, DEFAULT_MARGIN_RELATIVE);
				if (verticalMargin >= 0) {
					topMargin = verticalMargin;
					bottomMargin = verticalMargin;
				} else {
					topMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginTop, DEFAULT_MARGIN_RESOLVED);
					bottomMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginBottom, DEFAULT_MARGIN_RESOLVED);
				}
				if (isMarginRelative()) {
					mMarginFlags |= NEED_RESOLUTION_MASK;
				}
			}
			// AOSP: apps without android:supportsRtl (Waze) or older than API 17 keep left/right margins
			// over start/end ones; start/end then only fill a side left undefined
			final android.content.pm.ApplicationInfo info = context.getApplicationInfo();
			if (info == null || info.targetSdkVersion < 17 || !info.hasRtlSupport()) {
				mMarginFlags |= RTL_COMPATIBILITY_MODE_MASK;
			}
			// Layout direction is LTR by default
			mMarginFlags |= View.LAYOUT_DIRECTION_LTR;
			// ATL lays out with these fields directly: resolve now for the default direction (AOSP
			// does it when the params are set on a view, which resolveLayoutDirection repeats)
			if ((mMarginFlags & NEED_RESOLUTION_MASK) == NEED_RESOLUTION_MASK)
				doResolveMargins();
			a.recycle();
		}

		public MarginLayoutParams(ViewGroup.MarginLayoutParams source) {
			super();
			width = source.width;
			height = source.height;
			leftMargin = source.leftMargin;
			topMargin = source.topMargin;
			rightMargin = source.rightMargin;
			bottomMargin = source.bottomMargin;
			// AOSP: the relative margins and their resolution state come along
			startMargin = source.startMargin;
			endMargin = source.endMargin;
			mMarginFlags = source.mMarginFlags;
		}

		/* AOSP MarginLayoutParams relative margins (frameworks/base ViewGroup.java, Apache 2.0) */
		/** @hide the value of a start/end margin that was not set */
		public static final int DEFAULT_MARGIN_RELATIVE = Integer.MIN_VALUE;
		private static final int DEFAULT_MARGIN_RESOLVED = 0;
		private static final int UNDEFINED_MARGIN = DEFAULT_MARGIN_RELATIVE;
		private static final int LAYOUT_DIRECTION_MASK = 0x00000003;
		private static final int LEFT_MARGIN_UNDEFINED_MASK = 0x00000004;
		private static final int RIGHT_MARGIN_UNDEFINED_MASK = 0x00000008;
		private static final int RTL_COMPATIBILITY_MODE_MASK = 0x00000010;
		private static final int NEED_RESOLUTION_MASK = 0x00000020;
		private int startMargin = DEFAULT_MARGIN_RELATIVE;
		private int endMargin = DEFAULT_MARGIN_RELATIVE;
		/** layout direction (LTR/RTL), undefined-side and resolution flags */
		byte mMarginFlags;

		public void setMargins(int left, int top, int right, int bottom) {
			leftMargin = left;
			topMargin = top;
			rightMargin = right;
			bottomMargin = bottom;
			mMarginFlags &= ~LEFT_MARGIN_UNDEFINED_MASK;
			mMarginFlags &= ~RIGHT_MARGIN_UNDEFINED_MASK;
			if (isMarginRelative()) {
				mMarginFlags |= NEED_RESOLUTION_MASK;
				doResolveMargins(); // ATL reads the fields without a resolution pass
			} else {
				mMarginFlags &= ~NEED_RESOLUTION_MASK;
			}
		}

		/** AOSP: start/top/end/bottom margins, resolved for the layout direction */
		public void setMarginsRelative(int start, int top, int end, int bottom) {
			startMargin = start;
			topMargin = top;
			endMargin = end;
			bottomMargin = bottom;
			mMarginFlags |= NEED_RESOLUTION_MASK;
			doResolveMargins();
		}

		public void setMarginStart(int start) {
			startMargin = start;
			mMarginFlags |= NEED_RESOLUTION_MASK;
			doResolveMargins();
		}

		public int getMarginStart() {
			if (startMargin != DEFAULT_MARGIN_RELATIVE)
				return startMargin;
			if ((mMarginFlags & NEED_RESOLUTION_MASK) == NEED_RESOLUTION_MASK)
				doResolveMargins();
			switch (mMarginFlags & LAYOUT_DIRECTION_MASK) {
				case View.LAYOUT_DIRECTION_RTL:
					return rightMargin;
				case View.LAYOUT_DIRECTION_LTR:
				default:
					return leftMargin;
			}
		}

		public void setMarginEnd(int end) {
			endMargin = end;
			mMarginFlags |= NEED_RESOLUTION_MASK;
			doResolveMargins();
		}

		public int getMarginEnd() {
			if (endMargin != DEFAULT_MARGIN_RELATIVE)
				return endMargin;
			if ((mMarginFlags & NEED_RESOLUTION_MASK) == NEED_RESOLUTION_MASK)
				doResolveMargins();
			switch (mMarginFlags & LAYOUT_DIRECTION_MASK) {
				case View.LAYOUT_DIRECTION_RTL:
					return leftMargin;
				case View.LAYOUT_DIRECTION_LTR:
				default:
					return rightMargin;
			}
		}

		/** AOSP: whether a start or an end margin was set */
		public boolean isMarginRelative() {
			return (startMargin != DEFAULT_MARGIN_RELATIVE || endMargin != DEFAULT_MARGIN_RELATIVE);
		}

		/** AOSP: the resolved layout direction of these params (LTR/RTL), used by start/end margins. */
		public void setLayoutDirection(int layoutDirection) {
			if (layoutDirection != View.LAYOUT_DIRECTION_LTR && layoutDirection != View.LAYOUT_DIRECTION_RTL)
				return;
			if (layoutDirection != (mMarginFlags & LAYOUT_DIRECTION_MASK)) {
				mMarginFlags &= ~LAYOUT_DIRECTION_MASK;
				mMarginFlags |= (layoutDirection & LAYOUT_DIRECTION_MASK);
				if (isMarginRelative())
					mMarginFlags |= NEED_RESOLUTION_MASK;
				else
					mMarginFlags &= ~NEED_RESOLUTION_MASK;
			}
		}

		public int getLayoutDirection() {
			return (mMarginFlags & LAYOUT_DIRECTION_MASK);
		}

		/** AOSP: start/end margins become left/right ones for the view's layout direction */
		public void resolveLayoutDirection(int layoutDirection) {
			setLayoutDirection(layoutDirection);
			if (!isMarginRelative() || (mMarginFlags & NEED_RESOLUTION_MASK) != NEED_RESOLUTION_MASK)
				return;
			doResolveMargins();
		}

		private void doResolveMargins() {
			if ((mMarginFlags & RTL_COMPATIBILITY_MODE_MASK) == RTL_COMPATIBILITY_MODE_MASK) {
				// if left or right margins are not defined and if we have some start or right
				// margin defined then use those
				if ((mMarginFlags & LEFT_MARGIN_UNDEFINED_MASK) == LEFT_MARGIN_UNDEFINED_MASK && startMargin > DEFAULT_MARGIN_RELATIVE)
					leftMargin = startMargin;
				if ((mMarginFlags & RIGHT_MARGIN_UNDEFINED_MASK) == RIGHT_MARGIN_UNDEFINED_MASK && endMargin > DEFAULT_MARGIN_RELATIVE)
					rightMargin = endMargin;
			} else {
				// relative margins override what has been defined for left and right margins
				switch (mMarginFlags & LAYOUT_DIRECTION_MASK) {
					case View.LAYOUT_DIRECTION_RTL:
						leftMargin = (endMargin > DEFAULT_MARGIN_RELATIVE) ? endMargin : leftMargin;
						rightMargin = (startMargin > DEFAULT_MARGIN_RELATIVE) ? startMargin : rightMargin;
						break;
					case View.LAYOUT_DIRECTION_LTR:
					default:
						leftMargin = (startMargin > DEFAULT_MARGIN_RELATIVE) ? startMargin : leftMargin;
						rightMargin = (endMargin > DEFAULT_MARGIN_RELATIVE) ? endMargin : rightMargin;
						break;
				}
			}
			mMarginFlags &= ~NEED_RESOLUTION_MASK;
		}

		/** @hide AOSP */
		public boolean isLayoutRtl() {
			return ((mMarginFlags & LAYOUT_DIRECTION_MASK) == View.LAYOUT_DIRECTION_RTL);
		}
	}

	public interface OnHierarchyChangeListener {
		public void onChildViewAdded(View parent, View child);
		public void onChildViewRemoved(View parent, View child);
	}

	@Override
	public void onNestedScrollAccepted(View child, View target, int nestedScrollAxes) {
		mNestedScrollAxes = nestedScrollAxes;
	}

	@Override
	public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
		// Re-dispatch up the tree by default
		dispatchNestedPreScroll(dx, dy, consumed, null);
	}

	@Override
	public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
		// Re-dispatch up the tree by default
		dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, null);
	}

	@Override
	public void onStopNestedScroll(View target) {
		// Stop any recursive nested scrolling.
		stopNestedScroll();
		mNestedScrollAxes = 0;
	}

	/**
	 * AOSP: a descendant (focused) in this child's subtree took focus; this group drops its own
	 * focus and its previous focused child's, and passes the request up (tl_focus_model).
	 */
	public void requestChildFocus(View child, View focused) {
		if (getDescendantFocusability() == FOCUS_BLOCK_DESCENDANTS)
			return;
		super.tlUnFocus(focused); /* this group itself was focused */
		if (tlFocusedChild != child) {
			if (tlFocusedChild != null)
				tlFocusedChild.tlUnFocus(focused);
			tlFocusedChild = child;
		}
		if (getParent() instanceof ViewGroup)
			((ViewGroup) getParent()).requestChildFocus(this, focused);
	}

	// (layout) getClipChildren: see ViewGroup+layout.frag

	public native boolean native_dispatchTouchEvent(long widget, MotionEvent event, double x, double y);

	@Override
	/** AOSP: a descendant's drawing changed; propagate up so every ancestor that caches drawings knows. */
	public void onDescendantInvalidated(View child, View target) {
		if (System.getenv("ATL_DEBUG_SURFACE") != null && target instanceof SurfaceView && (sDescendantTrace++ % 100) == 0)
			android.util.Log.i("ViewGroup", "[surface] onDescendantInvalidated #" + sDescendantTrace + " " + getClass().getName() + " parent=" + (parent == null ? "null" : parent.getClass().getName()));
		final ViewParent p = parent;
		if (p != null) {
			p.onDescendantInvalidated(this, target);
		}
	}

	// (layout) getTouchscreenBlocksFocus: see ViewGroup+layout.frag

	public ViewGroupOverlay getOverlay() {
		if (viewGroupOverlay == null)
			viewGroupOverlay = new ViewGroupOverlay();
		return viewGroupOverlay;
	}

	// (layout) scheduleLayoutAnimation: see ViewGroup+layout.frag

	/* ---- teslalinux: ViewGroup members from AOSP that ATL lacked ---- */
	/** AOSP group flags (FLAG_IS_TRANSITION_GROUP* are already defined by ATL). */
	private int mGroupFlags;

	public static final int PERSISTENT_NO_CACHE = 0x0;
	public static final int PERSISTENT_ANIMATION_CACHE = 0x1;
	public static final int PERSISTENT_SCROLLING_CACHE = 0x2;
	public static final int PERSISTENT_ALL_CACHES = 0x3;
	private int mPersistentDrawingCache = PERSISTENT_SCROLLING_CACHE;

	public static final int LAYOUT_MODE_UNDEFINED = -1;
	public static final int LAYOUT_MODE_CLIP_BOUNDS = 0;
	public static final int LAYOUT_MODE_OPTICAL_BOUNDS = 1;
	public static final int LAYOUT_MODE_DEFAULT = LAYOUT_MODE_CLIP_BOUNDS;
	private int mLayoutMode = LAYOUT_MODE_UNDEFINED;

	private boolean mSuppressLayout;
	private boolean mLayoutCalledWhileSuppressed;
	private int mNestedScrollAxes;

	/** Deprecated drawing-cache policy; Android itself ignores it since API 28 (hardware rendering), only the value is kept. */
	public void setPersistentDrawingCache(int drawingCacheToKeep) {
		mPersistentDrawingCache = drawingCacheToKeep & PERSISTENT_ALL_CACHES;
	}

	public int getPersistentDrawingCache() {
		return mPersistentDrawingCache;
	}

	public void setLayoutMode(int layoutMode) {
		if (mLayoutMode != layoutMode) {
			mLayoutMode = layoutMode;
			requestLayout();
		}
	}

	public int getLayoutMode() {
		if (mLayoutMode == LAYOUT_MODE_UNDEFINED) {
			mLayoutMode = (getParent() instanceof ViewGroup) ? ((ViewGroup) getParent()).getLayoutMode() : LAYOUT_MODE_DEFAULT;
		}
		return mLayoutMode;
	}

	/** Tells this ViewGroup to suppress all layout() calls until layout suppression is disabled with a later call. */
	public void suppressLayout(boolean suppress) {
		mSuppressLayout = suppress;
		if (!suppress) {
			if (mLayoutCalledWhileSuppressed) {
				requestLayout();
				mLayoutCalledWhileSuppressed = false;
			}
		}
	}

	public boolean isLayoutSuppressed() {
		return mSuppressLayout;
	}

	public void setTransitionGroup(boolean isTransitionGroup) {
		mGroupFlags |= FLAG_IS_TRANSITION_GROUP_SET;
		if (isTransitionGroup) {
			mGroupFlags |= FLAG_IS_TRANSITION_GROUP;
		} else {
			mGroupFlags &= ~FLAG_IS_TRANSITION_GROUP;
		}
	}

	public int getNestedScrollAxes() {
		return mNestedScrollAxes;
	}

	/** Returns the index of the child to draw for this iteration; the default order is the child index. */
	protected int getChildDrawingOrder(int childCount, int drawingPosition) {
		return drawingPosition;
	}

	public final int getChildDrawingOrder(int drawingPosition) {
		return getChildDrawingOrder(getChildCount(), drawingPosition);
	}

	/** Removes a range of views during layout (no requestLayout / invalidate, as in AOSP). */
	public void removeViewsInLayout(int start, int count) {
		removeViews(start, count);
	}

	public void removeViews(int start, int count) {
		if (start < 0 || count < 0 || start + count > getChildCount()) {
			throw new IndexOutOfBoundsException();
		}
		java.util.ArrayList<View> toRemove = new java.util.ArrayList<View>();
		for (int i = start; i < start + count; i++) {
			toRemove.add(getChildAt(i));
		}
		for (View v : toRemove) {
			removeViewInternal(v);
		}
	}

	/** Offset a rectangle that is in our coordinate space into an ancestor descendant's coordinate space (AOSP offsetRectBetweenParentAndChild, inverse direction). */
	public final void offsetRectIntoDescendantCoords(View descendant, android.graphics.Rect rect) {
		View v = descendant;
		while (v != this) {
			if (v == null) {
				throw new IllegalArgumentException("parameter must be a descendant of this view");
			}
			rect.offset(v.getScrollX() - v.getLeft(), v.getScrollY() - v.getTop());
			v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
		}
	}

	/** Called when a child wants a rectangle on screen; a plain ViewGroup cannot scroll, so false (AOSP default). */
	public boolean requestChildRectangleOnScreen(View child, android.graphics.Rect rectangle, boolean immediate) {
		return false;
	}

	/** Look for a descendant to call requestFocus on, in the direction of traversal (AOSP). */
	protected boolean onRequestFocusInDescendants(int direction, android.graphics.Rect previouslyFocusedRect) {
		int index;
		int increment;
		int end;
		int count = getChildCount();
		if ((direction & FOCUS_FORWARD) != 0) {
			index = 0;
			increment = 1;
			end = count;
		} else {
			index = count - 1;
			increment = -1;
			end = -1;
		}
		for (int i = index; i != end; i += increment) {
			View child = getChildAt(i);
			if (child.getVisibility() == VISIBLE) {
				if (child.requestFocus(direction, previouslyFocusedRect)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Find the nearest view in the specified direction that wants to take focus (AOSP: delegated to the parent chain). */
	public View focusSearch(View focused, int direction) {
		if (getParent() instanceof ViewGroup) {
			return ((ViewGroup) getParent()).focusSearch(focused, direction);
		}
		return null;
	}

	@Override
	public boolean dispatchKeyEventPreIme(KeyEvent event) {
		View focused = getFocusedChild();
		if (focused != null) {
			return focused.dispatchKeyEventPreIme(event);
		}
		return super.dispatchKeyEventPreIme(event);
	}

	/** AOSP: a child became dirty; the dirty region is handled by the GTK widget tree here, the parent chain just propagates. */
	public ViewParent invalidateChildInParent(final int[] location, final android.graphics.Rect dirty) {
		invalidate();
		return getParent();
	}

	/** Adds the focusable views of this group and its children in traversal order (AOSP, honouring descendant focusability). */
	@Override
	public void addFocusables(java.util.ArrayList<View> views, int direction, int focusableMode) {
		final int focusableCount = views.size();
		final int descendantFocusability = getDescendantFocusability();
		final boolean blockFocusForTouchscreen = false;
		if (descendantFocusability == FOCUS_BLOCK_DESCENDANTS || blockFocusForTouchscreen) {
			if (isFocusable()) {
				super.addFocusables(views, direction, focusableMode);
			}
			return;
		}
		if (descendantFocusability == FOCUS_BEFORE_DESCENDANTS) {
			super.addFocusables(views, direction, focusableMode);
		}
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == View.VISIBLE) {
				child.addFocusables(views, direction, focusableMode);
			}
		}
		// When set to FOCUS_AFTER_DESCENDANTS, we only add ourselves if there are no focusable descendants.
		if (descendantFocusability == FOCUS_AFTER_DESCENDANTS && focusableCount == views.size()) {
			super.addFocusables(views, direction, focusableMode);
		}
	}

	/** AOSP ViewGroup.invalidateChild (hardware-accelerated path): the dirty child is reported upwards. */
	public final void invalidateChild(View child, final android.graphics.Rect dirty) {
		onDescendantInvalidated(child, child);
	}

	private static int sDescendantTrace;
	/** AOSP: the aggregated visibility continues to the visible children. */
	@Override
	public boolean dispatchVisibilityAggregated(boolean isVisible) {
		isVisible = super.dispatchVisibilityAggregated(isVisible);
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == VISIBLE) {
				child.dispatchVisibilityAggregated(isVisible);
			}
		}
		return isVisible;
	}
	/** AOSP: window focus reaches every child. */
	@Override
	public void dispatchWindowFocusChanged(boolean hasFocus) {
		super.dispatchWindowFocusChanged(hasFocus);
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			getChildAt(i).dispatchWindowFocusChanged(hasFocus);
		}
	}

	/* ---- teslalinux: descendant focusability and focus clearing (tl_touch_mode) ---- */
	private static final int[] TL_DESCENDANT_FOCUSABILITY_FLAGS = {FOCUS_BEFORE_DESCENDANTS, FOCUS_AFTER_DESCENDANTS, FOCUS_BLOCK_DESCENDANTS};
	/** AOSP initViewGroup: FOCUS_BEFORE_DESCENDANTS */
	private int tlDescendantFocusability = FOCUS_BEFORE_DESCENDANTS;

	/** AOSP: sets how this group and its descendants share focus. */
	@Override
	public void setDescendantFocusability(int focusability) {
		switch (focusability) {
			case FOCUS_BEFORE_DESCENDANTS:
			case FOCUS_AFTER_DESCENDANTS:
			case FOCUS_BLOCK_DESCENDANTS:
				break;
			default:
				throw new IllegalArgumentException("must be one of FOCUS_BEFORE_DESCENDANTS, FOCUS_AFTER_DESCENDANTS, FOCUS_BLOCK_DESCENDANTS");
		}
		tlDescendantFocusability = focusability;
	}

	/** AOSP: a group whose child holds focus clears the focus there; otherwise the group itself gives it up. */
	@Override
	public void clearFocus() {
		View focused = getFocusedChild();
		if (focused == null)
			super.clearFocus();
		else
			focused.clearFocus();
	}

	/* ---- teslalinux: the AOSP focus model for groups (tl_focus_model, see View.java.frag) ---- */
	/** AOSP mFocused: the child that is or contains the focused view */
	View tlFocusedChild;

	/** AOSP: this group or a descendant is the focused view of its window. */
	@Override
	public boolean hasFocus() {
		return tlFocused || tlFocusedChild != null;
	}

	/** AOSP: this group when focused, else the focused view in its subtree. */
	@Override
	public View findFocus() {
		if (tlFocused)
			return this;
		return tlFocusedChild != null ? tlFocusedChild.findFocus() : null;
	}

	/** AOSP: the focused descendant gave up focus; so does the path from here up. */
	public void clearChildFocus(View child) {
		tlFocusedChild = null;
		if (getParent() instanceof ViewGroup)
			((ViewGroup) getParent()).clearChildFocus(this);
	}

	/** AOSP unFocus: the focused view below (or this group) loses focus. */
	@Override
	void tlUnFocus(View focused) {
		if (tlFocusedChild == null) {
			super.tlUnFocus(focused);
		} else {
			tlFocusedChild.tlUnFocus(focused);
			tlFocusedChild = null;
		}
	}

	/** AOSP: the group itself takes focus; a focused descendant gives it up first. */
	@Override
	void tlHandleFocusGainInternal(int direction, android.graphics.Rect previouslyFocusedRect) {
		if (tlFocusedChild != null) {
			tlFocusedChild.tlUnFocus(this);
			tlFocusedChild = null;
		}
		super.tlHandleFocusGainInternal(direction, previouslyFocusedRect);
	}

	/** AOSP: the group, its descendants or both may take focus, in the order descendantFocusability says. */
	@Override
	public boolean requestFocus(int direction, android.graphics.Rect previouslyFocusedRect) {
		switch (getDescendantFocusability()) {
			case FOCUS_BLOCK_DESCENDANTS:
				return super.requestFocus(direction, previouslyFocusedRect);
			case FOCUS_BEFORE_DESCENDANTS: {
				final boolean took = super.requestFocus(direction, previouslyFocusedRect);
				return took ? took : onRequestFocusInDescendants(direction, previouslyFocusedRect);
			}
			case FOCUS_AFTER_DESCENDANTS: {
				final boolean took = onRequestFocusInDescendants(direction, previouslyFocusedRect);
				return took ? took : super.requestFocus(direction, previouslyFocusedRect);
			}
			default:
				throw new IllegalStateException("descendant focusability must be one of FOCUS_BEFORE_DESCENDANTS, FOCUS_AFTER_DESCENDANTS, FOCUS_BLOCK_DESCENDANTS but is " + getDescendantFocusability());
		}
	}

	/* ---- teslalinux: AOSP ViewGroup drawable state (selected/activated/pressed passed to children,
	 * addStatesFromChildren) ---- */
	private boolean tlAddStatesFromChildren;

	public boolean addStatesFromChildren() {
		return tlAddStatesFromChildren;
	}

	/** AOSP: a child's state changed; a group that shows its children's states rebuilds its own */
	public void childDrawableStateChanged(View child) {
		if (tlAddStatesFromChildren)
			refreshDrawableState();
	}

	@Override
	protected void dispatchSetSelected(boolean selected) {
		for (int i = 0; i < getChildCount(); i++)
			getChildAt(i).setSelected(selected);
	}

	@Override
	protected void dispatchSetActivated(boolean activated) {
		for (int i = 0; i < getChildCount(); i++)
			getChildAt(i).setActivated(activated);
	}

	/** AOSP: the union of the children's states when addStatesFromChildren is set */
	@Override
	protected int[] onCreateDrawableState(int extraSpace) {
		if (!tlAddStatesFromChildren)
			return super.onCreateDrawableState(extraSpace);
		int need = 0;
		for (int i = 0; i < getChildCount(); i++)
			need += getChildAt(i).getDrawableState().length;
		int[] state = super.onCreateDrawableState(extraSpace + need);
		for (int i = 0; i < getChildCount(); i++) {
			final int[] childState = getChildAt(i).getDrawableState();
			if (childState != null)
				state = mergeDrawableStates(state, childState);
		}
		return state;
	}

	/* ---- teslalinux (layout): AOSP ViewGroup clipping, custom drawing order, view transitions,
	 * layout animations, motion-event splitting, focus propagation and window insets dispatch ----
	 * Copyright (C) 2006 The Android Open Source Project, Apache License 2.0 (logic ported from
	 * frameworks/base core/java/android/view/ViewGroup.java, android14-release). */

	/** AOSP initViewGroup: FLAG_CLIP_CHILDREN | FLAG_CLIP_TO_PADDING */
	private boolean tlClipChildren = true;
	private boolean tlClipToPadding = true;
	/** AOSP FLAG_USE_CHILD_DRAWING_ORDER */
	private boolean tlChildrenDrawingOrder;
	/** AOSP FLAG_SPLIT_MOTION_EVENTS: on for every app targeting Honeycomb or later */
	private boolean tlSplitMotionEvents = true;
	/** AOSP FLAG_TOUCHSCREEN_BLOCKS_FOCUS */
	private boolean tlTouchscreenBlocksFocus;
	/** AOSP mTransitioningViews: children whose removal is deferred (startViewTransition) */
	private ArrayList<View> tlTransitioningViews;
	/** AOSP mDisappearingChildren: removed children still drawn until their transition ends */
	private ArrayList<View> tlDisappearingChildren;
	/** the group still drawing a disappearing child */
	private static final java.util.WeakHashMap<View, ViewGroup> tlDisappearingParents = new java.util.WeakHashMap<View, ViewGroup>();
	/** AOSP mLayoutAnimationController / FLAG_RUN_ANIMATION / mAnimationListener */
	private android.view.animation.LayoutAnimationController tlLayoutAnimation;
	private boolean tlRunLayoutAnimation;
	private boolean tlLayoutAnimationPending;
	private android.view.animation.Animation.AnimationListener tlLayoutAnimationListener;
	/** the drawing order last applied to the children's widgets (custom drawing order) */
	private View[] tlAppliedOrder;

	/** AOSP RenderNode.setClipToBounds of a child, from this group's clipChildren (tl_layout_clip) */
	private static native void native_setChildClipsToBounds(long child, boolean clip);
	/** AOSP FLAG_CLIP_TO_PADDING: the children are clipped to the padding box when drawn (tl_layout_clip) */
	private static native void native_setPaddingClip(long widget, boolean enabled);

	/** AOSP ViewGroup.initFromAttributes, the attributes ATL did not read */
	private void tlInitFromAttributes(TypedArray a) {
		final int n = a.getIndexCount();
		for (int i = 0; i < n; i++) {
			final int attr = a.getIndex(i);
			if (attr == com.android.internal.R.styleable.ViewGroup_clipChildren) {
				tlClipChildren = a.getBoolean(attr, true);
			} else if (attr == com.android.internal.R.styleable.ViewGroup_clipToPadding) {
				tlClipToPadding = a.getBoolean(attr, true);
			} else if (attr == com.android.internal.R.styleable.ViewGroup_persistentDrawingCache) {
				setPersistentDrawingCache(a.getInt(attr, PERSISTENT_SCROLLING_CACHE));
			} else if (attr == com.android.internal.R.styleable.ViewGroup_addStatesFromChildren) {
				setAddStatesFromChildren(a.getBoolean(attr, false));
			} else if (attr == com.android.internal.R.styleable.ViewGroup_layoutAnimation) {
				final int id = a.getResourceId(attr, -1);
				if (id > 0)
					setLayoutAnimation(android.view.animation.AnimationUtils.loadLayoutAnimation(getContext(), id));
			} else if (attr == com.android.internal.R.styleable.ViewGroup_splitMotionEvents) {
				tlSplitMotionEvents = a.getBoolean(attr, false);
			} else if (attr == com.android.internal.R.styleable.ViewGroup_animateLayoutChanges) {
				if (a.getBoolean(attr, false))
					setLayoutTransition(new LayoutTransition());
			} else if (attr == com.android.internal.R.styleable.ViewGroup_layoutMode) {
				setLayoutMode(a.getInt(attr, LAYOUT_MODE_UNDEFINED));
			} else if (attr == com.android.internal.R.styleable.ViewGroup_transitionGroup) {
				setTransitionGroup(a.getBoolean(attr, false));
			} else if (attr == com.android.internal.R.styleable.ViewGroup_touchscreenBlocksFocus) {
				tlTouchscreenBlocksFocus = a.getBoolean(attr, false);
			}
		}
	}

	/** end of the constructor: the padding clip of this group follows clipToPadding */
	private void tlInitViewGroup() {
		native_setPaddingClip(widget, tlClipToPadding);
	}

	// ---- clipChildren / clipToPadding ----

	/**
	 * AOSP: whether the children are clipped to their bounds (each child's RenderNode clips to its
	 * bounds while its parent has clipChildren). Off lets a child, and the child's own descendants,
	 * draw outside the child (still inside this group unless its own parent lets it overflow).
	 */
	public void setClipChildren(boolean clipChildren) {
		if (clipChildren == tlClipChildren)
			return;
		tlClipChildren = clipChildren;
		for (int i = 0; i < children.size(); i++)
			native_setChildClipsToBounds(children.get(i).widget, clipChildren);
		invalidate();
	}

	public boolean getClipChildren() {
		return tlClipChildren;
	}

	/** AOSP: whether the children are clipped to this group's padding box when drawn (default on) */
	public void setClipToPadding(boolean clipToPadding) {
		if (clipToPadding == tlClipToPadding)
			return;
		tlClipToPadding = clipToPadding;
		native_setPaddingClip(widget, clipToPadding);
		invalidate();
	}

	public boolean getClipToPadding() {
		return tlClipToPadding;
	}

	/** a child joins: its drawing clips to its bounds as this group's clipChildren says */
	private void tlChildAdded(View child) {
		native_setChildClipsToBounds(child.widget, tlClipChildren);
	}

	/**
	 * before a child's widget joins this group: a child added back while it was disappearing (still
	 * drawn by the group it was removed from) stops disappearing, as AOSP addViewInner does; GTK
	 * gives a widget one parent, so another group lets go of the widget first
	 */
	private void tlBeforeChildAdded(View child) {
		final ViewGroup old = tlDisappearingParents.remove(child);
		if (old != null) {
			if (old.tlDisappearingChildren != null)
				old.tlDisappearingChildren.remove(child);
			if (old != this) {
				old.native_removeView(old.widget, child.widget);
				old.invalidate();
			}
		}
	}

	// ---- custom drawing order (AOSP getAndVerifyPreorderedIndex) ----

	/**
	 * AOSP: when enabled, getChildDrawingOrder(childCount, i) decides which child is drawn i-th
	 * (and, in reverse, which one gets touches first); Z still sorts on top of that order.
	 */
	protected void setChildrenDrawingOrderEnabled(boolean enabled) {
		if (enabled == tlChildrenDrawingOrder)
			return;
		tlChildrenDrawingOrder = enabled;
		// usually called from a constructor: the order is asked for once the group is set up
		tlScheduleDrawingOrder();
	}

	protected boolean isChildrenDrawingOrderEnabled() {
		return tlChildrenDrawingOrder;
	}

	/** a re-evaluation of the custom drawing order is posted */
	private boolean tlDrawingOrderPending;

	/**
	 * AOSP getAndVerifyPreorderedIndex for every drawing position: the custom drawing order, or
	 * null for the child order. AOSP asks getChildDrawingOrder while drawing, when the group's own
	 * bookkeeping is complete; here the order is asked for when children come and go and at layout,
	 * where a ViewPager may still be adding pages. An order that cannot be had yet (an exception, an
	 * index out of range or twice) stacks the children in child order for now and is asked for
	 * again on the next main-loop pass.
	 */
	private int[] tlCustomDrawingOrder(int childrenCount) {
		if (!tlChildrenDrawingOrder)
			return null;
		final int[] order = new int[childrenCount];
		final boolean[] seen = new boolean[childrenCount];
		try {
			for (int i = 0; i < childrenCount; i++) {
				final int childIndex = getChildDrawingOrder(childrenCount, i);
				if (childIndex < 0 || childIndex >= childrenCount || seen[childIndex])
					throw new IndexOutOfBoundsException("getChildDrawingOrder() returned invalid index " + childIndex + " (child count is " + childrenCount + ")");
				seen[childIndex] = true;
				order[i] = childIndex;
			}
		} catch (RuntimeException e) {
			tlScheduleDrawingOrder();
			return null;
		}
		return order;
	}

	private void tlScheduleDrawingOrder() {
		if (tlDrawingOrderPending)
			return;
		tlDrawingOrderPending = true;
		post(new Runnable() {
			@Override
			public void run() {
				tlDrawingOrderPending = false;
				tlApplyDrawingOrder();
			}
		});
	}

	/**
	 * Re-stacks the children's widgets when the drawing order (custom order and Z) differs from the
	 * order last applied. AOSP evaluates the order on every draw; here the widget order is the
	 * drawing order, so it is re-evaluated whenever it can have changed: children added, removed or
	 * brought to front, the custom order switched, and every layout of this group (ViewPager and
	 * SwipeRefreshLayout compute their order while measuring and laying out).
	 */
	private void tlApplyDrawingOrder() {
		if (!tlChildrenDrawingOrder && tlAppliedOrder == null)
			return; // the plain child (or Z) order, kept by the add/Z paths
		final ArrayList<View> ordered = tlOrderedChildren();
		final int n = ordered.size();
		boolean same = tlAppliedOrder != null && tlAppliedOrder.length == n;
		for (int i = 0; same && i < n; i++)
			same = tlAppliedOrder[i] == ordered.get(i);
		if (same)
			return;
		tlAppliedOrder = tlChildrenDrawingOrder ? ordered.toArray(new View[n]) : null;
		tlRestackChildren();
	}

	@Override
	public void layout(int l, int t, int r, int b) {
		super.layout(l, t, r, b);
		if (tlChildrenDrawingOrder)
			tlApplyDrawingOrder();
		if (tlRunLayoutAnimation && tlLayoutAnimation != null && !tlLayoutAnimationPending) {
			// AOSP runs the layout animation from the first dispatchDraw after the layout: the
			// children are bound to it once this layout pass has allocated them
			tlLayoutAnimationPending = true;
			post(new Runnable() {
				@Override
				public void run() {
					tlLayoutAnimationPending = false;
					tlBindLayoutAnimation();
				}
			});
		}
	}

	// ---- view transitions (AOSP startViewTransition / endViewTransition) ----

	/**
	 * AOSP: a child that is removed while in a transition (a fragment's exit animation) stays drawn
	 * until endViewTransition; its widget stays in the group, it just leaves the child list.
	 */
	public void startViewTransition(View view) {
		if (view.parent == this) {
			if (tlTransitioningViews == null)
				tlTransitioningViews = new ArrayList<View>();
			tlTransitioningViews.add(view);
		}
	}

	public void endViewTransition(View view) {
		if (tlTransitioningViews != null) {
			tlTransitioningViews.remove(view);
			final ArrayList<View> disappearing = tlDisappearingChildren;
			if (disappearing != null && disappearing.remove(view)) {
				tlDisappearingParents.remove(view);
				native_removeView(widget, view.widget);
				invalidate();
			}
		}
	}

	/** AOSP removeViewInternal -> addDisappearingView: whether the removal keeps the widget for now */
	private boolean tlKeepDisappearing(View child) {
		if (tlTransitioningViews == null || !tlTransitioningViews.contains(child))
			return false;
		if (tlDisappearingChildren == null)
			tlDisappearingChildren = new ArrayList<View>();
		if (!tlDisappearingChildren.contains(child))
			tlDisappearingChildren.add(child);
		tlDisappearingParents.put(child, this);
		return true;
	}

	/** removes the child's widget, or keeps it drawn while the child is in a view transition */
	private void tlRemoveChildWidget(View child) {
		if (!tlKeepDisappearing(child))
			native_removeView(widget, child.widget);
	}

	// ---- layout animation (AOSP setLayoutAnimation & co.) ----

	/** AOSP: the animation run on the children at the next layout of this group */
	public void setLayoutAnimation(android.view.animation.LayoutAnimationController controller) {
		tlLayoutAnimation = controller;
		if (controller != null)
			tlRunLayoutAnimation = true;
	}

	public android.view.animation.LayoutAnimationController getLayoutAnimation() {
		return tlLayoutAnimation;
	}

	/** AOSP: runs the layout animation at the next layout, and asks for that layout */
	public void startLayoutAnimation() {
		if (tlLayoutAnimation != null) {
			tlRunLayoutAnimation = true;
			requestLayout();
		}
	}

	/** AOSP: the layout animation runs after the next layout pass */
	public void scheduleLayoutAnimation() {
		if (tlLayoutAnimation != null)
			tlRunLayoutAnimation = true;
	}

	public void setLayoutAnimationListener(android.view.animation.Animation.AnimationListener animationListener) {
		tlLayoutAnimationListener = animationListener;
	}

	public android.view.animation.Animation.AnimationListener getLayoutAnimationListener() {
		return tlLayoutAnimationListener;
	}

	/** AOSP: whether the group can run its layout animation */
	protected boolean canAnimate() {
		return tlLayoutAnimation != null;
	}

	/** AOSP: hands the child its index and the child count, for the controller's delays */
	protected void attachLayoutAnimationParameters(View child, LayoutParams params, int index, int count) {
		android.view.animation.LayoutAnimationController.AnimationParameters animationParams = params.layoutAnimationParameters;
		if (animationParams == null) {
			animationParams = new android.view.animation.LayoutAnimationController.AnimationParameters();
			params.layoutAnimationParameters = animationParams;
		}
		animationParams.count = count;
		animationParams.index = index;
	}

	/** AOSP dispatchDraw, FLAG_RUN_ANIMATION: every visible child gets its delayed animation */
	private void tlBindLayoutAnimation() {
		if (!tlRunLayoutAnimation || !canAnimate())
			return;
		final android.view.animation.LayoutAnimationController controller = tlLayoutAnimation;
		final int count = children.size();
		final ArrayList<View> animated = new ArrayList<View>();
		for (int i = 0; i < count; i++) {
			final View child = children.get(i);
			if (child.getVisibility() == VISIBLE) {
				final LayoutParams params = child.getLayoutParams();
				attachLayoutAnimationParameters(child, params, i, count);
				final android.view.animation.Animation animation = controller.getAnimationForView(child);
				child.setAnimation(animation);
				animated.add(child);
			}
		}
		controller.start();
		tlRunLayoutAnimation = false;
		if (tlLayoutAnimationListener != null)
			tlLayoutAnimationListener.onAnimationStart(controller.getAnimation());
		// AOSP notifies the end once the controller is done and no child animates any more
		post(new Runnable() {
			@Override
			public void run() {
				boolean more = false;
				for (int i = 0; i < animated.size() && !more; i++) {
					final android.view.animation.Animation a = animated.get(i).getAnimation();
					more = a != null && !a.hasEnded();
				}
				if (more || !controller.isDone()) {
					postDelayed(this, 16);
					return;
				}
				if (tlLayoutAnimationListener != null)
					tlLayoutAnimationListener.onAnimationEnd(controller.getAnimation());
			}
		});
	}

	// ---- touch: motion event splitting ----

	/**
	 * AOSP: whether a second pointer that goes down on another child is sent to that child instead
	 * of joining the gesture of the first one. ATL's touch path picks the target of every event
	 * from the primary pointer (native_dispatchTouchEvent); the flag is kept for the app and its
	 * own dispatch logic.
	 */
	public void setMotionEventSplittingEnabled(boolean split) {
		tlSplitMotionEvents = split;
	}

	public boolean isMotionEventSplittingEnabled() {
		return tlSplitMotionEvents;
	}

	// ---- layout params ----

	/** AOSP: any non-null params fit a plain ViewGroup; subclasses check their own type */
	protected boolean checkLayoutParams(LayoutParams p) {
		return p != null;
	}

	/** AOSP: the child skips the forced layout it asked for (PFLAG_FORCE_LAYOUT is cleared) */
	protected void cleanupLayoutState(View child) {
		child.tlCleanupLayoutState();
	}

	/**
	 * AOSP: true, a plain ViewGroup is assumed to scroll, so its children delay their pressed state
	 * by the tap timeout (FrameLayout, LinearLayout, RelativeLayout and other layouts say false).
	 */
	public boolean shouldDelayChildPressedState() {
		return true;
	}

	/** AOSP: moves every child up or down (their tops and bottoms) */
	public void offsetChildrenTopAndBottom(int offset) {
		for (int i = 0; i < children.size(); i++)
			children.get(i).offsetTopAndBottom(offset);
		invalidate();
	}

	/** AOSP: offsets a rectangle in the descendant's coordinates into this group's coordinates */
	public final void offsetDescendantRectToMyCoords(View descendant, Rect rect) {
		View v = descendant;
		while (v != this) {
			if (v == null)
				throw new IllegalArgumentException("parameter must be a descendant of this view");
			rect.offset(v.getLeft() - v.getScrollX(), v.getTop() - v.getScrollY());
			v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
		}
	}

	// ---- focus ----

	/* requestChildFocus is the focus model's (ViewGroup.java.repl, tl_focus_model) */

	/**
	 * AOSP: a descendant became focusable; the report travels up to the window root, which gives
	 * it focus when nothing has focus and the window is not in touch mode (ViewRootImpl).
	 */
	public void focusableViewAvailable(View v) {
		if (getDescendantFocusability() == FOCUS_BLOCK_DESCENDANTS || getVisibility() != VISIBLE)
			return;
		if (!isFocusableInTouchMode() && tlShouldBlockFocusForTouchscreen())
			return;
		if (isFocused() && getDescendantFocusability() != FOCUS_AFTER_DESCENDANTS)
			return;
		if (parent instanceof ViewGroup) {
			((ViewGroup) parent).focusableViewAvailable(v);
		} else if (!(parent instanceof View) && isAttachedToWindow()) {
			// AOSP ViewRootImpl.focusableViewAvailable, this view being the window's root
			if (!hasFocus()) {
				if (!isInTouchMode())
					v.requestFocus();
			} else {
				final View focused = findFocus();
				if (focused instanceof ViewGroup && ((ViewGroup) focused).getDescendantFocusability() == FOCUS_AFTER_DESCENDANTS && tlIsDescendantOf(v, focused))
					v.requestFocus();
			}
		}
	}

	private static boolean tlIsDescendantOf(View child, View ancestor) {
		for (ViewParent p = child.getParent(); p != null; p = p.getParent()) {
			if (p == ancestor)
				return true;
		}
		return false;
	}

	/** AOSP: on a touchscreen, a group that blocks focus keeps its descendants from taking focus */
	public void setTouchscreenBlocksFocus(boolean touchscreenBlocksFocus) {
		tlTouchscreenBlocksFocus = touchscreenBlocksFocus;
	}

	public boolean getTouchscreenBlocksFocus() {
		return tlTouchscreenBlocksFocus;
	}

	/**
	 * AOSP shouldBlockFocusForTouchscreen: the car screen is a touchscreen (FEATURE_TOUCHSCREEN), so
	 * the flag blocks, except for a keyboard navigation cluster that holds the focus.
	 */
	private boolean tlShouldBlockFocusForTouchscreen() {
		return tlTouchscreenBlocksFocus && !(isKeyboardNavigationCluster() && hasFocus());
	}

	// ---- window insets (AOSP dispatchApplyWindowInsets, the non-broken dispatch of API 30+) ----

	/** AOSP: the group applies the insets itself, then every child gets them, unless consumed */
	@Override
	public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
		insets = super.dispatchApplyWindowInsets(insets);
		if (insets.isConsumed())
			return insets;
		final int count = getChildCount();
		for (int i = 0; i < count; i++)
			getChildAt(i).dispatchApplyWindowInsets(insets);
		return insets;
	}

	/** AOSP: the group and every descendant use the framework's optional fitsSystemWindows */
	@Override
	public void makeOptionalFitsSystemWindows() {
		super.makeOptionalFitsSystemWindows();
		final int count = getChildCount();
		for (int i = 0; i < count; i++)
			getChildAt(i).makeOptionalFitsSystemWindows();
	}

	// ---- key shortcuts and trackball events (AOSP: the focused view, or the child holding it) ----

	@Override
	public boolean dispatchKeyShortcutEvent(KeyEvent event) {
		if (isFocused())
			return super.dispatchKeyShortcutEvent(event);
		final View focused = getFocusedChild();
		return focused != null && focused.dispatchKeyShortcutEvent(event);
	}

	@Override
	public boolean dispatchTrackballEvent(MotionEvent event) {
		if (isFocused())
			return super.dispatchTrackballEvent(event);
		final View focused = getFocusedChild();
		return focused != null && focused.dispatchTrackballEvent(event);
	}

	// ---- drawing caches (AOSP, deprecated since API 28: with hardware rendering they are flags) ----
	private boolean tlAlwaysDrawnWithCache = true;
	private boolean tlChildrenDrawnWithCache;
	private boolean tlAnimationCache = true;

	/** AOSP: deprecated; the flag is kept, as AOSP keeps it with hardware rendering */
	public void setAlwaysDrawnWithCacheEnabled(boolean always) {
		tlAlwaysDrawnWithCache = always;
	}

	public boolean isAlwaysDrawnWithCacheEnabled() {
		return tlAlwaysDrawnWithCache;
	}

	protected void setChildrenDrawnWithCacheEnabled(boolean enabled) {
		tlChildrenDrawnWithCache = enabled;
	}

	protected boolean isChildrenDrawnWithCacheEnabled() {
		return tlChildrenDrawnWithCache;
	}

	/** AOSP: deprecated; the flag is kept */
	public void setAnimationCacheEnabled(boolean enabled) {
		tlAnimationCache = enabled;
	}

	public boolean isAnimationCacheEnabled() {
		return tlAnimationCache;
	}

	/** AOSP: every child's drawing cache follows, unless all caches are to be kept */
	protected void setChildrenDrawingCacheEnabled(boolean enabled) {
		if (enabled || (getPersistentDrawingCache() & PERSISTENT_ALL_CACHES) != PERSISTENT_ALL_CACHES) {
			for (int i = 0; i < children.size(); i++)
				children.get(i).setDrawingCacheEnabled(enabled);
		}
	}

	/* ---- teslalinux: children drawn in Z order (AOSP ViewGroup.buildOrderedChildList) ----
	 * AOSP draws (and dispatches touches to, in reverse) the children sorted by Z, stable, so equal
	 * Z keeps child order; the child list itself (getChildAt) stays in insertion order. Here the
	 * order of the children's GTK widgets is the drawing order (native_drawChildren snapshots them in
	 * sibling order, and GTK picks the topmost), so it is kept equal to the ordered child list: after
	 * an add and whenever a child's Z changes. With every Z at 0 that order is the child order. */

	/** AOSP buildOrderedChildList: stable insertion by Z */
	private ArrayList<View> tlOrderedChildren() {
		final int n = children.size();
		final ArrayList<View> list = new ArrayList<View>(n);
		final int[] customOrder = tlCustomDrawingOrder(n); // (layout) AOSP getAndVerifyPreorderedIndex
		for (int i = 0; i < n; i++) {
			final View child = children.get(customOrder != null ? customOrder[i] : i);
			final float z = child.getZ();
			int insert = list.size();
			while (insert > 0 && list.get(insert - 1).getZ() > z)
				insert--;
			list.add(insert, child);
		}
		return list;
	}

	/** re-stacks the children's widgets in drawing order (last drawn on top) */
	void tlRestackChildren() {
		final ArrayList<View> ordered = tlOrderedChildren();
		long next = 0;
		for (int i = ordered.size() - 1; i >= 0; i--) {
			final View child = ordered.get(i);
			native_restackChild(widget, child.widget, next);
			next = child.widget;
		}
		invalidate();
	}

	/** whether any child has a Z (only then can the drawing order differ from the child order) */
	private boolean tlAnyChildZ() {
		for (int i = 0; i < children.size(); i++) {
			if (children.get(i).getZ() != 0f)
				return true;
		}
		return false;
	}

	/** moves child's widget right before next's (to the end when next is 0), unless already there */
	private native void native_restackChild(long widget, long child, long next);
}
