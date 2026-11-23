package android.security.keystore;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * teslalinux: the public half of a keystore key pair (AOSP keystore2 AndroidKeyStorePublicKey,
 * AndroidKeyStoreRSAPublicKey, AndroidKeyStoreECPublicKey): an ordinary exportable public key
 * (X.509 SubjectPublicKeyInfo) that knows its alias. As on Android, public key operations
 * (verifying, encrypting) are not keystore operations: the platform provider performs them.
 */
public abstract class AndroidKeyStorePublicKey extends AndroidKeyStoreKey implements PublicKey {
	private final byte[] encoded;

	AndroidKeyStorePublicKey(String alias, String algorithm, byte[] encoded, KeyAuthorizations auth) {
		super(alias, algorithm, null, auth);
		this.encoded = encoded;
	}

	/** the public key of a stored pair from its SubjectPublicKeyInfo (the certificate's) */
	static AndroidKeyStorePublicKey create(String alias, String algorithm, byte[] spki, KeyAuthorizations auth) {
		PublicKey platform;
		try {
			platform = KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(spki));
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new ProviderException("Keystore public key unreadable", e);
		}
		if (platform instanceof RSAPublicKey)
			return new RSA(alias, spki, auth, (RSAPublicKey) platform);
		if (platform instanceof ECPublicKey)
			return new EC(alias, spki, auth, (ECPublicKey) platform);
		throw new ProviderException("Unsupported key algorithm: " + algorithm);
	}

	@Override public String getFormat() { return "X.509"; }
	@Override public byte[] getEncoded() { return encoded.clone(); }
	@Override public int hashCode() { return Arrays.hashCode(encoded); }
	@Override public boolean equals(Object o) { return o instanceof PublicKey && getAlgorithm().equals(((PublicKey) o).getAlgorithm()) && Arrays.equals(encoded, ((PublicKey) o).getEncoded()); }

	public static final class RSA extends AndroidKeyStorePublicKey implements RSAPublicKey {
		private final BigInteger modulus, publicExponent;

		RSA(String alias, byte[] spki, KeyAuthorizations auth, RSAPublicKey key) {
			super(alias, KeyProperties.KEY_ALGORITHM_RSA, spki, auth);
			modulus = key.getModulus();
			publicExponent = key.getPublicExponent();
		}

		@Override public BigInteger getModulus() { return modulus; }
		@Override public BigInteger getPublicExponent() { return publicExponent; }
		@Override public String toString() { return "AndroidKeyStoreRSAPublicKey{" + getAlias() + ", modulus=" + modulus.bitLength() + " bits}"; }
	}

	public static final class EC extends AndroidKeyStorePublicKey implements ECPublicKey {
		private final ECParameterSpec params;
		private final ECPoint w;

		EC(String alias, byte[] spki, KeyAuthorizations auth, ECPublicKey key) {
			super(alias, KeyProperties.KEY_ALGORITHM_EC, spki, auth);
			params = key.getParams();
			w = key.getW();
		}

		@Override public ECParameterSpec getParams() { return params; }
		@Override public ECPoint getW() { return w; }
		@Override public String toString() { return "AndroidKeyStoreECPublicKey{" + getAlias() + "}"; }
	}
}
