package android.media;

import android.os.Handler;

public final class AudioFocusRequest {
	private final int focusGain;
	private final AudioAttributes attributes;
	private final AudioManager.OnAudioFocusChangeListener listener;
	private final boolean acceptsDelayed, pausesWhenDucked, forceDucking;

	AudioFocusRequest(int gain, AudioAttributes attrs, AudioManager.OnAudioFocusChangeListener l, boolean delayed, boolean pause, boolean force) {
		focusGain = gain; attributes = attrs; listener = l; acceptsDelayed = delayed; pausesWhenDucked = pause; forceDucking = force;
	}
	public int getFocusGain() { return focusGain; }
	public AudioAttributes getAudioAttributes() { return attributes; }
	public AudioManager.OnAudioFocusChangeListener getOnAudioFocusChangeListener() { return listener; }
	public boolean acceptsDelayedFocusGain() { return acceptsDelayed; }
	public boolean willPauseWhenDucked() { return pausesWhenDucked; }
	public boolean locksFocus() { return false; }

	public static final class Builder {
		private int gain; private AudioAttributes attrs; private AudioManager.OnAudioFocusChangeListener l;
		private boolean delayed, pause, force;
		public Builder(int focusGain) { gain = focusGain; }
		public Builder(AudioFocusRequest r) { gain = r.focusGain; attrs = r.attributes; l = r.listener; delayed = r.acceptsDelayed; pause = r.pausesWhenDucked; force = r.forceDucking; }
		public Builder setFocusGain(int focusGain) { gain = focusGain; return this; }
		public Builder setAudioAttributes(AudioAttributes a) { attrs = a; return this; }
		public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener listener) { l = listener; return this; }
		public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener listener, Handler handler) { l = listener; return this; }
		public Builder setAcceptsDelayedFocusGain(boolean v) { delayed = v; return this; }
		public Builder setWillPauseWhenDucked(boolean v) { pause = v; return this; }
		public Builder setForceDucking(boolean v) { force = v; return this; }
		public Builder setLocksFocus(boolean v) { return this; }
		public AudioFocusRequest build() { return new AudioFocusRequest(gain, attrs, l, delayed, pause, force); }
	}
}
