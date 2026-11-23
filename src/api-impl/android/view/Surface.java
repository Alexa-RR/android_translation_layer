package android.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import java.nio.ByteBuffer;

/**
 * Handle onto a raw buffer that is being managed by the screen compositor. Here a surface is the
 * content of a SurfaceView's widget (`widget`), which EGL window surfaces, MediaCodec output and
 * lockCanvas draw into, or the producer side of a SurfaceTexture's buffer queue.
 *
 * A Surface passes through a Parcel the way AOSP's does: AOSP writes the name and the producer's
 * binder (IGraphicBufferProducer), and the receiving side gets a Surface drawing into the same
 * buffer queue. The producer of an in-process surface is the surface itself, written as a binder
 * handle; reading it gives a Surface that draws into the same widget.
 *
 * lockCanvas / unlockCanvasAndPost (AOSP's software canvas): on a SurfaceTexture's surface the
 * CPU connects as the queue's producer (AOSP NATIVE_WINDOW_API_CPU, until the Surface is
 * released; an EGL surface cannot connect meanwhile, nor this while one is), each lock gives a
 * Canvas on a buffer of the SurfaceTexture's default buffer size, and each post queues the pixels
 * to its consumer. As AOSP's Surface::lock, a buffer starts with the previous one's content when
 * it has the same size (so only inOutDirty needs drawing; the canvas is clipped to it), and
 * inOutDirty grows to the whole buffer otherwise. On a SurfaceView's surface the canvas is the
 * view's (SurfaceHolder.lockCanvas), posted to its widget; it is always the whole surface.
 */
public class Surface implements Parcelable {
	private static final String TAG = "Surface";

	public long widget;
	private String mName;
	/* the SurfaceView this is the surface of (lockCanvas draws through it) */
	private SurfaceView tlOwner;

	/** AOSP: thrown by lockCanvas when the surface has no buffer for it. */
	public static class OutOfResourcesException extends RuntimeException {
		public OutOfResourcesException() {}

		public OutOfResourcesException(String name) {
			super(name);
		}
	}

	/** the producer handle written to parcels: identifies the surface in this process */
	private static final class Producer extends Binder {
		final Surface surface;
		/* the SurfaceTexture queue, with a reference of the producer's own: it outlives the
		 * Surface it was written from (PARCELABLE_WRITE_RETURN_VALUE releases that one) */
		long surfaceTexture;

		Producer(Surface surface) {
			this.surface = surface;
		}

		@Override
		protected void finalize() throws Throwable {
			try {
				if (surfaceTexture != 0)
					SurfaceTexture.tlUnrefNative(surfaceTexture);
				surfaceTexture = 0;
			} finally {
				super.finalize();
			}
		}
	}

	private Producer mProducer;

	/* a surface made from a SurfaceTexture: a reference to its native buffer queue (libandroid
	 * surface_texture.c), whose producer window EGL window surfaces on this Surface draw into */
	public long tlSurfaceTexture;

	/* lockCanvas: the CPU connection, the locked buffer and canvas, the last posted buffer */
	private final Object mLock = new Object();
	private boolean mCpuConnected;
	private Canvas mLockedCanvas;
	private Bitmap mLockedBitmap;
	private Bitmap mPostedBitmap;
	private ByteBuffer mPixels;

	public Surface() {}

	Surface(SurfaceView owner) {
		tlOwner = owner;
	}

	/**
	 * AOSP: a Surface whose frames go to the SurfaceTexture's image stream (the SurfaceTexture's
	 * buffer producer).
	 */
	public Surface(SurfaceTexture surfaceTexture) {
		if (surfaceTexture == null)
			throw new IllegalArgumentException("surfaceTexture must not be null");
		mName = surfaceTexture.toString();
		tlSurfaceTexture = surfaceTexture.tlRefNative();
	}

	public boolean isValid() {
		return widget != 0 || tlSurfaceTexture != 0;
	}

	/** AOSP: drops this Surface's reference to its buffer producer; it is invalid afterwards. */
	public void release() {
		long st;
		boolean cpu;
		synchronized (mLock) {
			synchronized (this) {
				st = tlSurfaceTexture;
				tlSurfaceTexture = 0;
			}
			cpu = mCpuConnected;
			mCpuConnected = false;
			mLockedCanvas = null;
			mLockedBitmap = null;
			mPostedBitmap = null;
			mPixels = null;
		}
		if (st != 0) {
			/* AOSP ~Surface: a connected producer disconnects */
			if (cpu)
				SurfaceTexture.tlCpuDisconnect(st);
			SurfaceTexture.tlUnrefNative(st);
		}
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			release();
		} finally {
			super.finalize();
		}
	}

	/* ---- the software canvas ---- */

	/**
	 * AOSP: a Canvas for drawing into this surface; post it with unlockCanvasAndPost.
	 *
	 * @param inOutDirty what the caller will redraw (null: everything); on return what it must
	 *     redraw, which may be more
	 * @throws IllegalArgumentException when the surface is locked already, or the buffer cannot
	 *     be had (another producer is connected)
	 * @throws OutOfResourcesException when no buffer can be made
	 * @throws IllegalStateException when the surface was released
	 */
	public Canvas lockCanvas(Rect inOutDirty) throws Surface.OutOfResourcesException, IllegalArgumentException {
		synchronized (mLock) {
			if (!isValid())
				throw new IllegalStateException("Surface has already been released.");
			if (mLockedCanvas != null)
				throw new IllegalArgumentException("Surface was already locked");
			if (tlSurfaceTexture == 0)
				return lockViewCanvas(inOutDirty);
			if (!mCpuConnected) {
				/* AOSP Surface::lock: connect(NATIVE_WINDOW_API_CPU), -EINVAL when another
				 * producer is connected or the queue was abandoned */
				if (!SurfaceTexture.tlCpuConnect(tlSurfaceTexture))
					throw new IllegalArgumentException();
				mCpuConnected = true;
			}
			int[] size = SurfaceTexture.tlDefaultBufferSize(tlSurfaceTexture);
			int w = Math.max(size[0], 1), h = Math.max(size[1], 1);
			Rect bounds = new Rect(0, 0, w, h);
			Rect dirty = inOutDirty != null ? new Rect(inOutDirty) : new Rect(bounds);
			if (!dirty.intersect(bounds))
				dirty.setEmpty();
			Bitmap buffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
			if (buffer == null)
				throw new OutOfResourcesException("no " + w + "x" + h + " buffer");
			Canvas canvas = new Canvas(buffer);
			boolean copyBack = mPostedBitmap != null && mPostedBitmap.getWidth() == w && mPostedBitmap.getHeight() == h;
			if (copyBack) {
				/* AOSP Surface::lock copies the previous buffer back: outside the dirty region
				 * its content stays (drawn into the empty buffer: the same pixels) */
				canvas.drawBitmap(mPostedBitmap, 0, 0, null);
			} else {
				dirty.set(bounds);
			}
			/* the clip in a save level of its own, which unlockCanvasAndPost restores before
			 * reading the pixels: a GTK snapshot read with a clip still pushed keeps only what was
			 * drawn inside the clip (AOSP clips without a save; the save count here is one more) */
			canvas.save();
			canvas.clipRect(dirty);
			if (inOutDirty != null)
				inOutDirty.set(dirty);
			mLockedBitmap = buffer;
			mLockedCanvas = canvas;
			return canvas;
		}
	}

	private Canvas lockViewCanvas(Rect inOutDirty) {
		Canvas canvas = tlOwner != null ? tlOwner.tlLockCanvas() : null;
		if (canvas == null)
			throw new IllegalArgumentException("the surface has no buffer (its view has no size)");
		/* a snapshot replaces the whole surface: all of it must be drawn */
		if (inOutDirty != null)
			inOutDirty.set(0, 0, tlOwner.getWidth(), tlOwner.getHeight());
		mLockedCanvas = canvas;
		return canvas;
	}

	/**
	 * AOSP lockHardwareCanvas: a canvas for the whole surface, whose content is not kept from the
	 * previous frame (the renderer draws every frame anew).
	 */
	public Canvas lockHardwareCanvas() {
		synchronized (mLock) {
			Bitmap previous = mPostedBitmap;
			mPostedBitmap = null;
			try {
				return lockCanvas(null);
			} finally {
				if (mLockedCanvas == null)
					mPostedBitmap = previous;
			}
		}
	}

	/** AOSP: posts the canvas lockCanvas gave to the surface's consumer. */
	public void unlockCanvasAndPost(Canvas canvas) {
		synchronized (mLock) {
			if (!isValid())
				throw new IllegalStateException("Surface has already been released.");
			if (canvas != mLockedCanvas)
				throw new IllegalArgumentException("canvas object must be the same instance that was previously returned by lockCanvas");
			if (mLockedCanvas == null)
				throw new IllegalStateException("Surface was not locked");
			Bitmap buffer = mLockedBitmap;
			mLockedCanvas = null;
			mLockedBitmap = null;
			if (tlSurfaceTexture == 0) {
				tlOwner.tlUnlockCanvasAndPost(canvas);
				return;
			}
			/* the dirty clip and whatever the app left saved: the buffer is done */
			canvas.restoreToCount(1);
			int bytes = buffer.getWidth() * buffer.getHeight() * 4;
			if (mPixels == null || mPixels.capacity() != bytes)
				mPixels = ByteBuffer.allocateDirect(bytes);
			mPixels.clear();
			/* ARGB_8888 as Android keeps it: R, G, B, A bytes, premultiplied, top row first */
			buffer.copyPixelsToBuffer(mPixels);
			mPixels.rewind();
			if (!SurfaceTexture.tlQueuePixels(tlSurfaceTexture, mPixels, buffer.getWidth(), buffer.getHeight()))
				Log.w(TAG, "unlockCanvasAndPost: the SurfaceTexture was released, the frame is dropped");
			mPostedBitmap = buffer;
		}
	}

	/** @deprecated AOSP: use unlockCanvasAndPost. */
	@Deprecated
	public void unlockCanvas(Canvas canvas) {
		throw new UnsupportedOperationException();
	}

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
			if (mProducer.surfaceTexture == 0 && tlSurfaceTexture != 0)
				mProducer.surfaceTexture = SurfaceTexture.tlRefNativePtr(tlSurfaceTexture);
			producer = mProducer;
		}
		dest.writeString8(mName);
		/* AOSP: whether the surface is a single-buffer (shared) surface; never here */
		dest.writeInt(0);
		dest.writeStrongBinder(isValid() ? producer : null);
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
			release();
			widget = p.surface.widget;
			tlOwner = p.surface.tlOwner;
			long st = p.surfaceTexture;
			tlSurfaceTexture = st != 0 ? SurfaceTexture.tlRefNativePtr(st) : 0;
		} else {
			release();
			widget = 0;
			tlOwner = null;
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
