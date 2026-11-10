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
		/* teslalinux (+text): AOSP measuring attributes */
		mAdjustViewBounds = a.getBoolean(com.android.internal.R.styleable.ImageView_adjustViewBounds, false);
		if (mAdjustViewBounds && !a.hasValue(com.android.internal.R.styleable.ImageView_scaleType))
			setScaleType(ScaleType.FIT_CENTER); /* AOSP setAdjustViewBounds(true) */
		mMaxWidth = a.getDimensionPixelSize(com.android.internal.R.styleable.ImageView_maxWidth, Integer.MAX_VALUE);
		mMaxHeight = a.getDimensionPixelSize(com.android.internal.R.styleable.ImageView_maxHeight, Integer.MAX_VALUE);
		mBaseline = a.getDimensionPixelSize(com.android.internal.R.styleable.ImageView_baseline, -1);
		mBaselineAlignBottom = a.getBoolean(com.android.internal.R.styleable.ImageView_baselineAlignBottom, false);
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
	/** AOSP: keep the drawable's aspect ratio when measuring; implies FIT_CENTER (teslalinux +text) */
	public void setAdjustViewBounds(boolean adjustViewBounds) {
		mAdjustViewBounds = adjustViewBounds;
		if (adjustViewBounds)
			setScaleType(ScaleType.FIT_CENTER);
	}

	/** AOSP: a new scale type changes how the image is laid out and drawn (requestLayout + invalidate) */
	public void setScaleType(ScaleType scaleType) {
		if (scaleType == null)
			throw new NullPointerException();
		final boolean changed = this.scaleType != scaleType;
		this.scaleType = scaleType;
		if (changed)
			mTlAnimActive = false;
		/* always told to the widget: its own default is not necessarily the field's initial value */
		native_setScaleType(widget, scaleType.nativeInt);
		if (changed) {
			requestLayout();
			invalidate();
		}
	}

	public ScaleType getScaleType() {
		return scaleType;
	}

	public Drawable getDrawable() {
		return drawable;
	}

	/** AOSP updateDrawable(): the new drawable takes the view's state, tint, colour filter and alpha.
	 * As in AOSP setImageDrawable, a different intrinsic size asks for a new layout and the view is
	 * invalidated: a parent that records its children's drawing (Compose's AndroidViewHolder, which
	 * hosts Waze's compass) only re-records on that invalidation, so the new image showed only after
	 * some unrelated redraw (the compass needle stayed blank). */
	public void setImageDrawable(Drawable drawable) {
		final int oldWidth = this.drawable != null ? this.drawable.getIntrinsicWidth() : -1;
		final int oldHeight = this.drawable != null ? this.drawable.getIntrinsicHeight() : -1;
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
		mTlAnimActive = false; /* AOSP configureBounds replaces an animateTransform matrix */
		native_setDrawable(widget, this.drawable != null ? this.drawable.paintable : 0);
		final int newWidth = this.drawable != null ? this.drawable.getIntrinsicWidth() : -1;
		final int newHeight = this.drawable != null ? this.drawable.getIntrinsicHeight() : -1;
		if (oldWidth != newWidth || oldHeight != newHeight)
			requestLayout();
		invalidate();
	}

	/** AOSP: the matrix a MATRIX scale type draws the image with (teslalinux +text) */
	public void setImageMatrix(Matrix matrix) {
		if (matrix != null && matrix.isIdentity())
			matrix = null;
		if ((matrix == null && !mTlMatrix.isIdentity()) || (matrix != null && !mTlMatrix.equals(matrix))) {
			mTlMatrix.set(matrix);
			/* the native image paintable draws a MATRIX scale type through it (native-imageview-scaletype.sh) */
			float[] values = null;
			if (matrix != null) {
				values = new float[9];
				matrix.getValues(values);
			}
			mTlAnimActive = false; /* AOSP configureBounds replaces an animateTransform matrix */
			native_setImageMatrix(widget, values);
			invalidate();
		}
	}

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

	/** AOSP: an upper bound for the measured size (with adjustViewBounds) (teslalinux +text) */
	public void setMaxWidth(int maxWidth) {
		mMaxWidth = maxWidth;
		requestLayout();
	}

	public void setMaxHeight(int maxHeight) {
		mMaxHeight = maxHeight;
		requestLayout();
	}

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

	/** AOSP: whether the image is clipped to the padding (teslalinux +text) */
	public void setCropToPadding(boolean crop) {
		if (mCropToPaddingFlag != crop) {
			mCropToPaddingFlag = crop;
			native_setCropToPadding(widget, crop);
			requestLayout();
			invalidate();
		}
	}

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

	/* teslalinux (+text): getImageMatrix is in ImageView.java+text.frag */

	@Override
	protected native long native_constructor(Context context, AttributeSet attrs);
	protected native void native_setDrawable(long widget, long paintable);
	protected native void native_setScaleType(long widget, int scale_type);

	public final void clearColorFilter() {
		setColorFilter((android.graphics.ColorFilter) null);
	}

	/* ==== teslalinux (+text): AOSP ImageView measuring, URI/level images, baseline ==== */
	private boolean mAdjustViewBounds;
	private int mMaxWidth = Integer.MAX_VALUE;
	private int mMaxHeight = Integer.MAX_VALUE;
	private int mBaseline = -1;
	private boolean mBaselineAlignBottom;
	private boolean mCropToPaddingFlag;
	private int mLevel;
	private android.net.Uri mUri;
	private final Matrix mTlMatrix = new Matrix();

	public boolean getAdjustViewBounds() {
		return mAdjustViewBounds;
	}

	public int getMaxWidth() {
		return mMaxWidth;
	}

	public int getMaxHeight() {
		return mMaxHeight;
	}

	/** AOSP onMeasure: the drawable's intrinsic size plus padding (adjustViewBounds, maxWidth and
	 * maxHeight applied). Always in Java: GtkPicture measures its paintable, and paintables without an
	 * intrinsic size (NinePatchPaintable) measured as anything but the drawable's size (Waze's alert
	 * card drew its 8 px route track as a 69 px black block). */
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int w, h;
		float desiredAspect = 0.0f;
		boolean resizeWidth = false, resizeHeight = false;
		final int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
		final int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
		final Drawable d = getDrawable();
		if (d == null) {
			w = h = 0;
		} else {
			w = d.getIntrinsicWidth();
			h = d.getIntrinsicHeight();
			if (w <= 0)
				w = 1;
			if (h <= 0)
				h = 1;
			if (mAdjustViewBounds) {
				resizeWidth = widthSpecMode != MeasureSpec.EXACTLY;
				resizeHeight = heightSpecMode != MeasureSpec.EXACTLY;
				desiredAspect = (float) w / (float) h;
			}
		}
		final int pleft = getPaddingLeft(), pright = getPaddingRight(), ptop = getPaddingTop(), pbottom = getPaddingBottom();
		int widthSize, heightSize;
		if (resizeWidth || resizeHeight) {
			widthSize = resolveAdjustedSize(w + pleft + pright, mMaxWidth, widthMeasureSpec);
			heightSize = resolveAdjustedSize(h + ptop + pbottom, mMaxHeight, heightMeasureSpec);
			if (desiredAspect != 0.0f) {
				final float actualAspect = (float) (widthSize - pleft - pright) / (heightSize - ptop - pbottom);
				if (Math.abs(actualAspect - desiredAspect) > 0.0000001) {
					boolean done = false;
					if (resizeWidth) {
						int newWidth = (int) (desiredAspect * (heightSize - ptop - pbottom)) + pleft + pright;
						if (!resizeHeight)
							widthSize = resolveAdjustedSize(newWidth, mMaxWidth, widthMeasureSpec);
						if (newWidth <= widthSize) {
							widthSize = newWidth;
							done = true;
						}
					}
					if (!done && resizeHeight) {
						int newHeight = (int) ((widthSize - pleft - pright) / desiredAspect) + ptop + pbottom;
						if (!resizeWidth)
							heightSize = resolveAdjustedSize(newHeight, mMaxHeight, heightMeasureSpec);
						if (newHeight <= heightSize)
							heightSize = newHeight;
					}
				}
			}
		} else {
			w += pleft + pright;
			h += ptop + pbottom;
			w = Math.max(w, getSuggestedMinimumWidth());
			h = Math.max(h, getSuggestedMinimumHeight());
			widthSize = resolveSizeAndState(w, widthMeasureSpec, 0);
			heightSize = resolveSizeAndState(h, heightMeasureSpec, 0);
		}
		setMeasuredDimension(widthSize, heightSize);
	}

	private int resolveAdjustedSize(int desiredSize, int maxSize, int measureSpec) {
		int result = desiredSize;
		final int specMode = MeasureSpec.getMode(measureSpec);
		final int specSize = MeasureSpec.getSize(measureSpec);
		switch (specMode) {
			case MeasureSpec.UNSPECIFIED:
				result = Math.min(desiredSize, maxSize);
				break;
			case MeasureSpec.AT_MOST:
				result = Math.min(Math.min(desiredSize, specSize), maxSize);
				break;
			case MeasureSpec.EXACTLY:
				result = specSize;
				break;
		}
		return result;
	}

	/** AOSP: an image from a URI (android.resource, content, file), read through the ContentResolver */
	public void setImageURI(android.net.Uri uri) {
		mUri = uri;
		if (uri == null) {
			setImageDrawable(null);
			return;
		}
		Drawable d = null;
		final String scheme = uri.getScheme();
		try {
			if ("android.resource".equals(scheme)) {
				final java.util.List<String> segs = uri.getPathSegments();
				final android.content.res.Resources res = getContext().getResources();
				int id = 0;
				if (segs.size() == 1) {
					id = Integer.parseInt(segs.get(0));
				} else if (segs.size() == 2) {
					id = res.getIdentifier(segs.get(1), segs.get(0), uri.getAuthority());
				}
				if (id != 0)
					d = getContext().getDrawable(id);
			} else {
				java.io.InputStream is = (scheme == null || "file".equals(scheme))
					? new java.io.FileInputStream(uri.getPath())
					: getContext().getContentResolver().openInputStream(uri);
				try {
					d = Drawable.createFromResourceStream(getResources(), null, is, uri.toString(), null);
				} finally {
					is.close();
				}
			}
		} catch (Exception e) {
			android.util.Log.w("ImageView", "Unable to open content: " + uri, e);
		}
		setImageDrawable(d);
	}

	/** AOSP: the image drawable's level (LevelListDrawable, ClipDrawable, ...) */
	public void setImageLevel(int level) {
		mLevel = level;
		final Drawable d = getDrawable();
		if (d != null) {
			d.setLevel(level);
			requestLayout();
			invalidate();
		}
	}

	/** AOSP: the matrix the image is drawn with (configureBounds' mDrawMatrix), identity when none */
	public Matrix getImageMatrix() {
		final Matrix m = tlDrawMatrix();
		return m != null ? m : new Matrix(Matrix.IDENTITY_MATRIX);
	}

	/** set by animateTransform until the next configureBounds (a new drawable, scale type, image matrix or size) */
	private Matrix mTlAnimMatrix;
	private boolean mTlAnimActive;
	private int mTlAnimW, mTlAnimH;

	/**
	 * AOSP ImageView.configureBounds: the drawable at its intrinsic size through this matrix (null:
	 * the drawable fills the content box). The native image paintable draws the same placement
	 * (native-imageview-scaletype.sh).
	 */
	private Matrix tlDrawMatrix() {
		final android.graphics.drawable.Drawable d = getDrawable();
		if (d == null)
			return null;
		final int vwidth = getWidth() - getPaddingLeft() - getPaddingRight();
		final int vheight = getHeight() - getPaddingTop() - getPaddingBottom();
		if (mTlAnimActive) {
			if (mTlAnimW == vwidth && mTlAnimH == vheight)
				return mTlAnimMatrix;
			mTlAnimActive = false;
		}
		final int dwidth = d.getIntrinsicWidth();
		final int dheight = d.getIntrinsicHeight();
		final ScaleType st = getScaleType();
		if (dwidth <= 0 || dheight <= 0 || st == ScaleType.FIT_XY)
			return null;
		if (st == ScaleType.MATRIX)
			return mTlMatrix.isIdentity() ? null : mTlMatrix;
		if (vwidth == dwidth && vheight == dheight)
			return null;
		final Matrix m = new Matrix();
		if (st == ScaleType.CENTER) {
			m.setTranslate(Math.round((vwidth - dwidth) * 0.5f), Math.round((vheight - dheight) * 0.5f));
		} else if (st == ScaleType.CENTER_CROP) {
			float scale, dx = 0, dy = 0;
			if (dwidth * vheight > vwidth * dheight) {
				scale = (float) vheight / (float) dheight;
				dx = (vwidth - dwidth * scale) * 0.5f;
			} else {
				scale = (float) vwidth / (float) dwidth;
				dy = (vheight - dheight * scale) * 0.5f;
			}
			m.setScale(scale, scale);
			m.postTranslate(Math.round(dx), Math.round(dy));
		} else if (st == ScaleType.CENTER_INSIDE) {
			float scale;
			if (dwidth <= vwidth && dheight <= vheight)
				scale = 1.0f;
			else
				scale = Math.min((float) vwidth / (float) dwidth, (float) vheight / (float) dheight);
			m.setScale(scale, scale);
			m.postTranslate(Math.round((vwidth - dwidth * scale) * 0.5f), Math.round((vheight - dheight * scale) * 0.5f));
		} else {
			final Matrix.ScaleToFit fit = st == ScaleType.FIT_START ? Matrix.ScaleToFit.START
			                              : st == ScaleType.FIT_END ? Matrix.ScaleToFit.END : Matrix.ScaleToFit.CENTER;
			m.setRectToRect(new android.graphics.RectF(0, 0, dwidth, dheight), new android.graphics.RectF(0, 0, vwidth, vheight), fit);
		}
		return m;
	}

	/** AOSP (hidden, androidx.transition ImageViewUtils): the draw matrix during a transition, until
	 * the next configureBounds; null fills the content box with the drawable */
	public void animateTransform(Matrix matrix) {
		if (getDrawable() == null)
			return;
		float[] values = null;
		if (matrix != null) {
			values = new float[9];
			matrix.getValues(values);
		}
		mTlAnimMatrix = matrix != null ? new Matrix(matrix) : null;
		mTlAnimActive = true;
		mTlAnimW = getWidth() - getPaddingLeft() - getPaddingRight();
		mTlAnimH = getHeight() - getPaddingTop() - getPaddingBottom();
		native_animateTransform(widget, values);
		invalidate();
	}

	protected native void native_setImageMatrix(long widget, float[] values);
	protected native void native_setCropToPadding(long widget, boolean crop);
	protected native void native_animateTransform(long widget, float[] values);

	public boolean getCropToPadding() {
		return mCropToPaddingFlag;
	}

	public void setBaseline(int baseline) {
		if (mBaseline != baseline) {
			mBaseline = baseline;
			requestLayout();
		}
	}

	public void setBaselineAlignBottom(boolean aligned) {
		if (mBaselineAlignBottom != aligned) {
			mBaselineAlignBottom = aligned;
			requestLayout();
		}
	}

	public boolean getBaselineAlignBottom() {
		return mBaselineAlignBottom;
	}

	@Override
	public int getBaseline() {
		if (mBaselineAlignBottom)
			return getMeasuredHeight();
		return mBaseline;
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

	/** AOSP: the image drawable is one of this view's own drawables (invalidateDrawable redraws for it) */
	@Override
	protected boolean verifyDrawable(Drawable dr) {
		return drawable == dr || super.verifyDrawable(dr);
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
