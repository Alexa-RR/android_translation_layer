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
	/* the app's latest speedometer reading (ATLCarSpeedometer), and whether the host thread has
	 * one to send queued already (under sLock) */
	private static ATLCarSpeedometer sSpeedometer;
	private static boolean sSpeedometerQueued;
	private static volatile int sConnectionType = ATLCarConnectionProvider.CONNECTION_TYPE_NOT_CONNECTED;

	private final ATLLoadedApp app;
	private final ClassLoader appClassLoader;
	private volatile Handler handler;

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
					public void onMessage(Object renderer, String json) {
						onRendererMessage(renderer, json);
					}

					@Override
					public void onRendererGone(Object renderer) {
						if (renderer == settingsOwner) {
							log("the renderer showing the app's settings is gone: back to the map");
							if (channel.holds(renderer))
								goneHolder = renderer;
							abandonAppSettings();
						} else {
							/* a holder whose screens are not the host's to pop (none above the base) */
							channel.releaseHoldOf(renderer);
						}
					}
				});
				channel.start();
				/* a reading the app made before the channel was there */
				sendSpeedometer();
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
				/* the names of the app's resource icons in the templates (CarModelJson.resources) */
				modelJson.resources = app.getApplication().getResources();
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

	/**
	 * A speedometer reading the app made (the ATLCarSpeedometer constructor, on whatever thread the
	 * app makes it; Waze's location thread): the host sends the latest to the renderers from its
	 * own thread, so the app's thread never waits on a socket. Readings that arrive while one is
	 * queued replace it. Without the car host (ATL_CAR_HOST unset) readings go nowhere.
	 */
	static void onSpeedometer(ATLCarSpeedometer reading) {
		if (!isEnabled())
			return;
		ATLCarHost host;
		synchronized (sLock) {
			sSpeedometer = reading;
			host = sInstance;
			if (host == null || host.handler == null || sSpeedometerQueued)
				return; /* the host sends it when its channel starts, or with the queued one */
			sSpeedometerQueued = true;
		}
		host.handler.post(new Runnable() {
			@Override
			public void run() {
				host.sendSpeedometer();
			}
		});
	}

	/* host thread: the latest reading to the renderers (the channel leaves out a repeat) */
	private void sendSpeedometer() {
		ATLCarSpeedometer reading;
		synchronized (sLock) {
			sSpeedometerQueued = false;
			reading = sSpeedometer;
		}
		if (reading != null && channel != null)
			channel.sendSpeedometer(reading.toJson());
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
		String mode = vehicle != null ? vehicle.restrictionMode() : ATLCarVehicle.MODE_BASELINE;
		log("car UX restrictions: 0x" + Integer.toHexString(active) + " (mode " + mode + ")");
		channel.sendRestrictions(r.isRequiresDistractionOptimization(),
		                         (active & android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD) != 0,
		                         limitContent ? r.getMaxCumulativeContentItems() : 0,
		                         limitString ? r.getMaxRestrictedStringLength() : 0, mode);
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
	private String lastTemplateJson;
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
		/* a template that differs from the one shown: a new screen, a route, a panel, which moves
		 * the app's map (a route preview zooms to the route) */
		if (!json.equals(lastTemplateJson))
			android.atl.ATLFrameRateLimiter.contentMoved("car template");
		lastTemplateJson = json;
		channel.sendTemplate(++templateSeq, json);
		if (settingsOwner != null || settingsClosing)
			appSettingsTemplate(stackIds(json));
	}

	/* ---- the app's own settings shown by a renderer in its own page ------------------------------
	 * A renderer may show the app's settings screens in a page of its own instead of over the map
	 * (teslalinux's car page: Settings > Waze > Waze's settings): it taps the app's settings action,
	 * draws the screens the app pushes for it, and when it is done pops them with back until the
	 * app's screen stack is the map's again. It tells the host with {"type":"appSettings","state":S}:
	 * "open" right before it taps the action, "closed" once it is back at the map, "abandon" when it
	 * goes away without popping them (the page being closed). A renderer that goes away while it
	 * shows them (its socket closed, or silent: ATLCarAppChannel) abandons them too. Abandoned
	 * settings would stay on the car's map, where no renderer offers a way out of them, so the host
	 * pops them itself as the renderer would: back (AppManager.onBackPressed, which the app's
	 * ScreenManager answers with pop), one at a time, each after the template answering the one
	 * before, until the stack is the one the settings were opened from. Screens are told apart by
	 * the stack's template ids (TemplateWrapper.templateInfosForScreenStack, top first), as the
	 * renderer does: a screen keeps its id while screens lie above it. */

	/** the settings screen arrives within this after the tap, or it did not open */
	private static final long SETTINGS_OPEN_MS = 5000;
	/** one back answered with a template within this, or the app kept its screen */
	private static final long SETTINGS_STEP_MS = 3000;
	/** never more backs than this for one closing */
	private static final int SETTINGS_MAX_BACKS = 12;

	/* the renderer showing the app's settings (its channel identity), or null */
	private Object settingsOwner;
	/* the app's screen stack (template ids, top first) the settings were opened from */
	private java.util.List<String> settingsBase = java.util.Collections.emptyList();
	/* the app's current screen stack, while settings are open or closing */
	private java.util.List<String> settingsStack = java.util.Collections.emptyList();
	/* whether a screen above the base was seen since they were opened */
	private boolean settingsSeen;
	/* the host is popping them (abandoned), a back on its way, how many were sent, and until when
	 * (uptimeMillis) a settings screen still on its way after the tap is waited for */
	private boolean settingsClosing;
	private boolean settingsBackPending;
	private int settingsBacks;
	private long settingsWaitUntil;
	private final Runnable settingsStepTimeout = new Runnable() {
		@Override
		public void run() {
			if (!settingsClosing)
				return;
			if (settingsBackPending) {
				/* the app kept its screen (a question of its own): it stays, where the driver sees it */
				log("app settings: back not answered in " + SETTINGS_STEP_MS + " ms; leaving the app's screen as it is");
				endAppSettings();
				return;
			}
			/* the settings screen never came after the tap */
			endAppSettings();
		}
	};

	/** the template ids of a template message's screen stack, top first (empty when the app sends none) */
	private static java.util.List<String> stackIds(String templateJson) {
		java.util.ArrayList<String> ids = new java.util.ArrayList<>();
		try {
			org.json.JSONArray a = new org.json.JSONObject(templateJson).optJSONArray("templateInfosForScreenStack");
			if (a != null)
				for (int i = 0; i < a.length(); i++) {
					org.json.JSONObject info = a.optJSONObject(i);
					String id = info != null ? info.optString("templateId", null) : null;
					if (id != null)
						ids.add(id);
				}
		} catch (org.json.JSONException e) {
			Log.w(TAG, "template without a readable screen stack", e);
		}
		return ids;
	}

	/* whether the bottom of IDS is exactly BASE (both top first) */
	private static boolean endsWith(java.util.List<String> ids, java.util.List<String> base) {
		return ids.size() >= base.size() && ids.subList(ids.size() - base.size(), ids.size()).equals(base);
	}

	/* how many screens IDS has above the settings' base, 0 at the base or not above it. The base's
	 * top screen may be refreshed (a new id) while nothing lies above it: then the base follows. */
	private int settingsDepth(java.util.List<String> ids) {
		java.util.List<String> base = settingsBase;
		int n = base.size();
		if (n == 0)
			return 0;
		java.util.List<String> under = base.subList(1, n);
		if (ids.size() == n && endsWith(ids, under)) {
			settingsBase = new java.util.ArrayList<>(ids);
			return 0;
		}
		if (ids.size() > n && endsWith(ids, under))
			return ids.size() - n;
		return 0;
	}

	/* host thread: a renderer's {"type":"appSettings"} */
	private void onAppSettingsMessage(Object renderer, String state) {
		switch (state) {
			case "open":
				/* a renderer opens them (again, or another renderer takes over): from the stack now */
				handler.removeCallbacks(settingsStepTimeout);
				settingsOwner = renderer;
				settingsStack = lastTemplateJson != null ? stackIds(lastTemplateJson) : java.util.Collections.<String>emptyList();
				settingsBase = settingsStack;
				settingsSeen = false;
				settingsClosing = false;
				settingsBackPending = false;
				settingsBacks = 0;
				log("app settings opened by a renderer, from a stack of " + settingsBase.size());
				break;
			case "closed":
				if (renderer == settingsOwner && !settingsClosing)
					endAppSettings();
				break;
			case "abandon":
				if (renderer == settingsOwner) {
					log("app settings abandoned by their renderer: back to the map");
					abandonAppSettings();
				}
				break;
			default:
				Log.w(TAG, "renderer: unknown app settings state " + state);
		}
	}

	/* host thread: the settings' renderer went without closing them: the host pops them */
	private void abandonAppSettings() {
		settingsOwner = null;
		if (settingsBase.isEmpty()) {
			/* the app sends no screen stack: nothing tells its settings from the map */
			endAppSettings();
			return;
		}
		settingsClosing = true;
		settingsBacks = 0;
		/* a settings screen tapped for just now may still be on its way */
		settingsWaitUntil = android.os.SystemClock.uptimeMillis() + SETTINGS_OPEN_MS;
		pumpAppSettingsBack();
	}

	/* host thread: a template while settings are open or closing */
	private void appSettingsTemplate(java.util.List<String> ids) {
		settingsStack = ids;
		int depth = settingsDepth(ids);
		if (depth > 0)
			settingsSeen = true;
		if (!settingsClosing)
			return;
		settingsBackPending = false;
		handler.removeCallbacks(settingsStepTimeout);
		pumpAppSettingsBack();
	}

	/* host thread: one back while the app's settings are above the map, none while one is answered */
	private void pumpAppSettingsBack() {
		if (!settingsClosing || settingsBackPending)
			return;
		int depth = settingsDepth(settingsStack);
		if (depth == 0) {
			long wait = settingsWaitUntil - android.os.SystemClock.uptimeMillis();
			if (settingsSeen || wait <= 0) {
				if (settingsSeen)
					log("app settings closed: the app is back at its map after " + settingsBacks + " back(s)");
				endAppSettings();
			} else {
				handler.removeCallbacks(settingsStepTimeout);
				handler.postDelayed(settingsStepTimeout, wait);
			}
			return;
		}
		if (settingsBacks >= SETTINGS_MAX_BACKS || appManager == null) {
			Log.w(TAG, "app settings: still " + depth + " screen(s) above the map after " + settingsBacks + " back(s); left as they are");
			endAppSettings();
			return;
		}
		settingsBackPending = true;
		settingsBacks++;
		handler.removeCallbacks(settingsStepTimeout);
		handler.postDelayed(settingsStepTimeout, SETTINGS_STEP_MS);
		sendBack();
	}

	/* a renderer that went while it held the app's screens: its hold ends once its screens are popped */
	private Object goneHolder;

	private void endAppSettings() {
		if (goneHolder != null) {
			channel.releaseHoldOf(goneHolder);
			goneHolder = null;
		}
		handler.removeCallbacks(settingsStepTimeout);
		settingsOwner = null;
		settingsClosing = false;
		settingsBackPending = false;
		settingsSeen = false;
		settingsBacks = 0;
		settingsBase = java.util.Collections.emptyList();
		settingsStack = java.util.Collections.emptyList();
	}

	/* host thread: back, as the car's back button (the app's ScreenManager pops its top screen) */
	private void sendBack() {
		if (appManager == null)
			return;
		try {
			appManager.onBackPressed(new Done("onBackPressed") {
				@Override
				void success(Object response) {
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.w(TAG, "onBackPressed failed", e);
		}
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
	private void onRendererMessage(Object renderer, String text) {
		try {
			org.json.JSONObject m = new org.json.JSONObject(text);
			String type = m.getString("type");
			if ("appSettings".equals(type)) {
				/* where the renderer is with the app's settings (onAppSettingsMessage) */
				onAppSettingsMessage(renderer, m.optString("state", ""));
				return;
			}
			/* every renderer message is the driver's input or a change of the map's area or look:
			 * the app's map moves or redraws */
			android.atl.ATLFrameRateLimiter.contentMoved("car renderer " + type);
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
			if ("restrictionMode".equals(type)) {
				/* the car's UX restriction mode chosen on the car page ("baseline", or "passenger":
				 * typing while moving), applied at once and kept (ATLCarVehicle.setRestrictionMode) */
				String mode = m.optString("mode", "");
				if (vehicle != null && vehicle.setRestrictionMode(mode))
					log("car UX restriction mode " + mode);
				else
					Log.w(TAG, "renderer: not a restriction mode: " + mode);
				return;
			}
			if ("mapScale".equals(type)) {
				/* the driver's map size on the car page: a factor on the display's density */
				setMapScale((float)m.getDouble("scale"));
				return;
			}
			if ("renderScale".equals(type)) {
				/* the driver's map sharpness on the car page: the map drawn in this fraction of the
				 * display's pixels and scaled up (ATLCarSurface.setRenderScale) */
				if (carSurface != null && carSurface.setRenderScale((float)m.getDouble("scale")))
					log("map render scale " + carSurface.renderScale());
				return;
			}
			if ("stopNavigation".equals(type)) {
				stopNavigation(renderer, m.has("id") ? m.optInt("id") : null);
				return;
			}
			if ("hold".equals(type)) {
				/* a renderer works on the app's screens out of sight (ATLCarAppChannel.hold): the
				 * screens it pushes are popped back by the host if it goes without releasing */
				hold(renderer, m.optBoolean("on"), m.has("id") ? m.optInt("id") : null);
				return;
			}
			if ("navigate".equals(type)) {
				/* a destination for the app, as a car's host hands one over (CarContext.ACTION_NAVIGATE) */
				navigate(renderer, m.optString("uri", ""), m.has("id") ? m.optInt("id") : null);
				return;
			}
			if ("back".equals(type)) {
				sendBack();
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
		} catch (org.json.JSONException | RuntimeException e) {
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
				if (channel != null) {
					channel.sendNavigation(active);
					/* the trip ends with the navigation */
					if (!active)
						publishTrip(null);
				}
			}
		});
	}

	/* host thread: the answer to a renderer's request that carried an id ({"type":T+"Result","id":N,
	 * "ok":B,"error":S}); a request without an id gets none */
	private void reply(Object renderer, String type, Integer id, boolean ok, String error) {
		if (channel == null || id == null)
			return;
		StringBuilder sb = new StringBuilder("{\"type\":\"").append(type).append("Result\",\"id\":").append(id).append(",\"ok\":").append(ok);
		if (error != null) {
			sb.append(",\"error\":");
			CarModelJson.quote(sb, error);
		}
		channel.sendTo(renderer, sb.append('}').toString());
	}

	/* host thread: the driver stopped navigation with the host's control: the app hears
	 * NavigationManagerCallback.onStopNavigation (INavigationManager.onStopNavigation) */
	private void stopNavigation(final Object renderer, final Integer id) {
		if (navigationManager == null || !navigating) {
			String why = navigationManager == null ? "no navigation manager" : "the app is not navigating";
			log("stop navigation: " + why);
			reply(renderer, "stopNavigation", id, false, why);
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
					reply(renderer, "stopNavigation", id, true, null);
				}

				@Override
				void failure(String why) {
					reply(renderer, "stopNavigation", id, false, "the app refused: " + why);
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "onStopNavigation failed", e);
			reply(renderer, "stopNavigation", id, false, String.valueOf(e));
		}
	}

	/**
	 * host thread: a renderer hands the app a destination, as a car's host does when the driver
	 * asks its assistant for one: ICarApp.onNewIntent with CarContext.ACTION_NAVIGATE
	 * ("androidx.car.app.action.NAVIGATE") and a geo: URI ("geo:0,0?q=ADDRESS" or
	 * "geo:LAT,LON?q=LAT,LON"), which reaches the app's Session.onNewIntent. The app decides what
	 * it does with it (Waze searches the query and starts driving to it).
	 */
	private void navigate(final Object renderer, String uri, final Integer id) {
		android.net.Uri data = android.net.Uri.parse(uri);
		if (!"geo".equals(data.getScheme())) {
			reply(renderer, "navigate", id, false, "not a geo: URI: " + uri);
			return;
		}
		if (carApp == null || !created) {
			reply(renderer, "navigate", id, false, "the app's car session is not running yet");
			return;
		}
		Intent intent = new Intent(ACTION_NAVIGATE, data);
		intent.setComponent(serviceName);
		log("navigate: ICarApp.onNewIntent(" + intent + ")");
		try {
			carApp.onNewIntent(intent, new Done("onNewIntent") {
				@Override
				void success(Object response) {
					reply(renderer, "navigate", id, true, null);
				}

				@Override
				void failure(String why) {
					reply(renderer, "navigate", id, false, "the app refused the intent: " + why);
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "onNewIntent failed", e);
			reply(renderer, "navigate", id, false, String.valueOf(e));
		}
	}

	/**
	 * host thread: {"type":"hold","on":B}: while a renderer holds, the app's new templates go to it
	 * alone and the driver's screens keep what they show; the app's screen stack when it took the
	 * hold is remembered as with the app's settings, so that a holder that goes away (its socket
	 * closed, or silent) has the screens it pushed popped back by the host. A holder that releases
	 * the hold leaves the app's screens as they are (it popped them itself, or a drive it started is
	 * on screen now). One renderer holds at a time, and none while a renderer shows the app's
	 * settings.
	 */
	private void hold(Object renderer, boolean on, Integer id) {
		if (on) {
			if (settingsOwner != null && settingsOwner != renderer) {
				reply(renderer, "hold", id, false, "the car screen shows the app's settings now");
				return;
			}
			if (settingsClosing) {
				reply(renderer, "hold", id, false, "the host is still closing screens a renderer left open");
				return;
			}
			if (!channel.hold(renderer, true)) {
				reply(renderer, "hold", id, false, "another renderer holds the app's screens");
				return;
			}
			onAppSettingsMessage(renderer, "open");
			log("a renderer holds the app's screens");
			reply(renderer, "hold", id, true, null);
			return;
		}
		if (!channel.holds(renderer)) {
			reply(renderer, "hold", id, false, "this renderer does not hold the app's screens");
			return;
		}
		if (renderer == settingsOwner && !settingsClosing)
			endAppSettings();
		channel.hold(renderer, false);
		log("the renderer released the app's screens");
		reply(renderer, "hold", id, true, null);
	}

	/** CarContext.ACTION_NAVIGATE: a host's intent for a navigation app to drive to a geo: URI */
	public static final String ACTION_NAVIGATE = "androidx.car.app.action.NAVIGATE";

	/* host thread: the app's trip (NavigationManager.updateTrip: destinations with their names and
	 * travel estimates, steps, the current road) to the renderers as state, its icons first; null
	 * once navigation ends */
	private void publishTrip(Bundleable trip) {
		if (channel == null)
			return;
		if (trip == null) {
			channel.sendTrip(null);
			return;
		}
		Object model;
		try {
			model = trip.get(appClassLoader);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Log.e(TAG, "trip not readable", e);
			return;
		}
		String json = modelJson.toJson(model);
		for (java.util.Map.Entry<Integer, Object> e : new java.util.ArrayList<>(modelJson.icons.entrySet())) {
			if (channel.hasIcon(e.getKey()))
				continue;
			byte[] png = renderIcon(e.getValue());
			if (png != null)
				channel.sendIcon(e.getKey(), png);
		}
		channel.sendTrip(json);
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

		/* the app's failure response, described */
		void failure(String why) {
			failure();
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
					String why = describe(failureResponse);
					log(what + " failed: " + why);
					failure(why);
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
		public void updateTrip(final Bundleable trip) {
			log("INavigationHost.updateTrip(" + describe(trip) + ")");
			handler.post(new Runnable() {
				@Override
				public void run() {
					/* a trip while navigating; one that arrives after the end is not kept */
					if (navigating)
						publishTrip(trip);
				}
			});
		}

		@Override
		public void setVoiceAssistantCapabilities(Bundleable capabilities) {
			log("INavigationHost.setVoiceAssistantCapabilities(" + describe(capabilities) + ")");
		}
	}
}
