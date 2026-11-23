package android.media;

import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The host's audio devices as Android's audio policy reports them: the sound server's outputs and
 * inputs (PulseAudio, or PipeWire's pulse server: android_media_HostAudioDevices.c) as
 * AudioDeviceInfos with AOSP's device types, which of them the app's sound reaches, and the app's
 * AudioDeviceCallbacks told of every device added or removed (a USB sound card plugged in, a loopback
 * started).
 *
 * <p>Types, from what the server says of a device: a car (form factor "car", e.g. a sink whose sound
 * goes to a car's head unit) is TYPE_BUS, as Android Automotive reports its car audio buses (the
 * device's server name is the bus address); a USB card TYPE_USB_HEADSET when it is a headset, else
 * TYPE_USB_DEVICE; Bluetooth TYPE_BLUETOOTH_A2DP / TYPE_BLUETOOTH_SCO; HDMI TYPE_HDMI; headphones
 * and headsets on a jack TYPE_WIRED_HEADPHONES / TYPE_WIRED_HEADSET; a built-in speaker or
 * microphone TYPE_BUILTIN_SPEAKER / TYPE_BUILTIN_MIC; any other analog jack (a line output such as
 * a Raspberry Pi's 3.5 mm socket, which cannot tell whether something is plugged in) TYPE_LINE_ANALOG.
 *
 * <p>Without a sound server (plain ALSA, or while the server restarts), the one device every track
 * plays to is ALSA's default output, reported as the built-in speaker, with a microphone when ALSA
 * lists a capture device (what a device whose HAL describes nothing more reports).
 */
final class HostAudioDevices {
	private static final String TAG = "HostAudioDevices";

	static final class Snapshot {
		final List<AudioDeviceInfo> outputs = new ArrayList<>();
		final List<AudioDeviceInfo> inputs = new ArrayList<>();
		/** the outputs the app's sound reaches, the default one first */
		final List<AudioDeviceInfo> routed = new ArrayList<>();
		AudioDeviceInfo defaultOutput, defaultInput;
	}

	private static final Object sLock = new Object();
	private static Snapshot sCurrent;
	private static long sGeneration;
	private static boolean sStarted;

	/** the device callbacks: AudioManager's registry */
	interface Listener {
		/** routedOutputRemoved: an output the app's sound went to is among the removed */
		void devicesChanged(AudioDeviceInfo[] added, AudioDeviceInfo[] removed, boolean routedOutputRemoved);
	}

	private static Listener sListener;

	static void setListener(Listener l) {
		synchronized (sLock) {
			sListener = l;
		}
	}

	private static native boolean nativeStart(int timeoutMs);

	private static native String[] nativeWait(long seenGeneration);

	/* the formats AudioTrack converts from (the server converts rates and channels: arbitrary rates,
	 * an empty array as in AOSP) */
	private static final int[] OUT_FORMATS = { AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_8BIT,
			AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT };
	private static final int[] IN_FORMATS = { AudioFormat.ENCODING_PCM_16BIT };
	private static final int[] OUT_MASKS = { AudioFormat.CHANNEL_OUT_MONO, AudioFormat.CHANNEL_OUT_STEREO };
	private static final int[] IN_MASKS = { AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO };

	/** The devices now (starts the monitor on first use: at most 2 s for the server's first answer). */
	static Snapshot current() {
		synchronized (sLock) {
			if (!sStarted) {
				sStarted = true;
				boolean natives = true, server = false;
				try {
					server = nativeStart(2000);
				} catch (UnsatisfiedLinkError e) {
					natives = false;
					Log.w(TAG, "no sound server monitor in this build: " + e);
				}
				if (server)
					apply(nativeWait(0));
				else
					sCurrent = alsaOnly();
				/* a server that starts later, or restarts, is followed */
				if (natives) {
					Thread t = new Thread(new Runnable() {
						@Override
						public void run() {
							watch();
						}
					}, "AudioDeviceMonitor");
					t.setDaemon(true);
					t.start();
				}
			}
			return sCurrent;
		}
	}

	private static void watch() {
		while (true) {
			long seen;
			synchronized (sLock) {
				seen = sGeneration;
			}
			String[] s = nativeWait(seen);
			AudioDeviceInfo[][] diff;
			Listener l;
			boolean routedRemoved = false;
			synchronized (sLock) {
				Snapshot before = sCurrent;
				apply(s);
				diff = diff(before, sCurrent);
				l = sListener;
				for (AudioDeviceInfo d : diff[1])
					routedRemoved |= before != null && before.routed.contains(d);
			}
			if (l != null && (diff[0].length > 0 || diff[1].length > 0))
				l.devicesChanged(diff[0], diff[1], routedRemoved);
		}
	}

	/* [added, removed] between two snapshots, by device id */
	private static AudioDeviceInfo[][] diff(Snapshot before, Snapshot after) {
		Map<Integer, AudioDeviceInfo> was = new HashMap<>(), now = new HashMap<>();
		if (before != null) {
			for (AudioDeviceInfo d : before.outputs)
				was.put(d.getId(), d);
			for (AudioDeviceInfo d : before.inputs)
				was.put(d.getId(), d);
		}
		for (AudioDeviceInfo d : after.outputs)
			now.put(d.getId(), d);
		for (AudioDeviceInfo d : after.inputs)
			now.put(d.getId(), d);
		List<AudioDeviceInfo> added = new ArrayList<>(), removed = new ArrayList<>();
		for (Map.Entry<Integer, AudioDeviceInfo> e : now.entrySet())
			if (!e.getValue().equals(was.get(e.getKey())))
				added.add(e.getValue());
		for (Map.Entry<Integer, AudioDeviceInfo> e : was.entrySet())
			if (!e.getValue().equals(now.get(e.getKey())))
				removed.add(e.getValue());
		return new AudioDeviceInfo[][] { added.toArray(new AudioDeviceInfo[0]), removed.toArray(new AudioDeviceInfo[0]) };
	}

	/* sLock held */
	private static void apply(String[] s) {
		String[] head = s[0].split("\t");
		sGeneration = Long.parseLong(head[0]);
		boolean connected = head.length > 1 && "1".equals(head[1]);
		if (!connected) {
			/* no sound server (yet, or it is restarting): ALSA's default output is what plays */
			sCurrent = alsaOnly();
			return;
		}
		Snapshot snap = new Snapshot();
		for (int i = 1; i < s.length; i++) {
			String[] f = s[i].split("\t", -1);
			if (f.length < 11)
				continue;
			boolean sink = "S".equals(f[0]);
			int index = Integer.parseInt(f[1]);
			String name = f[2], description = f[3], bus = f[4], formFactor = f[5], cardName = f[7];
			int portType = parseInt(f[6]);
			String flags = f[10];
			int type = sink ? outputType(bus, formFactor, portType, cardName, name) : inputType(bus, formFactor, portType, cardName);
			/* ids: a port handle, unique and stable while the device stays (the server's index) */
			int id = sink ? 2 * index + 1 : 2 * index + 2;
			String address = (type == AudioDeviceInfo.TYPE_BUS || type == AudioDeviceInfo.TYPE_USB_DEVICE
					|| type == AudioDeviceInfo.TYPE_USB_HEADSET) ? name : "";
			AudioDeviceInfo d = new AudioDeviceInfo(id, type, sink, description, address, new int[0],
					sink ? OUT_MASKS : IN_MASKS, new int[0], sink ? OUT_FORMATS : IN_FORMATS);
			if (sink) {
				snap.outputs.add(d);
				if (flags.indexOf('d') >= 0) {
					snap.defaultOutput = d;
					snap.routed.add(0, d);
				} else if (flags.indexOf('r') >= 0) {
					snap.routed.add(d);
				}
			} else {
				snap.inputs.add(d);
				if (flags.indexOf('d') >= 0)
					snap.defaultInput = d;
			}
		}
		sCurrent = snap;
		if (Log.isLoggable(TAG, Log.INFO)) {
			StringBuilder b = new StringBuilder("devices:");
			for (AudioDeviceInfo d : snap.outputs)
				b.append(" out ").append(d.getId()).append(" type ").append(d.getType()).append(" \"").append(d.getProductName())
						.append(d == snap.defaultOutput ? "\" default" : "\"").append(snap.routed.contains(d) ? " routed" : "").append(';');
			for (AudioDeviceInfo d : snap.inputs)
				b.append(" in ").append(d.getId()).append(" type ").append(d.getType()).append(" \"").append(d.getProductName()).append("\";");
			Log.i(TAG, b.toString());
		}
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/* PulseAudio's pa_device_port_type_t */
	private static final int PORT_AUX = 1, PORT_SPEAKER = 2, PORT_HEADPHONES = 3, PORT_LINE = 4, PORT_MIC = 5, PORT_HEADSET = 6,
			PORT_HANDSET = 7, PORT_EARPIECE = 8, PORT_SPDIF = 9, PORT_HDMI = 10, PORT_USB = 14, PORT_BLUETOOTH = 15,
			PORT_HANDSFREE = 17, PORT_CAR = 18, PORT_ANALOG = 22;

	static int outputType(String bus, String formFactor, int portType, String cardName, String name) {
		if ("car".equals(formFactor) || portType == PORT_CAR)
			return AudioDeviceInfo.TYPE_BUS;
		if ("bluetooth".equals(bus) || portType == PORT_BLUETOOTH)
			return ("hands-free".equals(formFactor) || "headset".equals(formFactor) || portType == PORT_HANDSFREE)
					? AudioDeviceInfo.TYPE_BLUETOOTH_SCO : AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
		if ("usb".equals(bus) || portType == PORT_USB)
			return ("headset".equals(formFactor) || portType == PORT_HEADSET) ? AudioDeviceInfo.TYPE_USB_HEADSET : AudioDeviceInfo.TYPE_USB_DEVICE;
		if (portType == PORT_HDMI || cardName.toLowerCase().contains("hdmi") || name.contains("hdmi"))
			return AudioDeviceInfo.TYPE_HDMI;
		if (portType == PORT_SPDIF)
			return AudioDeviceInfo.TYPE_LINE_DIGITAL;
		/* the port says what is behind it; the card's form factor only when the port does not
		 * ("internal" is a card on the board: a Raspberry Pi's 3.5 mm socket as much as a laptop's
		 * speakers) */
		if (portType == PORT_HEADSET)
			return AudioDeviceInfo.TYPE_WIRED_HEADSET;
		if (portType == PORT_HEADPHONES)
			return AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
		if (portType == PORT_EARPIECE || portType == PORT_HANDSET)
			return AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
		if (portType == PORT_SPEAKER)
			return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
		if (portType == PORT_LINE || portType == PORT_ANALOG)
			return AudioDeviceInfo.TYPE_LINE_ANALOG;
		if (portType == PORT_AUX)
			return AudioDeviceInfo.TYPE_AUX_LINE;
		if ("headset".equals(formFactor))
			return AudioDeviceInfo.TYPE_WIRED_HEADSET;
		if ("headphone".equals(formFactor))
			return AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
		if ("handset".equals(formFactor))
			return AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
		if ("speaker".equals(formFactor) || "internal".equals(formFactor))
			return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
		return AudioDeviceInfo.TYPE_LINE_ANALOG;
	}

	static int inputType(String bus, String formFactor, int portType, String cardName) {
		if ("car".equals(formFactor) || portType == PORT_CAR)
			return AudioDeviceInfo.TYPE_BUS;
		if ("bluetooth".equals(bus) || portType == PORT_BLUETOOTH)
			return AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
		if ("usb".equals(bus) || portType == PORT_USB)
			return ("headset".equals(formFactor) || portType == PORT_HEADSET) ? AudioDeviceInfo.TYPE_USB_HEADSET : AudioDeviceInfo.TYPE_USB_DEVICE;
		if (portType == PORT_HDMI || cardName.toLowerCase().contains("hdmi"))
			return AudioDeviceInfo.TYPE_HDMI;
		if (portType == PORT_HEADSET || (portType == 0 && "headset".equals(formFactor)))
			return AudioDeviceInfo.TYPE_WIRED_HEADSET;
		if (portType == PORT_LINE || portType == PORT_ANALOG)
			return AudioDeviceInfo.TYPE_LINE_ANALOG;
		if (portType == PORT_MIC || "microphone".equals(formFactor) || "internal".equals(formFactor) || "webcam".equals(formFactor))
			return AudioDeviceInfo.TYPE_BUILTIN_MIC;
		return AudioDeviceInfo.TYPE_LINE_ANALOG;
	}

	/* plain ALSA: its default output, and a microphone when ALSA lists a capture device */
	private static Snapshot alsaOnly() {
		Snapshot snap = new Snapshot();
		AudioDeviceInfo out = new AudioDeviceInfo(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, true, "", "",
				new int[0], OUT_MASKS, new int[0], OUT_FORMATS);
		snap.outputs.add(out);
		snap.routed.add(out);
		snap.defaultOutput = out;
		if (alsaHasCapture()) {
			AudioDeviceInfo mic = new AudioDeviceInfo(2, AudioDeviceInfo.TYPE_BUILTIN_MIC, false, "", "bottom",
					new int[0], IN_MASKS, new int[0], IN_FORMATS);
			snap.inputs.add(mic);
			snap.defaultInput = mic;
		}
		return snap;
	}

	private static boolean alsaHasCapture() {
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/asound/pcm"))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.contains("capture"))
					return true;
			}
		} catch (java.io.IOException e) {
		}
		return false;
	}
}
