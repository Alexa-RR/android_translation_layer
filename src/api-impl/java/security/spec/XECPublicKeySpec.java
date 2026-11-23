package java.security.spec;

import java.math.BigInteger;

/** An XEC (RFC 7748) public key: the curve's parameters and the u-coordinate (Java SE 11, Android API 33). */
public class XECPublicKeySpec implements KeySpec {
	private final AlgorithmParameterSpec params;
	private final BigInteger u;

	public XECPublicKeySpec(AlgorithmParameterSpec params, BigInteger u) {
		if (params == null)
			throw new NullPointerException("params must not be null");
		if (u == null)
			throw new NullPointerException("u must not be null");
		this.params = params;
		this.u = u;
	}

	public AlgorithmParameterSpec getParams() {
		return params;
	}

	public BigInteger getU() {
		return u;
	}
}
