package android.net;

public class TrafficStats {
	public static void setThreadStatsTag(int dummy) {}

	public static void clearThreadStatsTag() {}

	/* teslalinux: TrafficStats additions (OkHttp/Cronet tag sockets) */
	public static final int UNSUPPORTED = -1;
	public static int getThreadStatsTag() { return -1; }
	public static void setThreadStatsUid(int uid) {}
	public static void clearThreadStatsUid() {}
	public static long getUidRxBytes(int uid) { return 0; }
	public static long getUidTxBytes(int uid) { return 0; }
	public static long getUidRxPackets(int uid) { return 0; }
	public static long getUidTxPackets(int uid) { return 0; }
	public static long getTotalRxBytes() { return 0; }
	public static long getTotalTxBytes() { return 0; }
	public static long getMobileRxBytes() { return 0; }
	public static long getMobileTxBytes() { return 0; }
	public static void incrementOperationCount(int operationCount) {}
	public static void incrementOperationCount(int tag, int operationCount) {}
	public static void tagSocket(java.net.Socket socket) {}
	public static void untagSocket(java.net.Socket socket) {}
	public static void tagDatagramSocket(java.net.DatagramSocket socket) {}
	public static void untagDatagramSocket(java.net.DatagramSocket socket) {}
	public static void tagFileDescriptor(java.io.FileDescriptor fd) {}
	public static void untagFileDescriptor(java.io.FileDescriptor fd) {}
}
