package android.security.keystore;

import javax.crypto.SecretKey;

/* teslalinux: a symmetric key that lives in the keystore. Like on Android, the material is never
 * handed to the application: getEncoded()/getFormat() return null, so only the keystore's own
 * Cipher/Mac implementations (which read it package-privately) can use it. The authorization list
 * the key was made with travels with it (KeyMint keeps it in the key blob). */
public final class AndroidKeyStoreSecretKey extends AndroidKeyStoreKey implements SecretKey {
	AndroidKeyStoreSecretKey(String alias, String algorithm, byte[] material, KeyAuthorizations auth) {
		super(alias, algorithm, material, auth);
	}

	@Override public int hashCode() { return getAlias().hashCode(); }
	@Override public boolean equals(Object o) { return o instanceof AndroidKeyStoreSecretKey && ((AndroidKeyStoreSecretKey) o).getAlias().equals(getAlias()); }
	@Override public String toString() { return "AndroidKeyStoreSecretKey{" + getAlias() + ", " + getAlgorithm() + "}"; }
}
