package java.security.spec;

/**
 * The set of parameters of a cryptographic algorithm given by its standard name (Java SE 11,
 * Android API 33). This runtime's libcore predates it; the Curve 25519 keys of the Android
 * keystore report their curve with it.
 */
public class NamedParameterSpec implements AlgorithmParameterSpec {
	/** the Curve25519 Diffie-Hellman function (RFC 7748) */
	public static final NamedParameterSpec X25519 = new NamedParameterSpec("X25519");
	/** the Curve448 Diffie-Hellman function (RFC 7748) */
	public static final NamedParameterSpec X448 = new NamedParameterSpec("X448");
	/** EdDSA over edwards25519 (RFC 8032) */
	public static final NamedParameterSpec ED25519 = new NamedParameterSpec("Ed25519");
	/** EdDSA over edwards448 (RFC 8032) */
	public static final NamedParameterSpec ED448 = new NamedParameterSpec("Ed448");

	private final String name;

	public NamedParameterSpec(String stdName) {
		if (stdName == null)
			throw new NullPointerException("stdName must not be null");
		this.name = stdName;
	}

	public String getName() {
		return name;
	}
}
