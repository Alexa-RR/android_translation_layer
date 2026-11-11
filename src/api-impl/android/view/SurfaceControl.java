/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;

/**
 * Handle to a layer of the compositor (AOSP: a SurfaceFlinger layer).
 *
 * On Android a SurfaceControl names a layer in the system compositor; layers form a tree whose
 * roots are the windows, and a layer is on screen when its chain of parents reaches a window that
 * is on screen. Here the compositor is GTK inside this process: a layer is a {@link Layer} object,
 * its handle is that object itself (a Binder, so it passes through a Parcel the way AOSP's layer
 * handle does: readStrongBinder gives the same object back), and the layers that reach the screen
 * are:
 * - the layer of a {@link SurfaceView} ({@link SurfaceView#getSurfaceControl}), which is on screen
 *   where the SurfaceView is;
 * - the container layer of a {@link SurfaceControlViewHost}, whose content is that host's view
 *   hierarchy (an embedded window). When the chain of parents of such a layer reaches a SurfaceView
 *   layer, and every layer on the way is shown, its window is drawn in the SurfaceView's place,
 *   above the SurfaceView's own content, offset by the positions on the way and with their alpha
 *   multiplied, and it takes the touches there, as an embedded window does on Android.
 * Buffer layers (no container) can be built and placed; they show nothing until a buffer is queued
 * to them, and nothing here queues buffers to a bare layer (Surface(SurfaceControl) is not part of
 * this runtime), so they stay empty, as an AOSP buffer layer that never received a buffer does.
 *
 * Layers live while a SurfaceControl refers to them or while they have a parent, as on Android: a
 * released layer without a parent is destroyed, and its children lose their parent.
 */
public final class SurfaceControl implements Parcelable {
	/** A layer: the compositor-side object. Its identity is the handle. */
	static final class Layer extends Binder {
		final String name;
		final boolean container;
		int width, height, format;
		boolean hidden;
		float x, y;
		float alpha = 1f;
		int z;
		/** order of the last reparent, ties of equal z keep the later one on top (AOSP) */
		long order;
		Layer parent;
		final ArrayList<Layer> children = new ArrayList<>();
		/** SurfaceControl objects that refer to this layer */
		int refs;
		boolean destroyed;
		/** the SurfaceView this layer belongs to (its own layer), or null */
		SurfaceView surfaceView;
		/** the embedded window shown by this layer (a SurfaceControlViewHost's), or null */
		SurfaceControlViewHost.EmbeddedWindow window;

		Layer(String name, boolean container) {
			this.name = name;
			this.container = container;
		}

		@Override
		public String toString() {
			return "Layer{" + name + (destroyed ? " destroyed" : "") + "}";
		}
	}

	private static long sOrder;

	private Layer mLayer;
	private String mName;

	/** AOSP: an empty SurfaceControl, to be filled by readFromParcel or copyFrom. */
	public SurfaceControl() {
	}

	/** AOSP: another reference to the same layer. */
	public SurfaceControl(SurfaceControl other, String callsite) {
		copyFrom(other, callsite);
	}

	SurfaceControl(Layer layer) {
		mLayer = layer;
		mName = layer.name;
		layer.refs++;
	}

	Layer getLayer() {
		return mLayer;
	}

	/** AOSP: makes this a reference to the other's layer (releasing the layer it had). */
	public void copyFrom(SurfaceControl other, String callsite) {
		synchronized (SurfaceControl.class) {
			Layer layer = other.mLayer;
			if (layer != null)
				layer.refs++;
			releaseLocked();
			mLayer = layer;
			mName = other.mName;
		}
	}

	public boolean isValid() {
		synchronized (SurfaceControl.class) {
			return mLayer != null && !mLayer.destroyed;
		}
	}

	/** AOSP: drops this reference; the layer goes away when nothing refers to it and it has no parent. */
	public void release() {
		synchronized (SurfaceControl.class) {
			releaseLocked();
		}
	}

	private void releaseLocked() {
		Layer layer = mLayer;
		mLayer = null;
		if (layer == null)
			return;
		layer.refs--;
		if (layer.refs <= 0 && layer.parent == null)
			destroyLocked(layer);
	}

	/** the layer is gone: its children are orphaned and destroyed in turn when nothing refers to them */
	private static void destroyLocked(Layer layer) {
		if (layer.destroyed)
			return;
		layer.destroyed = true;
		for (Layer child : new ArrayList<>(layer.children)) {
			child.parent = null;
			layer.children.remove(child);
			if (child.refs <= 0)
				destroyLocked(child);
			else
				place(child);
		}
		if (layer.window != null)
			layer.window.place(null, 0, 0, 0f, 0);
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			release();
		} finally {
			super.finalize();
		}
	}

	public String toString() {
		return "Surface(name=" + mName + ")/@0x" + Integer.toHexString(System.identityHashCode(this));
	}

	@Override
	public int describeContents() {
		return 0;
	}

	/** AOSP writes the name and the layer handle; the handle of an in-process layer is the layer. */
	@Override
	public void writeToParcel(Parcel dest, int flags) {
		synchronized (SurfaceControl.class) {
			if (mLayer == null || mLayer.destroyed)
				throw new IllegalStateException("Cannot write an invalid SurfaceControl to a Parcel");
			dest.writeString8(mName);
			dest.writeStrongBinder(mLayer);
		}
		if ((flags & Parcelable.PARCELABLE_WRITE_RETURN_VALUE) != 0)
			release();
	}

	/** AOSP: becomes a new reference to the layer in the parcel. */
	public void readFromParcel(Parcel in) {
		if (in == null)
			throw new IllegalArgumentException("source must not be null");
		String name = in.readString8();
		IBinder handle = in.readStrongBinder();
		synchronized (SurfaceControl.class) {
			releaseLocked();
			if (!(handle instanceof Layer))
				throw new IllegalArgumentException("Parcel does not hold a layer handle of this process: " + handle);
			mLayer = (Layer) handle;
			mLayer.refs++;
			mName = name;
		}
	}

	public static final Creator<SurfaceControl> CREATOR = new Creator<SurfaceControl>() {
		public SurfaceControl createFromParcel(Parcel in) {
			SurfaceControl sc = new SurfaceControl();
			sc.readFromParcel(in);
			return sc;
		}

		public SurfaceControl[] newArray(int size) {
			return new SurfaceControl[size];
		}
	};

	/**
	 * Puts the layer's window, and the windows of its children, where its chain of parents says:
	 * in the SurfaceView the chain reaches when every layer on the way is shown, else nowhere.
	 */
	static void place(Layer layer) {
		float x = 0, y = 0, alpha = 1f;
		boolean shown = !layer.destroyed;
		SurfaceView target = null;
		for (Layer l = layer; l != null && shown; l = l.parent) {
			if (l.surfaceView != null) {
				target = l.surfaceView;
				break;
			}
			if (l.hidden || l.destroyed)
				shown = false;
			x += l.x;
			y += l.y;
			alpha *= l.alpha;
		}
		if (layer.window != null) {
			if (shown && target != null)
				layer.window.place(target, Math.round(x), Math.round(y), alpha, stackKey(layer));
			else
				layer.window.place(null, 0, 0, 0f, 0);
		}
		for (Layer child : new ArrayList<>(layer.children))
			place(child);
	}

	/** stacking among the windows in one SurfaceView: by z on each level of the chain, then by age */
	private static long stackKey(Layer layer) {
		/* the windows here are leaves of one SurfaceView layer, mostly direct children of it; their
		 * own z (and their order of attachment) decides, as for siblings in AOSP */
		return ((long) layer.z << 32) + (layer.order & 0xffffffffL);
	}

	/** AOSP SurfaceControl.Builder */
	public static class Builder {
		private String mName;
		private int mWidth, mHeight;
		private int mFormat = android.graphics.PixelFormat.OPAQUE;
		private boolean mContainer;
		private boolean mHidden;
		private SurfaceControl mParent;

		public Builder() {
		}

		/** hidden API: the session; there is one compositor in this process */
		public Builder(SurfaceSession session) {
		}

		public Builder setName(String name) {
			mName = name;
			return this;
		}

		public Builder setBufferSize(int width, int height) {
			if (width < 0 || height < 0)
				throw new IllegalArgumentException("width and height must be positive");
			mWidth = width;
			mHeight = height;
			return this;
		}

		public Builder setFormat(int format) {
			mFormat = format;
			return this;
		}

		public Builder setOpaque(boolean opaque) {
			return this;
		}

		public Builder setHidden(boolean hidden) {
			mHidden = hidden;
			return this;
		}

		public Builder setParent(SurfaceControl parent) {
			mParent = parent;
			return this;
		}

		public Builder setContainerLayer() {
			mContainer = true;
			mWidth = mHeight = 0;
			return this;
		}

		public Builder setCallsite(String callsite) {
			return this;
		}

		public SurfaceControl build() {
			if (mName == null)
				throw new IllegalStateException("name must be set");
			if (!mContainer && (mWidth < 0 || mHeight < 0))
				throw new IllegalStateException("width and height must be positive or unset");
			Layer layer = new Layer(mName, mContainer);
			layer.width = mWidth;
			layer.height = mHeight;
			layer.format = mFormat;
			layer.hidden = mHidden;
			SurfaceControl sc;
			synchronized (SurfaceControl.class) {
				sc = new SurfaceControl(layer);
				if (mParent != null && mParent.mLayer != null && !mParent.mLayer.destroyed)
					reparentLocked(layer, mParent.mLayer);
			}
			return sc;
		}
	}

	/** hidden AOSP class; one compositor connection per process */
	public static final class SurfaceSession {
		public SurfaceSession() {
		}

		public void kill() {
		}
	}

	private static void reparentLocked(Layer layer, Layer parent) {
		for (Layer p = parent; p != null; p = p.parent) {
			if (p == layer)
				throw new IllegalArgumentException("Cannot reparent " + layer + " under its own child");
		}
		if (layer.parent != null)
			layer.parent.children.remove(layer);
		layer.parent = parent;
		layer.order = ++sOrder;
		if (parent != null)
			parent.children.add(layer);
		else if (layer.refs <= 0)
			destroyLocked(layer);
	}

	private static void checkNotReleased(SurfaceControl sc) {
		if (sc == null)
			throw new IllegalArgumentException("SurfaceControl must not be null");
		if (sc.mLayer == null)
			throw new NullPointerException("mNativeObject of " + sc + " is null. Have you called release() already?");
	}

	/**
	 * AOSP SurfaceControl.Transaction: changes to layers, applied together by {@link #apply}. Each
	 * operation is recorded and runs, in order, when the transaction is applied.
	 */
	public static class Transaction implements java.io.Closeable {
		private interface Op {
			void run();
		}

		private final ArrayList<Op> mOps = new ArrayList<>();
		private final ArrayList<Layer> mTouched = new ArrayList<>();

		public Transaction() {
		}

		private Transaction add(SurfaceControl sc, Op op) {
			checkNotReleased(sc);
			mOps.add(op);
			if (!mTouched.contains(sc.mLayer))
				mTouched.add(sc.mLayer);
			return this;
		}

		public Transaction setVisibility(SurfaceControl sc, boolean visible) {
			final Layer l = sc != null ? sc.mLayer : null;
			return add(sc, () -> l.hidden = !visible);
		}

		public Transaction show(SurfaceControl sc) {
			return setVisibility(sc, true);
		}

		public Transaction hide(SurfaceControl sc) {
			return setVisibility(sc, false);
		}

		public Transaction setPosition(SurfaceControl sc, float x, float y) {
			final Layer l = sc != null ? sc.mLayer : null;
			return add(sc, () -> {
				l.x = x;
				l.y = y;
			});
		}

		public Transaction setLayer(SurfaceControl sc, int z) {
			final Layer l = sc != null ? sc.mLayer : null;
			return add(sc, () -> l.z = z);
		}

		public Transaction setAlpha(SurfaceControl sc, float alpha) {
			if (alpha < 0f || alpha > 1f)
				throw new IllegalArgumentException("alpha must be between 0 and 1, got " + alpha);
			final Layer l = sc != null ? sc.mLayer : null;
			return add(sc, () -> l.alpha = alpha);
		}

		public Transaction setBufferSize(SurfaceControl sc, int w, int h) {
			final Layer l = sc != null ? sc.mLayer : null;
			return add(sc, () -> {
				l.width = w;
				l.height = h;
			});
		}

		/** AOSP: a null parent detaches the layer (it is destroyed if nothing refers to it). */
		public Transaction reparent(SurfaceControl sc, SurfaceControl newParent) {
			final Layer l = sc != null ? sc.mLayer : null;
			final Layer p = newParent != null ? newParent.mLayer : null;
			if (newParent != null)
				checkNotReleased(newParent);
			add(sc, () -> {
				Layer before = l.parent;
				reparentLocked(l, p != null && !p.destroyed ? p : null);
				if (before != null && !mTouched.contains(before))
					mTouched.add(before);
			});
			return this;
		}

		/** AOSP: the other transaction's changes follow this one's; the other is left empty. */
		public Transaction merge(Transaction other) {
			if (this == other)
				return this;
			mOps.addAll(other.mOps);
			for (Layer l : other.mTouched) {
				if (!mTouched.contains(l))
					mTouched.add(l);
			}
			other.mOps.clear();
			other.mTouched.clear();
			return this;
		}

		public void apply() {
			ArrayList<Layer> touched;
			synchronized (SurfaceControl.class) {
				for (Op op : mOps)
					op.run();
				touched = new ArrayList<>(mTouched);
				mOps.clear();
				mTouched.clear();
			}
			Runnable placeAll = () -> {
				synchronized (SurfaceControl.class) {
					for (Layer l : touched)
						place(l);
				}
			};
			/* the windows are GTK widgets: they move on the main thread (the compositor's side) */
			if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
				placeAll.run();
			else
				new android.os.Handler(android.os.Looper.getMainLooper()).post(placeAll);
		}

		/** AOSP: apply(sync); there is no other process to wait for, the changes are made on return or posted */
		public void apply(boolean sync) {
			apply();
		}

		@Override
		public void close() {
			mOps.clear();
			mTouched.clear();
		}
	}

}
