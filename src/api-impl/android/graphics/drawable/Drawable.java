package android.graphics.drawable;

import android.atl.GskCanvas;
import android.atl.annotation.Export;
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

	/** AOSP: the configuration changes this drawable depends on (set by Resources when it loads it). */
	public int getChangingConfigurations() {
		return mChangingConfigurations;
	}

	/** AOSP: sets the configuration changes this drawable depends on. */
	public void setChangingConfigurations(int configs) {
		mChangingConfigurations = configs;
	}

	/**
	 * AOSP: a drawable that does not override this has no constant state (null). ATL's own
	 * paintable-backed Drawable (created from a path or a native paintable) has one: the paintable is
	 * immutable shared data, like the Bitmap of AOSP's BitmapState, so new drawables share it.
	 */
	public ConstantState getConstantState() {
		if (getClass() == Drawable.class && paintable != 0)
			return new TlPaintableState(paintable, mChangingConfigurations);
		return null;
	}

	/** The constant state of a paintable-backed Drawable: new drawables share the native paintable. */
	static final class TlPaintableState extends ConstantState {
		private final long mPaintable;
		private final int mChangingConfigurations;

		TlPaintableState(long paintable, int changingConfigurations) {
			mPaintable = paintable;
			mChangingConfigurations = changingConfigurations;
		}

		@Override
		public Drawable newDrawable(Resources res) {
			return newDrawable();
		}

		@Override
		public Drawable newDrawable() {
			Drawable d = new Drawable(mPaintable);
			d.mChangingConfigurations = mChangingConfigurations;
			return d;
		}

		@Override
		public int getChangingConfigurations() {
			return mChangingConfigurations;
		}
	}

	public static abstract class ConstantState {

		/** AOSP: a new drawable from this state for the given resources (default: newDrawable()). */
		public Drawable newDrawable(Resources res) {
			return newDrawable();
		}

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

	@Export
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
	@Export
	public void draw(Canvas canvas) {
		final GskCanvas gsk = canvas.tlGskCanvas(); /* a Canvas(Bitmap) draws through its GSK canvas too */
		if (gsk != null) {
			if (mTlAlpha == 0)
				return;
			/* the alpha is a DST_IN filter with the alpha as the filter colour: every channel keeps
			 * its colour and its alpha is multiplied by alpha/255 (Skia's paint alpha) */
			boolean alphaPushed = false;
			if (mTlAlpha != 0xFF) {
				if (mTlAlphaFilter == null || mTlAlphaFilter.getColor() != mTlAlpha << 24)
					mTlAlphaFilter = new PorterDuffColorFilter(mTlAlpha << 24, PorterDuff.Mode.DST_IN);
				alphaPushed = gsk.tlPushColorFilter(mTlAlphaFilter);
			}
			ColorFilter filter = mColorFilter != null ? mColorFilter : mTintFilter;
			boolean pushed = filter != null && gsk.tlPushColorFilter(filter);
			if (mBounds.left != 0 || mBounds.top != 0)
				canvas.translate(mBounds.left, mBounds.top);
			native_draw(paintable, gsk.snapshot, mBounds.width(), mBounds.height());
			if (mBounds.left != 0 || mBounds.top != 0)
				canvas.translate(-mBounds.left, -mBounds.top);
			if (pushed)
				gsk.tlPopGroup();
			if (alphaPushed)
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

	/** AOSP: removes the colour filter. */
	public void clearColorFilter() {
		setColorFilter(null);
	}

	/** AOSP: the current level (0..10000). */
	public final int getLevel() {
		return mLevel;
	}

	/**
	 * AOSP: sets the level (0..10000) that level-aware drawables (clip, scale, level-list, rotate,
	 * progress) use; returns what onLevelChange returns when the level changed.
	 */
	public final boolean setLevel(int level) {
		if (mLevel != level) {
			mLevel = level;
			return onLevelChange(level);
		}
		return false;
	}

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

	@Export
	public int getIntrinsicWidth() { return -1; }

	@Export
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

	/** AOSP Drawable.parseBlendMode(): an android:tintMode attribute value as a BlendMode. */
	public static BlendMode parseBlendMode(int value, BlendMode defaultMode) {
		switch (value) {
			case 3: return BlendMode.SRC_OVER;
			case 5: return BlendMode.SRC_IN;
			case 9: return BlendMode.SRC_ATOP;
			// PorterDuff MULTIPLY maps to Skia's modulate, as in AOSP (b/73224934)
			case 14: return BlendMode.MODULATE;
			case 15: return BlendMode.SCREEN;
			case 16: return BlendMode.PLUS;
			default: return defaultMode;
		}
	}

	/** AOSP Drawable.parseTintMode(): an android:tintMode attribute value as a PorterDuff mode. */
	public static PorterDuff.Mode parseTintMode(int value, PorterDuff.Mode defaultMode) {
		switch (value) {
			case 3: return PorterDuff.Mode.SRC_OVER;
			case 5: return PorterDuff.Mode.SRC_IN;
			case 9: return PorterDuff.Mode.SRC_ATOP;
			case 14: return PorterDuff.Mode.MULTIPLY;
			case 15: return PorterDuff.Mode.SCREEN;
			case 16: return PorterDuff.Mode.ADD;
			default: return defaultMode;
		}
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

	/**
	 * AOSP: the tint mode (null: SRC_IN). ATL keeps the tint of a plain drawable in the base class;
	 * as in AOSP the call is also handed to setTintBlendMode, so subclasses that only override
	 * the blend-mode variant (ColorDrawable, LayerDrawable, ...) get it too.
	 */
	public void setTintMode(PorterDuff.Mode tintMode) {
		if (tintMode == null)
			tintMode = DEFAULT_TINT_MODE;
		if (mTintMode != tintMode) {
			mTintMode = tintMode;
			mTintFilter = updateTintFilter(mTintFilter, mTint, mTintMode);
			invalidateSelf();
		}
		if (!mSetTintModeInvoked) {
			mSetTintModeInvoked = true;
			BlendMode mode = BlendMode.fromValue(tintMode.nativeInt);
			setTintBlendMode(mode != null ? mode : DEFAULT_BLEND_MODE);
			mSetTintModeInvoked = false;
		}
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
				/* AOSP DrawableInflater: <nine-patch android:src=...> inflates its source image */
				NinePatchDrawable drawable = new NinePatchDrawable();
				drawable.inflate(resources, parser, attrs, theme);
				return drawable;
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
			case "animated-image": {
				AnimatedImageDrawable drawable = new AnimatedImageDrawable();
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

	/** AOSP: a drawable from a stream (no resources, so no density information). */
	public static Drawable createFromStream(InputStream is, String srcName) {
		return createFromResourceStream(null, null, is, srcName, null);
	}

	/** AOSP: a drawable from a stream, with the resources and value giving density information. */
	public static Drawable createFromResourceStream(Resources res, TypedValue value, InputStream is, String srcName) {
		return createFromResourceStream(res, value, is, srcName, null);
	}

	public static Drawable createFromResourceStream(Resources resources, TypedValue value, InputStream is, String file,
	                                                Object object) {
		if (is == null)
			return null;
		if (file == null || !file.endsWith(".9.png")) {
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

	/**
	 * AOSP declares this abstract; ATL's plain (paintable) drawable keeps the alpha and draws the
	 * paintable with it (see draw). Subclasses with their own paint override it.
	 */
	public void setAlpha(int alpha) {
		alpha &= 0xFF;
		if (mTlAlpha != alpha) {
			mTlAlpha = alpha;
			invalidateSelf();
		}
	}

	/** AOSP: no padding (all zero) unless a subclass has some; true when there is padding. */
	public boolean getPadding(Rect padding) {
		padding.set(0, 0, 0, 0);
		return false;
	}

	public void copyBounds(Rect bounds) {
		bounds.set(mBounds);
	}

	/** AOSP: the suggested minimum width, the intrinsic width or 0 when there is none. */
	public int getMinimumWidth() {
		final int intrinsicWidth = getIntrinsicWidth();
		return intrinsicWidth > 0 ? intrinsicWidth : 0;
	}

	/** AOSP: the suggested minimum height, the intrinsic height or 0 when there is none. */
	public int getMinimumHeight() {
		final int intrinsicHeight = getIntrinsicHeight();
		return intrinsicHeight > 0 ? intrinsicHeight : 0;
	}

	protected void onBoundsChange(Rect bounds) {}

	public void setDither(boolean dither) {}

	public void setAutoMirrored(boolean mirrored) {}

	public void jumpToCurrentState() {}

	/**
	 * AOSP: sets the resolved layout direction (View.LAYOUT_DIRECTION_LTR or _RTL); returns what
	 * onLayoutDirectionChanged returns when it changed.
	 */
	public final boolean setLayoutDirection(int layoutDirection) {
		if (mLayoutDirection != layoutDirection) {
			mLayoutDirection = layoutDirection;
			return onLayoutDirectionChanged(layoutDirection);
		}
		return false;
	}

	public void setHotspot(float x, float y) {}

	/** AOSP: the resolved layout direction. */
	public int getLayoutDirection() {
		return mLayoutDirection;
	}

	static int resolveDensity(Resources r, int parentDensity) {
		final int densityDpi = r == null ? parentDensity : r.getDisplayMetrics().densityDpi;
		return densityDpi == 0 ? DisplayMetrics.DENSITY_DEFAULT : densityDpi;
	}

	public void setFilterBitmap(boolean filter) {}

	public void setHotspotBounds(int left, int top, int right, int bottom) {}

	public Rect copyBounds() {
		return new Rect(mBounds);
	}

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
	/** AOSP returns 0xFF unless a subclass tracks its alpha; the plain drawable tracks it (setAlpha). */
	public int getAlpha() { return mTlAlpha; }
	public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
	public boolean isAutoMirrored() { return false; }
	public Rect getDirtyBounds() { return getBounds(); }
	/** AOSP: the default outline is the bounds rectangle with alpha 0 (it casts no shadow). */
	public void getOutline(android.graphics.Outline outline) {
		outline.setRect(getBounds());
		outline.setAlpha(0);
	}
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
		if (!mSetBlendModeInvoked) {
			mSetBlendModeInvoked = true;
			android.graphics.PorterDuff.Mode mode = android.graphics.BlendMode.blendModeToPorterDuffMode(blendMode);
			setTintMode(mode != null ? mode : DEFAULT_TINT_MODE);
			mSetBlendModeInvoked = false;
		}
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

	/* ---- teslalinux (draw): AOSP base Drawable state ---- */
	/** AOSP mLevel: 0..10000, see setLevel. */
	private int mLevel = 0;
	/** AOSP mChangingConfigurations. */
	private int mChangingConfigurations = 0;
	/** AOSP mLayoutDirection (View.LAYOUT_DIRECTION_LTR = 0). */
	private int mLayoutDirection = LayoutDirection.LTR;
	/** The alpha of ATL's plain paintable drawable (AOSP keeps alpha in the subclasses' paints). */
	private int mTlAlpha = 0xFF;
	private PorterDuffColorFilter mTlAlphaFilter;
	/** AOSP (package): the density XML children are looked up at, 0 for the resources' density. */
	int mSrcDensityOverride = 0;
	/** AOSP: re-entrancy guards between setTintMode and setTintBlendMode. */
	private boolean mSetTintModeInvoked;
	private boolean mSetBlendModeInvoked;

	/**
	 * AOSP: the BitmapFactory.Options variant (deprecated in AOSP). ATL's decoder takes no options
	 * here (the drawable is decoded at the asset's own size, as the null-options path does).
	 */
	public static Drawable createFromResourceStream(Resources res, TypedValue value, InputStream is, String srcName, BitmapFactory.Options opts) {
		return createFromResourceStream(res, value, is, srcName, (Object) opts);
	}

	/** AOSP (hidden): XML inflation with a density override; ATL inflates at the resource density. */
	public static Drawable createFromXmlForDensity(Resources r, XmlPullParser parser, int density, Theme theme) throws XmlPullParserException, IOException {
		AttributeSet attrs = android.util.Xml.asAttributeSet(parser);
		int type;
		while ((type = parser.next()) != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT)
			;
		if (type != XmlPullParser.START_TAG)
			throw new XmlPullParserException("No start tag found");
		Drawable drawable = createFromXmlInnerForDensity(r, parser, attrs, density, theme);
		if (drawable == null)
			throw new RuntimeException("Unknown initial tag: " + parser.getName());
		return drawable;
	}

	/** AOSP (hidden): createFromXmlInner with a density override (0: the resources' density). */
	static Drawable createFromXmlInnerForDensity(Resources r, XmlPullParser parser, AttributeSet attrs, int density, Theme theme) throws XmlPullParserException, IOException {
		return createFromXmlInner(r, parser, attrs, theme);
	}

	/**
	 * Skia's saveLayer(bounds, paint) for a drawable that composites several draws before applying
	 * one alpha and one colour filter (GradientDrawable with a translucent stroke over a fill): on
	 * ATL's GSK canvas a group with a DST_IN alpha (outer) and the colour filter as a colour matrix
	 * (inner) gives the same result. Returns the number of groups pushed (pop them with
	 * tlPopLayer), or -1 when the canvas or the filter cannot be expressed that way (the caller
	 * then filters each draw itself).
	 */
	static int tlPushLayer(Canvas canvas, int alpha, ColorFilter filter) {
		final GskCanvas gsk = canvas.tlGskCanvas();
		if (gsk == null)
			return -1;
		PorterDuffColorFilter pd = null;
		if (filter instanceof PorterDuffColorFilter) {
			pd = (PorterDuffColorFilter) filter;
		} else if (filter instanceof BlendModeColorFilter) {
			BlendModeColorFilter blend = (BlendModeColorFilter) filter;
			PorterDuff.Mode mode = blend.getMode() == BlendMode.MODULATE ? PorterDuff.Mode.MULTIPLY : BlendMode.blendModeToPorterDuffMode(blend.getMode());
			if (mode == null)
				return -1;
			pd = new PorterDuffColorFilter(blend.getColor(), mode);
		} else if (filter != null) {
			return -1;
		}
		int pushed = 0;
		alpha &= 0xFF;
		if (alpha != 0xFF && gsk.tlPushColorFilter(new PorterDuffColorFilter(alpha << 24, PorterDuff.Mode.DST_IN)))
			pushed++;
		if (pd != null && gsk.tlPushColorFilter(pd))
			pushed++;
		return pushed;
	}

	/** Closes the groups tlPushLayer opened. */
	static void tlPopLayer(Canvas canvas, int pushed) {
		for (int i = 0; i < pushed; i++)
			canvas.tlGskCanvas().tlPopGroup();
	}
}
