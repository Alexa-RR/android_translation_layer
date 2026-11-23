/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.keystore;

import android.security.KeyPairGeneratorSpec;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyPairGeneratorSpi;
import java.security.KeyStoreException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * teslalinux: AOSP keystore2 AndroidKeyStoreKeyPairGeneratorSpi over this device's software
 * keystore. initialize() takes a KeyGenParameterSpec or the legacy KeyPairGeneratorSpec and checks
 * it as AOSP does (key sizes, the RSA public exponent, EC curves, IND-CPA paddings, user
 * authentication, attest keys); generateKeyPair() makes the pair on the platform provider, the
 * certificate KeyMint would make (SelfSignedCertificate), and stores both under the alias with the
 * key's authorization list.
 *
 * This device's keystore has no attestation keys and no StrongBox: a spec with an attestation
 * challenge (or an attest key) fails at generation with KeyMint's ATTESTATION_KEYS_NOT_PROVISIONED,
 * as on a device whose keys were never provisioned; PURPOSE_ATTEST_KEY keys need the attestation
 * feature and fail the same way; StrongBox fails with StrongBoxUnavailableException. Curve 25519
 * keys (x25519, ed25519) are not supported yet (listed in TODO-real).
 */
public abstract class AndroidKeyStoreKeyPairGeneratorSpi extends KeyPairGeneratorSpi {
	public static class RSA extends AndroidKeyStoreKeyPairGeneratorSpi {
		public RSA() { super(KeyProperties.KEY_ALGORITHM_RSA); }
	}

	public static class EC extends AndroidKeyStoreKeyPairGeneratorSpi {
		public EC() { super(KeyProperties.KEY_ALGORITHM_EC); }
	}

	/** AOSP: XDH represents the Curve 25519 providers; it is treated as EC */
	public static class XDH extends AndroidKeyStoreKeyPairGeneratorSpi {
		public XDH() { super(KeyProperties.KEY_ALGORITHM_EC); }
	}

	private static final int EC_DEFAULT_KEY_SIZE = 256;
	private static final int RSA_DEFAULT_KEY_SIZE = 2048;
	private static final int RSA_MIN_KEY_SIZE = 512;
	private static final int RSA_MAX_KEY_SIZE = 8192;
	private static final BigInteger UINT64_MAX_VALUE = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

	private static final Map<String, Integer> SUPPORTED_EC_CURVE_NAME_TO_SIZE = new HashMap<String, Integer>();
	private static final List<String> SUPPORTED_EC_CURVE_NAMES = new ArrayList<String>();
	private static final List<Integer> SUPPORTED_EC_CURVE_SIZES = new ArrayList<Integer>();
	static {
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-224", 224);
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp224r1", 224);
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-256", 256);
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp256r1", 256);
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("prime256v1", 256);
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-384", 384);
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp384r1", 384);
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-521", 521);
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp521r1", 521);
		// Aliases for Curve 25519
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put(KeyStoreCurve25519.CURVE_X25519, 256);
		SUPPORTED_EC_CURVE_NAME_TO_SIZE.put(KeyStoreCurve25519.CURVE_ED25519, 256);
		SUPPORTED_EC_CURVE_NAMES.addAll(SUPPORTED_EC_CURVE_NAME_TO_SIZE.keySet());
		Collections.sort(SUPPORTED_EC_CURVE_NAMES);
		SUPPORTED_EC_CURVE_SIZES.addAll(new HashSet<Integer>(SUPPORTED_EC_CURVE_NAME_TO_SIZE.values()));
		Collections.sort(SUPPORTED_EC_CURVE_SIZES);
	}

	private final String originalAlgorithm;
	private String algorithm;
	private KeyGenParameterSpec spec;
	private int keySizeBits;
	private BigInteger rsaPublicExponent;
	private SecureRandom rng;

	protected AndroidKeyStoreKeyPairGeneratorSpi(String algorithm) {
		this.originalAlgorithm = algorithm;
	}

	@Override
	public void initialize(int keysize, SecureRandom random) {
		throw new IllegalArgumentException(KeyGenParameterSpec.class.getName() + " or " + KeyPairGeneratorSpec.class.getName()
		    + " required to initialize this KeyPairGenerator");
	}

	@SuppressWarnings("deprecation")
	@Override
	public void initialize(AlgorithmParameterSpec params, SecureRandom random) throws InvalidAlgorithmParameterException {
		resetAll();
		boolean success = false;
		try {
			if (params == null)
				throw new InvalidAlgorithmParameterException("Must supply params of type " + KeyGenParameterSpec.class.getName()
				    + " or " + KeyPairGeneratorSpec.class.getName());
			KeyGenParameterSpec s;
			String alg = originalAlgorithm;
			if (params instanceof KeyGenParameterSpec) {
				s = (KeyGenParameterSpec) params;
			} else if (params instanceof KeyPairGeneratorSpec) {
				KeyPairGeneratorSpec legacy = (KeyPairGeneratorSpec) params;
				try {
					if (legacy.getKeyType() != null) {
						alg = legacy.getKeyType().toUpperCase(Locale.ROOT);
						if (!KeyProperties.KEY_ALGORITHM_RSA.equals(alg) && !KeyProperties.KEY_ALGORITHM_EC.equals(alg))
							throw new InvalidAlgorithmParameterException("Invalid key type in parameters",
							    new IllegalArgumentException("Unsupported key algorithm: " + legacy.getKeyType()));
					}
					s = buildKeyGenParameterSpecFromLegacy(legacy, alg);
				} catch (NullPointerException | IllegalArgumentException e) {
					throw new InvalidAlgorithmParameterException(e);
				}
			} else if (params instanceof java.security.spec.NamedParameterSpec) {
				java.security.spec.NamedParameterSpec namedSpec = (java.security.spec.NamedParameterSpec) params;
				// Android Keystore cannot support initialization from a NamedParameterSpec
				// because an alias for the key is needed (a KeyGenParameterSpec cannot be
				// constructed).
				if (namedSpec.getName().equalsIgnoreCase(java.security.spec.NamedParameterSpec.X25519.getName())
				    || namedSpec.getName().equalsIgnoreCase(java.security.spec.NamedParameterSpec.ED25519.getName())) {
					throw new IllegalArgumentException("This KeyPairGenerator cannot be initialized using NamedParameterSpec."
					    + " use " + KeyGenParameterSpec.class.getName() + " or " + KeyPairGeneratorSpec.class.getName());
				} else {
					throw new InvalidAlgorithmParameterException("Unsupported algorithm specified via NamedParameterSpec: " + namedSpec.getName());
				}
			} else {
				throw new InvalidAlgorithmParameterException("Unsupported params class: " + params.getClass().getName()
				    + ". Supported: " + KeyGenParameterSpec.class.getName() + ", " + KeyPairGeneratorSpec.class.getName());
			}
			spec = s;
			algorithm = alg;
			keySizeBits = s.getKeySize();
			initAlgorithmSpecificParameters();
			checkCorrectKeyPurposeForCurve(s);
			if (keySizeBits == -1)
				keySizeBits = KeyProperties.KEY_ALGORITHM_EC.equals(alg) ? EC_DEFAULT_KEY_SIZE : RSA_DEFAULT_KEY_SIZE;
			checkValidKeySize(alg, keySizeBits, s.isStrongBoxBacked());
			if (s.getKeystoreAlias() == null)
				throw new InvalidAlgorithmParameterException("KeyStore entry alias not provided");
			try {
				if ((s.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0 && s.isRandomizedEncryptionRequired()) {
					for (String padding : s.getEncryptionPaddings()) {
						if (!AndroidKeyStore.isIndCpaCompatibleAsymmetricPadding(padding))
							throw new InvalidAlgorithmParameterException("Randomized encryption (IND-CPA) required but may be violated by padding scheme: "
							    + padding + ". See " + KeyGenParameterSpec.class.getName() + " documentation.");
					}
				}
				for (String padding : s.getSignaturePaddings()) {
					if (!KeyProperties.SIGNATURE_PADDING_RSA_PKCS1.equalsIgnoreCase(padding) && !KeyProperties.SIGNATURE_PADDING_RSA_PSS.equalsIgnoreCase(padding))
						throw new IllegalArgumentException("Unsupported signature padding scheme: " + padding);
				}
				KeyGenerator.addUserAuthArgs(s);
			} catch (IllegalArgumentException | IllegalStateException e) {
				throw new InvalidAlgorithmParameterException(e);
			}
			rng = random;
			if (s.getAttestKeyAlias() != null && s.getAttestationChallenge() == null)
				throw new InvalidAlgorithmParameterException("AttestKey specified but no attestation challenge provided");
			if ((s.getPurposes() & KeyProperties.PURPOSE_ATTEST_KEY) != 0 && s.getPurposes() != KeyProperties.PURPOSE_ATTEST_KEY)
				throw new InvalidAlgorithmParameterException("PURPOSE_ATTEST_KEY may not be specified with any other purposes");
			success = true;
		} finally {
			if (!success)
				resetAll();
		}
	}

	/* AOSP buildKeyGenParameterSpecFromLegacy */
	@SuppressWarnings("deprecation")
	private static KeyGenParameterSpec buildKeyGenParameterSpecFromLegacy(KeyPairGeneratorSpec legacySpec, String algorithm) {
		KeyGenParameterSpec.Builder specBuilder;
		if (KeyProperties.KEY_ALGORITHM_EC.equals(algorithm)) {
			specBuilder = new KeyGenParameterSpec.Builder(legacySpec.getKeystoreAlias(), KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY);
			/* authorized to be used with any digest (including no digest); MD5 was never offered
			 * for Android Keystore for ECDSA */
			specBuilder.setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA224, KeyProperties.DIGEST_SHA256,
			    KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512);
		} else {
			specBuilder = new KeyGenParameterSpec.Builder(legacySpec.getKeystoreAlias(),
			    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY);
			specBuilder.setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_MD5, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA224,
			    KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512);
			specBuilder.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1, KeyProperties.ENCRYPTION_PADDING_RSA_OAEP);
			specBuilder.setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS);
			/* disable randomized encryption requirement to support encryption padding NONE */
			specBuilder.setRandomizedEncryptionRequired(false);
		}
		if (legacySpec.getKeySize() != -1)
			specBuilder.setKeySize(legacySpec.getKeySize());
		if (legacySpec.getAlgorithmParameterSpec() != null)
			specBuilder.setAlgorithmParameterSpec(legacySpec.getAlgorithmParameterSpec());
		specBuilder.setCertificateSubject(legacySpec.getSubjectDN());
		specBuilder.setCertificateSerialNumber(legacySpec.getSerialNumber());
		specBuilder.setCertificateNotBefore(legacySpec.getStartDate());
		specBuilder.setCertificateNotAfter(legacySpec.getEndDate());
		specBuilder.setUserAuthenticationRequired(false);
		return specBuilder.build();
	}

	private void resetAll() {
		algorithm = null;
		spec = null;
		keySizeBits = 0;
		rsaPublicExponent = null;
		rng = null;
		ecCurveName = null;
	}

	private String ecCurveName;

	private void initAlgorithmSpecificParameters() throws InvalidAlgorithmParameterException {
		AlgorithmParameterSpec algSpecificSpec = spec.getAlgorithmParameterSpec();
		if (KeyProperties.KEY_ALGORITHM_RSA.equals(algorithm)) {
			BigInteger publicExponent = null;
			if (algSpecificSpec instanceof RSAKeyGenParameterSpec) {
				RSAKeyGenParameterSpec rsaSpec = (RSAKeyGenParameterSpec) algSpecificSpec;
				if (keySizeBits == -1) {
					keySizeBits = rsaSpec.getKeysize();
				} else if (keySizeBits != rsaSpec.getKeysize()) {
					throw new InvalidAlgorithmParameterException("RSA key size must match  between " + spec + " and " + algSpecificSpec
					    + ": " + keySizeBits + " vs " + rsaSpec.getKeysize());
				}
				publicExponent = rsaSpec.getPublicExponent();
			} else if (algSpecificSpec != null) {
				throw new InvalidAlgorithmParameterException("RSA may only use RSAKeyGenParameterSpec");
			}
			if (publicExponent == null)
				publicExponent = RSAKeyGenParameterSpec.F4;
			if (publicExponent.compareTo(BigInteger.ZERO) < 1)
				throw new InvalidAlgorithmParameterException("RSA public exponent must be positive: " + publicExponent);
			if ((publicExponent.signum() == -1) || (publicExponent.compareTo(UINT64_MAX_VALUE) > 0))
				throw new InvalidAlgorithmParameterException("Unsupported RSA public exponent: " + publicExponent + ". Maximum supported value: " + UINT64_MAX_VALUE);
			rsaPublicExponent = publicExponent;
		} else {
			if (algSpecificSpec instanceof ECGenParameterSpec) {
				ECGenParameterSpec ecSpec = (ECGenParameterSpec) algSpecificSpec;
				ecCurveName = ecSpec.getName();
				Integer ecSpecKeySizeBits = SUPPORTED_EC_CURVE_NAME_TO_SIZE.get(ecCurveName.toLowerCase(Locale.US));
				if (ecSpecKeySizeBits == null)
					throw new InvalidAlgorithmParameterException("Unsupported EC curve name: " + ecCurveName + ". Supported: " + SUPPORTED_EC_CURVE_NAMES);
				if (keySizeBits == -1) {
					keySizeBits = ecSpecKeySizeBits;
				} else if (keySizeBits != ecSpecKeySizeBits) {
					throw new InvalidAlgorithmParameterException("EC key size must match  between " + spec + " and " + algSpecificSpec
					    + ": " + keySizeBits + " vs " + ecSpecKeySizeBits);
				}
			} else if (algSpecificSpec != null) {
				throw new InvalidAlgorithmParameterException("EC may only use ECGenParameterSpec");
			}
		}
	}

	private static void checkValidKeySize(String algorithm, int keySize, boolean isStrongBoxBacked) throws InvalidAlgorithmParameterException {
		if (KeyProperties.KEY_ALGORITHM_EC.equals(algorithm)) {
			if (isStrongBoxBacked && keySize != 256)
				throw new InvalidAlgorithmParameterException("Unsupported StrongBox EC key size: " + keySize + " bits. Supported: 256");
			if (!SUPPORTED_EC_CURVE_SIZES.contains(keySize))
				throw new InvalidAlgorithmParameterException("Unsupported EC key size: " + keySize + " bits. Supported: " + SUPPORTED_EC_CURVE_SIZES);
		} else {
			if (keySize < RSA_MIN_KEY_SIZE || keySize > RSA_MAX_KEY_SIZE)
				throw new InvalidAlgorithmParameterException("RSA key size must be >= " + RSA_MIN_KEY_SIZE + " and <= " + RSA_MAX_KEY_SIZE);
		}
	}

	/* AOSP checkCorrectKeyPurposeForCurve: x25519 is for key agreement only, ed25519 for signing
	 * and attesting */
	private void checkCorrectKeyPurposeForCurve(KeyGenParameterSpec s) throws InvalidAlgorithmParameterException {
		if (!KeyStoreCurve25519.isCurve25519(ecCurveName))
			return;
		if (ecCurveName.equalsIgnoreCase(KeyStoreCurve25519.CURVE_X25519) && s.getPurposes() != KeyProperties.PURPOSE_AGREE_KEY) {
			throw new InvalidAlgorithmParameterException("x25519 may only be used for key agreement.");
		} else if (ecCurveName.equalsIgnoreCase(KeyStoreCurve25519.CURVE_ED25519) && !hasOnlyAllowedPurposeForEd25519(s.getPurposes())) {
			throw new InvalidAlgorithmParameterException("ed25519 may not be used for key agreement.");
		}
	}

	private static boolean hasOnlyAllowedPurposeForEd25519(int purpose) {
		final int allowedPurposes = KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY | KeyProperties.PURPOSE_ATTEST_KEY;
		boolean hasAllowedPurpose = (purpose & allowedPurposes) != 0;
		boolean hasDisallowedPurpose = (purpose & ~allowedPurposes) != 0;
		return hasAllowedPurpose && !hasDisallowedPurpose;
	}

	private static String curveName(int bits) {
		switch (bits) {
			case 224: return "secp224r1";
			case 384: return "secp384r1";
			case 521: return "secp521r1";
			default: return "secp256r1";
		}
	}

	/* an x25519 or ed25519 key pair (KeyStoreCurve25519), with its certificate */
	private KeyPair generateCurve25519KeyPair(KeyGenParameterSpec s) {
		String storage = KeyStoreCurve25519.storageAlgorithm(ecCurveName);
		byte[][] pair = KeyStoreCurve25519.generate(ecCurveName, rng);
		KeyAuthorizations a = KeyAuthorizations.of(keySizeBits, KeyProperties.ORIGIN_GENERATED, s.getPurposes(), s.getBlockModes(),
		    s.getEncryptionPaddings(), s.getSignaturePaddings(), s.isDigestsSpecified() ? s.getDigests() : new String[0],
		    s.isRandomizedEncryptionRequired(), s.getKeyValidityStart(), s.getKeyValidityForOriginationEnd(),
		    s.getKeyValidityForConsumptionEnd(), s.getMaxUsageCount());
		X509Certificate cert = SelfSignedCertificate.makeCurve25519(storage, pair[1], pair[0], s.getPurposes(),
		    s.getCertificateSubject(), s.getCertificateSerialNumber(), s.getCertificateNotBefore(), s.getCertificateNotAfter());
		try {
			AndroidKeyStorePrivateKey priv = KeyStoreStorage.putKeyPair(s.getKeystoreAlias(), storage, pair[0], a,
			    Collections.singletonList(cert.getEncoded()));
			return new KeyPair(priv.getPublicKey(), priv);
		} catch (IOException | CertificateEncodingException e) {
			throw new ProviderException("Failed to generate key pair.", e);
		}
	}

	@Override
	public KeyPair generateKeyPair() {
		KeyGenParameterSpec s = spec;
		if (s == null)
			throw new IllegalStateException("Not initialized");
		/* KeyMint at the security level the spec asks for (see the class comment) */
		if (s.isStrongBoxBacked())
			throw new StrongBoxUnavailableException("Failed to generated key pair.");
		if (s.getAttestationChallenge() != null || (s.getPurposes() & KeyProperties.PURPOSE_ATTEST_KEY) != 0)
			throw new ProviderException("Failed to generate key pair.", new KeyStoreException("Attestation keys are not provisioned on this device (ATTESTATION_KEYS_NOT_PROVISIONED)"));
		if (s.isUserPresenceRequired() || s.isUserConfirmationRequired())
			throw new ProviderException("Failed to generate key pair.", new KeyStoreException("Unsupported tag (" + (s.isUserPresenceRequired() ? "TRUSTED_USER_PRESENCE_REQUIRED" : "TRUSTED_CONFIRMATION_REQUIRED") + ")"));

		if (KeyStoreCurve25519.isCurve25519(ecCurveName))
			return generateCurve25519KeyPair(s);

		KeyPair platform;
		try {
			KeyPairGenerator g = KeyPairGenerator.getInstance(algorithm);
			SecureRandom random = rng != null ? rng : new SecureRandom();
			if (KeyProperties.KEY_ALGORITHM_RSA.equals(algorithm))
				g.initialize(new RSAKeyGenParameterSpec(keySizeBits, rsaPublicExponent), random);
			else
				g.initialize(new ECGenParameterSpec(curveName(keySizeBits)), random);
			platform = g.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new ProviderException("Failed to generate key pair.", e);
		}

		KeyAuthorizations a = KeyAuthorizations.of(keySizeBits, KeyProperties.ORIGIN_GENERATED, s.getPurposes(), s.getBlockModes(),
		    s.getEncryptionPaddings(), s.getSignaturePaddings(), s.isDigestsSpecified() ? s.getDigests() : new String[0],
		    s.isRandomizedEncryptionRequired(), s.getKeyValidityStart(), s.getKeyValidityForOriginationEnd(),
		    s.getKeyValidityForConsumptionEnd(), s.getMaxUsageCount());
		String digest = SelfSignedCertificate.certificateDigest(algorithm, keySizeBits, a, s.isDigestsSpecified());
		X509Certificate cert = SelfSignedCertificate.make(algorithm, platform.getPublic(), platform.getPrivate(), digest, s.getPurposes(),
		    s.getCertificateSubject(), s.getCertificateSerialNumber(), s.getCertificateNotBefore(), s.getCertificateNotAfter());
		try {
			AndroidKeyStorePrivateKey priv = KeyStoreStorage.putKeyPair(s.getKeystoreAlias(), algorithm, platform.getPrivate().getEncoded(), a,
			    Collections.singletonList(cert.getEncoded()));
			return new KeyPair(priv.getPublicKey(), priv);
		} catch (IOException | CertificateEncodingException e) {
			throw new ProviderException("Failed to generate key pair.", e);
		}
	}
}
