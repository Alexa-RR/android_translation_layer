package java.security.interfaces;

import java.security.spec.NamedParameterSpec;

/** A key of an EdDSA curve such as Ed25519 (Java SE 15, Android API 33). */
public interface EdECKey {
	NamedParameterSpec getParams();
}
