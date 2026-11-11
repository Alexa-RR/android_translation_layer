/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.display;

import android.os.Handler;
import android.view.Display;
import android.view.Surface;

/**
 * Represents a virtual display. The content of a virtual display is rendered to a
 * {@link android.view.Surface} that you must pass to {@link DisplayManager#createVirtualDisplay}.
 * <p>
 * Because a virtual display renders to a surface provided by the application, it will be
 * released automatically when the process terminates and all remaining windows on it will
 * be forcibly removed.  However, you should also explicitly call {@link #release} when
 * you're done with it.
 * </p>
 *
 * <p>teslalinux: AOSP's VirtualDisplay; the display itself is kept by {@link DisplayManager}
 * (this runtime's display manager service), which adds, changes and removes it for its listeners.
 */
public final class VirtualDisplay {
	private final DisplayManager mDisplayManager;
	private final Display mDisplay;
	private final Callback mCallback;
	private final Handler mHandler;
	private Surface mSurface;
	private boolean mReleased;

	VirtualDisplay(DisplayManager displayManager, Display display, Surface surface, Callback callback, Handler handler) {
		mDisplayManager = displayManager;
		mDisplay = display;
		mSurface = surface;
		mCallback = callback;
		mHandler = handler;
	}

	/** Gets the virtual display. */
	public Display getDisplay() {
		return mDisplay;
	}

	/** Gets the surface that backs the virtual display. */
	public Surface getSurface() {
		return mSurface;
	}

	/**
	 * Sets the surface that backs the virtual display. Detaching the surface (null) turns the
	 * display off and pauses it; attaching one turns it back on and resumes it.
	 */
	public void setSurface(Surface surface) {
		Surface old;
		synchronized (this) {
			if (mReleased || mSurface == surface)
				return;
			old = mSurface;
			mSurface = surface;
		}
		mDisplayManager.tlSetVirtualDisplaySurface(this, old != null, surface != null);
	}

	/** Asks the virtual display to resize. */
	public void resize(int width, int height, int densityDpi) {
		if (width <= 0 || height <= 0 || densityDpi <= 0)
			throw new IllegalArgumentException("width, height, and densityDpi must be greater than 0");
		synchronized (this) {
			if (mReleased)
				return;
		}
		mDisplayManager.tlResizeVirtualDisplay(this, width, height, densityDpi);
	}

	/**
	 * Releases the virtual display and destroys its underlying surface. All remaining windows on
	 * the virtual display will be forcibly removed as part of releasing the virtual display.
	 */
	public void release() {
		synchronized (this) {
			if (mReleased)
				return;
			mReleased = true;
		}
		mDisplayManager.tlReleaseVirtualDisplay(this);
	}

	Callback getCallback() {
		return mCallback;
	}

	Handler getHandler() {
		return mHandler;
	}

	@Override
	public String toString() {
		return "VirtualDisplay{display=" + mDisplay + ", surface=" + mSurface + "}";
	}

	/** Interface for receiving information about a {@link VirtualDisplay}'s state changes. */
	public static abstract class Callback {
		/**
		 * Called when the virtual display video projection has been paused by the system or when
		 * the surface has been detached by the application by calling setSurface(null). The
		 * surface will not receive any more buffers while paused.
		 */
		public void onPaused() {}

		/** Called when the virtual display video projection has been resumed after having been paused. */
		public void onResumed() {}

		/**
		 * Called when the virtual display video projection has been stopped by the system. It will
		 * no longer receive frames and it will never be resumed. It is still the responsibility of
		 * the application to release() the virtual display.
		 */
		public void onStopped() {}
	}
}
