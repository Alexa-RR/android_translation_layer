package android.security.keystore;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;

/**
 * teslalinux: the KeyGenerator of the AndroidKeyStore provider (AOSP keystore2
 * AndroidKeyStoreKeyGeneratorSpi) over this device's software keystore. engineInit checks the
 * KeyGenParameterSpec as AOSP does; engineGenerateKey makes random key material of the size the
 * spec asks for and stores it under the spec's alias with the authorization list KeyMint would
 * keep (purposes, block modes, paddings, digests, caller nonce, validity, usage count), returning a
 * non-exportable AndroidKeyStoreSecretKey.
 *
 * This device has no secure hardware, no lock screen and no biometrics: keys bound to user
 * authentication are refused at engineInit with AOSP's messages (KeyStore2ParameterUtils
 * .addUserAuthArgs), a StrongBox key fails with StrongBoxUnavailableException at generation, and
 * keys needing trusted user presence or protected confirmation fail at generation like a KeyMint
 * without that hardware.
 */
public abstract class KeyGenerator extends KeyGeneratorSpi {
	private final String algorithm;
	/* the digest of an HMAC key algorithm, or null */
	private final String hmacDigest;
	private final int defaultKeySizeBits;
	private KeyGenParameterSpec spec;
	private SecureRandom random;
	private int keySizeBits = -1;
	private String[] digests;

	KeyGenerator(String algorithm, String hmacDigest, int defaultKeySizeBits) {
		this.algorithm = algorithm;
		this.hmacDigest = hmacDigest;
		this.defaultKeySizeBits = defaultKeySizeBits;
	}

	public static final class AES extends KeyGenerator {
		public AES() { super(KeyProperties.KEY_ALGORITHM_AES, null, 128); }

		@Override
		protected void engineInit(AlgorithmParameterSpec params, SecureRandom random) throws InvalidAlgorithmParameterException {
			super.engineInit(params, random);
			int bits = keySize();
			if (bits != 128 && bits != 192 && bits != 256) {
				resetAll();
				throw new InvalidAlgorithmParameterException("Unsupported key size: " + bits + ". Supported: 128, 192, 256.");
			}
		}
	}

	public static final class HmacSHA1 extends KeyGenerator { public HmacSHA1() { super(KeyProperties.KEY_ALGORITHM_HMAC_SHA1, KeyProperties.DIGEST_SHA1, 160); } }
	public static final class HmacSHA224 extends KeyGenerator { public HmacSHA224() { super(KeyProperties.KEY_ALGORITHM_HMAC_SHA224, KeyProperties.DIGEST_SHA224, 224); } }
	public static final class HmacSHA256 extends KeyGenerator { public HmacSHA256() { super(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KeyProperties.DIGEST_SHA256, 256); } }
	public static final class HmacSHA384 extends KeyGenerator { public HmacSHA384() { super(KeyProperties.KEY_ALGORITHM_HMAC_SHA384, KeyProperties.DIGEST_SHA384, 384); } }
	public static final class HmacSHA512 extends KeyGenerator { public HmacSHA512() { super(KeyProperties.KEY_ALGORITHM_HMAC_SHA512, KeyProperties.DIGEST_SHA512, 512); } }

	int keySize() {
		return keySizeBits;
	}

	void resetAll() {
		spec = null;
		random = null;
		keySizeBits = -1;
		digests = null;
	}

	/** AOSP KeymasterUtils.isKeymasterBlockModeIndCpaCompatibleWithSymmetricCrypto */
	private static boolean isIndCpaCompatible(String blockMode) {
		return !KeyProperties.BLOCK_MODE_ECB.equalsIgnoreCase(blockMode);
	}

	@Override
	protected void engineInit(AlgorithmParameterSpec params, SecureRandom random) throws InvalidAlgorithmParameterException {
		resetAll();
		boolean success = false;
		try {
			if (params == null || !(params instanceof KeyGenParameterSpec))
				throw new InvalidAlgorithmParameterException("Cannot initialize without a " + KeyGenParameterSpec.class.getName() + " parameter");
			KeyGenParameterSpec s = (KeyGenParameterSpec) params;
			if (s.getKeystoreAlias() == null)
				throw new InvalidAlgorithmParameterException("KeyStore entry alias not provided");
			int bits = s.getKeySize() != -1 ? s.getKeySize() : defaultKeySizeBits;
			if (bits <= 0)
				throw new InvalidAlgorithmParameterException("Key size must be positive: " + bits);
			if ((bits % 8) != 0)
				throw new InvalidAlgorithmParameterException("Key size must be a multiple of 8: " + bits);
			if (s.getSignaturePaddings().length > 0)
				throw new InvalidAlgorithmParameterException("Signature paddings not supported for symmetric key algorithms");
			if ((s.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0 && s.isRandomizedEncryptionRequired()) {
				for (String mode : s.getBlockModes()) {
					if (!isIndCpaCompatible(mode))
						throw new InvalidAlgorithmParameterException("Randomized encryption (IND-CPA) required but may be violated by block mode: " + mode
						    + ". See " + KeyGenParameterSpec.class.getName() + " documentation.");
				}
			}
			if (hmacDigest != null) {
				if (bits < 64 || bits > 512)
					throw new InvalidAlgorithmParameterException("HMAC key sizes must be within 64-512 bits, inclusive.");
				digests = new String[] {hmacDigest};
				if (s.isDigestsSpecified()) {
					String[] specDigests = s.getDigests();
					if (specDigests.length != 1 || !specDigests[0].equalsIgnoreCase(hmacDigest))
						throw new InvalidAlgorithmParameterException("Unsupported digests specification: " + Arrays.asList(specDigests)
						    + ". Only " + hmacDigest + " supported for this HMAC key algorithm");
				}
			} else {
				digests = s.isDigestsSpecified() ? s.getDigests() : new String[0];
			}
			try {
				addUserAuthArgs(s);
			} catch (IllegalStateException | IllegalArgumentException e) {
				throw new InvalidAlgorithmParameterException(e);
			}
			this.spec = s;
			this.random = random;
			this.keySizeBits = bits;
			success = true;
		} finally {
			if (!success)
				resetAll();
		}
	}

	/*
	 * AOSP KeyStore2ParameterUtils.addUserAuthArgs on this device: no biometric is enrolled (no
	 * biometric sensor) and there is no secure lock screen (KeyguardManager.isDeviceSecure is
	 * false, GateKeeper has no secure user id), so a key bound to user authentication cannot be
	 * made.
	 */
	private static void addUserAuthArgs(KeyGenParameterSpec s) {
		if (!s.isUserAuthenticationRequired())
			return;
		if (s.getUserAuthenticationValidityDurationSeconds() == 0)
			throw new IllegalStateException("At least one biometric must be enrolled to create keys requiring user authentication for every use");
		throw new IllegalStateException("Secure lock screen must be enabled to create keys requiring user authentication");
	}

	@Override
	protected void engineInit(SecureRandom random) {
		throw new UnsupportedOperationException("Cannot initialize without a " + KeyGenParameterSpec.class.getName() + " parameter");
	}

	@Override
	protected void engineInit(int keysize, SecureRandom random) {
		throw new UnsupportedOperationException("Cannot initialize without a " + KeyGenParameterSpec.class.getName() + " parameter");
	}

	@Override
	protected SecretKey engineGenerateKey() {
		KeyGenParameterSpec s = spec;
		if (s == null)
			throw new IllegalStateException("Not initialized");
		/* KeyMint at the security level the spec asks for: this device has no StrongBox, and its
		 * software KeyMint does not support trusted user presence or protected confirmation */
		if (s.isStrongBoxBacked())
			throw new StrongBoxUnavailableException("Failed to generate key");
		if (s.isUserPresenceRequired() || s.isUserConfirmationRequired())
			throw new ProviderException("Keystore key generation failed", new java.security.KeyStoreException("Unsupported tag (" + (s.isUserPresenceRequired() ? "TRUSTED_USER_PRESENCE_REQUIRED" : "TRUSTED_CONFIRMATION_REQUIRED") + ")"));

		KeyAuthorizations a = new KeyAuthorizations();
		a.keySize = keySizeBits;
		a.purposes = s.getPurposes();
		a.blockModes = s.getBlockModes();
		String[] paddings = s.getEncryptionPaddings();
		for (int i = 0; i < paddings.length; i++)
			paddings[i] = KeyAuthorizations.normalizePadding(paddings[i]);
		a.encryptionPaddings = paddings;
		a.digests = digests;
		/* AOSP: KM_TAG_CALLER_NONCE when encrypting without randomized encryption */
		a.callerNonce = (s.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0 && !s.isRandomizedEncryptionRequired();
		a.validityStart = s.getKeyValidityStart();
		a.originationEnd = s.getKeyValidityForOriginationEnd();
		a.consumptionEnd = s.getKeyValidityForConsumptionEnd();
		a.remainingUsages = s.getMaxUsageCount();
		a.origin = KeyProperties.ORIGIN_GENERATED;

		byte[] material = new byte[(keySizeBits + 7) / 8];
		(random != null ? random : new SecureRandom()).nextBytes(material);
		try {
			return KeyStoreStorage.put(s.getKeystoreAlias(), algorithm, material, a);
		} catch (IOException e) {
			throw new ProviderException("Keystore key generation failed", e);
		}
	}
}
