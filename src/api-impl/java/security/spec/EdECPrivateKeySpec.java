package java.security.spec;

/** An EdDSA private key: the parameters and the key's bytes (Java SE 15, Android API 33). */
public final class EdECPrivateKeySpec implements KeySpec {
	private final NamedParameterSpec params;
	private final byte[] bytes;

	public EdECPrivateKeySpec(NamedParameterSpec params, byte[] bytes) {
		if (params == null)
			throw new NullPointerException("params must not be null");
		if (bytes == null)
			throw new NullPointerException("bytes must not be null");
		this.params = params;
		this.bytes = bytes.clone();
	}

	public NamedParameterSpec getParams() {
		return params;
	}

	public byte[] getBytes() {
		return bytes.clone();
	}
}
