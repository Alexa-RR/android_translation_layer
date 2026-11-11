package android.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.MotionEvent;

public class EditText extends TextView {
	public EditText(Context context) {
		super(context);
	}

	public EditText(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public EditText(Context context, AttributeSet attrs, int defStyle, int defStyleRes) {
		super(context, attrs, defStyle, defStyleRes);
	}

	@Override
	protected native long native_constructor(Context context, AttributeSet attrs);
	protected native String native_getText(long widget);
	protected native void native_addTextChangedListener(long widget, TextWatcher watcher);
	protected native void native_removeTextChangedListener(long widget, TextWatcher watcher);
	protected native void native_setOnEditorActionListener(long widget, OnEditorActionListener l);
	protected native void native_setText(long widget, String text);
	protected native void native_setHint(long widget, CharSequence s);
	protected native CharSequence native_getHint(long widget); // gtk_entry_set_placeholder_text

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		return native_gtkEventPropagation();
	}

	public Editable getText() {
		return new SpannableStringBuilder(native_getText(widget));
	}

	public Editable getEditableText() {
		return new SpannableStringBuilder(native_getText(widget));
	}

	/** AOSP EditText.setText: always an editable buffer; the filters apply as in TextView (teslalinux +text) */
	@Override
	public void setText(CharSequence text) {
		setText(text, BufferType.EDITABLE);
	}

	@Override
	public void setText(CharSequence text, BufferType type) {
		if (text == null)
			text = "";
		for (android.text.InputFilter filter : getFilters()) {
			CharSequence out = filter.filter(text, 0, text.length(), new android.text.SpannedString(""), 0, 0);
			if (out != null)
				text = out;
		}
		native_setText(widget, text.toString());
	}

	@Override
	public void removeTextChangedListener(TextWatcher watcher) {
		native_removeTextChangedListener(widget, watcher);
	}

	@Override
	public void addTextChangedListener(TextWatcher watcher) {
		native_addTextChangedListener(widget, watcher);
	}

	@Override
	public void setOnEditorActionListener(OnEditorActionListener l) {
		native_setOnEditorActionListener(widget, l);
	}

	/* teslalinux (+text): compound drawables are TextView's (drawn around the GtkText) */

	@Override
	public void setHint(CharSequence s) {
		native_setHint(widget, s == null ? "" : s.toString());
		native_setHintColor(widget, getCurrentHintTextColor()); /* teslalinux (+text): the placeholder label exists now */
	}

	@Override
	public CharSequence getHint() {
		return native_getHint(widget);
	}

	/** AOSP: selects the whole text (teslalinux +text) */
	public void selectAll() {
		native_setSelection(widget, 0, native_getLength(widget));
	}

	/** Performs the editor action (IME action id); the listener gets it first, as with the IME. */
	public void onEditorAction(int actionCode) {
		if (tlEditorActionListener != null)
			tlEditorActionListener.onEditorAction(this, actionCode, null);
	}
	/* ---- teslalinux: the input method side of an EditText (AOSP TextView + EditableInputConnection) ---- */
	protected native void native_insertText(long widget, String text);
	protected native void native_deleteRange(long widget, int start, int end);
	protected native int native_getCursor(long widget);
	protected native void native_getSelection(long widget, int[] out);
	protected native void native_setSelection(long widget, int start, int end);
	protected native void native_activate(long widget);
	protected native int native_getLength(long widget);

	@Override
	public int getSelectionStart() {
		int[] sel = new int[2];
		native_getSelection(widget, sel);
		return sel[0];
	}

	@Override
	public int getSelectionEnd() {
		int[] sel = new int[2];
		native_getSelection(widget, sel);
		return sel[1];
	}

	@Override
	public void setSelection(int index) {
		native_setSelection(widget, index, index);
	}

	@Override
	public void setSelection(int start, int stop) {
		native_setSelection(widget, start, stop);
	}

	/** AOSP TextView.onCreateInputConnection: the editor's attributes and a connection the input method edits through. */
	@Override
	public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo outAttrs) {
		outAttrs.inputType = tlInputType;
		outAttrs.imeOptions = tlImeOptions;
		if ((outAttrs.imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION) == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
			// AOSP: a single-line editor's default action is DONE, a multi-line one's is none
			if ((tlInputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0)
				outAttrs.imeOptions |= android.view.inputmethod.EditorInfo.IME_ACTION_NONE;
			else
				outAttrs.imeOptions |= android.view.inputmethod.EditorInfo.IME_ACTION_DONE;
		}
		outAttrs.hintText = getHint();
		outAttrs.initialSelStart = getSelectionStart();
		outAttrs.initialSelEnd = getSelectionEnd();
		return new TlEditTextInputConnection(this);
	}

	/** GTK focus of the text widget: AOSP onFocusChanged; a focus gained by touch starts input and asks for the soft input. */
	private void tlGtkFocusChanged(boolean focused) {
		onFocusChanged(focused, FOCUS_DOWN, null);
		if (focused) {
			android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null) {
				imm.restartInput(this);
				imm.showSoftInput(this, 0);
			}
		}
	}

	/** AOSP EditableInputConnection over the GtkText buffer (positions are character offsets). */
	private static final class TlEditTextInputConnection extends android.view.inputmethod.BaseInputConnection {
		private final EditText mView;
		private int mComposingStart = -1, mComposingEnd = -1;

		TlEditTextInputConnection(EditText view) {
			super(view, false);
			mView = view;
		}

		private String text() {
			return mView.native_getText(mView.widget);
		}

		@Override
		public boolean commitText(CharSequence text, int newCursorPosition) {
			if (mComposingStart >= 0) {
				mView.native_deleteRange(mView.widget, mComposingStart, mComposingEnd);
				mComposingStart = mComposingEnd = -1;
			}
			mView.native_insertText(mView.widget, text.toString());
			return true;
		}

		@Override
		public boolean setComposingText(CharSequence text, int newCursorPosition) {
			if (mComposingStart >= 0)
				mView.native_deleteRange(mView.widget, mComposingStart, mComposingEnd);
			int start = mView.native_getCursor(mView.widget);
			mView.native_insertText(mView.widget, text.toString());
			mComposingStart = start;
			mComposingEnd = start + text.length();
			return true;
		}

		@Override
		public boolean finishComposingText() {
			mComposingStart = mComposingEnd = -1;
			return true;
		}

		@Override
		public boolean deleteSurroundingText(int beforeLength, int afterLength) {
			int[] sel = new int[2];
			mView.native_getSelection(mView.widget, sel);
			int len = mView.native_getLength(mView.widget);
			int start = Math.max(0, sel[0] - beforeLength);
			int end = Math.min(len, sel[1] + afterLength);
			if (end > sel[1])
				mView.native_deleteRange(mView.widget, sel[1], end);
			if (sel[0] > start)
				mView.native_deleteRange(mView.widget, start, sel[0]);
			return true;
		}

		@Override
		public CharSequence getTextBeforeCursor(int n, int flags) {
			String t = text();
			int cursor = Math.min(mView.getSelectionStart(), t.length());
			return t.substring(Math.max(0, cursor - n), cursor);
		}

		@Override
		public CharSequence getTextAfterCursor(int n, int flags) {
			String t = text();
			int cursor = Math.min(mView.getSelectionEnd(), t.length());
			return t.substring(cursor, Math.min(t.length(), cursor + n));
		}

		@Override
		public CharSequence getSelectedText(int flags) {
			String t = text();
			int a = Math.min(mView.getSelectionStart(), t.length()), b = Math.min(mView.getSelectionEnd(), t.length());
			return a == b ? null : t.substring(a, b);
		}

		@Override
		public boolean setSelection(int start, int end) {
			mView.native_setSelection(mView.widget, start, end);
			return true;
		}

		@Override
		public boolean performEditorAction(int editorAction) {
			mView.native_activate(mView.widget);
			return true;
		}

		@Override
		public boolean sendKeyEvent(android.view.KeyEvent event) {
			if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) {
				if (event.getAction() == android.view.KeyEvent.ACTION_UP)
					mView.native_activate(mView.widget);
				return true;
			}
			if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DEL) {
				if (event.getAction() == android.view.KeyEvent.ACTION_DOWN)
					deleteSurroundingText(1, 0);
				return true;
			}
			return mView.dispatchKeyEvent(event);
		}
	}

	/* teslalinux (+text): AOSP EditText behaviour around the GtkText */
	@Override
	public void append(CharSequence text, int start, int end) {
		final int len = native_getLength(widget);
		native_setSelection(widget, len, len);
		native_insertText(widget, text.subSequence(start, end).toString());
	}

	/** AOSP: the hint colour reaches GtkText's placeholder */
	@Override
	public void setHintTextColor(android.content.res.ColorStateList colors) {
		super.setHintTextColor(colors);
		native_setHintColor(widget, getCurrentHintTextColor());
	}

	/** AOSP: an EditText keeps its text across configuration changes */
	@Override
	public boolean getFreezesText() {
		return true;
	}

	/** AOSP: the GtkText cursor or selection moved (native-text-textview.sh, tl_edittext_selection) */
	private void tlGtkSelectionChanged() {
		onSelectionChanged(getSelectionStart(), getSelectionEnd());
	}

	/** AOSP: the platform text context menu items (select all, cut, copy, paste) on the GtkText */
	public boolean onTextContextMenuItem(int id) {
		final String text = native_getText(widget);
		final int min = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd()));
		final int max = Math.min(text.length(), Math.max(getSelectionStart(), getSelectionEnd()));
		final android.content.ClipboardManager cm = (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
		if (id == android.R.id.selectAll) {
			selectAll();
			return true;
		} else if (id == android.R.id.copy || id == android.R.id.cut) {
			if (min == max || cm == null)
				return false;
			cm.setPrimaryClip(android.content.ClipData.newPlainText(null, text.substring(min, max)));
			if (id == android.R.id.cut)
				native_deleteRange(widget, min, max);
			return true;
		} else if (id == android.R.id.paste) {
			if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0)
				return false;
			CharSequence paste = cm.getPrimaryClip().getItemAt(0).coerceToText(getContext());
			if (paste == null)
				return false;
			if (min != max)
				native_deleteRange(widget, min, max);
			native_setSelection(widget, min, min);
			native_insertText(widget, paste.toString());
			return true;
		}
		return false;
	}
}
