package android.security.keystore;

public abstract class KeyProperties {
	private KeyProperties() {}
	public static final int PURPOSE_ENCRYPT = 1, PURPOSE_DECRYPT = 2, PURPOSE_SIGN = 4, PURPOSE_VERIFY = 8, PURPOSE_WRAP_KEY = 32, PURPOSE_AGREE_KEY = 64, PURPOSE_ATTEST_KEY = 128;
	public static final String KEY_ALGORITHM_RSA = "RSA", KEY_ALGORITHM_EC = "EC", KEY_ALGORITHM_AES = "AES", KEY_ALGORITHM_HMAC_SHA1 = "HmacSHA1",
	                           KEY_ALGORITHM_HMAC_SHA224 = "HmacSHA224", KEY_ALGORITHM_HMAC_SHA256 = "HmacSHA256", KEY_ALGORITHM_HMAC_SHA384 = "HmacSHA384",
	                           KEY_ALGORITHM_HMAC_SHA512 = "HmacSHA512", KEY_ALGORITHM_3DES = "DESede", KEY_ALGORITHM_XDH = "XDH";
	public static final String BLOCK_MODE_ECB = "ECB", BLOCK_MODE_CBC = "CBC", BLOCK_MODE_CTR = "CTR", BLOCK_MODE_GCM = "GCM";
	public static final String ENCRYPTION_PADDING_NONE = "NoPadding", ENCRYPTION_PADDING_PKCS7 = "PKCS7Padding", ENCRYPTION_PADDING_RSA_PKCS1 = "PKCS1Padding", ENCRYPTION_PADDING_RSA_OAEP = "OAEPPadding";
	public static final String SIGNATURE_PADDING_RSA_PKCS1 = "PKCS1", SIGNATURE_PADDING_RSA_PSS = "PSS";
	public static final String DIGEST_NONE = "NONE", DIGEST_MD5 = "MD5", DIGEST_SHA1 = "SHA-1", DIGEST_SHA224 = "SHA-224", DIGEST_SHA256 = "SHA-256", DIGEST_SHA384 = "SHA-384", DIGEST_SHA512 = "SHA-512";
	public static final int ORIGIN_GENERATED = 1, ORIGIN_IMPORTED = 2, ORIGIN_UNKNOWN = 4, ORIGIN_SECURELY_IMPORTED = 8;
	public static final int AUTH_BIOMETRIC_STRONG = 2, AUTH_DEVICE_CREDENTIAL = 1;
	public static final int SECURITY_LEVEL_UNKNOWN = -2, SECURITY_LEVEL_UNKNOWN_SECURE = -1, SECURITY_LEVEL_SOFTWARE = 0, SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1, SECURITY_LEVEL_STRONGBOX = 2;
	public static final int UNRESTRICTED_USAGE_COUNT = -1;

	/* AOSP: keystore namespaces (KeyGenParameterSpec.Builder.setNamespace/setUid) */
	public static final int NAMESPACE_APPLICATION = -1;
	public static final int NAMESPACE_WIFI = 102;
	public static final int NAMESPACE_LOCKSETTINGS = 103;
	/** AOSP android.security.KeyStore.UID_SELF */
	static final int UID_SELF = -1;

	/** AOSP: the legacy uid of a namespace. */
	public static int namespaceToLegacyUid(int namespace) {
		switch (namespace) {
			case NAMESPACE_APPLICATION:
				return UID_SELF;
			case NAMESPACE_WIFI:
				return android.os.Process.WIFI_UID;
			default:
				throw new IllegalArgumentException("No UID corresponding to namespace " + namespace);
		}
	}

	/** AOSP: the namespace of a legacy uid. */
	public static int legacyUidToNamespace(int uid) {
		switch (uid) {
			case UID_SELF:
				return NAMESPACE_APPLICATION;
			case android.os.Process.WIFI_UID:
				return NAMESPACE_WIFI;
			default:
				throw new IllegalArgumentException("No namespace corresponding to uid " + uid);
		}
	}
}
