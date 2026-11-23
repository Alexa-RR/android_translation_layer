package java.security.interfaces;

import java.security.spec.AlgorithmParameterSpec;

/** A key of an XEC (RFC 7748) function such as X25519 (Java SE 11, Android API 33). */
public interface XECKey {
	AlgorithmParameterSpec getParams();
}
