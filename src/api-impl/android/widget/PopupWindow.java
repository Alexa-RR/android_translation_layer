package android.widget;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;

public class PopupWindow {

	int input_method_mode = 0;

	public PopupWindow(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		popover = native_constructor();
	}

	public PopupWindow(Context context) {
		this(context, null, 0, 0);
	}

	public PopupWindow() {
		popover = native_constructor();
	}

	public PopupWindow(View contentView, int width, int height, boolean focusable) {
		popover = native_constructor();
		setContentView(contentView);
		setWidth(width);
		setHeight(height);
		setFocusable(focusable);
	}

	/** AOSP: a popup made with this constructor is not focusable. */
	public PopupWindow(View contentView, int width, int height) {
		this(contentView, width, height, false);
	}

	private View contentView;
	private Drawable background;
	private long popover; // native pointer to GtkPopover

	public interface OnDismissListener {
		public void onDismiss();
	}

	public void setBackgroundDrawable(Drawable background) {
		this.background = background;
		/* FIXME: use a decorview? */
		if (contentView != null) {
			contentView.setBackgroundDrawable(background);
		}
	}

	public void setInputMethodMode(int mode) {
		input_method_mode = mode;
	}

	public int getInputMethodMode() {
		return input_method_mode;
	}

	public boolean isShowing() {
		return native_isShowing(popover);
	}

	/* ---- teslalinux: AOSP focus and touch semantics of a popup (tl_popup_window) ----
	 * AOSP: a popup that is not focusable (the default) never takes key focus (FLAG_NOT_FOCUSABLE)
	 * and is never touch modal (FLAG_NOT_FOCUSABLE implies FLAG_NOT_TOUCH_MODAL): touches outside
	 * it reach the window behind. A focusable, touch-modal popup consumes them. An outside-touchable
	 * popup gets ACTION_OUTSIDE for a touch elsewhere and dismisses itself (PopupDecorView).
	 * The popup is a GtkPopover: only a focusable, touch-modal one grabs (autohide: GTK consumes
	 * the outside click and closes it); the others never take the keyboard (can-focus off) and
	 * learn about outside touches from the main window's input hook (View.tlPointerDown). */
	private boolean mFocusable;
	private boolean mTouchModal = true;
	private boolean mOutsideTouchable;
	private View.OnTouchListener mTouchInterceptor;
	private static final java.util.ArrayList<PopupWindow> tlShowing = new java.util.ArrayList<>();

	public void setFocusable(boolean focusable) {
		mFocusable = focusable;
		native_setFocusable(popover, focusable);
		tlApplyTouchModal();
	}

	public boolean isFocusable() {
		return mFocusable;
	}

	public boolean isTouchModal() {
		return mTouchModal;
	}

	public boolean isOutsideTouchable() {
		return mOutsideTouchable;
	}

	private void tlApplyTouchModal() {
		native_setTouchModal(popover, mFocusable && mTouchModal);
	}

	private void tlShown() {
		synchronized (tlShowing) {
			if (!tlShowing.contains(this))
				tlShowing.add(this);
		}
	}

	/**
	 * A pointer DOWN landed outside this process's popups except the one whose GtkPopover is
	 * eventPopover (0: none). Every other showing, outside-touchable popup that does not grab gets
	 * ACTION_OUTSIDE: its touch interceptor first, then it dismisses itself (AOSP PopupDecorView).
	 */
	public static void tlDispatchOutsideTouch(long eventPopover) {
		java.util.ArrayList<PopupWindow> showing;
		synchronized (tlShowing) {
			showing = new java.util.ArrayList<>(tlShowing);
		}
		for (PopupWindow popup : showing) {
			if (!popup.isShowing()) {
				synchronized (tlShowing) {
					tlShowing.remove(popup);
				}
				continue;
			}
			if (popup.popover == eventPopover || !popup.mOutsideTouchable || (popup.mFocusable && popup.mTouchModal))
				continue;
			if (popup.mTouchInterceptor != null) {
				long now = android.os.SystemClock.uptimeMillis();
				android.view.MotionEvent outside = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_OUTSIDE, 0, 0, 0);
				boolean handled = popup.mTouchInterceptor.onTouch(popup.contentView, outside);
				outside.recycle();
				if (handled)
					continue;
			}
			popup.dismiss();
		}
	}

	private native void native_setFocusable(long popover, boolean focusable);

	public Drawable getBackground() {
		return background;
	}

	public void setContentView(View view) {
		contentView = view;
		if (contentView != null) {
			contentView.setBackground(getBackground());
		}
		native_setContentView(popover, view == null ? 0 : view.widget);
	}

	public int getMaxAvailableHeight(View anchor, int yOffset) { return 500; }

	public int getMaxAvailableHeight(View anchor, int yOffset, boolean ignoreKeyboard) { return 500; }

	/** AOSP: the popup is told about touches outside it (ACTION_OUTSIDE) and dismisses itself. */
	public void setOutsideTouchable(boolean touchable) {
		mOutsideTouchable = touchable;
	}

	/** AOSP: sees the popup's touch events (including ACTION_OUTSIDE) before the popup handles them. */
	public void setTouchInterceptor(View.OnTouchListener listener) {
		mTouchInterceptor = listener;
	}

	public void showAsDropDown(View anchor, int xoff, int yoff, int gravity) {
		native_showAsDropDown(popover, anchor.widget, xoff, yoff, gravity);
		tlShown();
	}

	public View getContentView() {
		return contentView;
	}

	public void setTouchable(boolean touchable) {
		native_setTouchable(popover, touchable);
	}

	public void showAsDropDown(View anchor, int xoff, int yoff) {
		if (!anchor.isAttachedToWindow()) {
			Log.e("PopupWindow", "anchor is not attached to window");
			return;
		}
		native_showAsDropDown(popover, anchor.widget, xoff, yoff, Gravity.NO_GRAVITY);
		tlShown();
	}

	public void showAtLocation(View parent, int gravity, int x, int y) {
		native_showAsDropDown(popover, parent.widget, x, y, gravity);
		tlShown();
	}

	public void dismiss() {
		synchronized (tlShowing) {
			tlShowing.remove(this);
		}
		native_dismiss(popover);
	}

	public void setAnimationStyle(int animationStyle) {}

	/** AOSP: whether a focusable popup consumes touches outside it. */
	public void setTouchModal(boolean touchModal) {
		mTouchModal = touchModal;
		tlApplyTouchModal();
	}

	public void setElevation(float elevation) {}

	public void update(View anchor, int xoff, int yoff, int width, int height) {
		native_update(popover, anchor.widget, xoff, yoff, width, height);
	}

	public void setWindowLayoutType(int type) {}

	public void setIsClippedToScreen(boolean isClippedToScreen) {}

	public void setEpicenterBounds(Rect bounds) {}

	public void setClippingEnabled(boolean enabled) {}

	/* TODO: handle LayoutParams.WRAP_CONTENT and LayoutParams.MATCH_PARENT */
	public void setWidth(int width) {
		if (width < 0)
			return;

		native_setWidth(popover, width);
	}

	public void setHeight(int height) {
		if (height < 0)
			return;

		native_setHeight(popover, height);
	}

	public int getWidth() {
		return native_getWidth(popover);
	}

	public int getHeight() {
		return native_getHeight(popover);
	}

	public void update(int x, int y, int width, int height) {}

	public void setWindowLayoutMode(int widthSpec, int heightSpec) {}

	public boolean isTouchable() {
		return native_isTouchable(popover);
	}

	public void setOverlapAnchor(boolean overlap) {
	}

	public void setSoftInputMode(int mode) {}

	protected native long native_constructor();
	protected native void native_setContentView(long widget, long contentView);
	protected native void native_showAsDropDown(long widget, long anchor, int xoff, int yoff, int gravity);
	protected native boolean native_isShowing(long widget);
	protected native void native_setTouchable(long widget, boolean touchable);
	protected native void native_setTouchModal(long widget, boolean touchable);
	protected native void native_dismiss(long widget);
	protected native void native_update(long widget, long anchor, int xoff, int yoff, int width, int height);
	public native void setOnDismissListener(OnDismissListener listener);
	public native void native_setWidth(long widget, int width);
	public native void native_setHeight(long widget, int height);
	public native int native_getWidth(long widget);
	public native int native_getHeight(long widget);
	public native boolean native_isTouchable(long widget);
}
