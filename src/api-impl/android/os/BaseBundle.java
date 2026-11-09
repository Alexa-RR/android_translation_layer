package android.os;

import android.util.ArrayMap;
import android.util.Log;
import java.util.Set;

public class BaseBundle {
	protected static final String TAG = "Bundle";
	static final boolean DEBUG = false;

	// Invariant - exactly one of mMap / mParcelledData will be null
	// (except inside a call to unparcel)

	/* package */ ArrayMap<String, Object> mMap;

	public BaseBundle() {
		mMap = new ArrayMap<String, Object>();
	}

	// Log a message if the value was non-null but not of the expected type
	void typeWarning(String key, Object value, String className,
	                 Object defaultValue, ClassCastException e) {
		StringBuilder sb = new StringBuilder();
		sb.append("Key ");
		sb.append(key);
		sb.append(" expected ");
		sb.append(className);
		sb.append(" but value was a ");
		sb.append(value.getClass().getName());
		sb.append(".  The default value ");
		sb.append(defaultValue);
		sb.append(" was returned.");
		Log.w(TAG, sb.toString());
		Log.w(TAG, "Attempt to cast generated internal exception:", e);
	}

	void typeWarning(String key, Object value, String className,
	                 ClassCastException e) {
		typeWarning(key, value, className, "<null>", e);
	}

	/**
	 * Returns true if the mapping of this Bundle is empty, false otherwise.
	 */
	public boolean isEmpty() {
		return mMap.isEmpty();
	}

	/**
	 * Returns a Set containing the Strings used as keys in this Bundle.
	 *
	 * @return a Set of String keys
	 */
	public Set<String> keySet() {
		return mMap.keySet();
	}

	/**
	 * Returns true if the given key is contained in the mapping
	 * of this Bundle.
	 *
	 * @param key a String key
	 * @return true if the key is part of the mapping, false otherwise
	 */
	public boolean containsKey(String key) {
		return mMap.containsKey(key);
	}

	/**
	 * Removes any entry with the given key from the mapping of this Bundle.
	 *
	 * @param key a String key
	 */
	public void remove(String key) {
		mMap.remove(key);
	}

	/**
	 * Returns the entry with the given key as an object.
	 *
	 * @param key a String key
	 * @return an Object, or null
	 */
	public Object get(String key) {
		return mMap.get(key);
	}

	/**
	 * Returns the value associated with the given key, or null if
	 * no mapping of the desired type exists for the given key or a null
	 * value is explicitly associated with the key.
	 *
	 * @param key a String, or null
	 * @return a String value, or null
	 */
	public String getString(String key) {
		final Object o = mMap.get(key);
		try {
			return (String)o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "String", e);
			return null;
		}
	}

	/**
	 * Returns the value associated with the given key, or defaultValue if
	 * no mapping of the desired type exists for the given key.
	 *
	 * @param key a String, or null
	 * @param defaultValue Value to return if key does not exist
	 * @return the String value associated with the given key, or defaultValue
	 *     if no valid String object is currently mapped to that key.
	 */
	public String getString(String key, String defaultValue) {
		final String s = getString(key);
		return (s == null) ? defaultValue : s;
	}

	/**
	 * Returns the value associated with the given key, or 0L if
	 * no mapping of the desired type exists for the given key.
	 *
	 * @param key a String
	 * @return a long value
	 */
	public long getLong(String key) {
		return getLong(key, 0L);
	}

	/**
	 * Returns the value associated with the given key, or defaultValue if
	 * no mapping of the desired type exists for the given key.
	 *
	 * @param key a String
	 * @param defaultValue Value to return if key does not exist
	 * @return a long value
	 */
	public long getLong(String key, long defaultValue) {
		Object o = mMap.get(key);
		if (o == null) {
			return defaultValue;
		}
		try {
			return (Long)o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "Long", defaultValue, e);
			return defaultValue;
		}
	}

	/**
	 * Inserts a long value into the mapping of this Bundle, replacing
	 * any existing value for the given key.
	 *
	 * @param key a String, or null
	 * @param value a long
	 */
	public void putLong(String key, long value) {
		mMap.put(key, value);
	}

	/**
	 * Inserts an int value into the mapping of this Bundle, replacing
	 * any existing value for the given key.
	 *
	 * @param key a String, or null
	 * @param value an int, or null
	 */
	public void putInt(String key, int value) {
		mMap.put(key, value);
	}

	/**
	 * Inserts a String value into the mapping of this Bundle, replacing
	 * any existing value for the given key.  Either key or value may be null.
	 *
	 * @param key a String, or null
	 * @param value a String, or null
	 */
	public void putString(String key, String value) {
		mMap.put(key, value);
	}

	/**
	 * Inserts an int array value into the mapping of this Bundle, replacing
	 * any existing value for the given key.  Either key or value may be null.
	 *
	 * @param key a String, or null
	 * @param value an int array object, or null
	 */
	public void putIntArray(String key, int[] value) {
		mMap.put(key, value);
	}

	/**
	 * Inserts a long array value into the mapping of this Bundle, replacing
	 * any existing value for the given key.  Either key or value may be null.
	 *
	 * @param key a String, or null
	 * @param value a long array object, or null
	 */
	public void putLongArray(String key, long[] value) {
		mMap.put(key, value);
	}

	/**
	 * Inserts a String array value into the mapping of this Bundle, replacing
	 * any existing value for the given key.  Either key or value may be null.
	 *
	 * @param key a String, or null
	 * @param value a String array object, or null
	 */
	public void putStringArray(String key, String[] value) {
		mMap.put(key, value);
	}

	/**
	 * Returns the number of mappings contained in this Bundle.
	 *
	 * @return the number of mappings as an int.
	 */
	public int size() {
		return mMap.size();
	}

	/**
	 * Returns the value associated with the given key, or defaultValue if
	 * no mapping of the desired type exists for the given key.
	 *
	 * @param key a String
	 * @param defaultValue Value to return if key does not exist
	 * @return a boolean value
	 */
	public boolean getBoolean(String key, boolean defaultValue) {
		Object o = mMap.get(key);
		System.out.println("bundle.getBoolean(" + key + ", " + defaultValue + ") called");
		/* the default for this is very scummy */
		if (key.equals("com.facebook.sdk.AutoLogAppEventsEnabled")) {
			return false;
		}
		if (o == null) {
			return defaultValue;
		}
		try {
			return (Boolean)o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "Boolean", defaultValue, e);
			return defaultValue;
		}
	}

	/**
	 * Returns the value associated with the given key, or false if
	 * no mapping of the desired type exists for the given key.
	 *
	 * @param key a String
	 * @return a boolean value
	 */
	public boolean getBoolean(String key) {
		if (DEBUG)
			Log.d(TAG, "Getting boolean in " + Integer.toHexString(System.identityHashCode(this)));
		return getBoolean(key, false);
	}

	/**
	 * Inserts a Boolean value into the mapping of this Bundle, replacing
	 * any existing value for the given key.  Either key or value may be null.
	 *
	 * @param key a String, or null
	 * @param value a Boolean, or null
	 */
	public void putBoolean(String key, boolean value) {
		mMap.put(key, value);
	}

	/**
	 * Returns the value associated with the given key, or defaultValue if
	 * no mapping of the desired type exists for the given key.
	 *
	 * @param key a String
	 * @param defaultValue Value to return if key does not exist
	 * @return an int value
	 */
	public int getInt(String key, int defaultValue) {
		Object o = mMap.get(key);
		if (o == null) {
			return defaultValue;
		}
		try {
			return (Integer)o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "Integer", defaultValue, e);
			return defaultValue;
		}
	}

	/**
	 * Returns the value associated with the given key, or 0 if
	 * no mapping of the desired type exists for the given key.
	 *
	 * @param key a String
	 * @return an int value
	 */
	public int getInt(String key) {
		return getInt(key, 0);
	}

	/**
	 * Returns the value associated with the given key, or null if
	 * no mapping of the desired type exists for the given key or a null
	 * value is explicitly associated with the key.
	 *
	 * @param key a String, or null
	 * @return a long[] value, or null
	 */
	public long[] getLongArray(String key) {
		Object o = mMap.get(key);
		if (o == null) {
			return null;
		}
		try {
			return (long[])o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "long[]", e);
			return null;
		}
	}

	/**
	 * Returns the value associated with the given key, or null if
	 * no mapping of the desired type exists for the given key or a null
	 * value is explicitly associated with the key.
	 *
	 * @param key a String, or null
	 * @return a String[] value, or null
	 */
	public String[] getStringArray(String key) {
		Object o = mMap.get(key);
		if (o == null) {
			return null;
		}
		try {
			return (String[])o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "String[]", e);
			return null;
		}
	}

	/**
	 * Returns the value associated with the given key, or null if
	 * no mapping of the desired type exists for the given key or a null
	 * value is explicitly associated with the key.
	 *
	 * @param key a String, or null
	 * @return an int[] value, or null
	 */
	public int[] getIntArray(String key) {
		Object o = mMap.get(key);
		if (o == null) {
			return null;
		}
		try {
			return (int[])o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "int[]", e);
			return null;
		}
	}

	/** AOSP: magic number of a bundle parcelled by Java code ('B' 'N' 'D' 'L') */
	static final int BUNDLE_MAGIC_JAVA = 0x4C444E42;
	/** AOSP: magic number of a bundle parcelled by libbinder's PersistableBundle ('B' 'N' 'D' 'N') */
	static final int BUNDLE_MAGIC_NATIVE = 0x4C444E44;

	/** AOSP: the class loader for Parcelables read from a parcel (Bundle keeps its own). */
	private ClassLoader mBaseClassLoader;

	void setClassLoader(ClassLoader loader) {
		mBaseClassLoader = loader;
	}

	ClassLoader getClassLoader() {
		return mBaseClassLoader;
	}

	/**
	 * teslalinux: the loader values of a parcelled bundle are resolved with. AOSP unparcels lazily,
	 * after the reader has had the chance to call setClassLoader; ATL unparcels when the bundle is
	 * read, so a bundle whose loader is still the framework's resolves through the app's class
	 * loader (which delegates to the framework's first): what the later setClassLoader would give.
	 */
	static ClassLoader parcelClassLoader(ClassLoader loader) {
		if (loader != null && loader != BaseBundle.class.getClassLoader())
			return loader;
		try {
			ClassLoader app = android.atl.ATLLoadedApp.getPrimaryApplication().class_loader;
			if (app != null)
				return app;
		} catch (Throwable e) {
			// no application loaded (ATL's test runner): the framework loader
		}
		return loader != null ? loader : BaseBundle.class.getClassLoader();
	}

	/** AOSP BaseBundle.writeToParcelInner: length, magic, then the map as Parcel.writeArrayMapInternal. */
	void writeToParcelInner(Parcel parcel, int flags) {
		final ArrayMap<String, Object> map = mMap;
		// Special case for empty bundles.
		if (map == null || map.size() <= 0) {
			parcel.writeInt(0);
			return;
		}
		int lengthPos = parcel.dataPosition();
		parcel.writeInt(-1); // placeholder, will hold length
		parcel.writeInt(BUNDLE_MAGIC_JAVA);

		int startPos = parcel.dataPosition();
		parcel.writeArrayMapInternal(map);
		int endPos = parcel.dataPosition();

		// Backpatch length
		parcel.setDataPosition(lengthPos);
		int length = endPos - startPos;
		parcel.writeInt(length);
		parcel.setDataPosition(endPos);
	}

	/**
	 * AOSP BaseBundle.readFromParcelInner, unparcelling at once (see parcelClassLoader). The parcel
	 * ends up after the bundle's length whatever its values consumed, as in AOSP.
	 */
	void readFromParcelInner(Parcel parcel, int length) {
		if (length < 0) {
			throw new RuntimeException("Bad length in parcel: " + length);
		} else if (length == 0) {
			// Empty Bundle or end of data.
			mMap = new ArrayMap<String, Object>();
			return;
		} else if (length % 4 != 0) {
			throw new IllegalStateException("Bundle length is not aligned by 4: " + length);
		}
		final int magic = parcel.readInt();
		final boolean isJavaBundle = magic == BUNDLE_MAGIC_JAVA;
		final boolean isNativeBundle = magic == BUNDLE_MAGIC_NATIVE;
		if (!isJavaBundle && !isNativeBundle) {
			throw new IllegalStateException("Bad magic number for Bundle: 0x" + Integer.toHexString(magic));
		}
		final int offset = parcel.dataPosition();
		final int end = android.util.MathUtils.addOrThrow(offset, length);
		try {
			int count = parcel.readInt();
			if (count < 0) {
				mMap = new ArrayMap<String, Object>();
				return;
			}
			ArrayMap<String, Object> map = new ArrayMap<String, Object>(count);
			parcel.readArrayMap(map, count, /* sorted */ isJavaBundle, /* lazy */ false,
			                    parcelClassLoader(getClassLoader()));
			mMap = map;
		} finally {
			parcel.setDataPosition(end);
		}
	}

	/** AOSP: whether the values hold file descriptors (Parcel.hasFileDescriptors on the map). */
	boolean mapHasFileDescriptors() {
		return mMap != null && Parcel.hasFileDescriptors(mMap);
	}

	/** AOSP: inserts every mapping of bundle. */
	public void putAll(PersistableBundle bundle) {
		mMap.putAll(bundle.mMap);
	}

	/** AOSP: inserts a double value, replacing any existing value for the key. */
	public void putDouble(String key, double value) {
		mMap.put(key, value);
	}

	/** AOSP: inserts a boolean array value, replacing any existing value for the key. */
	public void putBooleanArray(String key, boolean[] value) {
		mMap.put(key, value);
	}

	/** AOSP: inserts a double array value, replacing any existing value for the key. */
	public void putDoubleArray(String key, double[] value) {
		mMap.put(key, value);
	}

	/** AOSP: the double for key, 0.0 when there is none or it is of another type. */
	public double getDouble(String key) {
		return getDouble(key, 0.0);
	}

	/** AOSP: the double for key, defaultValue when there is none or it is of another type. */
	public double getDouble(String key, double defaultValue) {
		Object o = mMap.get(key);
		if (o == null)
			return defaultValue;
		try {
			return (Double) o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "Double", defaultValue, e);
			return defaultValue;
		}
	}

	/** AOSP: the boolean array for key, or null. */
	public boolean[] getBooleanArray(String key) {
		Object o = mMap.get(key);
		if (o == null)
			return null;
		try {
			return (boolean[]) o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "byte[]", e);
			return null;
		}
	}

	/** AOSP: the double array for key, or null. */
	public double[] getDoubleArray(String key) {
		Object o = mMap.get(key);
		if (o == null)
			return null;
		try {
			return (double[]) o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "double[]", e);
			return null;
		}
	}
}
