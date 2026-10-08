package android.system;

import java.io.IOException;

public final class ErrnoException extends Exception {
	public final int errno;
	private final String functionName;

	public ErrnoException(String functionName, int errno) {
		super(functionName + " failed: errno " + errno);
		this.functionName = functionName;
		this.errno = errno;
	}

	public ErrnoException(String functionName, int errno, Throwable cause) {
		super(functionName + " failed: errno " + errno, cause);
		this.functionName = functionName;
		this.errno = errno;
	}

	public IOException rethrowAsIOException() throws IOException {
		IOException e = new IOException(getMessage());
		e.initCause(this);
		throw e;
	}
}
