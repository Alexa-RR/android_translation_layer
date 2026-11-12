package android.atl.carhost;

import android.atl.ATLActiveServices;
import android.atl.ATLLoadedApp;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.location.Location;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import java.lang.reflect.Method;

/**
 * A Car App Library host inside the runtime (ATL_CAR_HOST=1): the part of a car head unit's host
 * (Android Auto, an Android Automotive template host) that talks to an app's CarAppService, so
 * that apps built on androidx.car.app (navigation apps such as Waze) run their car UI here.
 *
 * <p>The host is part of the platform: its thread acts as {@link Process#SYSTEM_UID} (Binder's
 * calling identity), it presents itself as the framework package "android" (signed with ATL's
 * platform key), and the app's HostValidator accepts it as a system binding. After the app's first
 * activity is up, it binds the app's service (action androidx.car.app.CarAppService) and runs the
 * session the way a host does: getAppInfo, the handshake with the negotiated API level, onAppCreate
 * with this host's ICarHost, onAppStart, onAppResume, then the app's managers ("app",
 * "navigation"). It also publishes the car connection state ({@link ATLCarConnectionProvider}).
 *
 * <p>The host draws nothing itself: the app's map goes to a full-window surface
 * ({@link ATLCarSurface}), and its templates, toasts and alerts go as JSON to the car page
 * ({@link ATLCarAppChannel}, docs in teslalinux docs/UPSTREAM-NOTES.md), which draws them over the
 * map and sends the driver's touches back. The car itself ({@link ATLCarVehicle}) gives the
 * driving restrictions and night mode; the car display's density is the device's times the map
 * scale the renderer chooses ({@link #carDensityDpi}). Host services: "app", "navigation", "constraints". Every
 * call the app makes to them is logged (tag ATLCarHost).
 */
public final class ATLCarHost {
	static final String TAG = "ATLCarHost";

	/** CarAppApiLevels.LEVEL_9: the Car App Library API level of the interfaces this host implements */
	public static final int HOST_API_LEVEL = 9;
	/** the host's package: the framework (see PackageManager.getPackagesForUid(SYSTEM_UID)) */
	public static final String HOST_PACKAGE = "android";
	public static final String ACTION_CAR_APP_SERVICE = "androidx.car.app.CarAppService";
	/** the bounds of the map scale a renderer can choose (the car display's density over the device's) */
	public static final float MAP_SCALE_MIN = 0.5f;
	public static final float MAP_SCALE_MAX = 2f;

	/**
	 * The car display's density: the device display's density times the map scale (within
	 * MAP_SCALE_MIN..MAX; anything else counts as 1). Apps get it as the SurfaceContainer's dpi and
	 * the CarContext configuration's densityDpi, and size their map's text and symbols by it.
	 */
	public static int carDensityDpi(int displayDpi, float mapScale) {
		if (!(mapScale >= MAP_SCALE_MIN && mapScale <= MAP_SCALE_MAX))
			mapScale = 1f;
		return Math.max(1, Math.round(displayDpi * mapScale));
	}

	/**
	 * The configuration a car display gives a car app's CarContext (ICarApp.onAppCreate,
	 * onConfigurationChanged): the device's, with the car's density, its size in dp at that density
	 * (the display's widthPx x heightPx, rounded as a display context computes it) and its night mode
	 * (CarContext.isDarkMode reads Configuration.UI_MODE_NIGHT_YES).
	 */
	public static Configuration carConfiguration(Configuration device, int densityDpi, int widthPx, int heightPx, boolean night) {
		Configuration config = new Configuration(device);
		config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
		if (densityDpi > 0) {
			config.densityDpi = densityDpi;
			if (widthPx > 0 && heightPx > 0) {
				float density = densityDpi * android.util.DisplayMetrics.DENSITY_DEFAULT_SCALE;
				config.screenWidthDp = (int)(widthPx / density + 0.5f);
				config.screenHeightDp = (int)(heightPx / density + 0.5f);
				config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
			}
		}
		return config;
	}

	private static final Object sLock = new Object();
	private static ATLCarHost sInstance;
	private static volatile int sConnectionType = ATLCarConnectionProvider.CONNECTION_TYPE_NOT_CONNECTED;

	private final ATLLoadedApp app;
	private final ClassLoader appClassLoader;
	private Handler handler;

	/* host thread */
	private CarHost carHost;
	private AppHost appHost;
	private NavigationHost navigationHost;
	private ServiceConnection connection;
	private ComponentName serviceName;
	private ICarApp carApp;
	private IAppManager appManager;
	private INavigationManager navigationManager;
	private IBinder surfaceCallback;
	private int apiLevel;
	private boolean templatePending;
	private boolean templateAgain;

	private ATLCarHost(ATLLoadedApp app) {
		this.app = app;
		this.appClassLoader = app.class_loader;
	}

	/** whether the car host is enabled (ATL_CAR_HOST=1) */
	public static boolean isEnabled() {
		return "1".equals(System.getenv("ATL_CAR_HOST"));
	}

	/** CarConnection's connection type as this host reports it */
	public static int getConnectionType() {
		return sConnectionType;
	}

	/** Activity.performResume: the first resumed activity starts the host when it is enabled */
	public static void onActivityResumed() {
		onProcessStarted();
	}

	/**
	 * The app's process is up (an activity resumed, or the app was started without one for its
	 * services, Application.tlStartedWithoutActivity): the host, when enabled, binds the app's car
	 * app service, once.
	 */
	public static void onProcessStarted() {
		if (!isEnabled())
			return;
		ATLCarHost host;
		synchronized (sLock) {
			if (sInstance != null)
				return;
			host = sInstance = new ATLCarHost(ATLLoadedApp.getPrimaryApplication());
		}
		host.start();
	}

	private void start() {
		HandlerThread thread = new HandlerThread("ATLCarHost");
		thread.start();
		handler = new Handler(thread.getLooper());
		/* the host thread is the platform's: binders made here are the platform's, calls from here
		 * reach the app with the system uid as the calling identity */
		handler.post(new Runnable() {
			@Override
			public void run() {
				Binder.setThreadActingUid(Process.SYSTEM_UID);
				carHost = new CarHost();
				appHost = new AppHost();
				navigationHost = new NavigationHost();
				constraintHost = new ConstraintHost();
				channel = new ATLCarAppChannel(ATLCarAppChannel.configuredPort(), handler, new ATLCarAppChannel.Listener() {
					@Override
					public void onMessage(String json) {
						onRendererMessage(json);
					}
				});
				channel.start();
				vehicle = new ATLCarVehicle(handler, new ATLCarVehicle.Listener() {
					@Override
					public void onRestrictionsChanged(android.car.drivingstate.CarUxRestrictions r) {
						sendRestrictions(r);
					}

					@Override
					public void onNightChanged(boolean night) {
						channel.sendDayNight(night);
						log("car night mode: " + (night ? "night" : "day"));
						configurationChanged();
					}
				});
				vehicle.start(app.getApplication());
				sendRestrictions(vehicle.restrictions());
				channel.sendDayNight(vehicle.isNight());
				carSurface = new ATLCarSurface(app.getApplication(), handler, new ATLCarSurface.Sender() {
					@Override
					public IBinder callback() {
						return surfaceCallback;
					}

					@Override
					public android.os.Parcelable container(android.view.Surface s, int width, int height, int dpi) {
						try {
							Object c = Class.forName("androidx.car.app.SurfaceContainer", true, appClassLoader)
							    .getConstructor(android.view.Surface.class, int.class, int.class, int.class)
							    .newInstance(s, width, height, dpi);
							return Bundleable.create(c, appClassLoader);
						} catch (ReflectiveOperationException | RuntimeException e) {
							Log.e(TAG, "no SurfaceContainer for the app", e);
							return null;
						}
					}

					@Override
					public IBinder done(String what) {
						return new Done(what) {
							@Override
							void success(Object response) {
							}
						};
					}

					@Override
					public void surfaceInfo(int width, int height, int dpi, int mapDpi) {
						channel.sendSurface(width, height, dpi, mapDpi);
					}
				});
				/* the device's density until a renderer chooses a map scale */
				channel.sendMapScale(carSurface.mapScale(), false);
				carSurface.show();
			}
		});
		long delay = 0;
		try {
			String d = System.getenv("ATL_CAR_HOST_DELAY_MS");
			if (d != null)
				delay = Long.parseLong(d);
		} catch (NumberFormatException e) {
			Log.w(TAG, "ATL_CAR_HOST_DELAY_MS is not a number: " + System.getenv("ATL_CAR_HOST_DELAY_MS"));
		}
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				connect();
			}
		}, delay);
	}

	/* ---- logging ---- */

	static void log(String msg) {
		/* long template dumps in pieces the log keeps */
		final int max = 3000;
		if (msg.length() <= max) {
			Log.i(TAG, msg);
			return;
		}
		int parts = (msg.length() + max - 1) / max;
		for (int i = 0; i < parts; i++)
			Log.i(TAG, "[" + (i + 1) + "/" + parts + "] " + msg.substring(i * max, Math.min(msg.length(), (i + 1) * max)));
	}

	private String describe(Bundleable b) {
		if (b == null)
			return "null";
		try {
			return CarModelDump.describe(b.get(appClassLoader));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "<undecodable: " + e + ">";
		}
	}

	/* ---- the session ---- */

	private void setConnectionType(final int type) {
		if (sConnectionType == type)
			return;
		sConnectionType = type;
		log("car connection: " + (type == ATLCarConnectionProvider.CONNECTION_TYPE_PROJECTION ? "projection" : "not connected"));
		/* CarConnection queries the provider again on this broadcast */
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				app.getApplication().sendBroadcast(new Intent(ATLCarConnectionProvider.ACTION_CAR_CONNECTION_UPDATED));
			}
		});
	}

	private void connect() {
		Intent intent = new Intent(ACTION_CAR_APP_SERVICE);
		intent.setPackage(app.pkg.packageName);
		connection = new ServiceConnection() {
			@Override
			public void onServiceConnected(final ComponentName name, final IBinder service) {
				handler.post(new Runnable() {
					@Override
					public void run() {
						onConnected(name, service);
					}
				});
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				log("service disconnected: " + name);
			}

			@Override
			public void onBindingDied(ComponentName name) {
				log("binding died: " + name);
			}

			@Override
			public void onNullBinding(ComponentName name) {
				log("service " + name + " returned no binder");
			}
		};
		setConnectionType(ATLCarConnectionProvider.CONNECTION_TYPE_PROJECTION);
		if (!ATLActiveServices.bindService(app, intent, connection, Context.BIND_AUTO_CREATE)) {
			log(app.pkg.packageName + " has no car app service (" + ACTION_CAR_APP_SERVICE + ")");
			connection = null;
			setConnectionType(ATLCarConnectionProvider.CONNECTION_TYPE_NOT_CONNECTED);
			return;
		}
		log("binding " + app.pkg.packageName + " for " + ACTION_CAR_APP_SERVICE + " as " + HOST_PACKAGE + " (uid " + Process.SYSTEM_UID + ")");
	}

	private void onConnected(ComponentName name, IBinder service) {
		serviceName = name;
		carApp = ICarApp.Stub.asInterface(service);
		log("connected to " + name.flattenToShortString());
		try {
			carApp.getAppInfo(new Done("getAppInfo") {
				@Override
				void success(Object appInfo) {
					handshake(appInfo);
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "getAppInfo failed", e);
		}
	}

	private static int intGetter(Object o, String getter) throws ReflectiveOperationException {
		Method m = o.getClass().getMethod(getter);
		return ((Number)m.invoke(o)).intValue();
	}

	private void handshake(Object appInfo) {
		int minLevel, latestLevel;
		try {
			minLevel = intGetter(appInfo, "getMinCarAppApiLevel");
			latestLevel = intGetter(appInfo, "getLatestCarAppApiLevel");
		} catch (ReflectiveOperationException | RuntimeException e) {
			Log.e(TAG, "unreadable AppInfo " + appInfo, e);
			return;
		}
		apiLevel = Math.min(HOST_API_LEVEL, latestLevel);
		if (apiLevel < minLevel) {
			log("the app needs Car App API level " + minLevel + ", this host supports up to " + HOST_API_LEVEL);
			return;
		}
		Bundleable handshakeInfo;
		try {
			Object info = Class.forName("androidx.car.app.HandshakeInfo", true, appClassLoader)
			                  .getConstructor(String.class, int.class)
			                  .newInstance(HOST_PACKAGE, apiLevel);
			handshakeInfo = Bundleable.create(info, appClassLoader);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Log.e(TAG, "cannot build the HandshakeInfo with the app's library", e);
			return;
		}
		log("handshake: host " + HOST_PACKAGE + ", API level " + apiLevel + " (app " + minLevel + ".." + latestLevel + ")");
		try {
			carApp.onHandshakeCompleted(handshakeInfo, new Done("onHandshakeCompleted") {
				@Override
				void success(Object response) {
					log("handshake accepted: the app validated this host (" + HOST_PACKAGE + ", uid " + Process.SYSTEM_UID + ")");
					create();
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "onHandshakeCompleted failed", e);
		}
	}

	private void create() {
		Intent intent = new Intent();
		intent.setComponent(serviceName);
		try {
			carApp.onAppCreate(carHost, intent, carConfiguration(), new Done("onAppCreate") {
				@Override
				void success(Object response) {
					created = true;
					lifecycle();
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "onAppCreate failed", e);
		}
	}

	/* the car's configuration for the app's CarContext: the device's, with the car display's
	 * density (the map scale's) and night mode */
	private Configuration carConfiguration() {
		android.content.res.Resources res = app.getApplication().getResources();
		android.util.DisplayMetrics m = res.getDisplayMetrics();
		int dpi = carSurface != null ? carSurface.dpi() : m.densityDpi;
		return carConfiguration(res.getConfiguration(), dpi, m.widthPixels, m.heightPixels, vehicle != null && vehicle.isNight());
	}

	private boolean created;

	/* host thread: the driver chose a map scale on the renderer: the car display's density changes,
	 * the app hears onConfigurationChanged and gets its surface again at the new density; every
	 * renderer hears the scale */
	private void setMapScale(float scale) {
		if (carSurface == null)
			return;
		if (!(scale >= MAP_SCALE_MIN && scale <= MAP_SCALE_MAX)) {
			Log.w(TAG, "renderer: map scale " + scale + " outside " + MAP_SCALE_MIN + ".." + MAP_SCALE_MAX);
			return;
		}
		boolean changed = carSurface.setMapScale(scale);
		channel.sendMapScale(scale, true);
		if (!changed)
			return;
		log("map scale " + scale + ": the car display's density is " + carSurface.dpi() + " dpi");
		configurationChanged();
	}

	/* host thread: the car's configuration changed (night mode, density): the app hears
	 * onConfigurationChanged */
	private void configurationChanged() {
		if (carApp == null || !created)
			return;
		try {
			carApp.onConfigurationChanged(carConfiguration(), new Done("onConfigurationChanged") {
				@Override
				void success(Object response) {
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "onConfigurationChanged failed", e);
		}
	}

	/* host thread: the car's UX restrictions to the renderer, which limits input and lists by them */
	private void sendRestrictions(android.car.drivingstate.CarUxRestrictions r) {
		if (channel == null)
			return;
		int active = r.getActiveRestrictions();
		boolean limitContent = (active & android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT) != 0;
		boolean limitString = (active & android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH) != 0;
		log("car UX restrictions: 0x" + Integer.toHexString(active));
		channel.sendRestrictions(r.isRequiresDistractionOptimization(),
		                         (active & android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD) != 0,
		                         limitContent ? r.getMaxCumulativeContentItems() : 0,
		                         limitString ? r.getMaxRestrictedStringLength() : 0);
	}

	private ATLCarVehicle vehicle;

	private void lifecycle() {
		try {
			carApp.onAppStart(new Done("onAppStart") {
				@Override
				void success(Object response) {
					try {
						carApp.onAppResume(new Done("onAppResume") {
							@Override
							void success(Object response) {
								managers();
							}
						});
					} catch (RemoteException | RuntimeException e) {
						Log.e(TAG, "onAppResume failed", e);
					}
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "onAppStart failed", e);
		}
	}

	private void managers() {
		try {
			carApp.getManager("navigation", new Done("getManager(navigation)") {
				@Override
				void success(Object manager) {
					IBinder binder = binderOf(manager);
					navigationManager = binder != null ? INavigationManager.Stub.asInterface(binder) : null;
					if (navigationManager == null)
						log("the app returned no navigation manager binder: " + (manager == null ? "null" : manager.getClass().getName()));
				}
			});
			carApp.getManager("app", new Done("getManager(app)") {
				@Override
				void success(Object manager) {
					IBinder binder = binderOf(manager);
					if (binder == null) {
						log("the app returned no app manager");
						return;
					}
					appManager = IAppManager.Stub.asInterface(binder);
					/* the host shows the app's first screen */
					fetchTemplate();
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "getManager failed", e);
		}
	}

	private static IBinder binderOf(Object manager) {
		if (manager instanceof android.os.IInterface)
			return ((android.os.IInterface)manager).asBinder();
		if (manager instanceof IBinder)
			return (IBinder)manager;
		return null;
	}

	/* ---- the renderer (the car page, through ATLCarAppChannel) ---- */

	private ATLCarAppChannel channel;
	private ATLCarSurface carSurface;
	private final CarModelJson modelJson = new CarModelJson();
	private int templateSeq;
	/**
	 * Drawable icons (vectors, resources) are rendered with at least this many pixels on their
	 * longer side, enough for the largest place a template shows one (a maneuver, a large row
	 * image) on a high-density car screen; bitmaps go at their own size.
	 */
	private static final int ICON_MIN_PX = 256;
	private static final int ICON_MAX_PX = 1024;

	/* host thread: the template as JSON, its new icons first */
	private void publishTemplate(Object wrapper) {
		if (channel == null)
			return;
		modelJson.reset();
		String json;
		try {
			json = modelJson.toJson(wrapper);
		} catch (RuntimeException e) {
			Log.e(TAG, "template not representable as JSON", e);
			return;
		}
		sendAppIcon();
		for (java.util.Map.Entry<Integer, Object> e : new java.util.ArrayList<>(modelJson.icons.entrySet())) {
			if (channel.hasIcon(e.getKey()))
				continue;
			byte[] png = renderIcon(e.getValue());
			if (png != null)
				channel.sendIcon(e.getKey(), png);
		}
		channel.sendTemplate(++templateSeq, json);
	}

	/* an IconCompat as PNG: the app's drawable (IconCompat.loadDrawable with the app's context),
	 * drawn at ICON_MIN_PX or more keeping its aspect ratio; tints are applied by the renderer (CarIcon.tint) */
	private byte[] renderIcon(Object iconCompat) {
		/* a bitmap icon (maneuvers, lanes, junctions): its own pixels, not scaled */
		android.graphics.Bitmap bitmap = CarModelJson.bitmapOf(iconCompat);
		if (bitmap != null && !bitmap.isRecycled() && Math.max(bitmap.getWidth(), bitmap.getHeight()) <= ICON_MAX_PX) {
			try {
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
				return out.toByteArray();
			} catch (java.io.IOException | RuntimeException e) {
				Log.w(TAG, "bitmap icon not encodable", e);
			}
		}
		try {
			Object d = iconCompat.getClass().getMethod("loadDrawable", android.content.Context.class).invoke(iconCompat, app.getApplication());
			if (!(d instanceof android.graphics.drawable.Drawable))
				return null;
			return renderDrawable((android.graphics.drawable.Drawable)d);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Log.w(TAG, "icon not loadable: " + CarModelDump.describe(iconCompat), e);
			return null;
		}
	}

	/* the app's launcher icon: Action.APP_ICON in a template is drawn with it (icon id 0) */
	private void sendAppIcon() {
		if (channel.hasIcon(0) || appIconFailed)
			return;
		try {
			byte[] png = renderDrawable(app.getApplication().getPackageManager().getApplicationIcon(app.pkg.applicationInfo));
			if (png != null)
				channel.sendIcon(0, png);
			else
				appIconFailed = true;
		} catch (RuntimeException e) {
			/* once: the icon does not change while the app runs */
			appIconFailed = true;
			Log.w(TAG, "app icon not renderable", e);
		}
	}

	private boolean appIconFailed;

	private byte[] renderDrawable(android.graphics.drawable.Drawable drawable) {
		try {
			int w = drawable.getIntrinsicWidth(), h = drawable.getIntrinsicHeight();
			if (w <= 0 || h <= 0) {
				w = ICON_MIN_PX;
				h = ICON_MIN_PX;
			} else {
				int longer = Math.max(w, h);
				int target = Math.min(ICON_MAX_PX, Math.max(ICON_MIN_PX, longer));
				float scale = (float)target / longer;
				w = Math.max(1, Math.round(w * scale));
				h = Math.max(1, Math.round(h * scale));
			}
			android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
			android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
			drawable.setBounds(0, 0, w, h);
			drawable.draw(canvas);
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
			bitmap.recycle();
			return out.toByteArray();
		} catch (java.io.IOException | RuntimeException e) {
			Log.w(TAG, "icon not renderable", e);
			return null;
		}
	}

	/* host thread: an interaction from the renderer, delivered to the app's callback for it */
	private void onRendererMessage(String text) {
		try {
			org.json.JSONObject m = new org.json.JSONObject(text);
			String type = m.getString("type");
			if ("visibleArea".equals(type)) {
				if (carSurface != null)
					carSurface.setVisibleArea(m.getInt("left"), m.getInt("top"), m.getInt("right"), m.getInt("bottom"));
				return;
			}
			if ("dayNight".equals(type)) {
				/* the driver's choice on the car page: "day", "night" or "auto" (the sun) */
				if (vehicle != null)
					vehicle.setMode(m.optString("mode", "auto"));
				return;
			}
			if ("mapScale".equals(type)) {
				/* the driver's map size on the car page: a factor on the display's density */
				setMapScale((float)m.getDouble("scale"));
				return;
			}
			if ("stopNavigation".equals(type)) {
				stopNavigation();
				return;
			}
			if ("back".equals(type)) {
				if (appManager != null)
					appManager.onBackPressed(new Done("onBackPressed") {
						@Override
						void success(Object response) {
						}
					});
				return;
			}
			Object delegate = modelJson.delegate(m.optInt("cb", -1));
			if (delegate == null) {
				Log.w(TAG, "renderer: no callback " + m.opt("cb") + " in the current template (" + type + ")");
				return;
			}
			switch (type) {
				case "click":
					invokeDelegate(delegate, "sendClick");
					break;
				case "selected":
					invokeDelegate(delegate, "sendSelected", m.getInt("index"));
					break;
				case "checked":
					invokeDelegate(delegate, "sendCheckedChange", m.getBoolean("checked"));
					break;
				case "search":
					invokeDelegate(delegate, m.optBoolean("submit") ? "sendSearchSubmitted" : "sendSearchTextChanged", m.getString("text"));
					break;
				case "alertCancel":
					/* the alert timed out (REASON_TIMEOUT 1) or the driver closed it (REASON_USER_ACTION 2) */
					invokeDelegate(delegate, "sendCancel", m.getInt("reason"));
					break;
				case "alertDismiss":
					/* the alert went away after one of its actions (AlertCallback.onDismiss) */
					invokeDelegate(delegate, "sendDismiss");
					break;
				case "pan":
					/* the host's pan mode (map action strip PAN action), told to the app's PanModeDelegate */
					invokeDelegate(delegate, "sendPanModeChanged", m.getBoolean("on"));
					break;
				default:
					Log.w(TAG, "renderer: unknown message type " + type);
			}
		} catch (org.json.JSONException | RemoteException | RuntimeException e) {
			Log.w(TAG, "renderer message not handled: " + text, e);
		}
	}

	/**
	 * Calls the delegate's send method (the library's OnClickDelegate.sendClick(OnDoneCallback),
	 * OnSelectedDelegate.sendSelected(int, OnDoneCallback), ...) with the arguments and a done
	 * callback of the app's library: the delegate sends the call to the app's listener and
	 * reports back through it.
	 */
	private void invokeDelegate(Object delegate, String name, Object... args) {
		ClassLoader loader = delegate.getClass().getClassLoader();
		final Class<?> doneClass;
		try {
			doneClass = Class.forName("androidx.car.app.OnDoneCallback", false, loader);
		} catch (ClassNotFoundException e) {
			Log.e(TAG, "the app has no OnDoneCallback", e);
			return;
		}
		Object done = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {doneClass}, new java.lang.reflect.InvocationHandler() {
			@Override
			public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] a) {
				if ("onFailure".equals(method.getName()))
					Log.w(TAG, "the app reported a failure for " + name);
				if (method.getDeclaringClass() == Object.class) {
					switch (method.getName()) {
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == a[0];
						default:
							return "OnDoneCallback(" + name + ")";
					}
				}
				return null;
			}
		});
		for (java.lang.reflect.Method m : delegate.getClass().getMethods()) {
			if (!m.getName().equals(name) || m.getParameterTypes().length != args.length + 1)
				continue;
			Object[] full = java.util.Arrays.copyOf(args, args.length + 1);
			full[args.length] = done;
			try {
				m.invoke(delegate, full);
			} catch (ReflectiveOperationException e) {
				Log.e(TAG, name + " failed", e);
			}
			return;
		}
		Log.w(TAG, delegate.getClass().getName() + " has no " + name);
	}

	/* whether the app reported navigation as active (NavigationManager.navigationStarted/Ended):
	 * while it is, the renderer offers the host's stop control */
	private volatile boolean navigating;

	private void setNavigating(final boolean active) {
		navigating = active;
		handler.post(new Runnable() {
			@Override
			public void run() {
				if (channel != null)
					channel.sendNavigation(active);
			}
		});
	}

	/* host thread: the driver stopped navigation with the host's control: the app hears
	 * NavigationManagerCallback.onStopNavigation (INavigationManager.onStopNavigation) */
	private void stopNavigation() {
		if (navigationManager == null || !navigating) {
			log("stop navigation: " + (navigationManager == null ? "no navigation manager" : "the app is not navigating"));
			return;
		}
		log("stop navigation: INavigationManager.onStopNavigation");
		try {
			navigationManager.onStopNavigation(new Done("onStopNavigation") {
				@Override
				void success(Object response) {
					/* the library ends its navigation state when the host stops it
					 * (NavigationManager.onStopNavigation), so the app's own navigationEnded()
					 * that follows is not sent to the host: navigation ends here */
					if (navigating)
						setNavigating(false);
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "onStopNavigation failed", e);
		}
	}

	/* host thread: an alert (car API 5) shown by the renderer over the template, its icons first;
	 * its actions and its AlertCallbackDelegate stay reachable until the next alert */
	private void showAlertOnRenderer(Bundleable alert) {
		if (channel == null || alert == null)
			return;
		Object model;
		try {
			model = alert.get(appClassLoader);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Log.e(TAG, "alert not readable", e);
			return;
		}
		modelJson.releaseKept();
		String json = modelJson.toJsonKept(model);
		for (java.util.Map.Entry<Integer, Object> e : new java.util.ArrayList<>(modelJson.icons.entrySet())) {
			if (channel.hasIcon(e.getKey()))
				continue;
			byte[] png = renderIcon(e.getValue());
			if (png != null)
				channel.sendIcon(e.getKey(), png);
		}
		channel.sendEvent("{\"type\":\"alert\",\"alert\":" + json + "}");
	}

	/* one getTemplate at a time; an invalidate meanwhile asks again once it is answered */
	private void fetchTemplate() {
		if (appManager == null)
			return;
		if (templatePending) {
			templateAgain = true;
			return;
		}
		templatePending = true;
		try {
			appManager.getTemplate(new Done("getTemplate") {
				@Override
				void success(Object wrapper) {
					log("template: " + CarModelDump.describe(wrapper));
					publishTemplate(wrapper);
					templateDone();
				}

				@Override
				void failure() {
					templateDone();
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "getTemplate failed", e);
			templatePending = false;
		}
	}

	private void templateDone() {
		templatePending = false;
		if (templateAgain) {
			templateAgain = false;
			fetchTemplate();
		}
	}

	private void finishSession() {
		if (connection != null) {
			ATLActiveServices.unbindService(connection);
			connection = null;
		}
		carApp = null;
		appManager = null;
		navigationManager = null;
		setConnectionType(ATLCarConnectionProvider.CONNECTION_TYPE_NOT_CONNECTED);
	}

	/* ---- callbacks and host services (platform binders, created on the host thread) ---- */

	/** an IOnDoneCallback whose result is handled on the host thread */
	private abstract class Done extends IOnDoneCallback.Stub {
		private final String what;

		Done(String what) {
			this.what = what;
		}

		abstract void success(Object response);

		void failure() {
		}

		@Override
		public void onSuccess(final Bundleable response) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					Object value = null;
					if (response != null) {
						try {
							value = response.get(appClassLoader);
						} catch (ReflectiveOperationException | RuntimeException e) {
							Log.e(TAG, what + ": undecodable response", e);
							failure();
							return;
						}
					}
					success(value);
				}
			});
		}

		@Override
		public void onFailure(final Bundleable failureResponse) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					log(what + " failed: " + describe(failureResponse));
					failure();
				}
			});
		}
	}

	/** ICarHost: the host services by type */
	private final class CarHost extends ICarHost.Stub {
		@Override
		public void startCarApp(Intent intent) {
			log("ICarHost.startCarApp(" + intent + ")");
		}

		@Override
		public IBinder getHost(String type) {
			if ("app".equals(type))
				return appHost;
			if ("navigation".equals(type))
				return navigationHost;
			if ("constraints".equals(type))
				return constraintHost;
			/* "car" (hardware), "suggestion", "media_playback": services this host does not offer;
			 * the library treats a null host as an unavailable service */
			log("ICarHost.getHost(" + type + "): not offered by this host");
			return null;
		}

		@Override
		public void finish() {
			log("ICarHost.finish()");
			handler.post(new Runnable() {
				@Override
				public void run() {
					finishSession();
				}
			});
		}
	}

	/** IAppHost ("app") */
	private final class AppHost extends IAppHost.Stub {
		@Override
		public void invalidate() {
			log("IAppHost.invalidate()");
			handler.post(new Runnable() {
				@Override
				public void run() {
					fetchTemplate();
				}
			});
		}

		@Override
		public void showToast(final CharSequence text, final int duration) {
			log("IAppHost.showToast(\"" + text + "\", " + (duration == 1 ? "LENGTH_LONG" : "LENGTH_SHORT") + ")");
			handler.post(new Runnable() {
				@Override
				public void run() {
					if (channel == null || text == null)
						return;
					StringBuilder sb = new StringBuilder("{\"type\":\"toast\",\"long\":").append(duration == 1).append(",\"text\":");
					CarModelJson.quote(sb, text.toString());
					channel.sendEvent(sb.append('}').toString());
				}
			});
		}

		@Override
		public void setSurfaceCallback(final IBinder callback) {
			log("IAppHost.setSurfaceCallback(" + (callback != null ? "<ISurfaceCallback>" : "null") + ")");
			handler.post(new Runnable() {
				@Override
				public void run() {
					surfaceCallback = callback;
					if (carSurface != null && callback != null)
						carSurface.onCallbackChanged();
				}
			});
		}

		@Override
		public void sendLocation(Location location) {
			log("IAppHost.sendLocation(" + location + ")");
		}

		@Override
		public void showAlert(final Bundleable alert) {
			log("IAppHost.showAlert(" + describe(alert) + ")");
			handler.post(new Runnable() {
				@Override
				public void run() {
					showAlertOnRenderer(alert);
				}
			});
		}

		@Override
		public void dismissAlert(final int alertId) {
			log("IAppHost.dismissAlert(" + alertId + ")");
			handler.post(new Runnable() {
				@Override
				public void run() {
					if (channel != null)
						channel.sendEvent("{\"type\":\"alertDismiss\",\"id\":" + alertId + "}");
				}
			});
		}

		@Override
		public Bundleable openMicrophone(Bundleable request) {
			/* this host has no microphone input for the app: no OpenMicrophoneResponse, as a host
			 * without that capability answers */
			log("IAppHost.openMicrophone(" + describe(request) + "): no microphone in this host");
			return null;
		}
	}

	/**
	 * IConstraintHost ("constraints"): the AOSP Templates Host's content limits (its
	 * res/values integers content_limit_*): how many items the app's lists, grids, place lists,
	 * route lists and panes may hold. While the car moves the renderer shows no more than these.
	 */
	private final class ConstraintHost extends IConstraintHost.Stub {
		@Override
		public int getContentLimit(int contentLimitType) {
			switch (contentLimitType) {
				case 1: /* CONTENT_LIMIT_TYPE_GRID */
					return 6;
				case 2: /* CONTENT_LIMIT_TYPE_PLACE_LIST */
					return 6;
				case 3: /* CONTENT_LIMIT_TYPE_ROUTE_LIST */
					return 3;
				case 4: /* CONTENT_LIMIT_TYPE_PANE */
					return 4;
				case 0: /* CONTENT_LIMIT_TYPE_LIST */
				default:
					return 6;
			}
		}

		@Override
		public boolean isAppDrivenRefreshEnabled() {
			/* this host fetches the template on every invalidate the app sends, without needing a
			 * driver interaction first */
			return true;
		}
	}

	private ConstraintHost constraintHost;

	/** INavigationHost ("navigation") */
	private final class NavigationHost extends INavigationHost.Stub {
		@Override
		public void navigationStarted() {
			log("INavigationHost.navigationStarted()");
			setNavigating(true);
		}

		@Override
		public void navigationEnded() {
			log("INavigationHost.navigationEnded()");
			setNavigating(false);
		}

		@Override
		public void updateTrip(Bundleable trip) {
			log("INavigationHost.updateTrip(" + describe(trip) + ")");
		}

		@Override
		public void setVoiceAssistantCapabilities(Bundleable capabilities) {
			log("INavigationHost.setVoiceAssistantCapabilities(" + describe(capabilities) + ")");
		}
	}
}
