package android.speech;

import android.atl.ATLWebSocketServer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * teslalinux: speech recognition done by the car page. This device has no microphone and no
 * recognition service of its own; the driver's browser has both (the Web Speech API). The page
 * connects to a WebSocket on 127.0.0.1:ATL_SPEECH_CHANNEL_PORT (served as /sockets/speech; unset or
 * 0: no channel, and recognition is unavailable as on a device without a service). JSON text
 * frames, described in teslalinux's docs/UPSTREAM-NOTES.md ("Speech recognition"):
 *
 * <ul>
 * <li>from the page: {"type":"hello","recognition":B} whether it can recognise speech;
 *     per session N: {"type":"ready","id":N} (listening), {"type":"speechStart","id":N},
 *     {"type":"speechEnd","id":N}, {"type":"partial","id":N,"texts":[...]},
 *     {"type":"results","id":N,"texts":[...],"confidences":[...]},
 *     {"type":"error","id":N,"error":E} with the Web Speech API's error names;</li>
 * <li>to the page: {"type":"listen","id":N,"lang":TAG,"partial":B,"maxResults":M},
 *     {"type":"stop","id":N} (end the audio, deliver what was heard), {"type":"cancel","id":N}.</li>
 * </ul>
 * The newest page that can recognise speech gets the sessions. Events arrive on the main thread.
 */
final class TlSpeechChannel implements ATLWebSocketServer.Listener {
	private static final String TAG = "TlSpeechChannel";

	interface Session {
		void onReady();

		void onSpeechStart();

		void onSpeechEnd();

		void onPartial(ArrayList<String> texts);

		void onResults(ArrayList<String> texts, float[] confidences);

		void onError(int error);
	}

	private static TlSpeechChannel sInstance;
	private static boolean sTried;

	private final ATLWebSocketServer server;
	/* pages that can recognise speech, oldest first */
	private final LinkedHashSet<ATLWebSocketServer.Client> recognizers = new LinkedHashSet<>();
	private final SparseArray<Session> sessions = new SparseArray<>();
	private final SparseArray<ATLWebSocketServer.Client> sessionPages = new SparseArray<>();
	private int nextId = 1;

	private TlSpeechChannel(int port) {
		server = new ATLWebSocketServer("speech-channel", port, new Handler(Looper.getMainLooper()), this);
	}

	/** The channel, started on first use; null when ATL_SPEECH_CHANNEL_PORT is unset or 0. */
	static synchronized TlSpeechChannel get() {
		if (!sTried) {
			sTried = true;
			int port = configuredPort();
			if (port > 0) {
				sInstance = new TlSpeechChannel(port);
				sInstance.server.start();
			}
		}
		return sInstance;
	}

	static int configuredPort() {
		String p = System.getenv("ATL_SPEECH_CHANNEL_PORT");
		if (p == null || p.isEmpty())
			return 0;
		try {
			return Integer.parseInt(p);
		} catch (NumberFormatException e) {
			Log.w(TAG, "ATL_SPEECH_CHANNEL_PORT is not a number: " + p);
			return 0;
		}
	}

	/** main thread: whether a page that can recognise speech is connected */
	boolean available() {
		return !recognizers.isEmpty();
	}

	/** main thread: starts a session on the newest capable page; its id, or 0 when there is none */
	int listen(String lang, boolean partial, int maxResults, Session session) {
		ATLWebSocketServer.Client page = null;
		for (ATLWebSocketServer.Client c : recognizers)
			page = c;
		if (page == null)
			return 0;
		int id = nextId++;
		sessions.put(id, session);
		sessionPages.put(id, page);
		try {
			JSONObject m = new JSONObject();
			m.put("type", "listen");
			m.put("id", id);
			m.put("lang", lang);
			m.put("partial", partial);
			m.put("maxResults", maxResults);
			page.send(m.toString());
		} catch (JSONException e) {
			throw new IllegalStateException(e);
		}
		return id;
	}

	/** main thread: the end of the audio; the page delivers what it heard */
	void stop(int id) {
		ATLWebSocketServer.Client page = sessionPages.get(id);
		if (page != null)
			page.send("{\"type\":\"stop\",\"id\":" + id + "}");
	}

	/** main thread: the session ends without results */
	void cancel(int id) {
		ATLWebSocketServer.Client page = sessionPages.get(id);
		end(id);
		if (page != null)
			page.send("{\"type\":\"cancel\",\"id\":" + id + "}");
	}

	private void end(int id) {
		sessions.remove(id);
		sessionPages.remove(id);
	}

	@Override
	public void onOpen(ATLWebSocketServer.Client client) {
	}

	@Override
	public void onClose(ATLWebSocketServer.Client client) {
		recognizers.remove(client);
		/* its sessions end: the recognition service went away (AOSP ERROR_SERVER_DISCONNECTED) */
		for (int i = sessionPages.size() - 1; i >= 0; i--) {
			if (sessionPages.valueAt(i) == client) {
				int id = sessionPages.keyAt(i);
				Session s = sessions.get(id);
				end(id);
				if (s != null)
					s.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
			}
		}
	}

	@Override
	public void onMessage(ATLWebSocketServer.Client client, String text) {
		JSONObject m;
		try {
			m = new JSONObject(text);
		} catch (JSONException e) {
			Log.w(TAG, "not JSON: " + text);
			return;
		}
		String type = m.optString("type");
		if ("hello".equals(type)) {
			recognizers.remove(client);
			if (m.optBoolean("recognition"))
				recognizers.add(client); /* the newest one last */
			return;
		}
		int id = m.optInt("id");
		Session s = sessions.get(id);
		if (s == null || sessionPages.get(id) != client)
			return; /* a cancelled or finished session */
		switch (type) {
			case "ready":
				s.onReady();
				break;
			case "speechStart":
				s.onSpeechStart();
				break;
			case "speechEnd":
				s.onSpeechEnd();
				break;
			case "partial":
				s.onPartial(texts(m));
				break;
			case "results": {
				ArrayList<String> texts = texts(m);
				JSONArray c = m.optJSONArray("confidences");
				float[] conf = new float[texts.size()];
				for (int i = 0; i < conf.length; i++)
					conf[i] = c != null ? (float)c.optDouble(i, -1) : -1f; /* AOSP: -1 when unknown */
				end(id);
				s.onResults(texts, conf);
				break;
			}
			case "error":
				end(id);
				s.onError(errorCode(m.optString("error")));
				break;
		}
	}

	private static ArrayList<String> texts(JSONObject m) {
		ArrayList<String> out = new ArrayList<>();
		JSONArray a = m.optJSONArray("texts");
		if (a != null)
			for (int i = 0; i < a.length(); i++)
				out.add(a.optString(i));
		return out;
	}

	/** the Web Speech API's SpeechRecognitionErrorEvent.error as SpeechRecognizer's error */
	static int errorCode(String error) {
		switch (error) {
			case "no-speech":
				return SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
			case "no-match":
				return SpeechRecognizer.ERROR_NO_MATCH;
			case "audio-capture":
				return SpeechRecognizer.ERROR_AUDIO;
			case "network":
				return SpeechRecognizer.ERROR_NETWORK;
			case "not-allowed":
			case "service-not-allowed":
				return SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
			case "language-not-supported":
				return SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED;
			case "busy":
				return SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
			default: /* "aborted", "bad-grammar" and anything new */
				return SpeechRecognizer.ERROR_CLIENT;
		}
	}
}
