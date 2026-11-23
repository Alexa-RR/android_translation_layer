#include <sys/time.h>
#include <time.h>

#include "generated_headers/android_os_SystemClock.h"

/* AOSP's clocks (utils/SystemClock, android_os_SystemClock.cpp): milliseconds and microseconds are
 * the clock's nanoseconds truncated (nanoseconds_to_milliseconds), so a reading is never ahead of
 * the clock's nanosecond value */
static jlong clock_ns(clockid_t clock)
{
	struct timespec t;
	clock_gettime(clock, &t);
	return (jlong)t.tv_sec * 1000000000LL + t.tv_nsec;
}

JNIEXPORT jlong JNICALL Java_android_os_SystemClock_elapsedRealtime(JNIEnv *env, jclass this)
{
	return clock_ns(CLOCK_BOOTTIME) / 1000000;
}

JNIEXPORT jlong JNICALL Java_android_os_SystemClock_uptimeMillis(JNIEnv *env, jclass this)
{
	return clock_ns(CLOCK_MONOTONIC) / 1000000;
}

JNIEXPORT jlong JNICALL Java_android_os_SystemClock_elapsedRealtimeNanos(JNIEnv *env, jclass this)
{
	return clock_ns(CLOCK_BOOTTIME);
}

JNIEXPORT jlong JNICALL Java_android_os_SystemClock_currentThreadTimeMillis(JNIEnv *env, jclass this)
{
	return clock_ns(CLOCK_THREAD_CPUTIME_ID) / 1000000;
}

/* @hide: the calling thread's CPU time in microseconds (systemTime(SYSTEM_TIME_THREAD)) */
JNIEXPORT jlong JNICALL Java_android_os_SystemClock_currentThreadTimeMicro(JNIEnv *env, jclass this)
{
	return clock_ns(CLOCK_THREAD_CPUTIME_ID) / 1000;
}

/* @hide: the wall clock in microseconds (gettimeofday) */
JNIEXPORT jlong JNICALL Java_android_os_SystemClock_currentTimeMicro(JNIEnv *env, jclass this)
{
	struct timeval tv;
	gettimeofday(&tv, NULL);
	return (jlong)tv.tv_sec * 1000000LL + tv.tv_usec;
}

JNIEXPORT jboolean JNICALL Java_android_os_SystemClock_setCurrentTimeMillis(JNIEnv *env, jclass this, jlong millis)
{
	struct timespec ts;
	ts.tv_sec = millis / 1000;
	ts.tv_nsec = (millis % 1000) * 1000000;
	return clock_settime(CLOCK_REALTIME, &ts) == 0;
}
