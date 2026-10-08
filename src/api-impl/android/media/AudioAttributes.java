package android.media;

/* teslalinux: replaces ATL's stub with one that keeps its values (Waze's sound engine reads them back). */
public class AudioAttributes {
	public static final int CONTENT_TYPE_UNKNOWN = 0, CONTENT_TYPE_SPEECH = 1, CONTENT_TYPE_MUSIC = 2, CONTENT_TYPE_MOVIE = 3, CONTENT_TYPE_SONIFICATION = 4;
	public static final int USAGE_UNKNOWN = 0, USAGE_MEDIA = 1, USAGE_VOICE_COMMUNICATION = 2, USAGE_VOICE_COMMUNICATION_SIGNALLING = 3, USAGE_ALARM = 4,
	                        USAGE_NOTIFICATION = 5, USAGE_NOTIFICATION_RINGTONE = 6, USAGE_NOTIFICATION_COMMUNICATION_REQUEST = 7, USAGE_NOTIFICATION_COMMUNICATION_INSTANT = 8,
	                        USAGE_NOTIFICATION_COMMUNICATION_DELAYED = 9, USAGE_NOTIFICATION_EVENT = 10, USAGE_ASSISTANCE_ACCESSIBILITY = 11, USAGE_ASSISTANCE_NAVIGATION_GUIDANCE = 12,
	                        USAGE_ASSISTANCE_SONIFICATION = 13, USAGE_GAME = 14, USAGE_ASSISTANT = 16;
	public static final int FLAG_AUDIBILITY_ENFORCED = 0x1, FLAG_HW_AV_SYNC = 0x10, FLAG_LOW_LATENCY = 0x100;
	public static final int ALLOW_CAPTURE_BY_ALL = 1, ALLOW_CAPTURE_BY_SYSTEM = 2, ALLOW_CAPTURE_BY_NONE = 3;
	public static final int SPATIALIZATION_BEHAVIOR_AUTO = 0, SPATIALIZATION_BEHAVIOR_NEVER = 1;

	int streamType = -1;
	int contentType = CONTENT_TYPE_UNKNOWN, usage = USAGE_UNKNOWN, flags = 0, capturePolicy = ALLOW_CAPTURE_BY_ALL, spatialization = SPATIALIZATION_BEHAVIOR_AUTO;
	boolean hapticMuted = true, contentSpatialized;

	public int getFlags() { return flags; }
	public int getUsage() { return usage; }
	public int getContentType() { return contentType; }
	public int getAllowedCapturePolicy() { return capturePolicy; }
	public int getSpatializationBehavior() { return spatialization; }
	public boolean areHapticChannelsMuted() { return hapticMuted; }
	public boolean isContentSpatialized() { return contentSpatialized; }
	public int getVolumeControlStream() {
		if (streamType >= 0) return streamType;
		switch (usage) {
			case USAGE_VOICE_COMMUNICATION: return 0;
			case USAGE_ALARM: return 4;
			case USAGE_NOTIFICATION_RINGTONE: return 2;
			case USAGE_NOTIFICATION: case USAGE_NOTIFICATION_EVENT: return 5;
			default: return 3;
		}
	}
	@Override public boolean equals(Object o) {
		if (!(o instanceof AudioAttributes)) return false;
		AudioAttributes a = (AudioAttributes) o;
		return a.usage == usage && a.contentType == contentType && a.flags == flags && a.streamType == streamType;
	}
	@Override public int hashCode() { return usage * 31 + contentType * 7 + flags; }
	@Override public String toString() { return "AudioAttributes: usage=" + usage + " content=" + contentType + " flags=0x" + Integer.toHexString(flags); }

	public static class Builder {
		private final AudioAttributes a = new AudioAttributes();
		public Builder() {}
		public Builder(AudioAttributes src) { a.usage = src.usage; a.contentType = src.contentType; a.flags = src.flags; a.streamType = src.streamType; }
		public Builder setContentType(int content_type) { a.contentType = content_type; return this; }
		public Builder setUsage(int usage) { a.usage = usage; return this; }
		public Builder setFlags(int flags) { a.flags |= flags; return this; }
		public Builder setLegacyStreamType(int legacy_stream_type) { a.streamType = legacy_stream_type; return this; }
		public Builder setAllowedCapturePolicy(int policy) { a.capturePolicy = policy; return this; }
		public Builder setHapticChannelsMuted(boolean muted) { a.hapticMuted = muted; return this; }
		public Builder setIsContentSpatialized(boolean v) { a.contentSpatialized = v; return this; }
		public Builder setSpatializationBehavior(int v) { a.spatialization = v; return this; }
		public AudioAttributes build() {
			AudioAttributes r = new AudioAttributes();
			r.usage = a.usage; r.contentType = a.contentType; r.flags = a.flags; r.streamType = a.streamType;
			r.capturePolicy = a.capturePolicy; r.hapticMuted = a.hapticMuted; r.contentSpatialized = a.contentSpatialized; r.spatialization = a.spatialization;
			return r;
		}
	}
}
