package android.system;

/* Linux/aarch64 values. */
public final class OsConstants {
	private OsConstants() {}

	public static final int S_IFMT = 0170000, S_IFSOCK = 0140000, S_IFLNK = 0120000, S_IFREG = 0100000, S_IFBLK = 0060000,
	                        S_IFDIR = 0040000, S_IFCHR = 0020000, S_IFIFO = 0010000;
	public static final int S_IRWXU = 00700, S_IRUSR = 00400, S_IWUSR = 00200, S_IXUSR = 00100, S_IRWXG = 00070, S_IRGRP = 00040,
	                        S_IWGRP = 00020, S_IXGRP = 00010, S_IRWXO = 00007, S_IROTH = 00004, S_IWOTH = 00002, S_IXOTH = 00001;
	public static final int F_OK = 0, X_OK = 1, W_OK = 2, R_OK = 4;
	public static final int O_RDONLY = 0, O_WRONLY = 1, O_RDWR = 2, O_CREAT = 0100, O_EXCL = 0200, O_TRUNC = 01000, O_APPEND = 02000,
	                        O_NONBLOCK = 04000, O_CLOEXEC = 02000000;
	public static final int SEEK_SET = 0, SEEK_CUR = 1, SEEK_END = 2;
	public static final int EPERM = 1, ENOENT = 2, EINTR = 4, EIO = 5, EBADF = 9, EAGAIN = 11, ENOMEM = 12, EACCES = 13, EEXIST = 17,
	                        ENOTDIR = 20, EISDIR = 21, EINVAL = 22, ENOSPC = 28, EPIPE = 32, ERANGE = 34, ENOSYS = 38, ENOTEMPTY = 39,
	                        ENOTSUP = 95, ECONNRESET = 104, ETIMEDOUT = 110, ECONNREFUSED = 111, EHOSTUNREACH = 113, EWOULDBLOCK = 11;
	public static final int AF_UNIX = 1, AF_INET = 2, AF_INET6 = 10, SOCK_STREAM = 1, SOCK_DGRAM = 2, SOL_SOCKET = 1,
	                        SO_REUSEADDR = 2, SO_KEEPALIVE = 9, SO_RCVBUF = 8, SO_SNDBUF = 7, IPPROTO_TCP = 6, IPPROTO_UDP = 17, TCP_NODELAY = 1;
	public static final int PROT_NONE = 0, PROT_READ = 1, PROT_WRITE = 2, PROT_EXEC = 4, MAP_SHARED = 1, MAP_PRIVATE = 2, MAP_ANONYMOUS = 0x20;
	public static final int _SC_NPROCESSORS_CONF = 83, _SC_NPROCESSORS_ONLN = 84, _SC_PAGESIZE = 30, _SC_PAGE_SIZE = 30, _SC_CLK_TCK = 2;
	public static final int SIGHUP = 1, SIGINT = 2, SIGQUIT = 3, SIGKILL = 9, SIGSEGV = 11, SIGTERM = 15, SIGUSR1 = 10, SIGUSR2 = 12;
	public static final int STDIN_FILENO = 0, STDOUT_FILENO = 1, STDERR_FILENO = 2;

	public static boolean S_ISDIR(int mode) { return (mode & S_IFMT) == S_IFDIR; }
	public static boolean S_ISREG(int mode) { return (mode & S_IFMT) == S_IFREG; }
	public static boolean S_ISLNK(int mode) { return (mode & S_IFMT) == S_IFLNK; }
	public static boolean S_ISSOCK(int mode) { return (mode & S_IFMT) == S_IFSOCK; }
	public static boolean S_ISCHR(int mode) { return (mode & S_IFMT) == S_IFCHR; }
	public static boolean S_ISBLK(int mode) { return (mode & S_IFMT) == S_IFBLK; }
	public static boolean S_ISFIFO(int mode) { return (mode & S_IFMT) == S_IFIFO; }
	public static String errnoName(int errno) { return "errno" + errno; }
}
