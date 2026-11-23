package android.security.keystore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* teslalinux: persistent, software-backed keystore storage (what Android does on devices without a
 * secure element). One file per alias under <app data dir>/keystore, owner-only permissions.
 * Format: line 1 algorithm, line 2 base64 key material, then the key's authorization list as
 * name=value lines (KeyAuthorizations); a file with only the first two lines is a key from before
 * the list was kept and has no restrictions. */
final class KeyStoreStorage {
	private static final Map<String, AndroidKeyStoreSecretKey> cache = new HashMap<>();
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
				byte[] material = Base64.getDecoder().decode(lines.get(1).trim());
				KeyAuthorizations auth = KeyAuthorizations.fromLines(lines.subList(2, lines.size()));
				if (auth == null)
					auth = KeyAuthorizations.unrestricted(material.length * 8, KeyProperties.ORIGIN_UNKNOWN);
				cache.put(f.getName(), new AndroidKeyStoreSecretKey(f.getName(), lines.get(0).trim(), material, auth));
			} catch (IOException | IllegalArgumentException e) {
				android.util.Log.w("AndroidKeyStore", "ignoring unreadable key file " + f + ": " + e);
			}
		}
	}

	static synchronized AndroidKeyStoreSecretKey get(String alias) { loadAll(); return cache.get(alias); }
	static synchronized boolean contains(String alias) { loadAll(); return cache.containsKey(alias); }
	static synchronized Set<String> aliases() { loadAll(); return new java.util.HashSet<>(cache.keySet()); }
	static synchronized int size() { loadAll(); return cache.size(); }

	static synchronized Date creationDate(String alias) {
		File f = new File(dir(), alias);
		return f.exists() ? new Date(f.lastModified()) : null;
	}

	private static void write(AndroidKeyStoreSecretKey k) throws IOException {
		File f = new File(dir(), k.getAlias());
		File tmp = new File(dir(), k.getAlias() + ".tmp");
		StringBuilder body = new StringBuilder();
		body.append(k.getAlgorithm()).append('\n').append(Base64.getEncoder().encodeToString(k.material)).append('\n');
		for (String line : k.auth.toLines())
			body.append(line).append('\n');
		Files.write(tmp.toPath(), body.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		try { tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true); } catch (SecurityException ignored) {}
		if (!tmp.renameTo(f)) {
			tmp.delete();
			throw new IOException("could not store key " + k.getAlias());
		}
	}

	static synchronized AndroidKeyStoreSecretKey put(String alias, String algorithm, byte[] material, KeyAuthorizations auth) throws IOException {
		loadAll();
		AndroidKeyStoreSecretKey k = new AndroidKeyStoreSecretKey(alias, algorithm, material, auth);
		write(k);
		cache.put(alias, k);
		return k;
	}

	/**
	 * KeyMint's usage count (KM_TAG_USAGE_COUNT_LIMIT): one use of a limited key; keystore deletes
	 * the key with its last use.
	 */
	static synchronized void consumeUse(AndroidKeyStoreSecretKey key) throws IOException {
		KeyAuthorizations a = key.auth;
		if (a.remainingUsages == KeyProperties.UNRESTRICTED_USAGE_COUNT)
			return;
		if (a.remainingUsages > 0)
			a.remainingUsages--;
		if (a.remainingUsages == 0) {
			delete(key.getAlias());
		} else if (cache.get(key.getAlias()) == key) {
			write(key);
		}
	}

	static synchronized void delete(String alias) {
		loadAll();
		cache.remove(alias);
		new File(dir(), alias).delete();
	}
}
