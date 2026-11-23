package android.content.pm;

import android.atl.ATLLoadedApp;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @hide teslalinux: the runtime (dangerous) permissions the user granted to the app through
 * Activity.requestPermissions, kept across starts in the app's data directory as AOSP keeps them in
 * runtime-permissions.xml. There is no permission prompt on this platform; a request is granted when
 * the user already consented to what the permission guards on the car page (requestGrantable).
 */
public final class TlRuntimePermissions {
	private static final String TAG = "TlRuntimePermissions";
	private static Set<String> sGranted;

	private TlRuntimePermissions() {}

	private static File file() {
		String dir = ATLLoadedApp.getPrimaryApplication().pkg.applicationInfo.dataDir;
		return dir != null ? new File(dir, "atl_runtime_permissions") : null;
	}

	private static Set<String> granted() {
		if (sGranted == null) {
			sGranted = new LinkedHashSet<>();
			File f = file();
			if (f != null && f.isFile()) {
				try {
					for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n"))
						if (!line.trim().isEmpty())
							sGranted.add(line.trim());
				} catch (IOException e) {
					Log.w(TAG, "cannot read " + f + ": " + e);
				}
			}
		}
		return sGranted;
	}

	/** Whether the user granted the permission to the app. */
	public static synchronized boolean isGranted(String permission) {
		return granted().contains(permission);
	}

	/** The user grants the permission (AOSP: "Allow" in the permission prompt). */
	public static synchronized void grant(String permission) {
		if (!granted().add(permission))
			return;
		File f = file();
		if (f == null)
			return;
		StringBuilder sb = new StringBuilder();
		for (String p : sGranted)
			sb.append(p).append('\n');
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			Log.w(TAG, "cannot write " + f + ": " + e);
		}
	}

	/**
	 * Whether a request for the permission is granted now, the answer the user gives in AOSP's prompt.
	 * RECORD_AUDIO: the car page's microphone is the only one (speech recognition in the browser, the
	 * platform's recognition service); a page that offers it is one whose user switched voice input
	 * on ("Voice search button"), which is the consent. Without such a page the request is denied.
	 */
	public static boolean requestGrantable(android.content.Context context, String permission) {
		if ("android.permission.RECORD_AUDIO".equals(permission))
			return android.speech.SpeechRecognizer.isRecognitionAvailable(context);
		return false;
	}
}
