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

		child.parent = this;
		child.setLayoutParams(params);
		if (index < 0)
			index = children.size();
		children.add(index, child);
		int sortedIndex = index;
		for (View v : children) {
			if (v.getZ() != 0.0f) {
				// sort children by z-order
				ArrayList<View> sortedChildren = new ArrayList<View>(children);
				sortedChildren.sort(new Comparator<View>() {
					@Override
					public int compare(View o1, View o2) {
						return (int)(o1.getZ() - o2.getZ());
					}
				});
				sortedIndex = sortedChildren.indexOf(child);
				break;
			}
		}
		native_addView(widget, child.widget, sortedIndex, params);
		if (callOnViewAdded) {
			onViewAdded(child);
			if (onHierarchyChangeListener != null)
				onHierarchyChangeListener.onChildViewAdded(this, child);
		}
	}

	/* We never call this ourselves */
	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		return native_dispatchTouchEvent(widget, event, event.getX(), event.getY());
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
		child.parent = null;
		children.remove(child);
		native_removeView(widget, child.widget);
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
		for (Iterator<View> it = children.iterator(); it.hasNext();) {
			View child = it.next();
			child.parent = null;
			it.remove();
			native_removeView(widget, child.widget);
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
		child.parent = null;
		native_removeView(widget, child.widget);
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

	public void bringChildToFront(View child) {
		// TODO: actually implement this (might make sense to implement it in the subclasses instead), when applicable
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

	public void setMotionEventSplittingEnabled(boolean enabled) {}

	public void setOnHierarchyChangeListener(OnHierarchyChangeListener listener) {
		this.onHierarchyChangeListener = listener;
	}

	protected boolean checkLayoutParams(LayoutParams params) {
		return true;
	}

	public LayoutTransition getLayoutTransition() {
		if (transition == null)
			transition = new LayoutTransition();
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

	public void setAddStatesFromChildren(boolean addsStates) {}

	public View getFocusedChild() { return null; }

	public int getDescendantFocusability() { return 0; }

	public void startViewTransition(View view) {}
	public void endViewTransition(View view) {}

	protected LayoutParams generateDefaultLayoutParams() {
		return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
	}

	public void focusableViewAvailable(View v) {}

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

	protected void setChildrenDrawingOrderEnabled(boolean enabled) {}

	public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
		for (ViewGroup iter = this; iter != null; iter = iter.parent instanceof ViewGroup ? (ViewGroup)iter.parent : null)
			iter.disallowIntercept = disallowIntercept;
	}

	protected boolean isChildrenDrawingOrderEnabled() { return false; }

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

	public void setClipToPadding(boolean clipToPadding) {}

	public View findViewById(int id) {
		Slog.v(TAG, "findViewById: looking for id: " + String.format("%x", id) + "(" + getResources().getAssets().getResourceName(id) + ")"
		            + " | checking: " + this + ",id: " + String.format("%x", this.getId()) + ", id_str: " + this.getIdName());
		if (this.id == id) {
			Slog.v(TAG, "findViewById: found: " + this + " | id: " + String.format("%x", this.getId()) + ", id_str: " + this.getIdName());
			return this;
		}
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

	public void setTouchscreenBlocksFocus(boolean touchscreenBlocksFocus) {}

	public void setClipChildren(boolean clipChildren) {}

	protected void dispatchSetPressed(boolean pressed) {}

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

	protected void cleanupLayoutState(View child) {}

	public boolean shouldDelayChildPressedState() { return false; }

	public void removeAllViewsInLayout() {
		for (int i = getChildCount() - 1; i >= 0; i--) {
			removeViewInLayout(getChildAt(i));
		}
	}

	public void offsetChildrenTopAndBottom(int offset) {
		// FIXME
	}

	public final void offsetDescendantRectToMyCoords(View descendant, Rect rect) {
		// FIXME
	}

	public boolean getClipToPadding() { return false; }

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

		public LayoutParams() {
			// FIXME
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

			int margin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_margin, -1);

			int marginVertical = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginVertical, -1);
			int marginHorizontal = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginHorizontal, -1);

			int marginStart = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginStart, -1);
			int marginEnd = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginEnd, -1);

			leftMargin = 0;
			topMargin = 0;
			rightMargin = 0;
			bottomMargin = 0;

			if (margin >= 0) {
				leftMargin = margin;
				topMargin = margin;
				rightMargin = margin;
				bottomMargin = margin;
			} else {
				if (marginVertical >= 0) {
					topMargin = marginVertical;
					bottomMargin = marginVertical;
				} else {
					topMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginTop, 0);
					bottomMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginBottom, 0);
				}

				if (marginHorizontal >= 0) {
					leftMargin = marginHorizontal;
					rightMargin = marginHorizontal;
				} else {
					leftMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginLeft, 0);
					rightMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginRight, 0);

					if (marginStart >= 0) {
						leftMargin = marginStart;
					}

					if (marginEnd >= 0) {
						rightMargin = marginEnd;
					}
				}
			}

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
		}

		public void setMargins(int left, int top, int right, int bottom) {
			leftMargin = left;
			topMargin = top;
			rightMargin = right;
			bottomMargin = bottom;
		}

		public int getMarginStart() {
			return leftMargin;
		}

		public int getMarginEnd() {
			return rightMargin;
		}

		public void setMarginStart(int marginStart) {
			leftMargin = marginStart;
		}
		public void setMarginEnd(int marginEnd) {
			rightMargin = marginEnd;
		}

		private int tlLayoutDirection = View.LAYOUT_DIRECTION_LTR;

		public int getLayoutDirection() { return tlLayoutDirection; }

		/** AOSP: the resolved layout direction of these params (LTR/RTL), used by start/end margins. */
		public void setLayoutDirection(int layoutDirection) {
			if (layoutDirection != View.LAYOUT_DIRECTION_LTR && layoutDirection != View.LAYOUT_DIRECTION_RTL)
				return;
			tlLayoutDirection = layoutDirection;
		}

		public void resolveLayoutDirection(int layoutDirection) {
			setLayoutDirection(layoutDirection);
		}

		public boolean isMarginRelative() { return false; }
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

	public void requestChildFocus(View child, View focused) {}

	public boolean getClipChildren() {
		return false;
	}

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

	public boolean getTouchscreenBlocksFocus() { return false; }

	public ViewGroupOverlay getOverlay() {
		if (viewGroupOverlay == null)
			viewGroupOverlay = new ViewGroupOverlay();
		return viewGroupOverlay;
	}

	public void scheduleLayoutAnimation() {}

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
}
