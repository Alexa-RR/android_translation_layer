package android.media;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class MediaFormat {

	private Map<String, Object> map = new HashMap<>();

	public void setString(String key, String value) {
		map.put(key, value);
	}

	public void setInteger(String key, int value) {
		map.put(key, value);
	}

	public void setByteBuffer(String key, ByteBuffer value) {
		map.put(key, value);
	}

	public void setFloat(String key, float value) {
		map.put(key, value);
	}

	public ByteBuffer getByteBuffer(String name) {
		return (ByteBuffer)map.get(name);
	}

	public int getInteger(String name) {
		return (int)map.get(name);
	}

	public boolean containsKey(String name) {
		return map.containsKey(name);
	}

	public String toString() {
		return map.toString();
	}

	public String getString(String name) {
		return (String)map.get(name);
	}

	public long getLong(String name) {
		return (long)map.get(name);
	}

	/* teslalinux: MediaFormat additions */
	public static final int TYPE_NULL = 0, TYPE_INTEGER = 1, TYPE_LONG = 2, TYPE_FLOAT = 3, TYPE_STRING = 4, TYPE_BYTE_BUFFER = 5;
	public static MediaFormat createAudioFormat(String mime, int sampleRate, int channelCount) {
		MediaFormat f = new MediaFormat(); f.setString("mime", mime); f.setInteger("sample-rate", sampleRate); f.setInteger("channel-count", channelCount); return f;
	}
	public static MediaFormat createVideoFormat(String mime, int width, int height) {
		MediaFormat f = new MediaFormat(); f.setString("mime", mime); f.setInteger("width", width); f.setInteger("height", height); return f;
	}
	public void setLong(String name, long value) { map.put(name, value); }
	public float getFloat(String name) { Object v = map.get(name); return v instanceof Number ? ((Number) v).floatValue() : 0f; }
	public float getFloat(String name, float defaultValue) { Object v = map.get(name); return v instanceof Number ? ((Number) v).floatValue() : defaultValue; }
	public void setFeatureEnabled(String feature, boolean enabled) { map.put("feature-" + feature, enabled ? 1 : 0); }
	public boolean getFeatureEnabled(String feature) { Object v = map.get("feature-" + feature); return v instanceof Integer && (Integer) v != 0; }
	public int getValueTypeForKey(String name) {
		Object v = map.get(name);
		if (v == null) return TYPE_NULL; if (v instanceof Integer) return TYPE_INTEGER; if (v instanceof Long) return TYPE_LONG;
		if (v instanceof Float) return TYPE_FLOAT; if (v instanceof String) return TYPE_STRING; return TYPE_BYTE_BUFFER;
	}
	public java.util.Set<String> getKeys() { return map.keySet(); }
	public boolean containsFeature(String name) { return map.containsKey("feature-" + name); }
}
