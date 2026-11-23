package java.security.interfaces;

import java.security.PublicKey;
import java.security.spec.EdECPoint;

/** An EdDSA public key: its point (Java SE 15, Android API 33). */
public interface EdECPublicKey extends EdECKey, PublicKey {
	EdECPoint getPoint();
}
