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
 * <p>This stage of the host renders nothing yet: every call the app makes to the host services
 * ("app": invalidate, toasts, the surface callback, alerts, location; "navigation": navigation
 * started/ended, trips) is logged, and on every invalidate() the current template is pulled from
 * the app and logged in readable form (tag ATLCarHost).
 */
public final class ATLCarHost {
	static final String TAG = "ATLCarHost";

	/** CarAppApiLevels.LEVEL_9: the Car App Library API level of the interfaces this host implements */
	public static final int HOST_API_LEVEL = 9;
	/** the host's package: the framework (see PackageManager.getPackagesForUid(SYSTEM_UID)) */
	public static final String HOST_PACKAGE = "android";
	public static final String ACTION_CAR_APP_SERVICE = "androidx.car.app.CarAppService";

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
		Configuration config = new Configuration(app.getApplication().getResources().getConfiguration());
		Intent intent = new Intent();
		intent.setComponent(serviceName);
		try {
			carApp.onAppCreate(carHost, intent, config, new Done("onAppCreate") {
				@Override
				void success(Object response) {
					lifecycle();
				}
			});
		} catch (RemoteException | RuntimeException e) {
			Log.e(TAG, "onAppCreate failed", e);
		}
	}

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
			/* "constraints", "car" (hardware), "suggestion", "media_playback": services this host does
			 * not offer; the library treats a null host as an unavailable service */
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
		public void showToast(CharSequence text, int duration) {
			log("IAppHost.showToast(\"" + text + "\", " + (duration == 1 ? "LENGTH_LONG" : "LENGTH_SHORT") + ")");
		}

		@Override
		public void setSurfaceCallback(final IBinder callback) {
			log("IAppHost.setSurfaceCallback(" + (callback != null ? "<ISurfaceCallback>" : "null") + ")");
			handler.post(new Runnable() {
				@Override
				public void run() {
					surfaceCallback = callback;
				}
			});
		}

		@Override
		public void sendLocation(Location location) {
			log("IAppHost.sendLocation(" + location + ")");
		}

		@Override
		public void showAlert(Bundleable alert) {
			log("IAppHost.showAlert(" + describe(alert) + ")");
		}

		@Override
		public void dismissAlert(int alertId) {
			log("IAppHost.dismissAlert(" + alertId + ")");
		}

		@Override
		public Bundleable openMicrophone(Bundleable request) {
			/* this host has no microphone input for the app: no OpenMicrophoneResponse, as a host
			 * without that capability answers */
			log("IAppHost.openMicrophone(" + describe(request) + "): no microphone in this host");
			return null;
		}
	}

	/** INavigationHost ("navigation") */
	private final class NavigationHost extends INavigationHost.Stub {
		@Override
		public void navigationStarted() {
			log("INavigationHost.navigationStarted()");
		}

		@Override
		public void navigationEnded() {
			log("INavigationHost.navigationEnded()");
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
