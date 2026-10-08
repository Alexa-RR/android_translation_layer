package android.media;

public final class AudioDeviceInfo {
	public static final int TYPE_UNKNOWN = 0, TYPE_BUILTIN_EARPIECE = 1, TYPE_BUILTIN_SPEAKER = 2, TYPE_WIRED_HEADSET = 3, TYPE_WIRED_HEADPHONES = 4,
	                        TYPE_LINE_ANALOG = 5, TYPE_LINE_DIGITAL = 6, TYPE_BLUETOOTH_SCO = 7, TYPE_BLUETOOTH_A2DP = 8, TYPE_HDMI = 9, TYPE_USB_DEVICE = 11,
	                        TYPE_USB_ACCESSORY = 12, TYPE_DOCK = 13, TYPE_FM = 14, TYPE_BUILTIN_MIC = 15, TYPE_TELEPHONY = 18, TYPE_AUX_LINE = 19, TYPE_IP = 20,
	                        TYPE_BUS = 21, TYPE_USB_HEADSET = 22, TYPE_HEARING_AID = 23, TYPE_BUILTIN_SPEAKER_SAFE = 24, TYPE_REMOTE_SUBMIX = 25, TYPE_BLE_HEADSET = 26,
	                        TYPE_BLE_SPEAKER = 27, TYPE_HDMI_EARC = 29, TYPE_BLE_BROADCAST = 30;
	private final int id, type; private final boolean sink;
	public AudioDeviceInfo(int id, int type, boolean sink) { this.id = id; this.type = type; this.sink = sink; }
	public int getId() { return id; }
	public int getType() { return type; }
	public boolean isSink() { return sink; }
	public boolean isSource() { return !sink; }
	public CharSequence getProductName() { return "atl"; }
	public String getAddress() { return ""; }
	public int[] getSampleRates() { return new int[] { 48000 }; }
	public int[] getChannelCounts() { return new int[] { 2 }; }
	public int[] getChannelMasks() { return new int[] { 12 }; }
	public int[] getEncodings() { return new int[] { 2 }; }
	public java.util.List<AudioProfile> getAudioProfiles() { return new java.util.ArrayList<>(); }
	public java.util.List<AudioDescriptor> getAudioDescriptors() { return new java.util.ArrayList<>(); }
	public int getInternalType() { return type; }
	@Override public boolean equals(Object o) { return o instanceof AudioDeviceInfo && ((AudioDeviceInfo) o).id == id; }
	@Override public int hashCode() { return id; }

	public static final class AudioProfile {
		public int getFormat() { return 2; }
		public int[] getSampleRates() { return new int[] { 48000 }; }
		public int[] getChannelMasks() { return new int[] { 12 }; }
		public int[] getChannelIndexMasks() { return new int[0]; }
		public int getEncapsulationType() { return 0; }
	}
	public static final class AudioDescriptor {
		public int getStandard() { return 0; }
		public byte[] getDescriptor() { return new byte[0]; }
		public int getEncapsulationType() { return 0; }
	}
}
