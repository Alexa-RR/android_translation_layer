package android.security.keystore;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.math.BigInteger;

/**
 * teslalinux: the private half of a keystore key pair (AOSP keystore2 AndroidKeyStorePrivateKey,
 * AndroidKeyStoreRSAPrivateKey, AndroidKeyStoreECPrivateKey). Not exportable: getEncoded() and
 * getFormat() are null; RSA keys give their modulus (RSAKey) and EC keys their curve (ECKey), as
 * on Android. The keystore's Signature, Cipher and KeyAgreement run the operation on the platform
 * provider with the key the keystore keeps (PKCS#8), after KeyMint's checks.
 */
public abstract class AndroidKeyStorePrivateKey extends AndroidKeyStoreKey implements PrivateKey {
	private final AndroidKeyStorePublicKey publicKey;
	private transient PrivateKey platformKey;

	AndroidKeyStorePrivateKey(String alias, String algorithm, byte[] pkcs8, KeyAuthorizations auth, AndroidKeyStorePublicKey publicKey) {
		super(alias, algorithm, pkcs8, auth);
		this.publicKey = publicKey;
	}

	static AndroidKeyStorePrivateKey create(String alias, String algorithm, byte[] pkcs8, KeyAuthorizations auth, AndroidKeyStorePublicKey publicKey) {
		if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(algorithm))
			return new RSA(alias, pkcs8, auth, (AndroidKeyStorePublicKey.RSA) publicKey);
		if (KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(algorithm))
			return new EC(alias, pkcs8, auth, (AndroidKeyStorePublicKey.EC) publicKey);
		if (KeyProperties.KEY_ALGORITHM_XDH.equalsIgnoreCase(algorithm))
			return new XDH(alias, pkcs8, auth, (AndroidKeyStorePublicKey.XDH) publicKey);
		if (KeyStoreCurve25519.ED25519.equalsIgnoreCase(algorithm))
			return new EdEC(alias, pkcs8, auth, (AndroidKeyStorePublicKey.EdEC) publicKey);
		throw new ProviderException("Unsupported key algorithm: " + algorithm);
	}

	/* Copyright (C) 2021 The Android Open Source Project (AOSP keystore2 AndroidKeyStoreXDHPrivateKey):
	 * an X25519 key; the platform provider (Conscrypt's XDH) computes its agreements */
	public static final class XDH extends AndroidKeyStorePrivateKey implements java.security.interfaces.XECPrivateKey {
		XDH(String alias, byte[] pkcs8, KeyAuthorizations auth, AndroidKeyStorePublicKey.XDH publicKey) {
			super(alias, KeyProperties.KEY_ALGORITHM_XDH, pkcs8, auth, publicKey);
		}

		@Override public java.security.spec.AlgorithmParameterSpec getParams() { return java.security.spec.NamedParameterSpec.X25519; }
		/* AOSP: the scalar never leaves the keystore */
		@Override public java.util.Optional<byte[]> getScalar() { return java.util.Optional.empty(); }
		@Override public String toString() { return "AndroidKeyStoreXDHPrivateKey{" + getAlias() + "}"; }
	}

	/* Copyright (C) 2022 The Android Open Source Project (AOSP keystore2 AndroidKeyStoreEdECPrivateKey):
	 * an Ed25519 key (TlEd25519 signs with it) */
	public static final class EdEC extends AndroidKeyStorePrivateKey implements java.security.interfaces.EdECPrivateKey {
		EdEC(String alias, byte[] pkcs8, KeyAuthorizations auth, AndroidKeyStorePublicKey.EdEC publicKey) {
			super(alias, "EdDSA", pkcs8, auth, publicKey);
		}

		/** the 32-byte RFC 8032 secret (never handed to the app) */
		byte[] secret() {
			byte[] s = TlEd25519.secretFromPkcs8(material);
			if (s == null)
				throw new ProviderException("Keystore key material unreadable");
			return s;
		}

		@Override public java.security.spec.NamedParameterSpec getParams() { return java.security.spec.NamedParameterSpec.ED25519; }
		@Override public java.util.Optional<byte[]> getBytes() { return java.util.Optional.empty(); }
		@Override public String toString() { return "AndroidKeyStoreEdECPrivateKey{" + getAlias() + "}"; }
	}

	AndroidKeyStorePublicKey getPublicKey() {
		return publicKey;
	}

	/** the key for the platform provider's operation (never handed to the app) */
	synchronized PrivateKey platformKey() {
		if (platformKey == null) {
			try {
				platformKey = KeyFactory.getInstance(getAlgorithm()).generatePrivate(new PKCS8EncodedKeySpec(material));
			} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
				throw new ProviderException("Keystore key material unreadable", e);
			}
		}
		return platformKey;
	}

	@Override public int hashCode() { return getAlias().hashCode() * 31 + getAlgorithm().hashCode(); }
	@Override public boolean equals(Object o) { return o instanceof AndroidKeyStorePrivateKey && ((AndroidKeyStorePrivateKey) o).getAlias().equals(getAlias()) && ((AndroidKeyStorePrivateKey) o).getAlgorithm().equals(getAlgorithm()); }
	@Override public String toString() { return getClass().getSimpleName() + "{" + getAlias() + "}"; }

	public static final class RSA extends AndroidKeyStorePrivateKey implements RSAKey {
		RSA(String alias, byte[] pkcs8, KeyAuthorizations auth, AndroidKeyStorePublicKey.RSA publicKey) {
			super(alias, KeyProperties.KEY_ALGORITHM_RSA, pkcs8, auth, publicKey);
		}

		@Override
		public BigInteger getModulus() {
			return ((RSAPublicKey) getPublicKey()).getModulus();
		}

		@Override public String toString() { return "AndroidKeyStoreRSAPrivateKey{" + getAlias() + "}"; }
	}

	public static final class EC extends AndroidKeyStorePrivateKey implements ECKey {
		EC(String alias, byte[] pkcs8, KeyAuthorizations auth, AndroidKeyStorePublicKey.EC publicKey) {
			super(alias, KeyProperties.KEY_ALGORITHM_EC, pkcs8, auth, publicKey);
		}

		@Override
		public ECParameterSpec getParams() {
			return ((ECPublicKey) getPublicKey()).getParams();
		}

		@Override public String toString() { return "AndroidKeyStoreECPrivateKey{" + getAlias() + "}"; }
	}
}
