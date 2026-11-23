package android.atl;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.Xml;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * The package manager's state for the packages of this runtime other than the app it runs, as
 * AOSP's PackageManagerService keeps it:
 *
 * <ul>
 * <li>System apps (AOSP's scan of /system/priv-app and /system/app): the APKs under {@link
 *     ATLPaths#system_dir}. Apps in priv-app are privileged (ApplicationInfo.FLAG_PRIVILEGED).</li>
 * <li>App uids (AOSP Settings, packages.list): every package other than the running app and the
 *     framework gets a uid of its own, the first free one from {@link Process#FIRST_APPLICATION_UID},
 *     kept across starts in packages.list in the app data directory.</li>
 * <li>Privileged permission allowlists (AOSP SystemConfig, etc/permissions/*.xml
 *     {@code <privapp-permissions package="...">}).</li>
 * <li>Install-time permission grants (AOSP PermissionManagerService.restorePermissionState):
 *     normal permissions are granted, signature permissions when the package is signed like the
 *     package defining the permission, privileged ones to allowlisted privileged apps; dangerous
 *     permissions are runtime permissions the user never granted to these packages.</li>
 * </ul>
 */
public final class ATLPackageSettings {
	private static final String TAG = "ATLPackageSettings";

	private static final Object sLock = new Object();
	private static boolean sScanned;
	/** system apps: package name to APK */
	private static final HashMap<String, File> sSystemApks = new HashMap<>();
	private static final HashSet<String> sPrivileged = new HashSet<>();
	/** AOSP SystemConfig.getPrivAppPermissions */
	private static final HashMap<String, Set<String>> sPrivappAllowlist = new HashMap<>();
	/** packages.list: package name to uid */
	private static final HashMap<String, Integer> sUids = new HashMap<>();
	private static boolean sUidsRead;
	/** permission definitions of the platform outside framework-res (name to protection level) */
	private static final HashMap<String, Integer> sPlatformPermissions = new HashMap<>();

	private ATLPackageSettings() {}

	/*
	 * Framework permissions AOSP added after the framework-res.apk ATL ships (its manifest is from
	 * an older release and is prebuilt), with their AOSP protection levels: every normal and
	 * dangerous <permission> of core/res/AndroidManifest.xml (android14-release) that
	 * framework-res does not define, without the ones marked permissionFlags="removed" (AOSP grants
	 * those to nobody). Signature-level ones are left out: no app that is not platform-signed can
	 * hold them, which is what an unknown permission gives too. ("instant" does not apply here; an
	 * app-op flag is honoured by AppOpsManager.)
	 */
	private static final String[][] NEWER_FRAMEWORK_PERMISSIONS = {
		{"android.permission.ACCEPT_HANDOVER", "dangerous"},
		{"android.permission.ACCESS_BACKGROUND_LOCATION", "dangerous"},
		{"android.permission.ACCESS_MEDIA_LOCATION", "dangerous"},
		{"android.permission.ACTIVITY_RECOGNITION", "dangerous"},
		{"android.permission.ANSWER_PHONE_CALLS", "dangerous"},
		{"android.permission.BLUETOOTH_ADVERTISE", "dangerous"},
		{"android.permission.BLUETOOTH_CONNECT", "dangerous"},
		{"android.permission.BLUETOOTH_SCAN", "dangerous"},
		{"android.permission.BODY_SENSORS_BACKGROUND", "dangerous"},
		{"android.permission.CALL_COMPANION_APP", "normal"},
		{"android.permission.CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS", "normal"},
		{"android.permission.CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS", "normal"},
		{"android.permission.CREDENTIAL_MANAGER_SET_ORIGIN", "normal"},
		{"android.permission.DELIVER_COMPANION_MESSAGES", "normal"},
		{"android.permission.DETECT_SCREEN_CAPTURE", "normal"},
		{"android.permission.ENFORCE_UPDATE_OWNERSHIP", "normal"},
		{"android.permission.FOREGROUND_SERVICE", "normal"},
		{"android.permission.FOREGROUND_SERVICE_CAMERA", "normal"},
		{"android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE", "normal"},
		{"android.permission.FOREGROUND_SERVICE_DATA_SYNC", "normal"},
		{"android.permission.FOREGROUND_SERVICE_FILE_MANAGEMENT", "normal"},
		{"android.permission.FOREGROUND_SERVICE_HEALTH", "normal"},
		{"android.permission.FOREGROUND_SERVICE_LOCATION", "normal"},
		{"android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK", "normal"},
		{"android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION", "normal"},
		{"android.permission.FOREGROUND_SERVICE_MICROPHONE", "normal"},
		{"android.permission.FOREGROUND_SERVICE_PHONE_CALL", "normal"},
		{"android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING", "normal"},
		{"android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "normal"},
		{"android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED", "normal"},
		{"android.permission.HIDE_OVERLAY_WINDOWS", "normal"},
		{"android.permission.HIGH_SAMPLING_RATE_SENSORS", "normal"},
		{"android.permission.MANAGE_OWN_CALLS", "normal"},
		{"android.permission.NEARBY_WIFI_DEVICES", "dangerous"},
		{"android.permission.NFC_PREFERRED_PAYMENT_INFO", "normal"},
		{"android.permission.NFC_TRANSACTION_EVENT", "normal"},
		{"android.permission.POST_NOTIFICATIONS", "dangerous"},
		{"android.permission.QUERY_ALL_PACKAGES", "normal"},
		{"android.permission.READ_BASIC_PHONE_STATE", "normal"},
		{"android.permission.READ_MEDIA_AUDIO", "dangerous"},
		{"android.permission.READ_MEDIA_IMAGES", "dangerous"},
		{"android.permission.READ_MEDIA_VIDEO", "dangerous"},
		{"android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "dangerous"},
		{"android.permission.READ_NEARBY_STREAMING_POLICY", "normal"},
		{"android.permission.READ_PHONE_NUMBERS", "dangerous"},
		{"android.permission.REQUEST_COMPANION_PROFILE_GLASSES", "normal"},
		{"android.permission.REQUEST_COMPANION_PROFILE_WATCH", "normal"},
		{"android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND", "normal"},
		{"android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND", "normal"},
		{"android.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND", "normal"},
		{"android.permission.REQUEST_DELETE_PACKAGES", "normal"},
		{"android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE", "normal"},
		{"android.permission.REQUEST_PASSWORD_COMPLEXITY", "normal"},
		{"android.permission.RUN_USER_INITIATED_JOBS", "normal"},
		{"android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION", "normal"},
		{"android.permission.USE_BIOMETRIC", "normal"},
		{"android.permission.USE_EXACT_ALARM", "normal"},
		{"android.permission.USE_FULL_SCREEN_INTENT", "normal"},
		{"android.permission.UWB_RANGING", "dangerous"},
		/* platform components that are part of this runtime rather than separate packages: the
		 * download provider (DownloadManager; packages/providers/DownloadProvider) */
		{"android.permission.DOWNLOAD_WITHOUT_NOTIFICATION", "normal"},
		/* the car app host (ATLCarHost: the role of the Android Auto / Templates host, which
		 * defines the car app library's permissions) */
		{"androidx.car.app.NAVIGATION_TEMPLATES", "normal"},
		{"androidx.car.app.MAP_TEMPLATES", "normal"},
		{"androidx.car.app.ACCESS_SURFACE", "normal"},
	};

	/*
	 * The permissions of Google Play services, the Google services framework and the Play Store,
	 * which this runtime's stand-ins for those packages (ATLLoadedApp.play_services, gstub) play:
	 * an app's <uses-permission> of them is granted as on a device with those packages.
	 */
	private static final String[][] PLAY_SERVICES_PERMISSIONS = {
		{"com.google.android.c2dm.permission.RECEIVE", "normal"},
		{"com.google.android.c2dm.permission.SEND", "signature"},
		{"com.google.android.gms.permission.AD_ID", "normal"},
		{"com.google.android.providers.gsf.permission.READ_GSERVICES", "normal"},
		{"com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE", "normal"},
	};
	private static final HashMap<String, Integer> sPlayServicesPermissions = new HashMap<>();

	static {
		for (String[] p : NEWER_FRAMEWORK_PERMISSIONS)
			definePlatformPermission(p[0], "dangerous".equals(p[1]) ? PermissionInfo.PROTECTION_DANGEROUS : PermissionInfo.PROTECTION_NORMAL);
		for (String[] p : PLAY_SERVICES_PERMISSIONS)
			sPlayServicesPermissions.put(p[0], "signature".equals(p[1]) ? PermissionInfo.PROTECTION_SIGNATURE : PermissionInfo.PROTECTION_NORMAL);
		android.atl.car.CarServicePermissions.register();
	}

	/**
	 * A permission defined by a platform component other than framework-res (the car service's
	 * android.car.permission.*), with its AOSP protection level. Its defining package is signed
	 * with the platform key.
	 */
	public static void definePlatformPermission(String name, int protectionLevel) {
		synchronized (sLock) {
			sPlatformPermissions.put(name, protectionLevel);
		}
	}

	private static void scanLocked() {
		if (sScanned)
			return;
		sScanned = true;
		scanDir(new File(ATLPaths.system_dir, "priv-app"), true);
		scanDir(new File(ATLPaths.system_dir, "app"), false);
		File[] configs = new File(ATLPaths.system_dir, "etc/permissions").listFiles();
		if (configs != null) {
			Arrays.sort(configs);
			for (File f : configs) {
				if (f.getName().endsWith(".xml"))
					readPermissionsXml(f);
			}
		}
	}

	/* AOSP: each app is a directory holding NAME.apk */
	private static void scanDir(File dir, boolean privileged) {
		File[] apps = dir.listFiles();
		if (apps == null)
			return;
		Arrays.sort(apps);
		for (File appDir : apps) {
			File apk = appDir.isDirectory() ? new File(appDir, appDir.getName() + ".apk") : appDir;
			if (!apk.isFile() || !apk.getName().endsWith(".apk"))
				continue;
			PackageParser parser = new PackageParser(apk.getPath());
			PackageParser.Package pkg = parser.parsePackage(apk, apk.getPath(), new DisplayMetrics(), PackageParser.PARSE_IS_SYSTEM | (privileged ? PackageParser.PARSE_IS_PRIVILEGED : 0));
			if (pkg == null) {
				Slog.w(TAG, "system app " + apk + " does not parse (error " + parser.getParseError() + ")");
				continue;
			}
			if (sSystemApks.containsKey(pkg.packageName)) {
				Slog.w(TAG, "system app " + pkg.packageName + " found twice; keeping " + sSystemApks.get(pkg.packageName));
				continue;
			}
			sSystemApks.put(pkg.packageName, apk);
			if (privileged)
				sPrivileged.add(pkg.packageName);
		}
	}

	/* AOSP SystemConfig.readPermissionsFromXml: the <privapp-permissions> entries */
	private static void readPermissionsXml(File file) {
		try (InputStream in = new FileInputStream(file)) {
			XmlPullParser parser = Xml.newPullParser();
			parser.setInput(in, "UTF-8");
			String pkg = null;
			int type;
			while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
				if (type == XmlPullParser.START_TAG) {
					String tag = parser.getName();
					if ("privapp-permissions".equals(tag)) {
						pkg = parser.getAttributeValue(null, "package");
					} else if ("permission".equals(tag) && pkg != null) {
						String name = parser.getAttributeValue(null, "name");
						if (name != null) {
							Set<String> set = sPrivappAllowlist.get(pkg);
							if (set == null)
								sPrivappAllowlist.put(pkg, set = new HashSet<>());
							set.add(name);
						}
					}
				} else if (type == XmlPullParser.END_TAG && "privapp-permissions".equals(parser.getName())) {
					pkg = null;
				}
			}
		} catch (IOException | XmlPullParserException e) {
			Slog.w(TAG, "could not read " + file, e);
		}
	}

	/**
	 * Whether the package is ATL's stand-in for Google Play services (gstub, or MicroG installed in
	 * its place): its permissions keep the answers of the running app's permission model, which
	 * apps' Play services checks (e.g. c2dm SEND held by the services) were made against.
	 */
	public static boolean isPlayServicesStandIn(String packageName) {
		return ATLLoadedApp.play_services.contains(packageName);
	}

	/** The APK of the system app packageName, or null. */
	public static File getSystemApk(String packageName) {
		synchronized (sLock) {
			scanLocked();
			return sSystemApks.get(packageName);
		}
	}

	/** The package names of the system apps. */
	public static Set<String> getSystemPackages() {
		synchronized (sLock) {
			scanLocked();
			return new HashSet<>(sSystemApks.keySet());
		}
	}

	/**
	 * Applies what the package manager knows about an installed package to its parsed form: its
	 * uid, and for a system app FLAG_SYSTEM (and FLAG_PRIVILEGED in priv-app).
	 */
	public static void applyInstallState(PackageParser.Package pkg) {
		boolean system;
		boolean privileged;
		synchronized (sLock) {
			scanLocked();
			system = sSystemApks.containsKey(pkg.packageName);
			privileged = sPrivileged.contains(pkg.packageName);
		}
		pkg.applicationInfo.uid = uidFor(pkg.packageName);
		if (system)
			pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
		if (privileged)
			pkg.applicationInfo.flags |= ApplicationInfo.FLAG_PRIVILEGED;
	}

	private static File packagesList() {
		return new File(ATLPaths.app_data_dir_base, "packages.list");
	}

	private static void readUidsLocked() {
		if (sUidsRead)
			return;
		sUidsRead = true;
		File f = packagesList();
		if (!f.isFile())
			return;
		try (InputStream in = new FileInputStream(f)) {
			byte[] data = new byte[(int) f.length()];
			int n = 0;
			while (n < data.length) {
				int r = in.read(data, n, data.length - n);
				if (r < 0)
					break;
				n += r;
			}
			for (String line : new String(data, 0, n, StandardCharsets.UTF_8).split("\n")) {
				String[] parts = line.trim().split("\\s+");
				if (parts.length < 2)
					continue;
				try {
					sUids.put(parts[0], Integer.parseInt(parts[1]));
				} catch (NumberFormatException e) {
					Slog.w(TAG, "bad line in " + f + ": " + line);
				}
			}
		} catch (IOException e) {
			Slog.w(TAG, "could not read " + f, e);
		}
	}

	private static void writeUidsLocked() {
		File f = packagesList();
		File tmp = new File(f.getPath() + ".tmp");
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Integer> e : sUids.entrySet())
			sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
		try {
			f.getParentFile().mkdirs();
			try (FileOutputStream out = new FileOutputStream(tmp)) {
				out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
			}
			if (!tmp.renameTo(f))
				Slog.w(TAG, "could not replace " + f);
		} catch (IOException e) {
			Slog.w(TAG, "could not write " + f, e);
		}
	}

	/**
	 * The uid of a package other than the running app and the framework (AOSP Settings: assigned
	 * at install, the lowest free application uid, then kept).
	 */
	public static int uidFor(String packageName) {
		synchronized (sLock) {
			readUidsLocked();
			Integer uid = sUids.get(packageName);
			if (uid != null)
				return uid;
			HashSet<Integer> used = new HashSet<>(sUids.values());
			int next = Process.FIRST_APPLICATION_UID;
			while (used.contains(next) || next == Process.myUid())
				next++;
			sUids.put(packageName, next);
			writeUidsLocked();
			return next;
		}
	}

	/** Whether the uid is one assigned to a package (see {@link #uidFor}). */
	public static boolean isAssignedUid(int uid) {
		synchronized (sLock) {
			return sUids.containsValue(uid);
		}
	}

	/** The packages assigned uid. */
	public static String[] packagesForUid(int uid) {
		synchronized (sLock) {
			readUidsLocked();
			java.util.ArrayList<String> list = new java.util.ArrayList<>();
			for (Map.Entry<String, Integer> e : sUids.entrySet()) {
				if (e.getValue() == uid)
					list.add(e.getKey());
			}
			return list.toArray(new String[0]);
		}
	}

	/** The permission's protection level and whether the platform defines it, or null if unknown. */
	private static Integer protectionLevel(String permission) {
		synchronized (sLock) {
			Integer level = sPlatformPermissions.get(permission);
			if (level != null)
				return level;
		}
		for (PackageParser.Permission p : ATLLoadedApp.getSystemApplication().pkg.permissions) {
			if (permission.equals(p.info.name))
				return p.info.protectionLevel;
		}
		return null;
	}

	private static boolean isPlatformSigned(PackageParser.Package pkg) {
		Signature[] platform = ATLLoadedApp.getSystemApplication().pkg.mSignatures;
		if (platform == null || platform.length == 0 || pkg.mSignatures == null || pkg.mSignatures.length == 0)
			return false;
		return new HashSet<>(Arrays.asList(platform)).equals(new HashSet<>(Arrays.asList(pkg.mSignatures)));
	}

	/**
	 * Whether the package was granted the permission at install (AOSP
	 * PermissionManagerService.restorePermissionState and shouldGrantPermissionBySignature /
	 * grantSignaturePermission): the package must request it and the permission must be defined;
	 * normal: granted; signature: the package is signed with the defining package's (the
	 * platform's) certificate, or the permission is also privileged and the package is a privileged
	 * system app; a privileged app not signed with the platform key needs the permission in its
	 * privapp-permissions allowlist (ro.control_privapp_permissions=enforce). Dangerous permissions
	 * are runtime permissions, not granted at install.
	 */
	public static boolean isGranted(PackageParser.Package pkg, String permission) {
		if (pkg == null || permission == null || !pkg.requestedPermissions.contains(permission))
			return false;
		Integer level = protectionLevel(permission);
		if (level == null)
			return false;
		int base = level & PermissionInfo.PROTECTION_MASK_BASE;
		boolean privilegedPermission = (level & PermissionInfo.PROTECTION_FLAG_SYSTEM) != 0 || base == PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM;
		switch (base) {
			case PermissionInfo.PROTECTION_NORMAL:
				return true;
			case PermissionInfo.PROTECTION_DANGEROUS:
				return false;
			default:
				break;
		}
		boolean privilegedApp = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_PRIVILEGED) != 0;
		boolean platformSigned = isPlatformSigned(pkg);
		if (privilegedPermission && privilegedApp && !platformSigned) {
			Set<String> allowed;
			synchronized (sLock) {
				scanLocked();
				allowed = sPrivappAllowlist.get(pkg.packageName);
			}
			if (allowed == null || !allowed.contains(permission)) {
				Slog.w(TAG, "privileged permission " + permission + " for " + pkg.packageName + " is not in its privapp-permissions allowlist");
				return false;
			}
		}
		if (platformSigned)
			return true;
		return privilegedPermission && privilegedApp;
	}

	/** The permissions the package holds (PackageInfo.requestedPermissionsFlags): the running app's (isGrantedToApp), another's from its install. */
	public static HashSet<String> grantedPermissions(PackageParser.Package pkg) {
		ATLLoadedApp primary = ATLLoadedApp.getPrimaryApplication();
		boolean running = primary != null && primary.pkg != null && primary.pkg.packageName.equals(pkg.packageName);
		HashSet<String> granted = new HashSet<>();
		for (String p : pkg.requestedPermissions) {
			if (running ? isGrantedToApp(pkg, p) : isGranted(pkg, p))
				granted.add(p);
		}
		return granted;
	}

	/** PackageManager.checkPermission for a package other than the running app and the framework. */
	public static int checkPermission(PackageParser.Package pkg, String permission) {
		return isGranted(pkg, permission) ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
	}

	/* ---- the running app ---- */

	private static final String DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";

	/** the protection level of a permission the package defines itself (<permission>), or null */
	private static Integer ownProtectionLevel(PackageParser.Package pkg, String permission) {
		if (pkg.permissions == null)
			return null;
		for (PackageParser.Permission p : pkg.permissions) {
			if (permission.equals(p.info.name))
				return p.info.protectionLevel;
		}
		return null;
	}

	/**
	 * Whether the user granted a runtime (dangerous) permission. This device has no permission
	 * controller to ask: the owner accepted the app's permissions by installing it on the car, so
	 * a runtime permission the app requests counts as granted, except those the host does not let
	 * apps use, which count as denied by the user: location without a location source
	 * (ATL_UGLY_ENABLE_LOCATION or ATL_LOCATION_SOURCE, which LocationManager needs too) and the
	 * microphone without ATL_UGLY_ENABLE_MICROPHONE (AudioRecord refuses to record without it),
	 * unless the user granted a request for it (TlRuntimePermissions: the car page's microphone, for
	 * speech recognition through the page).
	 */
	public static boolean isRuntimePermissionGranted(String permission) {
		switch (permission) {
			case "android.permission.ACCESS_FINE_LOCATION":
			case "android.permission.ACCESS_COARSE_LOCATION":
			case "android.permission.ACCESS_BACKGROUND_LOCATION":
				return System.getenv("ATL_UGLY_ENABLE_LOCATION") != null || System.getenv("ATL_LOCATION_SOURCE") != null;
			case "android.permission.RECORD_AUDIO":
				return System.getenv("ATL_UGLY_ENABLE_MICROPHONE") != null || android.content.pm.TlRuntimePermissions.isGranted(permission);
			default:
				return true;
		}
	}

	/**
	 * Whether the running app holds a permission: exactly what its manifest requests
	 * (requestedPermissions: <uses-permission> within maxSdkVersion, <uses-permission-sdk-23>,
	 * and the implicit and split permissions PackageParser adds for its target SDK), granted by
	 * the permission's protection level as PermissionManagerService grants them: normal ones at
	 * install, dangerous ones by the user's decision (isRuntimePermissionGranted), signature ones
	 * when the app is signed with the certificate of the package defining them (its own
	 * permissions, the platform's when platform-signed), privileged ones only to privileged
	 * apps. A permission nobody defines is held by no one.
	 */
	public static boolean isGrantedToApp(PackageParser.Package pkg, String permission) {
		if (pkg == null || permission == null)
			return false;
		Integer own = ownProtectionLevel(pkg, permission);
		if (!pkg.requestedPermissions.contains(permission)) {
			/* AndroidX ContextCompat.registerReceiver(RECEIVER_NOT_EXPORTED) below API 33 protects
			 * the receiver with <package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, which androidx.core's
			 * manifest defines (signature) for the app; the app's own broadcasts to it carry it */
			return own != null && permission.equals(pkg.packageName + DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION_SUFFIX);
		}
		Integer level = protectionLevel(permission);
		boolean definedByPlatform = level != null;
		if (level == null)
			level = own;
		if (level == null)
			level = sPlayServicesPermissions.get(permission);
		if (level == null)
			return false;
		int base = level & PermissionInfo.PROTECTION_MASK_BASE;
		switch (base) {
			case PermissionInfo.PROTECTION_NORMAL:
				return true;
			case PermissionInfo.PROTECTION_DANGEROUS:
				return isRuntimePermissionGranted(permission);
			default:
				break;
		}
		/* signature (and signature|privileged): the definer's certificate */
		if (!definedByPlatform)
			return own != null; // its own signature permission; the Play services ones belong to Google's key
		if (isPlatformSigned(pkg))
			return true;
		boolean privilegedPermission = (level & PermissionInfo.PROTECTION_FLAG_SYSTEM) != 0 || base == PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM;
		return privilegedPermission && (pkg.applicationInfo.flags & ApplicationInfo.FLAG_PRIVILEGED) != 0;
	}

	/**
	 * Whether a stand-in for a Google package (ATLLoadedApp.play_services) holds a permission: Play
	 * services is a privileged system app signed with Google's key, holding its own permissions and
	 * the platform permissions it needs; the user's runtime decisions apply to it as to any app.
	 */
	public static boolean isGrantedToPlayServicesStandIn(String permission) {
		if (permission == null)
			return false;
		if (sPlayServicesPermissions.containsKey(permission))
			return true;
		Integer level = protectionLevel(permission);
		if (level == null)
			return false;
		if ((level & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS)
			return isRuntimePermissionGranted(permission);
		return true;
	}
}
