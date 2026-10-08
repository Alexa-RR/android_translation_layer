/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics.drawable;

import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Gravity;
import com.android.internal.R;
import java.io.IOException;
import java.io.InputStream;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * A Drawable that wraps a bitmap and can be tiled, stretched, or aligned (AOSP API). The pixels are
 * shown through ATL's paintable mechanism (the bitmap's GdkTexture becomes the drawable's paintable);
 * the AOSP state (gravity, target density, tile modes, paint flags, mipmap, auto-mirroring) is kept
 * and reported exactly like the framework does, and the gravity-based destination rectangle is what
 * {@link #draw(Canvas)} uses when the drawable is drawn onto a Canvas.
 */
public class BitmapDrawable extends Drawable {
	private static final int DEFAULT_PAINT_FLAGS = Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG;

	private Bitmap bitmap;
	private final Paint mPaint = new Paint(DEFAULT_PAINT_FLAGS);
	private int mGravity = Gravity.FILL;
	private int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;
	private int mBitmapWidth = -1;
	private int mBitmapHeight = -1;
	private Shader.TileMode mTileModeX;
	private Shader.TileMode mTileModeY;
	private boolean mAutoMirrored;
	private boolean mMipMap;
	private boolean mDstRectAndInsetsDirty = true;
	private final Rect mDstRect = new Rect();

	/** @deprecated Use {@link #BitmapDrawable(Resources, Bitmap)} to ensure that the drawable has correctly set its target density. */
	@Deprecated
	public BitmapDrawable() {
	}

	public BitmapDrawable(Resources res, Bitmap bitmap) {
		setTargetDensityFromResources(res);
		setBitmapInternal(bitmap);
	}

	/** @deprecated Use {@link #BitmapDrawable(Resources, Bitmap)} to ensure that the drawable has correctly set its target density. */
	@Deprecated
	public BitmapDrawable(Bitmap bitmap) {
		setBitmapInternal(bitmap);
	}

	/** Create a drawable by opening a given file path and decoding the bitmap. */
	public BitmapDrawable(Resources res, String filepath) {
		setTargetDensityFromResources(res);
		Bitmap b = BitmapFactory.decodeFile(filepath);
		if (b == null)
			android.util.Log.w("BitmapDrawable", "BitmapDrawable cannot decode " + filepath);
		setBitmapInternal(b);
	}

	/** @deprecated Use {@link #BitmapDrawable(Resources, String)} to ensure that the drawable has correctly set its target density. */
	@Deprecated
	public BitmapDrawable(String filepath) {
		this((Resources) null, filepath);
	}

	/** Create a drawable by decoding a bitmap from the given input stream. */
	public BitmapDrawable(Resources res, InputStream is) {
		setTargetDensityFromResources(res);
		Bitmap b = BitmapFactory.decodeStream(is);
		if (b == null)
			android.util.Log.w("BitmapDrawable", "BitmapDrawable cannot decode " + is);
		setBitmapInternal(b);
	}

	/** @deprecated Use {@link #BitmapDrawable(Resources, InputStream)} to ensure that the drawable has correctly set its target density. */
	@Deprecated
	public BitmapDrawable(InputStream is) {
		this((Resources) null, is);
	}

	private void setTargetDensityFromResources(Resources res) {
		if (res != null)
			mTargetDensity = res.getDisplayMetrics().densityDpi;
	}

	private void setBitmapInternal(Bitmap bitmap) {
		if (bitmap != this.bitmap) {
			this.bitmap = bitmap;
			computeBitmapSize();
			if (bitmap != null)
				setPaintable(bitmap.getTexture());
			invalidateSelf();
		}
	}

	private void computeBitmapSize() {
		if (bitmap != null) {
			mBitmapWidth = bitmap.getScaledWidth(mTargetDensity);
			mBitmapHeight = bitmap.getScaledHeight(mTargetDensity);
		} else {
			mBitmapWidth = mBitmapHeight = -1;
		}
		mDstRectAndInsetsDirty = true;
	}

	/** Returns the paint used to render this drawable. */
	public final Paint getPaint() {
		return mPaint;
	}

	/** Returns the bitmap used by this drawable to render. May be null. */
	public final Bitmap getBitmap() {
		return bitmap;
	}

	/** Switch to a new Bitmap object. */
	public void setBitmap(Bitmap bitmap) {
		setBitmapInternal(bitmap);
	}

	/** Set the density scale at which this drawable will be rendered. */
	public void setTargetDensity(Canvas canvas) {
		setTargetDensity(canvas.getDensity());
	}

	/** Set the density scale at which this drawable will be rendered. */
	public void setTargetDensity(DisplayMetrics metrics) {
		setTargetDensity(metrics.densityDpi);
	}

	/** Set the density at which this drawable will be rendered. */
	public void setTargetDensity(int density) {
		if (mTargetDensity != density) {
			mTargetDensity = density == 0 ? DisplayMetrics.DENSITY_DEFAULT : density;
			computeBitmapSize();
			invalidateSelf();
		}
	}

	/** Get the gravity used to position/stretch the bitmap within its bounds. */
	public int getGravity() {
		return mGravity;
	}

	/** Set the gravity used to position/stretch the bitmap within its bounds. */
	public void setGravity(int gravity) {
		if (mGravity != gravity) {
			mGravity = gravity;
			mDstRectAndInsetsDirty = true;
			invalidateSelf();
		}
	}

	/** Enables or disables the mipmap hint for this drawable's bitmap. */
	public void setMipMap(boolean mipMap) {
		mMipMap = mipMap;
		invalidateSelf();
	}

	public boolean hasMipMap() {
		return mMipMap;
	}

	/** Enables or disables anti-aliasing for this drawable. */
	public void setAntiAlias(boolean aa) {
		mPaint.setAntiAlias(aa);
		invalidateSelf();
	}

	public boolean hasAntiAlias() {
		return mPaint.isAntiAlias();
	}

	@Override
	public void setFilterBitmap(boolean filter) {
		mPaint.setFilterBitmap(filter);
		invalidateSelf();
	}

	@Override
	public boolean isFilterBitmap() {
		return mPaint.isFilterBitmap();
	}

	@Override
	public void setDither(boolean dither) {
		mPaint.setDither(dither);
		invalidateSelf();
	}

	/** Indicates the repeat behavior of this drawable on the X axis. */
	public Shader.TileMode getTileModeX() {
		return mTileModeX;
	}

	/** Indicates the repeat behavior of this drawable on the Y axis. */
	public Shader.TileMode getTileModeY() {
		return mTileModeY;
	}

	public void setTileModeX(Shader.TileMode mode) {
		setTileModeXY(mode, mTileModeY);
	}

	public final void setTileModeY(Shader.TileMode mode) {
		setTileModeXY(mTileModeX, mode);
	}

	/** Sets the repeat behavior of this drawable on both axis. */
	public void setTileModeXY(Shader.TileMode xmode, Shader.TileMode ymode) {
		if (mTileModeX != xmode || mTileModeY != ymode) {
			mTileModeX = xmode;
			mTileModeY = ymode;
			mDstRectAndInsetsDirty = true;
			invalidateSelf();
		}
	}

	@Override
	public void setAutoMirrored(boolean mirrored) {
		if (mAutoMirrored != mirrored) {
			mAutoMirrored = mirrored;
			invalidateSelf();
		}
	}

	@Override
	public final boolean isAutoMirrored() {
		return mAutoMirrored;
	}

	private boolean needMirroring() {
		return isAutoMirrored() && getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL;
	}

	@Override
	protected void onBoundsChange(Rect bounds) {
		mDstRectAndInsetsDirty = true;
	}

	private void updateDstRectAndInsetsIfDirty() {
		if (mDstRectAndInsetsDirty) {
			if (mTileModeX == null && mTileModeY == null) {
				final Rect bounds = getBounds();
				final int layoutDirection = getLayoutDirection();
				Gravity.apply(mGravity, mBitmapWidth, mBitmapHeight, bounds, mDstRect, layoutDirection);
			} else {
				copyBounds(mDstRect);
			}
		}
		mDstRectAndInsetsDirty = false;
	}

	@Override
	public void draw(Canvas canvas) {
		final Bitmap b = bitmap;
		if (b == null)
			return;
		updateDstRectAndInsetsIfDirty();
		final boolean needMirroring = needMirroring();
		if (needMirroring) {
			canvas.save();
			canvas.translate(mDstRect.right - mDstRect.left, 0);
			canvas.scale(-1.0f, 1.0f);
		}
		canvas.drawBitmap(b, null, mDstRect, mPaint);
		if (needMirroring)
			canvas.restore();
	}

	@Override
	public void setAlpha(int alpha) {
		final int oldAlpha = mPaint.getAlpha();
		if (alpha != oldAlpha) {
			mPaint.setAlpha(alpha);
			invalidateSelf();
		}
	}

	@Override
	public int getAlpha() {
		return mPaint.getAlpha();
	}

	@Override
	public void setColorFilter(ColorFilter colorFilter) {
		mPaint.setColorFilter(colorFilter);
		invalidateSelf();
	}

	@Override
	public ColorFilter getColorFilter() {
		return mPaint.getColorFilter();
	}

	@Override
	public int getOpacity() {
		if (mGravity != Gravity.FILL)
			return PixelFormat.TRANSLUCENT;
		final Bitmap b = bitmap;
		return (b == null || b.hasAlpha() || mPaint.getAlpha() < 255) ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
	}

	@Override
	public int getIntrinsicWidth() {
		return mBitmapWidth;
	}

	@Override
	public int getIntrinsicHeight() {
		return mBitmapHeight;
	}

	public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
	    throws XmlPullParserException, IOException {
		final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.BitmapDrawable);
		setTargetDensityFromResources(r);
		Bitmap b = null;
		if (a.hasValue(R.styleable.BitmapDrawable_src)) {
			BitmapDrawable dr = null;
			try {
				// TODO: getDrawable() might pick up another <bitmap> XML,
				// but we should reject anything other than image files.
				dr = (BitmapDrawable)a.getDrawable(R.styleable.BitmapDrawable_src);
			} catch (java.lang.Exception e) {
				e.printStackTrace();
			}
			if (dr != null)
				b = dr.bitmap;
		}
		a.recycle();
		if (b == null)
			throw new XmlPullParserException("<bitmap> needs a valid `src' attribute");
		setBitmapInternal(b);
	}
}
