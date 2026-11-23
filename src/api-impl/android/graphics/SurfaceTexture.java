/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.graphics;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.lang.ref.WeakReference;

/**
 * Captures frames from an image stream as an OpenGL ES texture (AOSP).
 *
 * <p>The image stream comes from a producer drawing into a {@link android.view.Surface} made from
 * the SurfaceTexture, or into an EGL window surface made on it (EGL14/EGL10
 * eglCreateWindowSurface): ATL's EGL window surfaces queue each frame here (libandroid
 * surface_texture.c, AOSP's BufferQueue and GLConsumer). When {@link #updateTexImage} is called,
 * the texture object specified when the SurfaceTexture was created is updated to contain the
 * next image from the stream, bound to the GL_TEXTURE_EXTERNAL_OES target.
 *
 * <p>SurfaceTexture objects may be created on any thread. {@link #updateTexImage} may only be
 * called on the thread with the OpenGL ES context that contains the texture object. The
 * frame-available callback is posted to the listener's Handler (by default the looper of the
 * thread that created the SurfaceTexture, else the main looper).
 */
public class SurfaceTexture {
	private final Looper mCreatorLooper;
	private Handler mOnFrameAvailableHandler;

	/* the native buffer queue (libandroid struct tl_surface_texture); 0 once finalized */
	long mSurfaceTexture;
	private boolean mIsSingleBuffered;

	/** Callback interface for being notified that a new stream frame is available. */
	public interface OnFrameAvailableListener {
		void onFrameAvailable(SurfaceTexture surfaceTexture);
	}

	/** Exception thrown when a SurfaceTexture couldn't be created or resized. */
	@SuppressWarnings("serial")
	@Deprecated
	public static class OutOfResourcesException extends Exception {
		public OutOfResourcesException() {
		}

		public OutOfResourcesException(String name) {
			super(name);
		}
	}

	/**
	 * Construct a new SurfaceTexture to stream images to a given OpenGL texture.
	 *
	 * @param texName the OpenGL texture object name (e.g. generated via glGenTextures)
	 */
	public SurfaceTexture(int texName) {
		this(texName, false);
	}

	/**
	 * Construct a new SurfaceTexture to stream images to a given OpenGL texture.
	 *
	 * @param texName the OpenGL texture object name (e.g. generated via glGenTextures)
	 * @param singleBufferMode whether the SurfaceTexture will be in single buffered mode
	 */
	public SurfaceTexture(int texName, boolean singleBufferMode) {
		mCreatorLooper = Looper.myLooper();
		mIsSingleBuffered = singleBufferMode;
		mSurfaceTexture = nativeInit(false, texName, new WeakReference<SurfaceTexture>(this));
	}

	/**
	 * Construct a new SurfaceTexture to stream images to a given OpenGL texture, in detached
	 * mode: it must be attached with {@link #attachToGLContext} before updateTexImage.
	 */
	public SurfaceTexture(boolean singleBufferMode) {
		mCreatorLooper = Looper.myLooper();
		mIsSingleBuffered = singleBufferMode;
		mSurfaceTexture = nativeInit(true, 0, new WeakReference<SurfaceTexture>(this));
	}

	public void setOnFrameAvailableListener(OnFrameAvailableListener listener) {
		setOnFrameAvailableListener(listener, null);
	}

	public void setOnFrameAvailableListener(final OnFrameAvailableListener listener, Handler handler) {
		if (listener != null) {
			// Although we claim the thread is arbitrary, earlier implementation would
			// prefer to send the callback on the creating looper or the main looper
			// so we preserve this behavior here.
			Looper looper = handler != null ? handler.getLooper() : mCreatorLooper != null ? mCreatorLooper : Looper.getMainLooper();
			mOnFrameAvailableHandler = new Handler(looper, null, true /*async*/) {
				@Override
				public void handleMessage(Message msg) {
					listener.onFrameAvailable(SurfaceTexture.this);
				}
			};
		} else {
			mOnFrameAvailableHandler = null;
		}
	}

	/**
	 * Set the default size of the image buffers. The image producer may override the buffer size,
	 * in which case the producer-set buffer size will be used, not the default size set by this
	 * method. For OpenGL ES, the EGLSurface should be destroyed, made not-current, and then
	 * recreated to ensure that the new default size has taken effect.
	 */
	public void setDefaultBufferSize(int width, int height) {
		nativeSetDefaultBufferSize(mSurfaceTexture, width, height);
	}

	/**
	 * Update the texture image to the most recent frame from the image stream. This may only be
	 * called while the OpenGL ES context that owns the texture is current on the calling thread.
	 * It will implicitly bind its texture to the GL_TEXTURE_EXTERNAL_OES texture target.
	 */
	public void updateTexImage() {
		nativeUpdateTexImage(mSurfaceTexture);
	}

	/** Releases the texture buffer; the texture shows a black image until the next updateTexImage. */
	public void releaseTexImage() {
		nativeReleaseTexImage(mSurfaceTexture);
	}

	/**
	 * Detach the SurfaceTexture from the OpenGL ES context that owns the OpenGL ES texture object;
	 * the texture object is deleted. Must be called with that context current.
	 */
	public void detachFromGLContext() {
		int err = nativeDetachFromGLContext(mSurfaceTexture);
		if (err != 0) {
			throw new RuntimeException("Error during detachFromGLContext (see logcat for details)");
		}
	}

	/**
	 * Attach the SurfaceTexture to the OpenGL ES context that is current on the calling thread; a
	 * new texture object (texName) takes the image.
	 */
	public void attachToGLContext(int texName) {
		int err = nativeAttachToGLContext(mSurfaceTexture, texName);
		if (err != 0) {
			throw new RuntimeException("Error during attachToGLContext (see logcat for details)");
		}
	}

	/**
	 * Retrieve the 4x4 texture coordinate transform matrix associated with the texture image set
	 * by the most recent call to {@link #updateTexImage}.
	 */
	public void getTransformMatrix(float[] mtx) {
		// Note we intentionally don't check mtx for null, so this will result in a
		// NullPointerException. But it's safe because it happens before the call to native.
		if (mtx.length != 16) {
			throw new IllegalArgumentException();
		}
		nativeGetTransformMatrix(mSurfaceTexture, mtx);
	}

	/**
	 * Retrieve the timestamp associated with the texture image set by the most recent call to
	 * {@link #updateTexImage}: in nanoseconds, the producer's presentation time
	 * (eglPresentationTimeANDROID) or the time the frame was queued.
	 */
	public long getTimestamp() {
		return nativeGetTimestamp(mSurfaceTexture);
	}

	/**
	 * Retrieve the dataspace of the texture image: the producer's frames here are GL renderings
	 * that set none (EGL window surfaces without a colorspace attribute), which is
	 * DataSpace.DATASPACE_UNKNOWN, as AOSP reports for such frames.
	 */
	public int getDataSpace() {
		return android.hardware.DataSpace.DATASPACE_UNKNOWN;
	}

	/**
	 * Complete tear down of the SurfaceTexture: the frames it holds go back to the producer and
	 * the producer is abandoned (its next eglSwapBuffers fails).
	 */
	public void release() {
		nativeRelease(mSurfaceTexture);
	}

	/** Returns true if the SurfaceTexture was released. */
	public boolean isReleased() {
		return nativeIsReleased(mSurfaceTexture);
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			long ptr = mSurfaceTexture;
			mSurfaceTexture = 0;
			if (ptr != 0)
				nativeFinalize(ptr);
		} finally {
			super.finalize();
		}
	}

	/**
	 * This method is invoked from native code only (the producer's thread, when it queues a
	 * frame).
	 */
	@SuppressWarnings({"UnusedDeclaration"})
	private static void postEventFromNative(WeakReference<SurfaceTexture> weakSelf) {
		SurfaceTexture st = weakSelf.get();
		if (st != null) {
			Handler handler = st.mOnFrameAvailableHandler;
			if (handler != null) {
				handler.sendEmptyMessage(0);
			}
		}
	}

	/** Returns true if the SurfaceTexture is single-buffered. */
	public boolean isSingleBuffered() {
		return mIsSingleBuffered;
	}

	/** {@hide} a reference to the native queue for a Surface made from this SurfaceTexture. */
	public long tlRefNative() {
		return nativeRef(mSurfaceTexture);
	}

	/** {@hide} another reference to a native queue (a Surface read from a Parcel). */
	public static long tlRefNativePtr(long ptr) {
		return nativeRef(ptr);
	}

	/** {@hide} drops a reference tlRefNative gave. */
	public static void tlUnrefNative(long ptr) {
		nativeUnref(ptr);
	}

	/**
	 * {@hide} Surface.lockCanvas: connects the CPU as the queue's producer (AOSP
	 * NATIVE_WINDOW_API_CPU); false while another producer is connected or after release.
	 */
	public static boolean tlCpuConnect(long ptr) {
		return nativeCpuConnect(ptr);
	}

	/** {@hide} the CPU producer goes (the Surface is released). */
	public static void tlCpuDisconnect(long ptr) {
		nativeCpuDisconnect(ptr);
	}

	/** {@hide} the size of the buffers a producer gets: {width, height}. */
	public static int[] tlDefaultBufferSize(long ptr) {
		int[] size = new int[2];
		nativeGetDefaultBufferSize(ptr, size);
		return size;
	}

	/**
	 * {@hide} Surface.unlockCanvasAndPost: queues width x height RGBA 8888 pixels (premultiplied,
	 * top row first, from a direct buffer) with the time it is queued (CLOCK_MONOTONIC); false
	 * when the SurfaceTexture was released.
	 */
	public static boolean tlQueuePixels(long ptr, java.nio.ByteBuffer pixels, int width, int height) {
		return nativeQueuePixels(ptr, pixels, width, height);
	}

	private static native boolean nativeCpuConnect(long ptr);
	private static native void nativeCpuDisconnect(long ptr);
	private static native void nativeGetDefaultBufferSize(long ptr, int[] size);
	private static native boolean nativeQueuePixels(long ptr, java.nio.ByteBuffer pixels, int width, int height);

	private static native long nativeInit(boolean isDetached, int texName, WeakReference<SurfaceTexture> weakSelf);
	private static native void nativeFinalize(long ptr);
	private static native void nativeGetTransformMatrix(long ptr, float[] mtx);
	private static native long nativeGetTimestamp(long ptr);
	private static native void nativeSetDefaultBufferSize(long ptr, int width, int height);
	private static native void nativeUpdateTexImage(long ptr);
	private static native void nativeReleaseTexImage(long ptr);
	private static native int nativeDetachFromGLContext(long ptr);
	private static native int nativeAttachToGLContext(long ptr, int texName);
	private static native void nativeRelease(long ptr);
	private static native boolean nativeIsReleased(long ptr);
	private static native long nativeRef(long ptr);
	private static native void nativeUnref(long ptr);
}
