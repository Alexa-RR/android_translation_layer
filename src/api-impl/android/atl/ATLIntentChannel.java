package android.atl;

import android.app.ContextImpl;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The intent channel: how a program outside the app's process hands the app an intent to start an
 * activity with, as Android's activity manager does for another app's startActivity or for
 * {@code am start -a ACTION -d URI} (ActivityManagerShellCommand). It listens on
 * 127.0.0.1:ATL_INTENT_CHANNEL_PORT (off when unset; teslalinux sets 9993) and takes one request per
 * connection, a line of JSON, and answers with a line of JSON:
 *
 * <pre>
 *   {"type":"startActivity","action":"android.intent.action.VIEW","data":"waze://?q=...&amp;navigate=yes"}
 *   {"ok":true,"activity":"com.waze.MainActivity"}           the intent went to that activity
 *   {"ok":false,"error":"..."}                              no activity of the app takes it, ...
 * </pre>
 *
 * The intent is resolved against the app's own activities' intent filters and started the way
 * {@link Context#startActivity} starts it (an existing singleTask/singleInstance activity gets
 * onNewIntent; FLAG_ACTIVITY_NEW_TASK, as for a start from outside an activity). Only "action",
 * "data" (a URI) and "type" (a MIME type) are taken; extras are not, as {@code am start} takes
 * none without its -e options. While the app runs its car interface (ATL_CAR_HOST=1, started
 * --background) it has no phone activities on screen, so a start is refused: intents for its car
 * session go through the car host (ATLCarHost, the car app channel's "navigate").
 */
public final class ATLIntentChannel {
	private static final String TAG = "ATLIntentChannel";
	/** a request line longer than this is refused */
	private static final int MAX_LINE = 8192;
	/** how long a connection may take to send its request */
	private static final int READ_TIMEOUT_MS = 5000;
	/** how long the answer waits for the app's main thread to take the start */
	private static final long START_WAIT_MS = 5000;

	private static boolean sStarted;

	private ATLIntentChannel() {
	}

	/** the configured port, or 0 when the channel is off */
	static int configuredPort() {
		String p = System.getenv("ATL_INTENT_CHANNEL_PORT");
		if (p == null || p.isEmpty())
			return 0;
		try {
			int port = Integer.parseInt(p);
			return port > 0 && port < 65536 ? port : 0;
		} catch (NumberFormatException e) {
			Log.w(TAG, "ATL_INTENT_CHANNEL_PORT is not a port: " + p);
			return 0;
		}
	}

	/** The app's process is up (its first activity resumed, or started --background): the channel starts, once. */
	public static void onProcessStarted() {
		final int port = configuredPort();
		synchronized (ATLIntentChannel.class) {
			if (sStarted || port == 0)
				return;
			sStarted = true;
		}
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				serve(port);
			}
		}, "ATLIntentChannel");
		t.setDaemon(true);
		t.start();
	}

	private static void serve(int port) {
		ServerSocket server;
		try {
			server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
			server.setReuseAddress(true);
		} catch (IOException e) {
			Log.e(TAG, "cannot listen on 127.0.0.1:" + port, e);
			return;
		}
		Log.i(TAG, "listening on 127.0.0.1:" + port);
		while (true) {
			try (Socket s = server.accept()) {
				s.setSoTimeout(READ_TIMEOUT_MS);
				String answer;
				try {
					answer = handle(readLine(s)).toString();
				} catch (IOException e) {
					answer = error("no request: " + e.getMessage()).toString();
				}
				OutputStream out = s.getOutputStream();
				out.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
			} catch (IOException e) {
				Log.w(TAG, "connection failed", e);
			}
		}
	}

	private static String readLine(Socket s) throws IOException {
		BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder();
		int c;
		while ((c = in.read()) != -1 && c != '\n') {
			if (sb.length() >= MAX_LINE)
				throw new IOException("request longer than " + MAX_LINE + " characters");
			sb.append((char)c);
		}
		return sb.toString();
	}

	private static JSONObject error(String why) {
		JSONObject o = new JSONObject();
		try {
			o.put("ok", false);
			o.put("error", why);
		} catch (JSONException e) {
			/* constant keys */
		}
		return o;
	}

	/** One request: the intent resolved against the app's activities and started on the main thread. */
	static JSONObject handle(String line) {
		JSONObject req;
		try {
			req = new JSONObject(line);
		} catch (JSONException e) {
			return error("the request is not JSON");
		}
		if (!"startActivity".equals(req.optString("type")))
			return error("unknown request type '" + req.optString("type") + "' (startActivity)");
		if (android.atl.carhost.ATLCarHost.isEnabled())
			return error("the app runs its car interface: it shows no phone activities (send the car session's intents through the car host)");
		ATLLoadedApp loaded = ATLLoadedApp.getPrimaryApplication();
		final Context app = loaded != null ? loaded.getApplication() : null;
		if (app == null)
			return error("the app is not running yet");
		String action = req.optString("action", Intent.ACTION_VIEW);
		String data = req.optString("data", "");
		String type = req.has("mimeType") ? req.optString("mimeType") : null;
		final Intent intent = new Intent(action);
		if (!data.isEmpty()) {
			Uri uri = Uri.parse(data);
			if (uri.getScheme() == null)
				return error("the data is not a URI with a scheme: " + data);
			if (type != null)
				intent.setDataAndType(uri, type);
			else
				intent.setData(uri);
		} else if (type != null) {
			intent.setType(type);
		}
		intent.addCategory(Intent.CATEGORY_DEFAULT);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		ContextImpl base = baseOf(app);
		String activity = base != null ? base.tlResolveActivityFor(intent) : null;
		if (activity == null)
			return error("no activity of " + loaded.pkg.packageName + " takes " + intent);
		/* the start runs on the app's main thread, as an activity manager's would */
		final CountDownLatch posted = new CountDownLatch(1);
		final RuntimeException[] failed = new RuntimeException[1];
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				try {
					app.startActivity(intent);
				} catch (RuntimeException e) {
					failed[0] = e;
				}
				posted.countDown();
			}
		});
		try {
			if (!posted.await(START_WAIT_MS, TimeUnit.MILLISECONDS))
				return error("the app's main thread did not take the start within " + START_WAIT_MS + " ms");
		} catch (InterruptedException e) {
			return error("interrupted");
		}
		if (failed[0] != null) {
			Log.w(TAG, "startActivity(" + intent + ") failed", failed[0]);
			return error("the start failed: " + failed[0]);
		}
		Log.i(TAG, "started " + intent + " in " + activity);
		JSONObject ok = new JSONObject();
		try {
			ok.put("ok", true);
			ok.put("activity", activity);
		} catch (JSONException e) {
			/* constant keys */
		}
		return ok;
	}

	private static ContextImpl baseOf(Context c) {
		while (c instanceof ContextWrapper) {
			Context b = ((ContextWrapper)c).getBaseContext();
			if (b == null || b == c)
				break;
			c = b;
		}
		return c instanceof ContextImpl ? (ContextImpl)c : null;
	}
}
