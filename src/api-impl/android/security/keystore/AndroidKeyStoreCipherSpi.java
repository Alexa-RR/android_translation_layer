package android.security.keystore;

import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/*
 * teslalinux: the AES Cipher implementations for keystore keys (AOSP keystore2
 * AndroidKeyStoreUnauthenticatedAESCipherSpi and AndroidKeyStoreAuthenticatedAESCipherSpi.GCM).
 * The application never sees the key; the operation runs here on the platform's AES. When an
 * operation begins, the key's authorization list is enforced as KeyMint does (purpose, block mode,
 * padding, validity period, caller-provided IV, usage count), and when encrypting without
 * parameters the keystore generates the IV (12 bytes for GCM, 16 for CBC and CTR; Tink and
 * EncryptedSharedPreferences depend on exactly that).
 */
public abstract class AndroidKeyStoreCipherSpi extends CipherSpi {
	public static final class AesEcbNoPadding extends AndroidKeyStoreCipherSpi { public AesEcbNoPadding() { super(KeyProperties.BLOCK_MODE_ECB, KeyProperties.ENCRYPTION_PADDING_NONE); } }
	public static final class AesEcbPkcs7Padding extends AndroidKeyStoreCipherSpi { public AesEcbPkcs7Padding() { super(KeyProperties.BLOCK_MODE_ECB, KeyProperties.ENCRYPTION_PADDING_PKCS7); } }
	public static final class AesCbcNoPadding extends AndroidKeyStoreCipherSpi { public AesCbcNoPadding() { super(KeyProperties.BLOCK_MODE_CBC, KeyProperties.ENCRYPTION_PADDING_NONE); } }
	public static final class AesCbcPkcs7Padding extends AndroidKeyStoreCipherSpi { public AesCbcPkcs7Padding() { super(KeyProperties.BLOCK_MODE_CBC, KeyProperties.ENCRYPTION_PADDING_PKCS7); } }
	public static final class AesCtrNoPadding extends AndroidKeyStoreCipherSpi { public AesCtrNoPadding() { super(KeyProperties.BLOCK_MODE_CTR, KeyProperties.ENCRYPTION_PADDING_NONE); } }
	public static final class AesGcmNoPadding extends AndroidKeyStoreCipherSpi { public AesGcmNoPadding() { super(KeyProperties.BLOCK_MODE_GCM, KeyProperties.ENCRYPTION_PADDING_NONE); } }

	/** AOSP AndroidKeyStoreAuthenticatedAESCipherSpi.GCM */
	static final int GCM_MIN_SUPPORTED_TAG_LENGTH_BITS = 96;
	static final int GCM_MAX_SUPPORTED_TAG_LENGTH_BITS = 128;
	private static final int GCM_DEFAULT_TAG_LENGTH_BITS = 128;

	private final String blockMode;
	private final String padding;
	private final boolean gcm;
	private final boolean ivUsed;
	private final int ivSize;
	private Cipher delegate;
	private byte[] iv;
	private int tagLenBits = GCM_DEFAULT_TAG_LENGTH_BITS;

	AndroidKeyStoreCipherSpi(String blockMode, String padding) {
		this.blockMode = blockMode;
		this.padding = padding;
		this.gcm = KeyProperties.BLOCK_MODE_GCM.equals(blockMode);
		this.ivUsed = !KeyProperties.BLOCK_MODE_ECB.equals(blockMode);
		this.ivSize = gcm ? 12 : 16;
	}

	/* the platform transformation of this mode and padding (JCA's name for AES PKCS#7 is PKCS5Padding) */
	private String delegateTransformation() {
		return "AES/" + blockMode + "/" + (KeyProperties.ENCRYPTION_PADDING_PKCS7.equals(padding) ? "PKCS5Padding" : padding);
	}

	@Override
	protected void engineSetMode(String mode) throws NoSuchAlgorithmException {
		if (!blockMode.equalsIgnoreCase(mode))
			throw new NoSuchAlgorithmException("Unsupported block mode: " + mode);
	}

	@Override
	protected void engineSetPadding(String p) throws NoSuchPaddingException {
		if (!padding.equalsIgnoreCase(KeyAuthorizations.normalizePadding(p)))
			throw new NoSuchPaddingException("Unsupported padding scheme: " + p);
	}

	@Override protected int engineGetBlockSize() { return 16; }
	@Override protected int engineGetOutputSize(int inputLen) { return delegate != null ? delegate.getOutputSize(inputLen) : inputLen + 16 + 16; }
	@Override protected byte[] engineGetIV() { return iv == null ? null : iv.clone(); }

	@Override
	protected AlgorithmParameters engineGetParameters() {
		if (iv == null)
			return null;
		try {
			AlgorithmParameters p = AlgorithmParameters.getInstance(gcm ? "GCM" : "AES");
			p.init(gcm ? new GCMParameterSpec(tagLenBits, iv) : new IvParameterSpec(iv));
			return p;
		} catch (NoSuchAlgorithmException | InvalidParameterSpecException e) {
			throw new ProviderException("Failed to obtain " + (gcm ? "GCM" : "AES") + " AlgorithmParameters", e);
		}
	}

	private static AndroidKeyStoreSecretKey keystoreKey(Key key) throws InvalidKeyException {
		if (!(key instanceof AndroidKeyStoreSecretKey))
			throw new InvalidKeyException("Unsupported key: " + (key == null ? "null" : key.getClass().getName()));
		AndroidKeyStoreSecretKey k = (AndroidKeyStoreSecretKey) key;
		if (!KeyProperties.KEY_ALGORITHM_AES.equalsIgnoreCase(k.getAlgorithm()))
			throw new InvalidKeyException("Unsupported key algorithm: " + k.getAlgorithm() + ". Only " + KeyProperties.KEY_ALGORITHM_AES + " supported");
		return k;
	}

	private static boolean encrypting(int opmode) {
		return opmode == Cipher.ENCRYPT_MODE || opmode == Cipher.WRAP_MODE;
	}

	/* KeyMint begin(): the authorization list, then a use of a usage-limited key */
	private void begin(int opmode, AndroidKeyStoreSecretKey key, boolean callerIv) throws InvalidKeyException, InvalidAlgorithmParameterException {
		key.auth.checkBegin(encrypting(opmode) ? KeyProperties.PURPOSE_ENCRYPT : KeyProperties.PURPOSE_DECRYPT, blockMode, padding, null);
		if (callerIv && encrypting(opmode) && ivUsed)
			key.auth.checkCallerNonce();
		try {
			KeyStoreStorage.consumeUse(key);
		} catch (IOException e) {
			throw new ProviderException("Keystore operation failed", e);
		}
	}

	@Override
	protected void engineInit(int opmode, Key key, SecureRandom random) throws InvalidKeyException {
		try {
			engineInit(opmode, key, (AlgorithmParameterSpec) null, random);
		} catch (InvalidAlgorithmParameterException e) {
			throw new InvalidKeyException(e);
		}
	}

	@Override
	protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params, SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
		AndroidKeyStoreSecretKey k = keystoreKey(key);
		byte[] callerIv = null;
		int tagLen = GCM_DEFAULT_TAG_LENGTH_BITS;
		if (params != null) {
			if (!ivUsed)
				throw new InvalidAlgorithmParameterException("Unsupported parameters: " + params);
			if (gcm) {
				if (!(params instanceof GCMParameterSpec))
					throw new InvalidAlgorithmParameterException("Unsupported parameter spec: " + params + ". Supported: " + GCMParameterSpec.class.getName());
				GCMParameterSpec g = (GCMParameterSpec) params;
				tagLen = g.getTLen();
				if (tagLen < GCM_MIN_SUPPORTED_TAG_LENGTH_BITS || tagLen > GCM_MAX_SUPPORTED_TAG_LENGTH_BITS || (tagLen % 8) != 0)
					throw new InvalidAlgorithmParameterException("Unsupported tag length: " + tagLen + " bits. Supported lengths: 96, 104, 112, 120, 128");
				callerIv = g.getIV();
			} else {
				if (!(params instanceof IvParameterSpec))
					throw new InvalidAlgorithmParameterException("Only " + IvParameterSpec.class.getName() + " supported");
				callerIv = ((IvParameterSpec) params).getIV();
			}
			if (callerIv == null)
				throw new InvalidAlgorithmParameterException("Null IV in " + params);
		} else if (ivUsed && !encrypting(opmode)) {
			throw new InvalidAlgorithmParameterException("IV required when decrypting. Use IvParameterSpec or AlgorithmParameters to provide it.");
		}
		begin(opmode, k, callerIv != null);
		tagLenBits = tagLen;
		if (ivUsed) {
			if (callerIv != null) {
				iv = callerIv.clone();
			} else {
				iv = new byte[ivSize];
				(random != null ? random : new SecureRandom()).nextBytes(iv);
			}
		} else {
			iv = null;
		}
		try {
			delegate = Cipher.getInstance(delegateTransformation());
			SecretKeySpec raw = new SecretKeySpec(k.material, "AES");
			if (!ivUsed)
				delegate.init(opmode, raw);
			else
				delegate.init(opmode, raw, gcm ? new GCMParameterSpec(tagLenBits, iv) : new IvParameterSpec(iv));
		} catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
			throw new ProviderException("Keystore operation failed", e);
		}
	}

	@Override
	protected void engineInit(int opmode, Key key, AlgorithmParameters params, SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
		if (params == null) {
			engineInit(opmode, key, (AlgorithmParameterSpec) null, random);
			return;
		}
		try {
			AlgorithmParameterSpec spec = gcm ? params.getParameterSpec(GCMParameterSpec.class) : params.getParameterSpec(IvParameterSpec.class);
			engineInit(opmode, key, spec, random);
		} catch (InvalidParameterSpecException e) {
			throw new InvalidAlgorithmParameterException(e);
		}
	}

	private Cipher op() {
		if (delegate == null)
			throw new IllegalStateException("Cipher not initialized");
		return delegate;
	}

	@Override protected void engineUpdateAAD(byte[] src, int offset, int len) { op().updateAAD(src, offset, len); }
	@Override protected void engineUpdateAAD(java.nio.ByteBuffer src) { op().updateAAD(src); }
	@Override protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) { return op().update(input, inputOffset, inputLen); }
	@Override protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) throws ShortBufferException { return op().update(input, inputOffset, inputLen, output, outputOffset); }
	@Override protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen) throws IllegalBlockSizeException, BadPaddingException {
		return input == null ? op().doFinal() : op().doFinal(input, inputOffset, inputLen);
	}
	@Override protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
		return op().doFinal(input, inputOffset, inputLen, output, outputOffset);
	}
}
