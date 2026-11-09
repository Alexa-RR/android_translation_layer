package android.os;

import dalvik.system.VMDebug;

public final class Debug {
	public static class MemoryInfo {
		public int getTotalPss() {
			return 0;
		}
	}

	/** AOSP: blocks until a debugger attaches, when the VM has debugging enabled. */
	public static void waitForDebugger() {
		if (!VMDebug.isDebuggingEnabled())
			return;
		if (isDebuggerConnected())
			return;
		System.out.println("Sending WAIT chunk");
		tlWaitingForDebugger = true;
		try {
			while (!isDebuggerConnected()) {
				try {
					Thread.sleep(200);
				} catch (InterruptedException ie) {
				}
			}
		} finally {
			tlWaitingForDebugger = false;
		}
	}

	public static class InstructionCount {
		public InstructionCount() {
		}
	}

	/** AOSP: whether a debugger is attached to the VM (ART's JDWP state). */
	public static boolean isDebuggerConnected() {
		return VMDebug.isDebuggerConnected();
	}

	/** AOSP: the free part of the native heap (see tlProcStatusKb). */
	public static long getNativeHeapFreeSize() {
		return Math.max(0, getNativeHeapSize() - getNativeHeapAllocatedSize());
	}

	/** AOSP: the allocated part of the native heap (see tlProcStatusKb). */
	public static long getNativeHeapAllocatedSize() {
		return Math.min(tlProcStatusKb("RssAnon:"), tlProcStatusKb("VmData:")) * 1024;
	}

	/** AOSP: whether waitForDebugger() is waiting. */
	public static boolean waitingForDebugger() {
		return tlWaitingForDebugger;
	}

	public static long threadCpuTimeNanos() {
		return VMDebug.threadCpuTimeNanos();
	}

	public static void getMemoryInfo(MemoryInfo memoryInfo) {}

	/* teslalinux: Debug additions */
	/**
	 * AOSP reads the native heap from mallinfo(). The Alpine target uses musl's malloc, which has
	 * no mallinfo, so the native heap is taken from the kernel's view of this process: the size is
	 * its data segment (VmData: brk heap and anonymous mappings malloc draws from), the allocated
	 * part its resident anonymous memory (RssAnon), the free part the rest.
	 */
	private static long tlProcStatusKb(String key) {
		try {
			for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/proc/self/status"))) {
				if (line.startsWith(key)) {
					String[] f = line.substring(key.length()).trim().split("\\s+");
					return Long.parseLong(f[0]);
				}
			}
		} catch (Exception e) {
			// no /proc: unknown, reported as 0 like a failed mallinfo
		}
		return 0;
	}
	public static long getNativeHeapSize() { return tlProcStatusKb("VmData:") * 1024; }
	private static volatile boolean tlWaitingForDebugger;
	/** AOSP (hidden): the depth callers of the caller, "Class.method:line" separated by spaces. */
	public static String getCallers(final int depth) {
		final StackTraceElement[] callStack = Thread.currentThread().getStackTrace();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < depth; i++) {
			sb.append(tlGetCaller(callStack, i)).append(" ");
		}
		return sb.toString();
	}
	private static String tlGetCaller(StackTraceElement[] callStack, int depth) {
		// callStack[4] is the caller of the method that called getCallers()
		if (4 + depth >= callStack.length) {
			return "<bottom of call stack>";
		}
		StackTraceElement caller = callStack[4 + depth];
		return caller.getClassName() + "." + caller.getMethodName() + ":" + caller.getLineNumber();
	}
}
