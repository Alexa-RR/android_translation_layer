package android.text;

import android.atl.GskCanvas;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

public class Layout {

	public enum Alignment {
		ALIGN_NORMAL,
		ALIGN_OPPOSITE,
		ALIGN_CENTER,
		ALIGN_LEFT,
		ALIGN_RIGHT,
	}

	public class Directions {}

	long layout; // native PangoLayout
	private CharSequence text;
	private TextPaint paint;
	private float spacing_mult;
	private float spacing_add;
	private Alignment align;

	protected Layout(CharSequence text, TextPaint paint, int width, Layout.Alignment align, float spacingMult, float spacingAdd) {
		this.text = text;
		this.paint = paint;
		this.spacing_mult = spacingMult;
		this.spacing_add = spacingAdd;
		this.align = align;
		layout = native_constructor(text != null ? text.toString() : "", paint.paint, width);
		tlApplyStyle(); /* teslalinux (+text) */
	}

	public int getLineCount() {
		return native_get_line_count(layout);
	}

	public float getLineWidth(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_line_width(layout, line);
	}

	public TextPaint getPaint() {
		return paint;
	}

	public int getEllipsisCount(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_ellipsis_count(layout, line);
	}

	public CharSequence getText() { return text; }

	public int getWidth() {
		return native_get_width(layout);
	}

	public int getHeight() {
		return native_get_height(layout);
	}

	public void draw(Canvas canvas) {
		if (canvas instanceof GskCanvas)
			native_draw(layout, ((GskCanvas)canvas).snapshot, paint.paint);
		else
			native_draw_custom_canvas(layout, canvas, paint);
	}

	public void draw(Canvas canvas, Path selectionHighlight, Paint paint, int selectionOffset) {
		draw(canvas);
	}

	/* teslalinux (+text) 1: getParagraphDirection is in Layout.java+text.frag */

	public static float getDesiredWidth(CharSequence source, int start, int end, TextPaint paint) {
		return getDesiredWidth(source.subSequence(start, end), paint);
	}

	public static float getDesiredWidth(CharSequence source, TextPaint paint) {
		long layout = native_constructor(source != null ? source.toString() : "", paint.paint, -1);
		float width = native_get_desired_width(layout);
		native_free(layout);
		return width;
	}

	public int getLineBaseline(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_line_baseline(layout, line);
	}

	public int getLineAscent(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_line_ascent(layout, line);
	}

	public int getLineDescent(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_line_descent(layout, line);
	}

	/* teslalinux (+text) 2: getTopPadding/getBottomPadding/isRtlCharAt are in Layout.java+text.frag */

	public float getSecondaryHorizontal(int offset) {
		if (getLineDirections(0) == null)
			throw new NullPointerException();
		return native_get_secondary_horizontal(layout, offset);
	}

	public int getLineForVertical(int y) {
		return native_get_line_for_vertical(layout, y);
	}

	public int getOffsetForHorizontal(int line, float x) {
		if (getLineDirections(0) == null)
			throw new NullPointerException();
		return native_get_offset_for_horizontal(layout, line, x);
	}

	public float getPrimaryHorizontal(int offset) {
		if (getLineDirections(0) == null)
			throw new NullPointerException();
		return native_get_primary_horizontal(layout, offset);
	}

	public int getLineForOffset(int offset) {
		return native_get_line_for_offset(layout, offset);
	}

	public int getLineTop(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_line_top(layout, line);
	}

	public int getLineBottom(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_line_bottom(layout, line);
	}

	public float getLineLeft(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_line_left(layout, line);
	}

	public float getLineRight(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_line_right(layout, line);
	}

	public int getLineStart(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_line_start(layout, line);
	}

	public int getLineEnd(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_line_end(layout, line);
	}

	public boolean isSpanned() {
		return text instanceof Spanned;
	}

	public void increaseWidthTo(int width) {
		if (width < getWidth())
			throw new RuntimeException("cannot decrease width");
		native_set_width(layout, width);
	}

	public float getSpacingMultiplier() {
		return spacing_mult;
	}

	public float getSpacing_add() {
		return spacing_add;
	}

	public void getSelectionPath(int start, int end, Path path) {
		if (start != end && getLineDirections(0) == null)
			throw new NullPointerException();
	}

	public int getParagraphRight(int line) {
		return getWidth();
	}

	public int getParagraphLeft(int line) {
		return 0;
	}

	public Alignment getParagraphAlignment(int line) {
		return align;
	}

	public int getOffsetToRightOf(int offset) {
		if (getLineDirections(0) == null)
			throw new NullPointerException();
		return offset;
	}

	public int getOffsetToLeftOf(int offset) {
		if (getLineDirections(0) == null)
			throw new NullPointerException();
		return offset;
	}

	public Directions getLineDirections(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return new Directions();
	}

	public int getLineVisibleEnd(int line) {
		return getLineEnd(line);
	}

	public boolean getLineContainsTab(int line) { return text.toString().split("\n")[line].contains("\t"); }

	public int getEllipsizedWidth() {
		return getWidth();
	}

	/* teslalinux (+text) 3: getEllipsisStart is in Layout.java+text.frag */

	protected static native long native_constructor(String text, long paint, int width);
	protected native void native_set_width(long layout, int width);
	protected native int native_get_width(long layout);
	protected native int native_get_height(long layout);
	protected native int native_get_line_count(long layout);
	protected native int native_get_line_start(long layout, int line);
	protected native int native_get_line_end(long layout, int line);
	protected native int native_get_line_top(long layout, int line);
	protected native int native_get_line_bottom(long layout, int line);
	protected native int native_get_line_left(long layout, int line);
	protected native int native_get_line_right(long layout, int line);
	protected native int native_get_line_width(long layout, int line);
	protected native int native_get_line_baseline(long layout, int line);
	protected native int native_get_line_ascent(long layout, int line);
	protected native int native_get_line_descent(long layout, int line);
	protected native int native_get_line_for_vertical(long layout, int y);
	protected native void native_set_ellipsize(long layout, int ellipsize_mode, float ellipsize_width);
	protected native int native_get_ellipsis_count(long layout, int line);
	protected native void native_draw(long layout, long snapshot, long paint);
	protected native void native_draw_custom_canvas(long layout, Canvas canvas, Paint paint);
	protected native int native_get_line_for_offset(long layout, int offset);
	protected native float native_get_primary_horizontal(long layout, int offset);
	protected native float native_get_secondary_horizontal(long layout, int offset);
	protected native int native_get_offset_for_horizontal(long layout, int line, float x);
	protected static native float native_get_desired_width(long layout);
	protected static native void native_free(long layout);

	/** Break strategy, hyphenation and justification constants (AOSP Layout, API 23/26). */
	public static final int BREAK_STRATEGY_SIMPLE = 0;
	public static final int BREAK_STRATEGY_HIGH_QUALITY = 1;
	public static final int BREAK_STRATEGY_BALANCED = 2;
	public static final int HYPHENATION_FREQUENCY_NONE = 0;
	public static final int HYPHENATION_FREQUENCY_NORMAL = 1;
	public static final int HYPHENATION_FREQUENCY_FULL = 2;
	public static final int HYPHENATION_FREQUENCY_NORMAL_FAST = 3;
	public static final int HYPHENATION_FREQUENCY_FULL_FAST = 4;
	public static final int JUSTIFICATION_MODE_NONE = 0;
	public static final int JUSTIFICATION_MODE_INTER_WORD = 1;
	/** Default text-direction heuristic for layouts (AOSP). */
	public static final TextDirectionHeuristic DEFAULT_TEXT_DIRECTION = TextDirectionHeuristics.FIRSTSTRONG_LTR;

	/**
	 * AOSP (API 34): decides whether a text segment counts as inside an area, for
	 * {@link #getRangeForRect} and the text-bounds queries of input methods.
	 */
	public interface TextInclusionStrategy {
		boolean isSegmentInside(android.graphics.RectF segmentBounds, android.graphics.RectF area);
	}

	/** AOSP: a segment is inside when its bounds overlap the area at all. */
	public static final TextInclusionStrategy INCLUSION_STRATEGY_ANY_OVERLAP = new TextInclusionStrategy() {
		@Override
		public boolean isSegmentInside(android.graphics.RectF segmentBounds, android.graphics.RectF area) {
			return android.graphics.RectF.intersects(segmentBounds, area);
		}
	};

	/** AOSP: a segment is inside when its centre point is inside the area. */
	public static final TextInclusionStrategy INCLUSION_STRATEGY_CONTAINS_CENTER = new TextInclusionStrategy() {
		@Override
		public boolean isSegmentInside(android.graphics.RectF segmentBounds, android.graphics.RectF area) {
			return area.contains(segmentBounds.centerX(), segmentBounds.centerY());
		}
	};

	/** AOSP: a segment is inside when the area contains all of it. */
	public static final TextInclusionStrategy INCLUSION_STRATEGY_CONTAINS_ALL = new TextInclusionStrategy() {
		@Override
		public boolean isSegmentInside(android.graphics.RectF segmentBounds, android.graphics.RectF area) {
			return area.contains(segmentBounds);
		}
	};
	/** AOSP: the width of a run of text; the direction heuristic changes shaping only for bidi text, which Pango resolves itself. */
	public static float getDesiredWidth(CharSequence source, int start, int end, TextPaint paint, TextDirectionHeuristic textDir) {
		return getDesiredWidth(source, start, end, paint);
	}

	/* ==== teslalinux (+text): AOSP Layout behaviour on the PangoLayout (native-text-layout.sh) ==== */
	protected native void native_set_text_attrs(long layout, String text, int[] attrs, String[] strings);
	protected native void native_set_max_lines(long layout, int lines);
	protected native void native_set_alignment(long layout, int align);
	protected native int native_get_ellipsis_start(long layout, int line);

	/** @hide a Layout over an existing PangoLayout (a TextView's GtkLabel layout), which it owns */
	public static Layout tlWrapNative(long pangoLayout, CharSequence text, TextPaint paint, Alignment align, float spacingMult, float spacingAdd) {
		return new Layout(pangoLayout, text, paint, align, spacingMult, spacingAdd);
	}

	private Layout(long pangoLayout, CharSequence text, TextPaint paint, Alignment align, float spacingMult, float spacingAdd) {
		this.text = text;
		this.paint = paint;
		this.spacing_mult = spacingMult;
		this.spacing_add = spacingAdd;
		this.align = align;
		this.layout = pangoLayout;
	}

	/** the paint's face, the spans, the line spacing and the alignment as Pango attributes, like
	 * AOSP draws a layout's text with its TextPaint and the spans' updateDrawState */
	private void tlApplyStyle() {
		if (layout == 0)
			return;
		native_set_alignment(layout, align != null ? align.ordinal() : 0);
		final TlTextAttrs at = new TlTextAttrs();
		final TlTextAttrs.Face base = TlTextAttrs.Face.of(paint.getTypeface(), paint.isFakeBoldText(), paint.getTextSkewX());
		at.face(base, null, 0, -1);
		final float size = paint.getTextSize();
		final float ls = paint.getLetterSpacing();
		if (ls != 0 && size > 0)
			at.addFloat(TlTextAttrs.A_LETTERSP, 0, -1, ls * size);
		final int flags = paint.getFlags();
		if ((flags & android.graphics.Paint.UNDERLINE_TEXT_FLAG) != 0)
			at.add(TlTextAttrs.A_UNDERLINE, 0, -1, 1);
		if ((flags & android.graphics.Paint.STRIKE_THRU_TEXT_FLAG) != 0)
			at.add(TlTextAttrs.A_STRIKE, 0, -1, 1);
		if (spacing_mult != 1.0f || spacing_add != 0.0f) {
			final int lh = Math.round(paint.getFontMetricsInt(null) * spacing_mult + spacing_add);
			if (lh > 0)
				at.addFloat(TlTextAttrs.A_LINEHEIGHT, 0, -1, lh);
		}
		if (text instanceof Spanned) {
			at.spans((Spanned) text, base, paint.getTypeface(), size, paint.getColor(), paint.linkColor, ls, paint.density, paint.drawableState);
			at.lineHeights((Spanned) text, paint);
		}
		if (!at.isEmpty())
			native_set_text_attrs(layout, text != null ? text.toString() : "", at.attrs(), at.strings());
	}

	/** @hide the PangoEllipsizeMode for an AOSP truncation (START and MIDDLE need a single line) */
	protected static int tlPangoEllipsize(TextUtils.TruncateAt where, int maxLines) {
		if (where == null)
			return 0;
		switch (where) {
			case START:
				return maxLines == 1 ? 1 : 0;
			case MIDDLE:
				return maxLines == 1 ? 2 : 0;
			default:
				return 3; /* END, END_SMALL, MARQUEE (an end ellipsis while not scrolling) */
		}
	}

	/** AOSP (hidden): the extra space a line got from line spacing */
	public int getLineExtra(int line) {
		int extra = (getLineBottom(line) - getLineTop(line)) - (getLineDescent(line) - getLineAscent(line));
		return Math.max(0, extra);
	}

	private int tlBidiStart;

	private java.text.Bidi tlBidi(int line) {
		final CharSequence t = text != null ? text : "";
		final int start = Math.min(getLineStart(line), t.length());
		int pstart = start;
		while (pstart > 0 && t.charAt(pstart - 1) != '\n')
			pstart--;
		int pend = start;
		while (pend < t.length() && t.charAt(pend) != '\n')
			pend++;
		tlBidiStart = pstart;
		return new java.text.Bidi(t.subSequence(pstart, pend).toString(), java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
	}

	/** AOSP: the direction of the line's paragraph (first strong character, LTR by default) */
	public int getParagraphDirection(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return tlBidi(line).baseIsLeftToRight() ? DIR_LEFT_TO_RIGHT : DIR_RIGHT_TO_LEFT;
	}

	/** AOSP: whether the character at offset runs right to left (odd bidi level) */
	public boolean isRtlCharAt(int offset) {
		final CharSequence t = text != null ? text : "";
		if (offset < 0 || offset >= t.length())
			return false;
		final java.text.Bidi bidi = tlBidi(getLineForOffset(offset));
		final int i = offset - tlBidiStart;
		return i >= 0 && i < bidi.getLength() && (bidi.getLevelAt(i) & 1) != 0;
	}

	/** AOSP: where the ellipsis starts on the line, relative to the line start */
	public int getEllipsisStart(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_ellipsis_start(layout, line);
	}

	/** AOSP Layout: the base class has no font padding. Pango sizes lines by the fonts' ascent and
	 * descent and adds no top/bottom padding, so subclasses report 0 too (the values must match the
	 * layout that is drawn; Compose offsets every line by them). */
	public int getTopPadding() {
		return 0;
	}

	public int getBottomPadding() {
		return 0;
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			if (layout != 0) {
				native_free(layout);
				layout = 0;
			}
		} finally {
			super.finalize();
		}
	}

	public static final int DIR_LEFT_TO_RIGHT = 1;
	public static final int DIR_RIGHT_TO_LEFT = -1;

	/**
	 * AOSP (API 34): the range of text segments (from segmentFinder) inside area, judged by
	 * inclusionStrategy on each segment's bounds; null when there is none. Segment bounds are the
	 * cursor positions of its ends on its line, from the line top to the line bottom.
	 */
	public int[] getRangeForRect(android.graphics.RectF area, SegmentFinder segmentFinder, TextInclusionStrategy inclusionStrategy) {
		final int lineCount = getLineCount();
		if (lineCount == 0)
			return null;
		int startLine = getLineForVertical((int) area.top);
		if (area.top > getLineBottom(startLine)) {
			startLine++;
			if (startLine >= lineCount)
				return null;
		}
		int endLine = getLineForVertical((int) area.bottom);
		if (area.bottom < getLineTop(endLine)) {
			endLine--;
			if (endLine < 0)
				return null;
		}
		if (startLine > endLine)
			return null;
		int start = -1, end = -1;
		final android.graphics.RectF bounds = new android.graphics.RectF();
		for (int line = startLine; line <= endLine; line++) {
			final int lineStart = getLineStart(line);
			final int lineEnd = getLineEnd(line);
			int segStart = lineStart > 0 ? segmentFinder.nextStartBoundary(lineStart - 1) : segmentFinder.nextStartBoundary(-1);
			if (segStart == SegmentFinder.DONE || segStart < lineStart)
				segStart = segmentFinder.nextStartBoundary(lineStart);
			while (segStart != SegmentFinder.DONE && segStart < lineEnd) {
				int segEnd = segmentFinder.nextEndBoundary(segStart);
				if (segEnd == SegmentFinder.DONE)
					break;
				segEnd = Math.min(segEnd, lineEnd);
				final float x1 = getPrimaryHorizontal(segStart);
				final float x2 = segEnd >= lineEnd ? (isRtlCharAt(segStart) ? getLineLeft(line) : getLineRight(line)) : getPrimaryHorizontal(segEnd);
				bounds.set(Math.min(x1, x2), getLineTop(line), Math.max(x1, x2), getLineBottom(line));
				if (inclusionStrategy.isSegmentInside(bounds, area)) {
					if (start == -1 || segStart < start)
						start = segStart;
					if (segEnd > end)
						end = segEnd;
				}
				segStart = segmentFinder.nextStartBoundary(segStart);
			}
		}
		return start == -1 ? null : new int[] {start, end};
	}
}
