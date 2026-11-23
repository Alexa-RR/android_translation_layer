package android.security.keystore;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * teslalinux: the Curve 25519 keys of the keystore (Android API 33: x25519 for key agreement,
 * ed25519 for signing, both generated as EC keys with ECGenParameterSpec("x25519"/"ed25519") or
 * through KeyPairGenerator "XDH"). X25519 keys are the platform provider's (Conscrypt's XDH on
 * BoringSSL); Ed25519 keys are TlEd25519's. They are stored as their PKCS#8 encoding under the
 * storage names XDH and Ed25519, and their public keys report AOSP's algorithm names ("XDH", and
 * Conscrypt's Ed25519 OID for EdDSA public keys).
 */
final class KeyStoreCurve25519 {
	static final String CURVE_X25519 = "x25519";
	static final String CURVE_ED25519 = "ed25519";
	/** the storage name of Ed25519 key pairs */
	static final String ED25519 = "Ed25519";
	/** the JCA algorithm of an Ed25519 public key on Android (Conscrypt's) */
	static final String ED25519_OID = "1.3.101.112";

	private static final byte[] X509_PREAMBLE = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00};
	private static final byte[] X509_PREAMBLE_WITH_NULL = {0x30, 0x2C, 0x30, 0x07, 0x06, 0x03, 0x2B, 0x65, 0x6E, 0x05, 0x00, 0x03, 0x21, 0x00};

	private KeyStoreCurve25519() {}

	static boolean isCurve25519(String curve) {
		return curve != null && (CURVE_X25519.equalsIgnoreCase(curve) || CURVE_ED25519.equalsIgnoreCase(curve));
	}

	/** the storage algorithm of a key pair on the curve */
	static String storageAlgorithm(String curve) {
		return CURVE_X25519.equalsIgnoreCase(curve) ? KeyProperties.KEY_ALGORITHM_XDH : ED25519;
	}

	/** AOSP AndroidKeyStoreXDHPublicKey.matchesPreamble: the preamble's length, 0 when it is not an X25519 key */
	static int x25519PreambleLength(byte[] spki) {
		for (byte[] preamble : new byte[][] {X509_PREAMBLE, X509_PREAMBLE_WITH_NULL}) {
			if (spki != null && spki.length == preamble.length + 32 && Arrays.equals(preamble, Arrays.copyOf(spki, preamble.length)))
				return preamble.length;
		}
		return 0;
	}

	/** {pkcs8, spki} of a new key pair on the curve */
	static byte[][] generate(String curve, SecureRandom random) {
		if (CURVE_ED25519.equalsIgnoreCase(curve)) {
			byte[] secret = TlEd25519.newSecret(random);
			return new byte[][] {TlEd25519.pkcs8(secret), TlEd25519.spki(TlEd25519.publicKey(secret))};
		}
		try {
			/* Conscrypt draws X25519 keys from BoringSSL's own generator (as KeyMint draws them
			 * from its own) */
			KeyPairGenerator g = KeyPairGenerator.getInstance("XDH");
			KeyPair p = g.generateKeyPair();
			return new byte[][] {p.getPrivate().getEncoded(), p.getPublic().getEncoded()};
		} catch (GeneralSecurityException | RuntimeException e) {
			throw new ProviderException("Failed to generate key pair.", e);
		}
	}

	/* ---- DER: the SubjectPublicKeyInfo of a certificate (the platform's X509Certificate does not
	 * make a PublicKey of every algorithm) ---- */

	/** {tag, content start, content end, element end} of the element at off */
	private static int[] element(byte[] d, int off) {
		int tag = d[off] & 0xff;
		int len = d[off + 1] & 0xff;
		int start = off + 2;
		if (len >= 0x80) {
			int n = len & 0x7f;
			len = 0;
			for (int i = 0; i < n; i++)
				len = (len << 8) | (d[start + i] & 0xff);
			start += n;
		}
		return new int[] {tag, start, start + len, start + len};
	}

	/** the SubjectPublicKeyInfo DER of a certificate's DER */
	static byte[] spkiOfCertificate(byte[] cert) {
		int[] c = element(cert, 0);           // Certificate
		int[] tbs = element(cert, c[1]);      // TBSCertificate
		int off = tbs[1];
		int[] e = element(cert, off);
		if (e[0] == 0xa0)                      // [0] version
			off = e[3];
		for (int i = 0; i < 5; i++)            // serial, signature, issuer, validity, subject
			off = element(cert, off)[3];
		int[] spki = element(cert, off);
		return Arrays.copyOfRange(cert, off, spki[3]);
	}
}
