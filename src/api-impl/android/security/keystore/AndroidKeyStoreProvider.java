package android.security.keystore;

import java.security.Provider;
import java.security.Security;

/**
 * teslalinux: AOSP keystore2 AndroidKeyStoreProvider and AndroidKeyStoreBCWorkaroundProvider for
 * this runtime's software keystore.
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
	private static final String KEYSTORE_PRIVATE_KEY_CLASS_NAME = PACKAGE_NAME + ".AndroidKeyStorePrivateKey";
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
		put("KeyPairGenerator.EC", PACKAGE_NAME + ".AndroidKeyStoreKeyPairGeneratorSpi$EC");
		put("KeyPairGenerator.RSA", PACKAGE_NAME + ".AndroidKeyStoreKeyPairGeneratorSpi$RSA");
		putKeyFactoryImpl("EC");
		putKeyFactoryImpl("RSA");
		put("KeyAgreement.ECDH", PACKAGE_NAME + ".AndroidKeyStoreKeyAgreementSpi$ECDH");
	}

	/* AOSP: keystore key factories only take keystore keys */
	private void putKeyFactoryImpl(String algorithm) {
		put("KeyFactory." + algorithm, PACKAGE_NAME + ".AndroidKeyStoreKeyFactorySpi");
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

			/* the asymmetric half: private key operations (public key ones stay with the
			 * platform provider, as on Android) */
			putAsymmetricCipherImpl("RSA/ECB/NoPadding", "NoPadding");
			put("Alg.Alias.Cipher.RSA/None/NoPadding", "RSA/ECB/NoPadding");
			putAsymmetricCipherImpl("RSA/ECB/PKCS1Padding", "PKCS1Padding");
			put("Alg.Alias.Cipher.RSA/None/PKCS1Padding", "RSA/ECB/PKCS1Padding");
			putAsymmetricCipherImpl("RSA/ECB/OAEPPadding", "OAEPWithSHA1AndMGF1Padding");
			put("Alg.Alias.Cipher.RSA/None/OAEPPadding", "RSA/ECB/OAEPPadding");
			for (String sha : new String[] {"1", "224", "256", "384", "512"}) {
				String t = "RSA/ECB/OAEPWithSHA-" + sha + "AndMGF1Padding";
				putAsymmetricCipherImpl(t, "OAEPWithSHA" + sha + "AndMGF1Padding");
				put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-" + sha + "AndMGF1Padding", t);
			}

			putSignatureImpl("NONEwithRSA", "NONEWithPKCS1Padding");
			putSignatureImpl("MD5withRSA", "MD5WithPKCS1Padding");
			put("Alg.Alias.Signature.MD5WithRSAEncryption", "MD5withRSA");
			put("Alg.Alias.Signature.MD5/RSA", "MD5withRSA");
			put("Alg.Alias.Signature.1.2.840.113549.1.1.4", "MD5withRSA");
			put("Alg.Alias.Signature.1.2.840.113549.2.5with1.2.840.113549.1.1.1", "MD5withRSA");
			putSignatureImpl("SHA1withRSA", "SHA1WithPKCS1Padding");
			put("Alg.Alias.Signature.SHA1WithRSAEncryption", "SHA1withRSA");
			put("Alg.Alias.Signature.SHA1/RSA", "SHA1withRSA");
			put("Alg.Alias.Signature.SHA-1/RSA", "SHA1withRSA");
			put("Alg.Alias.Signature.1.2.840.113549.1.1.5", "SHA1withRSA");
			put("Alg.Alias.Signature.1.3.14.3.2.26with1.2.840.113549.1.1.1", "SHA1withRSA");
			put("Alg.Alias.Signature.1.3.14.3.2.26with1.2.840.113549.1.1.5", "SHA1withRSA");
			put("Alg.Alias.Signature.1.3.14.3.2.29", "SHA1withRSA");
			String[][] rsaShas = {{"224", "1.2.840.113549.1.1.14", "2.16.840.1.101.3.4.2.4"}, {"256", "1.2.840.113549.1.1.11", "2.16.840.1.101.3.4.2.1"},
			                      {"384", "1.2.840.113549.1.1.12", "2.16.840.1.101.3.4.2.2"}, {"512", "1.2.840.113549.1.1.13", "2.16.840.1.101.3.4.2.3"}};
			for (String[] r : rsaShas) {
				String name = "SHA" + r[0] + "withRSA";
				putSignatureImpl(name, "SHA" + r[0] + "WithPKCS1Padding");
				put("Alg.Alias.Signature.SHA" + r[0] + "WithRSAEncryption", name);
				put("Alg.Alias.Signature." + r[1], name);
				put("Alg.Alias.Signature." + r[2] + "with1.2.840.113549.1.1.1", name);
				put("Alg.Alias.Signature." + r[2] + "with" + r[1], name);
			}
			for (String sha : new String[] {"1", "224", "256", "384", "512"})
				putSignatureImpl("SHA" + sha + "withRSA/PSS", "SHA" + sha + "WithPSSPadding");

			putSignatureImpl("NONEwithECDSA", "NONEWithECDSA");
			putSignatureImpl("SHA1withECDSA", "SHA1WithECDSA");
			put("Alg.Alias.Signature.ECDSA", "SHA1withECDSA");
			put("Alg.Alias.Signature.ECDSAwithSHA1", "SHA1withECDSA");
			put("Alg.Alias.Signature.1.2.840.10045.4.1", "SHA1withECDSA");
			put("Alg.Alias.Signature.1.3.14.3.2.26with1.2.840.10045.2.1", "SHA1withECDSA");
			String[][] ecShas = {{"224", "1.2.840.10045.4.3.1", "2.16.840.1.101.3.4.2.4"}, {"256", "1.2.840.10045.4.3.2", "2.16.840.1.101.3.4.2.1"},
			                     {"384", "1.2.840.10045.4.3.3", "2.16.840.1.101.3.4.2.2"}, {"512", "1.2.840.10045.4.3.4", "2.16.840.1.101.3.4.2.3"}};
			for (String[] e : ecShas) {
				String name = "SHA" + e[0] + "withECDSA";
				putSignatureImpl(name, "SHA" + e[0] + "WithECDSA");
				put("Alg.Alias.Signature." + e[1], name);
				put("Alg.Alias.Signature." + e[2] + "with1.2.840.10045.2.1", name);
			}
		}

		private void putSymmetricCipherImpl(String transformation, String impl) {
			put("Cipher." + transformation, PACKAGE_NAME + ".AndroidKeyStoreCipherSpi$" + impl);
			put("Cipher." + transformation + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
		}

		private void putAsymmetricCipherImpl(String transformation, String impl) {
			put("Cipher." + transformation, PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$" + impl);
			put("Cipher." + transformation + " SupportedKeyClasses", KEYSTORE_PRIVATE_KEY_CLASS_NAME);
		}

		private void putSignatureImpl(String algorithm, String impl) {
			put("Signature." + algorithm, PACKAGE_NAME + ".AndroidKeyStoreSignatureSpi$" + impl);
			put("Signature." + algorithm + " SupportedKeyClasses", KEYSTORE_PRIVATE_KEY_CLASS_NAME);
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
