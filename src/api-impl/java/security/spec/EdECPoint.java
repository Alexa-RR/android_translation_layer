package java.security.spec;

import java.math.BigInteger;

/**
 * A point on an Edwards curve as RFC 8032 encodes it: the y-coordinate and whether x is odd
 * (Java SE 15, Android API 33).
 */
public final class EdECPoint {
	private final boolean xOdd;
	private final BigInteger y;

	public EdECPoint(boolean xOdd, BigInteger y) {
		if (y == null)
			throw new NullPointerException("y must not be null");
		this.xOdd = xOdd;
		this.y = y;
	}

	public boolean isXOdd() {
		return xOdd;
	}

	public BigInteger getY() {
		return y;
	}
}
