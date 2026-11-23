package android.security.keystore;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECKey;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyAgreementSpi;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;

/**
 * teslalinux: ECDH with a keystore EC private key (AOSP keystore2 AndroidKeyStoreKeyAgreementSpi.ECDH):
 * the key must have PURPOSE_AGREE_KEY (KeyMint begin()), the peer's key must be an EC public key on
 * the same curve, and the shared secret is computed once, in doPhase with lastPhase true, as
 * KeyMint's single-step agreement.
 */
public abstract class AndroidKeyStoreKeyAgreementSpi extends KeyAgreementSpi {
	public static final class ECDH extends AndroidKeyStoreKeyAgreementSpi {
		public ECDH() { super("ECDH"); }
	}

	private final String algorithm;
	private AndroidKeyStorePrivateKey key;
	private KeyAgreement delegate;
	private byte[] secret;

	AndroidKeyStoreKeyAgreementSpi(String algorithm) {
		this.algorithm = algorithm;
	}

	@Override
	protected void engineInit(Key key, SecureRandom random) throws InvalidKeyException {
		this.key = null;
		delegate = null;
		secret = null;
		if (key == null)
			throw new InvalidKeyException("key == null");
		if (!(key instanceof AndroidKeyStorePrivateKey))
			throw new InvalidKeyException("Only Android KeyStore private keys supported. Key: " + key);
		AndroidKeyStorePrivateKey k = (AndroidKeyStorePrivateKey) key;
		if (!KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(k.getAlgorithm()))
			throw new InvalidKeyException("Unsupported key algorithm: " + k.getAlgorithm() + ". Only EC supported");
		k.auth.checkBeginAsymmetric(KeyProperties.PURPOSE_AGREE_KEY, null, null, null);
		try {
			KeyStoreStorage.consumeUse(k);
			KeyAgreement a = KeyAgreement.getInstance(algorithm);
			a.init(k.platformKey(), random);
			delegate = a;
			this.key = k;
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new ProviderException("Keystore operation failed", e);
		}
	}

	@Override
	protected void engineInit(Key key, AlgorithmParameterSpec params, SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
		if (params != null)
			throw new InvalidAlgorithmParameterException("Unsupported algorithm parameters: " + params);
		engineInit(key, random);
	}

	@Override
	protected Key engineDoPhase(Key peerKey, boolean lastPhase) throws InvalidKeyException {
		if (delegate == null)
			throw new IllegalStateException("Not initialized");
		if (secret != null)
			throw new IllegalStateException("Already in Phase 2");
		if (peerKey == null)
			throw new InvalidKeyException("key == null");
		if (!lastPhase)
			throw new IllegalStateException("Only one other party allowed");
		if (!(peerKey instanceof PublicKey) || !(peerKey instanceof ECKey))
			throw new InvalidKeyException("Only public keys supported. Key: " + peerKey);
		if (((ECKey) peerKey).getParams().getCurve().getField().getFieldSize() != ((ECKey) key).getParams().getCurve().getField().getFieldSize())
			throw new InvalidKeyException("Public and Private key parameters are different.");
		delegate.doPhase(peerKey, true);
		secret = delegate.generateSecret();
		return null;
	}

	private byte[] takeSecret() {
		if (secret == null)
			throw new IllegalStateException("Key agreement not completed");
		byte[] s = secret;
		secret = null;
		/* a new agreement with the same key needs init again, as KeyMint's operation is finished */
		delegate = null;
		return s;
	}

	@Override protected byte[] engineGenerateSecret() { return takeSecret(); }

	@Override
	protected int engineGenerateSecret(byte[] sharedSecret, int offset) throws ShortBufferException {
		if (secret == null)
			throw new IllegalStateException("Key agreement not completed");
		if (sharedSecret.length - offset < secret.length)
			throw new ShortBufferException("Needed: " + secret.length + ", available: " + (sharedSecret.length - offset));
		byte[] s = takeSecret();
		System.arraycopy(s, 0, sharedSecret, offset, s.length);
		return s.length;
	}

	@Override
	protected SecretKey engineGenerateSecret(String algorithm) throws NoSuchAlgorithmException {
		if (algorithm == null)
			throw new NoSuchAlgorithmException("algorithm == null");
		return new javax.crypto.spec.SecretKeySpec(takeSecret(), algorithm);
	}
}
