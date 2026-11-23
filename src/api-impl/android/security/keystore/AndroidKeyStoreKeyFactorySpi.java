package android.security.keystore;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactorySpi;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * teslalinux: AOSP keystore2 AndroidKeyStoreKeyFactorySpi: the KeyInfo of a keystore private key
 * (software security level, not inside secure hardware: this device has neither TEE nor
 * StrongBox), the specs of a keystore public key (X.509, RSAPublicKeySpec, ECPublicKeySpec).
 * Keystore keys cannot be made from key specs or exported as PKCS#8.
 */
public class AndroidKeyStoreKeyFactorySpi extends KeyFactorySpi {
	@Override
	@SuppressWarnings("unchecked")
	protected <T extends KeySpec> T engineGetKeySpec(Key key, Class<T> keySpecClass) throws InvalidKeySpecException {
		if (key == null)
			throw new InvalidKeySpecException("key == null");
		if (!(key instanceof AndroidKeyStorePrivateKey) && !(key instanceof AndroidKeyStorePublicKey))
			throw new InvalidKeySpecException("Unsupported key type: " + key.getClass().getName() + ". This KeyFactory supports only Android Keystore keys");
		if (keySpecClass == null)
			throw new InvalidKeySpecException("keySpecClass == null");
		if (key instanceof AndroidKeyStorePrivateKey) {
			AndroidKeyStorePrivateKey k = (AndroidKeyStorePrivateKey) key;
			if (KeyInfo.class.equals(keySpecClass))
				return (T) k.auth.toKeyInfo(k.getAlias());
			if (X509EncodedKeySpec.class.equals(keySpecClass))
				throw new InvalidKeySpecException("Key material export of Android Keystore private keys is not supported: X.509 encoding is for public keys");
			if (PKCS8EncodedKeySpec.class.equals(keySpecClass))
				throw new InvalidKeySpecException("Key material export of Android Keystore private keys is not supported");
			throw new InvalidKeySpecException("Key material export of Android Keystore private keys is not supported");
		}
		AndroidKeyStorePublicKey k = (AndroidKeyStorePublicKey) key;
		if (X509EncodedKeySpec.class.equals(keySpecClass))
			return (T) new X509EncodedKeySpec(k.getEncoded());
		if (PKCS8EncodedKeySpec.class.equals(keySpecClass))
			throw new InvalidKeySpecException("Cannot export key material of public key in PKCS#8 format. Only X.509 format (X509EncodedKeySpec) supported.");
		if (RSAPublicKeySpec.class.equals(keySpecClass) && k instanceof AndroidKeyStorePublicKey.RSA) {
			AndroidKeyStorePublicKey.RSA r = (AndroidKeyStorePublicKey.RSA) k;
			return (T) new RSAPublicKeySpec(r.getModulus(), r.getPublicExponent());
		}
		if (ECPublicKeySpec.class.equals(keySpecClass) && k instanceof AndroidKeyStorePublicKey.EC) {
			AndroidKeyStorePublicKey.EC e = (AndroidKeyStorePublicKey.EC) k;
			return (T) new ECPublicKeySpec(e.getW(), e.getParams());
		}
		throw new InvalidKeySpecException("Unsupported key spec: " + keySpecClass.getName());
	}

	@Override
	protected PrivateKey engineGeneratePrivate(KeySpec spec) throws InvalidKeySpecException {
		throw new InvalidKeySpecException("To generate a key pair in Android Keystore, use KeyPairGenerator initialized with " + KeyGenParameterSpec.class.getName());
	}

	@Override
	protected PublicKey engineGeneratePublic(KeySpec spec) throws InvalidKeySpecException {
		throw new InvalidKeySpecException("To generate a key pair in Android Keystore, use KeyPairGenerator initialized with " + KeyGenParameterSpec.class.getName());
	}

	@Override
	protected Key engineTranslateKey(Key key) throws InvalidKeyException {
		if (key == null)
			throw new InvalidKeyException("key == null");
		if (!(key instanceof AndroidKeyStorePrivateKey) && !(key instanceof AndroidKeyStorePublicKey))
			throw new InvalidKeyException("To import a key into Android Keystore, use KeyStore.setEntry");
		return key;
	}
}
