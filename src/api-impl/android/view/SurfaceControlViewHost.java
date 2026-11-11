/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/**
 * Utility class for adding a View hierarchy to a {@link SurfaceControl}. The View hierarchy will
 * render in to a root SurfaceControl, and receive input based on the SurfaceControl's placement on
 * screen. The primary usage of this class is to embed a View hierarchy from one process in to
 * another (a SurfaceView in another component shows it through {@link
 * SurfaceView#setChildSurfacePackage}).
 *
 * Here the hierarchy is a window of this process's compositor (WindowManagerImpl's embedded
 * windows): the AOSP host creates a container layer and a ViewRootImpl drawing into it through a
 * WindowlessWindowManager; this one creates the container layer and gives it the hierarchy as an
 * embedded window, laid out at the size given to setView/relayout (WindowlessWindowManager.relayout
 * places the window at 0,0 with the requested size), attached as soon as it is set (ViewRootImpl's
 * first traversal), and shown wherever the layer is placed (see {@link SurfaceControl}).
 */
public class SurfaceControlViewHost {
	private final Context mContext;
	private final Display mDisplay;
	private final IBinder mHostToken;
	/** AOSP ViewRootImpl.getInputToken: identifies the embedded window for input transfers */
	private final IBinder mInputToken = new Binder();
	private SurfaceControl mSurfaceControl;
	private final EmbeddedWindow mWindow = new EmbeddedWindow();
	private View mView;
	private boolean mReleased;

	/**
	 * The embedded window of a SurfaceControlViewHost: its root view and its native layer, placed
	 * into a SurfaceView (or nowhere) by its SurfaceControl's layer tree.
	 */
	final class EmbeddedWindow {
		long nativeLayer;
		SurfaceView placedIn;

		/** on the main thread: show the window in the SurfaceView at x/y, or nowhere when sv is null */
		void place(SurfaceView sv, int x, int y, float alpha, long stackKey) {
			/* the window is a GTK widget: it moves on the main thread (layers may be released elsewhere) */
			if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> place(sv, x, y, alpha, stackKey));
				return;
			}
			if (nativeLayer == 0) {
				placedIn = null;
				return;
			}
			placedIn = sv;
			WindowManagerImpl.tlPlaceEmbeddedWindow(nativeLayer, sv != null ? sv.widget : 0, x, y, alpha, stackKey);
		}
	}

	/**
	 * Package encapsulating a Surface hierarchy which contains interactive view elements. It's
	 * expected to get this object from {@link SurfaceControlViewHost#getSurfacePackage} afterwards
	 * it can be embedded within a SurfaceView by calling {@link SurfaceView#setChildSurfacePackage}.
	 */
	public static final class SurfacePackage implements Parcelable {
		private SurfaceControl mSurfaceControl;
		private final IBinder mInputToken;

		SurfacePackage(SurfaceControl sc, IBinder inputToken) {
			mSurfaceControl = sc;
			mInputToken = inputToken;
		}

		/**
		 * Constructs a copy of {@code SurfacePackage} with an independent lifetime. The caller can
		 * use this to create an independent copy in situations where ownership of the {@code
		 * SurfacePackage} would be transferred elsewhere, such as attaching to a {@code
		 * SurfaceView}, returning as {@code Binder} result value, etc.
		 */
		public SurfacePackage(SurfacePackage other) {
			SurfaceControl otherSurfaceControl = other.mSurfaceControl;
			if (otherSurfaceControl != null && otherSurfaceControl.isValid()) {
				mSurfaceControl = new SurfaceControl();
				mSurfaceControl.copyFrom(otherSurfaceControl, "SurfacePackage");
			}
			mInputToken = other.mInputToken;
		}

		private SurfacePackage(Parcel in) {
			mSurfaceControl = new SurfaceControl();
			mSurfaceControl.readFromParcel(in);
			/* AOSP: the accessibility connection of the embedded hierarchy. This runtime has no
			 * accessibility service, so there is no embedded connection to pass (a null binder). */
			in.readStrongBinder();
			mInputToken = in.readStrongBinder();
		}

		/** Use {@link SurfaceView#setChildSurfacePackage} or manually fix accessibility (see SurfaceView.java). */
		public SurfaceControl getSurfaceControl() {
			return mSurfaceControl;
		}

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel out, int flags) {
			mSurfaceControl.writeToParcel(out, flags);
			out.writeStrongBinder(null);
			out.writeStrongBinder(mInputToken);
		}

		/**
		 * Release the {@link SurfaceControl} associated with this package. It's not necessary to
		 * call this if you pass the package to {@link SurfaceView#setChildSurfacePackage} as {@link
		 * SurfaceView} will take ownership in that case.
		 */
		public void release() {
			if (mSurfaceControl != null)
				mSurfaceControl.release();
			mSurfaceControl = null;
		}

		/** hidden AOSP API */
		public IBinder getInputToken() {
			return mInputToken;
		}

		public static final Creator<SurfacePackage> CREATOR = new Creator<SurfacePackage>() {
			public SurfacePackage createFromParcel(Parcel in) {
				return new SurfacePackage(in);
			}

			public SurfacePackage[] newArray(int size) {
				return new SurfacePackage[size];
			}
		};
	}

	/**
	 * Construct a new SurfaceControlViewHost. The root Surface will be allocated internally and is
	 * accessible via getSurfacePackage(). The hostToken parameter, primarily used for ANR reporting
	 * and focus transfer, should be obtained from {@link SurfaceView#getHostToken}.
	 */
	public SurfaceControlViewHost(Context context, Display display, IBinder hostToken) {
		mContext = Objects.requireNonNull(context);
		mDisplay = display;
		mHostToken = hostToken;
		mSurfaceControl = new SurfaceControl.Builder()
		                      .setContainerLayer()
		                      .setName("SurfaceControlViewHost")
		                      .setCallsite("SurfaceControlViewHost")
		                      .build();
		mSurfaceControl.getLayer().window = mWindow;
	}

	@Override
	protected void finalize() throws Throwable {
		/* AOSP: mViewRoot.die(false); the window is a GTK widget, removed on the main thread */
		if (!mReleased && mWindow.nativeLayer != 0) {
			final long layer = mWindow.nativeLayer;
			mWindow.nativeLayer = 0;
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> WindowManagerImpl.tlRemoveEmbeddedWindow(layer));
		}
		super.finalize();
	}

	/**
	 * Return a SurfacePackage for the root SurfaceControl of the embedded hierarchy. Rather than
	 * being directly reparented to some other SurfaceControl, it's expected to use
	 * SurfaceView#setChildSurfacePackage. Each call returns a new reference (release it when done).
	 */
	public SurfacePackage getSurfacePackage() {
		if (mSurfaceControl != null && mSurfaceControl.isValid())
			return new SurfacePackage(new SurfaceControl(mSurfaceControl, "getSurfacePackage"), mInputToken);
		return null;
	}

	/** Set the root view of the SurfaceControlViewHost. This view will render in to the SurfaceControl. */
	public void setView(View view, int width, int height) {
		final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
		                                                                     WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.TRANSPARENT);
		setView(view, lp);
	}

	/** hidden (TestApi) */
	public void setView(View view, WindowManager.LayoutParams attrs) {
		Objects.requireNonNull(view);
		attrs.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
		view.setLayoutParams(attrs);
		/* AOSP ViewRootImpl.setView: a window takes its view once; later calls are ignored */
		if (mView != null || mReleased)
			return;
		mView = view;
		mWindow.nativeLayer = WindowManagerImpl.tlAddEmbeddedWindow(view);
		/* the layer may already be placed (the package was attached before the view was set) */
		SurfaceControl.Layer layer = mSurfaceControl != null ? mSurfaceControl.getLayer() : null;
		if (layer != null) {
			synchronized (SurfaceControl.class) {
				SurfaceControl.place(layer);
			}
		}
	}

	/** @return The view passed to setView, or null if none has been passed. */
	public View getView() {
		return mView;
	}

	/** hidden (TestApi): new window attributes, laid out at the next traversal */
	public void relayout(WindowManager.LayoutParams attrs) {
		if (mView == null)
			return;
		mView.setLayoutParams(attrs);
		if (mWindow.nativeLayer != 0)
			WindowManagerImpl.tlRelayoutEmbeddedWindow(mWindow.nativeLayer);
	}

	/** Modify the size of the root view. */
	public void relayout(int width, int height) {
		final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
		                                                                     WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.TRANSPARENT);
		relayout(lp);
	}

	/**
	 * Trigger the tear down of the embedded view hierarchy and release the SurfaceControl. This
	 * will result in onDetachedFromWindow being dispatched to the embedded view hierarchy and
	 * release all resources.
	 */
	public void release() {
		if (mReleased)
			return;
		mReleased = true;
		if (mWindow.nativeLayer != 0) {
			long layer = mWindow.nativeLayer;
			mWindow.nativeLayer = 0;
			mWindow.placedIn = null;
			WindowManagerImpl.tlRemoveEmbeddedWindow(layer);
		}
		if (mSurfaceControl != null) {
			synchronized (SurfaceControl.class) {
				SurfaceControl.Layer layer = mSurfaceControl.getLayer();
				if (layer != null)
					layer.window = null;
			}
			/* ViewRootImpl releases the root layer; references handed out stay valid but empty */
			mSurfaceControl.release();
			mSurfaceControl = null;
		}
	}

	IBinder getHostToken() {
		return mHostToken;
	}
}
