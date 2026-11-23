package android.security.keystore;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Ed25519 (RFC 8032, PureEdDSA over edwards25519) for keystore keys: the platform crypto provider
 * here (Conscrypt on BoringSSL, built without its EdDSA classes) offers X25519 but no Ed25519, so
 * signing and verifying are done here, after the RFC's reference implementation (section 6:
 * extended coordinates, the same encodings and checks). AOSP does the operation in KeyMint; this
 * runtime's keystore is software, as on a device without secure hardware.
 */
final class TlEd25519 {
	static final int KEY_SIZE = 32;
	static final int SIGNATURE_SIZE = 64;

	/* in this order: each uses the ones before it */
	private static final BigInteger TWO = BigInteger.valueOf(2);
	private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
	private static final BigInteger Q = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
	private static final BigInteger D = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P);
	private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
	private static final BigInteger[] G;
	static {
		BigInteger gy = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P);
		BigInteger gx = recoverX(gy, 0);
		G = new BigInteger[] {gx, gy, BigInteger.ONE, gx.multiply(gy).mod(P)};
	}

	private TlEd25519() {}

	private static BigInteger inv(BigInteger x) {
		return x.modPow(P.subtract(TWO), P);
	}

	/* points: {X, Y, Z, T} with x = X/Z, y = Y/Z, x*y = T/Z */
	private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
		BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
		BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
		BigInteger c = TWO.multiply(p[3]).multiply(q[3]).multiply(D).mod(P);
		BigInteger d = TWO.multiply(p[2]).multiply(q[2]).mod(P);
		BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
		return new BigInteger[] {e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
	}

	private static BigInteger[] mul(BigInteger s, BigInteger[] p) {
		BigInteger[] q = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO}; // the neutral element
		for (int i = 0; i < s.bitLength(); i++) {
			if (s.testBit(i))
				q = add(q, p);
			p = add(p, p);
		}
		return q;
	}

	private static boolean equal(BigInteger[] p, BigInteger[] q) {
		return p[0].multiply(q[2]).subtract(q[0].multiply(p[2])).mod(P).signum() == 0
		    && p[1].multiply(q[2]).subtract(q[1].multiply(p[2])).mod(P).signum() == 0;
	}

	private static BigInteger recoverX(BigInteger y, int sign) {
		if (y.compareTo(P) >= 0)
			return null;
		BigInteger x2 = y.multiply(y).subtract(BigInteger.ONE).multiply(inv(D.multiply(y).multiply(y).add(BigInteger.ONE))).mod(P);
		if (x2.signum() == 0)
			return sign != 0 ? null : BigInteger.ZERO;
		BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
		if (x.multiply(x).subtract(x2).mod(P).signum() != 0)
			x = x.multiply(SQRT_M1).mod(P);
		if (x.multiply(x).subtract(x2).mod(P).signum() != 0)
			return null;
		if ((x.testBit(0) ? 1 : 0) != sign)
			x = P.subtract(x);
		return x;
	}

	private static BigInteger le(byte[] b, int off, int len) {
		byte[] be = new byte[len + 1];
		for (int i = 0; i < len; i++)
			be[len - i] = b[off + i];
		return new BigInteger(be);
	}

	private static byte[] le32(BigInteger v) {
		byte[] be = v.toByteArray();
		byte[] out = new byte[32];
		for (int i = 0; i < 32 && i < be.length; i++)
			out[i] = be[be.length - 1 - i];
		return out;
	}

	private static byte[] compress(BigInteger[] p) {
		BigInteger zinv = inv(p[2]);
		BigInteger x = p[0].multiply(zinv).mod(P), y = p[1].multiply(zinv).mod(P);
		byte[] out = le32(y);
		if (x.testBit(0))
			out[31] |= (byte) 0x80;
		return out;
	}

	private static BigInteger[] decompress(byte[] s) {
		if (s.length != 32)
			return null;
		int sign = (s[31] >> 7) & 1;
		byte[] c = s.clone();
		c[31] &= 0x7f;
		BigInteger y = le(c, 0, 32);
		BigInteger x = recoverX(y, sign);
		if (x == null)
			return null;
		return new BigInteger[] {x, y, BigInteger.ONE, x.multiply(y).mod(P)};
	}

	private static MessageDigest sha512() {
		try {
			return MessageDigest.getInstance("SHA-512");
		} catch (NoSuchAlgorithmException e) {
			throw new ProviderException(e);
		}
	}

	private static BigInteger sha512ModQ(byte[]... parts) {
		MessageDigest md = sha512();
		for (byte[] p : parts)
			md.update(p);
		byte[] h = md.digest();
		return le(h, 0, h.length).mod(Q);
	}

	/** {a, prefix} of a 32-byte secret */
	private static Object[] expand(byte[] secret) {
		if (secret.length != KEY_SIZE)
			throw new IllegalArgumentException("Bad size of private key");
		byte[] h = sha512().digest(secret);
		BigInteger a = le(h, 0, 32);
		a = a.and(BigInteger.ONE.shiftLeft(254).subtract(BigInteger.valueOf(8))).or(BigInteger.ONE.shiftLeft(254));
		return new Object[] {a, Arrays.copyOfRange(h, 32, 64)};
	}

	static byte[] newSecret(SecureRandom random) {
		byte[] s = new byte[KEY_SIZE];
		(random != null ? random : new SecureRandom()).nextBytes(s);
		return s;
	}

	static byte[] publicKey(byte[] secret) {
		return compress(mul((BigInteger) expand(secret)[0], G));
	}

	static byte[] sign(byte[] secret, byte[] msg) {
		Object[] e = expand(secret);
		BigInteger a = (BigInteger) e[0];
		byte[] prefix = (byte[]) e[1];
		byte[] pub = compress(mul(a, G));
		BigInteger r = sha512ModQ(prefix, msg);
		byte[] rs = compress(mul(r, G));
		BigInteger h = sha512ModQ(rs, pub, msg);
		BigInteger s = r.add(h.multiply(a)).mod(Q);
		byte[] out = new byte[SIGNATURE_SIZE];
		System.arraycopy(rs, 0, out, 0, 32);
		System.arraycopy(le32(s), 0, out, 32, 32);
		return out;
	}

	static boolean verify(byte[] pub, byte[] msg, byte[] signature) {
		if (pub.length != KEY_SIZE || signature.length != SIGNATURE_SIZE)
			return false;
		BigInteger[] a = decompress(pub);
		if (a == null)
			return false;
		byte[] rs = Arrays.copyOfRange(signature, 0, 32);
		BigInteger[] r = decompress(rs);
		if (r == null)
			return false;
		BigInteger s = le(signature, 32, 32);
		if (s.compareTo(Q) >= 0)
			return false;
		BigInteger h = sha512ModQ(rs, pub, msg);
		return equal(mul(s, G), add(r, mul(h, a)));
	}

	/* RFC 8410 encodings: PKCS#8 OneAsymmetricKey with the secret as a CurvePrivateKey (an OCTET
	 * STRING in the privateKey OCTET STRING), SubjectPublicKeyInfo with the key as the BIT STRING */
	static final byte[] ED25519_OID = {0x06, 0x03, 0x2b, 0x65, 0x70};   // 1.3.101.112
	static final byte[] X25519_OID = {0x06, 0x03, 0x2b, 0x65, 0x6e};    // 1.3.101.110
	private static final byte[] PKCS8_PREFIX = {0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20};
	private static final byte[] SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

	static byte[] pkcs8(byte[] secret) {
		byte[] out = Arrays.copyOf(PKCS8_PREFIX, PKCS8_PREFIX.length + 32);
		System.arraycopy(secret, 0, out, PKCS8_PREFIX.length, 32);
		return out;
	}

	static byte[] spki(byte[] pub) {
		byte[] out = Arrays.copyOf(SPKI_PREFIX, SPKI_PREFIX.length + 32);
		System.arraycopy(pub, 0, out, SPKI_PREFIX.length, 32);
		return out;
	}

	/** the 32-byte secret of an RFC 8410 PKCS#8 Ed25519 key (the attributes and the public key a
	 * v2 OneAsymmetricKey may add after it are left alone), or null */
	static byte[] secretFromPkcs8(byte[] der) {
		if (der == null || der.length < PKCS8_PREFIX.length + 32)
			return null;
		for (int i = 2; i < PKCS8_PREFIX.length; i++) {
			if (i == 4)
				continue; // version 0 or 1
			if (der[i] != PKCS8_PREFIX[i])
				return null;
		}
		if (der[0] != 0x30)
			return null;
		return Arrays.copyOfRange(der, PKCS8_PREFIX.length, PKCS8_PREFIX.length + 32);
	}

	/** the 32-byte key of an RFC 8410 Ed25519 SubjectPublicKeyInfo, or null */
	static byte[] publicFromSpki(byte[] der) {
		if (der == null || der.length != SPKI_PREFIX.length + 32)
			return null;
		for (int i = 0; i < SPKI_PREFIX.length; i++)
			if (der[i] != SPKI_PREFIX[i])
				return null;
		return Arrays.copyOfRange(der, SPKI_PREFIX.length, der.length);
	}
}
