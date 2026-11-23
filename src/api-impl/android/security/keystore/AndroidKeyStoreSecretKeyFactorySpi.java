package android.security.keystore;

import java.security.InvalidKeyException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactorySpi;

/* teslalinux: AOSP keystore2 AndroidKeyStoreSecretKeyFactorySpi: the KeyInfo of a keystore key.
 * The key is not inside secure hardware and its security level is SOFTWARE, as on a device
 * without a TEE or StrongBox; keystore keys cannot be made from or turned into key material. */
public class AndroidKeyStoreSecretKeyFactorySpi extends SecretKeyFactorySpi {
	@Override
	protected KeySpec engineGetKeySpec(SecretKey key, Class keySpecClass) throws InvalidKeySpecException {
		if (keySpecClass == null)
			throw new InvalidKeySpecException("keySpecClass == null");
		if (!(key instanceof AndroidKeyStoreSecretKey))
			throw new InvalidKeySpecException("Only Android KeyStore secret keys supported: " + (key != null ? key.getClass().getName() : "null"));
		if (javax.crypto.spec.SecretKeySpec.class.isAssignableFrom(keySpecClass))
			throw new InvalidKeySpecException("Key material export of Android KeyStore keys is not supported");
		if (!KeyInfo.class.equals(keySpecClass))
			throw new InvalidKeySpecException("Unsupported key spec: " + keySpecClass.getName());
		AndroidKeyStoreSecretKey k = (AndroidKeyStoreSecretKey) key;
		return k.auth.toKeyInfo(k.getAlias());
	}

	@Override
	protected SecretKey engineGenerateSecret(KeySpec keySpec) throws InvalidKeySpecException {
		throw new InvalidKeySpecException("To generate secret key in Android Keystore, use KeyGenerator initialized with " + KeyGenParameterSpec.class.getName());
	}

	@Override
	protected SecretKey engineTranslateKey(SecretKey key) throws InvalidKeyException {
		if (key == null)
			throw new InvalidKeyException("key == null");
		if (!(key instanceof AndroidKeyStoreSecretKey))
			throw new InvalidKeyException("To import a secret key into Android Keystore, use KeyStore.setEntry");
		return key;
	}
}
