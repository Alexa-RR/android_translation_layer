package java.security.interfaces;

import java.math.BigInteger;
import java.security.PublicKey;

/** An XEC public key: its u-coordinate (Java SE 11, Android API 33). */
public interface XECPublicKey extends XECKey, PublicKey {
	BigInteger getU();
}
