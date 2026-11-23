package android.security.keystore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableEntryException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import javax.crypto.SecretKey;

/**
 * teslalinux: the "AndroidKeyStore" KeyStoreSpi (AOSP keystore2 AndroidKeyStoreSpi) over
 * KeyStoreStorage: secret keys, key pairs with their certificate chain, and trusted certificate
 * entries.
 *
 * getKey gives a keystore key (a key pair's private key); getCertificate a key pair's leaf
 * certificate or a certificate entry's; getCertificateChain a key pair's chain; getEntry a
 * PrivateKeyEntry, SecretKeyEntry or TrustedCertificateEntry. setEntry / setKeyEntry import keys as
 * AOSP does: a private key (PKCS#8) needs a certificate chain and is kept with the KeyProtection's
 * authorizations, or AOSP's legacy ones without a KeyProtection; a secret key (RAW) needs a
 * KeyProtection; a keystore key already under the alias only gets its chain replaced. Entries are
 * not password protected.
 */
public class AndroidKeyStore extends KeyStoreSpi {
	@Override
	public Key engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException, UnrecoverableKeyException {
		return KeyStoreStorage.get(alias);
	}

	private static Certificate[] certificates(List<byte[]> ders) {
		Certificate[] out = new Certificate[ders.size()];
		try {
			for (int i = 0; i < out.length; i++)
				out[i] = KeyStoreStorage.parseCertificate(ders.get(i));
		} catch (CertificateException e) {
			android.util.Log.w("AndroidKeyStore", "unreadable stored certificate: " + e);
			return null;
		}
		return out;
	}

	@Override
	public Certificate[] engineGetCertificateChain(String alias) {
		if (alias == null)
			throw new NullPointerException("alias == null");
		KeyStoreStorage.Entry e = KeyStoreStorage.entry(alias);
		if (e == null || !(e.key instanceof AndroidKeyStorePrivateKey))
			return null;
		return certificates(e.certificates);
	}

	@Override
	public Certificate engineGetCertificate(String alias) {
		if (alias == null)
			throw new NullPointerException("alias == null");
		KeyStoreStorage.Entry e = KeyStoreStorage.entry(alias);
		if (e == null || e.certificates.isEmpty())
			return null;
		Certificate[] c = certificates(e.certificates.subList(0, 1));
		return c == null ? null : c[0];
	}

	@Override public Date engineGetCreationDate(String alias) { return KeyStoreStorage.creationDate(alias); }

	@Override
	public KeyStore.Entry engineGetEntry(String alias, KeyStore.ProtectionParameter param) throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableEntryException {
		if (alias == null)
			throw new NullPointerException("alias == null");
		KeyStoreStorage.Entry e = KeyStoreStorage.entry(alias);
		if (e == null)
			return null;
		if (e.key == null) {
			Certificate[] c = certificates(e.certificates);
			return c == null ? null : new KeyStore.TrustedCertificateEntry(c[0]);
		}
		if (e.key instanceof SecretKey)
			return new KeyStore.SecretKeyEntry((SecretKey) e.key);
		Certificate[] chain = certificates(e.certificates);
		if (chain == null)
			throw new UnrecoverableEntryException("Failed to read the certificate chain of " + alias);
		return new KeyStore.PrivateKeyEntry((PrivateKey) e.key, chain);
	}

	@Override
	public void engineSetEntry(String alias, KeyStore.Entry entry, KeyStore.ProtectionParameter param) throws KeyStoreException {
		if (entry == null)
			throw new KeyStoreException("entry == null");
		if (entry instanceof KeyStore.TrustedCertificateEntry) {
			engineSetCertificateEntry(alias, ((KeyStore.TrustedCertificateEntry) entry).getTrustedCertificate());
			return;
		}
		if (entry instanceof KeyStore.PrivateKeyEntry) {
			KeyStore.PrivateKeyEntry p = (KeyStore.PrivateKeyEntry) entry;
			setPrivateKeyEntry(alias, p.getPrivateKey(), p.getCertificateChain(), param);
		} else if (entry instanceof KeyStore.SecretKeyEntry) {
			setSecretKeyEntry(alias, ((KeyStore.SecretKeyEntry) entry).getSecretKey(), param);
		} else {
			throw new KeyStoreException("Entry must be a PrivateKeyEntry, SecretKeyEntry or TrustedCertificateEntry; was " + entry);
		}
	}

	@Override
	public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) throws KeyStoreException {
		if (password != null && password.length > 0)
			throw new KeyStoreException("entries cannot be protected with passwords");
		if (key instanceof PrivateKey)
			setPrivateKeyEntry(alias, (PrivateKey) key, chain, null);
		else if (key instanceof SecretKey)
			setSecretKeyEntry(alias, (SecretKey) key, null);
		else
			throw new KeyStoreException("Only PrivateKey and SecretKey are supported");
	}

	@Override
	public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) throws KeyStoreException {
		throw new KeyStoreException("Operation not supported because key encoding is unknown");
	}

	/* ---- imports (AOSP AndroidKeyStoreSpi.setPrivateKeyEntry / setSecretKeyEntry) ---- */

	private static List<byte[]> encodeChain(Certificate[] chain) throws KeyStoreException {
		if (chain == null || chain.length == 0)
			throw new KeyStoreException("Must supply at least one Certificate with PrivateKey");
		List<byte[]> ders = new ArrayList<>();
		for (int i = 0; i < chain.length; i++) {
			if (!"X.509".equals(chain[i].getType()) || !(chain[i] instanceof X509Certificate))
				throw new KeyStoreException("Certificates must be in X.509 format: invalid cert #" + i);
			try {
				ders.add(chain[i].getEncoded());
			} catch (CertificateEncodingException e) {
				throw new KeyStoreException("Failed to encode certificate #" + i, e);
			}
		}
		return ders;
	}

	/** the key size KeyMint records for an imported key pair */
	private static int keySize(PrivateKey key, Certificate leaf) {
		if (key instanceof RSAKey)
			return ((RSAKey) key).getModulus().bitLength();
		if (key instanceof ECKey)
			return ((ECKey) key).getParams().getCurve().getField().getFieldSize();
		java.security.PublicKey pub = leaf.getPublicKey();
		if (pub instanceof RSAKey)
			return ((RSAKey) pub).getModulus().bitLength();
		if (pub instanceof ECKey)
			return ((ECKey) pub).getParams().getCurve().getField().getFieldSize();
		return 0;
	}

	/* AOSP KeymasterUtils.isKeymasterPaddingSchemeIndCpaCompatibleWithAsymmetricCrypto */
	static boolean isIndCpaCompatibleAsymmetricPadding(String padding) {
		return KeyProperties.ENCRYPTION_PADDING_RSA_OAEP.equalsIgnoreCase(padding) || KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1.equalsIgnoreCase(padding);
	}

	private static void checkProtection(KeyProtection p) throws KeyStoreException {
		try {
			KeyGenerator.addUserAuthArgs(p.isUserAuthenticationRequired(), p.getUserAuthenticationValidityDurationSeconds());
		} catch (IllegalStateException e) {
			throw new KeyStoreException(e);
		}
		/* KeyMint at the security level asked for: no StrongBox, no trusted user presence or
		 * protected confirmation on this device */
		if (p.isStrongBoxBacked())
			throw new KeyStoreException("Failed to import key", new StrongBoxUnavailableException("No StrongBox on this device"));
		if (p.isUserPresenceRequired() || p.isUserConfirmationRequired())
			throw new KeyStoreException("Failed to import key", new KeyStoreException("Unsupported tag (" + (p.isUserPresenceRequired() ? "TRUSTED_USER_PRESENCE_REQUIRED" : "TRUSTED_CONFIRMATION_REQUIRED") + ")"));
	}

	private void setPrivateKeyEntry(String alias, PrivateKey key, Certificate[] chain, KeyStore.ProtectionParameter param) throws KeyStoreException {
		if (alias == null)
			throw new NullPointerException("alias == null");
		if (param != null && !(param instanceof KeyProtection))
			throw new KeyStoreException("Unsupported protection parameter class:" + param.getClass().getName() + ". Supported: " + KeyProtection.class.getName());
		List<byte[]> ders = encodeChain(chain);
		if (key instanceof AndroidKeyStorePrivateKey) {
			/* a keystore key: only its certificates change (keystore2 updateSubcomponents) */
			AndroidKeyStorePrivateKey k = (AndroidKeyStorePrivateKey) key;
			if (!k.getAlias().equals(alias))
				throw new KeyStoreException("Can only replace keys with same alias: " + alias + " != " + k.getAlias());
			try {
				KeyStoreStorage.setChain(alias, ders);
			} catch (IOException e) {
				throw new KeyStoreException("Failed to store certificate and certificate chain", e);
			}
			return;
		}
		String format = key.getFormat();
		if (format == null || !"PKCS#8".equals(format))
			throw new KeyStoreException("Unsupported private key export format: " + format + ". Only private keys which export their key material in PKCS#8 format are supported.");
		byte[] pkcs8 = key.getEncoded();
		if (pkcs8 == null)
			throw new KeyStoreException("Private key did not export any key material");
		String algorithm = key.getAlgorithm().toUpperCase(Locale.ROOT);
		/* AOSP (API 33): X25519 ("XDH") and Ed25519 keys are imported as EC keys on Curve 25519 */
		String storage = algorithm;
		if ("XDH".equals(algorithm) || "X25519".equals(algorithm))
			storage = KeyProperties.KEY_ALGORITHM_XDH;
		else if (TlEd25519.secretFromPkcs8(pkcs8) != null)
			storage = KeyStoreCurve25519.ED25519;
		else if (!KeyProperties.KEY_ALGORITHM_RSA.equals(algorithm) && !KeyProperties.KEY_ALGORITHM_EC.equals(algorithm))
			throw new KeyStoreException("Unsupported key algorithm: " + key.getAlgorithm());
		final boolean curve25519 = !storage.equals(algorithm);
		int bits = curve25519 ? 256 : keySize(key, chain[0]);
		KeyAuthorizations a;
		if (param == null) {
			a = KeyAuthorizations.legacyKeyPair(curve25519 ? KeyProperties.KEY_ALGORITHM_EC : algorithm, bits, KeyProperties.ORIGIN_IMPORTED);
		} else {
			KeyProtection p = (KeyProtection) param;
			checkProtection(p);
			if ((p.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0 && p.isRandomizedEncryptionRequired()) {
				for (String padding : p.getEncryptionPaddings()) {
					if (!isIndCpaCompatibleAsymmetricPadding(padding))
						throw new KeyStoreException("Randomized encryption (IND-CPA) required but is violated by encryption padding mode: " + padding
						    + ". See KeyProtection documentation.");
				}
			}
			a = KeyAuthorizations.of(bits, KeyProperties.ORIGIN_IMPORTED, p.getPurposes(), p.getBlockModes(), p.getEncryptionPaddings(),
			    p.getSignaturePaddings(), p.isDigestsSpecified() ? p.getDigests() : new String[0], p.isRandomizedEncryptionRequired(),
			    p.getKeyValidityStart(), p.getKeyValidityForOriginationEnd(), p.getKeyValidityForConsumptionEnd(), p.getMaxUsageCount());
		}
		try {
			/* the key must be one KeyMint can read */
			if (!KeyStoreCurve25519.ED25519.equals(storage))
				java.security.KeyFactory.getInstance(storage).generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pkcs8));
			KeyStoreStorage.putKeyPair(alias, storage, pkcs8, a, ders);
		} catch (IOException | java.security.GeneralSecurityException | RuntimeException e) {
			throw new KeyStoreException("Failed to store private key", e);
		}
	}

	private static final String[] HMAC_DIGESTS = {"HMACSHA1", "SHA-1", "HMACSHA224", "SHA-224", "HMACSHA256", "SHA-256", "HMACSHA384", "SHA-384", "HMACSHA512", "SHA-512"};

	private void setSecretKeyEntry(String alias, SecretKey key, KeyStore.ProtectionParameter param) throws KeyStoreException {
		if (alias == null)
			throw new NullPointerException("alias == null");
		if (param != null && !(param instanceof KeyProtection))
			throw new KeyStoreException("Unsupported protection parameter class: " + param.getClass().getName() + ". Supported: " + KeyProtection.class.getName());
		KeyProtection p = (KeyProtection) param;
		if (key instanceof AndroidKeyStoreSecretKey) {
			if (!((AndroidKeyStoreSecretKey) key).getAlias().equals(alias))
				throw new KeyStoreException("Can only replace keys with same alias: " + alias + " != " + ((AndroidKeyStoreSecretKey) key).getAlias());
			if (p != null)
				throw new KeyStoreException("Modifying KeyStore-backed key using protection parameters not supported");
			return;
		}
		if (p == null)
			throw new KeyStoreException("Protection parameters must be specified when importing a symmetric key");
		String format = key.getFormat();
		if (format == null)
			throw new KeyStoreException("Only secret keys that export their key material are supported");
		if (!"RAW".equals(format))
			throw new KeyStoreException("Unsupported secret key material export format: " + format);
		byte[] material = key.getEncoded();
		if (material == null)
			throw new KeyStoreException("Key did not export its key material despite supporting RAW format export");
		String algorithm = key.getAlgorithm();
		String[] digests = p.isDigestsSpecified() ? p.getDigests() : new String[0];
		if (KeyProperties.KEY_ALGORITHM_AES.equalsIgnoreCase(algorithm)) {
			algorithm = KeyProperties.KEY_ALGORITHM_AES;
			int bits = material.length * 8;
			if (bits != 128 && bits != 192 && bits != 256)
				throw new KeyStoreException("Failed to import secret key", new KeyStoreException("Unsupported key size: " + bits));
		} else {
			String hmacDigest = null;
			for (int i = 0; i < HMAC_DIGESTS.length; i += 2)
				if (HMAC_DIGESTS[i].equalsIgnoreCase(algorithm))
					hmacDigest = HMAC_DIGESTS[i + 1];
			if (hmacDigest == null)
				throw new KeyStoreException("Unsupported secret key algorithm: " + algorithm);
			algorithm = "Hmac" + hmacDigest.replace("-", "");
			if (p.isDigestsSpecified() && !(digests.length == 1 && digests[0].equalsIgnoreCase(hmacDigest)))
				throw new KeyStoreException("Unsupported digests specification: " + java.util.Arrays.asList(digests) + ". Only " + hmacDigest + " supported for HMAC key algorithm " + key.getAlgorithm());
			digests = new String[] {hmacDigest};
		}
		checkProtection(p);
		if ((p.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0 && p.isRandomizedEncryptionRequired()) {
			for (String mode : p.getBlockModes()) {
				if (KeyProperties.BLOCK_MODE_ECB.equalsIgnoreCase(mode))
					throw new KeyStoreException("Randomized encryption (IND-CPA) required but may be violated by block mode: " + mode + ". See KeyProtection documentation.");
			}
		}
		KeyAuthorizations a = KeyAuthorizations.of(material.length * 8, KeyProperties.ORIGIN_IMPORTED, p.getPurposes(), p.getBlockModes(),
		    p.getEncryptionPaddings(), new String[0], digests, p.isRandomizedEncryptionRequired(), p.getKeyValidityStart(),
		    p.getKeyValidityForOriginationEnd(), p.getKeyValidityForConsumptionEnd(), p.getMaxUsageCount());
		try {
			KeyStoreStorage.put(alias, algorithm, material.clone(), a);
		} catch (IOException e) {
			throw new KeyStoreException("Failed to import secret key", e);
		}
	}

	@Override
	public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
		if (alias == null)
			throw new NullPointerException("alias == null");
		if (cert == null)
			throw new NullPointerException("cert == null");
		KeyStoreStorage.Entry e = KeyStoreStorage.entry(alias);
		if (e != null && e.key != null)
			throw new KeyStoreException("Entry exists and is not a trusted certificate");
		try {
			KeyStoreStorage.putCertificate(alias, cert.getEncoded());
		} catch (CertificateEncodingException | IOException ex) {
			throw new KeyStoreException("Failed to store certificate", ex);
		}
	}

	@Override public void engineDeleteEntry(String alias) throws KeyStoreException { KeyStoreStorage.delete(alias); }
	@Override public Enumeration<String> engineAliases() { return Collections.enumeration(KeyStoreStorage.aliases()); }
	@Override public boolean engineContainsAlias(String alias) { return KeyStoreStorage.contains(alias); }
	@Override public int engineSize() { return KeyStoreStorage.size(); }

	@Override
	public boolean engineIsKeyEntry(String alias) {
		KeyStoreStorage.Entry e = KeyStoreStorage.entry(alias);
		return e != null && e.key != null;
	}

	@Override
	public boolean engineIsCertificateEntry(String alias) {
		KeyStoreStorage.Entry e = KeyStoreStorage.entry(alias);
		return e != null && e.key == null;
	}

	@Override
	public String engineGetCertificateAlias(Certificate cert) {
		if (cert == null)
			return null;
		if (!"X.509".equalsIgnoreCase(cert.getType()))
			return null;
		try {
			return KeyStoreStorage.certificateAlias(cert.getEncoded());
		} catch (CertificateEncodingException e) {
			return null;
		}
	}

	@Override
	public void engineStore(OutputStream stream, char[] password) throws IOException, NoSuchAlgorithmException, CertificateException {
		throw new UnsupportedOperationException("Can not serialize AndroidKeyStore to OutputStream");
	}

	@Override
	public void engineLoad(InputStream stream, char[] password) throws IOException, NoSuchAlgorithmException, CertificateException {
		/* AOSP: only load(null) / load(null, null) (or an AndroidKeyStoreLoadStoreParameter): the
		 * entries are the keystore's own (here KeyStoreStorage) */
		if (stream != null)
			throw new IllegalArgumentException("InputStream not supported");
		if (password != null)
			throw new IllegalArgumentException("password not supported");
	}
}
