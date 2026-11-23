/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.view;

import android.atl.ATLPaths;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * AOSP's libinput KeyCharacterMap (frameworks/native/libs/input/KeyCharacterMap.cpp, android14-release)
 * ported to Java: a key character map file (.kcm) parsed with the same grammar and checks, and the
 * same lookups (characters by meta state, fallback actions, numbers, matches, display labels,
 * the key events that type a string) and parcel layout. KeyCharacterMap is its Java side, as on
 * AOSP over the native object.
 */
final class TlKeyCharacterMapFile {
	private static final String TAG = "KeyCharacterMap";

	// KeyboardType (KeyCharacterMap.h)
	static final int TYPE_UNKNOWN = 0;
	static final int TYPE_NUMERIC = 1;
	static final int TYPE_PREDICTIVE = 2;
	static final int TYPE_ALPHA = 3;
	static final int TYPE_FULL = 4;
	static final int TYPE_SPECIAL_FUNCTION = 5;
	static final int TYPE_OVERLAY = 6;

	/* the AMETA_* bits are KeyEvent's META_* */
	private static final String[] MODIFIER_LABELS = {
		"shift", "lshift", "rshift", "alt", "lalt", "ralt", "ctrl", "lctrl", "rctrl",
		"meta", "lmeta", "rmeta", "sym", "fn", "capslock", "numlock", "scrolllock",
	};
	private static final int[] MODIFIER_META = {
		KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.META_SHIFT_RIGHT_ON,
		KeyEvent.META_ALT_ON, KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_RIGHT_ON,
		KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_RIGHT_ON,
		KeyEvent.META_META_ON, KeyEvent.META_META_LEFT_ON, KeyEvent.META_META_RIGHT_ON,
		KeyEvent.META_SYM_ON, KeyEvent.META_FUNCTION_ON, KeyEvent.META_CAPS_LOCK_ON,
		KeyEvent.META_NUM_LOCK_ON, KeyEvent.META_SCROLL_LOCK_ON,
	};

	static final class Behavior {
		int metaState;
		char character;
		int fallbackKeyCode;
		int replacementKeyCode;

		Behavior copy() {
			Behavior b = new Behavior();
			b.metaState = metaState;
			b.character = character;
			b.fallbackKeyCode = fallbackKeyCode;
			b.replacementKeyCode = replacementKeyCode;
			return b;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Behavior))
				return false;
			Behavior b = (Behavior)o;
			return metaState == b.metaState && character == b.character && fallbackKeyCode == b.fallbackKeyCode && replacementKeyCode == b.replacementKeyCode;
		}

		@Override
		public int hashCode() {
			return Objects.hash(metaState, (int)character, fallbackKeyCode, replacementKeyCode);
		}
	}

	static final class Key {
		char label;
		char number;
		/* AOSP keeps a list with each new behavior in front: the last declared is tried first */
		final ArrayList<Behavior> behaviors = new ArrayList<>();

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Key))
				return false;
			Key k = (Key)o;
			return label == k.label && number == k.number && behaviors.equals(k.behaviors);
		}

		@Override
		public int hashCode() {
			return Objects.hash((int)label, (int)number, behaviors);
		}
	}

	private final String mLoadFileName;
	private int mType = TYPE_UNKNOWN;
	private final SparseArray<Key> mKeys = new SparseArray<>();
	private final SparseArray<Integer> mKeysByScanCode = new SparseArray<>();
	private final SparseArray<Integer> mKeysByUsageCode = new SparseArray<>();
	private boolean mLayoutOverlayApplied;

	private TlKeyCharacterMapFile(String loadFileName) {
		mLoadFileName = loadFileName;
	}

	private static final HashMap<String, TlKeyCharacterMapFile> sSystemMaps = new HashMap<>();

	/**
	 * The map of a key character map file in the runtime's system directory (AOSP:
	 * /system/usr/keychars/NAME), parsed once; null (and the reason logged) when the file is
	 * missing or malformed, as EventHub then has no map for the device.
	 */
	static synchronized TlKeyCharacterMapFile loadSystem(String name) {
		if (sSystemMaps.containsKey(name))
			return sSystemMaps.get(name);
		File file = new File(new File(ATLPaths.system_dir, "usr/keychars"), name);
		TlKeyCharacterMapFile map = null;
		try {
			map = load(file.getPath(), new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
		} catch (IOException e) {
			Log.e(TAG, "Error opening key character map file " + file + ": " + e);
		}
		sSystemMaps.put(name, map);
		return map;
	}

	/** AOSP KeyCharacterMap::load(filename, Format::BASE) over the file's text */
	static TlKeyCharacterMapFile load(String fileName, String text) {
		TlKeyCharacterMapFile map = new TlKeyCharacterMapFile(fileName);
		try {
			new Parser(map, fileName, text).parse();
		} catch (IllegalArgumentException e) {
			Log.e(TAG, e.getMessage());
			return null;
		}
		return map;
	}

	int getKeyboardType() {
		return mType;
	}

	char getDisplayLabel(int keyCode) {
		Key key = mKeys.get(keyCode);
		return key != null ? key.label : 0;
	}

	char getNumber(int keyCode) {
		Key key = mKeys.get(keyCode);
		return key != null ? key.number : 0;
	}

	char getCharacter(int keyCode, int metaState) {
		Behavior behavior = getKeyBehavior(keyCode, metaState);
		return behavior != null ? behavior.character : 0;
	}

	boolean getFallbackAction(int keyCode, int metaState, KeyCharacterMap.FallbackAction out) {
		out.keyCode = 0;
		out.metaState = 0;
		Behavior behavior = getKeyBehavior(keyCode, metaState);
		if (behavior != null && behavior.fallbackKeyCode != 0) {
			out.keyCode = behavior.fallbackKeyCode;
			out.metaState = metaState & ~behavior.metaState;
			return true;
		}
		return false;
	}

	char getMatch(int keyCode, char[] chars, int metaState) {
		char result = 0;
		Key key = mKeys.get(keyCode);
		if (key != null) {
			// Try to find the most general behavior that maps to this character.
			// For example, the base key behavior will usually be last in the list.
			// However, if we find a perfect meta state match for one behavior then use that one.
			for (Behavior behavior : key.behaviors) {
				if (behavior.character != 0) {
					for (char c : chars) {
						if (behavior.character == c) {
							result = behavior.character;
							if ((behavior.metaState & metaState) == behavior.metaState)
								return result; // Found exact match!
							break;
						}
					}
				}
			}
		}
		return result;
	}

	/** AOSP getEvents: null when a character has no key in this map */
	KeyEvent[] getEvents(int deviceId, char[] chars) {
		long now = SystemClock.uptimeMillis();
		ArrayList<KeyEvent> out = new ArrayList<>();
		for (char ch : chars) {
			int[] found = findKey(ch);
			if (found == null)
				return null;
			int keyCode = found[0], metaState = found[1];
			int[] current = {0};
			addMetaKeys(out, deviceId, metaState, true, now, current);
			addKey(out, deviceId, keyCode, current[0], true, now);
			addKey(out, deviceId, keyCode, current[0], false, now);
			addMetaKeys(out, deviceId, metaState, false, now, current);
		}
		return out.toArray(new KeyEvent[0]);
	}

	/** AOSP mapKey: the key code of a scan code or HID usage this map remaps (0 when none) */
	int mapKey(int scanCode, int usageCode) {
		if (usageCode != 0) {
			Integer k = mKeysByUsageCode.get(usageCode);
			if (k != null)
				return k;
		}
		if (scanCode != 0) {
			Integer k = mKeysByScanCode.get(scanCode);
			if (k != null)
				return k;
		}
		return KeyEvent.KEYCODE_UNKNOWN;
	}

	/** AOSP applyKeyBehavior: a key's replacement key code and meta state */
	int[] applyKeyBehavior(int fromKeyCode, int fromMetaState) {
		int toKeyCode = fromKeyCode;
		int toMetaState = fromMetaState;
		Behavior behavior = getKeyBehavior(fromKeyCode, fromMetaState);
		if (behavior != null && behavior.replacementKeyCode != 0) {
			toKeyCode = behavior.replacementKeyCode;
			toMetaState = fromMetaState & ~behavior.metaState;
			// Reset dependent meta states.
			if ((behavior.metaState & KeyEvent.META_ALT_ON) != 0)
				toMetaState &= ~(KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON);
			if ((behavior.metaState & (KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON)) != 0)
				toMetaState &= ~KeyEvent.META_ALT_ON;
			if ((behavior.metaState & KeyEvent.META_CTRL_ON) != 0)
				toMetaState &= ~(KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON);
			if ((behavior.metaState & (KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON)) != 0)
				toMetaState &= ~KeyEvent.META_CTRL_ON;
			if ((behavior.metaState & KeyEvent.META_SHIFT_ON) != 0)
				toMetaState &= ~(KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_RIGHT_ON);
			if ((behavior.metaState & (KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_RIGHT_ON)) != 0)
				toMetaState &= ~KeyEvent.META_SHIFT_ON;
			// ... and put universal bits back if needed
			toMetaState = KeyEvent.normalizeMetaState(toMetaState);
		}
		return new int[] {toKeyCode, toMetaState};
	}

	private Behavior getKeyBehavior(int keyCode, int metaState) {
		Key key = mKeys.get(keyCode);
		if (key != null) {
			for (Behavior behavior : key.behaviors) {
				if (matchesMetaState(metaState, behavior.metaState))
					return behavior;
			}
		}
		return null;
	}

	private static boolean matchesMetaState(int eventMetaState, int behaviorMetaState) {
		// Behavior must have at least the set of meta states specified.
		// And if the key event has CTRL, ALT or META then the behavior must exactly
		// match those, taking into account that a behavior can specify that it handles
		// one, both or either of a left/right modifier pair.
		if ((eventMetaState & behaviorMetaState) == behaviorMetaState) {
			final int EXACT_META_STATES = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON
				| KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON
				| KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_RIGHT_ON;
			int unmatchedMetaState = eventMetaState & ~behaviorMetaState & EXACT_META_STATES;
			if ((behaviorMetaState & KeyEvent.META_CTRL_ON) != 0)
				unmatchedMetaState &= ~(KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON);
			else if ((behaviorMetaState & (KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON)) != 0)
				unmatchedMetaState &= ~KeyEvent.META_CTRL_ON;
			if ((behaviorMetaState & KeyEvent.META_ALT_ON) != 0)
				unmatchedMetaState &= ~(KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON);
			else if ((behaviorMetaState & (KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON)) != 0)
				unmatchedMetaState &= ~KeyEvent.META_ALT_ON;
			if ((behaviorMetaState & KeyEvent.META_META_ON) != 0)
				unmatchedMetaState &= ~(KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_RIGHT_ON);
			else if ((behaviorMetaState & (KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_RIGHT_ON)) != 0)
				unmatchedMetaState &= ~KeyEvent.META_META_ON;
			return unmatchedMetaState == 0;
		}
		return false;
	}

	/** AOSP findKey: {keyCode, metaState}, or null */
	private int[] findKey(char ch) {
		if (ch == 0)
			return null;
		for (int i = 0; i < mKeys.size(); i++) {
			// Try to find the most general behavior that maps to this character.
			// For example, the base key behavior will usually be last in the list.
			Behavior found = null;
			for (Behavior behavior : mKeys.valueAt(i).behaviors) {
				if (behavior.character == ch)
					found = behavior;
			}
			if (found != null)
				return new int[] {mKeys.keyAt(i), found.metaState};
		}
		return null;
	}

	private static void addKey(List<KeyEvent> out, int deviceId, int keyCode, int metaState, boolean down, long time) {
		out.add(new KeyEvent(time, time, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, 0, metaState,
				     deviceId, 0, 0, InputDevice.SOURCE_KEYBOARD));
	}

	private static void addMetaKeys(List<KeyEvent> out, int deviceId, int metaState, boolean down, long time, int[] current) {
		// Add and remove meta keys symmetrically.
		if (down) {
			addLockedMetaKey(out, deviceId, metaState, time, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.META_CAPS_LOCK_ON, current);
			addLockedMetaKey(out, deviceId, metaState, time, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.META_NUM_LOCK_ON, current);
			addLockedMetaKey(out, deviceId, metaState, time, KeyEvent.KEYCODE_SCROLL_LOCK, KeyEvent.META_SCROLL_LOCK_ON, current);
			addDoubleEphemeralMetaKey(out, deviceId, metaState, true, time, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_LEFT_ON,
						  KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.META_SHIFT_RIGHT_ON, KeyEvent.META_SHIFT_ON, current);
			addDoubleEphemeralMetaKey(out, deviceId, metaState, true, time, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_LEFT_ON,
						  KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.META_ALT_RIGHT_ON, KeyEvent.META_ALT_ON, current);
			addDoubleEphemeralMetaKey(out, deviceId, metaState, true, time, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_LEFT_ON,
						  KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.META_CTRL_RIGHT_ON, KeyEvent.META_CTRL_ON, current);
			addDoubleEphemeralMetaKey(out, deviceId, metaState, true, time, KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_LEFT_ON,
						  KeyEvent.KEYCODE_META_RIGHT, KeyEvent.META_META_RIGHT_ON, KeyEvent.META_META_ON, current);
			addSingleEphemeralMetaKey(out, deviceId, metaState, true, time, KeyEvent.KEYCODE_SYM, KeyEvent.META_SYM_ON, current);
			addSingleEphemeralMetaKey(out, deviceId, metaState, true, time, KeyEvent.KEYCODE_FUNCTION, KeyEvent.META_FUNCTION_ON, current);
		} else {
			addSingleEphemeralMetaKey(out, deviceId, metaState, false, time, KeyEvent.KEYCODE_FUNCTION, KeyEvent.META_FUNCTION_ON, current);
			addSingleEphemeralMetaKey(out, deviceId, metaState, false, time, KeyEvent.KEYCODE_SYM, KeyEvent.META_SYM_ON, current);
			addDoubleEphemeralMetaKey(out, deviceId, metaState, false, time, KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_LEFT_ON,
						  KeyEvent.KEYCODE_META_RIGHT, KeyEvent.META_META_RIGHT_ON, KeyEvent.META_META_ON, current);
			addDoubleEphemeralMetaKey(out, deviceId, metaState, false, time, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_LEFT_ON,
						  KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.META_CTRL_RIGHT_ON, KeyEvent.META_CTRL_ON, current);
			addDoubleEphemeralMetaKey(out, deviceId, metaState, false, time, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_LEFT_ON,
						  KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.META_ALT_RIGHT_ON, KeyEvent.META_ALT_ON, current);
			addDoubleEphemeralMetaKey(out, deviceId, metaState, false, time, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_LEFT_ON,
						  KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.META_SHIFT_RIGHT_ON, KeyEvent.META_SHIFT_ON, current);
			addLockedMetaKey(out, deviceId, metaState, time, KeyEvent.KEYCODE_SCROLL_LOCK, KeyEvent.META_SCROLL_LOCK_ON, current);
			addLockedMetaKey(out, deviceId, metaState, time, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.META_NUM_LOCK_ON, current);
			addLockedMetaKey(out, deviceId, metaState, time, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.META_CAPS_LOCK_ON, current);
		}
	}

	private static boolean addSingleEphemeralMetaKey(List<KeyEvent> out, int deviceId, int metaState, boolean down, long time,
							 int keyCode, int keyMetaState, int[] current) {
		if ((metaState & keyMetaState) == keyMetaState) {
			current[0] = updateMetaState(keyCode, down, current[0]);
			addKey(out, deviceId, keyCode, current[0], down, time);
			return true;
		}
		return false;
	}

	private static void addDoubleEphemeralMetaKey(List<KeyEvent> out, int deviceId, int metaState, boolean down, long time,
						      int leftKeyCode, int leftKeyMetaState, int rightKeyCode, int rightKeyMetaState,
						      int eitherKeyMetaState, int[] current) {
		boolean specific = false;
		specific |= addSingleEphemeralMetaKey(out, deviceId, metaState, down, time, leftKeyCode, leftKeyMetaState, current);
		specific |= addSingleEphemeralMetaKey(out, deviceId, metaState, down, time, rightKeyCode, rightKeyMetaState, current);
		if (!specific)
			addSingleEphemeralMetaKey(out, deviceId, metaState, down, time, leftKeyCode, eitherKeyMetaState, current);
	}

	private static void addLockedMetaKey(List<KeyEvent> out, int deviceId, int metaState, long time,
					     int keyCode, int keyMetaState, int[] current) {
		if ((metaState & keyMetaState) == keyMetaState) {
			current[0] = updateMetaState(keyCode, true, current[0]);
			addKey(out, deviceId, keyCode, current[0], true, time);
			current[0] = updateMetaState(keyCode, false, current[0]);
			addKey(out, deviceId, keyCode, current[0], false, time);
		}
	}

	/* libinput's updateMetaState (Input.cpp, input/Input.h) */
	private static int setEphemeralMetaState(int mask, boolean down, int oldMetaState) {
		int newMetaState;
		if (down) {
			newMetaState = oldMetaState | mask;
		} else {
			newMetaState = oldMetaState & ~(mask | KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON | KeyEvent.META_CTRL_ON | KeyEvent.META_META_ON);
		}
		return KeyEvent.normalizeMetaState(newMetaState);
	}

	private static int toggleLockedMetaState(int mask, boolean down, int oldMetaState) {
		if (down)
			return oldMetaState;
		return oldMetaState ^ mask;
	}

	static int updateMetaState(int keyCode, boolean down, int oldMetaState) {
		switch (keyCode) {
			case KeyEvent.KEYCODE_ALT_LEFT:
				return setEphemeralMetaState(KeyEvent.META_ALT_LEFT_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_ALT_RIGHT:
				return setEphemeralMetaState(KeyEvent.META_ALT_RIGHT_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_SHIFT_LEFT:
				return setEphemeralMetaState(KeyEvent.META_SHIFT_LEFT_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_SHIFT_RIGHT:
				return setEphemeralMetaState(KeyEvent.META_SHIFT_RIGHT_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_SYM:
				return setEphemeralMetaState(KeyEvent.META_SYM_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_FUNCTION:
				return setEphemeralMetaState(KeyEvent.META_FUNCTION_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_CTRL_LEFT:
				return setEphemeralMetaState(KeyEvent.META_CTRL_LEFT_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_CTRL_RIGHT:
				return setEphemeralMetaState(KeyEvent.META_CTRL_RIGHT_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_META_LEFT:
				return setEphemeralMetaState(KeyEvent.META_META_LEFT_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_META_RIGHT:
				return setEphemeralMetaState(KeyEvent.META_META_RIGHT_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_CAPS_LOCK:
				return toggleLockedMetaState(KeyEvent.META_CAPS_LOCK_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_NUM_LOCK:
				return toggleLockedMetaState(KeyEvent.META_NUM_LOCK_ON, down, oldMetaState);
			case KeyEvent.KEYCODE_SCROLL_LOCK:
				return toggleLockedMetaState(KeyEvent.META_SCROLL_LOCK_ON, down, oldMetaState);
			default:
				return oldMetaState;
		}
	}

	/* AOSP KeyCharacterMap::writeToParcel/readFromParcel */
	void writeToParcel(Parcel parcel) {
		parcel.writeString(mLoadFileName);
		parcel.writeInt(mType);
		parcel.writeInt(mLayoutOverlayApplied ? 1 : 0);
		parcel.writeInt(mKeys.size());
		for (int i = 0; i < mKeys.size(); i++) {
			Key key = mKeys.valueAt(i);
			parcel.writeInt(mKeys.keyAt(i));
			parcel.writeInt(key.label);
			parcel.writeInt(key.number);
			for (Behavior b : key.behaviors) {
				parcel.writeInt(1);
				parcel.writeInt(b.metaState);
				parcel.writeInt(b.character);
				parcel.writeInt(b.fallbackKeyCode);
				parcel.writeInt(b.replacementKeyCode);
			}
			parcel.writeInt(0);
		}
		writeMap(parcel, mKeysByScanCode);
		writeMap(parcel, mKeysByUsageCode);
	}

	private static void writeMap(Parcel parcel, SparseArray<Integer> map) {
		parcel.writeInt(map.size());
		for (int i = 0; i < map.size(); i++) {
			parcel.writeInt(map.keyAt(i));
			parcel.writeInt(map.valueAt(i));
		}
	}

	private static final int MAX_KEYS = 8192;

	static TlKeyCharacterMapFile readFromParcel(Parcel parcel) {
		TlKeyCharacterMapFile map = new TlKeyCharacterMapFile(parcel.readString());
		map.mType = parcel.readInt();
		map.mLayoutOverlayApplied = parcel.readInt() != 0;
		int numKeys = parcel.readInt();
		if (numKeys < 0 || numKeys > MAX_KEYS) {
			Log.e(TAG, "Too many keys in KeyCharacterMap (" + numKeys + " > " + MAX_KEYS + ")");
			return null;
		}
		for (int i = 0; i < numKeys; i++) {
			int keyCode = parcel.readInt();
			Key key = new Key();
			key.label = (char)parcel.readInt();
			key.number = (char)parcel.readInt();
			while (parcel.readInt() != 0) {
				Behavior b = new Behavior();
				b.metaState = parcel.readInt();
				b.character = (char)parcel.readInt();
				b.fallbackKeyCode = parcel.readInt();
				b.replacementKeyCode = parcel.readInt();
				key.behaviors.add(b);
			}
			map.mKeys.put(keyCode, key);
		}
		if (!readMap(parcel, map.mKeysByScanCode) || !readMap(parcel, map.mKeysByUsageCode))
			return null;
		return map;
	}

	private static boolean readMap(Parcel parcel, SparseArray<Integer> map) {
		int n = parcel.readInt();
		if (n < 0 || n > MAX_KEYS)
			return false;
		for (int i = 0; i < n; i++) {
			int from = parcel.readInt();
			map.put(from, parcel.readInt());
		}
		return true;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof TlKeyCharacterMapFile))
			return false;
		TlKeyCharacterMapFile m = (TlKeyCharacterMapFile)o;
		if (mType != m.mType || mLayoutOverlayApplied != m.mLayoutOverlayApplied || mKeys.size() != m.mKeys.size()
		    || mKeysByScanCode.size() != m.mKeysByScanCode.size() || mKeysByUsageCode.size() != m.mKeysByUsageCode.size())
			return false;
		for (int i = 0; i < mKeys.size(); i++) {
			if (mKeys.keyAt(i) != m.mKeys.keyAt(i) || !mKeys.valueAt(i).equals(m.mKeys.valueAt(i)))
				return false;
		}
		for (int i = 0; i < mKeysByScanCode.size(); i++) {
			if (mKeysByScanCode.keyAt(i) != m.mKeysByScanCode.keyAt(i) || !mKeysByScanCode.valueAt(i).equals(m.mKeysByScanCode.valueAt(i)))
				return false;
		}
		for (int i = 0; i < mKeysByUsageCode.size(); i++) {
			if (mKeysByUsageCode.keyAt(i) != m.mKeysByUsageCode.keyAt(i) || !mKeysByUsageCode.valueAt(i).equals(m.mKeysByUsageCode.valueAt(i)))
				return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int h = mType;
		for (int i = 0; i < mKeys.size(); i++)
			h = 31 * h + mKeys.keyAt(i) * 17 + mKeys.valueAt(i).hashCode();
		return h;
	}

	/** AOSP KeyCharacterMap::Parser over utils' Tokenizer (line by line, delimiter sets) */
	private static final class Parser {
		private static final String WHITESPACE = " \t\r";
		private static final String WHITESPACE_OR_PROPERTY_DELIMITER = " \t\r,:";
		private static final int STATE_TOP = 0;
		private static final int STATE_KEY = 1;
		private static final int PROPERTY_LABEL = 1;
		private static final int PROPERTY_NUMBER = 2;
		private static final int PROPERTY_META = 3;

		private final TlKeyCharacterMapFile mMap;
		private final String mFileName;
		private final String[] mLines;
		private int mLineIndex;
		private String mLine;
		private int mPos;
		private int mState = STATE_TOP;
		private int mKeyCode;

		Parser(TlKeyCharacterMapFile map, String fileName, String text) {
			mMap = map;
			mFileName = fileName;
			mLines = text.split("\n", -1);
		}

		private IllegalArgumentException error(String msg) {
			return new IllegalArgumentException(mFileName + ":" + (mLineIndex + 1) + ": " + msg);
		}

		private boolean isEol() {
			return mPos >= mLine.length();
		}

		private char peekChar() {
			return isEol() ? '\0' : mLine.charAt(mPos);
		}

		private char nextChar() {
			return isEol() ? '\0' : mLine.charAt(mPos++);
		}

		private void skipDelimiters(String delimiters) {
			while (!isEol() && delimiters.indexOf(mLine.charAt(mPos)) >= 0)
				mPos++;
		}

		private String nextToken(String delimiters) {
			int start = mPos;
			while (!isEol() && delimiters.indexOf(mLine.charAt(mPos)) < 0)
				mPos++;
			return mLine.substring(start, mPos);
		}

		private String remainder() {
			return mLine.substring(Math.min(mPos, mLine.length()));
		}

		void parse() {
			for (mLineIndex = 0; mLineIndex < mLines.length; mLineIndex++) {
				mLine = mLines[mLineIndex];
				mPos = 0;
				skipDelimiters(WHITESPACE);
				if (!isEol() && peekChar() != '#') {
					switch (mState) {
						case STATE_TOP: {
							String keywordToken = nextToken(WHITESPACE);
							if (keywordToken.equals("type")) {
								skipDelimiters(WHITESPACE);
								parseType();
							} else if (keywordToken.equals("map")) {
								skipDelimiters(WHITESPACE);
								parseMap();
							} else if (keywordToken.equals("key")) {
								skipDelimiters(WHITESPACE);
								parseKey();
							} else {
								throw error("Expected keyword, got '" + keywordToken + "'.");
							}
							break;
						}
						case STATE_KEY:
							parseKeyProperty();
							break;
					}
					skipDelimiters(WHITESPACE);
					if (!isEol() && peekChar() != '#')
						throw error("Expected end of line or trailing comment, got '" + remainder() + "'.");
				}
			}
			if (mState != STATE_TOP)
				throw error("Unterminated key description at end of file.");
			if (mMap.mType == TYPE_UNKNOWN)
				throw error("Keyboard layout missing required keyboard 'type' declaration.");
			// Format::BASE
			if (mMap.mType == TYPE_OVERLAY)
				throw error("Base keyboard layout must specify a keyboard 'type' other than 'OVERLAY'.");
		}

		private void parseType() {
			if (mMap.mType != TYPE_UNKNOWN)
				throw error("Duplicate keyboard 'type' declaration.");
			String typeToken = nextToken(WHITESPACE);
			int type;
			switch (typeToken) {
				case "NUMERIC": type = TYPE_NUMERIC; break;
				case "PREDICTIVE": type = TYPE_PREDICTIVE; break;
				case "ALPHA": type = TYPE_ALPHA; break;
				case "FULL": type = TYPE_FULL; break;
				case "SPECIAL_FUNCTION":
					Log.w(TAG, "The SPECIAL_FUNCTION type is now declared in the device's IDC file, please set "
						   + "the property 'keyboard.specialFunction' to '1' there instead.");
					type = TYPE_SPECIAL_FUNCTION;
					break;
				case "OVERLAY": type = TYPE_OVERLAY; break;
				default:
					throw error("Expected keyboard type label, got '" + typeToken + "'.");
			}
			mMap.mType = type;
		}

		private void parseMap() {
			String keywordToken = nextToken(WHITESPACE);
			if (keywordToken.equals("key")) {
				skipDelimiters(WHITESPACE);
				parseMapKey();
				return;
			}
			throw error("Expected keyword after 'map', got '" + keywordToken + "'.");
		}

		private static int parseCode(String token) {
			// strtol(token, &end, 0): decimal, 0x hex or 0 octal, the whole token
			if (token.startsWith("0x") || token.startsWith("0X"))
				return Integer.parseInt(token.substring(2), 16);
			if (token.length() > 1 && token.startsWith("0"))
				return Integer.parseInt(token.substring(1), 8);
			return Integer.parseInt(token);
		}

		private void parseMapKey() {
			String codeToken = nextToken(WHITESPACE);
			boolean mapUsage = false;
			if (codeToken.equals("usage")) {
				mapUsage = true;
				skipDelimiters(WHITESPACE);
				codeToken = nextToken(WHITESPACE);
			}
			int code;
			try {
				code = parseCode(codeToken);
			} catch (NumberFormatException e) {
				throw error("Expected key " + (mapUsage ? "usage" : "scan code") + " number, got '" + codeToken + "'.");
			}
			SparseArray<Integer> map = mapUsage ? mMap.mKeysByUsageCode : mMap.mKeysByScanCode;
			if (map.indexOfKey(code) >= 0)
				throw error("Duplicate entry for key " + (mapUsage ? "usage" : "scan code") + " '" + codeToken + "'.");
			skipDelimiters(WHITESPACE);
			String keyCodeToken = nextToken(WHITESPACE);
			int keyCode = keyCodeByLabel(keyCodeToken);
			if (keyCode == 0)
				throw error("Expected key code label, got '" + keyCodeToken + "'.");
			map.put(code, keyCode);
		}

		private void parseKey() {
			String keyCodeToken = nextToken(WHITESPACE);
			int keyCode = keyCodeByLabel(keyCodeToken);
			if (keyCode == 0)
				throw error("Expected key code label, got '" + keyCodeToken + "'.");
			if (mMap.mKeys.indexOfKey(keyCode) >= 0)
				throw error("Duplicate entry for key code '" + keyCodeToken + "'.");
			skipDelimiters(WHITESPACE);
			String openBraceToken = nextToken(WHITESPACE);
			if (!openBraceToken.equals("{"))
				throw error("Expected '{' after key code label, got '" + openBraceToken + "'.");
			mKeyCode = keyCode;
			mMap.mKeys.put(keyCode, new Key());
			mState = STATE_KEY;
		}

		private void parseKeyProperty() {
			Key key = mMap.mKeys.get(mKeyCode);
			String token = nextToken(WHITESPACE_OR_PROPERTY_DELIMITER);
			if (token.equals("}")) {
				mState = STATE_TOP;
				finishKey(key);
				return;
			}
			ArrayList<int[]> properties = new ArrayList<>(); // {property, metaState}
			// Parse all comma-delimited property names up to the first colon.
			for (;;) {
				if (token.equals("label")) {
					properties.add(new int[] {PROPERTY_LABEL, 0});
				} else if (token.equals("number")) {
					properties.add(new int[] {PROPERTY_NUMBER, 0});
				} else {
					Integer metaState = parseModifier(token);
					if (metaState == null)
						throw error("Expected a property name or modifier, got '" + token + "'.");
					properties.add(new int[] {PROPERTY_META, metaState});
				}
				skipDelimiters(WHITESPACE);
				if (!isEol()) {
					char ch = nextChar();
					if (ch == ':') {
						break;
					} else if (ch == ',') {
						skipDelimiters(WHITESPACE);
						token = nextToken(WHITESPACE_OR_PROPERTY_DELIMITER);
						continue;
					}
				}
				throw error("Expected ',' or ':' after property name.");
			}
			// Parse behavior after the colon.
			skipDelimiters(WHITESPACE);
			Behavior behavior = new Behavior();
			boolean haveCharacter = false;
			boolean haveFallback = false;
			boolean haveReplacement = false;
			do {
				char ch = peekChar();
				if (ch == '\'') {
					char character = parseCharacterLiteral();
					if (character == 0)
						throw error("Invalid character literal for key.");
					if (haveCharacter)
						throw error("Cannot combine multiple character literals or 'none'.");
					if (haveReplacement)
						throw error("Cannot combine character literal with replace action.");
					behavior.character = character;
					haveCharacter = true;
				} else {
					token = nextToken(WHITESPACE);
					if (token.equals("none")) {
						if (haveCharacter)
							throw error("Cannot combine multiple character literals or 'none'.");
						if (haveReplacement)
							throw error("Cannot combine 'none' with replace action.");
						haveCharacter = true;
					} else if (token.equals("fallback")) {
						skipDelimiters(WHITESPACE);
						token = nextToken(WHITESPACE);
						int keyCode = keyCodeByLabel(token);
						if (keyCode == 0)
							throw error("Invalid key code label for fallback behavior, got '" + token + "'.");
						if (haveFallback || haveReplacement)
							throw error("Cannot combine multiple fallback/replacement key codes.");
						behavior.fallbackKeyCode = keyCode;
						haveFallback = true;
					} else if (token.equals("replace")) {
						skipDelimiters(WHITESPACE);
						token = nextToken(WHITESPACE);
						int keyCode = keyCodeByLabel(token);
						if (keyCode == 0)
							throw error("Invalid key code label for replace, got '" + token + "'.");
						if (haveCharacter)
							throw error("Cannot combine character literal with replace action.");
						if (haveFallback || haveReplacement)
							throw error("Cannot combine multiple fallback/replacement key codes.");
						behavior.replacementKeyCode = keyCode;
						haveReplacement = true;
					} else {
						throw error("Expected a key behavior after ':'.");
					}
				}
				skipDelimiters(WHITESPACE);
			} while (!isEol() && peekChar() != '#');
			// Add the behavior.
			for (int[] property : properties) {
				switch (property[0]) {
					case PROPERTY_LABEL:
						if (key.label != 0)
							throw error("Duplicate label for key.");
						key.label = behavior.character;
						break;
					case PROPERTY_NUMBER:
						if (key.number != 0)
							throw error("Duplicate number for key.");
						key.number = behavior.character;
						break;
					case PROPERTY_META: {
						for (Behavior b : key.behaviors) {
							if (b.metaState == property[1])
								throw error("Duplicate key behavior for modifier.");
						}
						Behavior newBehavior = behavior.copy();
						newBehavior.metaState = property[1];
						key.behaviors.add(0, newBehavior);
						break;
					}
				}
			}
		}

		private static void finishKey(Key key) {
			// Fill in default number property.
			if (key.number == 0) {
				char digit = 0;
				char symbol = 0;
				for (Behavior b : key.behaviors) {
					char ch = b.character;
					if (ch != 0) {
						if (ch >= '0' && ch <= '9') {
							digit = ch;
						} else if (ch == '(' || ch == ')' || ch == '#' || ch == '*' || ch == '-' || ch == '+' || ch == ','
							   || ch == '.' || ch == '\'' || ch == ':' || ch == ';' || ch == '/') {
							symbol = ch;
						}
					}
				}
				key.number = digit != 0 ? digit : symbol;
			}
		}

		private Integer parseModifier(String token) {
			if (token.equals("base"))
				return 0;
			int combinedMeta = 0;
			for (String part : token.split("\\+", -1)) {
				int metaState = 0;
				for (int i = 0; i < MODIFIER_LABELS.length; i++) {
					if (MODIFIER_LABELS[i].equals(part)) {
						metaState = MODIFIER_META[i];
						break;
					}
				}
				if (metaState == 0)
					return null;
				if ((combinedMeta & metaState) != 0)
					throw error("Duplicate modifier combination '" + token + "'.");
				combinedMeta |= metaState;
			}
			return combinedMeta;
		}

		/** the literal's character; 0 when it is malformed */
		private char parseCharacterLiteral() {
			char ch = nextChar();
			if (ch != '\'')
				return 0;
			char result;
			ch = nextChar();
			if (ch == '\\') {
				// Escape sequence.
				ch = nextChar();
				if (ch == 'n') {
					result = '\n';
				} else if (ch == 't') {
					result = '\t';
				} else if (ch == '\\') {
					result = '\\';
				} else if (ch == '\'') {
					result = '\'';
				} else if (ch == '"') {
					result = '"';
				} else if (ch == 'u') {
					result = 0;
					for (int i = 0; i < 4; i++) {
						int digit = Character.digit(nextChar(), 16);
						if (digit < 0)
							return 0;
						result = (char)((result << 4) | digit);
					}
				} else {
					return 0;
				}
			} else if (ch >= 32 && ch <= 126 && ch != '\'') {
				// ASCII literal character.
				result = ch;
			} else {
				return 0;
			}
			if (nextChar() != '\'')
				return 0;
			// Ensure that we consumed the entire token.
			if (!nextToken(WHITESPACE).isEmpty())
				return 0;
			return result;
		}

		/** InputEventLookup::getKeyCodeByLabel: a key code label without its KEYCODE_ prefix; 0 when unknown */
		private static int keyCodeByLabel(String label) {
			if (label.isEmpty())
				return 0;
			// a label only ("0" is KEYCODE_0), never a number standing for a key code
			int keyCode = KeyEvent.keyCodeFromString("KEYCODE_" + label);
			return keyCode > 0 && KeyEvent.keyCodeToString(keyCode).equals("KEYCODE_" + label) ? keyCode : 0;
		}
	}
}
