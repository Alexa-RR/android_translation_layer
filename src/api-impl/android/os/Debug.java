package android.os;

import dalvik.system.VMDebug;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

public final class Debug {
	public static class MemoryInfo {
		public int getTotalPss() {
			return 0;
		}

		public String getMemoryStat(String statName) {
			return "fixme getMemoryStat";
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

	/* ---- AOSP: allocation counting, backed by ART's counters (dalvik.system.VMDebug) ---- */

	public static void startAllocCounting() {
		VMDebug.startAllocCounting();
	}

	public static void stopAllocCounting() {
		VMDebug.stopAllocCounting();
	}

	public static int getGlobalAllocCount() {
		return VMDebug.getAllocCount(VMDebug.KIND_GLOBAL_ALLOCATED_OBJECTS);
	}

	public static void resetGlobalAllocCount() {
		VMDebug.resetAllocCount(VMDebug.KIND_GLOBAL_ALLOCATED_OBJECTS);
	}

	public static int getGlobalAllocSize() {
		return VMDebug.getAllocCount(VMDebug.KIND_GLOBAL_ALLOCATED_BYTES);
	}

	public static void resetGlobalAllocSize() {
		VMDebug.resetAllocCount(VMDebug.KIND_GLOBAL_ALLOCATED_BYTES);
	}

	public static int getGlobalFreedCount() {
		return VMDebug.getAllocCount(VMDebug.KIND_GLOBAL_FREED_OBJECTS);
	}

	public static void resetGlobalFreedCount() {
		VMDebug.resetAllocCount(VMDebug.KIND_GLOBAL_FREED_OBJECTS);
	}

	public static int getGlobalFreedSize() {
		return VMDebug.getAllocCount(VMDebug.KIND_GLOBAL_FREED_BYTES);
	}

	public static void resetGlobalFreedSize() {
		VMDebug.resetAllocCount(VMDebug.KIND_GLOBAL_FREED_BYTES);
	}

	public static int getGlobalGcInvocationCount() {
		return VMDebug.getAllocCount(VMDebug.KIND_GLOBAL_GC_INVOCATIONS);
	}

	public static void resetGlobalGcInvocationCount() {
		VMDebug.resetAllocCount(VMDebug.KIND_GLOBAL_GC_INVOCATIONS);
	}

	public static int getGlobalClassInitCount() {
		return VMDebug.getAllocCount(VMDebug.KIND_GLOBAL_CLASS_INIT_COUNT);
	}

	public static void resetGlobalClassInitCount() {
		VMDebug.resetAllocCount(VMDebug.KIND_GLOBAL_CLASS_INIT_COUNT);
	}

	public static int getGlobalClassInitTime() {
		/* cumulative elapsed time for class initialization, in usec */
		return VMDebug.getAllocCount(VMDebug.KIND_GLOBAL_CLASS_INIT_TIME);
	}

	public static void resetGlobalClassInitTime() {
		VMDebug.resetAllocCount(VMDebug.KIND_GLOBAL_CLASS_INIT_TIME);
	}

	/* AOSP: the "external" allocation counters are obsolete (ART has no external allocations);
	 * the getters report 0 and the resets have nothing to clear, as on Android. */

	@Deprecated
	public static int getGlobalExternalAllocCount() {
		return 0;
	}

	@Deprecated
	public static void resetGlobalExternalAllocCount() {}

	@Deprecated
	public static int getGlobalExternalAllocSize() {
		return 0;
	}

	@Deprecated
	public static void resetGlobalExternalAllocSize() {}

	@Deprecated
	public static int getGlobalExternalFreedCount() {
		return 0;
	}

	@Deprecated
	public static void resetGlobalExternalFreedCount() {}

	@Deprecated
	public static int getGlobalExternalFreedSize() {
		return 0;
	}

	@Deprecated
	public static void resetGlobalExternalFreedSize() {}

	public static int getThreadAllocCount() {
		return VMDebug.getAllocCount(VMDebug.KIND_THREAD_ALLOCATED_OBJECTS);
	}

	public static void resetThreadAllocCount() {
		VMDebug.resetAllocCount(VMDebug.KIND_THREAD_ALLOCATED_OBJECTS);
	}

	public static int getThreadAllocSize() {
		return VMDebug.getAllocCount(VMDebug.KIND_THREAD_ALLOCATED_BYTES);
	}

	public static void resetThreadAllocSize() {
		VMDebug.resetAllocCount(VMDebug.KIND_THREAD_ALLOCATED_BYTES);
	}

	@Deprecated
	public static int getThreadExternalAllocCount() {
		return 0;
	}

	@Deprecated
	public static void resetThreadExternalAllocCount() {}

	@Deprecated
	public static int getThreadExternalAllocSize() {
		return 0;
	}

	@Deprecated
	public static void resetThreadExternalAllocSize() {}

	public static int getThreadGcInvocationCount() {
		return VMDebug.getAllocCount(VMDebug.KIND_THREAD_GC_INVOCATIONS);
	}

	public static void resetThreadGcInvocationCount() {
		VMDebug.resetAllocCount(VMDebug.KIND_THREAD_GC_INVOCATIONS);
	}

	public static void resetAllCounts() {
		VMDebug.resetAllocCount(VMDebug.KIND_ALL_COUNTS);
	}

	/** AOSP: the number of classes the VM has loaded. */
	public static int getLoadedClassCount() {
		return VMDebug.getLoadedClassCount();
	}

	/** AOSP: prints the loaded classes to the log. */
	public static void printLoadedClasses(int flags) {
		VMDebug.printLoadedClasses(flags);
	}

	/* ---- AOSP: binder statistics ----
	 * Binders in this runtime never leave the process: no native BBinder, BinderProxy or death
	 * recipient is created, so those counts are 0, and there is no binder driver whose statistics
	 * could be read, which AOSP reports as -1. */

	public static int getBinderSentTransactions() {
		return -1;
	}

	public static int getBinderReceivedTransactions() {
		return -1;
	}

	public static int getBinderLocalObjectCount() {
		return 0;
	}

	public static int getBinderProxyObjectCount() {
		return 0;
	}

	public static int getBinderDeathObjectCount() {
		return 0;
	}

	/** AOSP: dumps the Java heap to fileName in hprof format. */
	public static void dumpHprofData(String fileName) throws IOException {
		VMDebug.dumpHprofData(fileName);
	}

	/* ---- AOSP: method tracing (ART's trace files) ---- */

	private static final String DEFAULT_TRACE_BODY = "dmtrace";
	private static final String DEFAULT_TRACE_EXTENSION = ".trace";

	public static void startMethodTracing() {
		VMDebug.startMethodTracing(fixTracePath(null), 0, 0, false, 0);
	}

	public static void startMethodTracing(String tracePath) {
		startMethodTracing(tracePath, 0, 0);
	}

	public static void startMethodTracing(String tracePath, int bufferSize) {
		startMethodTracing(tracePath, bufferSize, 0);
	}

	public static void startMethodTracing(String tracePath, int bufferSize, int flags) {
		VMDebug.startMethodTracing(fixTracePath(tracePath), bufferSize, flags, false, 0);
	}

	public static void stopMethodTracing() {
		VMDebug.stopMethodTracing();
	}

	/** AOSP: a relative or missing trace path goes to the app's external files directory. */
	private static String fixTracePath(String tracePath) {
		if (tracePath == null || tracePath.isEmpty() || tracePath.charAt(0) != '/') {
			File dir = null;
			android.atl.ATLLoadedApp app = android.atl.ATLLoadedApp.getPrimaryApplication();
			if (app != null && app.getApplication() != null)
				dir = app.getApplication().getExternalFilesDir(null);
			if (dir == null)
				dir = Environment.getExternalStorageDirectory();
			if (tracePath == null || tracePath.isEmpty()) {
				tracePath = new File(dir, DEFAULT_TRACE_BODY).getAbsolutePath();
			} else {
				tracePath = new File(dir, tracePath).getAbsolutePath();
			}
		}
		if (!tracePath.endsWith(DEFAULT_TRACE_EXTENSION)) {
			tracePath += DEFAULT_TRACE_EXTENSION;
		}
		return tracePath;
	}

	/* ---- AOSP: emulator (qemu) tracing; the sysfs switch exists only under the emulator ---- */

	private static final String SYSFS_QEMU_TRACE_STATE = "/sys/qemu_trace/state";

	private static void tlWriteQemuTraceState(String state) {
		PrintWriter outStream = null;
		try {
			outStream = new PrintWriter(new FileOutputStream(SYSFS_QEMU_TRACE_STATE));
			outStream.println(state);
		} catch (Exception e) {
			// not running in the emulator
		} finally {
			if (outStream != null)
				outStream.close();
		}
	}

	public static void startNativeTracing() {
		tlWriteQemuTraceState("1");
		VMDebug.startEmulatorTracing();
	}

	public static void stopNativeTracing() {
		VMDebug.stopEmulatorTracing();
		tlWriteQemuTraceState("0");
	}
}
