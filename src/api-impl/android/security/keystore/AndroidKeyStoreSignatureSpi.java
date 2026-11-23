package android.security.keystore;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.SignatureSpi;

/**
 * teslalinux: signatures with keystore private keys (AOSP keystore2 AndroidKeyStoreSignatureSpiBase,
 * AndroidKeyStoreRSASignatureSpi, AndroidKeyStoreECDSASignatureSpi): NONE/MD5/SHA-1/SHA-2 with
 * RSA PKCS#1, SHA-1/SHA-2 with RSA PSS (MGF1 with the same digest, salt as long as the digest, as
 * KeyMint signs), NONE/SHA-1/SHA-2 with ECDSA. When signing begins the key's authorization list is
 * enforced as KeyMint does (purpose SIGN, the digest, the RSA signature padding, validity, usage
 * count); the platform provider then signs with the key the keystore keeps. Verification is a
 * public key operation, which keystore leaves to the highest-priority provider, as AOSP does.
 */
public abstract class AndroidKeyStoreSignatureSpi extends SignatureSpi {
	/* RSA, PKCS#1 */
	public static final class NONEWithPKCS1Padding extends AndroidKeyStoreSignatureSpi { public NONEWithPKCS1Padding() { super("RSA", KeyProperties.DIGEST_NONE, KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, "NONEwithRSA"); } }
	public static final class MD5WithPKCS1Padding extends AndroidKeyStoreSignatureSpi { public MD5WithPKCS1Padding() { super("RSA", KeyProperties.DIGEST_MD5, KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, "MD5withRSA"); } }
	public static final class SHA1WithPKCS1Padding extends AndroidKeyStoreSignatureSpi { public SHA1WithPKCS1Padding() { super("RSA", KeyProperties.DIGEST_SHA1, KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, "SHA1withRSA"); } }
	public static final class SHA224WithPKCS1Padding extends AndroidKeyStoreSignatureSpi { public SHA224WithPKCS1Padding() { super("RSA", KeyProperties.DIGEST_SHA224, KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, "SHA224withRSA"); } }
	public static final class SHA256WithPKCS1Padding extends AndroidKeyStoreSignatureSpi { public SHA256WithPKCS1Padding() { super("RSA", KeyProperties.DIGEST_SHA256, KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, "SHA256withRSA"); } }
	public static final class SHA384WithPKCS1Padding extends AndroidKeyStoreSignatureSpi { public SHA384WithPKCS1Padding() { super("RSA", KeyProperties.DIGEST_SHA384, KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, "SHA384withRSA"); } }
	public static final class SHA512WithPKCS1Padding extends AndroidKeyStoreSignatureSpi { public SHA512WithPKCS1Padding() { super("RSA", KeyProperties.DIGEST_SHA512, KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, "SHA512withRSA"); } }
	/* RSA, PSS */
	public static final class SHA1WithPSSPadding extends AndroidKeyStoreSignatureSpi { public SHA1WithPSSPadding() { super("RSA", KeyProperties.DIGEST_SHA1, KeyProperties.SIGNATURE_PADDING_RSA_PSS, "SHA1withRSA/PSS"); } }
	public static final class SHA224WithPSSPadding extends AndroidKeyStoreSignatureSpi { public SHA224WithPSSPadding() { super("RSA", KeyProperties.DIGEST_SHA224, KeyProperties.SIGNATURE_PADDING_RSA_PSS, "SHA224withRSA/PSS"); } }
	public static final class SHA256WithPSSPadding extends AndroidKeyStoreSignatureSpi { public SHA256WithPSSPadding() { super("RSA", KeyProperties.DIGEST_SHA256, KeyProperties.SIGNATURE_PADDING_RSA_PSS, "SHA256withRSA/PSS"); } }
	public static final class SHA384WithPSSPadding extends AndroidKeyStoreSignatureSpi { public SHA384WithPSSPadding() { super("RSA", KeyProperties.DIGEST_SHA384, KeyProperties.SIGNATURE_PADDING_RSA_PSS, "SHA384withRSA/PSS"); } }
	public static final class SHA512WithPSSPadding extends AndroidKeyStoreSignatureSpi { public SHA512WithPSSPadding() { super("RSA", KeyProperties.DIGEST_SHA512, KeyProperties.SIGNATURE_PADDING_RSA_PSS, "SHA512withRSA/PSS"); } }
	/* ECDSA */
	public static final class NONEWithECDSA extends AndroidKeyStoreSignatureSpi { public NONEWithECDSA() { super("EC", KeyProperties.DIGEST_NONE, null, "NONEwithECDSA"); } }
	public static final class SHA1WithECDSA extends AndroidKeyStoreSignatureSpi { public SHA1WithECDSA() { super("EC", KeyProperties.DIGEST_SHA1, null, "SHA1withECDSA"); } }
	public static final class SHA224WithECDSA extends AndroidKeyStoreSignatureSpi { public SHA224WithECDSA() { super("EC", KeyProperties.DIGEST_SHA224, null, "SHA224withECDSA"); } }
	public static final class SHA256WithECDSA extends AndroidKeyStoreSignatureSpi { public SHA256WithECDSA() { super("EC", KeyProperties.DIGEST_SHA256, null, "SHA256withECDSA"); } }
	public static final class SHA384WithECDSA extends AndroidKeyStoreSignatureSpi { public SHA384WithECDSA() { super("EC", KeyProperties.DIGEST_SHA384, null, "SHA384withECDSA"); } }
	public static final class SHA512WithECDSA extends AndroidKeyStoreSignatureSpi { public SHA512WithECDSA() { super("EC", KeyProperties.DIGEST_SHA512, null, "SHA512withECDSA"); } }

	private final String keyAlgorithm;
	private final String digest;
	private final String padding;
	private final String jcaAlgorithm;
	private Signature delegate;

	AndroidKeyStoreSignatureSpi(String keyAlgorithm, String digest, String padding, String jcaAlgorithm) {
		this.keyAlgorithm = keyAlgorithm;
		this.digest = digest;
		this.padding = padding;
		this.jcaAlgorithm = jcaAlgorithm;
	}

	@Override
	protected void engineInitSign(PrivateKey key) throws InvalidKeyException {
		engineInitSign(key, null);
	}

	@Override
	protected void engineInitSign(PrivateKey key, SecureRandom random) throws InvalidKeyException {
		delegate = null;
		if (key == null)
			throw new InvalidKeyException("Unsupported key: null");
		if (!(key instanceof AndroidKeyStorePrivateKey))
			throw new InvalidKeyException("Unsupported private key type: " + key);
		AndroidKeyStorePrivateKey k = (AndroidKeyStorePrivateKey) key;
		if (!keyAlgorithm.equalsIgnoreCase(k.getAlgorithm()))
			throw new InvalidKeyException("Unsupported key algorithm: " + k.getAlgorithm() + ". Only " + keyAlgorithm + " supported");
		/* KeyMint begin() */
		k.auth.checkBeginAsymmetric(KeyProperties.PURPOSE_SIGN, null, padding, digest);
		try {
			KeyStoreStorage.consumeUse(k);
		} catch (IOException e) {
			throw new ProviderException("Keystore operation failed", e);
		}
		try {
			Signature s = Signature.getInstance(jcaAlgorithm);
			if (random != null)
				s.initSign(k.platformKey(), random);
			else
				s.initSign(k.platformKey());
			delegate = s;
		} catch (NoSuchAlgorithmException e) {
			throw new ProviderException("Keystore operation failed", e);
		}
	}

	@Override
	protected void engineInitVerify(PublicKey key) throws InvalidKeyException {
		/* AOSP: keystore does not perform public key operations; the highest priority provider does */
		try {
			delegate = Signature.getInstance(jcaAlgorithm);
		} catch (NoSuchAlgorithmException e) {
			throw new InvalidKeyException(e);
		}
		delegate.initVerify(key);
	}

	private Signature op() throws SignatureException {
		if (delegate == null)
			throw new SignatureException("Not initialized");
		return delegate;
	}

	@Override protected void engineUpdate(byte b) throws SignatureException { op().update(b); }
	@Override protected void engineUpdate(byte[] b, int off, int len) throws SignatureException { op().update(b, off, len); }
	@Override protected void engineUpdate(java.nio.ByteBuffer input) {
		try {
			op().update(input);
		} catch (SignatureException e) {
			throw new IllegalStateException(e);
		}
	}
	@Override protected byte[] engineSign() throws SignatureException { return op().sign(); }
	@Override protected boolean engineVerify(byte[] sig) throws SignatureException { return op().verify(sig); }

	@Override
	@Deprecated
	protected void engineSetParameter(String param, Object value) {
		throw new java.security.InvalidParameterException();
	}

	@Override
	@Deprecated
	protected Object engineGetParameter(String param) {
		return null;
	}
}
