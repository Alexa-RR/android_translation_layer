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
		/* Curve 25519 (AOSP keystore2 AndroidKeyStoreXDHPublicKey / AndroidKeyStoreEdECPublicKey):
		 * made from the encoding itself, as AOSP does */
		if (KeyStoreCurve25519.ED25519.equalsIgnoreCase(algorithm))
			return new EdEC(alias, spki, auth);
		if (KeyProperties.KEY_ALGORITHM_XDH.equalsIgnoreCase(algorithm))
			return new XDH(alias, spki, auth);
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

	/* Copyright (C) 2021 The Android Open Source Project (AOSP keystore2 AndroidKeyStoreXDHPublicKey) */
	public static final class XDH extends AndroidKeyStorePublicKey implements java.security.interfaces.XECPublicKey {
		private final int preambleLength;

		XDH(String alias, byte[] spki, KeyAuthorizations auth) {
			super(alias, KeyProperties.KEY_ALGORITHM_XDH, spki, auth);
			preambleLength = KeyStoreCurve25519.x25519PreambleLength(spki);
			if (preambleLength == 0)
				throw new IllegalArgumentException("Key size is not correct size");
		}

		/* AOSP: the encoded key's bytes as a big-endian integer */
		@Override public BigInteger getU() { return new BigInteger(Arrays.copyOfRange(getEncoded(), preambleLength, getEncoded().length)); }
		@Override public java.security.spec.AlgorithmParameterSpec getParams() { return java.security.spec.NamedParameterSpec.X25519; }
		@Override public String getFormat() { return "x.509"; }
		@Override public String toString() { return "AndroidKeyStoreXDHPublicKey{" + getAlias() + "}"; }
	}

	/* Copyright (C) 2022 The Android Open Source Project (AOSP keystore2 AndroidKeyStoreEdECPublicKey) */
	public static final class EdEC extends AndroidKeyStorePublicKey implements java.security.interfaces.EdECPublicKey {
		private final java.security.spec.EdECPoint point;

		EdEC(String alias, byte[] spki, KeyAuthorizations auth) {
			super(alias, KeyStoreCurve25519.ED25519_OID, spki, auth);
			byte[] coordinates = TlEd25519.publicFromSpki(spki);
			if (coordinates == null)
				throw new IllegalArgumentException("Key size is not correct size");
			// Oddity of the key is the most-significant bit of the last byte.
			boolean isOdd = (0x80 & coordinates[coordinates.length - 1]) != 0;
			coordinates[coordinates.length - 1] &= (byte) 0x7f;
			// Representation of Y is in little-endian, according to rfc8032 section-3.1.
			for (int i = 0, j = coordinates.length - 1; i < j; i++, j--) {
				byte t = coordinates[i];
				coordinates[i] = coordinates[j];
				coordinates[j] = t;
			}
			point = new java.security.spec.EdECPoint(isOdd, new BigInteger(1, coordinates));
		}

		/** the 32-byte RFC 8032 encoding of the key */
		byte[] raw() { return TlEd25519.publicFromSpki(getEncoded()); }
		@Override public java.security.spec.NamedParameterSpec getParams() { return java.security.spec.NamedParameterSpec.ED25519; }
		@Override public java.security.spec.EdECPoint getPoint() { return point; }
		@Override public String toString() { return "AndroidKeyStoreEdECPublicKey{" + getAlias() + "}"; }
	}
}
