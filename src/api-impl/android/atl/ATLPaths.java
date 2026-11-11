package android.atl;

import java.io.File;

public final class ATLPaths {
	public static final File app_data_dir_base;
	public static final File installed_apks_dir;
	public static final File api_impl_jar;
	public static final File gstub_jar;
	public static final File ghax_jar;
	/** the framework package's APK, or null when the runtime was started without it */
	public static final File framework_res_apk;
	/**
	 * The system partition of this runtime (AOSP's /system): priv-app/NAME/NAME.apk and
	 * app/NAME/NAME.apk are the system apps, etc/permissions/*.xml the privileged-permission
	 * allowlists. Next to api-impl.jar, or $ATL_SYSTEM_DIR.
	 */
	public static final File system_dir;

	static {
		// The main executable set api-impl.jar as the first element of "java.class.path"
		String cp = System.getProperty("java.class.path");
		api_impl_jar = new File(cp.substring(0, cp.indexOf(':'))).getAbsoluteFile();
		// additional .jar files are always next to api-impl.jar both when installed and when running from builddir
		File extraJarsDirectory = ATLPaths.api_impl_jar.getParentFile();
		gstub_jar = new File(extraJarsDirectory, "gstub.jar");
		ghax_jar = new File(extraJarsDirectory, "ghax.jar");
		// main.c puts framework-res.apk (the "android" package) on the class path after api-impl.jar
		File frameworkRes = null;
		for (String element : cp.split(":")) {
			if (element.endsWith("/framework-res.apk") || element.equals("framework-res.apk")) {
				frameworkRes = new File(element).getAbsoluteFile();
				break;
			}
		}
		framework_res_apk = frameworkRes;
		String systemDir = System.getenv("ATL_SYSTEM_DIR");
		system_dir = systemDir != null && !systemDir.isEmpty() ? new File(systemDir).getAbsoluteFile() : new File(extraJarsDirectory, "system");
		// Mirror how main.c calculates app_data_dir_base
		String ANDROID_APP_DATA_DIR = System.getenv("ANDROID_APP_DATA_DIR");
		if (ANDROID_APP_DATA_DIR != null && !ANDROID_APP_DATA_DIR.isEmpty()) {
			app_data_dir_base = new File(ANDROID_APP_DATA_DIR).getAbsoluteFile();
		} else {
			// Mirror g_get_user_data_dir(); behavior
			String user_data_dir = System.getenv("XDG_DATA_HOME");
			if (user_data_dir == null || user_data_dir.isEmpty()) {
				user_data_dir = System.getenv("HOME") + "/.local/share";
			}
			app_data_dir_base = new File(user_data_dir + "/android_translation_layer").getAbsoluteFile();
		}
		installed_apks_dir = new File(app_data_dir_base, "_installed_apks_");
	}
}
