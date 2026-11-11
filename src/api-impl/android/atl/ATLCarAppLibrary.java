package android.atl;

import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.util.Slog;
import java.io.IOException;

/**
 * Car apps on an automotive device (Android Automotive OS): an app built with the androidx Car App
 * Library runs its car UI through androidx's {@code CarAppActivity} (the app-automotive library),
 * declared in the app's manifest as its launcher activity. The activity binds the system's
 * template renderer ({@code android.car.template.host.RendererService}), which binds the app's
 * CarAppService and shows the templates in the activity's SurfaceView.
 *
 * An app built only for projection (Android Auto) has the CarAppService but not the
 * app-automotive library. On an automotive device ATL adds the library to such an app, as the
 * build would have merged it: the component androidx.car.app.activity.CarAppActivity is the app's
 * launcher activity, running as the app (its package, uid and application), with the library's
 * unmodified code and resources. The library comes from the system's template renderer package,
 * which carries app-automotive itself (the AOSP car Templates Host is built with it); the
 * activity loads it in a class loader of its own, as a copy inside the app, so nothing is shared
 * with the renderer except through binder calls.
 */
public final class ATLCarAppLibrary {
	private static final String TAG = "ATLCarAppLibrary";
	public static final String CAR_APP_ACTIVITY = "androidx.car.app.activity.CarAppActivity";
	/** androidx CarAppService.SERVICE_INTERFACE */
	public static final String CAR_APP_SERVICE = "androidx.car.app.CarAppService";
	/** CarAppActivity.ACTION_RENDER */
	public static final String ACTION_RENDER = "android.car.template.host.RendererService";

	private static final Object sLock = new Object();
	private static boolean sLooked;
	private static ATLLoadedApp sLibrary;

	private ATLCarAppLibrary() {}

	private static boolean isAutomotive() {
		return new PackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
	}

	private static boolean hasAction(PackageParser.Component<?> c, String action) {
		if (c.intents == null)
			return false;
		for (Object o : c.intents) {
			if (((android.content.IntentFilter) o).matchAction(action))
				return true;
		}
		return false;
	}

	/**
	 * Adds the library's CarAppActivity to the app's package when {@link #appliesTo} says so, as
	 * an AAOS car app declares it (the documented declaration): exported, singleTask, theme
	 * @android:style/Theme.DeviceDefault.NoActionBar, distractionOptimized, and the app's launcher
	 * activity (MAIN/LAUNCHER, ahead of the app's own). Returns whether it was added.
	 */
	public static boolean mergeInto(ATLLoadedApp app) {
		if (!appliesTo(app))
			return false;
		PackageParser.Package pkg = app.pkg;
		android.content.pm.ActivityInfo info = new android.content.pm.ActivityInfo();
		info.theme = android.R.style.Theme_DeviceDefault_NoActionBar;
		info.launchMode = android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
		info.exported = true;
		info.enabled = true;
		info.labelRes = pkg.applicationInfo.labelRes;
		info.icon = pkg.applicationInfo.icon;
		info.metaData = new android.os.Bundle();
		info.metaData.putBoolean("distractionOptimized", true);
		PackageParser.Activity activity = new PackageParser.Activity(pkg, CAR_APP_ACTIVITY, info);
		activity.metaData = info.metaData;
		PackageParser.ActivityIntentInfo filter = new PackageParser.ActivityIntentInfo(activity);
		filter.addAction("android.intent.action.MAIN");
		filter.addCategory("android.intent.category.LAUNCHER");
		activity.intents.add(filter);
		pkg.activities.add(0, activity);
		app.tlCarAppLibrary = library();
		Slog.i(TAG, pkg.packageName + ": " + CAR_APP_ACTIVITY + " added from the car app library (automotive device)");
		return true;
	}

	/** The library whose code runs the app's activity className, or null for the app's own code. */
	public static ATLLoadedApp libraryFor(ATLLoadedApp app, String className) {
		return app.tlCarAppLibrary != null && CAR_APP_ACTIVITY.equals(className) ? app.tlCarAppLibrary : null;
	}

	/**
	 * Whether the app gets the library's CarAppActivity: the device is automotive, the app has a
	 * car app service, does not declare CarAppActivity itself, and the system has the library.
	 */
	public static boolean appliesTo(ATLLoadedApp app) {
		if (!isAutomotive())
			return false;
		boolean service = false;
		for (PackageParser.Service s : app.pkg.services) {
			if (hasAction(s, CAR_APP_SERVICE)) {
				service = true;
				break;
			}
		}
		if (!service)
			return false;
		for (PackageParser.Activity a : app.pkg.activities) {
			if (CAR_APP_ACTIVITY.equals(a.className))
				return false;
		}
		return library() != null;
	}

	/**
	 * The app-automotive library: the APK of the system app holding the template renderer, loaded
	 * on its own (class loader and resources), or null when there is none.
	 */
	public static ATLLoadedApp library() {
		synchronized (sLock) {
			if (sLooked)
				return sLibrary;
			sLooked = true;
			for (String name : ATLPackageSettings.getSystemPackages()) {
				PackageParser.Package pkg = ATLLoadedAppManager.getPackageFromPackageName(name);
				if (pkg == null)
					continue;
				boolean renderer = false;
				for (PackageParser.Service s : pkg.services) {
					if (hasAction(s, ACTION_RENDER)) {
						renderer = true;
						break;
					}
				}
				if (!renderer)
					continue;
				String apk = pkg.applicationInfo.sourceDir;
				try {
					ATLLoadedApp library = ATLLoadedApp.loadFromPath(apk, null, apk);
					library.loadClass(CAR_APP_ACTIVITY);
					sLibrary = library;
					Slog.i(TAG, "car app library (app-automotive) from " + apk);
				} catch (IOException | ClassNotFoundException e) {
					Slog.w(TAG, "the template renderer " + name + " carries no app-automotive library", e);
				}
				break;
			}
			return sLibrary;
		}
	}
}
