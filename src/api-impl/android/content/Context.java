package android.content;
import java.util.concurrent.Executor;

import android.R;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.ContextImpl;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.Service;
import android.app.SharedPreferencesImpl;
import android.app.StatusBarManager;
import android.app.UiModeManager;
import android.app.job.JobScheduler;
import android.atl.ATLLoadedApp;
import android.bluetooth.BluetoothManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ShortcutManager;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
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
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.Vibrator;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.WindowManagerImpl;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.CaptioningManager;
import android.view.inputmethod.InputMethodManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.Provider;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Context {
	private final static String TAG = "Context";

	public static final int MODE_PRIVATE = 0;
	public static final String ACTIVITY_SERVICE = "activity";
	public static final String AUDIO_SERVICE = "audio";
	public static final String CLIPBOARD_SERVICE = "clipboard";
	public static final String DISPLAY_SERVICE = "display";
	/** AOSP: the input method manager is "input_method"; "input" is the InputManager (INPUT_SERVICE). */
	public static final String INPUT_METHOD_SERVICE = "input_method";
	public static final String INPUT_SERVICE = "input";
	public static final String LOCATION_SERVICE = "location";
	public static final String MEDIA_ROUTER_SERVICE = "media_router";
	public static final String POWER_SERVICE = "power";
	public static final String VIBRATOR_SERVICE = "vibrator";
	public static final String WINDOW_SERVICE = "window";

	public static Vibrator vibrator;

	public static PackageManager package_manager;
	public static Configuration sys_config;

	// TODO: Migrate to ATLLoadedApp, cannot remove yet due to being called by native
	// The current replacement is ATLLoadedApp.getPrimaryApplication().getApplication()
	@Deprecated
	private static Application this_application;

	File data_dir = null;
	File prefs_dir = null;
	File files_dir = null;
	File obb_dir = null;
	File cache_dir = null;
	File nobackup_dir = null;

	private static Map<IntentFilter, BroadcastReceiver> receiverMap = new ConcurrentHashMap<>();

	static {
		sys_config = new Configuration();
		native_updateConfig(sys_config);
		// teslalinux: the system locale, applied the way AOSP's ActivityThread applies persist.sys.locale
		// (LocaleList.setDefault with the configuration's locales) before any resources exist. The
		// launcher passes the device setting as -Duser.locale (host/phase1-pios/teslalinux-atl).
		// the system time zone, as AOSP's RuntimeInit hands persist.sys.timezone to libcore: the
		// launcher passes it as -Duser.timezone (and TZ); without it libcore falls back to GMT
		String tlZone = System.getProperty("user.timezone", "");
		if (tlZone.isEmpty() && System.getenv("TZ") != null)
			tlZone = System.getenv("TZ");
		if (!tlZone.isEmpty()) {
			final java.util.TimeZone tlTz = java.util.TimeZone.getTimeZone(tlZone);
			if (tlTz.getID().equals(tlZone))
				java.util.TimeZone.setDefault(tlTz);
		}
		final String tlLocaleTags = System.getProperty("user.locale", "");
		if (!tlLocaleTags.isEmpty()) {
			final android.os.LocaleList tlLocales = android.os.LocaleList.forLanguageTags(tlLocaleTags);
			if (!tlLocales.isEmpty()) {
				android.os.LocaleList.setDefault(tlLocales);
				sys_config.setLocales(tlLocales);
			}
		}
		ATLLoadedApp primary_application = ATLLoadedApp.getPrimaryApplication();

		ApplicationInfo application_info = primary_application.pkg.applicationInfo;
		application_info.dataDir = Environment.getExternalStorageDirectory().getAbsolutePath();
		application_info.nativeLibraryDir = (new File(Environment.getExternalStorageDirectory(), "lib")).getAbsolutePath();
		// AOSP: sourceDir/publicSourceDir name the base APK; the split APKs that were given on the
		// command line go to splitSourceDirs (ATL hands us the whole ':'-joined classpath).
		String[] apkPaths = native_get_apk_path().split(":");
		application_info.sourceDir = apkPaths[0];
		application_info.publicSourceDir = apkPaths[0];
		if (apkPaths.length > 1) {
			application_info.splitSourceDirs = java.util.Arrays.copyOfRange(apkPaths, 1, apkPaths.length);
			application_info.splitPublicSourceDirs = application_info.splitSourceDirs;
			application_info.splitNames = android.content.pm.PackageParser.readSplitNames(primary_application.default_resources.getAssets(), application_info.splitSourceDirs);
		}
		package_manager = new PackageManager();

		Provider provider = new Provider("AndroidKeyStore", 1.0, "Android KeyStore provider") {};
		provider.put("KeyStore.AndroidKeyStore", "android.security.keystore.AndroidKeyStore");
		provider.put("KeyGenerator.AES", "android.security.keystore.KeyGenerator$AES");
		provider.put("KeyGenerator.HmacSHA512", "android.security.keystore.KeyGenerator$HmacSHA512");
		Security.addProvider(provider);

		for (PackageParser.Activity receiver : primary_application.pkg.receivers) {
			if (receiver.intents == null)
				continue;
			for (PackageParser.ActivityIntentInfo intent : receiver.intents) {
				if (intent.matchAction("org.unifiedpush.android.connector.MESSAGE")) {
					nativeExportUnifiedPush(application_info.packageName);
					break;
				}
			}
		}
	}

	private static native String native_get_apk_path();
	protected static native void native_updateConfig(Configuration config);
	protected static native void nativeOpenFile(int fd);
	protected static native void nativeShareFile(String text, int fd);
	private static native void nativeExportUnifiedPush(String packageName);
	private static native void nativeRegisterUnifiedPush(String token, String application);
	protected static native void nativeStartExternalService(Intent service);

	static Application createApplication(long native_window) throws Exception {
		Application application = ATLLoadedApp.getPrimaryApplication().getApplication();
		application.native_window = native_window;
		this_application = application;
		return application;
	}

	public Context() {
		Slog.v(TAG, "new Context! this one is: " + this);
	}

	public int checkPermission(String permission, int pid, int uid) {
		return getPackageManager().checkPermission(permission, getPackageName());
	}

	public abstract Resources.Theme getTheme();

	public abstract ApplicationInfo getApplicationInfo();

	public Context getApplicationContext() {
		return ATLLoadedApp.getPrimaryApplication().getApplication();
	}

	public ContentResolver getContentResolver() {
		return new ContentResolver();
	}

	public abstract Object getSystemService(String name);

	public abstract Object getSystemService(Class<?> serviceClass) throws InstantiationException, IllegalAccessException, InvocationTargetException;

	public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
		if (receiver == null)
			return null;
		receiverMap.put(filter, receiver);
		return new Intent();
	}

	public Looper getMainLooper() {
		return Looper.getMainLooper();
	}

	public String getPackageName() {
		return getApplicationInfo().packageName;
	}

	public String getPackageCodePath() {
		return getApplicationInfo().sourceDir;
	}

	public int getColor(int resId) {
		return this.getResources().getColor(resId);
	}

	public final String getString(int resId) {
		return this.getResources().getString(resId);
	}

	public final String getString(int resId, Object... formatArgs) {
		return this.getResources().getString(resId, formatArgs);
	}

	public PackageManager getPackageManager() {
		return package_manager;
	}

	public abstract Resources getResources();

	public AssetManager getAssets() {
		return getResources().getAssets();
	}

	private File makeFilename(File base, String name) {
		if (name.indexOf(File.separatorChar) < 0) {
			return new File(base, name);
		}
		throw new IllegalArgumentException(
		    "File " + name + " contains a path separator");
	}

	private File getDataDirFile() {
		if (data_dir == null) {
			data_dir = android.os.Environment.getExternalStorageDirectory();
		}
		return data_dir;
	}

	public File getDataDir() {
		return getDataDirFile();
	}

	public File getFilesDir() {
		if (files_dir == null) {
			files_dir = new File(getDataDirFile(), "files");
		}
		if (!files_dir.exists()) {
			if (!files_dir.mkdirs()) {
				if (files_dir.exists()) {
					// spurious failure; probably racing with another process for this app
					return files_dir;
				}
				Slog.w(TAG, "Unable to create files directory " + files_dir.getPath());
				return null;
			}
		}
		return files_dir;
	}

	public File getExternalFilesDir(String type) {
		return getFilesDir();
	}

	public File[] getExternalFilesDirs(String type) {
		return new File[] {getExternalFilesDir(type)};
	}

	public File getObbDir() {
		if (obb_dir == null) {
			obb_dir = new File(getDataDirFile(), "Android/obb/" + getPackageName());
		}
		if (!obb_dir.exists()) {
			if (!obb_dir.mkdirs()) {
				if (obb_dir.exists()) {
					// spurious failure; probably racing with another process for this app
					return obb_dir;
				}
				Slog.w(TAG, "Unable to create obb directory >" + obb_dir.getPath() + "<");
				return null;
			}
		}
		return obb_dir;
	}

	public File[] getObbDirs() {
		return new File[] {getObbDir()};
	}

	public File getCacheDir() {
		if (cache_dir == null) {
			cache_dir = new File("/tmp/atl_cache/" + getPackageName());
		}
		if (!cache_dir.exists()) {
			if (!cache_dir.mkdirs()) {
				if (cache_dir.exists()) {
					// spurious failure; probably racing with another process for this app
					return cache_dir;
				}
				Slog.w(TAG, "Unable to create cache directory >" + cache_dir.getPath() + "<");
				return null;
			}
		}
		return cache_dir;
	}

	public File getExternalCacheDir() {
		return getCacheDir();
	}

	public File[] getExternalCacheDirs() {
		return new File[] {getCacheDir()};
	}

	public File getNoBackupFilesDir() {
		if (nobackup_dir == null) {
			nobackup_dir = new File(getDataDirFile(), "no_backup/" + getPackageName());
		}
		if (!nobackup_dir.exists()) {
			if (!nobackup_dir.mkdirs()) {
				if (nobackup_dir.exists()) {
					// spurious failure; probably racing with another process for this app
					return nobackup_dir;
				}
				Slog.w(TAG, "Unable to create no_backup directory >" + nobackup_dir.getPath() + "<");
				return null;
			}
		}
		return nobackup_dir;
	}

	private File getPreferencesDir() {
		if (prefs_dir == null) {
			prefs_dir = new File(getDataDirFile(), "shared_prefs");
		}
		return prefs_dir;
	}

	public File[] getExternalMediaDirs() {
		return getExternalFilesDirs("media");
	}

	public File getDir(String name, int mode) {
		File dir = new File(getFilesDir(), name);
		if (!dir.exists()) {
			if (!dir.mkdirs()) {
				if (dir.exists()) {
					// spurious failure; probably racing with another process for this app
					return dir;
				}
				Slog.w(TAG, "Unable to create directory >" + dir.getPath() + "<");
				return null;
			}
		}
		return dir;
	}

	public File getFileStreamPath(String name) {
		return makeFilename(getFilesDir(), name);
	}

	public File getSharedPrefsFile(String name) {
		return makeFilename(getPreferencesDir(), name + ".xml");
	}

	private static Map<String, SharedPreferences> sharedPrefs = new HashMap<String, SharedPreferences>();

	public SharedPreferences getSharedPreferences(String name, int mode) {
		Slog.v(TAG, "\n\n...> getSharedPreferences(" + name + ")\n\n");
		if (sharedPrefs.containsKey(name)) {
			return sharedPrefs.get(name);
		} else {
			File prefsFile = getSharedPrefsFile(name);
			SharedPreferences prefs = new SharedPreferencesImpl(prefsFile, mode);
			sharedPrefs.put(name, prefs);
			return prefs;
		}
	}

	public abstract ClassLoader getClassLoader();

	public abstract ComponentName startService(Intent intent);

	// TODO: do these both work? make them look more alike
	public FileInputStream openFileInput(String name) throws FileNotFoundException {
		Slog.v(TAG, "openFileInput called for: '" + name + "'");
		File file = new File(getFilesDir(), name);

		return new FileInputStream(file);
	}

	public FileOutputStream openFileOutput(String name, int mode) throws java.io.FileNotFoundException {
		Slog.v(TAG, "openFileOutput called for: '" + name + "'");
		return new FileOutputStream(android.os.Environment.getExternalStorageDirectory().getPath() + "/files/" + name);
	}

	public int checkCallingOrSelfPermission(String permission) {
		return getPackageManager().checkPermission(permission, getPackageName());
	}

	public int checkSelfPermission(String permission) {
		return checkCallingOrSelfPermission(permission);
	}

	/**
	 * AOSP: a context's component callbacks are the application's (Context.registerComponentCallbacks
	 * hands them to getApplicationContext(); Application keeps and dispatches them).
	 */
	public void registerComponentCallbacks(ComponentCallbacks callbacks) {
		Context app = getApplicationContext();
		if (app == null || app == this)
			throw new IllegalStateException("registerComponentCallbacks on a context without an application");
		app.registerComponentCallbacks(callbacks);
	}

	public void unregisterComponentCallbacks(ComponentCallbacks callbacks) {
		Context app = getApplicationContext();
		if (app == null || app == this)
			throw new IllegalStateException("unregisterComponentCallbacks on a context without an application");
		app.unregisterComponentCallbacks(callbacks);
	}

	public abstract boolean bindService(final Intent intent, final ServiceConnection serviceConnection, int flags);

	/* For use from native code */
	static Activity resolveActivityInternal(Intent intent) throws ReflectiveOperationException {
		String className = null;
		ATLLoadedApp primary = ATLLoadedApp.getPrimaryApplication();
		if (intent.getComponent() != null) {
			className = intent.getComponent().getClassName();
		} else {
			int best_score = -5;
			for (PackageParser.Activity activity : primary.pkg.activities) {
				for (PackageParser.IntentInfo intentInfo : activity.intents) {
					int score = intentInfo.match(intent.getAction(), intent.getType(), intent.getScheme(), intent.getData(), intent.getCategories(), "Context");
					if (score > best_score && score > 0) {
						className = activity.className;
						best_score = score;
					}
				}
			}
		}
		if (className != null) {
			return Activity.internalCreateActivity(className, primary.getNativeWindow(), intent);
		} else {
			return null;
		}
	}

	public abstract void startActivity(Intent intent);

	public void startActivity(Intent intent, Bundle options) {
		startActivity(intent);
	}

	public final TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs) {
		return getTheme().obtainStyledAttributes(set, attrs, 0, 0);
	}
	public final TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
		return getTheme().obtainStyledAttributes(set, attrs, defStyleAttr, defStyleRes);
	}
	public final TypedArray obtainStyledAttributes(int resid, int[] attrs) {
		return getTheme().obtainStyledAttributes(resid, attrs);
	}
	public final TypedArray obtainStyledAttributes(int[] attrs) {
		return getTheme().obtainStyledAttributes(attrs);
	}

	public abstract void setTheme(int resId);

	public final CharSequence getText(int resId) {
		return getResources().getText(resId);
	}

	public final ColorStateList getColorStateList(int id) {
		return getResources().getColorStateList(id);
	}

	public final Drawable getDrawable(int resId) {
		return getResources().getDrawable(resId, getTheme());
	}

	public abstract boolean isRestricted();

	public File getDatabasePath(String dbName) {
		File databaseDir = new File(getDataDirFile(), "databases");
		if (!databaseDir.exists())
			databaseDir.mkdirs();
		return new File(databaseDir, dbName);
	}

	public void sendBroadcast(Intent intent) {
		if ("org.unifiedpush.android.distributor.REGISTER".equals(intent.getAction())) {
			nativeRegisterUnifiedPush(intent.getStringExtra("token"), intent.getStringExtra("application"));
		}
		for (IntentFilter filter : receiverMap.keySet()) {
			if (filter.matchAction(intent.getAction())) {
				tlDeliverBroadcast(receiverMap.get(filter), intent);
			}
		}
		ATLLoadedApp.getPrimaryApplication().receiveBroadcast(this, intent);
	}

	public abstract boolean stopService(Intent intent);

	public void unbindService(ServiceConnection serviceConnection) {}

	public void unregisterReceiver(BroadcastReceiver receiver) {
		while (receiverMap.values().remove(receiver))
			;
		tlReceiverSchedulers.remove(receiver);
	}

	public static final int CONTEXT_INCLUDE_CODE = 0x00000001;
	public static final int CONTEXT_IGNORE_SECURITY = 0x00000002;
	public static final int CONTEXT_RESTRICTED = 0x00000004;

	public abstract Context createPackageContext(String packageName, int flags);

	public void grantUriPermission(String dummy, Uri dummy2, int dummy3) {
		System.out.println("grantUriPermission(" + dummy + ", " + dummy2 + ", " + dummy3 + ") called");
	}

	public static final int MODE_ENABLE_WRITE_AHEAD_LOGGING = 0x0008;
	public static final int MODE_NO_LOCALIZED_COLLATORS = 0x0010;

	public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
		return openOrCreateDatabase(name, mode, factory, null);
	}

	public SQLiteDatabase openOrCreateDatabase(String filename, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
		int flags = SQLiteDatabase.CREATE_IF_NECESSARY;
		if ((mode & MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
			flags |= SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
		}
		if ((mode & MODE_NO_LOCALIZED_COLLATORS) != 0) {
			flags |= SQLiteDatabase.NO_LOCALIZED_COLLATORS;
		}
		SQLiteDatabase db = SQLiteDatabase.openDatabase(filename, factory, flags, errorHandler);
		return db;
	}

	public boolean deleteDatabase(String name) {
		File dbFile = getDatabasePath(name);
		return dbFile.delete();
	}

	public abstract Context createConfigurationContext(Configuration configuration);

	public void sendOrderedBroadcast(Intent intent, String receiverPermission, BroadcastReceiver resultReceiver, Handler handler, int flags, String extra, Bundle options) {
		System.out.println("sendOrderedBroadcast(" + intent + ", " + receiverPermission + ", " + resultReceiver + ", " + handler + ", " + flags + ", " + extra + ", " + options + ") called");
	}

	public abstract Context createDisplayContext(Display display);

	/** AOSP: onReceive runs on scheduler's thread (the main thread when null). */
	public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, String broadcastPermission, Handler scheduler) {
		Intent sticky = registerReceiver(receiver, filter);
		if (receiver != null && scheduler != null)
			tlReceiverSchedulers.put(receiver, scheduler);
		return sticky;
	}

	/** AOSP: the names of the private files of this context's package (getFilesDir()). */
	public String[] fileList() {
		String[] list = getFilesDir().list();
		return list != null ? list : new String[0];
	}

	public void revokeUriPermission(Uri uri, int mode) {
		System.out.println("revokeUriPermission(" + uri + ", " + mode + ") called");
	}

	public android.content.AttributionSource getAttributionSource() {
		return new android.content.AttributionSource(android.os.Process.myUid(), getPackageName(), getAttributionTag());
	}

	public String getAttributionTag() {
		return null;
	}
	public abstract boolean isDeviceProtectedStorage();

	public Drawable getWallpaper() {
		return null;
	}

	public String[] databaseList() {
		File databaseDir = new File(getDataDirFile(), "databases");
		if (databaseDir.exists()) {
			return databaseDir.list();
		} else {
			return new String[0];
		}
	}

	public abstract Context createDeviceProtectedStorageContext();

	public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
		File sourceFile = sourceContext.getSharedPrefsFile(name);
		if (!sourceFile.exists()) {
			return true;
		}
		deleteSharedPreferences(name);
		return sourceFile.renameTo(getSharedPrefsFile(name));
	}

	public boolean deleteSharedPreferences(String name) {
		getSharedPrefsFile(name).delete();
		sharedPrefs.remove(name);
		return true;
	}

	public String getPackageResourcePath() {
		return native_get_apk_path();
	}

	public abstract int getThemeResId();

	public abstract ATLLoadedApp get_atl_loaded_app();


	/* teslalinux: API 28-34 Context additions Waze touches during Application.onCreate */
	public Executor getMainExecutor() {
		final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
		return new Executor() {
			public void execute(Runnable r) { h.post(r); }
		};
	}
	/** AOSP: a context whose operations are attributed to the tag (getAttributionTag/Source report it). */
	public Context createAttributionContext(final String attributionTag) {
		return new ContextWrapper(this) {
			@Override
			public String getAttributionTag() {
				return attributionTag;
			}

			@Override
			public android.content.AttributionSource getAttributionSource() {
				return new android.content.AttributionSource(android.os.Process.myUid(), getPackageName(), attributionTag);
			}
		};
	}
	public Context createWindowContext(int type, android.os.Bundle options) { return this; }
	public Context createWindowContext(android.view.Display display, int type, android.os.Bundle options) { return this; }
	public Context createDeviceContext(int deviceId) { return this; }
	/** AOSP Context.DEVICE_ID_DEFAULT: there are no virtual devices, every context is on the default one. */
	public static final int DEVICE_ID_DEFAULT = 0;
	/** AOSP Context.DEVICE_ID_INVALID */
	public static final int DEVICE_ID_INVALID = -1;
	public int getDeviceId() { return DEVICE_ID_DEFAULT; }
	/**
	 * AOSP: whether this context can show UI: activities (and input method services), and wrappers of
	 * them; the application and service contexts are not UI contexts.
	 */
	public boolean isUiContext() {
		if (this instanceof android.app.Activity || this instanceof android.inputmethodservice.InputMethodService)
			return true;
		if (this instanceof ContextWrapper) {
			Context base = ((ContextWrapper) this).getBaseContext();
			return base != null && base != this && base.isUiContext();
		}
		return false;
	}
	public String getOpPackageName() { return getPackageName(); }
	public java.io.File getCodeCacheDir() { java.io.File f = new java.io.File(getCacheDir(), "code_cache"); f.mkdirs(); return f; }
	public String getSystemServiceName(Class<?> serviceClass) { return serviceClass.getSimpleName(); }
	public ComponentName startForegroundService(Intent service) { return startService(service); }
	public void startActivities(Intent[] intents) { for (Intent i : intents) startActivity(i); }
	public void startActivities(Intent[] intents, android.os.Bundle options) { startActivities(intents); }
	public boolean deleteFile(String name) { return getFileStreamPath(name).delete(); }
	public int checkCallingOrSelfUriPermission(android.net.Uri uri, int modeFlags) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
	public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags, android.os.UserHandle user) { return bindService(service, conn, flags); }
	public android.view.Display getDisplay() { return ((android.view.WindowManager) getSystemService("window")).getDefaultDisplay(); }
	/*
	 * AOSP: listeners for this context moving to another (virtual) device. ATL has no virtual
	 * devices, so the device id never changes and registered listeners are never called; the
	 * registration rules (no null, no duplicates) are AOSP's.
	 */
	private final java.util.ArrayList<java.util.function.IntConsumer> tlDeviceIdListeners = new java.util.ArrayList<>();
	public void registerDeviceIdChangeListener(Executor executor, java.util.function.IntConsumer listener) {
		if (executor == null)
			throw new NullPointerException("executor must not be null");
		if (listener == null)
			throw new NullPointerException("listener must not be null");
		synchronized (tlDeviceIdListeners) {
			if (tlDeviceIdListeners.contains(listener))
				throw new IllegalArgumentException("attempt to call registerDeviceIdChangeListener() on a previously registered listener");
			tlDeviceIdListeners.add(listener);
		}
	}
	public void unregisterDeviceIdChangeListener(java.util.function.IntConsumer listener) {
		if (listener == null)
			throw new NullPointerException("listener must not be null");
		synchronized (tlDeviceIdListeners) {
			tlDeviceIdListeners.remove(listener);
		}
	}
	/* API 29/33 overloads: the executor variant dispatches the connection callbacks on that executor;
	 * receiver export flags are meaningless in a single-process runtime and are ignored like options. */
	public boolean bindService(Intent service, int flags, final java.util.concurrent.Executor executor, final ServiceConnection conn) {
		ServiceConnection onExecutor = new ServiceConnection() {
			public void onServiceConnected(final ComponentName name, final android.os.IBinder binder) { executor.execute(new Runnable() { public void run() { conn.onServiceConnected(name, binder); } }); }
			public void onServiceDisconnected(final ComponentName name) { executor.execute(new Runnable() { public void run() { conn.onServiceDisconnected(name); } }); }
		};
		return bindService(service, onExecutor, flags);
	}
	public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) { return registerReceiver(receiver, filter); }
	public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, String broadcastPermission, android.os.Handler scheduler, int flags) { return registerReceiver(receiver, filter, broadcastPermission, scheduler); }
	public void sendBroadcast(Intent intent, String receiverPermission, android.os.Bundle options) { sendBroadcast(intent); }
	/** AOSP: name of the content capture service; absent in this runtime, so getSystemService returns null. */
	public static final String CONTENT_CAPTURE_MANAGER_SERVICE = "content_capture";
	/** AOSP (hidden): whether resources of other packages may be loaded; true for the app's own package. */
	public boolean canLoadUnsafeResources() {
		return getPackageName().equals(getOpPackageName());
	}
	/**
	 * teslalinux: the window size changed (native-config.sh updated sys_config and the Display);
	 * refresh the system resources' metrics and configuration like AOSP's ResourcesManager does.
	 */
	public static void tlOnConfigurationChanged() {
		android.content.res.Resources sys = android.content.res.Resources.getSystem();
		android.util.DisplayMetrics metrics = sys.getDisplayMetrics();
		metrics.setToDefaults();
		sys.updateConfiguration(sys_config, metrics);
		for (android.atl.ATLLoadedApp app : android.atl.ATLLoadedAppManager.tlLoadedApps())
			app.tlApplyConfiguration(sys_config, metrics);
	}
	/** AOSP: the name of the connectivity service (getSystemService). */
	public static final String CONNECTIVITY_SERVICE = "connectivity";
	/** AOSP: the autofill service name; this platform has no autofill service, so getSystemService returns null for it. */
	public static final String AUTOFILL_MANAGER_SERVICE = "autofill";

	/** the Handler each registered receiver asked to run on (registerReceiver with a scheduler) */
	private static final java.util.Map<BroadcastReceiver, Handler> tlReceiverSchedulers = new ConcurrentHashMap<>();

	/**
	 * AOSP LoadedApk.ReceiverDispatcher: the broadcast reaches a registered receiver later, on its
	 * scheduler (the main thread by default), as a copy of the sent intent, with a PendingResult the
	 * receiver may take with goAsync(); one it leaves is finished after onReceive. A receiver
	 * unregistered before delivery does not get it.
	 */
	private void tlDeliverBroadcast(final BroadcastReceiver receiver, Intent intent) {
		if (receiver == null)
			return;
		Handler scheduler = tlReceiverSchedulers.get(receiver);
		if (scheduler == null)
			scheduler = new Handler(Looper.getMainLooper());
		final Intent delivered = new Intent(intent);
		final Context context = this;
		scheduler.post(new Runnable() {
			@Override
			public void run() {
				if (!receiverMap.containsValue(receiver))
					return;
				receiver.setPendingResult(new BroadcastReceiver.PendingResult(0, null, null,
				    BroadcastReceiver.PendingResult.TYPE_REGISTERED, false, false, null,
				    android.os.UserHandle.myUserId(), delivered.getFlags()));
				try {
					receiver.onReceive(context, delivered);
				} finally {
					BroadcastReceiver.PendingResult result = receiver.getPendingResult();
					if (result != null) {
						receiver.setPendingResult(null);
						result.finish();
					}
				}
			}
		});
	}
}
