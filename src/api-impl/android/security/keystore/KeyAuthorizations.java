package android.security.keystore;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The authorization list of a keystore key (what KeyMint keeps with a key as tags and enforces on
 * every operation), for this runtime's software keystore: purposes, block modes, encryption
 * paddings, digests, whether the caller may supply the IV (KM_TAG_CALLER_NONCE), the validity
 * period, the usage count limit and the origin. Kept next to the key material by KeyStoreStorage
 * as "name=value" lines.
 *
 * What needs secure hardware (user authentication bound keys, trusted user presence, protected
 * confirmation, StrongBox) does not exist on this device; KeyGenerator refuses such keys the way
 * Android does on a device without that hardware, so no stored key carries those tags.
 */
final class KeyAuthorizations {
	int keySize;
	int purposes;
	String[] blockModes = new String[0];
	String[] encryptionPaddings = new String[0];
	/** RSA key pairs: PKCS1, PSS */
	String[] signaturePaddings = new String[0];
	String[] digests = new String[0];
	/** KM_TAG_CALLER_NONCE: set when the key was made without randomized encryption */
	boolean callerNonce;
	Date validityStart;
	Date originationEnd;
	Date consumptionEnd;
	/** remaining uses, or KeyProperties.UNRESTRICTED_USAGE_COUNT */
	int remainingUsages = KeyProperties.UNRESTRICTED_USAGE_COUNT;
	int origin = KeyProperties.ORIGIN_GENERATED;
	/**
	 * A secret key stored before this list existed (the file has only its algorithm and material):
	 * no restrictions were recorded for it, so it is used as AOSP uses its legacy keys (keys of
	 * the pre-M KeyPairGeneratorSpec, and private keys imported without a KeyProtection:
	 * AndroidKeyStoreSpi.getLegacyKeyProtectionParameter): every purpose, digest, block mode and
	 * padding, a caller IV allowed (no randomized encryption requirement), no validity period or
	 * usage limit. KeyInfo lists no block modes or paddings for it.
	 */
	boolean unrestricted;

	/**
	 * AOSP's legacy authorizations of a key pair (AndroidKeyStoreSpi.getLegacyKeyProtectionParameter,
	 * AndroidKeyStoreKeyPairGeneratorSpi.buildKeyGenParameterSpecFromLegacy): EC keys sign and
	 * verify with any digest; RSA keys also encrypt and decrypt, with any digest, every encryption
	 * padding (none, PKCS#1, OAEP) and signature padding (PKCS#1, PSS), without the randomized
	 * encryption requirement.
	 */
	static KeyAuthorizations legacyKeyPair(String algorithm, int keySize, int origin) {
		KeyAuthorizations a = new KeyAuthorizations();
		a.keySize = keySize;
		a.origin = origin;
		if (KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(algorithm)) {
			a.purposes = KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY;
			a.digests = new String[] {KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA224,
			                          KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512};
		} else {
			a.purposes = KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY;
			a.digests = new String[] {KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_MD5, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA224,
			                          KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512};
			a.encryptionPaddings = new String[] {KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1, KeyProperties.ENCRYPTION_PADDING_RSA_OAEP};
			a.signaturePaddings = new String[] {KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS};
			a.callerNonce = true;
		}
		return a;
	}

	static KeyAuthorizations unrestricted(int keySize, int origin) {
		KeyAuthorizations a = new KeyAuthorizations();
		a.unrestricted = true;
		a.keySize = keySize;
		a.origin = origin;
		a.purposes = KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY;
		a.callerNonce = true;
		return a;
	}

	/**
	 * The list KeyMint keeps for a key made or imported with these parameters (a
	 * KeyGenParameterSpec's or KeyProtection's); encryption paddings are normalized (PKCS5Padding
	 * is PKCS7Padding), and KM_TAG_CALLER_NONCE goes with encryption without randomized encryption.
	 */
	static KeyAuthorizations of(int keySize, int origin, int purposes, String[] blockModes, String[] encryptionPaddings, String[] signaturePaddings,
	                            String[] digests, boolean randomizedEncryptionRequired, Date validityStart, Date originationEnd, Date consumptionEnd,
	                            int maxUsageCount) {
		KeyAuthorizations a = new KeyAuthorizations();
		a.keySize = keySize;
		a.origin = origin;
		a.purposes = purposes;
		a.blockModes = blockModes.clone();
		a.encryptionPaddings = encryptionPaddings.clone();
		for (int i = 0; i < a.encryptionPaddings.length; i++)
			a.encryptionPaddings[i] = normalizePadding(a.encryptionPaddings[i]);
		a.signaturePaddings = signaturePaddings.clone();
		a.digests = digests.clone();
		a.callerNonce = (purposes & KeyProperties.PURPOSE_ENCRYPT) != 0 && !randomizedEncryptionRequired;
		a.validityStart = validityStart;
		a.originationEnd = originationEnd;
		a.consumptionEnd = consumptionEnd;
		a.remainingUsages = maxUsageCount;
		return a;
	}

	/* ---- persistence ---- */

	private static String join(String[] values) {
		return String.join(",", values);
	}

	private static String[] split(String value) {
		return value.isEmpty() ? new String[0] : value.split(",");
	}

	List<String> toLines() {
		List<String> lines = new ArrayList<>();
		lines.add("keySize=" + keySize);
		lines.add("purposes=" + purposes);
		lines.add("blockModes=" + join(blockModes));
		lines.add("encryptionPaddings=" + join(encryptionPaddings));
		lines.add("signaturePaddings=" + join(signaturePaddings));
		lines.add("digests=" + join(digests));
		lines.add("callerNonce=" + (callerNonce ? 1 : 0));
		if (validityStart != null)
			lines.add("validityStart=" + validityStart.getTime());
		if (originationEnd != null)
			lines.add("originationEnd=" + originationEnd.getTime());
		if (consumptionEnd != null)
			lines.add("consumptionEnd=" + consumptionEnd.getTime());
		lines.add("remainingUsages=" + remainingUsages);
		lines.add("origin=" + origin);
		if (unrestricted)
			lines.add("unrestricted=1");
		return lines;
	}

	/** the list from the lines after the key material; null when there are none (an old key file) */
	static KeyAuthorizations fromLines(List<String> lines) {
		if (lines.isEmpty())
			return null;
		KeyAuthorizations a = new KeyAuthorizations();
		for (String line : lines) {
			int eq = line.indexOf('=');
			if (eq <= 0)
				continue;
			String name = line.substring(0, eq), value = line.substring(eq + 1).trim();
			switch (name) {
				case "keySize": a.keySize = Integer.parseInt(value); break;
				case "purposes": a.purposes = Integer.parseInt(value); break;
				case "blockModes": a.blockModes = split(value); break;
				case "encryptionPaddings": a.encryptionPaddings = split(value); break;
				case "signaturePaddings": a.signaturePaddings = split(value); break;
				case "digests": a.digests = split(value); break;
				case "callerNonce": a.callerNonce = "1".equals(value); break;
				case "validityStart": a.validityStart = new Date(Long.parseLong(value)); break;
				case "originationEnd": a.originationEnd = new Date(Long.parseLong(value)); break;
				case "consumptionEnd": a.consumptionEnd = new Date(Long.parseLong(value)); break;
				case "remainingUsages": a.remainingUsages = Integer.parseInt(value); break;
				case "origin": a.origin = Integer.parseInt(value); break;
				case "unrestricted": a.unrestricted = "1".equals(value); break;
				default: break;
			}
		}
		return a;
	}

	/* ---- enforcement (KeyMint begin()) ---- */

	private static boolean containsIgnoreCase(String[] values, String value) {
		for (String v : values) {
			if (v.equalsIgnoreCase(value))
				return true;
		}
		return false;
	}

	/** AOSP KeyStoreCryptoOperationUtils.getInvalidKeyException for KeyMint's errors */
	private static InvalidKeyException failed(String keymintError) {
		return new InvalidKeyException("Keystore operation failed", new KeyStoreException(keymintError));
	}

	/**
	 * The checks KeyMint makes when an operation begins.
	 *
	 * @param purpose KeyProperties.PURPOSE_*
	 * @param blockMode the transformation's block mode, or null (MAC)
	 * @param padding the transformation's padding, or null (MAC)
	 * @param digest the MAC's digest, or null (cipher)
	 */
	void checkBegin(int purpose, String blockMode, String padding, String digest) throws InvalidKeyException {
		if (remainingUsages == 0)
			throw failed("Key not found (its usage count limit was reached)");
		Date now = new Date();
		if (validityStart != null && now.before(validityStart))
			throw new KeyNotYetValidException();
		boolean origination = purpose == KeyProperties.PURPOSE_ENCRYPT || purpose == KeyProperties.PURPOSE_SIGN;
		Date end = origination ? originationEnd : consumptionEnd;
		if (end != null && now.after(end))
			throw new KeyExpiredException();
		if (unrestricted)
			return;
		if ((purposes & purpose) == 0)
			throw failed("Incompatible purpose");
		if (blockMode != null && !containsIgnoreCase(blockModes, blockMode))
			throw failed("Incompatible block mode");
		if (padding != null && !containsIgnoreCase(encryptionPaddings, padding))
			throw failed("Incompatible padding mode");
		if (digest != null && digests.length > 0 && !containsIgnoreCase(digests, digest))
			throw failed("Incompatible digest");
	}

	/** KM_TAG_CALLER_NONCE: an IV from the caller when encrypting needs it */
	void checkCallerNonce() throws InvalidAlgorithmParameterException {
		if (!unrestricted && !callerNonce)
			throw new InvalidAlgorithmParameterException("Caller-provided IV not permitted");
	}

	/**
	 * KeyMint begin() for a key pair's private key operation.
	 *
	 * @param purpose PURPOSE_SIGN (signatures, raw RSA with the private key), PURPOSE_DECRYPT or
	 *     PURPOSE_AGREE_KEY
	 * @param encryptionPadding the cipher's padding, or null
	 * @param signaturePadding PKCS1 / PSS for RSA signatures, or null
	 * @param digest the operation's digest (NONE for raw), or null when the operation has none
	 */
	void checkBeginAsymmetric(int purpose, String encryptionPadding, String signaturePadding, String digest) throws InvalidKeyException {
		checkBegin(purpose, null, null, null);
		if (unrestricted)
			return;
		if (encryptionPadding != null && !containsIgnoreCase(encryptionPaddings, encryptionPadding))
			throw failed("Incompatible padding mode");
		if (signaturePadding != null && !containsIgnoreCase(signaturePaddings, signaturePadding))
			throw failed("Incompatible padding mode");
		/* KeyMint: an asymmetric operation's digest must be one the key authorizes */
		if (digest != null && !containsIgnoreCase(digests, digest))
			throw failed("Incompatible digest");
	}

	KeyInfo toKeyInfo(String alias) {
		String[] modes = unrestricted ? new String[0] : blockModes;
		String[] paddings = unrestricted ? new String[0] : encryptionPaddings;
		String[] sigPaddings = unrestricted ? new String[0] : signaturePaddings;
		return new KeyInfo(alias, false /* not inside secure hardware: this device has none */, origin, keySize,
		    validityStart, originationEnd, consumptionEnd, purposes, paddings, sigPaddings, digests, modes,
		    false, 0, 0, false, false, false, false, false,
		    KeyProperties.SECURITY_LEVEL_SOFTWARE, remainingUsages);
	}

	@Override
	public String toString() {
		return "KeyAuthorizations{purposes=" + purposes + " modes=" + Arrays.toString(blockModes) + " paddings=" + Arrays.toString(encryptionPaddings)
		    + " digests=" + Arrays.toString(digests) + " callerNonce=" + callerNonce + " uses=" + remainingUsages + (unrestricted ? " unrestricted" : "") + "}";
	}

	static String normalizePadding(String padding) {
		return padding == null ? null : padding.toUpperCase(Locale.ROOT).equals("PKCS5PADDING") ? KeyProperties.ENCRYPTION_PADDING_PKCS7 : padding;
	}
}
