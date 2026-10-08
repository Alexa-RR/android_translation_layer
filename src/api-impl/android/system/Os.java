package android.system;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributes;

/* teslalinux: minimal android.system.Os. Apps (and OkHttp/Firebase internals) use a handful of
 * these for pid/uid, env, stat and fd plumbing. Everything maps to java.io/nio on the host. */
public final class Os {
	private Os() {}

	public static int getpid() { return android.os.Process.myPid(); }
	public static int getppid() { return 1; }
	public static int getuid() { return android.os.Process.myUid(); }
	public static int geteuid() { return android.os.Process.myUid(); }
	public static int getgid() { return android.os.Process.myUid(); }
	public static int getegid() { return android.os.Process.myUid(); }
	public static int gettid() { return (int) Thread.currentThread().getId(); }

	public static String getenv(String name) { return System.getenv(name); }
	public static void setenv(String name, String value, boolean overwrite) throws ErrnoException {}
	public static void unsetenv(String name) throws ErrnoException {}

	public static String strerror(int errno) { return "errno " + errno; }
	public static String strsignal(int signal) { return "signal " + signal; }

	public static StructStat stat(String path) throws ErrnoException {
		try {
			PosixFileAttributes a = Files.readAttributes(Paths.get(path), PosixFileAttributes.class);
			File f = new File(path);
			int mode = (f.isDirectory() ? OsConstants.S_IFDIR : (Files.isSymbolicLink(Paths.get(path)) ? OsConstants.S_IFLNK : OsConstants.S_IFREG));
			return new StructStat(0, 0, mode, 1, getuid(), getgid(), 0, a.size(), a.lastAccessTime().to(java.util.concurrent.TimeUnit.SECONDS),
			                      a.lastModifiedTime().to(java.util.concurrent.TimeUnit.SECONDS), a.lastModifiedTime().to(java.util.concurrent.TimeUnit.SECONDS), 4096, (a.size() + 511) / 512);
		} catch (IOException | UnsupportedOperationException e) {
			throw new ErrnoException("stat", OsConstants.ENOENT);
		}
	}

	public static StructStat lstat(String path) throws ErrnoException {
		try {
			PosixFileAttributes a = Files.readAttributes(Paths.get(path), PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			int mode = a.isDirectory() ? OsConstants.S_IFDIR : (a.isSymbolicLink() ? OsConstants.S_IFLNK : OsConstants.S_IFREG);
			return new StructStat(0, 0, mode, 1, getuid(), getgid(), 0, a.size(), 0, a.lastModifiedTime().to(java.util.concurrent.TimeUnit.SECONDS), 0, 4096, (a.size() + 511) / 512);
		} catch (IOException | UnsupportedOperationException e) {
			throw new ErrnoException("lstat", OsConstants.ENOENT);
		}
	}

	public static StructStat fstat(FileDescriptor fd) throws ErrnoException {
		return new StructStat(0, 0, OsConstants.S_IFREG, 1, getuid(), getgid(), 0, 0, 0, 0, 0, 4096, 0);
	}

	public static boolean access(String path, int mode) throws ErrnoException {
		return new File(path).exists();
	}

	public static String readlink(String path) throws ErrnoException {
		try {
			return Files.readSymbolicLink(Paths.get(path)).toString();
		} catch (IOException e) {
			throw new ErrnoException("readlink", OsConstants.EINVAL);
		}
	}

	public static void mkdir(String path, int mode) throws ErrnoException {
		if (!new File(path).mkdir())
			throw new ErrnoException("mkdir", OsConstants.EEXIST);
	}

	public static void remove(String path) throws ErrnoException {
		if (!new File(path).delete())
			throw new ErrnoException("remove", OsConstants.ENOENT);
	}

	public static void rename(String oldPath, String newPath) throws ErrnoException {
		if (!new File(oldPath).renameTo(new File(newPath)))
			throw new ErrnoException("rename", OsConstants.EINVAL);
	}

	public static void chmod(String path, int mode) throws ErrnoException {}
	public static void fsync(FileDescriptor fd) throws ErrnoException {}
	public static void fdatasync(FileDescriptor fd) throws ErrnoException {}
	public static void close(FileDescriptor fd) throws ErrnoException {}
	public static void setsockoptInt(FileDescriptor fd, int level, int option, int value) throws ErrnoException {}
	public static long sysconf(int name) { return name == OsConstants._SC_NPROCESSORS_CONF ? Runtime.getRuntime().availableProcessors() : 4096; }
	public static StructUtsname uname() { return new StructUtsname("Linux", "localhost", "6.12", "#1", System.getProperty("os.arch")); }
	public static long lseek(FileDescriptor fd, long offset, int whence) throws ErrnoException { return offset; }
}
