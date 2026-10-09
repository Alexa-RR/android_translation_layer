package android.os;

public interface Parcelable {
	public static interface Creator<T> {
		public T createFromParcel(Parcel parcel);

		public T[] newArray(int size);
	}

	public static interface ClassLoaderCreator<T> extends Creator<T> {
		/** Create a new instance of the Parcelable class, instantiating it from the given Parcel using the given ClassLoader. */
		public T createFromParcel(Parcel source, ClassLoader loader);
	}

	public default int describeContents() {
		System.out.println("Parcelable.describeContents()");
		return 0;
	}

	public default void writeToParcel(Parcel dest, int flags) {
		System.out.println("Parcelable.writeToParcel(" + this + ", " + dest + ", " + flags + ")");
	}

	/** AOSP: flag for writeToParcel when the object is a return value of a remote call. */
	public static final int PARCELABLE_WRITE_RETURN_VALUE = 0x0001;
	/** AOSP: hidden flag, the object is to be stabilised for a different process (no-op in-process). */
	public static final int PARCELABLE_ELIDE_DUPLICATES = 0x0002;
	/** AOSP: describeContents bit, the object includes a file descriptor. */
	public static final int CONTENTS_FILE_DESCRIPTOR = 0x0001;
}
