package android.security.keystore;

import java.security.Provider;
import java.security.Security;

/**
 * teslalinux: AOSP keystore2 AndroidKeyStoreProvider and AndroidKeyStoreBCWorkaroundProvider for
 * this runtime's software keystore (symmetric keys).
 *
 * "AndroidKeyStore" holds the KeyStore, the KeyGenerators and the SecretKeyFactories (KeyInfo).
 * The Cipher and Mac implementations for keystore keys are in "AndroidKeyStoreBCWorkaround", as on
 * Android, installed after the platform's providers with SupportedKeyClasses set to keystore
 * keys: Cipher.getInstance("AES/GCM/NoPadding") without a provider then works with a keystore key
 * (the platform's implementation turns it down, having no key material, and JCA's delayed provider
 * selection moves on), and other keys keep the platform's implementations.
 */
public final class AndroidKeyStoreProvider extends Provider {
	public static final String PROVIDER_NAME = "AndroidKeyStore";
	private static final String PACKAGE_NAME = "android.security.keystore";
	private static final String KEYSTORE_SECRET_KEY_CLASS_NAME = PACKAGE_NAME + ".AndroidKeyStoreSecretKey";
	private static final String[] HMACS = {"HmacSHA1", "HmacSHA224", "HmacSHA256", "HmacSHA384", "HmacSHA512"};

	public AndroidKeyStoreProvider() {
		super(PROVIDER_NAME, 1.0, "Android KeyStore security provider");
		put("KeyStore.AndroidKeyStore", PACKAGE_NAME + ".AndroidKeyStore");
		put("KeyGenerator.AES", PACKAGE_NAME + ".KeyGenerator$AES");
		put("SecretKeyFactory.AES", PACKAGE_NAME + ".AndroidKeyStoreSecretKeyFactorySpi");
		for (String hmac : HMACS) {
			put("KeyGenerator." + hmac, PACKAGE_NAME + ".KeyGenerator$" + hmac);
			put("SecretKeyFactory." + hmac, PACKAGE_NAME + ".AndroidKeyStoreSecretKeyFactorySpi");
		}
	}

	/** AOSP AndroidKeyStoreBCWorkaroundProvider (the symmetric half) */
	static final class BCWorkaround extends Provider {
		BCWorkaround() {
			super("AndroidKeyStoreBCWorkaround", 1.0, "Android KeyStore security provider to work around Bouncy Castle");
			putSymmetricCipherImpl("AES/ECB/NoPadding", "AesEcbNoPadding");
			putSymmetricCipherImpl("AES/ECB/PKCS7Padding", "AesEcbPkcs7Padding");
			putSymmetricCipherImpl("AES/CBC/NoPadding", "AesCbcNoPadding");
			putSymmetricCipherImpl("AES/CBC/PKCS7Padding", "AesCbcPkcs7Padding");
			putSymmetricCipherImpl("AES/CTR/NoPadding", "AesCtrNoPadding");
			putSymmetricCipherImpl("AES/GCM/NoPadding", "AesGcmNoPadding");
			for (String hmac : HMACS) {
				put("Mac." + hmac, PACKAGE_NAME + ".AndroidKeyStoreMacSpi$" + hmac);
				put("Mac." + hmac + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
			}
		}

		private void putSymmetricCipherImpl(String transformation, String impl) {
			put("Cipher." + transformation, PACKAGE_NAME + ".AndroidKeyStoreCipherSpi$" + impl);
			put("Cipher." + transformation + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
		}
	}

	/**
	 * AOSP AndroidKeyStoreProvider.install: both providers, once per process. The workaround
	 * provider goes right above Bouncy Castle: BC's ciphers take any SecretKey and fail on one
	 * without key material with a NullPointerException instead of an InvalidKeyException, which
	 * would end JCA's provider selection before a keystore key reaches the keystore's cipher.
	 */
	public static synchronized void install() {
		if (Security.getProvider(PROVIDER_NAME) != null)
			return;
		Provider[] providers = Security.getProviders();
		int bcProviderIndex = -1;
		for (int i = 0; i < providers.length; i++) {
			if ("BC".equals(providers[i].getName())) {
				bcProviderIndex = i;
				break;
			}
		}
		Security.addProvider(new AndroidKeyStoreProvider());
		Provider workaroundProvider = new BCWorkaround();
		if (bcProviderIndex != -1) {
			// Bouncy Castle provider found -- install the workaround provider above it.
			// insertProviderAt uses 1-based positions.
			Security.insertProviderAt(workaroundProvider, bcProviderIndex + 1);
		} else {
			// Bouncy Castle provider not found -- install the workaround provider at lowest
			// priority.
			Security.addProvider(workaroundProvider);
		}
	}
}
