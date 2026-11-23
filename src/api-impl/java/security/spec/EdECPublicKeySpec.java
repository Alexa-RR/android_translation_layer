package java.security.spec;

/** An EdDSA public key: the parameters and the point (Java SE 15, Android API 33). */
public final class EdECPublicKeySpec implements KeySpec {
	private final NamedParameterSpec params;
	private final EdECPoint point;

	public EdECPublicKeySpec(NamedParameterSpec params, EdECPoint point) {
		if (params == null)
			throw new NullPointerException("params must not be null");
		if (point == null)
			throw new NullPointerException("point must not be null");
		this.params = params;
		this.point = point;
	}

	public NamedParameterSpec getParams() {
		return params;
	}

	public EdECPoint getPoint() {
		return point;
	}
}
