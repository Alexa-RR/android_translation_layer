package android.atl;

import dalvik.system.PathClassLoader;
import java.io.File;

/**
 * teslalinux: compiles an app before its first start. ART compiles code when a class loader first
 * opens it (art_standalone runs dex2oat for the loader's class loader context), which takes minutes
 * for a large app on a Raspberry Pi 4. An installer runs this with the runtime options
 * android-translation-layer gives the VM:
 *
 * <pre>
 * dalvikvm -Ximage:BOOT_ART -Xusejit:true -Djava.class.path=API_IMPL_JAR:FRAMEWORK_RES_APK \
 *     android.atl.TlPrewarm APK [APK...]
 * </pre>
 *
 * as the user and with the environment the app runs with (the compiled code goes to that user's
 * cache). The VM opens its class path as ATL's does (api-impl.jar's own code is compiled), then this
 * builds the app's class loader as {@link ATLLoadedApp#loadFromPath} builds it (the
 * ATL_APP_CLASS_OVERRIDES first, then ghax.jar, the APKs in the order given, api-impl's loader as
 * parent), and gstub.jar as ATLGStub loads it: the same dex files in the same contexts, so dex2oat
 * makes exactly what the app's first start would have made, and that start finds it ready. ART
 * keeps one compiled copy per APK, made for one class loader context: the start uses it only when
 * its overrides are the ones given here.
 * Exit status 0 when every APK could be opened.
 */
public final class TlPrewarm {
	private TlPrewarm() {}

	public static void main(String[] args) {
		if (args.length == 0) {
			System.err.println("usage: android.atl.TlPrewarm APK [APK...]");
			System.exit(2);
		}
		StringBuilder path = new StringBuilder();
		for (String apk : args) {
			if (!new File(apk).isFile()) {
				System.err.println("TlPrewarm: no such APK: " + apk);
				System.exit(1);
			}
			if (path.length() > 0)
				path.append(File.pathSeparator);
			path.append(apk);
		}
		String classLoaderPath = path.toString();
		if (ATLToggles.ATL_GHAX)
			classLoaderPath = ATLPaths.ghax_jar.getPath() + File.pathSeparator + classLoaderPath;
		/* the overrides as ATLLoadedApp puts them in front (its quiet variant: android.util.Log has no
		 * native code in this VM) */
		java.util.ArrayList<String> missing = new java.util.ArrayList<>();
		String overrides = ATLLoadedApp.appClassOverrides(missing);
		for (String m : missing)
			System.err.println("TlPrewarm: ATL_APP_CLASS_OVERRIDES: " + m + " is not a file, left out");
		if (overrides != null)
			classLoaderPath = overrides + File.pathSeparator + classLoaderPath;
		long start = System.nanoTime();
		/* opening the dex files is what compiles them (DexPathList, OatFileManager) */
		ClassLoader loader = new PathClassLoader(classLoaderPath, null, TlPrewarm.class.getClassLoader());
		/* the Google services stand-in the runtime loads for apps without them (ATLGStub's loader) */
		if (ATLPaths.gstub_jar.isFile())
			new PathClassLoader(ATLPaths.gstub_jar.getPath(), null, TlPrewarm.class.getClassLoader());
		System.out.println("TlPrewarm: " + classLoaderPath + " ready in " + (System.nanoTime() - start) / 1000000 + " ms (" + loader + ")");
		/* System.exit, not a return: dalvikvm's DestroyJavaVM runs libjavacore's JNI_OnUnload on the
		 * runtime's shutdown thread, where JavaVM::GetEnv fails and it aborts (exit status 134) */
		System.exit(0);
	}
}
