package android.atl;

import android.annotation.Nullable;
import android.content.pm.PackageParser;
import android.util.DisplayMetrics;
import android.util.Slog;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public final class ATLLoadedAppManager {
	private static final String TAG = "ATLLoadedAppManager";
	private static final HashMap<String, ATLLoadedApp> loaded_apps = new HashMap<>();
	private static final HashMap<String, PackageParser.Package> loaded_packages = new HashMap<>();
	private static final Object load_lock = new Object();
	private static final HashSet<String> always_visible_packages = new HashSet<>();
	private static final HashSet<String> always_allow_query_all_packages = new HashSet<>();

	static {
		// Always allow MicroG to be visible for application compatibility.
		always_visible_packages.addAll(ATLLoadedApp.play_services);
		// Always allow MicroG to see all applications for compatibility.
		always_allow_query_all_packages.addAll(ATLLoadedApp.play_services);
	}

	private ATLLoadedAppManager() {}

	@Nullable
	public static PackageParser.Package getPackageFromPackageName(String packageName) {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		if (app.pkg.packageName.equals(packageName)) {
			return app.pkg;
		}
		if ("android".equals(packageName) || "atl".equals(packageName)) {
			return ATLLoadedApp.getSystemApplication().pkg;
		}
		app = loaded_apps.get(packageName);
		if (app != null) {
			return app.pkg;
		}
		PackageParser.Package pkg = loaded_packages.get(packageName);
		if (pkg != null) {
			return pkg;
		}
		return loadPackage(packageName);
	}

	@Nullable
	public static ATLLoadedApp peekAppFromPackageName(String packageName) {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		if (app.pkg.packageName.equals(packageName)) {
			return app;
		}
		if ("android".equals(packageName) || "atl".equals(packageName)) {
			return ATLLoadedApp.getSystemApplication();
		}
		return loaded_apps.get(packageName);
	}

	@Nullable
	public static ATLLoadedApp getAppFromPackageName(String packageName) {
		ATLLoadedApp app = peekAppFromPackageName(packageName);
		if (app != null) {
			return app;
		}
		return loadApplication(packageName);
	}

	private static PackageParser.Package loadPackage(String packageName) {
		if (!isPackageVisible(ATLLoadedApp.getPrimaryApplication().pkg.packageName, packageName)) {
			// No right to view this package.
			return null;
		}
		PackageParser.Package pkg;
		synchronized (load_lock) {
			ATLLoadedApp app = loaded_apps.get(packageName);
			if (app != null) {
				return app.pkg;
			}
			pkg = loaded_packages.get(packageName);
			if (pkg != null) {
				return pkg;
			}
			File installed_apk_location = apkOf(packageName);
			if (!installed_apk_location.isFile()) {
				if (ATLLoadedApp.play_services.contains(packageName)) {
					return loadGStub(packageName).pkg;
				}
				return null;
			}
			PackageParser packageParser = new PackageParser(installed_apk_location.getPath());
			pkg = packageParser.parsePackage(installed_apk_location,
			                                 installed_apk_location.getPath(), new DisplayMetrics(), 0);
			if (pkg != null) {
				packageParser.collectCertificates(pkg, 0);
				if (ATLLoadedApp.play_services.contains(packageName)) {
					ATLSigHelper.addGMSSignatures(pkg);
				}
				pkg.applicationInfo.sourceDir = installed_apk_location.getPath();
				pkg.applicationInfo.publicSourceDir = installed_apk_location.getPath();
				ATLPackageSettings.applyInstallState(pkg);
				loaded_packages.put(packageName, pkg);
			} else {
				Slog.e(TAG, "Failed to load package " + packageName);
			}
		}
		return pkg;
	}

	private static ATLLoadedApp loadApplication(String packageName) {
		if (!isPackageVisible(ATLLoadedApp.getPrimaryApplication().pkg.packageName, packageName)) {
			// No right to view this package.
			return null;
		}
		ATLLoadedApp app;
		synchronized (load_lock) {
			app = loaded_apps.get(packageName);
			if (app != null) {
				return app;
			}
			Slog.i(TAG, "Attempting to load " + packageName + " into " + ATLLoadedApp.getPrimaryApplication().pkg.packageName);
			File installed_apk_location = apkOf(packageName);
			File installed_lib_location = new File(ATLPaths.app_data_dir_base, packageName + "_/lib");
			if (!installed_apk_location.isFile()) {
				if (ATLLoadedApp.play_services.contains(packageName)) {
					return loadGStub(packageName);
				}
				return null;
			}
			String apk_path = installed_apk_location.getPath();
			String lib_path = installed_lib_location.isDirectory() ? installed_lib_location.getPath() : null;
			try {
				app = ATLLoadedApp.loadFromPath(apk_path, lib_path, apk_path);
				app.pkg.applicationInfo.nativeLibraryDir = lib_path;
				app.pkg.applicationInfo.sourceDir = apk_path;
				app.pkg.applicationInfo.publicSourceDir = apk_path;
				ATLPackageSettings.applyInstallState(app.pkg);
				Slog.i(TAG, "loadApplication(\"" + packageName + "\")");
				loaded_packages.put(packageName, app.pkg);
				loaded_apps.put(packageName, app);
			} catch (IOException e) {
				Slog.e(TAG, "Failed to load " + packageName, e);
				return null;
			}
		}
		return app;
	}

	/* AOSP AppsFilter: system apps are force-queryable here (config_forceSystemPackagesQueryable), the
	 * platform components apps talk to (a car template renderer) must be found by them */
	private static boolean isPackageVisible(String sourcePackageName, String targetPackageName) {
		return always_visible_packages.contains(targetPackageName) || always_allow_query_all_packages.contains(sourcePackageName)
		    || ATLPackageSettings.getSystemApk(targetPackageName) != null;
	}

	/** where a package's APK is: a system app's under the system directory, else the installed one */
	private static File apkOf(String packageName) {
		File system = ATLPackageSettings.getSystemApk(packageName);
		return system != null ? system : new File(ATLPaths.installed_apks_dir, packageName + ".apk");
	}

	/** Whether uid is the uid of a package loaded in this process other than the app and the framework. */
	public static boolean isLoadedPackageUid(int uid) {
		synchronized (load_lock) {
			for (PackageParser.Package pkg : loaded_packages.values()) {
				if (pkg.applicationInfo.uid == uid)
					return true;
			}
		}
		return false;
	}

	/** The packages known with this uid, other than the running app and the framework. */
	public static java.util.List<PackageParser.Package> packagesForUid(int uid) {
		java.util.ArrayList<PackageParser.Package> list = new java.util.ArrayList<>();
		for (String name : ATLPackageSettings.packagesForUid(uid)) {
			PackageParser.Package pkg = getPackageFromPackageName(name);
			if (pkg != null && pkg.applicationInfo.uid == uid)
				list.add(pkg);
		}
		return list;
	}

	private static ATLLoadedApp loadGStub(String packageName) {
		ATLLoadedApp app = ATLGStub.loadFakePlayServices(packageName);
		Slog.i(TAG, "loadGStub(\"" + packageName + "\")");
		loaded_packages.put(packageName, app.pkg);
		loaded_apps.put(packageName, app);
		return app;
	}

	/** Every app loaded in this process (for configuration changes). */
	public static java.util.Collection<ATLLoadedApp> tlLoadedApps() {
		synchronized (load_lock) {
			return new java.util.ArrayList<>(loaded_apps.values());
		}
	}
}
