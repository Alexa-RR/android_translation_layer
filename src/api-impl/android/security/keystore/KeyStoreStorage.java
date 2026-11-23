package android.security.keystore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/* teslalinux: persistent, software-backed keystore storage (what Android does on devices without a
 * secure element). One file per alias under <app data dir>/keystore, owner-only permissions.
 * Format: line 1 the key algorithm (or "certificate" for a trusted certificate entry), line 2 the
 * base64 key material (a secret key's raw bytes, a private key's PKCS#8; "-" for a certificate
 * entry), then the key's authorization list as name=value lines (KeyAuthorizations) and its
 * certificate chain as "certificate=<base64 DER>" lines, leaf first (keystore2's user certificate
 * and chain; a certificate entry's one certificate). A secret key file with only the first two
 * lines is a key from before the list was kept and is used without restrictions
 * (KeyAuthorizations.unrestricted). A key pair's public key is its leaf certificate's, as in
 * keystore2. */
final class KeyStoreStorage {
	static final String CERTIFICATE_ENTRY = "certificate";
	private static final String CERT_LINE = "certificate=";

	/** what one alias holds */
	static final class Entry {
		/** the secret or private key; null for a trusted certificate entry */
		final AndroidKeyStoreKey key;
		/** DER certificates: a key pair's chain (leaf first), a certificate entry's certificate */
		final List<byte[]> certificates;

		Entry(AndroidKeyStoreKey key, List<byte[]> certificates) {
			this.key = key;
			this.certificates = Collections.unmodifiableList(new ArrayList<>(certificates));
		}
	}

	private static final Map<String, Entry> cache = new HashMap<>();
	private static File dir;
	private static boolean loaded;

	private KeyStoreStorage() {}

	private static synchronized File dir() {
		if (dir == null) {
			String base = System.getenv("ANDROID_APP_DATA_DIR");
			if (base == null || base.isEmpty()) base = System.getProperty("user.home") + "/.local/share/android_translation_layer";
			dir = new File(base, "keystore");
			dir.mkdirs();
			try { dir.setReadable(false, false); dir.setReadable(true, true); dir.setExecutable(false, false); dir.setExecutable(true, true); dir.setWritable(false, false); dir.setWritable(true, true); } catch (SecurityException ignored) {}
		}
		return dir;
	}

	private static Entry parse(String alias, List<String> lines) {
		String algorithm = lines.get(0).trim();
		List<byte[]> certs = new ArrayList<>();
		List<String> authLines = new ArrayList<>();
		for (String line : lines.subList(2, lines.size())) {
			if (line.startsWith(CERT_LINE))
				certs.add(Base64.getDecoder().decode(line.substring(CERT_LINE.length()).trim()));
			else
				authLines.add(line);
		}
		if (CERTIFICATE_ENTRY.equals(algorithm))
			return certs.isEmpty() ? null : new Entry(null, certs);
		byte[] material = Base64.getDecoder().decode(lines.get(1).trim());
		KeyAuthorizations auth = KeyAuthorizations.fromLines(authLines);
		if (certs.isEmpty()) {
			if (auth == null)
				auth = KeyAuthorizations.unrestricted(material.length * 8, KeyProperties.ORIGIN_UNKNOWN);
			return new Entry(new AndroidKeyStoreSecretKey(alias, algorithm, material, auth), certs);
		}
		if (auth == null)
			auth = KeyAuthorizations.legacyKeyPair(algorithm, 0, KeyProperties.ORIGIN_UNKNOWN);
		return new Entry(keyPair(alias, algorithm, material, auth, certs.get(0)), certs);
	}

	/* the private key of a pair, with the public key of its leaf certificate */
	private static AndroidKeyStorePrivateKey keyPair(String alias, String algorithm, byte[] pkcs8, KeyAuthorizations auth, byte[] leaf) {
		byte[] spki;
		try {
			/* Curve 25519: the certificate's key info as it is (the platform's certificate has no
			 * PublicKey for Ed25519) */
			if (KeyProperties.KEY_ALGORITHM_XDH.equalsIgnoreCase(algorithm) || KeyStoreCurve25519.ED25519.equalsIgnoreCase(algorithm))
				spki = KeyStoreCurve25519.spkiOfCertificate(leaf);
			else
				spki = parseCertificate(leaf).getPublicKey().getEncoded();
		} catch (java.security.cert.CertificateException | RuntimeException e) {
			throw new IllegalArgumentException("unreadable certificate of " + alias, e);
		}
		return AndroidKeyStorePrivateKey.create(alias, algorithm, pkcs8, auth, AndroidKeyStorePublicKey.create(alias, algorithm, spki, auth));
	}

	private static synchronized void loadAll() {
		if (loaded) return;
		loaded = true;
		File[] files = dir().listFiles();
		if (files == null) return;
		for (File f : files) {
			if (f.getName().endsWith(".tmp"))
				continue;
			try {
				List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
				if (lines.size() < 2)
					continue;
				Entry e = parse(f.getName(), lines);
				if (e != null)
					cache.put(f.getName(), e);
			} catch (IOException | RuntimeException e) {
				android.util.Log.w("AndroidKeyStore", "ignoring unreadable key file " + f + ": " + e);
			}
		}
	}

	static synchronized Entry entry(String alias) { loadAll(); return cache.get(alias); }

	static synchronized AndroidKeyStoreKey get(String alias) {
		Entry e = entry(alias);
		return e == null ? null : e.key;
	}

	static synchronized boolean contains(String alias) { loadAll(); return cache.containsKey(alias); }
	static synchronized Set<String> aliases() { loadAll(); return new TreeSet<>(cache.keySet()); }
	static synchronized int size() { loadAll(); return cache.size(); }

	static synchronized Date creationDate(String alias) {
		if (!contains(alias))
			return null;
		File f = new File(dir(), alias);
		return f.exists() ? new Date(f.lastModified()) : null;
	}

	private static void write(String alias, Entry e) throws IOException {
		File f = new File(dir(), alias);
		File tmp = new File(dir(), alias + ".tmp");
		StringBuilder body = new StringBuilder();
		if (e.key == null) {
			body.append(CERTIFICATE_ENTRY).append('\n').append("-\n");
		} else {
			body.append(e.key.getAlgorithm()).append('\n').append(Base64.getEncoder().encodeToString(e.key.material)).append('\n');
			for (String line : e.key.auth.toLines())
				body.append(line).append('\n');
		}
		for (byte[] cert : e.certificates)
			body.append(CERT_LINE).append(Base64.getEncoder().encodeToString(cert)).append('\n');
		Files.write(tmp.toPath(), body.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		try { tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true); } catch (SecurityException ignored) {}
		if (!tmp.renameTo(f)) {
			tmp.delete();
			throw new IOException("could not store key " + alias);
		}
	}

	private static void store(String alias, Entry e) throws IOException {
		loadAll();
		write(alias, e);
		cache.put(alias, e);
	}

	/** a secret key */
	static synchronized AndroidKeyStoreSecretKey put(String alias, String algorithm, byte[] material, KeyAuthorizations auth) throws IOException {
		AndroidKeyStoreSecretKey k = new AndroidKeyStoreSecretKey(alias, algorithm, material, auth);
		store(alias, new Entry(k, Collections.<byte[]>emptyList()));
		return k;
	}

	/** a key pair: its private key (PKCS#8) and certificate chain, leaf first */
	static synchronized AndroidKeyStorePrivateKey putKeyPair(String alias, String algorithm, byte[] pkcs8, KeyAuthorizations auth, List<byte[]> chain) throws IOException {
		AndroidKeyStorePrivateKey k = keyPair(alias, algorithm, pkcs8, auth, chain.get(0));
		store(alias, new Entry(k, chain));
		return k;
	}

	/** keystore2 updateSubcomponents: a new certificate chain for the key pair under alias */
	static synchronized AndroidKeyStorePrivateKey setChain(String alias, List<byte[]> chain) throws IOException {
		Entry e = entry(alias);
		if (e == null || !(e.key instanceof AndroidKeyStorePrivateKey))
			throw new IOException("no key pair under " + alias);
		AndroidKeyStorePrivateKey old = (AndroidKeyStorePrivateKey) e.key;
		AndroidKeyStorePrivateKey k = keyPair(alias, old.getAlgorithm(), old.material, old.auth, chain.get(0));
		store(alias, new Entry(k, chain));
		return k;
	}

	/** a trusted certificate entry */
	static synchronized void putCertificate(String alias, byte[] der) throws IOException {
		store(alias, new Entry(null, Collections.singletonList(der)));
	}

	/**
	 * AOSP AndroidKeyStoreSpi.engineGetCertificateAlias: the alias whose key pair has this leaf
	 * certificate, else the alias of a certificate entry holding it (aliases in order)
	 */
	static synchronized String certificateAlias(byte[] der) {
		loadAll();
		Set<String> aliases = new TreeSet<>(cache.keySet());
		for (String alias : aliases) {
			Entry e = cache.get(alias);
			if (e.key != null && !e.certificates.isEmpty() && Arrays.equals(e.certificates.get(0), der))
				return alias;
		}
		for (String alias : aliases) {
			Entry e = cache.get(alias);
			if (e.key == null && Arrays.equals(e.certificates.get(0), der))
				return alias;
		}
		return null;
	}

	/**
	 * KeyMint's usage count (KM_TAG_USAGE_COUNT_LIMIT): one use of a limited key; keystore deletes
	 * the key with its last use.
	 */
	static synchronized void consumeUse(AndroidKeyStoreKey key) throws IOException {
		KeyAuthorizations a = key.auth;
		if (a.remainingUsages == KeyProperties.UNRESTRICTED_USAGE_COUNT)
			return;
		if (a.remainingUsages > 0)
			a.remainingUsages--;
		Entry e = cache.get(key.getAlias());
		if (a.remainingUsages == 0) {
			delete(key.getAlias());
		} else if (e != null && e.key == key) {
			write(key.getAlias(), e);
		}
	}

	static synchronized void delete(String alias) {
		loadAll();
		cache.remove(alias);
		new File(dir(), alias).delete();
	}

	static java.security.cert.X509Certificate parseCertificate(byte[] der) throws java.security.cert.CertificateException {
		return (java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
		    .generateCertificate(new java.io.ByteArrayInputStream(der));
	}
}
