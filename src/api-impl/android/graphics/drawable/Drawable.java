package android.graphics.drawable;

import android.atl.GskCanvas;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LayoutDirection;
import android.util.TypedValue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public class Drawable {
	public static interface Callback {
		public void invalidateDrawable(Drawable drawable);
		public void scheduleDrawable(Drawable drawable, Runnable runnable, long time);
		public void unscheduleDrawable(Drawable drawable, Runnable runnable);
	}

	static final BlendMode DEFAULT_BLEND_MODE = BlendMode.SRC_IN;
	static final PorterDuff.Mode DEFAULT_TINT_MODE = PorterDuff.Mode.SRC_IN;

	private Rect mBounds = new Rect();
	private int[] mStateSet = new int[0];
	public long paintable;

	private Callback callback = null;

	public Drawable() {
		this.paintable = native_constructor();
	}

	public Drawable(long paintable) {
		native_ref(paintable);
		this.paintable = paintable;
	}

	protected void setPaintable(long paintable) {
		if (this.paintable != 0)
			native_unref(this.paintable);
		if (paintable != 0)
			native_ref(paintable);
		this.paintable = paintable;
	}

	public int getChangingConfigurations() {
		return 0;
	}

	public void setChangingConfigurations(int bitmap) {}

	public ConstantState getConstantState() {
		return new ConstantState() {
			@Override
			public Drawable newDrawable(Resources res) {
				return Drawable.this;
			}

			@Override
			public Drawable newDrawable() {
				return Drawable.this;
			}

			@Override
			public int getChangingConfigurations() {
				return Drawable.this.getChangingConfigurations();
			}
		};
	}

	public static abstract class ConstantState {

		public abstract Drawable newDrawable(Resources res);

		public abstract Drawable newDrawable();

		public Drawable newDrawable(Resources res, Theme theme) {
			return newDrawable(res);
		}

		public abstract int getChangingConfigurations();
	
		/** AOSP: whether this constant state has theme attributes to apply. */
		public boolean canApplyTheme() {
			return false;
		}
}

	public void setBounds(int left, int top, int right, int bottom) {
		boolean changed = left != mBounds.left || top != mBounds.top || right != mBounds.right || bottom != mBounds.bottom;
		mBounds.set(left, top, right, bottom);
		if (changed)
			onBoundsChange(mBounds);
	}

	public void setBounds(Rect bounds) {
		setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom);
	}

	public final Rect getBounds() {
		return mBounds;
	}

	/**
	 * Draws the paintable behind this drawable; the colour filter (or the tint filter derived from
	 * the tint list and the current state, AOSP) is applied as a GSK colour matrix around it.
	 */
	public void draw(Canvas canvas) {
		if (canvas instanceof GskCanvas) {
			GskCanvas gsk = (GskCanvas) canvas;
			ColorFilter filter = mColorFilter != null ? mColorFilter : mTintFilter;
			boolean pushed = filter != null && gsk.tlPushColorFilter(filter);
			if (mBounds.left != 0 || mBounds.top != 0)
				canvas.translate(mBounds.left, mBounds.top);
			native_draw(paintable, gsk.snapshot, mBounds.width(), mBounds.height());
			if (mBounds.left != 0 || mBounds.top != 0)
				canvas.translate(-mBounds.left, -mBounds.top);
			if (pushed)
				gsk.tlPopGroup();
		}
	}

	public boolean setState(int[] stateSet) {
		if (!Arrays.equals(this.mStateSet, stateSet)) {
			this.mStateSet = stateSet;
			if (mTint != null)
				mTintFilter = updateTintFilter(mTintFilter, mTint, mTintMode);
			return onStateChange(stateSet);
		}
		return false;
	}

	public int[] getState() {
		return mStateSet;
	}

	public void setCallback(Callback callback) {
		this.callback = callback;
	}

	public Callback getCallback() {
		return this.callback;
	}

	public void invalidateSelf() {
		native_invalidate(paintable);

		/* this shouldn't ever be needed with Gtk, but let's play it safe for now */
		if (this.callback != null) {
			callback.invalidateDrawable(this);
		}
	}

	public void scheduleSelf(Runnable runnable, long time) {
		if (this.callback != null) {
			callback.scheduleDrawable(this, runnable, time);
		}
	}

	public void unscheduleSelf(Runnable runnable) {
		if (this.callback != null) {
			callback.unscheduleDrawable(this, runnable);
		}
	}

	public boolean isVisible() {
		return mVisible;
	}

	/** AOSP: sets the visibility; returns true when it changed (subclasses restart animations then). */
	public boolean setVisible(boolean visible, boolean restart) {
		boolean changed = mVisible != visible;
		if (changed) {
			mVisible = visible;
			invalidateSelf();
		}
		return changed;
	}

	protected static TypedArray obtainAttributes(Resources r, Theme theme, AttributeSet set, int[] attrs) {
		if (theme != null)
			return theme.obtainStyledAttributes(set, attrs, 0, 0);
		else
			return r.obtainAttributes(set, attrs);
	}

	public void clearColorFilter() {}

	public final int getLevel() { return 0; }
	public final boolean setLevel(int level) { return false; }

	public void setColorFilter(int color, PorterDuff.Mode mode) {
		setColorFilter(new PorterDuffColorFilter(color, mode));
	}
	/** AOSP: a colour filter applied when drawing; it takes precedence over the tint. */
	public void setColorFilter(ColorFilter filter) {
		if (mColorFilter != filter) {
			mColorFilter = filter;
			invalidateSelf();
		}
	}

	public Drawable mutate() {
		return this;
	}

	public int getIntrinsicWidth() { return -1; }
	public int getIntrinsicHeight() { return -1; }

	/** AOSP: tint colour list, resolved against the drawable state into a PorterDuff filter. */
	public void setTintList(ColorStateList tint) {
		mTint = tint;
		mTintFilter = updateTintFilter(mTintFilter, mTint, mTintMode);
		invalidateSelf();
	}

	public void setTint(int tint) {
		setTintList(ColorStateList.valueOf(tint));
	}

	public static BlendMode parseBlendMode(int value, BlendMode defaultMode) {
		return defaultMode;
	}

	/* Copyright (C) 2006 The Android Open Source Project */
	BlendModeColorFilter updateBlendModeFilter(BlendModeColorFilter blendFilter, ColorStateList tint, BlendMode blendMode) {
		if (tint == null || blendMode == null) {
			return null;
		}

		final int color = tint.getColorForState(getState(), Color.TRANSPARENT);
		if (blendFilter == null || blendFilter.getColor() != color
		    || blendFilter.getMode() != blendMode) {
			return new BlendModeColorFilter(color, blendMode);
		}
		return blendFilter;
	}

	/* Copyright (C) 2006 The Android Open Source Project */
	PorterDuffColorFilter updateTintFilter(PorterDuffColorFilter tintFilter, ColorStateList tint, PorterDuff.Mode tintMode) {
		if (tint == null || tintMode == null) {
			return null;
		}
		final int color = tint.getColorForState(getState(), Color.TRANSPARENT);
		if (tintFilter == null || tintFilter.getColor() != color || tintFilter.getMode() != tintMode) {
			return new PorterDuffColorFilter(color, tintMode);
		}
		return tintFilter;
	}

	public boolean isStateful() {
		return false;
	}

	public void setTintMode(PorterDuff.Mode tintMode) {
		mTintMode = tintMode;
		mTintFilter = updateTintFilter(mTintFilter, mTint, mTintMode);
		invalidateSelf();
	}

	public boolean isProjected() { return false; }

	public static Drawable createFromXml(Resources resources, XmlResourceParser parser) throws XmlPullParserException, IOException {
		return createFromXml(resources, parser, null);
	}

	public static Drawable createFromXml(Resources resources, XmlResourceParser parser, Theme theme) throws XmlPullParserException, IOException {
		int type;
		while ((type = parser.next()) != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT)
			;
		if (type != XmlPullParser.START_TAG)
			throw new XmlPullParserException("No start tag found");

		return createFromXmlInner(resources, parser, parser, theme);
	}

	public static Drawable createFromXmlInner(Resources resources, XmlPullParser parser, AttributeSet attrs) throws XmlPullParserException, IOException {
		return createFromXmlInner(resources, parser, attrs, null);
	}

	public static Drawable createFromXmlInner(Resources resources, XmlPullParser parser, AttributeSet attrs, Theme theme) throws XmlPullParserException, IOException {
		switch (parser.getName()) {
			case "selector": {
				StateListDrawable drawable = new StateListDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "shape": {
				GradientDrawable drawable = new GradientDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "bitmap": {
				BitmapDrawable drawable = new BitmapDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "transition": {
				TransitionDrawable drawable = new TransitionDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "ripple": {
				RippleDrawable drawable = new RippleDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "vector": {
				VectorDrawable drawable = new VectorDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "layer-list": {
				LayerDrawable drawable = new LayerDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "nine-patch": {
				return new NinePatchDrawable(resources, null, null, null, null);
			}
			case "animation-list": {
				AnimationDrawable drawable = new AnimationDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "adaptive-icon": {
				AdaptiveIconDrawable drawable = new AdaptiveIconDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "inset": {
				InsetDrawable drawable = new InsetDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			/* AOSP DrawableInflater.inflateFromTag: the remaining drawable tags */
			case "animated-selector": {
				AnimatedStateListDrawable drawable = new AnimatedStateListDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "level-list": {
				LevelListDrawable drawable = new LevelListDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "color": {
				ColorDrawable drawable = new ColorDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "animated-vector": {
				AnimatedVectorDrawable drawable = new AnimatedVectorDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "scale": {
				ScaleDrawable drawable = new ScaleDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "clip": {
				ClipDrawable drawable = new ClipDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "rotate": {
				RotateDrawable drawable = new RotateDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
			case "animated-rotate": {
				AnimatedRotateDrawable drawable = new AnimatedRotateDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
			}
		}

		/* AOSP DrawableInflater: a tag that is not in the table is a Drawable class name */
		Drawable drawable = tlInflateFromClass(resources, parser.getName(), parser);
		drawable.inflate(resources, parser, attrs, theme);
		return drawable;
	}

	public static Drawable createFromResourceStream(Resources resources, TypedValue value, InputStream is, String file,
	                                                Object object) {
		if (!file.endsWith(".9.png")) {
			final Bitmap bm = BitmapFactory.decodeStream(is);
			if (bm == null)
				return null;
			return new BitmapDrawable(resources, bm);
		}
		/* AOSP decodes the PNG with its npTc chunk and builds the NinePatchDrawable with the resource's
		 * density and the chunk's padding; GDK decodes the asset stream directly (no copy to storage). */
		int density = Bitmap.DENSITY_NONE;
		if (value != null) {
			if (value.density == TypedValue.DENSITY_DEFAULT)
				density = android.util.DisplayMetrics.DENSITY_DEFAULT;
			else if (value.density != TypedValue.DENSITY_NONE)
				density = value.density;
		}
		android.graphics.NinePatch patch = android.graphics.NinePatch.tlDecode(is, density, file);
		if (patch == null)
			return null;
		return new NinePatchDrawable(resources, patch);
	}

	public static Drawable createFromPath(String path) {
		if (path == null)
			return null;

		if (path.endsWith(".9.png")) {
			android.graphics.NinePatch patch = android.graphics.NinePatch.tlDecodeFile(path);
			return patch != null ? new NinePatchDrawable(null, patch) : null;
		}
		long paintable = native_paintable_from_path(path);
		return new Drawable(paintable);
	}

	protected boolean onStateChange(int[] stateSet) {
		return false;
	}

	public void setAlpha(int alpha) {}

	public boolean getPadding(Rect padding) {
		return false;
	}

	public void copyBounds(Rect bounds) {
		bounds.set(mBounds);
	}

	public int getMinimumWidth() {
		return 10; // FIXME
	}
	public int getMinimumHeight() {
		return 10; // FIXME
	}

	protected void onBoundsChange(Rect bounds) {}

	public void setDither(boolean dither) {}

	public void setAutoMirrored(boolean mirrored) {}

	public void jumpToCurrentState() {}

	public boolean setLayoutDirection(int layoutDirection) {
		return false;
	}

	public void setHotspot(float x, float y) {}

	public int getLayoutDirection() {
		return LayoutDirection.LTR;
	}

	static int resolveDensity(Resources r, int parentDensity) {
		final int densityDpi = r == null ? parentDensity : r.getDisplayMetrics().densityDpi;
		return densityDpi == 0 ? DisplayMetrics.DENSITY_DEFAULT : densityDpi;
	}

	public void setFilterBitmap(boolean filter) {}

	public void setHotspotBounds(int left, int top, int right, int bottom) {}

	@SuppressWarnings("removal")
	protected void finalize() throws Throwable {
		try {
			if (paintable != 0)
				native_unref(paintable);
			paintable = 0;
		} finally {
			super.finalize();
		}
	}

	protected static native long native_paintable_from_path(String path);
	protected native long native_constructor();
	protected native void native_invalidate(long paintable);
	protected native void native_draw(long paintable, long snapshot, int width, int height);
	protected native void native_ref(long paintable);
	protected native void native_unref(long paintable);


	/* teslalinux: Drawable additions */
	public int getAlpha() { return 255; }
	public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
	public boolean isAutoMirrored() { return false; }
	public Rect getDirtyBounds() { return getBounds(); }
	public void getOutline(android.graphics.Outline outline) {}
	public android.graphics.Insets getOpticalInsets() { return android.graphics.Insets.NONE; }
	public void applyTheme(android.content.res.Resources.Theme t) {}
	public boolean canApplyTheme() { return false; }
	public android.graphics.Region getTransparentRegion() { return null; }
	protected boolean onLevelChange(int level) { return false; }

	/** Inflate this Drawable from an XML resource (AOSP: reads android:visible). */
	public void inflate(android.content.res.Resources r, org.xmlpull.v1.XmlPullParser parser, android.util.AttributeSet attrs) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
		inflate(r, parser, attrs, null);
	}

	public void inflate(android.content.res.Resources r, org.xmlpull.v1.XmlPullParser parser, android.util.AttributeSet attrs, android.content.res.Resources.Theme theme) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
		if (attrs != null) {
			boolean visible = attrs.getAttributeBooleanValue("http://schemas.android.com/apk/res/android", "visible", true);
			setVisible(visible, false);
		}
	}
	/** AOSP Drawable base: no bitmap filtering unless a subclass says so. */
	public boolean isFilterBitmap() { return false; }
	/** AOSP Drawable base: no colour filter unless a subclass keeps one. */
	/* ---- teslalinux: AOSP tint and colour filter state ---- */
	private android.content.res.ColorStateList mTint;
	private android.graphics.PorterDuff.Mode mTintMode = android.graphics.PorterDuff.Mode.SRC_IN;
	private android.graphics.PorterDuffColorFilter mTintFilter;
	private android.graphics.ColorFilter mColorFilter;
	public android.graphics.ColorFilter getColorFilter() { return mColorFilter; }
	/** The filter a subclass should paint with: the explicit colour filter, else the tint filter (AOSP). */
	protected android.graphics.ColorFilter tlEffectiveColorFilter() { return mColorFilter != null ? mColorFilter : mTintFilter; }
	public android.content.res.ColorStateList tlGetTintList() { return mTint; }
	public android.graphics.PorterDuff.Mode tlGetTintMode() { return mTintMode; }
	/** AOSP: the drawable currently showing (the drawable itself unless a container overrides). */
	public Drawable getCurrent() { return this; }
	/** AOSP (hidden): wraps a checked exception from a drawable inflation helper. */
	static void rethrowAsRuntimeException(Exception e) {
		throw new RuntimeException(e);
	}
	/* AOSP Drawable defaults (API 23-29) */
	/** AOSP: called when the resolved layout direction changes; true when the drawable changed because of it. */
	public boolean onLayoutDirectionChanged(int layoutDirection) {
		return false;
	}

	/** AOSP (API 29): a tint blend mode; the default maps it to the PorterDuff mode. */
	public void setTintBlendMode(android.graphics.BlendMode blendMode) {
		setTintMode(android.graphics.BlendMode.blendModeToPorterDuffMode(blendMode));
	}

	/** AOSP (API 28): whether the drawable has a state for focus (so no focus highlight is needed). */
	public boolean hasFocusStateSpecified() {
		return false;
	}

	/** AOSP (hidden): an Xfermode for drawables that support one; ignored by the base class. */
	public void setXfermode(android.graphics.Xfermode mode) {
	}

	/** AOSP (hidden): clears the mutated state after a constant state swap. */
	public void clearMutated() {
	}
	/** AOSP: the hotspot bounds, the drawable bounds unless a subclass narrows them. */
	public void getHotspotBounds(Rect outRect) {
		outRect.set(getBounds());
	}

	/** AOSP: the more opaque of two opacities, used by containers. */
	public static int resolveOpacity(int op1, int op2) {
		if (op1 == op2) {
			return op1;
		}
		if (op1 == android.graphics.PixelFormat.UNKNOWN || op2 == android.graphics.PixelFormat.UNKNOWN) {
			return android.graphics.PixelFormat.UNKNOWN;
		}
		if (op1 == android.graphics.PixelFormat.TRANSLUCENT || op2 == android.graphics.PixelFormat.TRANSLUCENT) {
			return android.graphics.PixelFormat.TRANSLUCENT;
		}
		if (op1 == android.graphics.PixelFormat.TRANSPARENT || op2 == android.graphics.PixelFormat.TRANSPARENT) {
			return android.graphics.PixelFormat.TRANSPARENT;
		}
		return android.graphics.PixelFormat.OPAQUE;
	}

	/** AOSP (hidden): reads the "visible" attribute during inflation. */
	void inflateWithAttributes(Resources r, org.xmlpull.v1.XmlPullParser parser, android.content.res.TypedArray attrs, int visibleAttr)
			throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
		mVisible = attrs.getBoolean(visibleAttr, mVisible);
	}

	/* AOSP: visibility state (setVisible/isVisible); drawables start visible */
	private boolean mVisible = true;
	/**
	 * AOSP: scales a pixel value between densities; a size never rounds to 0.
	 * @hide
	 */
	public static int scaleFromDensity(int pixels, int sourceDensity, int targetDensity, boolean isSize) {
		if (pixels == 0 || sourceDensity == targetDensity) {
			return pixels;
		}

		final float result = pixels * targetDensity / (float) sourceDensity;
		if (!isSize) {
			return (int) result;
		}

		final int rounded = Math.round(result);
		if (rounded != 0) {
			return rounded;
		} else if (pixels > 0) {
			return 1;
		} else {
			return -1;
		}
	}

	/** @hide */
	public static float scaleFromDensity(float pixels, int sourceDensity, int targetDensity) {
		return pixels * targetDensity / sourceDensity;
	}
	/* AOSP DrawableInflater.inflateFromClass: constructors by class name, cached */
	private static final java.util.HashMap<String, java.lang.reflect.Constructor<? extends Drawable>> tlConstructorMap = new java.util.HashMap<>();

	private static Drawable tlInflateFromClass(android.content.res.Resources res, String className, org.xmlpull.v1.XmlPullParser parser) throws org.xmlpull.v1.XmlPullParserException {
		try {
			java.lang.reflect.Constructor<? extends Drawable> constructor;
			synchronized (tlConstructorMap) {
				constructor = tlConstructorMap.get(className);
				if (constructor == null) {
					ClassLoader loader = Drawable.class.getClassLoader();
					Class<?> clazz;
					try {
						clazz = loader.loadClass(className);
					} catch (ClassNotFoundException e) {
						/* an app-defined drawable class lives in the app's class loader */
						ClassLoader app = Thread.currentThread().getContextClassLoader();
						if (app == null)
							throw e;
						clazz = app.loadClass(className);
					}
					constructor = clazz.asSubclass(Drawable.class).getConstructor();
					tlConstructorMap.put(className, constructor);
				}
			}
			return constructor.newInstance();
		} catch (NoSuchMethodException e) {
			final android.view.InflateException ie = new android.view.InflateException(parser.getPositionDescription() + ": Error inflating class " + className);
			ie.initCause(e);
			throw ie;
		} catch (ClassCastException e) {
			// If loaded class is not a Drawable subclass.
			final android.view.InflateException ie = new android.view.InflateException(parser.getPositionDescription() + ": Class is not a Drawable " + className);
			ie.initCause(e);
			throw ie;
		} catch (ClassNotFoundException e) {
			/* AOSP: an unknown tag that is no class either is an invalid drawable tag */
			throw new org.xmlpull.v1.XmlPullParserException(parser.getPositionDescription() + ": invalid drawable tag " + className);
		} catch (Exception e) {
			final android.view.InflateException ie = new android.view.InflateException(parser.getPositionDescription() + ": Error inflating class " + className);
			ie.initCause(e);
			throw ie;
		}
	}
}
