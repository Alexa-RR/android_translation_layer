package android.media;
import java.util.concurrent.Executor;

public class AudioRecord {

	public static final int RECORDSTATE_STOPPED = 1;
	public static final int RECORDSTATE_RECORDING = 3;

	public static final int ERROR_BAD_VALUE = -2;

	private long pcm_handle;
	private int channels; // set by native constructor
	private int recordingState = RECORDSTATE_STOPPED;
	private int sampleRateInHz;

	private native long native_constructor(int streamType, int sampleRateInHz, int num_channels, int audioFormat, int bufferSizeInBytes);
	private native void native_record(long pcm_handle);
	private native void native_stop(long pcm_handle);
	private native int native_read(long pcm_handle, short[] audioData, int offsetInShorts, int framesToWrite);
	private native void native_release(long pcm_handle);

	public AudioRecord(int streamType, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes) {
		this.sampleRateInHz = sampleRateInHz;
		pcm_handle = native_constructor(streamType, sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes);
		tlChannelMask = channelConfig;
		tlAudioFormat = audioFormat;
		tlBufferSizeInBytes = bufferSizeInBytes;
	}

	public static native int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat);

	public int getSampleRate() {
		return sampleRateInHz;
	}

	public int getState() {
		return /*STATE_INITIALIZED*/ 1;
	}

	public int getRecordingState() {
		return recordingState;
	}

	public void startRecording() {
		native_record(pcm_handle);
		recordingState = RECORDSTATE_RECORDING;
	}

	public int read(short[] audioData, int offsetInShorts, int sizeInShorts) {
		/* sanity check the parameters before calling native_write */
		if ((audioData == null)
		    || (offsetInShorts < 0) || (sizeInShorts < 0)
		    || (offsetInShorts + sizeInShorts < 0)
		    || (offsetInShorts + sizeInShorts > audioData.length)) {
			return ERROR_BAD_VALUE;
		}

		return native_read(pcm_handle, audioData, offsetInShorts, sizeInShorts / channels) * channels;
	}

	public void stop() {
		native_stop(pcm_handle);
		recordingState = RECORDSTATE_STOPPED;
	}

	public void release() {
		native_release(pcm_handle);
		pcm_handle = 0;
	}

	public static class Builder {

		private int audioSource;
		private AudioFormat audioFormat;

		public Builder setAudioSource(int audioSource) {
			this.audioSource = audioSource;
			return this;
		}

		public Builder setAudioFormat(AudioFormat audioFormat) {
			this.audioFormat = audioFormat;
			return this;
		}

		public AudioRecord build() {
			return new AudioRecord(audioSource, audioFormat.getSampleRate(), audioFormat.getChannelMask(), audioFormat.getEncoding(), 32768);
		}
	
		public Builder setBufferSizeInBytes(int bufferSizeInBytes) { return this; }
		public Builder setContext(android.content.Context context) { return this; }
		public Builder setPrivacySensitive(boolean privacySensitive) { return this; }
}

	/* teslalinux: AudioRecord additions. The recorder state the constructor records (repl) backs the getters. */
	private int tlAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
	private int tlChannelMask;
	private int tlBufferSizeInBytes;
	private int tlSessionId;
	private AudioDeviceInfo tlPreferredDevice;
	private final java.util.concurrent.CopyOnWriteArrayList<AudioManager.AudioRecordingCallback> tlRecordingCallbacks = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** AOSP: the session the recorder was created in, allocated like AudioFlinger's unique ids. */
	public int getAudioSessionId() {
		synchronized (this) {
			if (tlSessionId == 0)
				tlSessionId = AudioSystem.newAudioSessionId();
			return tlSessionId;
		}
	}

	/** AOSP returns ERROR_INVALID_OPERATION when no capture timestamp is available; the ALSA capture path reports none. */
	public int getTimestamp(AudioTimestamp outTimestamp, int timebase) {
		if (outTimestamp == null || (timebase != AudioTimestamp.TIMEBASE_BOOTTIME && timebase != AudioTimestamp.TIMEBASE_MONOTONIC))
			throw new IllegalArgumentException();
		return -3; // ERROR_INVALID_OPERATION
	}

	public void registerAudioRecordingCallback(Executor executor, AudioManager.AudioRecordingCallback cb) {
		if (cb == null)
			throw new IllegalArgumentException("Illegal null AudioRecordingCallback");
		if (executor == null)
			throw new IllegalArgumentException("Illegal null Executor");
		if (!tlRecordingCallbacks.contains(cb))
			tlRecordingCallbacks.add(cb);
	}

	public void unregisterAudioRecordingCallback(AudioManager.AudioRecordingCallback cb) {
		if (cb == null)
			throw new IllegalArgumentException("Illegal null AudioRecordingCallback argument");
		tlRecordingCallbacks.remove(cb);
	}

	/** AOSP: null while the recorder is not recording; the recording registry has no entry for ALSA capture. */
	public AudioRecordingConfiguration getActiveRecordingConfiguration() {
		return null;
	}

	public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
		if (deviceInfo != null && !deviceInfo.isSource())
			return false;
		tlPreferredDevice = deviceInfo;
		return true;
	}

	public AudioDeviceInfo getPreferredDevice() {
		return tlPreferredDevice;
	}

	/** AOSP: the input device while recording, else null. */
	public AudioDeviceInfo getRoutedDevice() {
		return getRecordingState() == RECORDSTATE_RECORDING ? AudioManager.tlDefaultInputDevice() : null;
	}

	public int getChannelCount() {
		return channels > 0 ? channels : Math.max(1, Integer.bitCount(tlChannelMask));
	}

	public int getChannelConfiguration() {
		return tlChannelMask;
	}

	public int getAudioFormat() {
		return tlAudioFormat;
	}

	public int getBufferSizeInFrames() {
		int frameBytes = getChannelCount() * AudioFormat.getBytesPerSample(tlAudioFormat);
		return frameBytes > 0 ? tlBufferSizeInBytes / frameBytes : 0;
	}

	/** AOSP read(ByteBuffer, int): 16-bit PCM into a direct buffer, in native byte order. */
	public int read(java.nio.ByteBuffer audioBuffer, int sizeInBytes) {
		if (audioBuffer == null || sizeInBytes < 0 || sizeInBytes > audioBuffer.capacity())
			return ERROR_BAD_VALUE;
		short[] tmp = new short[sizeInBytes / 2];
		int shorts = read(tmp, 0, tmp.length);
		if (shorts <= 0)
			return shorts;
		java.nio.ByteBuffer b = audioBuffer.duplicate().order(java.nio.ByteOrder.nativeOrder());
		b.position(0);
		b.asShortBuffer().put(tmp, 0, shorts);
		return shorts * 2;
	}
}
