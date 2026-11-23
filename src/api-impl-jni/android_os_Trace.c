/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * android.os.Trace's natives: AOSP's android_os_Trace.cpp over libcutils' atrace (trace-dev.cpp,
 * trace-dev.inc, android14-release) ported to C. Trace events are written to the kernel's
 * trace_marker as atrace writes them, so ftrace-based tools (perfetto, trace-cmd, the kernel's
 * trace buffer) see the app's sections. The tags come from the properties AOSP reads
 * (debug.atrace.tags.enableflags, debug.atrace.app_number, debug.atrace.app_N), which are ATL's
 * SystemProperties (ATL_SYSPROPS); without a writable trace_marker no tag is enabled, as on AOSP.
 */

#include <errno.h>
#include <fcntl.h>
#include <fnmatch.h>
#include <inttypes.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "defines.h"
#include "util.h"

#include "generated_headers/android_os_Trace.h"

#define ATRACE_MESSAGE_LENGTH 1024
#define ATRACE_TAG_ALWAYS (1ULL << 0)
#define ATRACE_TAG_APP (1ULL << 12)
#define ATRACE_TAG_NOT_READY (1ULL << 63)
#define ATRACE_TAG_LAST (1ULL << 27) /* ATRACE_TAG_THERMAL */
#define ATRACE_TAG_VALID_MASK ((ATRACE_TAG_LAST - 1) | ATRACE_TAG_LAST)

static int atrace_marker_fd = -1;
static uint64_t atrace_enabled_tags = ATRACE_TAG_NOT_READY;
static atomic_bool atrace_is_enabled = true;
static pthread_mutex_t atrace_tags_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t atrace_once_control = PTHREAD_ONCE_INIT;

/* property_get over android.os.SystemProperties.get(key, def): a malloc'd copy */
static char *property_get(JNIEnv *env, const char *key, const char *def)
{
	jclass cls = (*env)->FindClass(env, "android/os/SystemProperties");
	if (!cls) {
		(*env)->ExceptionClear(env);
		return strdup(def);
	}
	jmethodID get = (*env)->GetStaticMethodID(env, cls, "get", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
	if (!get) {
		(*env)->ExceptionClear(env);
		(*env)->DeleteLocalRef(env, cls);
		return strdup(def);
	}
	jstring jkey = (*env)->NewStringUTF(env, key);
	jstring jdef = (*env)->NewStringUTF(env, def);
	jstring v = (*env)->CallStaticObjectMethod(env, cls, get, jkey, jdef);
	char *ret;
	if ((*env)->ExceptionCheck(env) || !v) {
		(*env)->ExceptionClear(env);
		ret = strdup(def);
	} else {
		const char *s = (*env)->GetStringUTFChars(env, v, NULL);
		ret = strdup(s);
		(*env)->ReleaseStringUTFChars(env, v, s);
		(*env)->DeleteLocalRef(env, v);
	}
	(*env)->DeleteLocalRef(env, jkey);
	(*env)->DeleteLocalRef(env, jdef);
	(*env)->DeleteLocalRef(env, cls);
	return ret;
}

/* Check whether the given command line matches one of the comma-separated values listed in the
 * app_cmdlines property. */
static bool atrace_is_cmdline_match(JNIEnv *env, const char *cmdline)
{
	char *n = property_get(env, "debug.atrace.app_number", "0");
	int count = atoi(n);
	free(n);
	for (int i = 0; i < count; i++) {
		char buf[64];
		snprintf(buf, sizeof(buf), "debug.atrace.app_%d", i);
		char *value = property_get(env, buf, "");
		bool match = fnmatch(value, cmdline, FNM_NOESCAPE) == 0;
		free(value);
		if (match)
			return true;
	}
	return false;
}

/* Determine whether application-level tracing is enabled for this process. */
static bool atrace_is_app_tracing_enabled(JNIEnv *env)
{
	bool result = false;
	FILE *file = fopen("/proc/self/cmdline", "re");
	if (file) {
		char cmdline[4096];
		if (fgets(cmdline, sizeof(cmdline), file))
			result = atrace_is_cmdline_match(env, cmdline);
		else
			android_log_printf(ANDROID_LOG_ERROR, "cutils-trace", "Error reading cmdline: %s (%d)", strerror(errno), errno);
		fclose(file);
	} else {
		android_log_printf(ANDROID_LOG_ERROR, "cutils-trace", "Error opening /proc/self/cmdline: %s (%d)", strerror(errno), errno);
	}
	return result;
}

/* Read the sysprop and return the value tags should be set to */
static uint64_t atrace_get_property(JNIEnv *env)
{
	char *value = property_get(env, "debug.atrace.tags.enableflags", "0");
	char *endptr;
	errno = 0;
	uint64_t tags = strtoull(value, &endptr, 0);
	if (value[0] == '\0' || *endptr != '\0') {
		android_log_printf(ANDROID_LOG_ERROR, "cutils-trace", "Error parsing trace property: Not a number: %s", value);
		free(value);
		return 0;
	} else if (errno == ERANGE || tags == ULLONG_MAX) {
		android_log_printf(ANDROID_LOG_ERROR, "cutils-trace", "Error parsing trace property: Number too large: %s", value);
		free(value);
		return 0;
	}
	free(value);
	/* Only set the "app" tag if this process was selected for app-level debug tracing. */
	if (atrace_is_app_tracing_enabled(env))
		tags |= ATRACE_TAG_APP;
	else
		tags &= ~ATRACE_TAG_APP;
	return (tags | ATRACE_TAG_ALWAYS) & ATRACE_TAG_VALID_MASK;
}

static void atrace_init_once(void)
{
	atrace_marker_fd = open("/sys/kernel/tracing/trace_marker", O_WRONLY | O_CLOEXEC);
	if (atrace_marker_fd == -1)
		atrace_marker_fd = open("/sys/kernel/debug/tracing/trace_marker", O_WRONLY | O_CLOEXEC);
	if (atrace_marker_fd == -1)
		android_log_printf(ANDROID_LOG_DEBUG, "cutils-trace", "Error opening trace file: %s (%d)", strerror(errno), errno);
}

/* Update tags if tracing is ready. */
static void atrace_update_tags(JNIEnv *env)
{
	pthread_once(&atrace_once_control, atrace_init_once);
	uint64_t tags;
	if (atrace_marker_fd == -1)
		tags = 0; /* no trace file: nothing is traced */
	else if (atomic_load_explicit(&atrace_is_enabled, memory_order_acquire))
		tags = atrace_get_property(env);
	else
		tags = ATRACE_TAG_NOT_READY; /* tracing is disabled for this process */
	pthread_mutex_lock(&atrace_tags_mutex);
	atrace_enabled_tags = tags;
	pthread_mutex_unlock(&atrace_tags_mutex);
}

/* AOSP withString: at most 1024 UTF-16 units, '\n' and '|' (the marker's separators) as spaces,
 * "(null)" for null */
static void with_string(JNIEnv *env, jstring jstr, char *buffer, size_t size)
{
	if (!jstr) {
		snprintf(buffer, size, "(null)");
		return;
	}
	memset(buffer, 0, size);
	jsize len = (*env)->GetStringLength(env, jstr);
	if (len > 1024)
		len = 1024;
	(*env)->GetStringUTFRegion(env, jstr, 0, len, buffer);
	for (char *s = buffer; *s; s++) {
		if (*s == '\n' || *s == '|')
			*s = ' ';
	}
}

/* libcutils WRITE_MSG without a track name: the name truncated to make the message fit */
static void write_msg(const char *format_begin, const char *name, const char *suffix)
{
	if (atrace_marker_fd == -1)
		return;
	char buf[ATRACE_MESSAGE_LENGTH];
	char head[32];
	int pid = getpid();
	int head_len = snprintf(head, sizeof(head), format_begin, pid);
	int len = snprintf(buf, sizeof(buf), "%s%s%s", head, name, suffix);
	if (len >= (int)sizeof(buf)) {
		int name_len = (int)strlen(name) - (len - (int)sizeof(buf)) - 1;
		if (name_len > 0)
			len = snprintf(buf, sizeof(buf), "%s%.*s%s", head, name_len, name, suffix);
		else
			len = 0; /* Data is still too long. Drop it. */
	}
	(void)head_len;
	if (len > 0) {
		ssize_t r = write(atrace_marker_fd, buf, len);
		(void)r;
	}
}

JNIEXPORT jlong JNICALL Java_android_os_Trace_nativeGetEnabledTags(JNIEnv *env, jclass clazz)
{
	if (atrace_enabled_tags == ATRACE_TAG_NOT_READY)
		atrace_update_tags(env);
	return (jlong)atrace_enabled_tags;
}

JNIEXPORT void JNICALL Java_android_os_Trace_nativeTraceCounter(JNIEnv *env, jclass clazz, jlong tag, jstring name, jint value)
{
	char str[4097], suffix[24];
	with_string(env, name, str, sizeof(str));
	snprintf(suffix, sizeof(suffix), "|%" PRId32, (int32_t)value);
	write_msg("C|%d|", str, suffix);
}

JNIEXPORT void JNICALL Java_android_os_Trace_nativeTraceBegin(JNIEnv *env, jclass clazz, jlong tag, jstring name)
{
	char str[4097];
	with_string(env, name, str, sizeof(str));
	write_msg("B|%d|", str, "");
}

JNIEXPORT void JNICALL Java_android_os_Trace_nativeTraceEnd(JNIEnv *env, jclass clazz, jlong tag)
{
	write_msg("E|%d", "", "");
}

JNIEXPORT void JNICALL Java_android_os_Trace_nativeAsyncTraceBegin(JNIEnv *env, jclass clazz, jlong tag, jstring name, jint cookie)
{
	char str[4097], suffix[24];
	with_string(env, name, str, sizeof(str));
	snprintf(suffix, sizeof(suffix), "|%" PRId32, (int32_t)cookie);
	write_msg("S|%d|", str, suffix);
}

JNIEXPORT void JNICALL Java_android_os_Trace_nativeAsyncTraceEnd(JNIEnv *env, jclass clazz, jlong tag, jstring name, jint cookie)
{
	char str[4097], suffix[24];
	with_string(env, name, str, sizeof(str));
	snprintf(suffix, sizeof(suffix), "|%" PRId32, (int32_t)cookie);
	write_msg("F|%d|", str, suffix);
}

/* AOSP: the app tag may change with this (debuggable apps), so the tags are read again */
JNIEXPORT void JNICALL Java_android_os_Trace_nativeSetAppTracingAllowed(JNIEnv *env, jclass clazz, jboolean allowed)
{
	atrace_update_tags(env);
}

/* atrace_set_tracing_enabled: whether this process traces at all (AOSP keeps the Zygote from it) */
JNIEXPORT void JNICALL Java_android_os_Trace_nativeSetTracingEnabled(JNIEnv *env, jclass clazz, jboolean enabled)
{
	atomic_store_explicit(&atrace_is_enabled, enabled, memory_order_release);
	atrace_update_tags(env);
}
