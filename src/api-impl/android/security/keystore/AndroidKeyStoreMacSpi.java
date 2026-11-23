package android.security.keystore;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.Mac;
import javax.crypto.MacSpi;
import javax.crypto.spec.SecretKeySpec;

/* teslalinux: HMAC over keystore keys (AOSP keystore2 AndroidKeyStoreHmacSpi), same scheme as the
 * cipher: the key's authorization list (purpose SIGN, digest, validity, usage count) is enforced
 * when the operation begins, then the platform's HMAC runs with the key material. */
public abstract class AndroidKeyStoreMacSpi extends MacSpi {
	public static final class HmacSHA1 extends AndroidKeyStoreMacSpi { public HmacSHA1() { super(KeyProperties.KEY_ALGORITHM_HMAC_SHA1, KeyProperties.DIGEST_SHA1, 20); } }
	public static final class HmacSHA224 extends AndroidKeyStoreMacSpi { public HmacSHA224() { super(KeyProperties.KEY_ALGORITHM_HMAC_SHA224, KeyProperties.DIGEST_SHA224, 28); } }
	public static final class HmacSHA256 extends AndroidKeyStoreMacSpi { public HmacSHA256() { super(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KeyProperties.DIGEST_SHA256, 32); } }
	public static final class HmacSHA384 extends AndroidKeyStoreMacSpi { public HmacSHA384() { super(KeyProperties.KEY_ALGORITHM_HMAC_SHA384, KeyProperties.DIGEST_SHA384, 48); } }
	public static final class HmacSHA512 extends AndroidKeyStoreMacSpi { public HmacSHA512() { super(KeyProperties.KEY_ALGORITHM_HMAC_SHA512, KeyProperties.DIGEST_SHA512, 64); } }

	private final String algorithm;
	private final String digest;
	private final int macLength;
	private Mac delegate;

	AndroidKeyStoreMacSpi(String algorithm, String digest, int macLength) {
		this.algorithm = algorithm;
		this.digest = digest;
		this.macLength = macLength;
	}

	@Override protected int engineGetMacLength() { return macLength; }

	@Override
	protected void engineInit(Key key, AlgorithmParameterSpec params) throws InvalidKeyException, InvalidAlgorithmParameterException {
		if (!(key instanceof AndroidKeyStoreSecretKey))
			throw new InvalidKeyException("Unsupported key: " + (key == null ? "null" : key.getClass().getName()));
		if (params != null)
			throw new InvalidAlgorithmParameterException("Unsupported AlgorithmParameterSpec: " + params);
		AndroidKeyStoreSecretKey k = (AndroidKeyStoreSecretKey) key;
		if (!algorithm.equalsIgnoreCase(k.getAlgorithm()))
			throw new InvalidKeyException("Keystore operation failed", new java.security.KeyStoreException("Incompatible digest"));
		k.auth.checkBegin(KeyProperties.PURPOSE_SIGN, null, null, digest);
		try {
			KeyStoreStorage.consumeUse(k);
			delegate = Mac.getInstance(algorithm);
			delegate.init(new SecretKeySpec(k.material, algorithm));
		} catch (NoSuchAlgorithmException | IOException e) {
			throw new ProviderException("Keystore operation failed", e);
		}
	}

	private Mac op() {
		if (delegate == null)
			throw new IllegalStateException("Not initialized");
		return delegate;
	}

	@Override protected void engineUpdate(byte input) { op().update(input); }
	@Override protected void engineUpdate(byte[] input, int offset, int len) { op().update(input, offset, len); }
	@Override protected byte[] engineDoFinal() { return op().doFinal(); }
	@Override protected void engineReset() { if (delegate != null) delegate.reset(); }
}
