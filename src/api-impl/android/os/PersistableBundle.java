package android.os;

public final class PersistableBundle extends BaseBundle implements Cloneable, Parcelable {

	@Override
	public synchronized String toString() {
		return "PersistableBundle[" + mMap.toString() + "]";
	}

	/* AOSP constructors and deepCopy (adding a constructor removes the implicit default one) */
	public PersistableBundle() { super(); }
	public PersistableBundle(PersistableBundle b) {
		super();
		mMap.putAll(b.mMap);
	}
	public PersistableBundle deepCopy() {
		PersistableBundle copy = new PersistableBundle();
		for (String key : keySet()) {
			Object v = get(key);
			if (v instanceof PersistableBundle) v = ((PersistableBundle) v).deepCopy();
			else if (v instanceof int[]) v = ((int[]) v).clone();
			else if (v instanceof long[]) v = ((long[]) v).clone();
			else if (v instanceof double[]) v = ((double[]) v).clone();
			else if (v instanceof boolean[]) v = ((boolean[]) v).clone();
			else if (v instanceof String[]) v = ((String[]) v).clone();
			copy.mMap.put(key, v);
		}
		return copy;
	}

	/** AOSP: an immutable empty PersistableBundle. */
	public static final PersistableBundle EMPTY;

	static {
		EMPTY = new PersistableBundle();
		EMPTY.mMap = android.util.ArrayMap.EMPTY;
	}

	/** AOSP: an empty bundle sized for capacity mappings. */
	public PersistableBundle(int capacity) {
		super();
		mMap.ensureCapacity(capacity);
	}

	/**
	 * AOSP (hidden): a PersistableBundle from a Bundle whose values are all of the persistable
	 * types; nested Bundles become PersistableBundles, anything else is an IllegalArgumentException.
	 */
	public PersistableBundle(Bundle b) {
		super();
		for (String key : b.keySet()) {
			Object value = b.get(key);
			if (value instanceof Bundle) {
				value = new PersistableBundle((Bundle) value);
			} else if (!isValidType(value)) {
				throw new IllegalArgumentException("Bad value in PersistableBundle key=" + key + " value=" + value);
			}
			mMap.put(key, value);
		}
	}

	/** AOSP (hidden): read from a parcel whose length int has been read already. */
	PersistableBundle(Parcel parcelledData, int length) {
		super();
		readFromParcelInner(parcelledData, length);
	}

	/** AOSP (hidden): the value types a PersistableBundle may hold. */
	public static boolean isValidType(Object value) {
		return (value instanceof Integer) || (value instanceof Long) || (value instanceof Double)
		    || (value instanceof String) || (value instanceof int[]) || (value instanceof long[])
		    || (value instanceof double[]) || (value instanceof String[])
		    || (value instanceof PersistableBundle) || (value == null)
		    || (value instanceof Boolean) || (value instanceof boolean[]);
	}

	/** AOSP: inserts a PersistableBundle value, replacing any existing value for the key. */
	public void putPersistableBundle(String key, PersistableBundle value) {
		mMap.put(key, value);
	}

	/** AOSP: the PersistableBundle for key, or null. */
	public PersistableBundle getPersistableBundle(String key) {
		Object o = mMap.get(key);
		if (o == null)
			return null;
		try {
			return (PersistableBundle) o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "Bundle", e);
			return null;
		}
	}

	/** AOSP: a shallow copy. */
	@Override
	public Object clone() {
		return new PersistableBundle(this);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	/** AOSP: written as a Bundle is (length, magic, map), never with file descriptors. */
	@Override
	public void writeToParcel(Parcel out, int flags) {
		final boolean oldAllowFds = out.pushAllowFds(false);
		try {
			writeToParcelInner(out, flags);
		} finally {
			out.restoreAllowFds(oldAllowFds);
		}
	}

	public static final Parcelable.Creator<PersistableBundle> CREATOR = new Parcelable.Creator<PersistableBundle>() {
		@Override
		public PersistableBundle createFromParcel(Parcel in) {
			return in.readPersistableBundle();
		}

		@Override
		public PersistableBundle[] newArray(int size) {
			return new PersistableBundle[size];
		}
	};
}
