package java.security.interfaces;

import java.security.PrivateKey;
import java.util.Optional;

/** An EdDSA private key: its bytes, when the key lets them out (Java SE 15, Android API 33). */
public interface EdECPrivateKey extends EdECKey, PrivateKey {
	Optional<byte[]> getBytes();
}
