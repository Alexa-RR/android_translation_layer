package android.atl.carhost;

import android.os.Binder;
import android.os.Handler;
import android.os.Process;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The car app channel: a WebSocket (RFC 6455) on 127.0.0.1:ATL_CAR_APP_CHANNEL_PORT (default
 * 9995, served as /sockets/carapp) through which a renderer outside this process (the car page)
 * shows the app's car templates and sends the driver's interactions back. The messages are JSON
 * text frames, described in teslalinux's docs/UPSTREAM-NOTES.md ("Car app templates"):
 *
 * <ul>
 * <li>to the renderer: {"type":"surface","width":W,"height":H,"dpi":D} for the map surface the
 *     templates are laid out on, {"type":"icon","id":N,"png":BASE64} for each icon before the first template
 *     that uses it, {"type":"template","seq":N,"template":{...}} (CarModelJson), and state such as
 *     {"type":"dayNight","night":B} (the car's night mode);</li>
 * <li>from the renderer: {"type":"click","cb":ID}, {"type":"selected","cb":ID,"index":I},
 *     {"type":"checked","cb":ID,"checked":B}, {"type":"search","cb":ID,"text":T,"submit":B},
 *     {"type":"back"}; CarAppChannelListener receives them on the host's handler.</li>
 * </ul>
 * A renderer that connects gets the icons and the current template at once.
 */
final class ATLCarAppChannel {
	private static final String TAG = "ATLCarAppChannel";
	private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	interface Listener {
		/** a message from a renderer, as its JSON text (on the host's handler) */
		void onMessage(String json);
	}

	private final int port;
	private final Handler handler;
	private final Listener listener;
	private final ArrayList<Client> clients = new ArrayList<>();
	/* what a new renderer needs: the icons sent so far and the current template */
	private final LinkedHashMap<Integer, String> iconMessages = new LinkedHashMap<>();
	private String templateMessage;

	ATLCarAppChannel(int port, Handler handler, Listener listener) {
		this.port = port;
		this.handler = handler;
		this.listener = listener;
	}

	static int configuredPort() {
		String p = System.getenv("ATL_CAR_APP_CHANNEL_PORT");
		if (p == null || p.isEmpty())
			return 9995;
		try {
			return Integer.parseInt(p);
		} catch (NumberFormatException e) {
			Log.w(TAG, "ATL_CAR_APP_CHANNEL_PORT is not a number: " + p);
			return 9995;
		}
	}

	void start() {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				/* the channel belongs to the host, which acts for the platform */
				Binder.tlEnterParty(Process.SYSTEM_UID);
				serve();
			}
		}, "car-app-channel");
		t.setDaemon(true);
		t.start();
	}

	private void serve() {
		try (ServerSocket server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))) {
			server.setReuseAddress(true);
			Log.i(TAG, "car app channel on 127.0.0.1:" + port);
			while (true) {
				final Socket s = server.accept();
				Thread t = new Thread(new Runnable() {
					@Override
					public void run() {
						handle(s);
					}
				}, "car-app-client");
				t.setDaemon(true);
				t.start();
			}
		} catch (IOException e) {
			Log.e(TAG, "car app channel on port " + port + " stopped", e);
		}
	}

	/** Sends an icon's image to every renderer (and to later ones). */
	void sendIcon(int id, byte[] png) {
		StringBuilder sb = new StringBuilder(png.length * 4 / 3 + 64);
		sb.append("{\"type\":\"icon\",\"id\":").append(id).append(",\"png\":\"").append(Base64.encodeToString(png, Base64.NO_WRAP)).append("\"}");
		String msg = sb.toString();
		synchronized (this) {
			iconMessages.put(id, msg);
		}
		broadcast(msg);
	}

	synchronized boolean hasIcon(int id) {
		return iconMessages.containsKey(id);
	}

	/** Tells the renderers whether the app is navigating (state: new renderers get it too). */
	void sendNavigation(boolean active) {
		String msg = "{\"type\":\"navigation\",\"active\":" + active + "}";
		synchronized (this) {
			navigationMessage = msg;
		}
		broadcast(msg);
	}

	private String navigationMessage;

	/**
	 * Tells the renderers the car's UX restrictions (state: new renderers get it too): whether
	 * they apply, no keyboard input, the longest list a renderer shows, the longest string.
	 */
	void sendRestrictions(boolean active, boolean noKeyboard, int maxItems, int maxString) {
		String msg = "{\"type\":\"restrictions\",\"active\":" + active + ",\"noKeyboard\":" + noKeyboard
		             + ",\"maxItems\":" + maxItems + ",\"maxString\":" + maxString + "}";
		synchronized (this) {
			restrictionsMessage = msg;
		}
		broadcast(msg);
	}

	private String restrictionsMessage;

	/**
	 * Tells the renderers whether the car is in night mode, as the app hears it
	 * (CarContext.isDarkMode); state: new renderers get it too. A renderer can draw its own panels
	 * by day or by night with the app's map.
	 */
	void sendDayNight(boolean night) {
		String msg = "{\"type\":\"dayNight\",\"night\":" + night + "}";
		synchronized (this) {
			dayNightMessage = msg;
		}
		broadcast(msg);
	}

	private String dayNightMessage;

	/** Sends a one-off message (a toast, an alert) to the renderers connected now. */
	void sendEvent(String json) {
		broadcast(json);
	}

	/** Sends the map surface's size and density (the renderer lays the templates out in it). */
	void sendSurface(int width, int height, int dpi) {
		String msg = "{\"type\":\"surface\",\"width\":" + width + ",\"height\":" + height + ",\"dpi\":" + dpi + "}";
		synchronized (this) {
			surfaceMessage = msg;
		}
		broadcast(msg);
	}

	private String surfaceMessage;

	/** Sends the current template to every renderer. */
	void sendTemplate(int seq, String templateJson) {
		String msg = "{\"type\":\"template\",\"seq\":" + seq + ",\"template\":" + templateJson + "}";
		synchronized (this) {
			templateMessage = msg;
		}
		broadcast(msg);
	}

	private void broadcast(String msg) {
		ArrayList<Client> list;
		synchronized (clients) {
			list = new ArrayList<>(clients);
		}
		for (Client c : list)
			c.send(msg);
	}

	private final class Client {
		final Socket socket;
		final OutputStream out;

		Client(Socket socket) throws IOException {
			this.socket = socket;
			this.out = socket.getOutputStream();
		}

		/* one text frame; servers send unmasked frames */
		synchronized void send(String text) {
			byte[] payload = text.getBytes(StandardCharsets.UTF_8);
			try {
				writeFrame(0x1, payload);
			} catch (IOException e) {
				close();
			}
		}

		synchronized void writeFrame(int opcode, byte[] payload) throws IOException {
			ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 10);
			f.write(0x80 | opcode);
			if (payload.length < 126) {
				f.write(payload.length);
			} else if (payload.length < 65536) {
				f.write(126);
				f.write(payload.length >> 8);
				f.write(payload.length & 0xff);
			} else {
				f.write(127);
				long n = payload.length;
				for (int i = 7; i >= 0; i--)
					f.write((int)(n >> (8 * i)) & 0xff);
			}
			f.write(payload);
			out.write(f.toByteArray());
			out.flush();
		}

		void close() {
			synchronized (clients) {
				clients.remove(this);
			}
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void handle(Socket s) {
		Client client = null;
		try {
			s.setTcpNoDelay(true);
			InputStream in = s.getInputStream();
			Map<String, String> headers = readRequest(in);
			String key = headers.get("sec-websocket-key");
			if (key == null || !"websocket".equalsIgnoreCase(headers.get("upgrade"))) {
				s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				s.close();
				return;
			}
			String accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
			s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			client = new Client(s);
			/* the state so far, then live updates */
			ArrayList<String> initial;
			synchronized (this) {
				initial = new ArrayList<>();
				if (surfaceMessage != null)
					initial.add(surfaceMessage);
				if (navigationMessage != null)
					initial.add(navigationMessage);
				if (restrictionsMessage != null)
					initial.add(restrictionsMessage);
				if (dayNightMessage != null)
					initial.add(dayNightMessage);
				initial.addAll(iconMessages.values());
				if (templateMessage != null)
					initial.add(templateMessage);
			}
			synchronized (clients) {
				clients.add(client);
			}
			for (String m : initial)
				client.send(m);
			readFrames(in, client);
		} catch (IOException | NoSuchAlgorithmException e) {
			Log.i(TAG, "renderer gone: " + e);
		} finally {
			if (client != null)
				client.close();
			else
				try {
					s.close();
				} catch (IOException ignored) {
				}
		}
	}

	private static Map<String, String> readRequest(InputStream in) throws IOException {
		LinkedHashMap<String, String> headers = new LinkedHashMap<>();
		StringBuilder line = new StringBuilder();
		boolean first = true;
		int prev = -1;
		while (true) {
			int ch = in.read();
			if (ch < 0)
				throw new IOException("connection closed during the handshake");
			if (ch == '\n' && prev == '\r') {
				line.setLength(line.length() - 1);
				if (line.length() == 0)
					return headers;
				if (!first) {
					int colon = line.indexOf(":");
					if (colon > 0)
						headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
				}
				first = false;
				line.setLength(0);
				if (headers.size() > 64)
					throw new IOException("too many headers");
			} else {
				line.append((char)ch);
				if (line.length() > 8192)
					throw new IOException("header line too long");
			}
			prev = ch;
		}
	}

	private static int readByte(InputStream in) throws IOException {
		int b = in.read();
		if (b < 0)
			throw new IOException("closed");
		return b;
	}

	private void readFrames(InputStream in, Client client) throws IOException {
		ByteArrayOutputStream message = new ByteArrayOutputStream();
		while (true) {
			int b0 = readByte(in);
			int b1 = readByte(in);
			boolean fin = (b0 & 0x80) != 0;
			int opcode = b0 & 0x0f;
			boolean masked = (b1 & 0x80) != 0;
			long len = b1 & 0x7f;
			if (len == 126)
				len = (readByte(in) << 8) | readByte(in);
			else if (len == 127) {
				len = 0;
				for (int i = 0; i < 8; i++)
					len = (len << 8) | readByte(in);
			}
			if (len > (1 << 20))
				throw new IOException("frame too large");
			byte[] mask = new byte[4];
			if (masked)
				for (int i = 0; i < 4; i++)
					mask[i] = (byte)readByte(in);
			byte[] payload = new byte[(int)len];
			int off = 0;
			while (off < payload.length) {
				int r = in.read(payload, off, payload.length - off);
				if (r < 0)
					throw new IOException("closed");
				off += r;
			}
			if (masked)
				for (int i = 0; i < payload.length; i++)
					payload[i] ^= mask[i & 3];
			switch (opcode) {
				case 0x8: /* close */
					client.writeFrame(0x8, new byte[0]);
					return;
				case 0x9: /* ping */
					client.writeFrame(0xA, payload);
					continue;
				case 0xA: /* pong */
					continue;
				case 0x0:
				case 0x1:
					message.write(payload);
					if (fin) {
						final String text = new String(message.toByteArray(), StandardCharsets.UTF_8);
						message.reset();
						handler.post(new Runnable() {
							@Override
							public void run() {
								listener.onMessage(text);
							}
						});
					}
					continue;
				default:
					/* binary frames are not part of this protocol */
					continue;
			}
		}
	}
}
