package android.security.keystore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.security.SignatureSpi;

/**
 * teslalinux: Ed25519 signatures (AOSP keystore2 AndroidKeyStoreECDSASignatureSpi.Ed25519): signing
 * with a keystore ed25519 key, whose authorizations are enforced as KeyMint's begin() does (purpose
 * SIGN, digest NONE: Ed25519 has its own, validity, usage count), and verifying with an Ed25519
 * public key. PureEdDSA signs the whole message, so it is collected until sign/verify. The
 * platform provider has no Ed25519 here, so verification is done here too (TlEd25519).
 */
public final class AndroidKeyStoreEd25519SignatureSpi extends SignatureSpi {
	private final ByteArrayOutputStream message = new ByteArrayOutputStream();
	private byte[] secret;      // signing
	private byte[] publicKey;   // verifying

	@Override
	protected void engineInitSign(PrivateKey key) throws InvalidKeyException {
		engineInitSign(key, null);
	}

	@Override
	protected void engineInitSign(PrivateKey key, SecureRandom random) throws InvalidKeyException {
		secret = null;
		publicKey = null;
		message.reset();
		if (key == null)
			throw new InvalidKeyException("Unsupported key: null");
		if (!(key instanceof AndroidKeyStorePrivateKey.EdEC))
			throw new InvalidKeyException("Unsupported private key type: " + key);
		AndroidKeyStorePrivateKey.EdEC k = (AndroidKeyStorePrivateKey.EdEC) key;
		/* KeyMint begin() */
		k.auth.checkBeginAsymmetric(KeyProperties.PURPOSE_SIGN, null, null, KeyProperties.DIGEST_NONE);
		try {
			KeyStoreStorage.consumeUse(k);
		} catch (IOException e) {
			throw new ProviderException("Keystore operation failed", e);
		}
		secret = k.secret();
	}

	@Override
	protected void engineInitVerify(PublicKey key) throws InvalidKeyException {
		secret = null;
		publicKey = null;
		message.reset();
		if (key == null)
			throw new InvalidKeyException("Unsupported key: null");
		byte[] raw = TlEd25519.publicFromSpki(key.getEncoded());
		if (raw == null)
			throw new InvalidKeyException("Unsupported public key type: " + key + " (an Ed25519 key needed)");
		publicKey = raw;
	}

	private void check() throws SignatureException {
		if (secret == null && publicKey == null)
			throw new SignatureException("Not initialized");
	}

	@Override
	protected void engineUpdate(byte b) throws SignatureException {
		check();
		message.write(b);
	}

	@Override
	protected void engineUpdate(byte[] b, int off, int len) throws SignatureException {
		check();
		message.write(b, off, len);
	}

	@Override
	protected byte[] engineSign() throws SignatureException {
		check();
		if (secret == null)
			throw new SignatureException("Not initialized for signing");
		byte[] sig = TlEd25519.sign(secret, message.toByteArray());
		message.reset();
		return sig;
	}

	@Override
	protected boolean engineVerify(byte[] sig) throws SignatureException {
		check();
		if (publicKey == null)
			throw new SignatureException("Not initialized for verification");
		boolean ok = sig != null && TlEd25519.verify(publicKey, message.toByteArray(), sig);
		message.reset();
		return ok;
	}

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
