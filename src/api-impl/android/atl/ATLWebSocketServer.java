package android.atl;

import android.os.Handler;
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
import java.util.List;
import java.util.Map;

/**
 * teslalinux: a WebSocket server (RFC 6455) on 127.0.0.1 for a page outside this process (the car
 * page, through nginx) that talks to the platform in JSON text frames. Each connection is a
 * {@link Client}; the listener hears connections, messages and disconnections on the given handler
 * (the platform's thread), and may send to a client or to all of them from any thread.
 */
public final class ATLWebSocketServer {
	private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	public interface Listener {
		void onOpen(Client client);

		void onMessage(Client client, String text);

		void onClose(Client client);
	}

	private final String name;
	private final int port;
	private final Handler handler;
	private final Listener listener;
	private final ArrayList<Client> clients = new ArrayList<>();

	public ATLWebSocketServer(String name, int port, Handler handler, Listener listener) {
		this.name = name;
		this.port = port;
		this.handler = handler;
		this.listener = listener;
	}

	/** Listens on 127.0.0.1:port on a thread of its own. */
	public void start() {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				serve();
			}
		}, name);
		t.setDaemon(true);
		t.start();
	}

	public List<Client> clients() {
		synchronized (clients) {
			return new ArrayList<>(clients);
		}
	}

	public void broadcast(String text) {
		for (Client c : clients())
			c.send(text);
	}

	private void serve() {
		try (ServerSocket server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))) {
			server.setReuseAddress(true);
			Log.i(name, "on 127.0.0.1:" + port);
			while (true) {
				final Socket s = server.accept();
				Thread t = new Thread(new Runnable() {
					@Override
					public void run() {
						handle(s);
					}
				}, name + "-client");
				t.setDaemon(true);
				t.start();
			}
		} catch (IOException e) {
			Log.e(name, "server on port " + port + " stopped", e);
		}
	}

	/** One page connected to the server. */
	public final class Client {
		final Socket socket;
		final OutputStream out;
		private boolean closed;

		Client(Socket socket) throws IOException {
			this.socket = socket;
			this.out = socket.getOutputStream();
		}

		/** one text frame (servers send unmasked frames); a client that cannot take it is closed */
		public synchronized void send(String text) {
			if (closed)
				return;
			try {
				writeFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
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

		public void close() {
			boolean was;
			synchronized (this) {
				was = closed;
				closed = true;
			}
			boolean removed;
			synchronized (clients) {
				removed = clients.remove(this);
			}
			try {
				socket.close();
			} catch (IOException ignored) {
			}
			if (!was && removed) {
				final Client self = this;
				handler.post(new Runnable() {
					@Override
					public void run() {
						listener.onClose(self);
					}
				});
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
			synchronized (clients) {
				clients.add(client);
			}
			final Client opened = client;
			handler.post(new Runnable() {
				@Override
				public void run() {
					listener.onOpen(opened);
				}
			});
			readFrames(in, client);
		} catch (IOException | NoSuchAlgorithmException e) {
			Log.i(name, "page gone: " + e);
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

	private void readFrames(InputStream in, final Client client) throws IOException {
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
								listener.onMessage(client, text);
							}
						});
					}
					continue;
				default:
					continue; /* binary frames are not part of these protocols */
			}
		}
	}
}
