#include <sys/resource.h>
#include <errno.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>

#include "../generated_headers/android_os_Process.h"

JNIEXPORT jboolean JNICALL Java_android_os_Process_is64Bit(JNIEnv *env, jclass this)
{
#ifdef __LP64__
	return 1;
#else
	return 0;
#endif
}

JNIEXPORT jint JNICALL Java_android_os_Process_getThreadPriority(JNIEnv *env, jclass this, jint tid)
{
	return getpriority(PRIO_PROCESS, tid);
}

/* tl_set_thread_priority: AOSP android_os_Process_setThreadPriority */
static void tl_set_thread_priority(JNIEnv *env, jint tid, jint pri)
{
	if (pri < -20)
		pri = -20;
	if (pri > 19)
		pri = 19;
	if (setpriority(PRIO_PROCESS, tid, pri) == 0)
		return;
	int err = errno;
	if ((err == EACCES || err == EPERM) && pri < getpriority(PRIO_PROCESS, tid)) {
		/* raising beyond RLIMIT_NICE: take the best level the limit allows (20 - rlim_cur) */
		struct rlimit rl;
		if (getrlimit(RLIMIT_NICE, &rl) == 0) {
			int best = 20 - (int)rl.rlim_cur;
			if (best > 19)
				best = 19;
			if (best < pri)
				best = pri;
			errno = 0;
			int cur = getpriority(PRIO_PROCESS, tid);
			if (errno == 0 && best < cur && setpriority(PRIO_PROCESS, tid, best) == 0)
				return;
			if (errno == 0 && best >= cur)
				return; /* already as high as allowed */
			err = errno;
		}
	}
	char buf[128];
	if (err == ESRCH || err == EINVAL) {
		snprintf(buf, sizeof(buf), "Given thread %d does not exist", tid);
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), buf);
	} else if (err == EPERM || err == EACCES) {
		snprintf(buf, sizeof(buf), "No permission to modify given thread %d", tid);
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/SecurityException"), buf);
	} else {
		snprintf(buf, sizeof(buf), "Unknown error %d", err);
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), buf);
	}
}

JNIEXPORT void JNICALL Java_android_os_Process_setThreadPriority__II(JNIEnv *env, jclass clazz, jint tid, jint pri)
{
	tl_set_thread_priority(env, tid, pri);
}

JNIEXPORT void JNICALL Java_android_os_Process_setThreadPriority__I(JNIEnv *env, jclass clazz, jint pri)
{
	tl_set_thread_priority(env, (jint)syscall(SYS_gettid), pri);
}

JNIEXPORT jlong JNICALL Java_android_os_Process_getElapsedCpuTime(JNIEnv *env, jclass this)
{
	struct timespec now;
	if (clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &now) != 0)
		return 0;
	return now.tv_sec * 1000 + now.tv_nsec / 1000000;
}
