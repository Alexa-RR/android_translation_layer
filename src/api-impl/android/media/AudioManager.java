package android.media;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class AudioManager {
	public static final String PROPERTY_OUTPUT_FRAMES_PER_BUFFER = "android.media.property.OUTPUT_FRAMES_PER_BUFFER";
	public static final String PROPERTY_OUTPUT_SAMPLE_RATE = "android.media.property.OUTPUT_SAMPLE_RATE";

	public static final int STREAM_MUSIC = 0x3;

	private native void nativeSetStreamVolume(int volume);

	public boolean isBluetoothA2dpOn() {
		return false;
	}

	public String getProperty(String name) {
		switch (name) {
			case PROPERTY_OUTPUT_FRAMES_PER_BUFFER:
				return "256"; // FIXME arbitrary
			case PROPERTY_OUTPUT_SAMPLE_RATE:
				return "44100"; // FIXME arbitrary
			default:
				System.out.println("AudioManager.getProperty: >" + name + "< not handled");
				return "";
		}
	}

	public interface OnAudioFocusChangeListener {
	}

	public int getRingerMode() {
		return 0;
	}

	public int getStreamVolume(int streamType) {
		return 0; // arbitrary, shouldn't matter too much?
	}

	public int getStreamMaxVolume(int streamType) {
		return 100;
	}

	public int requestAudioFocus(OnAudioFocusChangeListener listener, int streamType, int durationHint) {
		return /*AUDIOFOCUS_REQUEST_GRANTED*/ 1;
	}

	public int abandonAudioFocus(OnAudioFocusChangeListener listener) {
		return /*AUDIOFOCUS_REQUEST_GRANTED*/ 1;
	}

	public boolean isWiredHeadsetOn() {
		return false;
	}

	public void setStreamVolume(int streamType, int index, int flags) {
		nativeSetStreamVolume(index);
	}

	public boolean isStreamMute(int streamType) {
		return false;
	}

	public boolean isMusicActive() {
		return false;
	}

	public void setSpeakerphoneOn(boolean on) {}

	public boolean isSpeakerphoneOn() {
		return false;
	}

	public void setBluetoothScoOn(boolean on) {}

	public boolean isBluetoothScoOn() {
		return false;
	}

	public void stopBluetoothSco() {}

	public void setMode(int mode) {}

	public int getMode() {
		return /*MODE_NORMAL*/ 0;
	}

	public boolean isMicrophoneMute() {
		return false;
	}

	public void setMicrophoneMute(boolean on) {
		System.out.println("AudioManager.setMicrophoneMute(" + on + ")");
	}
	public void unloadSoundEffects() {}

	public int generateAudioSessionId() {
		return 0;
	}

	/* teslalinux: AudioManager additions (API 26-34) */
	public static final int STREAM_VOICE_CALL = 0, STREAM_SYSTEM = 1, STREAM_RING = 2, STREAM_ALARM = 4, STREAM_NOTIFICATION = 5, STREAM_DTMF = 8, STREAM_ACCESSIBILITY = 10;
	public static final int MODE_INVALID = -2, MODE_CURRENT = -1, MODE_NORMAL = 0, MODE_RINGTONE = 1, MODE_IN_CALL = 2, MODE_IN_COMMUNICATION = 3, MODE_CALL_SCREENING = 4;
	public static final int AUDIOFOCUS_GAIN = 1, AUDIOFOCUS_GAIN_TRANSIENT = 2, AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK = 3, AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE = 4,
	                        AUDIOFOCUS_LOSS = -1, AUDIOFOCUS_LOSS_TRANSIENT = -2, AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3, AUDIOFOCUS_NONE = 0;
	public static final int AUDIOFOCUS_REQUEST_FAILED = 0, AUDIOFOCUS_REQUEST_GRANTED = 1, AUDIOFOCUS_REQUEST_DELAYED = 2;
	public static final int ADJUST_RAISE = 1, ADJUST_LOWER = -1, ADJUST_SAME = 0, ADJUST_MUTE = -100, ADJUST_UNMUTE = 100, ADJUST_TOGGLE_MUTE = 101;
	public static final int FLAG_SHOW_UI = 1, FLAG_ALLOW_RINGER_MODES = 2, FLAG_PLAY_SOUND = 4, FLAG_REMOVE_SOUND_AND_VIBRATE = 8, FLAG_VIBRATE = 16;
	public static final int RINGER_MODE_SILENT = 0, RINGER_MODE_VIBRATE = 1, RINGER_MODE_NORMAL = 2;
	public static final int GET_DEVICES_INPUTS = 1, GET_DEVICES_OUTPUTS = 2, GET_DEVICES_ALL = 3;
	public static final int FX_KEY_CLICK = 0, FX_FOCUS_NAVIGATION_UP = 1, FX_KEYPRESS_STANDARD = 5;
	public static final int SCO_AUDIO_STATE_DISCONNECTED = 0, SCO_AUDIO_STATE_CONNECTED = 1, SCO_AUDIO_STATE_CONNECTING = 2, SCO_AUDIO_STATE_ERROR = -1;
	public static final int PLAYBACK_OFFLOAD_NOT_SUPPORTED = 0, DIRECT_PLAYBACK_NOT_SUPPORTED = 0;
	public static final int AUDIO_SESSION_ID_GENERATE = 0;
	public static final String ACTION_AUDIO_BECOMING_NOISY = "android.media.AUDIO_BECOMING_NOISY", ACTION_HEADSET_PLUG = "android.intent.action.HEADSET_PLUG",
	                           ACTION_SCO_AUDIO_STATE_UPDATED = "android.media.ACTION_SCO_AUDIO_STATE_UPDATED", EXTRA_SCO_AUDIO_STATE = "android.media.extra.SCO_AUDIO_STATE";

	public interface OnModeChangedListener { void onModeChanged(int mode); }
	public interface OnCommunicationDeviceChangedListener { void onCommunicationDeviceChanged(AudioDeviceInfo device); }
	public static abstract class AudioPlaybackCallback { public void onPlaybackConfigChanged(List<Object> configs) {} }
	public static abstract class AudioRecordingCallback { public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {} }

	private static final AudioDeviceInfo TL_SPEAKER = new AudioDeviceInfo(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, true);
	private static final AudioDeviceInfo TL_MIC = new AudioDeviceInfo(2, AudioDeviceInfo.TYPE_BUILTIN_MIC, false);

	public void addOnModeChangedListener(Executor executor, OnModeChangedListener listener) {}
	public void removeOnModeChangedListener(OnModeChangedListener listener) {}
	public void addOnCommunicationDeviceChangedListener(Executor executor, OnCommunicationDeviceChangedListener listener) {}
	public void removeOnCommunicationDeviceChangedListener(OnCommunicationDeviceChangedListener listener) {}
	public int requestAudioFocus(AudioFocusRequest focusRequest) { return AUDIOFOCUS_REQUEST_GRANTED; }
	public int abandonAudioFocusRequest(AudioFocusRequest focusRequest) { return AUDIOFOCUS_REQUEST_GRANTED; }
	public void adjustStreamVolume(int streamType, int direction, int flags) {}
	public void adjustVolume(int direction, int flags) {}
	public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags) {}
	public void clearCommunicationDevice() {}
	public boolean setCommunicationDevice(AudioDeviceInfo device) { return true; }
	public AudioDeviceInfo getCommunicationDevice() { return TL_SPEAKER; }
	public List<AudioDeviceInfo> getAvailableCommunicationDevices() { List<AudioDeviceInfo> l = new ArrayList<>(); l.add(TL_SPEAKER); return l; }
	public List<AudioDeviceInfo> getAudioDevicesForAttributes(AudioAttributes attributes) { List<AudioDeviceInfo> l = new ArrayList<>(); l.add(TL_SPEAKER); return l; }
	public AudioDeviceInfo[] getDevices(int flags) {
		List<AudioDeviceInfo> l = new ArrayList<>();
		if ((flags & GET_DEVICES_OUTPUTS) != 0) l.add(TL_SPEAKER);
		if ((flags & GET_DEVICES_INPUTS) != 0) l.add(TL_MIC);
		return l.toArray(new AudioDeviceInfo[0]);
	}
	public static int getDirectPlaybackSupport(AudioFormat format, AudioAttributes attributes) { return DIRECT_PLAYBACK_NOT_SUPPORTED; }
	public List<Object> getDirectProfilesForAttributes(AudioAttributes attributes) { return new ArrayList<>(); }
	public String getParameters(String keys) { return ""; }
	public void setParameters(String keyValuePairs) {}
	public static int getPlaybackOffloadSupport(AudioFormat format, AudioAttributes attributes) { return PLAYBACK_OFFLOAD_NOT_SUPPORTED; }
	public static boolean isOffloadedPlaybackSupported(AudioFormat format, AudioAttributes attributes) { return false; }
	public Spatializer getSpatializer() { return new Spatializer(); }
	public int getStreamMinVolume(int streamType) { return 0; }
	public float getStreamVolumeDb(int streamType, int index, int deviceType) { return 0f; }
	public boolean isVolumeFixed() { return false; }
	public void playSoundEffect(int effectType) {}
	public void playSoundEffect(int effectType, float volume) {}
	public void loadSoundEffects() {}
	public void registerAudioDeviceCallback(AudioDeviceCallback callback, android.os.Handler handler) {
		if (callback != null) callback.onAudioDevicesAdded(getDevices(GET_DEVICES_ALL));
	}
	public void unregisterAudioDeviceCallback(AudioDeviceCallback callback) {}
	public void registerAudioPlaybackCallback(AudioPlaybackCallback cb, android.os.Handler handler) {}
	public void unregisterAudioPlaybackCallback(AudioPlaybackCallback cb) {}
	public void registerAudioRecordingCallback(AudioRecordingCallback cb, android.os.Handler handler) {}
	public void unregisterAudioRecordingCallback(AudioRecordingCallback cb) {}
	public List<Object> getActivePlaybackConfigurations() { return new ArrayList<>(); }
	public List<AudioRecordingConfiguration> getActiveRecordingConfigurations() { return new ArrayList<>(); }
	public void startBluetoothSco() {}
	public boolean isBluetoothScoAvailableOffCall() { return false; }
	public void setAllowedCapturePolicy(int capturePolicy) {}
	public int getAllowedCapturePolicy() { return AudioAttributes.ALLOW_CAPTURE_BY_ALL; }
	public boolean isCallScreeningModeSupported() { return false; }
	public static boolean isHapticPlaybackSupported() { return false; }
	public boolean isStreamAffectedByRingerMode(int streamType) { return false; }
	public void dispatchMediaKeyEvent(android.view.KeyEvent keyEvent) {}
	public boolean isRampingRingerEnabled() { return false; }
	public int getEncodedSurroundMode() { return 0; }
	public boolean isSurroundFormatEnabled(int format) { return false; }
	public void setStreamMute(int streamType, boolean state) {}
	/** The output device every track is routed to (the host's default ALSA output). */
	public static AudioDeviceInfo tlDefaultOutputDevice() {
		return TL_SPEAKER;
	}
}
