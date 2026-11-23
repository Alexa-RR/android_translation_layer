package android.security.keystore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.security.auth.x500.X500Principal;

/**
 * teslalinux: the certificate a newly generated keystore key pair gets, as KeyMint makes it when no
 * attestation is asked for: a single X.509 v3 certificate with the spec's subject (also the
 * issuer), serial number and validity dates, the key's SubjectPublicKeyInfo and a critical
 * KeyUsage extension from the key's purposes (sign: digitalSignature; encrypt/decrypt:
 * keyEncipherment and dataEncipherment; wrap: keyEncipherment; agree: keyAgreement; attest:
 * keyCertSign).
 *
 * It is signed with the key itself when the key may sign, with the digest AOSP's
 * getCertificateSignatureAlgorithm picks from the key's authorized digests (RSA: PKCS#1 padding
 * authorized, the largest digest that fits the modulus; EC: the digest closest to the curve
 * size). Otherwise it carries AOSP's fake signature (keystore's
 * generateSelfSignedCertificateWithFakeSignature): sha256WithRSAEncryption with a single zero byte
 * for RSA, ecdsa-with-SHA256 with an empty SEQUENCE for EC.
 */
final class SelfSignedCertificate {
	private SelfSignedCertificate() {}

	/* ---- DER ---- */

	static byte[] tlv(int tag, byte[] content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
		out.write(tag);
		int len = content.length;
		if (len < 0x80) {
			out.write(len);
		} else {
			int bytes = len > 0xffffff ? 4 : len > 0xffff ? 3 : len > 0xff ? 2 : 1;
			out.write(0x80 | bytes);
			for (int i = bytes - 1; i >= 0; i--)
				out.write((len >> (8 * i)) & 0xff);
		}
		out.write(content, 0, content.length);
		return out.toByteArray();
	}

	static byte[] cat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts)
			out.write(p, 0, p.length);
		return out.toByteArray();
	}

	static byte[] sequence(byte[]... parts) { return tlv(0x30, cat(parts)); }
	static byte[] integer(BigInteger v) { return tlv(0x02, v.toByteArray()); }
	static byte[] nul() { return new byte[] {0x05, 0x00}; }

	static byte[] bitString(byte[] bits, int unusedBits) {
		byte[] c = new byte[bits.length + 1];
		c[0] = (byte) unusedBits;
		System.arraycopy(bits, 0, c, 1, bits.length);
		return tlv(0x03, c);
	}

	static byte[] oid(String dotted) {
		String[] parts = dotted.split("\\.");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
		for (int i = 2; i < parts.length; i++) {
			long v = Long.parseLong(parts[i]);
			int n = 0;
			for (long t = v; t >= 0x80; t >>= 7)
				n++;
			for (int k = n; k >= 0; k--)
				out.write((int) ((v >> (7 * k)) & 0x7f) | (k > 0 ? 0x80 : 0));
		}
		return tlv(0x06, out.toByteArray());
	}

	/* RFC 5280 4.1.2.5: UTCTime through 2049, GeneralizedTime from 2050 */
	static byte[] time(Date d) {
		Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
		c.setTime(d);
		int year = c.get(Calendar.YEAR);
		boolean utc = year >= 1950 && year < 2050;
		SimpleDateFormat f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.ROOT);
		f.setTimeZone(TimeZone.getTimeZone("UTC"));
		return tlv(utc ? 0x17 : 0x18, f.format(d).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	/* ---- the certificate ---- */

	private static final String OID_SHA1_RSA = "1.2.840.113549.1.1.5", OID_SHA224_RSA = "1.2.840.113549.1.1.14",
	                            OID_SHA256_RSA = "1.2.840.113549.1.1.11", OID_SHA384_RSA = "1.2.840.113549.1.1.12",
	                            OID_SHA512_RSA = "1.2.840.113549.1.1.13", OID_MD5_RSA = "1.2.840.113549.1.1.4";
	private static final String OID_ECDSA_SHA1 = "1.2.840.10045.4.1", OID_ECDSA_SHA224 = "1.2.840.10045.4.3.1",
	                            OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2", OID_ECDSA_SHA384 = "1.2.840.10045.4.3.3",
	                            OID_ECDSA_SHA512 = "1.2.840.10045.4.3.4";
	private static final String OID_KEY_USAGE = "2.5.29.15";

	static int digestBits(String digest) {
		switch (digest.toUpperCase(Locale.ROOT)) {
			case "MD5": return 128;
			case "SHA-1": return 160;
			case "SHA-224": return 224;
			case "SHA-256": return 256;
			case "SHA-384": return 384;
			case "SHA-512": return 512;
			default: return -1;
		}
	}

	private static String jcaDigest(String digest) {
		return digest.replace("-", "").toUpperCase(Locale.ROOT);
	}

	/** AOSP getCertificateSignatureAlgorithm: the digest to self-sign with, or null (fake signature) */
	static String certificateDigest(String algorithm, int keySizeBits, KeyAuthorizations a, boolean digestsSpecified) {
		if ((a.purposes & KeyProperties.PURPOSE_SIGN) == 0 || !digestsSpecified)
			return null;
		boolean ec = KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(algorithm);
		if (!ec && !Arrays.containsIgnoreCase(a.signaturePaddings, KeyProperties.SIGNATURE_PADDING_RSA_PKCS1))
			return null;
		String best = null;
		int bestBits = -1;
		int maxBits = keySizeBits - 30 * 8;
		for (String d : a.digests) {
			int bits = digestBits(d);
			/* the ECDSA signature digests (AndroidKeyStoreBCWorkaroundProvider); NONE has no
			 * certificate signature algorithm */
			if (bits < 160)
				continue;
			if (ec) {
				if (bits == keySizeBits) {
					best = d;
					break;
				}
				if (best == null || (bestBits < keySizeBits ? bits > bestBits : bits < bestBits && bits >= keySizeBits)) {
					best = d;
					bestBits = bits;
				}
			} else {
				if (bits > maxBits)
					continue;
				if (best == null || bits > bestBits) {
					best = d;
					bestBits = bits;
				}
			}
		}
		return best;
	}

	private static String signatureOid(boolean ec, String digest) {
		switch (digest == null ? "SHA-256" : digest.toUpperCase(Locale.ROOT)) {
			case "MD5": return OID_MD5_RSA;
			case "SHA-1": return ec ? OID_ECDSA_SHA1 : OID_SHA1_RSA;
			case "SHA-224": return ec ? OID_ECDSA_SHA224 : OID_SHA224_RSA;
			case "SHA-384": return ec ? OID_ECDSA_SHA384 : OID_SHA384_RSA;
			case "SHA-512": return ec ? OID_ECDSA_SHA512 : OID_SHA512_RSA;
			default: return ec ? OID_ECDSA_SHA256 : OID_SHA256_RSA;
		}
	}

	/* KeyUsage bits (RFC 5280 4.2.1.3), first byte, MSB first */
	private static final int KU_DIGITAL_SIGNATURE = 0x80, KU_KEY_ENCIPHERMENT = 0x20, KU_DATA_ENCIPHERMENT = 0x10,
	                         KU_KEY_AGREEMENT = 0x08, KU_KEY_CERT_SIGN = 0x04;

	private static byte[] keyUsageExtension(int purposes) {
		int bits = 0;
		if ((purposes & KeyProperties.PURPOSE_SIGN) != 0)
			bits |= KU_DIGITAL_SIGNATURE;
		if ((purposes & (KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)) != 0)
			bits |= KU_KEY_ENCIPHERMENT | KU_DATA_ENCIPHERMENT;
		if ((purposes & KeyProperties.PURPOSE_WRAP_KEY) != 0)
			bits |= KU_KEY_ENCIPHERMENT;
		if ((purposes & KeyProperties.PURPOSE_AGREE_KEY) != 0)
			bits |= KU_KEY_AGREEMENT;
		if ((purposes & KeyProperties.PURPOSE_ATTEST_KEY) != 0)
			bits |= KU_KEY_CERT_SIGN;
		if (bits == 0)
			return null;
		int unused = Integer.numberOfTrailingZeros(bits);
		byte[] value = bitString(new byte[] {(byte) bits}, unused);
		byte[] ext = sequence(oid(OID_KEY_USAGE), tlv(0x01, new byte[] {(byte) 0xff}) /* critical */, tlv(0x04, value));
		return tlv(0xa3, sequence(ext));
	}

	/**
	 * The certificate of a new key pair.
	 *
	 * @param digest the digest to self-sign with (certificateDigest), or null for the fake signature
	 */
	static X509Certificate make(String algorithm, PublicKey publicKey, PrivateKey privateKey, String digest, int purposes,
	                            X500Principal subject, BigInteger serial, Date notBefore, Date notAfter) {
		boolean ec = KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(algorithm);
		String sigOid = signatureOid(ec, digest);
		/* RSA signature algorithms carry NULL parameters, ECDSA ones none (RFC 3279, 5758) */
		byte[] sigAlg = ec ? sequence(oid(sigOid)) : sequence(oid(sigOid), nul());
		byte[] name = subject.getEncoded();
		byte[] ext = keyUsageExtension(purposes);
		byte[] tbs = sequence(
		    tlv(0xa0, integer(BigInteger.valueOf(2))), /* v3 */
		    integer(serial),
		    sigAlg,
		    name,
		    sequence(time(notBefore), time(notAfter)),
		    name,
		    publicKey.getEncoded(),
		    ext != null ? ext : new byte[0]);
		byte[] signature;
		if (digest != null) {
			try {
				Signature s = Signature.getInstance(jcaDigest(digest) + (ec ? "withECDSA" : "withRSA"));
				s.initSign(privateKey);
				s.update(tbs);
				signature = s.sign();
			} catch (GeneralSecurityException e) {
				throw new ProviderException("Failed to sign the key pair's certificate", e);
			}
		} else {
			signature = ec ? sequence() : new byte[1];
		}
		byte[] cert = sequence(tbs, sigAlg, bitString(signature, 0));
		try {
			return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
		} catch (GeneralSecurityException e) {
			throw new ProviderException("Failed to make the key pair's certificate", e);
		}
	}

	private static final class Arrays {
		static boolean containsIgnoreCase(String[] values, String value) {
			for (String v : values)
				if (v.equalsIgnoreCase(value))
					return true;
			return false;
		}
	}
}
