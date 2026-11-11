package android.atl;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.Application;
import android.app.ContextImpl;
import android.app.Service;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Slog;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashSet;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Hold ATL Specific application data to load apps
 */
public final class ATLLoadedApp {
	private final static String TAG = "ATLLoadedApp";
	static final HashSet<String> play_services = new HashSet<>(Arrays.asList(
	    "com.android.vending", "com.google.android.gms", "com.google.android.gsf"));
	private static ATLLoadedApp system_application;
	private static ATLLoadedApp primary_application;
	public final Resources default_resources;
	public final ClassLoader class_loader;
	public final PackageParser.Package pkg;
	public final int effective_sdk_compat;
	private final Object init_lock;
	private Application application;
	private Resources.Theme default_theme;

	ATLLoadedApp(Resources resources, ClassLoader classLoader,
	             PackageParser.Package pkg) {
		this.default_resources = resources;
		this.class_loader = classLoader;
		this.pkg = pkg;
		this.effective_sdk_compat = Math.min(pkg.applicationInfo.targetSdkVersion, Build.VERSION.SDK_INT);
		this.init_lock = new Object();
	}

	@NonNull
	public static synchronized ATLLoadedApp getSystemApplication() {
		if (system_application != null) {
			return system_application;
		}
		File frameworkRes = ATLPaths.framework_res_apk;
		PackageParser packageParser = new PackageParser(frameworkRes != null ? frameworkRes.getPath() : null);
		Resources resources = Resources.getSystem();
		String[] outError = new String[1];
		PackageParser.Package pkg;
		try {
			pkg = packageParser.parsePackage(resources,
			                                 resources.getAssets().openXmlResourceParser(1, "AndroidManifest.xml"),
			                                 PackageParser.PARSE_IS_SYSTEM, outError);
		} catch (XmlPullParserException | IOException e) {
			throw new RuntimeException(outError[0], e);
		}
		/* AOSP: the framework package ("android", shared user android.uid.system) runs as the system
		 * uid, is a persistent system package and is signed with the platform key; ATL's build signs
		 * framework-res.apk with a platform key of its own (build-aux/sign-framework-res.sh) */
		pkg.applicationInfo.uid = android.os.Process.SYSTEM_UID;
		pkg.applicationInfo.flags |= android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_PERSISTENT;
		if (frameworkRes != null) {
			pkg.applicationInfo.sourceDir = frameworkRes.getPath();
			pkg.applicationInfo.publicSourceDir = frameworkRes.getPath();
			if (!packageParser.collectCertificates(pkg, PackageParser.PARSE_IS_SYSTEM))
				Slog.w(TAG, "framework package " + frameworkRes + " has no platform signature (error " + packageParser.getParseError() + ")");
		} else {
			Slog.w(TAG, "framework-res.apk is not on the class path: the framework package has no signature");
		}
		return system_application = new ATLLoadedApp(resources,
		                                             ATLLoadedApp.class.getClassLoader(), pkg);
	}

	@NonNull
	public static synchronized ATLLoadedApp getPrimaryApplication() {
		if (primary_application != null) {
			return primary_application;
		}
		final String classLoaderPath = System.getProperty("atl.app.class.path");
		final String nativePath = System.getProperty("atl.app.library.path");
		if (classLoaderPath == null && nativePath == null) {
			// Allow ATL test runner to not fail when no primary application is present
			Slog.w(TAG, "No primary application defined, using framework-res.apk as fallback");
			return primary_application = getSystemApplication();
		}
		final int pathsIndex = classLoaderPath.indexOf(':');
		try {
			return primary_application = loadFromPath(
				   pathsIndex == -1 ? classLoaderPath : classLoaderPath.substring(0, pathsIndex),
				   nativePath, classLoaderPath);
		} catch (IOException e) {
			// Failing to get the primary application should always be an error
			throw new Error("Failed to parse primary application", e);
		}
	}

	@NonNull
	public static ATLLoadedApp loadFromPath(String mainApk, String nativePath, String classLoaderPath) throws IOException {
		if (ATLToggles.ATL_GHAX) {
			// Always load ghax.jar as the first element to override application library classes
			classLoaderPath = ATLPaths.ghax_jar.getPath() + File.pathSeparator + classLoaderPath;
		}
		BaseDexClassLoader classLoader = new PathClassLoader(
		    classLoaderPath, nativePath, ATLLoadedApp.class.getClassLoader());
		AssetManager assetManager = new AssetManager(classLoaderPath + ":" + System.getProperty("java.class.path"));
		PackageParser packageParser = new PackageParser(mainApk);
		Resources resources = new Resources(assetManager, new DisplayMetrics(), Context.sys_config);
		String[] outError = new String[1];
		PackageParser.Package pkg;
		try {
			pkg = packageParser.parsePackage(resources,
			                                 assetManager.openXmlResourceParser(assetManager.findCookieForPath(mainApk), "AndroidManifest.xml"), 0, outError);
		} catch (XmlPullParserException e) {
			throw new IOException(outError[0], e);
		}
		if (packageParser.getParseError() != PackageManager.INSTALL_SUCCEEDED) {
			throw new IOException(outError[0]);
		}
		packageParser.collectCertificates(pkg, 0);
		// Support for MicroG and other custom GMS implementations.
		if (play_services.contains(pkg.packageName)) {
			ATLSigHelper.addGMSSignatures(pkg);
		}
		pkg.applicationInfo.sourceDir = mainApk;
		return new ATLLoadedApp(resources, classLoader, pkg);
	}

	public Resources createDefaultResources() {
		return this.createResources(this.default_resources.getDisplayMetrics(), Context.sys_config);
	}

	public Resources createResources(DisplayMetrics displayMetrics, Configuration configuration) {
		if (displayMetrics == null)
			displayMetrics = this.default_resources.getDisplayMetrics();
		if (configuration == null)
			configuration = Context.sys_config;
		/* AOSP (ResourcesManager): the resources' configuration is the system one with the caller's
		 * override applied, over an AssetManager of their own, and later system changes keep the override */
		Configuration override = new Configuration(configuration);
		Configuration effective = new Configuration(Context.sys_config);
		effective.updateFrom(override);
		Resources resources = new Resources(this.default_resources.getAssets().tlNewSharing(), displayMetrics, effective);
		synchronized (tl_resources) {
			tl_resources.add(new Object[] {new java.lang.ref.WeakReference<Resources>(resources), override});
		}
		return resources;
	}

	public ContextImpl createContext(DisplayMetrics displayMetrics, Configuration configuration, int theme) {
		return new ContextImpl(this.createResources(displayMetrics, configuration), this, theme);
	}

	public ContextImpl createContext(DisplayMetrics displayMetrics, Configuration configuration, Resources.Theme theme) {
		return new ContextImpl(this.createResources(displayMetrics, configuration), this, theme);
	}

	public Resources.Theme getDefaultTheme() {
		if (this.default_theme != null)
			return this.default_theme;
		synchronized (this.init_lock) {
			if (this.default_theme != null)
				return this.default_theme;
			this.default_theme = this.default_resources.newTheme();
			this.default_theme.applyStyle(this.pkg.applicationInfo.theme, true);
		}
		return this.default_theme;
	}

	public Application getApplicationUnsafe() throws ReflectiveOperationException {
		if (this.application != null)
			return application;
		final String applicationClassName = pkg.applicationInfo.className;
		synchronized (this.init_lock) {
			if (application != null)
				return application;
			if (applicationClassName != null) {
				Class<? extends Application> cls =
				    this.loadClass(pkg.applicationInfo.className)
					.asSubclass(Application.class);
				Constructor<? extends Application> constructor = cls.getConstructor();
				application = constructor.newInstance();
			} else {
				application = new Application();
			}
			application.atl_attach_base_context(new ContextImpl(
			    this.createDefaultResources(), this, pkg.applicationInfo.theme));
			// HACK: Set WhatsApp's custom logging mechanism to verbose for easier debugging.
			// Should be removed again once WhatsApp is fully supported
			try {
				this.loadClass("com.whatsapp.util.Log").getField("level").setInt(null, 5);
			} catch (Exception ignore) {
			}
		}
		return application;
	}

	public Class<?> loadClass(String name) throws ClassNotFoundException {
		return Class.forName(name, false, class_loader);
	}

	public Application getApplication() {
		try {
			return this.getApplicationUnsafe();
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public Activity createActivity(String className, Intent intent)
	    throws ReflectiveOperationException {
		int theme_res = 0;
		int label_res = 0;
		for (PackageParser.Activity activity : pkg.activities) {
			if (className.equals(activity.className)) {
				label_res = activity.info.labelRes;
				theme_res = activity.info.getThemeResource();
				break;
			}
		}

		theme_res = Resources.selectDefaultTheme(theme_res, this.effective_sdk_compat);

		Class<? extends Activity> cls = this.loadClass(className).asSubclass(Activity.class);
		Constructor<? extends Activity> constructor = cls.getConstructor();
		Activity activity = constructor.newInstance();
		intent.setComponent(new ComponentName(pkg.packageName, className));
		activity.intent = intent;
		Resources r = this.createDefaultResources();
		activity.atl_attach_base_context(new ContextImpl(r, this, theme_res));
		if (label_res != 0) {
			activity.setTitle(r.getText(label_res));
		} else if (pkg.applicationInfo.labelRes != 0) {
			activity.setTitle(r.getText(pkg.applicationInfo.labelRes));
		}
		return activity;
	}

	/**
	 * The service of this app an intent addresses (PackageManager.resolveService): its explicit
	 * component when the app declares that service, else the declared service whose intent filter
	 * matches with the highest priority; null when there is none.
	 */
	public ComponentName resolveService(Intent intent) {
		ComponentName component = intent.getComponent();
		if (component != null) {
			for (PackageParser.Service service : pkg.services) {
				if (service.className.equals(component.getClassName()))
					return new ComponentName(pkg.packageName, service.className);
			}
			return null;
		}
		int priority = Integer.MIN_VALUE;
		for (PackageParser.Service service : pkg.services) {
			if (service.intents == null)
				continue;
			for (PackageParser.IntentInfo intentInfo : service.intents) {
				if (intentInfo.matchAction(intent.getAction()) && tlMatchesCategories(intentInfo, intent) && intentInfo.priority > priority) {
					component = new ComponentName(pkg.packageName, service.className);
					priority = intentInfo.priority;
					break;
				}
			}
		}
		return component;
	}

	/* IntentFilter.matchCategories: every category of the intent must be in the filter */
	private static boolean tlMatchesCategories(PackageParser.IntentInfo filter, Intent intent) {
		java.util.Set<String> categories = intent.getCategories();
		if (categories == null)
			return true;
		for (String category : categories) {
			if (!filter.hasCategory(category))
				return false;
		}
		return true;
	}

	/** A new instance of one of this app's services, attached to a context of this app (not yet created). */
	public Service instantiateService(String className) throws ReflectiveOperationException {
		Class<? extends Service> cls = this.loadClass(className).asSubclass(Service.class);
		Service service = cls.getConstructor().newInstance();
		service.attachBaseContext(new ContextImpl(this.createDefaultResources(), this, pkg.applicationInfo.theme));
		return service;
	}

	public void receiveBroadcast(Context context, Intent intent) {
		for (PackageParser.Activity receiver : pkg.receivers) {
			if (receiver.intents == null)
				continue;
			for (PackageParser.IntentInfo intentInfo : receiver.intents) {
				if (intentInfo.matchAction(intent.getAction())) {
					try {
						Class<? extends BroadcastReceiver> cls = this.loadClass(receiver.className).asSubclass(BroadcastReceiver.class);
						BroadcastReceiver receiverInstance = cls.newInstance();
						receiverInstance.onReceive(context, intent);
					} catch (ReflectiveOperationException e) {
						Slog.e(TAG, "startService: failed to receive broadcast " + receiver.className, e);
					}
				}
			}
		}
	}

	public long getNativeWindow() {
		return this.application == null ? 0 : this.application.native_window;
	}

	/* teslalinux: the Resources created for this app and the override each was created with */
	private final java.util.ArrayList<Object[]> tl_resources = new java.util.ArrayList<>();

	/** AOSP ResourcesManager.applyConfigurationToResources: a system configuration change reaches every Resources, each keeping its override. */
	public void tlApplyConfiguration(Configuration sys, DisplayMetrics metrics) {
		this.default_resources.updateConfiguration(sys, metrics);
		synchronized (tl_resources) {
			for (int i = tl_resources.size() - 1; i >= 0; i--) {
				Object[] entry = tl_resources.get(i);
				Resources res = ((java.lang.ref.WeakReference<Resources>) entry[0]).get();
				if (res == null) {
					tl_resources.remove(i);
					continue;
				}
				Configuration effective = new Configuration(sys);
				effective.updateFrom((Configuration) entry[1]);
				res.updateConfiguration(effective, metrics);
			}
		}
	}
}
