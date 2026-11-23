package java.security.spec;

/** An XEC (RFC 7748) private key: the curve's parameters and the scalar (Java SE 11, Android API 33). */
public class XECPrivateKeySpec implements KeySpec {
	private final AlgorithmParameterSpec params;
	private final byte[] scalar;

	public XECPrivateKeySpec(AlgorithmParameterSpec params, byte[] scalar) {
		if (params == null)
			throw new NullPointerException("params must not be null");
		if (scalar == null)
			throw new NullPointerException("scalar must not be null");
		this.params = params;
		this.scalar = scalar.clone();
	}

	public AlgorithmParameterSpec getParams() {
		return params;
	}

	public byte[] getScalar() {
		return scalar.clone();
	}
}
