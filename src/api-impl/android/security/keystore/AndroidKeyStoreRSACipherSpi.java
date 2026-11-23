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
import java.security.spec.MGF1ParameterSpec;
import java.util.Locale;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * teslalinux: RSA with keystore private keys (AOSP keystore2 AndroidKeyStoreRSACipherSpi):
 * RSA/ECB/NoPadding, PKCS1Padding, OAEPPadding and OAEPWith{SHA-1,SHA-224,SHA-256,SHA-384,SHA-512}AndMGF1Padding.
 * A private key decrypts (purpose DECRYPT, the padding, OAEP's digest as KeyMint checks them),
 * and encrypting with a private key under NoPadding or PKCS1Padding is a raw / PKCS#1 signature
 * (purpose SIGN, digest NONE), as AOSP allows for legacy reasons. OAEP's MGF1 digest is SHA-1
 * unless an OAEPParameterSpec names another: keys from this keystore carry no
 * KM_TAG_RSA_OAEP_MGF_DIGEST, so as in AOSP the MGF1 digest asked for is not passed to KeyMint,
 * which then uses SHA-1. Encrypting with the public key is a public key operation, left to the
 * platform provider as in AOSP (the provider only takes keystore private keys).
 */
public abstract class AndroidKeyStoreRSACipherSpi extends CipherSpi {
	public static final class NoPadding extends AndroidKeyStoreRSACipherSpi { public NoPadding() { super(KeyProperties.ENCRYPTION_PADDING_NONE, null); } }
	public static final class PKCS1Padding extends AndroidKeyStoreRSACipherSpi { public PKCS1Padding() { super(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1, null); } }
	public static final class OAEPWithSHA1AndMGF1Padding extends AndroidKeyStoreRSACipherSpi { public OAEPWithSHA1AndMGF1Padding() { super(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP, KeyProperties.DIGEST_SHA1); } }
	public static final class OAEPWithSHA224AndMGF1Padding extends AndroidKeyStoreRSACipherSpi { public OAEPWithSHA224AndMGF1Padding() { super(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP, KeyProperties.DIGEST_SHA224); } }
	public static final class OAEPWithSHA256AndMGF1Padding extends AndroidKeyStoreRSACipherSpi { public OAEPWithSHA256AndMGF1Padding() { super(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP, KeyProperties.DIGEST_SHA256); } }
	public static final class OAEPWithSHA384AndMGF1Padding extends AndroidKeyStoreRSACipherSpi { public OAEPWithSHA384AndMGF1Padding() { super(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP, KeyProperties.DIGEST_SHA384); } }
	public static final class OAEPWithSHA512AndMGF1Padding extends AndroidKeyStoreRSACipherSpi { public OAEPWithSHA512AndMGF1Padding() { super(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP, KeyProperties.DIGEST_SHA512); } }

	private final String padding;
	private final String defaultOaepDigest;
	private String oaepDigest;
	private Cipher delegate;
	private int modulusSizeBytes = -1;

	AndroidKeyStoreRSACipherSpi(String padding, String oaepDigest) {
		this.padding = padding;
		this.defaultOaepDigest = oaepDigest;
	}

	private boolean oaep() {
		return KeyProperties.ENCRYPTION_PADDING_RSA_OAEP.equals(padding);
	}

	@Override
	protected void engineSetMode(String mode) throws NoSuchAlgorithmException {
		if (!"ECB".equalsIgnoreCase(mode) && !"NONE".equalsIgnoreCase(mode))
			throw new NoSuchAlgorithmException("Unsupported mode: " + mode);
	}

	@Override
	protected void engineSetPadding(String p) throws NoSuchPaddingException {
		String want = oaep() ? "OAEP" : padding.toUpperCase(Locale.ROOT);
		if (!p.toUpperCase(Locale.ROOT).startsWith(want))
			throw new NoSuchPaddingException("Unsupported padding: " + p);
	}

	@Override protected int engineGetBlockSize() { return 0; /* not a block cipher, according to the RI */ }
	@Override protected byte[] engineGetIV() { return null; /* IV never used */ }

	@Override
	protected int engineGetOutputSize(int inputLen) {
		if (modulusSizeBytes == -1)
			throw new IllegalStateException("Not initialized");
		return modulusSizeBytes;
	}

	@Override
	protected AlgorithmParameters engineGetParameters() {
		if (!oaep())
			return null;
		try {
			AlgorithmParameters params = AlgorithmParameters.getInstance("OAEP");
			params.init(new OAEPParameterSpec(oaepDigest != null ? oaepDigest : defaultOaepDigest, "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
			return params;
		} catch (NoSuchAlgorithmException e) {
			throw new ProviderException("Failed to obtain OAEP AlgorithmParameters", e);
		} catch (InvalidParameterSpecException e) {
			throw new ProviderException("Failed to initialize OAEP AlgorithmParameters with an IV", e);
		}
	}

	private static String opmodeToString(int opmode) {
		switch (opmode) {
			case Cipher.ENCRYPT_MODE: return "ENCRYPT_MODE";
			case Cipher.DECRYPT_MODE: return "DECRYPT_MODE";
			case Cipher.WRAP_MODE: return "WRAP_MODE";
			case Cipher.UNWRAP_MODE: return "UNWRAP_MODE";
			default: return String.valueOf(opmode);
		}
	}

	/* AOSP OAEPWithMGF1Padding.initAlgorithmSpecificParameters */
	private String oaepDigest(AlgorithmParameterSpec params) throws InvalidAlgorithmParameterException {
		if (params == null)
			return defaultOaepDigest;
		if (!oaep())
			throw new InvalidAlgorithmParameterException("Unexpected parameters: " + params + ". No parameters supported");
		if (!(params instanceof OAEPParameterSpec))
			throw new InvalidAlgorithmParameterException("Unsupported parameter spec: " + params + ". Only OAEPParameterSpec supported");
		OAEPParameterSpec spec = (OAEPParameterSpec) params;
		if (!"MGF1".equalsIgnoreCase(spec.getMGFAlgorithm()))
			throw new InvalidAlgorithmParameterException("Unsupported MGF: " + spec.getMGFAlgorithm() + ". Only MGF1 supported");
		String digest = spec.getDigestAlgorithm();
		if (SelfSignedCertificate.digestBits(digest) < 160)
			throw new InvalidAlgorithmParameterException("Unsupported digest: " + digest);
		AlgorithmParameterSpec mgfParams = spec.getMGFParameters();
		if (mgfParams == null)
			throw new InvalidAlgorithmParameterException("MGF parameters must be provided");
		if (!(mgfParams instanceof MGF1ParameterSpec))
			throw new InvalidAlgorithmParameterException("Unsupported MGF parameters: " + mgfParams + ". Only MGF1ParameterSpec supported");
		PSource pSource = spec.getPSource();
		if (!(pSource instanceof PSource.PSpecified))
			throw new InvalidAlgorithmParameterException("Unsupported source of encoding input P: " + pSource + ". Only pSpecifiedEmpty (PSource.PSpecified.DEFAULT) supported");
		byte[] p = ((PSource.PSpecified) pSource).getValue();
		if (p != null && p.length > 0)
			throw new InvalidAlgorithmParameterException("Unsupported source of encoding input P: " + pSource + ". Only pSpecifiedEmpty (PSource.PSpecified.DEFAULT) supported");
		return digest.toUpperCase(Locale.ROOT);
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
	protected void engineInit(int opmode, Key key, AlgorithmParameters params, SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
		AlgorithmParameterSpec spec = null;
		if (params != null) {
			if (!oaep())
				throw new InvalidAlgorithmParameterException("Unexpected parameters: " + params + ". No parameters supported");
			try {
				spec = params.getParameterSpec(OAEPParameterSpec.class);
			} catch (InvalidParameterSpecException e) {
				throw new InvalidAlgorithmParameterException("OAEP parameters required, but not found in parameters: " + params, e);
			}
		}
		engineInit(opmode, key, spec, random);
	}

	@Override
	protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params, SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
		delegate = null;
		modulusSizeBytes = -1;
		if (key == null)
			throw new InvalidKeyException("Unsupported key: null");
		if (!KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(key.getAlgorithm()))
			throw new InvalidKeyException("Unsupported key algorithm: " + key.getAlgorithm() + ". Only " + KeyProperties.KEY_ALGORITHM_RSA + " supported");
		if (!(key instanceof AndroidKeyStorePrivateKey))
			throw new InvalidKeyException("Unsupported key type: " + key);
		AndroidKeyStorePrivateKey k = (AndroidKeyStorePrivateKey) key;
		String digest = oaepDigest(params);
		boolean encrypting = opmode == Cipher.ENCRYPT_MODE || opmode == Cipher.WRAP_MODE;
		boolean decrypting = opmode == Cipher.DECRYPT_MODE || opmode == Cipher.UNWRAP_MODE;
		if (!encrypting && !decrypting)
			throw new InvalidKeyException("RSA private keys cannot be used with opmode: " + opmode);
		if (encrypting) {
			/* raw RSA / PKCS#1 signatures through Cipher, which JCA does not expose via Signature */
			if (oaep())
				throw new InvalidKeyException("RSA private keys cannot be used with " + opmodeToString(opmode) + " and padding " + padding
				    + ". Only RSA public keys supported for this mode");
			/* KeyMint keeps one padding list: a raw signature needs PaddingMode::NONE in it
			 * (the NoPadding encryption padding), a PKCS#1 one RSA_PKCS1_1_5_SIGN (PKCS1) */
			boolean raw = KeyProperties.ENCRYPTION_PADDING_NONE.equals(padding);
			k.auth.checkBeginAsymmetric(KeyProperties.PURPOSE_SIGN, raw ? KeyProperties.ENCRYPTION_PADDING_NONE : null,
			    raw ? null : KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.DIGEST_NONE);
		} else {
			k.auth.checkBeginAsymmetric(KeyProperties.PURPOSE_DECRYPT, padding, null, oaep() ? digest : null);
		}
		try {
			KeyStoreStorage.consumeUse(k);
		} catch (IOException e) {
			throw new ProviderException("Keystore operation failed", e);
		}
		try {
			Cipher c;
			if (oaep()) {
				c = Cipher.getInstance("RSA/ECB/OAEPPadding");
				c.init(opmode, k.platformKey(), new OAEPParameterSpec(digest, "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT), random);
			} else {
				/* encrypting with the private key is the private key operation of a signature
				 * (PKCS#1 type 1 padding, or none) */
				c = Cipher.getInstance("RSA/ECB/" + padding);
				c.init(opmode, k.platformKey(), random);
			}
			delegate = c;
			oaepDigest = digest;
			modulusSizeBytes = (((java.security.interfaces.RSAKey) k).getModulus().bitLength() + 7) / 8;
		} catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
			throw new ProviderException("Keystore operation failed", e);
		}
	}

	private Cipher op() {
		if (delegate == null)
			throw new IllegalStateException("Cipher not initialized");
		return delegate;
	}

	@Override protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) { return op().update(input, inputOffset, inputLen); }
	@Override protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) throws ShortBufferException { return op().update(input, inputOffset, inputLen, output, outputOffset); }
	@Override protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen) throws IllegalBlockSizeException, BadPaddingException {
		return input == null ? op().doFinal() : op().doFinal(input, inputOffset, inputLen);
	}
	@Override protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
		return op().doFinal(input, inputOffset, inputLen, output, outputOffset);
	}

	@Override
	protected byte[] engineWrap(Key key) throws IllegalBlockSizeException, InvalidKeyException {
		return op().wrap(key);
	}

	@Override
	protected Key engineUnwrap(byte[] wrappedKey, String wrappedKeyAlgorithm, int wrappedKeyType) throws InvalidKeyException, NoSuchAlgorithmException {
		return op().unwrap(wrappedKey, wrappedKeyAlgorithm, wrappedKeyType);
	}

	@Override
	protected int engineGetKeySize(Key key) throws InvalidKeyException {
		if (key instanceof java.security.interfaces.RSAKey)
			return ((java.security.interfaces.RSAKey) key).getModulus().bitLength();
		throw new InvalidKeyException("Unsupported key: " + key);
	}
}
