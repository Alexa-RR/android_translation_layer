package android.security.keystore;

import java.security.Key;

/**
 * teslalinux: a key that lives in this device's software keystore (AOSP keystore2
 * AndroidKeyStoreKey): its alias, its algorithm and the authorization list it was made with. The
 * key material stays inside the keystore package; getEncoded() of a secret or private keystore key
 * is null, as on Android, so only the keystore's own Cipher, Mac, Signature and KeyAgreement
 * implementations use it.
 */
public abstract class AndroidKeyStoreKey implements Key {
	private final String alias;
	private final String algorithm;
	/** secret keys: the raw key; private keys: the PKCS#8 encoding */
	final byte[] material;
	final KeyAuthorizations auth;

	AndroidKeyStoreKey(String alias, String algorithm, byte[] material, KeyAuthorizations auth) {
		this.alias = alias;
		this.algorithm = algorithm;
		this.material = material;
		this.auth = auth;
	}

	public String getAlias() { return alias; }
	@Override public String getAlgorithm() { return algorithm; }
	@Override public String getFormat() { return null; }
	@Override public byte[] getEncoded() { return null; }
}
