package android.atl.carhost;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

/**
 * The car display's map surface: what a car head unit's host gives a navigation app that holds
 * ACCESS_SURFACE (AppManager.setSurfaceCallback). A window covering the display holds a
 * SurfaceView; its Surface goes to the app in a SurfaceContainer through the app's
 * ISurfaceCallback (onSurfaceAvailable with the size and density, again when the size changes,
 * onSurfaceDestroyed when it goes), the app draws its map into it, and the templates are shown
 * above it by the renderer (the car page). Gestures on the map reach the app as the host's
 * surface callbacks do on a car: onScroll (pan), onFling, onScale (pinch; a double tap zooms in by
 * 2, as the AOSP host does) and onClick.
 *
 * <p>The density the app gets is the car display's: the device's density times the map scale the
 * renderer chose ({@link ATLCarHost#carDensityDpi}). Apps size their map's text and symbols by it
 * (SurfaceContainer.getDpi). When it changes, the app gets the surface again the way a host
 * replaces its surface: onSurfaceDestroyed for the old one, onSurfaceAvailable at the new density,
 * then the visible and stable areas.
 *
 * The window and the gestures live on the main thread; every call into the app goes through the
 * host's thread, as the platform (ATLCarHost's identity).
 */
final class ATLCarSurface implements SurfaceHolder.Callback {
	private static final String TAG = "ATLCarSurface";
	private static final String DESCRIPTOR = "androidx.car.app.ISurfaceCallback";
	private static final int TRANSACTION_onSurfaceAvailable = IBinder.FIRST_CALL_TRANSACTION + 1;
	private static final int TRANSACTION_onVisibleAreaChanged = IBinder.FIRST_CALL_TRANSACTION + 2;
	private static final int TRANSACTION_onStableAreaChanged = IBinder.FIRST_CALL_TRANSACTION + 3;
	private static final int TRANSACTION_onSurfaceDestroyed = IBinder.FIRST_CALL_TRANSACTION + 4;
	private static final int TRANSACTION_onScroll = IBinder.FIRST_CALL_TRANSACTION + 5;
	private static final int TRANSACTION_onFling = IBinder.FIRST_CALL_TRANSACTION + 6;
	private static final int TRANSACTION_onScale = IBinder.FIRST_CALL_TRANSACTION + 7;
	private static final int TRANSACTION_onClick = IBinder.FIRST_CALL_TRANSACTION + 8;
	/** AOSP host: a double tap zooms in by this factor */
	private static final float DOUBLE_TAP_ZOOM_FACTOR = 2f;

	interface Sender {
		/** the app's ISurfaceCallback, or null while it has none (host thread) */
		IBinder callback();

		/** a SurfaceContainer of the app's library as a Bundleable to send, or null */
		android.os.Parcelable container(android.view.Surface surface, int width, int height, int dpi);

		/** a done callback for a call that reports back */
		IBinder done(String what);

		/**
		 * the surface's size or density changed (for the renderer): the display's density, which
		 * the renderer lays its templates out with, and the density the app's map got
		 */
		void surfaceInfo(int width, int height, int dpi, int mapDpi);
	}

	private final Context context;
	private final Handler host;
	private final Sender sender;
	private final Handler main = new Handler(Looper.getMainLooper());
	private SurfaceView view;
	/* main thread */
	private int width, height;
	private boolean available;
	/* the density the app got with the current surface (main thread) */
	private int givenDpi;
	private int[] visibleArea;
	/* the renderer's map scale on the display's density (written on the host thread) */
	private volatile float mapScale = 1f;
	/*
	 * The renderer's render scale: the map surface's buffers are this fraction of the display's
	 * size (SurfaceHolder.setFixedSize) and are scaled up to it; the app gets that size with the
	 * density scaled alike, so it lays its map out exactly as at full size, in fewer pixels (its
	 * GPU work is mostly per pixel). Gestures and the visible area reach it in its surface's pixels.
	 * Written on the host thread.
	 */
	private volatile float renderScale = 1f;
	/* the size the app got with the current surface (main thread) */
	private int givenWidth, givenHeight;

	static final float RENDER_SCALE_MIN = 0.5f;

	private int scaled(int px) {
		return Math.max(1, Math.round(px * renderScale));
	}

	float renderScale() {
		return renderScale;
	}

	/**
	 * The renderer's render scale (host thread; RENDER_SCALE_MIN..1, else 1). Returns whether it
	 * changed; if it did and the app has the surface, the app gets it again at the new size.
	 */
	boolean setRenderScale(float scale) {
		float s = (scale >= RENDER_SCALE_MIN && scale <= 1f) ? scale : 1f;
		if (s == renderScale)
			return false;
		renderScale = s;
		main.post(new Runnable() {
			@Override
			public void run() {
				if (!available || view == null)
					return;
				android.view.Surface surface = view.getHolder().getSurface();
				sendDestroyed(surface, width, height);
				sendAvailable(surface, width, height);
				if (visibleArea != null)
					setVisibleArea(visibleArea[0], visibleArea[1], visibleArea[2], visibleArea[3]);
			}
		});
		return true;
	}

	ATLCarSurface(Context context, Handler host, Sender sender) {
		this.context = context;
		this.host = host;
		this.sender = sender;
	}

	/** Shows the map surface (main thread). */
	void show() {
		main.post(new Runnable() {
			@Override
			public void run() {
				if (view != null)
					return;
				view = new SurfaceView(context);
				view.getHolder().addCallback(ATLCarSurface.this);
				installGestures(view);
				WindowManager.LayoutParams p = new WindowManager.LayoutParams(
				    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
				    WindowManager.LayoutParams.TYPE_APPLICATION, 0, android.graphics.PixelFormat.OPAQUE);
				p.setTitle("car map surface");
				((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).addView(view, p);
			}
		});
	}

	/** The app set or replaced its surface callback: it gets the current surface (host thread). */
	void onCallbackChanged() {
		main.post(new Runnable() {
			@Override
			public void run() {
				if (available)
					sendAvailable(view.getHolder().getSurface(), width, height);
				if (visibleArea != null)
					setVisibleArea(visibleArea[0], visibleArea[1], visibleArea[2], visibleArea[3]);
			}
		});
	}

	/**
	 * The part of the surface the templates leave free (display pixels), from the renderer:
	 * onVisibleAreaChanged, and onStableAreaChanged (the area that stays free whatever the
	 * template shows; here the same, as the renderer reports what it lays out).
	 */
	void setVisibleArea(int displayLeft, int displayTop, int displayRight, int displayBottom) {
		visibleArea = new int[] {displayLeft, displayTop, displayRight, displayBottom};
		/* in the app's surface pixels (render scale) */
		final float rs = renderScale;
		final int left = Math.round(displayLeft * rs), top = Math.round(displayTop * rs);
		final int right = Math.round(displayRight * rs), bottom = Math.round(displayBottom * rs);
		host.post(new Runnable() {
			@Override
			public void run() {
				for (int code : new int[] {TRANSACTION_onVisibleAreaChanged, TRANSACTION_onStableAreaChanged}) {
					IBinder cb = sender.callback();
					if (cb == null)
						return;
					Parcel data = Parcel.obtain();
					try {
						data.writeInterfaceToken(DESCRIPTOR);
						/* Rect as AOSP parcels it */
						data.writeInt(1);
						data.writeInt(left);
						data.writeInt(top);
						data.writeInt(right);
						data.writeInt(bottom);
						data.writeStrongBinder(sender.done(code == TRANSACTION_onVisibleAreaChanged ? "onVisibleAreaChanged" : "onStableAreaChanged"));
						cb.transact(code, data, null, IBinder.FLAG_ONEWAY);
					} catch (RemoteException | RuntimeException e) {
						Log.w(TAG, "visible area not delivered", e);
					} finally {
						data.recycle();
					}
				}
			}
		});
	}

	/** the device display's density */
	int displayDpi() {
		DisplayMetrics m = context.getResources().getDisplayMetrics();
		return m.densityDpi;
	}

	/** the car display's density: what the app's map is drawn at */
	int dpi() {
		return ATLCarHost.carDensityDpi(displayDpi(), mapScale);
	}

	float mapScale() {
		return mapScale;
	}

	/**
	 * The renderer's map scale (host thread, already within ATLCarHost's bounds). Returns whether
	 * the car display's density changed; if it did and the app has the surface, the app gets it
	 * again at the new density.
	 */
	boolean setMapScale(float scale) {
		int before = dpi();
		mapScale = scale;
		if (dpi() == before)
			return false;
		main.post(new Runnable() {
			@Override
			public void run() {
				if (!available || view == null)
					return;
				android.view.Surface surface = view.getHolder().getSurface();
				sendDestroyed(surface, width, height);
				sendAvailable(surface, width, height);
				if (visibleArea != null)
					setVisibleArea(visibleArea[0], visibleArea[1], visibleArea[2], visibleArea[3]);
			}
		});
		return true;
	}

	private void sendAvailable(final android.view.Surface surface, final int w, final int h) {
		final int dpi = dpi();
		final int displayDpi = displayDpi();
		/* the render scale: the surface's buffers (setFixedSize) and the app's density alike */
		final int sw = scaled(w), sh = scaled(h);
		final int appDpi = Math.max(1, Math.round(dpi * renderScale));
		if (view != null) {
			if (sw != w || sh != h)
				view.getHolder().setFixedSize(sw, sh);
			else
				view.getHolder().setSizeFromLayout();
		}
		givenDpi = appDpi;
		givenWidth = sw;
		givenHeight = sh;
		host.post(new Runnable() {
			@Override
			public void run() {
				sender.surfaceInfo(w, h, displayDpi, dpi);
				IBinder cb = sender.callback();
				if (cb == null)
					return;
				android.os.Parcelable container = sender.container(surface, sw, sh, appDpi);
				if (container == null)
					return;
				Parcel data = Parcel.obtain();
				try {
					data.writeInterfaceToken(DESCRIPTOR);
					data.writeTypedObject(container, 0);
					data.writeStrongBinder(sender.done("onSurfaceAvailable"));
					cb.transact(TRANSACTION_onSurfaceAvailable, data, null, IBinder.FLAG_ONEWAY);
					Log.i(TAG, "surface " + sw + "x" + sh + " at " + appDpi + " dpi given to the app (display " + w + "x" + h + ")");
				} catch (RemoteException | RuntimeException e) {
					Log.w(TAG, "onSurfaceAvailable not delivered", e);
				} finally {
					data.recycle();
				}
			}
		});
	}

	private void sendDestroyed(final android.view.Surface surface, int displayW, int displayH) {
		/* the container the app was given */
		final int dpi = givenDpi;
		final int w = givenWidth > 0 ? givenWidth : displayW, h = givenHeight > 0 ? givenHeight : displayH;
		host.post(new Runnable() {
			@Override
			public void run() {
				IBinder cb = sender.callback();
				if (cb == null)
					return;
				android.os.Parcelable container = sender.container(surface, w, h, dpi);
				if (container == null)
					return;
				Parcel data = Parcel.obtain();
				try {
					data.writeInterfaceToken(DESCRIPTOR);
					data.writeTypedObject(container, 0);
					data.writeStrongBinder(sender.done("onSurfaceDestroyed"));
					cb.transact(TRANSACTION_onSurfaceDestroyed, data, null, IBinder.FLAG_ONEWAY);
				} catch (RemoteException | RuntimeException e) {
					Log.w(TAG, "onSurfaceDestroyed not delivered", e);
				} finally {
					data.recycle();
				}
			}
		});
	}

	/* the gestures: one-way calls carrying floats */
	private void sendFloats(final int code, final float... values) {
		host.post(new Runnable() {
			@Override
			public void run() {
				IBinder cb = sender.callback();
				if (cb == null)
					return;
				Parcel data = Parcel.obtain();
				try {
					data.writeInterfaceToken(DESCRIPTOR);
					for (float v : values)
						data.writeFloat(v);
					cb.transact(code, data, null, IBinder.FLAG_ONEWAY);
				} catch (RemoteException | RuntimeException e) {
					Log.w(TAG, "gesture not delivered", e);
				} finally {
					data.recycle();
				}
			}
		});
	}

	private void installGestures(View v) {
		final ScaleGestureDetector scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
			@Override
			public boolean onScale(ScaleGestureDetector d) {
				sendFloats(TRANSACTION_onScale, d.getFocusX() * renderScale, d.getFocusY() * renderScale, d.getScaleFactor());
				return true;
			}
		});
		final GestureDetector gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
			@Override
			public boolean onDown(MotionEvent e) {
				return true;
			}

			@Override
			public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
				if (scale.isInProgress())
					return false;
				sendFloats(TRANSACTION_onScroll, dx * renderScale, dy * renderScale);
				return true;
			}

			@Override
			public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
				if (scale.isInProgress())
					return false;
				sendFloats(TRANSACTION_onFling, vx * renderScale, vy * renderScale);
				return true;
			}

			@Override
			public boolean onSingleTapConfirmed(MotionEvent e) {
				sendFloats(TRANSACTION_onClick, e.getX() * renderScale, e.getY() * renderScale);
				return true;
			}

			@Override
			public boolean onDoubleTap(MotionEvent e) {
				sendFloats(TRANSACTION_onScale, e.getX() * renderScale, e.getY() * renderScale, DOUBLE_TAP_ZOOM_FACTOR);
				return true;
			}
		});
		v.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View view, MotionEvent event) {
				scale.onTouchEvent(event);
				gestures.onTouchEvent(event);
				return true;
			}
		});
	}

	/* ---- SurfaceHolder.Callback (main thread) ---- */

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
		/* the display's size is the view's: with a render scale the surface's own is smaller */
		if (view != null) {
			int[] size = view.tlViewSize();
			if (size[0] > 0 && size[1] > 0) {
				w = size[0];
				h = size[1];
			}
		}
		if (available && w == width && h == height)
			return;
		width = w;
		height = h;
		available = true;
		sendAvailable(holder.getSurface(), w, h);
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		if (!available)
			return;
		available = false;
		sendDestroyed(holder.getSurface(), width, height);
	}
}
