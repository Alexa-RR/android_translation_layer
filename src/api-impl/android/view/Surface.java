package android.view;

import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Handle onto a raw buffer that is being managed by the screen compositor. Here a surface is the
 * content of a SurfaceView's widget (`widget`), which EGL window surfaces, MediaCodec output and
 * lockCanvas draw into.
 *
 * A Surface passes through a Parcel the way AOSP's does: AOSP writes the name and the producer's
 * binder (IGraphicBufferProducer), and the receiving side gets a Surface drawing into the same
 * buffer queue. The producer of an in-process surface is the surface itself, written as a binder
 * handle; reading it gives a Surface that draws into the same widget.
 */
public class Surface implements Parcelable {
	public long widget;
	private String mName;

	/** the producer handle written to parcels: identifies the surface in this process */
	private static final class Producer extends Binder {
		final Surface surface;

		Producer(Surface surface) {
			this.surface = surface;
		}
	}

	private Producer mProducer;

	public Surface() {}

	public Surface(SurfaceTexture surfaceTexture) {}

	public boolean isValid() {
		return widget != 0;
	}

	public void release() {}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		IBinder producer;
		synchronized (this) {
			if (mProducer == null)
				mProducer = new Producer(this);
			producer = mProducer;
		}
		dest.writeString8(mName);
		/* AOSP: whether the surface is a single-buffer (shared) surface; never here */
		dest.writeInt(0);
		dest.writeStrongBinder(widget != 0 ? producer : null);
		if ((flags & Parcelable.PARCELABLE_WRITE_RETURN_VALUE) != 0)
			release();
	}

	/** AOSP: makes this Surface refer to the surface in the parcel (an invalid one for a null producer). */
	public void readFromParcel(Parcel source) {
		if (source == null)
			throw new IllegalArgumentException("source must not be null");
		mName = source.readString8();
		source.readInt();
		IBinder producer = source.readStrongBinder();
		if (producer instanceof Producer) {
			Producer p = (Producer) producer;
			synchronized (this) {
				mProducer = p;
			}
			widget = p.surface.widget;
		} else {
			widget = 0;
		}
	}

	public static final Creator<Surface> CREATOR = new Creator<Surface>() {
		public Surface createFromParcel(Parcel source) {
			Surface s = new Surface();
			s.readFromParcel(source);
			return s;
		}

		public Surface[] newArray(int size) {
			return new Surface[size];
		}
	};

	@Override
	public String toString() {
		return "Surface(name=" + mName + ")/@0x" + Integer.toHexString(System.identityHashCode(this));
	}
}
