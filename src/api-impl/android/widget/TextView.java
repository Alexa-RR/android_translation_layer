package android.widget;

import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.BoringLayout;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.BaseMovementMethod;
import android.text.method.KeyListener;
import android.text.method.MovementMethod;
import android.text.method.TransformationMethod;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;

public class TextView extends View {
	private ColorStateList colors = new ColorStateList(new int[][] {new int[0]}, new int[1]);
	private CharSequence text = "";
	private TextPaint paint = new TextPaint();
	private boolean include_padding = false;
	private int break_strategy = 0 /*BREAK_STRATEGY_SIMPLE*/;
	private int hyphenation_frequency = 0 /*HYPHENATION_FREQUENCY_NONE*/;
	private int gravity = Gravity.TOP | Gravity.START; // AOSP default

	public TextView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public TextView(Context context) {
		this(context, null);
	}

	public TextView(Context context, AttributeSet attrs, int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public TextView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);

		TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.TextView, defStyleAttr, 0);
		try {
			if (a.hasValue(com.android.internal.R.styleable.TextView_gravity)) {
				setGravity(a.getInt(com.android.internal.R.styleable.TextView_gravity, Gravity.TOP | Gravity.START));
			}
			if (a.hasValue(com.android.internal.R.styleable.TextView_text)) {
				setText(a.getText(com.android.internal.R.styleable.TextView_text));
			}
			if (a.hasValue(com.android.internal.R.styleable.TextView_hint)) {
				setHint(a.getText(com.android.internal.R.styleable.TextView_hint));
			}

			int ap = a.getResourceId(com.android.internal.R.styleable.TextView_textAppearance, -1);
			if (ap != -1) {
				TypedArray aa = context.obtainStyledAttributes(ap, com.android.internal.R.styleable.TextAppearance);
				if (aa.hasValue(com.android.internal.R.styleable.TextAppearance_textColor)) {
					setTextColor(aa.getColorStateList(com.android.internal.R.styleable.TextAppearance_textColor));
				}
				if (aa.hasValue(com.android.internal.R.styleable.TextAppearance_textSize)) {
					setTextSize(aa.getDimensionPixelSize(com.android.internal.R.styleable.TextAppearance_textSize, 10));
				}
				aa.recycle();
			}
			if (a.hasValue(com.android.internal.R.styleable.TextView_textColor)) {
				setTextColor(a.getColorStateList(com.android.internal.R.styleable.TextView_textColor));
			}
			if (a.hasValue(com.android.internal.R.styleable.TextView_textSize)) {
				setTextSize(a.getDimensionPixelSize(com.android.internal.R.styleable.TextView_textSize, 10));
			}

			if (a.hasValue(com.android.internal.R.styleable.TextView_textStyle)) {
				int textStyle = a.getInt(com.android.internal.R.styleable.TextView_textStyle, 0);
				setTypeface(getTypeface(), textStyle);
			}

			if (a.hasValue(com.android.internal.R.styleable.TextView_textAllCaps)) {
				boolean allCaps = a.getBoolean(com.android.internal.R.styleable.TextView_textAllCaps, false);
				setAllCaps(allCaps);
			}
		} catch (java.lang.Exception e) {
			System.out.println("exception while inflating TextView:");
			e.printStackTrace();
		}

		a.recycle();
		haveCustomMeasure = false;
	}

	@Override
	protected native long native_constructor(Context context, AttributeSet attrs);

	public void setText(int resId) {
		setText(getContext().getResources().getText(resId));
	}

	public void setText(CharSequence text) {
		setText(text, BufferType.NORMAL);
	}

	public void setText(CharSequence text, BufferType type) {
		this.text = text == null ? "" : text;
		native_setText(text != null ? text.toString() : null);

		if (text instanceof android.text.Spanned)
			native_set_markup(1);
		if (!isLayoutRequested())
			requestLayout();
	}

	public void setText(char[] text, int start, int len) {
		setText(new String(text, start, len));
	}

	public final void append(CharSequence text) {
		append(text, 0, text.length());
	}

	public void append(CharSequence text, int start, int end) {
		setText(SpannableStringBuilder.valueOf(this.text).append(text, start, end));
	}

	private native final void native_set_markup(int bool);

	public native final void native_setText(String text);

	public void setTextSize(int unit, float size) {
		if (unit != TypedValue.COMPLEX_UNIT_SP)
			System.out.println("setTextSize called with non-SP unit (" + unit + "), we don't currently handle that");
		setTextSize(size);
	}
	public native void setTextSize(float size);

	public native final void native_setTextColor(int color);
	public void setTextColor(int color) {
		native_setTextColor(color);
	}
	public void setTextColor(ColorStateList colors) {
		if (colors != null) {
			this.colors = colors;
			native_setTextColor(colors.getDefaultColor()); // TODO: do this properly
		}
	}
	public void setTypeface(Typeface tf, int style) {
		String[] classesToRemove = {"ATL-font-bold", "ATL-font-italic"};
		native_removeClasses(widget, classesToRemove);

		switch (style) {
			case Typeface.BOLD:
				native_addClass(widget, "ATL-font-bold");
				break;
			case Typeface.ITALIC:
				native_addClass(widget, "ATL-font-italic");
				break;
			case Typeface.BOLD_ITALIC:
				native_addClass(widget, "ATL-font-bold");
				native_addClass(widget, "ATL-font-italic");
				break;
			default:
				break;
		}
	}
	public void setTypeface(Typeface tf) {}
	public void setLineSpacing(float add, float mult) {}
	public final void setLinksClickable(boolean whether) {}

	public void setInputType(int type) {}
	public void setFilters(InputFilter[] filters) {}
	public void setCursorVisible(boolean visible) {}
	public void setImeOptions(int imeOptions) {}

	public final ColorStateList getTextColors() { return colors; }
	public static ColorStateList getTextColors(Context context, TypedArray attrs) { return new ColorStateList(new int[][] {new int[0]}, new int[1]); }

	public TextPaint getPaint() {
		return paint;
	}

	public void addTextChangedListener(TextWatcher watcher) {}
	public void removeTextChangedListener(TextWatcher watcher) {}
	protected TextView.OnEditorActionListener tlEditorActionListener;

	public void setOnEditorActionListener(TextView.OnEditorActionListener l) {
		tlEditorActionListener = l;
	}

	public TransformationMethod getTransformationMethod() {
		return null;
	}

	public void setHintTextColor(ColorStateList colorStateList) {}
	public void setHintTextColor(int i) {}
	public void setLinkTextColor(ColorStateList colorStateList) {}

	public void setSingleLine() {}
	public void setSelection(int i) {}
	public void setSelection(int i, int j) {}

	public int getSelectionStart() {
		return 0;
	}

	public int getSelectionEnd() {
		return 0;
	}

	public void setEllipsize(TextUtils.TruncateAt truncateAt) {}

	public void setTextAppearance(Context context, int appearance) {}

	public void setMaxLines(int maxLines) {}

	public void setMinWidth(int minWidth) {}
	public void setMaxWidth(int maxWidth) {}

	public Typeface getTypeface() { return null; }

	public float getTextSize() { return 10; }

	public int getGravity() {
		return gravity;
	}

	public int getCompoundPaddingTop() { return 0; }
	public int getCompoundPaddingBottom() { return 0; }

	public CharSequence getText() {
		return text;
	};

	public void setCompoundDrawablePadding(int pad) {}

	protected native void native_setCompoundDrawables(long widget, long left, long top, long right, long bottom);

	// just to prevent garbage collection while native side uses it
	private Drawable drawableLeft = null;
	private Drawable drawableTop = null;
	private Drawable drawableRight = null;
	private Drawable drawableBottom = null;

	public void setCompoundDrawables(Drawable left, Drawable top, Drawable right, Drawable bottom) {
		native_setCompoundDrawables(widget,
		                            left != null ? left.paintable : 0,
		                            top != null ? top.paintable : 0,
		                            right != null ? right.paintable : 0,
		                            bottom != null ? bottom.paintable : 0);
		drawableLeft = left;
		drawableTop = top;
		drawableRight = right;
		drawableBottom = bottom;
	}

	public void setAllCaps(boolean allCaps) {
		mAllCaps = allCaps;
		String[] classesToRemove = {"ATL-text-uppercase"};
		native_removeClasses(widget, classesToRemove);

		if (allCaps) {
			native_addClass(widget, "ATL-text-uppercase");
		}
	}

	public void setSaveEnabled(boolean enabled) {}

	public final void setAutoLinkMask(int mask) {}

	public void setEditableFactory(Editable.Factory factory) {}

	public KeyListener getKeyListener() { return null; }

	public int getInputType() { return 0; }

	public final void setTransformationMethod(TransformationMethod method) {}

	public InputFilter[] getFilters() { return new InputFilter[0]; }

	public int getMaxLines() { return -1; }

	public void setCompoundDrawablesRelative(Drawable start, Drawable top, Drawable end, Drawable bottom) {
		setCompoundDrawables(start, top, end, bottom);
	}

	public int getLineCount() { return 1; }

	public URLSpan[] getUrls() { return new URLSpan[0]; }

	public void setMovementMethod(MovementMethod method) {}

	public void setTextIsSelectable(boolean selectable) {}

	public MovementMethod getMovementMethod() {
		return new BaseMovementMethod();
	}

	public CharSequence getHint() { return "HINT"; }

	public int getMinHeight() { return 0; }
	public int getMinWidth() { return 0; }
	public void setMinHeight(int minHeight) {}
	public void setMaxHeight(int maxHeight) {}

	public void setHorizontallyScrolling(boolean whether) {}
	public boolean getHorizontallyScrolling() {
		return false;
	}

	public static interface OnEditorActionListener {
		public abstract boolean onEditorAction(TextView v, int actionId, KeyEvent event);
	}

	public static enum BufferType {
		EDITABLE,
		NORMAL,
		SPANNABLE,
	}

	public Layout getLayout() {
		return new BoringLayout(getText(), getPaint(), getWidth(), Layout.Alignment.ALIGN_NORMAL, 1, 0, new BoringLayout.Metrics(), false);
	}

	public int getCurrentTextColor() {
		return Color.CYAN;
	}

	public void setSingleLine(boolean singleLine) {}

	public int getCompoundPaddingLeft() { return 0; }

	public int getCompoundPaddingRight() { return 0; }

	public void setHint(int resId) {
		setHint(getContext().getResources().getText(resId));
	}

	public float getLetterSpacing() { return 0.f; }

	public void setCompoundDrawablesRelativeWithIntrinsicBounds(int start, int top, int end, int bottom) {}

	public void setCompoundDrawablesRelativeWithIntrinsicBounds(Drawable start, Drawable top, Drawable end, Drawable bottom) {}

	public boolean getLinksClickable() { return true; }

	public boolean isTextSelectable() { return true; }

	public void setCompoundDrawablesWithIntrinsicBounds(int left, int top, int right, int bottom) {}

	public void setCompoundDrawablesWithIntrinsicBounds(Drawable left, Drawable top, Drawable right, Drawable bottom) {}

	public void setHint(CharSequence s) {}

	public Drawable[] getCompoundDrawablesRelative() { return new Drawable[4]; }

	public Drawable[] getCompoundDrawables() { return new Drawable[4]; }

	public void setTextAppearance(int dummy) {}

	public int length() {
		return getText().length();
	}

	public void setHighlightColor(int color) {
		mHighlightColor = color;
	}

	public Editable getEditableText() {
		return new SpannableStringBuilder(getText());
	}

	public int getMaxWidth() { return 1000; }

	public void nullLayouts() {}

	public void setLinkTextColor(int color) {}

	public void setCustomSelectionActionModeCallback(ActionMode.Callback actionModeCallback) {}

	public int getExtendedPaddingTop() { return 0; }

	public void setRawInputType(int type) {}

	public TextUtils.TruncateAt getEllipsize() { return null; }

	public void setLines(int lines) {}

	public int getMinLines() {
		return -1;
	}

	public void setMinLines(int lines) {}

	public void setSelectAllOnFocus(boolean selectAllOnFocus) {}

	public int getCompoundDrawablePadding() { return 0; }

	public int getPaintFlags() { return 0; }

	public void setPaintFlags(int flags) {}

	public int getLineHeight() {
		return 10; // FIXME
	}

	public int getMaxHeight() {
		return -1;
	}

	/** AOSP: whether the text is transformed to upper case (setAllCaps). */
	public boolean isAllCaps() {
		return mAllCaps;
	}

	public int getAutoSizeStepGranularity() {
		return -1;
	}

	public void setAutoSizeTextTypeUniformWithPresetSizes(int[] presetSizes, int unit) {}

	public void setCompoundDrawableTintList(ColorStateList tint) {}

	public int getHyphenationFrequency() {
		return hyphenation_frequency;
	}

	public void setHyphenationFrequency(int hyphenationFrequency) {
		hyphenation_frequency = hyphenationFrequency;
	}

	public boolean getIncludeFontPadding() { return include_padding; }

	public void setIncludeFontPadding(boolean includePadding) {
		include_padding = includePadding;
	}

	public float getLineSpacingExtra() { return 0.f; }

	public float getLineSpacingMultiplier() { return 1.f; }

	public TextDirectionHeuristic getTextDirectionHeuristic() {
		return TextDirectionHeuristics.LTR;
	}

	public Bundle getInputExtras(boolean key) {
		return new Bundle();
	}

	public void setError(CharSequence error) {
		System.out.println("ERROR: " + error);
	}

	public int getTotalPaddingLeft() { return 0; }

	public int getTotalPaddingTop() { return 0; }

	public int getTotalPaddingRight() { return 0; }

	public int getTotalPaddingBottom() { return 0; }

	public int getImeOptions() { return 0; }

	public void setShadowLayer(float radius, float dx, float dy, int color) {}

	public int getBreakStrategy() {
		return break_strategy;
	}

	public void setBreakStrategy(int strategy) {
		break_strategy = strategy;
	}

	public void clearComposingText() {}

	public void setKeyListener(KeyListener keyListener) {}

	public int getAutoLinkMask() { return 0; }

	public void setWidth(int width) {}
	public void setHeight(int height) {}

	public void setFreezesText(boolean freezesText) {}

	public void setLetterSpacing(float letterSpacing) {}

	public void setMarqueeRepeatLimit(int marqueeLimit) {}

	public void setShowSoftInputOnFocus(boolean showSoftInputOnFocus) {}

	@UnsupportedAppUsage /* androidx ACTVAutoSizeHelper seems to love this */
	/* Copyright (C) 2006 The Android Open Source Project */
	private Layout.Alignment getLayoutAlignment() {
		Layout.Alignment alignment;
		switch (getTextAlignment()) {
			case TEXT_ALIGNMENT_GRAVITY:
				switch (gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) {
					case Gravity.START:
						alignment = Layout.Alignment.ALIGN_NORMAL;
						break;
					case Gravity.END:
						alignment = Layout.Alignment.ALIGN_OPPOSITE;
						break;
					case Gravity.LEFT:
						alignment = Layout.Alignment.ALIGN_LEFT;
						break;
					case Gravity.RIGHT:
						alignment = Layout.Alignment.ALIGN_RIGHT;
						break;
					case Gravity.CENTER_HORIZONTAL:
						alignment = Layout.Alignment.ALIGN_CENTER;
						break;
					default:
						alignment = Layout.Alignment.ALIGN_NORMAL;
						break;
				}
				break;
			case TEXT_ALIGNMENT_TEXT_START:
				alignment = Layout.Alignment.ALIGN_NORMAL;
				break;
			case TEXT_ALIGNMENT_TEXT_END:
				alignment = Layout.Alignment.ALIGN_OPPOSITE;
				break;
			case TEXT_ALIGNMENT_CENTER:
				alignment = Layout.Alignment.ALIGN_CENTER;
				break;
			case TEXT_ALIGNMENT_VIEW_START:
				alignment = (getLayoutDirection() == LAYOUT_DIRECTION_RTL) ? Layout.Alignment.ALIGN_RIGHT : Layout.Alignment.ALIGN_LEFT;
				break;
			case TEXT_ALIGNMENT_VIEW_END:
				alignment = (getLayoutDirection() == LAYOUT_DIRECTION_RTL) ? Layout.Alignment.ALIGN_LEFT : Layout.Alignment.ALIGN_RIGHT;
				break;
			case TEXT_ALIGNMENT_INHERIT:
				// This should never happen as we have already resolved the text alignment
				// but better safe than sorry so we just fall through
			default:
				alignment = Layout.Alignment.ALIGN_NORMAL;
				break;
		}
		return alignment;
	}


	/* teslalinux: AOSP text attributes that have no Pango counterpart are kept and reported */
	private boolean mAllCaps;
	private int mLineBreakStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_STYLE_NONE;
	private int mLineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE;
	private boolean mFallbackLineSpacing = true;

	/** AOSP (API 33): the line-break strictness for CJK text; Pango uses the Unicode line breaking rules only. */
	public void setLineBreakStyle(int lineBreakStyle) {
		mLineBreakStyle = lineBreakStyle;
	}

	public int getLineBreakStyle() {
		return mLineBreakStyle;
	}

	public void setLineBreakWordStyle(int lineBreakWordStyle) {
		mLineBreakWordStyle = lineBreakWordStyle;
	}

	public int getLineBreakWordStyle() {
		return mLineBreakWordStyle;
	}

	/** AOSP (API 28): whether fallback fonts may raise the line height; Pango always sizes lines by the fonts used. */
	public void setFallbackLineSpacing(boolean enabled) {
		mFallbackLineSpacing = enabled;
	}

	public boolean isFallbackLineSpacing() {
		return mFallbackLineSpacing;
	}

	public void setElegantTextHeight(boolean elegant) {
		getPaint().setElegantTextHeight(elegant);
	}

	public boolean isElegantTextHeight() {
		return getPaint().isElegantTextHeight();
	}
	/* AOSP: selection highlight colour (default holo blue, 40% alpha) */
	private int mHighlightColor = 0x6633B5E5;

	public int getHighlightColor() {
		return mHighlightColor;
	}

	private int mCurHintTextColor = 0xFF808080;

	/** AOSP: the hint colour for the current state. */
	public int getCurrentHintTextColor() {
		return mCurHintTextColor;
	}
	/** AOSP: the gravity of the text within the view; a missing half defaults to START / TOP. */
	@Override
	public void setGravity(int gravity) {
		if ((gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) {
			gravity |= Gravity.START;
		}
		if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == 0) {
			gravity |= Gravity.TOP;
		}
		boolean newLayout = (gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) != (this.gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
		if (gravity != this.gravity) {
			invalidate();
		}
		this.gravity = gravity;
		if (widget != 0) {
			native_setGravity(widget, gravity);
		}
		if (newLayout) {
			requestLayout();
		}
	}

	private native void native_setGravity(long widget, int gravity);
	/* teslalinux: compound padding (a CompoundButton's button, a Switch's track) keeps the GTK label clear of the drawable */
	private int tlCompoundLeft, tlCompoundRight;

	private native void native_setCompoundPadding(long widget, int left, int right);

	protected void tlUpdateCompoundPadding() {
		int l = getCompoundPaddingLeft();
		int r = getCompoundPaddingRight();
		if (l != tlCompoundLeft || r != tlCompoundRight) {
			tlCompoundLeft = l;
			tlCompoundRight = r;
			native_setCompoundPadding(widget, l, r);
		}
	}
	/** AOSP: the locale of the text paint. */
	public java.util.Locale getTextLocale() {
		return getPaint().getTextLocale();
	}
	/** AOSP (hidden): the horizontal space a subclass draws its own drawable in (CompoundButton's button). */
	public int getHorizontalOffsetForDrawables() {
		return 0;
	}
}
