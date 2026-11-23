/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.usage;

import android.app.AppOpsManager;
import android.atl.ATLLoadedApp;
import android.atl.ATLPackageSettings;
import android.atl.ATLPaths;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * AOSP StorageStatsManager with the measuring half of StorageStatsService and installd
 * (calculate_tree_size per directory, as installd does on a device without quota support):
 *
 * <ul>
 * <li>app bytes: the APKs (base and splits), the unpacked native libraries, the dexopt output
 *     next to the APKs (oat/&lt;isa&gt;/, where teslalinux-dexopt and Android put it), ART's own
 *     compiled copies of those APKs in its cache, dex metadata, and the OBB directory;</li>
 * <li>data bytes: the data directory, cache, code cache and external files (each directory counted
 *     once: on this runtime external storage lives inside the app's data directory);</li>
 * <li>cache bytes: the cache and code cache directories.</li>
 * </ul>
 * Queries about another app or the whole user need PACKAGE_USAGE_STATS (the GET_USAGE_STATS app
 * op), as on AOSP; an app may always query itself.
 */
public class StorageStatsManager {
	private final Context mContext;

	/** AOSP (hidden): ART's isa directory names. */
	private static final String[] ISAS = {"arm64", "arm", "x86_64", "x86", "riscv64"};

	public StorageStatsManager() {
		this(null);
	}

	/** {@hide} */
	public StorageStatsManager(Context context) {
		mContext = context;
	}

	/* ---- volumes: one private internal volume, StorageManager.UUID_DEFAULT ---- */

	private static void checkVolume(UUID storageUuid) throws IOException {
		if (!StorageManager.UUID_DEFAULT.equals(storageUuid))
			throw new IOException("Failed to find storage device for UUID " + storageUuid);
	}

	/** the filesystem holding app data (AOSP: /data) */
	private static File dataVolume() {
		return ATLPaths.app_data_dir_base.exists() ? ATLPaths.app_data_dir_base : new File("/");
	}

	/** AOSP: whether the volume supports quota-based (fast) measurement; installd here walks the trees. */
	public boolean isQuotaSupported(UUID storageUuid) {
		return false;
	}

	/** AOSP: whether the volume reserves space for the system; this host's filesystems do not. */
	public boolean isReservedSupported(UUID storageUuid) {
		return false;
	}

	/** AOSP FileUtils.roundStorageSize: the marketed size of a storage device of this size. */
	static long roundStorageSize(long size) {
		long val = 1;
		long pow = 1;
		while ((val * pow) < size) {
			val <<= 1;
			if (val > 512) {
				val = 1;
				pow *= 1000;
			}
		}
		return val * pow;
	}

	/** AOSP: the total size of the volume, rounded like the storage device's marketed size. */
	public long getTotalBytes(UUID storageUuid) throws IOException {
		checkVolume(storageUuid);
		return roundStorageSize(dataVolume().getTotalSpace());
	}

	/** AOSP: the space available to apps on the volume (no clearable cache quotas here). */
	public long getFreeBytes(UUID storageUuid) throws IOException {
		checkVolume(storageUuid);
		return dataVolume().getUsableSpace();
	}

	/** {@hide} AOSP: the cache quota of a uid; without quota support the whole cache may grow. */
	public long getCacheQuotaBytes(String volumeUuid, int uid) {
		return dataVolume().getUsableSpace();
	}

	/* ---- permissions (AOSP StorageStatsService.enforceStatsPermission) ---- */

	private Context context() {
		return mContext;
	}

	private void enforceStatsPermission() {
		int uid = Binder.getCallingUid();
		if (uid == Process.SYSTEM_UID || uid == 0)
			return;
		String pkg = ownPackageName();
		int mode = AppOpsManager.MODE_DEFAULT;
		Context c = context();
		if (c != null) {
			AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
			if (ops != null)
				mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, pkg);
		}
		boolean granted;
		if (mode == AppOpsManager.MODE_DEFAULT)
			granted = c != null && c.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED;
		else
			granted = mode == AppOpsManager.MODE_ALLOWED;
		if (!granted)
			throw new SecurityException("Caller does not have " + android.Manifest.permission.PACKAGE_USAGE_STATS + "; this is required for querying storage stats of other apps or the user");
	}

	private static String ownPackageName() {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		return app != null && app.pkg != null ? app.pkg.packageName : null;
	}

	/* ---- packages ---- */

	private ApplicationInfo applicationInfo(String packageName) throws PackageManager.NameNotFoundException {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		if (app != null && app.pkg != null && packageName.equals(app.pkg.packageName))
			return app.pkg.applicationInfo;
		PackageManager pm = context() != null ? context().getPackageManager() : new PackageManager();
		return pm.getApplicationInfo(packageName, 0);
	}

	/** the packages measured for a uid: the app itself, the system apps of that uid */
	private List<String> packagesOf(int uid) {
		List<String> out = new ArrayList<>();
		String own = ownPackageName();
		if (uid == Process.myUid() && own != null)
			out.add(own);
		for (String p : ATLPackageSettings.packagesForUid(uid)) {
			if (!out.contains(p))
				out.add(p);
		}
		return out;
	}

	public StorageStats queryStatsForPackage(UUID storageUuid, String packageName, UserHandle user) throws PackageManager.NameNotFoundException, IOException {
		checkVolume(storageUuid);
		if (packageName == null)
			throw new NullPointerException("packageName");
		ApplicationInfo info = applicationInfo(packageName);
		if (info.uid != Binder.getCallingUid())
			enforceStatsPermission();
		return measure(packageName, info);
	}

	public StorageStats queryStatsForUid(UUID storageUuid, int uid) throws IOException {
		checkVolume(storageUuid);
		if (uid != Binder.getCallingUid())
			enforceStatsPermission();
		StorageStats total = new StorageStats();
		for (String p : packagesOf(uid)) {
			try {
				total.add(measure(p, applicationInfo(p)));
			} catch (PackageManager.NameNotFoundException e) {
				// AOSP: a uid's package that went away meanwhile is not counted
			}
		}
		return total;
	}

	/** AOSP: the user's totals (all apps' code, data and cache); the per-type app bytes are not collected. */
	public StorageStats queryStatsForUser(UUID storageUuid, UserHandle user) throws IOException {
		checkVolume(storageUuid);
		enforceStatsPermission();
		StorageStats total = new StorageStats();
		Set<String> all = new HashSet<>();
		String own = ownPackageName();
		if (own != null)
			all.add(own);
		all.addAll(ATLPackageSettings.getSystemPackages());
		for (String p : all) {
			try {
				StorageStats s = measure(p, applicationInfo(p));
				total.codeBytes += s.codeBytes;
				total.dataBytes += s.dataBytes;
				total.cacheBytes += s.cacheBytes;
				total.externalCacheBytes += s.externalCacheBytes;
			} catch (PackageManager.NameNotFoundException e) {
			}
		}
		return total;
	}

	/** AOSP: the shared storage of the user, by media type. */
	public ExternalStorageStats queryExternalStatsForUser(UUID storageUuid, UserHandle user) throws IOException {
		checkVolume(storageUuid);
		enforceStatsPermission();
		ExternalStorageStats stats = new ExternalStorageStats();
		File root = android.os.Environment.getExternalStorageDirectory();
		walkExternal(root, root, stats);
		return stats;
	}

	private static void walkExternal(File root, File f, ExternalStorageStats stats) {
		File[] children = f.listFiles();
		if (children == null)
			return;
		for (File c : children) {
			if (isSymlink(c))
				continue;
			if (c.isDirectory()) {
				walkExternal(root, c, stats);
				continue;
			}
			long len = c.length();
			stats.totalBytes += len;
			String rel = root.toURI().relativize(c.toURI()).getPath();
			if (rel.startsWith("Android/")) {
				stats.appBytes += len;
				if (rel.startsWith("Android/obb/"))
					stats.obbBytes += len;
				continue;
			}
			String name = c.getName();
			int dot = name.lastIndexOf('.');
			String mime = dot >= 0 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
			if (mime == null)
				continue;
			if (mime.startsWith("audio/"))
				stats.audioBytes += len;
			else if (mime.startsWith("video/"))
				stats.videoBytes += len;
			else if (mime.startsWith("image/"))
				stats.imageBytes += len;
		}
	}

	/* ---- measuring (installd calculate_tree_size) ---- */

	private static boolean isSymlink(File f) {
		try {
			return !f.getCanonicalPath().equals(f.getAbsoluteFile().getParentFile().getCanonicalPath() + File.separator + f.getName());
		} catch (IOException e) {
			return true;
		}
	}

	/** bytes of the files under f (f itself if a file); symbolic links are not followed, each file counts once */
	private static long treeSize(File f, Set<String> seen) {
		if (f == null || !f.exists())
			return 0;
		String key;
		try {
			key = f.getCanonicalPath();
		} catch (IOException e) {
			return 0;
		}
		if (!seen.add(key))
			return 0;
		if (f.isFile())
			return f.length();
		long total = 0;
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) {
				if (isSymlink(c))
					continue;
				total += treeSize(c, seen);
			}
		}
		return total;
	}

	private static String artCacheDir() {
		String xdg = System.getenv("XDG_CACHE_HOME");
		if (xdg != null && !xdg.isEmpty())
			return xdg + "/art";
		return System.getenv("HOME") + "/.cache/art";
	}

	/** ART's compiled copies of an APK: <apk dir>/oat/<isa>/<name>.{odex,vdex,art} and its cache's <path@>@classes.{dex,vdex,art} */
	private static long dexoptBytes(String apk, Set<String> seen) {
		File f = new File(apk);
		String name = f.getName();
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String cacheName = (apk.startsWith("/") ? apk.substring(1) : apk).replace('/', '@') + "@classes";
		List<File> caches = new ArrayList<>();
		caches.add(new File(artCacheDir()));
		String androidData = System.getenv("ANDROID_DATA");
		if (androidData != null && !androidData.isEmpty())
			caches.add(new File(androidData, "dalvik-cache"));
		long total = 0;
		for (String isa : ISAS) {
			File oat = new File(f.getParentFile(), "oat/" + isa);
			for (String ext : new String[] {".odex", ".vdex", ".art"})
				total += treeSize(new File(oat, base + ext), seen);
			for (File cache : caches) {
				for (String ext : new String[] {".dex", ".vdex", ".art", ".oat"})
					total += treeSize(new File(cache, isa + "/" + cacheName + ext), seen);
			}
		}
		return total;
	}

	private StorageStats measure(String packageName, ApplicationInfo info) {
		StorageStats s = new StorageStats();
		Set<String> seen = new HashSet<>();
		List<String> apks = new ArrayList<>();
		if (info.sourceDir != null)
			apks.add(info.sourceDir);
		if (info.splitSourceDirs != null) {
			for (String split : info.splitSourceDirs) {
				if (split != null && !apks.contains(split))
					apks.add(split);
			}
		}
		for (String apk : apks) {
			s.apkBytes += treeSize(new File(apk), seen);
			String dm = apk.endsWith(".apk") ? apk.substring(0, apk.length() - 4) + ".dm" : apk + ".dm";
			s.dmBytes += treeSize(new File(dm), seen);
		}
		for (String apk : apks)
			s.dexoptBytes += dexoptBytes(apk, seen);
		if (info.nativeLibraryDir != null)
			s.libBytes += treeSize(new File(info.nativeLibraryDir), seen);

		/* the package's directories: the app's own context knows them, other packages use the
		 * runtime's layout (main.c: <app data base>/<package>_/, ContextImpl: /tmp/atl_cache/<package>) */
		File dataDir, cacheDir, codeCacheDir, externalFiles, externalCache, obbDir;
		Context c = context();
		if (c != null && packageName.equals(c.getPackageName())) {
			dataDir = c.getDataDir();
			cacheDir = c.getCacheDir();
			codeCacheDir = c.getCodeCacheDir();
			externalFiles = c.getExternalFilesDir(null);
			externalCache = c.getExternalCacheDir();
			obbDir = c.getObbDir();
		} else {
			dataDir = info.dataDir != null ? new File(info.dataDir) : new File(ATLPaths.app_data_dir_base, packageName + "_");
			cacheDir = new File("/tmp/atl_cache/" + packageName);
			codeCacheDir = new File(cacheDir, "code_cache");
			externalFiles = new File(dataDir, "files");
			externalCache = cacheDir;
			obbDir = new File(dataDir, "Android/obb/" + packageName);
		}

		/* code: the OBB files are counted as the app (AOSP), not as its data */
		long obb = treeSize(obbDir, seen);
		s.codeBytes = s.apkBytes + s.dmBytes + s.dexoptBytes + s.libBytes + obb;

		/* cache first, so that a cache inside the data directory counts as cache and once */
		long cache = treeSize(cacheDir, seen) + treeSize(codeCacheDir, seen);
		long extCache = treeSize(externalCache, seen);
		s.externalCacheBytes = extCache;
		s.cacheBytes = cache + extCache;
		s.dataBytes = s.cacheBytes + treeSize(dataDir, seen) + treeSize(externalFiles, seen);
		return s;
	}
}
