package java.security.interfaces;

import java.security.PrivateKey;
import java.util.Optional;

/** An XEC private key: its scalar, when the key lets it out (Java SE 11, Android API 33). */
public interface XECPrivateKey extends XECKey, PrivateKey {
	Optional<byte[]> getScalar();
}
