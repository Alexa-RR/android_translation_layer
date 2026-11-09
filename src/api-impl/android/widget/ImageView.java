package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

public class ImageView extends View {

	private Bitmap bitmap = null;
	private ScaleType scaleType = ScaleType.FIT_CENTER;
	private Drawable drawable = null;
	/* colour filter and tint: tlColorFilter / tlDrawableTintList (ImageView.java+tint.frag) */

	public ImageView(Context context) {
		this(context, null);
	}

	public ImageView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public ImageView(Context context, AttributeSet attrs, int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public ImageView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);

		haveCustomMeasure = false;
		TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.ImageView, defStyleAttr, defStyleRes);
		// AOSP ImageView(Context, AttributeSet, ...): a tint colour state list and its mode (the
		// attribute's enum values, not PorterDuff.Mode ordinals), the drawable alpha, then the source
		if (a.hasValue(com.android.internal.R.styleable.ImageView_tint)) {
			tlDrawableTintList = a.getColorStateList(com.android.internal.R.styleable.ImageView_tint);
			tlHasDrawableTint = true;
			// AOSP: a tint without a mode blends SRC_ATOP
			tlDrawableTintMode = PorterDuff.Mode.SRC_ATOP;
			tlHasDrawableTintMode = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.ImageView_tintMode)) {
			tlDrawableTintMode = Drawable.parseTintMode(a.getInt(com.android.internal.R.styleable.ImageView_tintMode, -1), tlDrawableTintMode);
			tlHasDrawableTintMode = true;
		}
		final Drawable src = a.getDrawable(com.android.internal.R.styleable.ImageView_src);
		if (src != null)
			setImageDrawable(src);
		if (a.hasValue(com.android.internal.R.styleable.ImageView_scaleType))
			setScaleType(scaletype_from_int[a.getInt(com.android.internal.R.styleable.ImageView_scaleType, 3 /*CENTER*/)]);
		a.recycle();
	}

	/** AOSP: the resource becomes the image drawable (a BitmapDrawable for bitmaps), so tint, colour
	 * filter, state and getDrawable() apply to it like to any other drawable. */
	public void setImageResource(final int resid) {
		if (resid == 0) {
			setImageDrawable(null);
			return;
		}
		if (this.getResources().getString(resid).endsWith(".xml")) {
			setImageDrawable(getContext().getDrawable(resid));
			return;
		}
		final Bitmap b = BitmapFactory.decodeResource(this.getResources(), resid);
		setImageDrawable(b != null ? new android.graphics.drawable.BitmapDrawable(getResources(), b) : null);
	}
	public void setAdjustViewBounds(boolean adjustViewBounds) {}

	public void setScaleType(ScaleType scaleType) {
		this.scaleType = scaleType;
		native_setScaleType(widget, scaleType.nativeInt);
	}

	public ScaleType getScaleType() {
		return scaleType;
	}

	public Drawable getDrawable() {
		return drawable;
	}

	/** AOSP updateDrawable(): the new drawable takes the view's state, tint, colour filter and alpha. */
	public void setImageDrawable(Drawable drawable) {
		if (this.drawable != null && this.drawable != drawable)
			this.drawable.setCallback(null);
		this.drawable = drawable;
		if (drawable != null) {
			drawable.setCallback(this);
			if (drawable.isStateful())
				drawable.setState(getDrawableState());
			tlApplyImageTint();
			tlApplyColorMod();
			if (this.drawable != drawable)
				this.drawable.setCallback(this);
		}
		native_setDrawable(widget, this.drawable != null ? this.drawable.paintable : 0);
	}

	public void setImageMatrix(Matrix matrix) {}

	/** AOSP: the bitmap is shown through a BitmapDrawable (getDrawable(), tint and filters apply). */
	public void setImageBitmap(Bitmap bitmap) {
		setImageDrawable(bitmap != null ? new android.graphics.drawable.BitmapDrawable(getResources(), bitmap) : null);
	}

	/**
	 * Options for scaling the bounds of an image to the bounds of this view.
	 */
	public enum ScaleType {
		/**
		 * Scale using the image matrix when drawing. The image matrix can be set using
		 * {@link ImageView#setImageMatrix(Matrix)}. From XML, use this syntax:
		 * <code>android:scaleType="matrix"</code>.
		 */
		MATRIX(0),
		/**
		 * Scale the image using {@link Matrix.ScaleToFit#FILL}.
		 * From XML, use this syntax: <code>android:scaleType="fitXY"</code>.
		 */
		FIT_XY(1),
		/**
		 * Scale the image using {@link Matrix.ScaleToFit#START}.
		 * From XML, use this syntax: <code>android:scaleType="fitStart"</code>.
		 */
		FIT_START(2),
		/**
		 * Scale the image using {@link Matrix.ScaleToFit#CENTER}.
		 * From XML, use this syntax:
		 * <code>android:scaleType="fitCenter"</code>.
		 */
		FIT_CENTER(3),
		/**
		 * Scale the image using {@link Matrix.ScaleToFit#END}.
		 * From XML, use this syntax: <code>android:scaleType="fitEnd"</code>.
		 */
		FIT_END(4),
		/**
		 * Center the image in the view, but perform no scaling.
		 * From XML, use this syntax: <code>android:scaleType="center"</code>.
		 */
		CENTER(5),
		/**
		 * Scale the image uniformly (maintain the image's aspect ratio) so
		 * that both dimensions (width and height) of the image will be equal
		 * to or larger than the corresponding dimension of the view
		 * (minus padding). The image is then centered in the view.
		 * From XML, use this syntax: <code>android:scaleType="centerCrop"</code>.
		 */
		CENTER_CROP(6),
		/**
		 * Scale the image uniformly (maintain the image's aspect ratio) so
		 * that both dimensions (width and height) of the image will be equal
		 * to or less than the corresponding dimension of the view
		 * (minus padding). The image is then centered in the view.
		 * From XML, use this syntax: <code>android:scaleType="centerInside"</code>.
		 */
		CENTER_INSIDE(7);

		ScaleType(int ni) {
			nativeInt = ni;
		}
		final int nativeInt;
	}
	private final ScaleType[] scaletype_from_int = {
		ScaleType.MATRIX,
		ScaleType.FIT_XY,
		ScaleType.FIT_START,
		ScaleType.FIT_CENTER,
		ScaleType.FIT_END,
		ScaleType.CENTER,
		ScaleType.CENTER_CROP,
		ScaleType.CENTER_INSIDE,
	};

	public final void setColorFilter(int color, PorterDuff.Mode mode) {
		setColorFilter(new PorterDuffColorFilter(color, mode));
	}

	/** AOSP: the image's tint list, resolved against the drawable state (pressed, selected, ...). */
	public void setImageTintList(ColorStateList tint) {
		tlDrawableTintList = tint;
		tlHasDrawableTint = true;
		tlRefreshImage();
	}

	public void setImageAlpha(int alpha) {
		alpha &= 0xFF;
		if (tlImageAlpha != alpha) {
			tlImageAlpha = alpha;
			tlColorMod = true;
			tlRefreshImage();
		}
	}

	public void setMaxWidth(int width) {}

	public void setMaxHeight(int height) {}

	public void setImageState(int[] state, boolean merge) {
		tlImageState = state;
		tlMergeImageState = merge;
		if (drawable != null)
			refreshDrawableState();
	}

	public ColorStateList getImageTintList() {
		return tlDrawableTintList;
	}

	public PorterDuff.Mode getImageTintMode() {
		return tlDrawableTintMode;
	}

	public void setImageTintMode(PorterDuff.Mode tintMode) {
		tlDrawableTintMode = tintMode;
		tlHasDrawableTintMode = true;
		tlRefreshImage();
	}

	public void setCropToPadding(boolean crop) {}

	/** AOSP: SRC_ATOP, the colour over the image's opaque pixels */
	public void setColorFilter(int color) {
		setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
	}

	public void setColorFilter(ColorFilter cf) {
		if (tlColorFilter != cf) {
			tlColorFilter = cf;
			tlHasColorFilter = true;
			tlColorMod = true;
			tlRefreshImage();
		}
	}

	public Matrix getImageMatrix() {
		return Matrix.IDENTITY_MATRIX;
	}

	@Override
	protected native long native_constructor(Context context, AttributeSet attrs);
	protected native void native_setDrawable(long widget, long paintable);
	protected native void native_setScaleType(long widget, int scale_type);

	public final void clearColorFilter() {
		setColorFilter((android.graphics.ColorFilter) null);
	}

	/* AOSP ImageView drawable tint, colour filter, image alpha and image state (frameworks/base
	 * ImageView.java): kept on the view and applied to every drawable it shows, mutated first so a
	 * drawable shared through its constant state keeps its own colours. Icon colours in apps come
	 * from these (app:tint, setImageTintList, setColorFilter). */
	private android.content.res.ColorStateList tlDrawableTintList;
	private android.graphics.PorterDuff.Mode tlDrawableTintMode;
	private boolean tlHasDrawableTint;
	private boolean tlHasDrawableTintMode;
	private android.graphics.ColorFilter tlColorFilter;
	private boolean tlHasColorFilter;
	private boolean tlColorMod;
	private int tlImageAlpha = 255;
	private int[] tlImageState;
	private boolean tlMergeImageState;

	/** AOSP applyImageTint() */
	private void tlApplyImageTint() {
		if (drawable == null || !(tlHasDrawableTint || tlHasDrawableTintMode))
			return;
		drawable = drawable.mutate();
		if (tlHasDrawableTint)
			drawable.setTintList(tlDrawableTintList);
		if (tlHasDrawableTintMode)
			drawable.setTintMode(tlDrawableTintMode);
		// the tint list resolves against the drawable state
		if (drawable.isStateful())
			drawable.setState(getDrawableState());
	}

	/** AOSP applyColorMod(): the colour filter and the image alpha */
	private void tlApplyColorMod() {
		if (drawable == null || !tlColorMod)
			return;
		drawable = drawable.mutate();
		if (tlHasColorFilter)
			drawable.setColorFilter(tlColorFilter);
		drawable.setAlpha(tlImageAlpha);
	}

	/** re-applies the view's tint/filter (they may mutate the drawable into a new object) and shows the result */
	private void tlRefreshImage() {
		final Drawable before = drawable;
		tlApplyImageTint();
		tlApplyColorMod();
		if (drawable != before) {
			if (before != null)
				before.setCallback(null);
			drawable.setCallback(this);
			native_setDrawable(widget, drawable.paintable);
		}
		invalidate();
	}

	public void setImageTintBlendMode(android.graphics.BlendMode blendMode) {
		setImageTintMode(android.graphics.BlendMode.blendModeToPorterDuffMode(blendMode));
	}

	public android.graphics.ColorFilter getColorFilter() {
		return tlColorFilter;
	}

	public int getImageAlpha() {
		return tlImageAlpha;
	}

	/** AOSP: the image state replaces (merge=false) or extends the view's drawable state */
	@Override
	public int[] onCreateDrawableState(int extraSpace) {
		if (tlImageState == null)
			return super.onCreateDrawableState(extraSpace);
		if (!tlMergeImageState)
			return tlImageState;
		return mergeDrawableStates(super.onCreateDrawableState(extraSpace + tlImageState.length), tlImageState);
	}

	/** AOSP: a stateful image follows the view's state (pressed, selected, checked, ...) */
	@Override
	protected void drawableStateChanged() {
		super.drawableStateChanged();
		final Drawable d = drawable;
		if (d != null && d.isStateful() && d.setState(getDrawableState()))
			invalidateDrawable(d);
	}
}
