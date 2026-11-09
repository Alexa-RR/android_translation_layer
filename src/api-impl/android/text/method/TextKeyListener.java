package android.text.method;

import android.text.Editable;

public class TextKeyListener {

	public static void clear(Editable content) {}

	/** AOSP TextKeyListener.Capitalize: the capitalization modes of a key listener (teslalinux +text) */
	public enum Capitalize {
		NONE, SENTENCES, WORDS, CHARACTERS,
	}
}
