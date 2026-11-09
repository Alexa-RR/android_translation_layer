package android.text;

import android.graphics.Typeface;
import android.graphics.Paint;
import android.text.style.CharacterStyle;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * teslalinux (@hide): the Pango attributes of text that Pango lays out and draws (a TextView's
 * GtkLabel, an android.text.Layout). AOSP draws text with the TextPaint a view or layout was given
 * and lets every CharacterStyle span update a copy of that paint (updateDrawState); here the paint's
 * face and each span's effect are turned into Pango attributes instead. The encoding (type, start,
 * end, value; start/end in UTF-16 offsets, end -1 for "to the end") is decoded by
 * tl_text_attr_list() in android_text_Layout.c (native-text-layout.sh).
 */
public final class TlTextAttrs {
	public static final int A_SIZE = 1, A_FG = 2, A_BG = 3, A_WEIGHT = 4, A_ITALIC = 5, A_UNDERLINE = 6, A_STRIKE = 7,
		A_FAMILY = 8, A_LETTERSP = 9, A_RISE = 10, A_SCALE = 11, A_LINEHEIGHT = 12, A_FEATURES = 13, A_STRETCH = 14,
		A_LANGUAGE = 15, A_VARIATIONS = 16;

	private int[] a = new int[64];
	private int n;
	private final ArrayList<String> strings = new ArrayList<String>();
	private SpanPaint spanPaint;

	public void reset() {
		n = 0;
		strings.clear();
	}

	public void add(int type, int start, int end, int value) {
		if (n + 4 > a.length)
			a = Arrays.copyOf(a, a.length * 2);
		a[n++] = type;
		a[n++] = start;
		a[n++] = end;
		a[n++] = value;
	}

	public void addFloat(int type, int start, int end, float value) {
		add(type, start, end, Float.floatToIntBits(value));
	}

	public void addString(int type, int start, int end, String value) {
		strings.add(value);
		add(type, start, end, strings.size() - 1);
	}

	public boolean isEmpty() {
		return n == 0;
	}

	public int[] attrs() {
		return Arrays.copyOf(a, n);
	}

	public String[] strings() {
		return strings.toArray(new String[strings.size()]);
	}

	/** Pango face for an AOSP typeface: family alias (AOSP fonts.xml), weight, slant, width, features */
	public static final class Face {
		public String family;
		public int weight = 400;
		public boolean italic;
		public int stretch = 4; /* PANGO_STRETCH_NORMAL */
		public String features;

		public static Face of(Typeface tf, boolean fakeBold, float skewX) {
			Face f = new Face();
			if (tf != null) {
				f.weight = tf.getWeight();
				f.italic = tf.isItalic();
				String name = tf.getSystemFontFamilyName();
				if (name != null) {
					int aliasWeight = -1;
					switch (name) {
						case "sans-serif-thin": aliasWeight = 100; name = "sans-serif"; break;
						case "sans-serif-light": aliasWeight = 300; name = "sans-serif"; break;
						case "sans-serif-medium": aliasWeight = 500; name = "sans-serif"; break;
						case "sans-serif-black": aliasWeight = 900; name = "sans-serif"; break;
						case "sans-serif-condensed": f.stretch = 2; name = "sans-serif"; break;
						case "sans-serif-condensed-light": f.stretch = 2; aliasWeight = 300; name = "sans-serif"; break;
						case "sans-serif-condensed-medium": f.stretch = 2; aliasWeight = 500; name = "sans-serif"; break;
						case "sans-serif-smallcaps": f.features = "\"smcp\" 1"; name = "sans-serif"; break;
						case "serif-monospace": name = "monospace"; break;
						case "casual": name = "cursive"; break;
						default: break;
					}
					if (aliasWeight > 0 && !tf.isBold())
						f.weight = aliasWeight;
					f.family = name;
				}
			}
			if (fakeBold && f.weight < 700)
				f.weight = 700;
			if (skewX != 0)
				f.italic = true;
			return f;
		}
	}

	/** the attributes of face f where it differs from base (base null: from Pango's defaults) */
	public void face(Face f, Face base, int start, int end) {
		if (f.family != null && (base == null || !f.family.equals(base.family)))
			addString(A_FAMILY, start, end, f.family);
		if (base == null ? f.weight != 400 : f.weight != base.weight)
			add(A_WEIGHT, start, end, f.weight);
		if (base == null ? f.italic : f.italic != base.italic)
			add(A_ITALIC, start, end, f.italic ? 1 : 0);
		if (base == null ? f.stretch != 4 : f.stretch != base.stretch)
			add(A_STRETCH, start, end, f.stretch);
		if (f.features != null && (base == null || !f.features.equals(base.features)))
			addString(A_FEATURES, start, end, f.features);
	}

	/** a paint that records what a span's updateDrawState sets */
	private static final class SpanPaint extends TextPaint {
		int color;
		float size;
		Typeface face;
		boolean underline, strike, fakeBold;
		float skew, letterSpacing, scaleX = 1f;

		@Override
		public void setColor(int c) {
			color = c;
		}
		@Override
		public int getColor() {
			return color;
		}
		@Override
		public void setAlpha(int al) {
			color = (color & 0x00ffffff) | ((al & 0xff) << 24);
		}
		@Override
		public int getAlpha() {
			return color >>> 24;
		}
		@Override
		public void setTextSize(float s) {
			size = s;
			super.setTextSize(s);
		}
		@Override
		public float getTextSize() {
			return size;
		}
		@Override
		public Typeface setTypeface(Typeface tf) {
			face = tf;
			return tf;
		}
		@Override
		public Typeface getTypeface() {
			return face;
		}
		@Override
		public void setUnderlineText(boolean u) {
			underline = u;
		}
		@Override
		public void setStrikeThruText(boolean s) {
			strike = s;
		}
		@Override
		public void setFakeBoldText(boolean b) {
			fakeBold = b;
		}
		@Override
		public boolean isFakeBoldText() {
			return fakeBold;
		}
		@Override
		public void setTextSkewX(float s) {
			skew = s;
		}
		@Override
		public float getTextSkewX() {
			return skew;
		}
		@Override
		public void setLetterSpacing(float s) {
			letterSpacing = s;
		}
		@Override
		public float getLetterSpacing() {
			return letterSpacing;
		}
		@Override
		public void setTextScaleX(float s) {
			scaleX = s;
		}
		@Override
		public float getTextScaleX() {
			return scaleX;
		}
	}

	/**
	 * Every CharacterStyle span of sp, applied the AOSP way (updateDrawState on a paint holding the
	 * base state) and turned into the attributes where the result differs from the base.
	 * RelativeSizeSpan becomes a Pango scale so nested sizes compose as in AOSP.
	 */
	public void spans(Spanned sp, Face base, Typeface baseFace, float baseSize, int baseColor, int linkColor,
			float baseLetterSpacing, float density, int[] drawableState) {
		final Object[] spans = sp.getSpans(0, sp.length(), CharacterStyle.class);
		if (spans == null || spans.length == 0)
			return;
		if (spanPaint == null)
			spanPaint = new SpanPaint();
		final SpanPaint tp = spanPaint;
		for (Object o : spans) {
			final int s = sp.getSpanStart(o), e = sp.getSpanEnd(o);
			if (s < 0 || e <= s)
				continue;
			if (o instanceof RelativeSizeSpan) {
				addFloat(A_SCALE, s, e, ((RelativeSizeSpan) o).getSizeChange());
				continue;
			}
			tp.color = baseColor;
			tp.setTextSize(baseSize);
			tp.face = baseFace;
			tp.underline = tp.strike = tp.fakeBold = false;
			tp.skew = 0;
			tp.letterSpacing = baseLetterSpacing;
			tp.scaleX = 1f;
			tp.bgColor = 0;
			tp.baselineShift = 0;
			tp.linkColor = linkColor;
			tp.density = density;
			tp.drawableState = drawableState;
			try {
				((CharacterStyle) o).updateDrawState(tp);
			} catch (RuntimeException ex) {
				continue;
			}
			if (tp.color != baseColor)
				add(A_FG, s, e, tp.color);
			if (tp.bgColor != 0)
				add(A_BG, s, e, tp.bgColor);
			if (tp.underline)
				add(A_UNDERLINE, s, e, 1);
			if (tp.strike)
				add(A_STRIKE, s, e, 1);
			if (tp.size != baseSize && tp.size > 0)
				addFloat(A_SIZE, s, e, tp.size);
			if (tp.face != baseFace || tp.fakeBold || tp.skew != 0)
				face(Face.of(tp.face, tp.fakeBold, tp.skew), base, s, e);
			if (tp.letterSpacing != baseLetterSpacing)
				addFloat(A_LETTERSP, s, e, tp.letterSpacing * (tp.size > 0 ? tp.size : baseSize));
			if (tp.baselineShift != 0)
				addFloat(A_RISE, s, e, -tp.baselineShift);
		}
	}

	/** AOSP LineHeightSpan (Compose's line height styles): the height the span chooses for the
	 * font's line becomes the Pango line height of its paragraph(s). AOSP asks the span once per
	 * line; Pango has one line height per attribute range, so it is asked once for the whole span. */
	public void lineHeights(Spanned sp, TextPaint basePaint) {
		final LineHeightSpan[] spans = sp.getSpans(0, sp.length(), LineHeightSpan.class);
		if (spans == null)
			return;
		for (LineHeightSpan o : spans) {
			final int s = sp.getSpanStart(o), e = sp.getSpanEnd(o);
			if (s < 0 || e <= s)
				continue;
			final Paint.FontMetricsInt fm = new Paint.FontMetricsInt();
			basePaint.getFontMetricsInt(fm);
			final int natural = fm.descent - fm.ascent;
			try {
				if (o instanceof LineHeightSpan.WithDensity)
					((LineHeightSpan.WithDensity) o).chooseHeight(sp, s, e, 0, natural, fm, basePaint);
				else
					o.chooseHeight(sp, s, e, 0, natural, fm);
			} catch (RuntimeException ex) {
				continue;
			}
			final int h = fm.descent - fm.ascent;
			if (h > 0 && h != natural)
				addFloat(A_LINEHEIGHT, s, e, h);
		}
	}
}
