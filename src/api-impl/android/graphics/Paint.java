package android.graphics;

import java.util.Locale;

public class Paint {
	public static final int ANTI_ALIAS_FLAG = (1 << 0);
	public static final int FILTER_BITMAP_FLAG = (1 << 1);
	public static final int DITHER_FLAG = (1 << 2);
	public static final int UNDERLINE_TEXT_FLAG = (1 << 3);
	public static final int STRIKE_THRU_TEXT_FLAG = (1 << 4);
	public static final int FAKE_BOLD_TEXT_FLAG = (1 << 5);
	public static final int LINEAR_TEXT_FLAG = (1 << 6);
	public static final int SUBPIXEL_TEXT_FLAG = (1 << 7);
	public static final int DEV_KERN_TEXT_FLAG = (1 << 8);
	public static final int LCD_RENDER_TEXT_FLAG = (1 << 9);
	public static final int EMBEDDED_BITMAP_TEXT_FLAG = (1 << 10);
	public static final int AUTO_HINTING_TEXT_FLAG = (1 << 11);
	public static final int VERTICAL_TEXT_FLAG = (1 << 12);

	public long paint; // native paint
	private Xfermode xfermode;
	private Shader shader;
	private Align align = Align.LEFT; // AOSP's default; the native paint starts left-aligned too
	private ColorFilter color_filter;

	public Paint() {
		paint = native_create();
	}

	public Paint(int flags) {
		this();
		setFlags(flags);
	}

	/** AOSP: a copy of every setting of the paint (the native part is cloned, the Java state copied). */
	public Paint(Paint paint) {
		this.paint = native_clone(paint.paint);
		tlCopyJavaState(paint);
	}

	public void setColor(int color) {
		native_set_color(paint, color);
	}

	public void setARGB(int a, int r, int g, int b) {
		setColor(Color.argb(a, r, g, b));
	}

	public int getColor() {
		return native_get_color(paint);
	}

	public void setAlpha(int a) {
		native_set_alpha(paint, a);
	}

	public int getAlpha() {
		return native_get_alpha(paint);
	}

	/** AOSP: whether anti-aliasing is on (the ANTI_ALIAS_FLAG). */
	public boolean isAntiAlias() {
		return tlAntiAlias;
	}

	private boolean tlAntiAlias = true;

	public void setAntiAlias(boolean aa) {
		tlAntiAlias = aa;
		if (aa)
			mFlags |= ANTI_ALIAS_FLAG;
		else
			mFlags &= ~ANTI_ALIAS_FLAG;
	}

	/**
	 * AOSP: font variation settings ("'wght' 700, 'wdth' 50"); false (and unchanged) when the string
	 * does not parse. Pango applies the axes to the variable font.
	 */
	public boolean setFontVariationSettings(String fvs) {
		if (fvs == null || fvs.isEmpty()) {
			mFontVariationSettings = null;
			native_set_font_variations(paint, null);
			return true;
		}
		android.graphics.fonts.FontVariationAxis[] axes;
		try {
			axes = android.graphics.fonts.FontVariationAxis.fromFontVariationSettings(fvs);
		} catch (IllegalArgumentException e) {
			return false;
		}
		StringBuilder pango = new StringBuilder();
		for (android.graphics.fonts.FontVariationAxis axis : axes) {
			if (pango.length() > 0)
				pango.append(',');
			pango.append(axis.getTag()).append('=').append(axis.getStyleValue());
		}
		mFontVariationSettings = fvs;
		native_set_font_variations(paint, pango.toString());
		return true;
	}

	public String getFontVariationSettings() {
		return mFontVariationSettings;
	}

	public void setStrokeWidth(float width) {
		native_set_stroke_width(paint, width);
	}
	public void setTextSize(float size) {
		native_set_text_size(paint, size);
	}

	/* teslalinux: AOSP Paint keeps the typeface it was given (null means the default); ATL used to
	 * drop it and hand back a fresh default from getTypeface(). Pango still picks the actual font
	 * by family/style, see Typeface. */
	private Typeface mTypeface;

	public Typeface setTypeface(Typeface typeface) {
		mTypeface = typeface;
		return typeface;
	}
	public void getTextBounds(String text, int start, int end, Rect bounds) {
		if (end > text.length())
			end = text.length();
		native_get_text_bounds(paint, text.substring(start, end), bounds);
	}
	public void getTextBounds(char[] text, int index, int count, Rect bounds) {
		native_get_text_bounds(paint, new String(text, index, count), bounds);
	}
	/** AOSP: the advance of every character of text[start, end) into widths; returns the count. */
	public int getTextWidths(String text, int start, int end, float[] widths) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		if (end - start > widths.length)
			throw new ArrayIndexOutOfBoundsException();
		if (end == start)
			return 0;
		char[] buf = new char[end - start];
		text.getChars(start, end, buf, 0);
		return getTextWidths(buf, 0, buf.length, widths);
	}
	/** AOSP: FILTER_BITMAP_FLAG selects bilinear sampling of scaled bitmaps. */
	public void setFilterBitmap(boolean filter) {
		if (filter)
			mFlags |= FILTER_BITMAP_FLAG;
		else
			mFlags &= ~FILTER_BITMAP_FLAG;
	}

	public void setFlags(int flags) {
		mFlags = flags;
		setAntiAlias((flags & ANTI_ALIAS_FLAG) != 0);
	}

	public void setStyle(Style style) {
		native_set_style(paint, style.ordinal());
	}

	/** AOSP: distance above the baseline (negative) recommended by the font. */
	public float ascent() {
		return tlFontMetrics()[1];
	}

	public float measureText(char[] text, int index, int count) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((index | count) < 0 || index + count > text.length)
			throw new ArrayIndexOutOfBoundsException();
		if (count == 0)
			return 0f;
		return native_measure_text(paint, text, index, count) * mTextScaleX;
	}
	public float measureText(String text, int start, int end) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		if (end == start)
			return 0f;
		char[] buf = new char[end - start];
		text.getChars(start, end, buf, 0);
		return measureText(buf, 0, buf.length);
	}
	public float measureText(String text) {
		return measureText(text, 0, text.length());
	}
	public float measureText(CharSequence text, int start, int end) {
		return measureText(text.toString(), start, end);
	}

	/** AOSP: a blend-mode colour filter (PorterDuffColorFilter, or BlendModeColorFilter as Compose and
	 * AndroidX use on API 29+) with the mode's real result (native-paint-colorfilter-modes.sh). */
	public ColorFilter setColorFilter(ColorFilter colorFilter) {
		if (colorFilter instanceof PorterDuffColorFilter) {
			PorterDuffColorFilter porterDuff = (PorterDuffColorFilter)colorFilter;
			native_set_color_filter_mode(paint, porterDuff.getMode().ordinal(), porterDuff.getColor());
		} else if (colorFilter instanceof BlendModeColorFilter) {
			BlendModeColorFilter blend = (BlendModeColorFilter)colorFilter;
			PorterDuff.Mode mode = BlendMode.blendModeToPorterDuffMode(blend.getMode());
			if (blend.getMode() == BlendMode.MODULATE)
				mode = PorterDuff.Mode.MULTIPLY;
			native_set_color_filter_mode(paint, mode != null ? mode.ordinal() : PorterDuff.Mode.SRC_IN.ordinal(), blend.getColor());
		} else if (colorFilter instanceof ColorMatrixColorFilter) {
			native_set_color_matrix(paint, ((ColorMatrixColorFilter) colorFilter).tlArray());
		} else if (colorFilter instanceof LightingColorFilter) {
			native_set_color_matrix(paint, ((LightingColorFilter) colorFilter).tlArray());
		} else {
			native_set_color_filter_mode(paint, -1, 0);
		}
		color_filter = colorFilter;
		return colorFilter;
	}

	public ColorFilter getColorFilter() {
		return color_filter;
	}

	public Shader setShader(Shader shader) {
		this.shader = shader;
		return shader;
	}

	public enum Style {
		/**
		 * Geometry and text drawn with this style will be filled, ignoring all
		 * stroke-related settings in the paint.
		 */
		FILL,
		/**
		 * Geometry and text drawn with this style will be stroked, respecting
		 * the stroke-related fields on the paint.
		 */
		STROKE,
		/**
		 * Geometry and text drawn with this style will be both filled and
		 * stroked at the same time, respecting the stroke-related fields on
		 * the paint. This mode can give unexpected results if the geometry
		 * is oriented counter-clockwise. This restriction does not apply to
		 * either FILL or STROKE.
		 */
		FILL_AND_STROKE;

		public static final Style values[] = Style.values();
	}

	public static class FontMetrics {
		/**
		 * The maximum distance above the baseline for the tallest glyph in
		 * the font at a given text size.
		 */
		public float top;
		/**
		 * The recommended distance above the baseline for singled spaced text.
		 */
		public float ascent;
		/**
		 * The recommended distance below the baseline for singled spaced text.
		 */
		public float descent;
		/**
		 * The maximum distance below the baseline for the lowest glyph in
		 * the font at a given text size.
		 */
		public float bottom;
		/**
		 * The recommended additional space to add between lines of text.
		 */
		public float leading;
	}

	public static class FontMetricsInt {
		public int top;
		public int ascent;
		public int descent;
		public int bottom;
		public int leading;

		@Override
		public String toString() {
			return "FontMetricsInt: top=" + top + " ascent=" + ascent + " descent=" + descent + " bottom=" + bottom + " leading=" + leading;
		}
	}

	public int getFlags() {
		return mFlags;
	}

	public int getHinting() {
		return mHinting;
	}
	public void setHinting(int mode) {
		mHinting = mode;
	}

	public void setDither(boolean dither) {
		if (dither)
			mFlags |= DITHER_FLAG;
		else
			mFlags &= ~DITHER_FLAG;
	}
	public boolean isDither() {
		return (mFlags & DITHER_FLAG) != 0;
	}

	/* AOSP: these set their flag in the flag word; GskCanvas draws underline and strike-through
	 * (Skia's positions), linear/subpixel text only select glyph positioning that Pango does anyway. */
	public void setLinearText(boolean linearText) { tlSetFlag(LINEAR_TEXT_FLAG, linearText); }
	public void setSubpixelText(boolean subpixelText) { tlSetFlag(SUBPIXEL_TEXT_FLAG, subpixelText); }
	public void setUnderlineText(boolean underlineText) { tlSetFlag(UNDERLINE_TEXT_FLAG, underlineText); }
	public void setStrikeThruText(boolean strikeThruText) { tlSetFlag(STRIKE_THRU_TEXT_FLAG, strikeThruText); }
	public void setFakeBoldText(boolean fakeBoldText) { tlSetFlag(FAKE_BOLD_TEXT_FLAG, fakeBoldText); }
	public boolean isLinearText() { return (mFlags & LINEAR_TEXT_FLAG) != 0; }
	public boolean isSubpixelText() { return (mFlags & SUBPIXEL_TEXT_FLAG) != 0; }
	public boolean isUnderlineText() { return (mFlags & UNDERLINE_TEXT_FLAG) != 0; }
	public boolean isStrikeThruText() { return (mFlags & STRIKE_THRU_TEXT_FLAG) != 0; }

	private void tlSetFlag(int flag, boolean on) {
		if (on)
			mFlags |= flag;
		else
			mFlags &= ~flag;
	}

	public float getStrokeWidth() {
		return native_get_stroke_width(paint);
	}

	/** AOSP: the stroke miter limit (4 by default), kept on the GSK stroke. */
	public float getStrokeMiter() {
		return native_get_stroke_miter(paint);
	}

	/** AOSP: sets the miter limit; negative values are ignored. */
	public void setStrokeMiter(float miter) {
		if (miter >= 0)
			native_set_stroke_miter(paint, miter);
	}
	public /*native*/ float getTextSize() {
		return native_get_text_size(paint);
	}

	/** AOSP: horizontal scale of the text, 1.0 by default; applied to measured advances. */
	public float getTextScaleX() {
		return mTextScaleX;
	}
	public void setTextScaleX(float scaleX) {
		mTextScaleX = scaleX;
	}
	/** AOSP: horizontal skew of the text (0 by default, -0.25 for synthetic italic); GskCanvas skews the text. */
	public float getTextSkewX() {
		return mTextSkewX;
	}

	public void setTextSkewX(float skewX) {
		mTextSkewX = skewX;
	}

	/** AOSP: distance below the baseline recommended by the font. */
	public float descent() {
		return tlFontMetrics()[2];
	}
	/** AOSP: fills metrics (may be null) and returns the recommended line spacing descent - ascent + leading. */
	public float getFontMetrics(FontMetrics metrics) {
		float[] m = tlFontMetrics();
		if (metrics != null) {
			metrics.top = m[0];
			metrics.ascent = m[1];
			metrics.descent = m[2];
			metrics.bottom = m[3];
			metrics.leading = m[4];
		}
		return m[2] - m[1] + m[4];
	}
	/** AOSP (Paint.cpp): top floored, bottom ceiled, the rest rounded; returns descent - ascent + leading. */
	public int getFontMetricsInt(FontMetricsInt fmi) {
		float[] m = tlFontMetrics();
		int top = (int) Math.floor(m[0]);
		int ascent = Math.round(m[1]);
		int descent = Math.round(m[2]);
		int bottom = (int) Math.ceil(m[3]);
		int leading = Math.round(m[4]);
		if (fmi != null) {
			fmi.top = top;
			fmi.ascent = ascent;
			fmi.descent = descent;
			fmi.bottom = bottom;
			fmi.leading = leading;
		}
		return descent - ascent + leading;
	}

	/**
	 * AOSP: a blurred shadow drawn under the text (radius 0 clears it). The parameters are kept on
	 * the paint and reported by the getters; GskCanvas draws the shadow for text (see tlShadow*).
	 */
	public void setShadowLayer(float radius, float dx, float dy, int color) {
		mShadowLayerRadius = radius;
		mShadowLayerDx = dx;
		mShadowLayerDy = dy;
		mShadowLayerColor = color;
	}

	public Xfermode setXfermode(Xfermode xfermode) {
		this.xfermode = xfermode;
		return xfermode;
	}

	public Xfermode getXfermode() {
		return xfermode;
	}

	public void setBlendMode(BlendMode blendmode) {
		setXfermode(blendmode == null ? null : blendmode.getXfermode());
	}

	public BlendMode getBlendMode() {
		if (this.xfermode instanceof PorterDuffXfermode) {
			return BlendMode.fromValue(((PorterDuffXfermode)this.xfermode).porterDuffMode);
		}
		return null;
	}

	public void setLetterSpacing(float spacing) {
		mLetterSpacing = spacing;
		native_set_letter_spacing(paint, spacing);
	}

	public enum Cap {
		/**
		 * The stroke ends with the path, and does not project beyond it.
		 */
		BUTT,
		/**
		 * The stroke projects out as a semicircle, with the center at the
		 * end of the path.
		 */
		ROUND,
		/**
		 * The stroke projects out as a square, with the center at the end
		 * of the path.
		 */
		SQUARE;

		public static final Cap values[] = Cap.values();
	}

	public enum Join {
		/**
		 * The outer edges of a join meet at a sharp angle
		 */
		MITER,
		/**
		 * The outer edges of a join meet in a circular arc.
		 */
		ROUND,
		/**
		 * The outer edges of a join meet with a straight line
		 */
		BEVEL;

		public static final Join values[] = Join.values();
	}

	public enum Align {
		LEFT,
		CENTER,
		RIGHT,
	}

	public void setStrokeCap(Cap cap) {
		native_set_stroke_cap(paint, cap.ordinal());
	}

	public void setStrokeJoin(Join join) {
		native_set_stroke_join(paint, join.ordinal());
	}

	public Typeface getTypeface() {
		return mTypeface;
	}

	public void setTextAlign(Align align) {
		this.align = align;
		native_set_text_align(paint, align.ordinal());
	}

	public Shader getShader() {
		return shader;
	}

	/** AOSP: the paint's path effect, or null. */
	public PathEffect getPathEffect() {
		return mPathEffect;
	}

	/**
	 * AOSP: sets or clears the path effect. A DashPathEffect also goes onto the GSK stroke, so a
	 * dashed stroke is drawn by GSK directly; GskCanvas applies every other effect to the geometry.
	 */
	public PathEffect setPathEffect(PathEffect effect) {
		mPathEffect = effect;
		if (effect instanceof DashPathEffect) {
			DashPathEffect dash = (DashPathEffect) effect;
			native_set_dash(paint, dash.tlIntervals(), dash.tlPhase());
		} else {
			native_set_dash(paint, null, 0);
		}
		return effect;
	}

	/** AOSP: how many characters of text[index, index+count) fit into maxWidth (count < 0 measures backwards). */
	public int breakText(char[] text, int index, int count, float maxWidth, float[] measuredWidth) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if (index < 0 || text.length - index < Math.abs(count))
			throw new ArrayIndexOutOfBoundsException();
		boolean forwards = count >= 0;
		int n = Math.abs(count);
		if (n == 0) {
			if (measuredWidth != null && measuredWidth.length > 0)
				measuredWidth[0] = 0;
			return 0;
		}
		int from = forwards ? index : index - n;
		float[] widths = new float[n];
		getTextWidths(text, from, n, widths);
		float sum = 0;
		int fit = 0;
		for (int i = 0; i < n; i++) {
			float w = widths[forwards ? i : n - 1 - i];
			if (sum + w > maxWidth)
				break;
			sum += w;
			fit++;
		}
		if (measuredWidth != null && measuredWidth.length > 0)
			measuredWidth[0] = sum;
		return fit;
	}
	public int breakText(String text, boolean measureForwards, float maxWidth, float[] measuredWidth) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		char[] buf = text.toCharArray();
		return measureForwards ? breakText(buf, 0, buf.length, maxWidth, measuredWidth)
		                       : breakText(buf, buf.length, -buf.length, maxWidth, measuredWidth);
	}
	public int breakText(CharSequence text, int start, int end, boolean measureForwards, float maxWidth, float[] measuredWidth) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		char[] buf = new char[end - start];
		android.text.TextUtils.getChars(text, start, end, buf, 0);
		return measureForwards ? breakText(buf, 0, buf.length, maxWidth, measuredWidth)
		                       : breakText(buf, buf.length, -buf.length, maxWidth, measuredWidth);
	}

	public void clearShadowLayer() {
		setShadowLayer(0, 0, 0, 0);
	}

	public boolean hasShadowLayer() {
		return mShadowLayerRadius > 0;
	}

	/** AOSP: a new FontMetrics filled from the current font. */
	public FontMetrics getFontMetrics() {
		FontMetrics fm = new FontMetrics();
		getFontMetrics(fm);
		return fm;
	}

	public void setFontMetricsInt(FontMetricsInt fmi) {}

	/** AOSP: a new FontMetricsInt filled from the current font. */
	public FontMetricsInt getFontMetricsInt() {
		FontMetricsInt fm = new FontMetricsInt();
		getFontMetricsInt(fm);
		return fm;
	}

	/** AOSP: copies every setting of src into this paint. */
	public void set(Paint paint) {
		if (paint == this)
			return;
		native_recycle(this.paint);
		this.paint = native_clone(paint.paint);
		tlCopyJavaState(paint);
	}

	public boolean isFilterBitmap() {
		return (mFlags & FILTER_BITMAP_FLAG) != 0;
	}

	public Cap getStrokeCap() {
		return Cap.values[native_get_stroke_cap(paint)];
	}

	public Join getStrokeJoin() {
		return Join.values[native_get_stroke_join(paint)];
	}

	public Locale getTextLocale() { return getTextLocales().get(0); }

	/** AOSP: letter spacing in em units, 0 by default. */
	public float getLetterSpacing() {
		return mLetterSpacing;
	}

	public Style getStyle() {
		return Style.values[native_get_style(paint)];
	}

	public Align getTextAlign() {
		return align;
	}

	/** AOSP: true when the string renders as a single glyph of the font (so emoji sequences are supported). */
	public boolean hasGlyph(String text) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if (text.isEmpty())
			return false;
		return native_has_glyph(paint, text);
	}

	/** AOSP: sets or clears the mask filter (BlurMaskFilter is drawn by GskCanvas). */
	public MaskFilter setMaskFilter(MaskFilter filter) {
		mMaskFilter = filter;
		return filter;
	}

	/** AOSP: the paint's mask filter, or null. */
	public MaskFilter getMaskFilter() {
		return mMaskFilter;
	}

	public boolean isFakeBoldText() {
		return false;
	}

	public void reset() {
		native_recycle(paint);
		paint = native_create();
		xfermode = null;
		shader = null;
		align = Align.LEFT;
		color_filter = null;
		tlResetGfx();
	}

	private static native long native_create();
	private static native long native_clone(long paint);
	private static native void native_recycle(long paint);
	private static native void native_set_color(long paint, int color);
	private static native int native_get_color(long paint);
	private static native void native_set_alpha(long paint, int alpha);
	private static native int native_get_alpha(long paint);
	private static native void native_set_style(long paint, int style);
	private static native int native_get_style(long paint);
	private static native void native_set_stroke_width(long paint, float width);
	private static native float native_get_stroke_width(long paint);
	private static native void native_set_stroke_cap(long paint, int cap);
	private static native int native_get_stroke_cap(long paint);
	private static native void native_set_stroke_join(long paint, int join);
	private static native int native_get_stroke_join(long paint);
	private static native void native_set_text_size(long paint, float size);
	private static native float native_get_text_size(long paint);
	private static native void native_set_color_filter(long paint, int mode, int color);
	private static native void native_get_text_bounds(long paint, String text, Rect bounds);
	private static native void native_set_text_align(long paint, int align);


	/* teslalinux: Paint additions */
	public long getColorLong() { return Color.pack(getColor()); }
	public void setColor(long color) { setColor((int) (color >> 32)); }
	public long getColorLongCompat() { return getColorLong(); }

	/** Text locales (AOSP mLocales): never empty, defaults to the system locale list. */
	private android.os.LocaleList mLocales;

	/** Sets the text locale list; must not be empty (AOSP semantics). */
	public void setTextLocales(android.os.LocaleList locales) {
		if (locales == null || locales.isEmpty())
			throw new IllegalArgumentException("locales cannot be null or empty");
		mLocales = locales;
	}

	/** Sets a single text locale. */
	public void setTextLocale(Locale locale) {
		if (locale == null)
			throw new IllegalArgumentException("locale cannot be null");
		mLocales = new android.os.LocaleList(locale);
	}

	/** The text locale list (AOSP getTextLocales). */
	public android.os.LocaleList getTextLocales() {
		if (mLocales == null)
			mLocales = new android.os.LocaleList(Locale.getDefault());
		return mLocales;
	}

	/** Bounds of the text (API 29 CharSequence overload). */
	public void getTextBounds(CharSequence text, int start, int end, Rect bounds) {
		getTextBounds(text.toString(), start, end, bounds);
	}

	private String mFontFeatureSettings;

	/** CSS-style font feature settings, e.g. "smcp" (AOSP); kept for the layout engine to read. */
	public String getFontFeatureSettings() {
		return mFontFeatureSettings;
	}

	public void setFontFeatureSettings(String settings) {
		if (settings != null && settings.isEmpty())
			settings = null;
		mFontFeatureSettings = settings;
	}
	/** AOSP flag word (ANTI_ALIAS_FLAG, FILTER_BITMAP_FLAG, DITHER_FLAG, ...) and hinting mode. */
	private int mFlags;
	private int mHinting = HINTING_ON;
	/** Paint hinting modes (AOSP). */
	public static final int HINTING_OFF = 0x0;
	public static final int HINTING_ON = 0x1;

	/* teslalinux: text measurement state and natives (native-paint-metrics.sh) */
	private float mLetterSpacing = 0f;
	private float mTextScaleX = 1f;
	private static native void native_get_font_metrics(long paint, float[] out);
	private static native float native_measure_text(long paint, char[] text, int index, int count);
	private static native float native_get_text_advances(long paint, char[] text, int index, int count, float[] advances, int advancesIndex);
	private static native boolean native_has_glyph(long paint, String text);
	private static native void native_set_letter_spacing(long paint, float spacing);

	/** top, ascent, descent, bottom, leading of the current font in pixels (ascent and top negative). */
	private float[] tlFontMetrics() {
		float[] m = new float[5];
		native_get_font_metrics(paint, m);
		return m;
	}

	/** AOSP: the recommended line spacing, descent - ascent + leading. */
	public float getFontSpacing() {
		return getFontMetrics(null);
	}

	public int getTextWidths(char[] text, int index, int count, float[] widths) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((index | count) < 0 || index + count > text.length || count > widths.length)
			throw new ArrayIndexOutOfBoundsException();
		if (count == 0)
			return 0;
		native_get_text_advances(paint, text, index, count, widths, 0);
		if (mTextScaleX != 1f)
			for (int i = 0; i < count; i++)
				widths[i] *= mTextScaleX;
		return count;
	}

	public int getTextWidths(CharSequence text, int start, int end, float[] widths) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		if (end - start > widths.length)
			throw new ArrayIndexOutOfBoundsException();
		if (end == start)
			return 0;
		char[] buf = new char[end - start];
		android.text.TextUtils.getChars(text, start, end, buf, 0);
		return getTextWidths(buf, 0, buf.length, widths);
	}

	public int getTextWidths(String text, float[] widths) {
		return getTextWidths(text, 0, text.length(), widths);
	}

	/**
	 * AOSP (hidden, used by the text layout code): advances of chars[index, index+count) measured in
	 * the context chars[contextIndex, contextIndex+contextCount); returns the total advance.
	 */
	public float getTextRunAdvances(char[] chars, int index, int count, int contextIndex, int contextCount, boolean isRtl, float[] advances, int advancesIndex) {
		if (chars == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((index | count | contextIndex | contextCount | advancesIndex | (index - contextIndex) | (contextCount - count)
				| ((contextIndex + contextCount) - (index + count)) | (chars.length - (contextIndex + contextCount))
				| (advances == null ? 0 : (advances.length - (advancesIndex + count)))) < 0)
			throw new IndexOutOfBoundsException();
		if (count == 0)
			return 0f;
		/* measure the whole context so shaping across the run boundary matches drawing, then take the slice */
		float[] all = new float[contextCount];
		native_get_text_advances(paint, chars, contextIndex, contextCount, all, 0);
		float total = 0f;
		for (int i = 0; i < count; i++) {
			float a = all[index - contextIndex + i] * mTextScaleX;
			if (advances != null)
				advances[advancesIndex + i] = a;
			total += a;
		}
		return total;
	}

	public float getTextRunAdvances(CharSequence text, int start, int end, int contextStart, int contextEnd, boolean isRtl, float[] advances, int advancesIndex) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | contextStart | contextEnd | (end - start) | (start - contextStart) | (contextEnd - end) | (text.length() - contextEnd)) < 0)
			throw new IndexOutOfBoundsException();
		char[] buf = new char[contextEnd - contextStart];
		android.text.TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
		return getTextRunAdvances(buf, start - contextStart, end - start, 0, contextEnd - contextStart, isRtl, advances, advancesIndex);
	}

	/** AOSP: the advance from start to offset within the run text[start, end), measured in its context. */
	public float getRunAdvance(char[] text, int start, int end, int contextStart, int contextEnd, boolean isRtl, int offset) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((contextStart | start | offset | end | contextEnd | start - contextStart | offset - start | end - offset
				| contextEnd - end | text.length - contextEnd) < 0)
			throw new IndexOutOfBoundsException();
		if (end == start)
			return 0f;
		float[] all = new float[contextEnd - contextStart];
		native_get_text_advances(paint, text, contextStart, contextEnd - contextStart, all, 0);
		float sum = 0f;
		for (int i = start; i < offset; i++)
			sum += all[i - contextStart];
		return sum * mTextScaleX;
	}

	public float getRunAdvance(CharSequence text, int start, int end, int contextStart, int contextEnd, boolean isRtl, int offset) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((contextStart | start | offset | end | contextEnd | start - contextStart | offset - start | end - offset
				| contextEnd - end | text.length() - contextEnd) < 0)
			throw new IndexOutOfBoundsException();
		if (end == start)
			return 0f;
		char[] buf = new char[contextEnd - contextStart];
		android.text.TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
		return getRunAdvance(buf, start - contextStart, end - contextStart, 0, contextEnd - contextStart, isRtl, offset - contextStart);
	}

	/** AOSP: the character offset whose position is closest to advance (cluster boundaries only). */
	public int getOffsetForAdvance(char[] text, int start, int end, int contextStart, int contextEnd, boolean isRtl, float advance) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((contextStart | start | end | contextEnd | start - contextStart | end - start | contextEnd - end
				| text.length - contextEnd) < 0)
			throw new IndexOutOfBoundsException();
		float[] all = new float[contextEnd - contextStart];
		if (contextEnd > contextStart)
			native_get_text_advances(paint, text, contextStart, contextEnd - contextStart, all, 0);
		float x = 0f;
		int best = start;
		float bestDistance = Math.abs(advance);
		for (int i = start; i < end; i++) {
			float w = all[i - contextStart] * mTextScaleX;
			if (w == 0f && i > start)
				continue; /* not a cluster boundary */
			x += w;
			float distance = Math.abs(advance - x);
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = i + 1;
			}
		}
		return best;
	}

	public int getOffsetForAdvance(CharSequence text, int start, int end, int contextStart, int contextEnd, boolean isRtl, float advance) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		char[] buf = new char[contextEnd - contextStart];
		android.text.TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
		return getOffsetForAdvance(buf, start - contextStart, end - contextStart, 0, contextEnd - contextStart, isRtl, advance) + contextStart;
	}

	/* teslalinux: shadow layer and elegant text height state (AOSP Paint) */
	private float mShadowLayerRadius;
	private float mShadowLayerDx;
	private float mShadowLayerDy;
	private int mShadowLayerColor;
	private boolean mElegantTextHeight;

	public float getShadowLayerRadius() {
		return mShadowLayerRadius;
	}

	public float getShadowLayerDx() {
		return mShadowLayerDx;
	}

	public float getShadowLayerDy() {
		return mShadowLayerDy;
	}

	public int getShadowLayerColor() {
		return mShadowLayerColor;
	}

	public long getShadowLayerColorLong() {
		return Color.pack(mShadowLayerColor);
	}

	public void setShadowLayer(float radius, float dx, float dy, long color) {
		setShadowLayer(radius, dx, dy, Color.toArgb(color));
	}

	/** AOSP: whether tall scripts use their elegant (taller) variant fonts; Pango has no such variant switch, the flag is kept. */
	public boolean isElegantTextHeight() {
		return mElegantTextHeight;
	}

	public void setElegantTextHeight(boolean elegant) {
		mElegantTextHeight = elegant;
	}
	/* AOSP (hidden): compatibility scaling for apps that run in screen-compatibility mode (factor 1 here) */
	private boolean mHasCompatScaling;
	private float mCompatScaling = 1f, mInvCompatScaling = 1f;

	public void setCompatibilityScaling(float factor) {
		if (factor == 1.0f) {
			mHasCompatScaling = false;
			mCompatScaling = mInvCompatScaling = 1.0f;
		} else {
			mHasCompatScaling = true;
			mCompatScaling = factor;
			mInvCompatScaling = 1.0f / factor;
		}
	}

	/* teslalinux: blend-mode colour filter with the real per-mode result (native-paint-colorfilter-modes.sh) */
	private static native void native_set_color_filter_mode(long paint, int mode, int color);

	/* ---- teslalinux (gfx): path effects, mask filters, text skew, stroke miter (native/atl_gfx_canvas.c) ---- */
	private PathEffect mPathEffect;
	private MaskFilter mMaskFilter;
	private float mTextSkewX;
	private String mFontVariationSettings;
	private static native void native_set_font_variations(long paint, String pangoVariations);

	private static native void native_set_stroke_miter(long paint, float miter);
	private static native float native_get_stroke_miter(long paint);
	private static native void native_set_dash(long paint, float[] intervals, float phase);
	private static native void native_set_color_matrix(long paint, float[] matrix);

	/** AOSP's Paint copy covers every setting; the native clone holds colour, stroke and font, this the rest. */
	private void tlCopyJavaState(Paint src) {
		xfermode = src.xfermode;
		shader = src.shader;
		align = src.align;
		color_filter = src.color_filter;
		tlAntiAlias = src.tlAntiAlias;
		mTypeface = src.mTypeface;
		mLocales = src.mLocales;
		mFontFeatureSettings = src.mFontFeatureSettings;
		mFlags = src.mFlags;
		mHinting = src.mHinting;
		mLetterSpacing = src.mLetterSpacing;
		mTextScaleX = src.mTextScaleX;
		mShadowLayerRadius = src.mShadowLayerRadius;
		mShadowLayerDx = src.mShadowLayerDx;
		mShadowLayerDy = src.mShadowLayerDy;
		mShadowLayerColor = src.mShadowLayerColor;
		mElegantTextHeight = src.mElegantTextHeight;
		mHasCompatScaling = src.mHasCompatScaling;
		mCompatScaling = src.mCompatScaling;
		mInvCompatScaling = src.mInvCompatScaling;
		mPathEffect = src.mPathEffect;
		mMaskFilter = src.mMaskFilter;
		mTextSkewX = src.mTextSkewX;
		mFontVariationSettings = src.mFontVariationSettings;
	}

	private void tlResetGfx() {
		mPathEffect = null;
		mMaskFilter = null;
		mTextSkewX = 0;
	}

	/**
	 * The transfer mode of the paint as an SkBlendMode value, or -1 for plain source-over drawing
	 * (no xfermode, or SRC_OVER).
	 */
	public int tlBlendModeValue() {
		if (xfermode instanceof PorterDuffXfermode) {
			int m = ((PorterDuffXfermode) xfermode).porterDuffMode;
			return m == PorterDuff.Mode.SRC_OVER.nativeInt ? -1 : m;
		}
		return -1;
	}
}
