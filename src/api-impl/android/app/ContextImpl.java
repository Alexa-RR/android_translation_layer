package android.app;

import android.accounts.AccountManager;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.app.SearchManager;
import android.app.job.JobScheduler;
import android.atl.ATLLoadedApp;
import android.atl.ATLLoadedAppManager;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import android.os.storage.StorageManager;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.CaptioningManager;
import android.view.inputmethod.InputMethodManager;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

public final class ContextImpl extends Context {
	private static final int ATL_FLAG_NO_CODE = 0x00000001;
	private static final int ATL_FLAG_RESTRICTED = 0x00000002;
	private static final int ATL_FLAG_PROTECTED_STORAGE = 0x00000004;
	private final static String TAG = "ContextImpl";
	private final Resources resources;
	private final ATLLoadedApp atl_loaded_app;
	/**
	 * While not being part of the official Android API, some applications use it to reset/reload the theme in the context.
	 */
	@UnsupportedAppUsage
	private Resources.Theme mTheme;
	/**
	 * While not being part of the official Android API, some application use it to get the theme resource ID.
	 */
	@UnsupportedAppUsage
	private int mThemeResource;

	private final LayoutInflater layout_inflater = new LayoutInflater(this);
	private final JobScheduler job_scheduler = new JobScheduler(this);

	private final int atl_flags;

	/* the display of a context made by createDisplayContext (AOSP ContextImpl.mDisplay), else null:
	 * the window's display */
	private Display tlDisplay;

	public ContextImpl(Resources resources, ATLLoadedApp loadedApp, Resources.Theme theme) {
		this(resources, loadedApp, 0, 0);
		getTheme().setTo(theme);
	}

	private ContextImpl(Resources resources, ATLLoadedApp loadedApp, Resources.Theme theme, int flags) {
		this(resources, loadedApp, 0, flags);
		getTheme().setTo(theme);
	}

	public ContextImpl(Resources resources, ATLLoadedApp loadedApp, int themeResource) {
		this(resources, loadedApp, themeResource, 0);
	}

	private ContextImpl(Resources resources, ATLLoadedApp loadedApp, int themeResource, int flags) {
		this.resources = resources;
		this.atl_loaded_app = loadedApp;
		mThemeResource = themeResource;
		this.atl_flags = flags;
	}

	@Nullable
	private ATLLoadedApp atl_get_intent_target(Intent intent) {
		ATLLoadedApp targetApp;
		String targetPackage = intent.getComponent() != null ? intent.getComponent().getPackageName() : null;
		if (targetPackage != null || (targetPackage = intent.getPackage()) != null) {
			if (targetPackage.equals(this.atl_loaded_app.pkg.packageName)) {
				targetApp = this.atl_loaded_app;
			} else {
				targetApp = ATLLoadedAppManager.getAppFromPackageName(targetPackage);
			}
		} else {
			targetApp = this.atl_loaded_app;
		}
		return targetApp;
	}

	@Override
	public ApplicationInfo getApplicationInfo() {
		return atl_loaded_app.pkg.applicationInfo;
	}

	/**
	 * AOSP: the application of this context's package (LoadedApk.getApplication): another package
	 * loaded in this process (a system app) has its own Application. Framework contexts keep the
	 * running app's application, as before.
	 */
	@Override
	public Context getApplicationContext() {
		if (atl_loaded_app == ATLLoadedApp.getSystemApplication() || atl_loaded_app == ATLLoadedApp.getPrimaryApplication())
			return super.getApplicationContext();
		return atl_loaded_app.getApplication();
	}

	@Override
	public void setTheme(int resId) {
		mThemeResource = resId;
		if (mTheme != null) {
			mTheme.applyStyle(resId, true);
		}
	}

	@Override
	public boolean isRestricted() {
		return (this.atl_flags & ATL_FLAG_RESTRICTED) != 0;
	}

	@Override
	public boolean stopService(Intent intent) {
		ATLLoadedApp targetApp = this.atl_get_intent_target(intent);
		if (targetApp == null) {
			return false;
		}
		return android.atl.ATLActiveServices.stopService(targetApp, intent);
	}

	@Override
	public Resources.Theme getTheme() {
		if (mTheme == null) {
			mTheme = resources.newTheme();
			if (mThemeResource != 0) {
				mTheme.applyStyle(mThemeResource, true);
			}
		}
		return mTheme;
	}

	@Override
	public Object getSystemService(String name) {
		switch (name) {
			case "window":
				return new WindowManagerImpl();
			case "clipboard":
				return new ClipboardManager();
			case "sensor":
				return new SensorManager();
			case "connectivity":
				return new ConnectivityManager(this);
			case "keyguard":
				return new KeyguardManager();
			case "phone":
				return new TelephonyManager();
			case "audio":
				return new AudioManager();
			case "activity":
				return new ActivityManager();
			case "account":
				return AccountManager.get(this);
			case "usb":
				return new UsbManager();
			case "vibrator":
				return (vibrator != null) ? vibrator : (vibrator = new Vibrator());
			case "power":
				return new PowerManager();
			case "display":
				return new DisplayManager();
			case "media_router":
				return new MediaRouter();
			case "notification":
				return new NotificationManager();
			case "alarm":
				return new AlarmManager();
			case "input":
				return new InputManager();
			case "location":
				return new LocationManager();
			case "download":
				return new DownloadManager(this);
			case "uimode":
				return new UiModeManager();
			case "input_method":
				return new InputMethodManager();
			case "accessibility":
				return new AccessibilityManager();
			case "layout_inflater":
				return layout_inflater;
			case "wifi":
				return new WifiManager();
			case "bluetooth":
				/* no Bluetooth feature on this device: Android returns null for the service too */
				return getPackageManager().hasSystemFeature("android.hardware.bluetooth") ? new BluetoothManager() : null;
			case "jobscheduler":
				return job_scheduler;
			case "appops":
				return new AppOpsManager();
			case "user":
				return new UserManager();
			case "captioning":
				return new CaptioningManager();
			case "statusbar":
				return new StatusBarManager();
			case "camera":
				return new CameraManager();
			case "color_display":
				return new ColorDisplayManager();
			case "search":
				return new SearchManager();
			case "storage":
				return new StorageManager();
			case "media_metrics":
				return new android.media.metrics.MediaMetricsManager();
			case "content_capture":
				/* no content capture service on this device: Android returns null for the service too */
				return null;
			case "batterymanager":
				return new BatteryManager();
			default:
				Slog.e(TAG, "!!!!!!! getSystemService: case >" + name + "< is not implemented yet");
				return null;
		}
	}

	@Override
	public Object getSystemService(Class<?> serviceClass) throws InstantiationException, IllegalAccessException, InvocationTargetException {
		if (serviceClass == LayoutInflater.class)
			return layout_inflater;
		if (serviceClass == JobScheduler.class)
			return job_scheduler;
		/* teslalinux: interfaces (WindowManager) and managers without a public constructor used to throw
		 * ArrayIndexOutOfBounds here. Route through the name-based lookup first, then any no-arg ctor. */
		String tlName = tlSystemServiceName(serviceClass);
		if (tlName != null) {
			Object o = getSystemService(tlName);
			if (o != null)
				return o;
		}
		try {
			java.lang.reflect.Constructor<?> c = serviceClass.getDeclaredConstructor();
			c.setAccessible(true);
			return c.newInstance();
		} catch (NoSuchMethodException e) {
			Slog.e(TAG, "getSystemService(" + serviceClass.getName() + "): no mapping and no no-arg constructor");
			return null;
		}
	}

	static String tlSystemServiceName(Class<?> c) {
		switch (c.getName()) {
			case "android.view.WindowManager": return "window";
			case "android.view.LayoutInflater": return "layout_inflater";
			case "android.app.ActivityManager": return "activity";
			case "android.os.PowerManager": return "power";
			case "android.app.AlarmManager": return "alarm";
			case "android.app.NotificationManager": return "notification";
			case "android.app.KeyguardManager": return "keyguard";
			case "android.location.LocationManager": return "location";
			case "android.app.DownloadManager": return "download";
			case "android.app.SearchManager": return "search";
			case "android.hardware.SensorManager": return "sensor";
			case "android.os.storage.StorageManager": return "storage";
			case "android.os.Vibrator": return "vibrator";
			case "android.net.ConnectivityManager": return "connectivity";
			case "android.net.wifi.WifiManager": return "wifi";
			case "android.media.AudioManager": return "audio";
			case "android.media.MediaRouter": return "media_router";
			case "android.telephony.TelephonyManager": return "phone";
			case "android.content.ClipboardManager": return "clipboard";
			case "android.view.inputmethod.InputMethodManager": return "input_method";
			case "android.app.UiModeManager": return "uimode";
			case "android.hardware.usb.UsbManager": return "usb";
			case "android.hardware.input.InputManager": return "input";
			case "android.hardware.display.DisplayManager": return "display";
			case "android.os.UserManager": return "user";
			case "android.app.AppOpsManager": return "appops";
			case "android.hardware.camera2.CameraManager": return "camera";
			case "android.view.accessibility.AccessibilityManager": return "accessibility";
			case "android.bluetooth.BluetoothManager": return "bluetooth";
			case "android.app.job.JobScheduler": return "jobscheduler";
			case "android.view.accessibility.CaptioningManager": return "captioning";
			case "android.app.StatusBarManager": return "statusbar";
			case "android.hardware.display.ColorDisplayManager": return "color_display";
			case "android.media.metrics.MediaMetricsManager": return "media_metrics";
			case "android.os.BatteryManager": return "batterymanager";
			case "android.accounts.AccountManager": return "account";
			default: return null;
		}
	}

	@Override
	public Resources getResources() {
		return resources;
	}

	@Override
	public ClassLoader getClassLoader() {
		if ((this.atl_flags & ATL_FLAG_NO_CODE) != 0) {
			return null;
		}
		if (tlCodeFrom != null)
			return tlCodeFrom.class_loader;
		return this.atl_loaded_app.class_loader;
	}

	public ComponentName startService(Intent intent) {
		// One way Messenger interface for GCM. Request per intent and response per Messenger.
		// Wrap the response part in a BroadcastReceiver for compatibility with our gcm_service
		if ("com.google.android.c2dm.intent.REGISTER".equals(intent.getAction()) && intent.getParcelableExtra("google.messenger") instanceof Messenger) {
			final Messenger messenger = (Messenger)intent.getParcelableExtra("google.messenger");
			this.registerReceiver(new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent resultIntent) {
					try {
						messenger.send(Message.obtain(null, 0, resultIntent));
					} catch (RemoteException e) {
						e.printStackTrace();
					}
				}
			}, new IntentFilter("com.google.android.c2dm.intent.REGISTRATION"));
		}
		ATLLoadedApp targetApp = this.atl_get_intent_target(intent);
		// Cloud Messaging (c2dm) intents should always be started using DBus. Delivering them to the in process gstub is pointless
		if (targetApp == null || (intent.getAction() != null && intent.getAction().startsWith("com.google.android.c2dm"))) {
			// External package. Try to start using DBus Action
			if (intent.getPackage() != null)
				nativeStartExternalService(intent);
			return null;
		}
		return android.atl.ATLActiveServices.startService(targetApp, intent);
	}

	@Override
	public boolean bindService(Intent intent, ServiceConnection serviceConnection, int flags) {
		// Two way Messenger interface for GCM. Request and response are send over Messenger.
		// Wrap it on top of the old intent interface for compatibility with our gcm_service
		if ("com.google.android.c2dm.intent.REGISTER".equals(intent.getAction())) {
			Messenger requestMessenger = new Messenger(new Handler(Looper.getMainLooper()) {
				@Override
				public void handleMessage(Message requestMessage) {
					int request_id = requestMessage.arg1;
					Messenger responseMessenger = requestMessage.replyTo;
					registerReceiver(new BroadcastReceiver() {
						@Override
						public void onReceive(Context context, Intent responseIntent) {
							try {
								// map response Intent to Message
								Message responseMessage = Message.obtain(null, 0, request_id, 0);
								Bundle data = new Bundle();
								data.putBundle("data", responseIntent.getExtras());
								responseMessage.setData(data);
								responseMessenger.send(responseMessage);
							} catch (RemoteException e) {
								e.printStackTrace();
							}
						}
					}, new IntentFilter("com.google.android.c2dm.intent.REGISTRATION"));
					// map request Message to Intent
					Intent requestIntent = new Intent("com.google.android.c2dm.intent.REGISTER");
					requestIntent.setPackage("com.google.android.gms");
					requestIntent.putExtras(requestMessage.getData().getBundle("data"));
					nativeStartExternalService(requestIntent);
				}
			});
			/* the connection is to a service outside this process; unbindService must accept it */
			android.atl.ATLActiveServices.registerExternalConnection(serviceConnection);
			serviceConnection.onServiceConnected(intent.getComponent(), requestMessenger.getBinder());
			return true;
		}
		ATLLoadedApp targetApp = this.atl_get_intent_target(intent);
		if (targetApp == null) {
			return false;
		}
		/* AOSP service lifecycle: one binder per intent, onUnbind/onRebind, BIND_AUTO_CREATE */
		return android.atl.ATLActiveServices.bindService(targetApp, intent, serviceConnection, flags);
	}

	@Override
	public void startActivity(Intent intent) {
		Slog.i(TAG, "startActivity(" + intent + ") called");
		if (intent.getAction() != null && intent.getAction().equals("android.intent.action.CHOOSER")) {
			intent = (Intent)intent.getExtras().get("android.intent.extra.INTENT");
		}

		String className = null;
		if (intent.getComponent() != null) {
			className = intent.getComponent().getClassName();
		} else {
			if (intent.getAction() != null && intent.getAction().equals("android.intent.action.SEND")) {
				Slog.i(TAG, "sharing intent via composeMail: " + intent);
				String text = intent.getStringExtra("android.intent.extra.TEXT");
				ParcelFileDescriptor fd = null;
				if (intent.hasExtra(Intent.EXTRA_STREAM)) {
					try {
						fd = getContentResolver().openFileDescriptor((Uri)intent.getParcelableExtra(Intent.EXTRA_STREAM), "r");
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					}
				}
				final ParcelFileDescriptor fd_final = fd;
				Runnable runnable = new Runnable() {
					@Override
					public void run() {
						nativeShareFile(text, fd_final != null ? fd_final.getFd() : -1);
						if (fd_final != null) {
							try {
								fd_final.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
				};
				if (Looper.myLooper() == Looper.getMainLooper()) {
					runnable.run();
				} else {
					new Handler(Looper.getMainLooper()).post(runnable);
				}
				return;
			} else if (intent.getData() != null && (className = tlResolveViewIntent(intent)) != null) {
				/* AOSP: the app's own activities' intent filters resolve a view intent (deep link) before
				 * anything external gets to open it */
				Slog.i(TAG, "startActivity: " + intent.getData() + " resolved to " + className);
			} else if (intent.getData() != null) {
				Slog.i(TAG, "starting extern activity with intent: " + intent);
				if (intent.getData().getScheme().equals("content")) {
					try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(intent.getData(), "r")) {
						if (fd != null) {
							nativeOpenFile(fd.getFd());
							return;
						}
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				Activity.nativeOpenURI(String.valueOf(intent.getData()));
				return;
			}
			for (PackageParser.Activity activity : this.atl_loaded_app.pkg.activities) {
				for (PackageParser.IntentInfo intentInfo : activity.intents) {
					if (intentInfo.matchAction(intent.getAction())) {
						className = activity.className;
						break;
					}
				}
			}
		}
		if (className == null) {
			Slog.w(TAG, "startActivity: intent could not be handled.");
			return;
		}
		final String className_ = className;
		final ActivityOptions tlOptions_ = ActivityOptions.fromBundle(tlNextLaunchOptions); /* startActivity(Intent, Bundle) */
		final Intent intent_ = intent;
		/* AOSP singleTask/singleInstance (and FLAG_ACTIVITY_SINGLE_TOP): an existing instance gets onNewIntent */
		final boolean tlSingle_ = tlResumesExisting(className, intent);
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				try {
					if (((intent_.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0 && intent_.getComponent() != null) || tlSingle_) {
						boolean found = Activity.nativeResumeActivity(
						    ContextImpl.this.atl_loaded_app.loadClass(className_).asSubclass(Activity.class),
						    intent_);
						if (found)
							return;
					}
					Activity activity = Activity.internalCreateActivity(
					    className_, ContextImpl.this.atl_loaded_app.getNativeWindow(), intent_);
					activity.tlLaunchOptions = tlOptions_;
					Activity.nativeStartActivity(activity);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	@Override
	public Context createPackageContext(String packageName, int flags) {
		if (packageName.equals(atl_loaded_app.pkg.packageName)) {
			return this;
		}
		ATLLoadedApp primaryApplication = ATLLoadedApp.getPrimaryApplication();
		if (primaryApplication.pkg.packageName.equals(packageName)) {
			return primaryApplication.getApplication();
		}
		if (packageName.equals("android")) {
			// Shortcut for system application
			ATLLoadedApp system = ATLLoadedApp.getSystemApplication();
			return new ContextImpl(Resources.getSystem(), system,
			                       Resources.selectDefaultTheme(0, Build.VERSION.SDK_INT));
		}
		ATLLoadedApp loadedApplication = ATLLoadedAppManager.getAppFromPackageName(packageName);
		if (loadedApplication != null) {
			int atl_flags = 0;
			if ((flags & Context.CONTEXT_INCLUDE_CODE) == 0) {
				atl_flags |= ATL_FLAG_NO_CODE;
			}
			if ((flags & Context.CONTEXT_RESTRICTED) != 0) {
				atl_flags |= ATL_FLAG_RESTRICTED;
			}
			return new ContextImpl(loadedApplication.createDefaultResources(),
			                       loadedApplication, loadedApplication.pkg.applicationInfo.theme,
			                       atl_flags);
		}
		// Return the application context as a fallback
		Log.e(TAG, "!!!!!!! createPackageContext: case >" + packageName + "< is not implemented yet");
		return primaryApplication.getApplication();
	}

	@Override
	public Context createConfigurationContext(Configuration config_override) {
		Configuration config = new Configuration();
		config.setTo(getResources().getConfiguration());
		if (config_override != null)
			config.updateFrom(config_override);
		/* AOSP: the new context stays on this context's display */
		DisplayMetrics metrics = null;
		if (tlDisplay != null) {
			metrics = new DisplayMetrics();
			tlDisplay.getMetrics(metrics);
		}
		ContextImpl context = tlDerive(metrics, config);
		context.tlDisplay = tlDisplay;
		return context;
	}

	/* a context like this one for other metrics/configuration: a library component's context keeps
	 * the library's code and resources */
	private ContextImpl tlDerive(DisplayMetrics metrics, Configuration config) {
		if (tlCodeFrom == null)
			return this.atl_loaded_app.createContext(metrics, config, getTheme());
		ContextImpl context = new ContextImpl(tlCodeFrom.createResources(metrics, config), this.atl_loaded_app, getTheme());
		context.tlCodeFrom = tlCodeFrom;
		return context;
	}

	/* the library package whose code and resources a component of this app runs from (see
	 * tlCreateLibraryComponentContext), else null */
	private ATLLoadedApp tlCodeFrom;

	/**
	 * teslalinux: the context of an app component whose code is a library's (AOSP: a library merged
	 * into the app, its classes in the app's class loader and its resources in the app's): the
	 * component is the app's (package, uid, application, data), while its class loader and
	 * resources are those of the library package, so that the library's R ids, layouts and views
	 * resolve as they were built.
	 */
	public static ContextImpl tlCreateLibraryComponentContext(ATLLoadedApp app, ATLLoadedApp library, int themeResource) {
		ContextImpl context = new ContextImpl(library.createDefaultResources(), app, themeResource);
		context.tlCodeFrom = library;
		return context;
	}

	/**
	 * AOSP: a context whose resources describe the display: its size and density in the
	 * DisplayMetrics, and the configuration's screen size, smallest width, orientation and density
	 * computed from them (ResourcesManager applies the display's metrics to the configuration).
	 */
	@Override
	public Context createDisplayContext(Display display) {
		if (display == null)
			throw new IllegalArgumentException("display must not be null");
		DisplayMetrics metrics = new DisplayMetrics();
		display.getMetrics(metrics);
		Configuration config = new Configuration();
		config.setTo(getResources().getConfiguration());
		config.densityDpi = metrics.densityDpi;
		config.screenWidthDp = (int)(metrics.widthPixels / metrics.density + 0.5f);
		config.screenHeightDp = (int)(metrics.heightPixels / metrics.density + 0.5f);
		config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
		config.orientation = metrics.widthPixels > metrics.heightPixels ? Configuration.ORIENTATION_LANDSCAPE
		                     : metrics.widthPixels < metrics.heightPixels ? Configuration.ORIENTATION_PORTRAIT
		                     : Configuration.ORIENTATION_SQUARE;
		ContextImpl context = tlDerive(metrics, config);
		context.tlDisplay = display;
		return context;
	}

	/** AOSP: the display this context is associated with */
	@Override
	public Display getDisplay() {
		return tlDisplay != null ? tlDisplay : super.getDisplay();
	}

	@Override
	public boolean isDeviceProtectedStorage() {
		return (this.atl_flags & ATL_FLAG_PROTECTED_STORAGE) != 0;
	}

	@Override
	public Context createDeviceProtectedStorageContext() {
		return new ContextImpl(getResources(), this.atl_loaded_app, getTheme(),
		                       this.atl_flags | ATL_FLAG_PROTECTED_STORAGE);
	}

	@Override
	public int getThemeResId() {
		return mThemeResource;
	}

	@Override
	public ATLLoadedApp get_atl_loaded_app() {
		return atl_loaded_app;
	}

	/** AOSP PackageManager resolution of an implicit intent against this app's own activities (intent filters). */
	private String tlResolveViewIntent(Intent intent) {
		if (atl_loaded_app == null || atl_loaded_app.pkg == null)
			return null;
		String type = intent.resolveTypeIfNeeded(getContentResolver());
		for (PackageParser.Activity activity : atl_loaded_app.pkg.activities) {
			for (PackageParser.ActivityIntentInfo info : activity.intents) {
				if (info.match(intent.getAction(), type, intent.getScheme(), intent.getData(), intent.getCategories(), TAG) >= 0)
					return activity.className;
			}
		}
		return null;
	}

	/** AOSP: singleTask/singleInstance activities and FLAG_ACTIVITY_SINGLE_TOP reuse a running instance (onNewIntent). */
	private boolean tlResumesExisting(String className, Intent intent) {
		if (className == null || atl_loaded_app == null || atl_loaded_app.pkg == null)
			return false;
		if ((intent.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
			return true;
		for (PackageParser.Activity activity : atl_loaded_app.pkg.activities) {
			if (className.equals(activity.className) && activity.info != null)
				return activity.info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK
				    || activity.info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
		}
		return false;
	}

	/* ---- teslalinux (app): AOSP Context.startActivity(Intent, Bundle) ----
	 * The options bundle of the launch in progress: the started activity gets its ActivityOptions
	 * (usage time report) and a custom animation becomes the pending activity transition. */
	static android.os.Bundle tlNextLaunchOptions;

	@Override
	public void startActivity(Intent intent, android.os.Bundle options) {
		Activity.tlApplyLaunchOptions(this, options);
		android.os.Bundle previous = tlNextLaunchOptions;
		tlNextLaunchOptions = options;
		try {
			startActivity(intent);
		} finally {
			tlNextLaunchOptions = previous;
		}
	}
}
