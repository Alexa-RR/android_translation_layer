/*
 * Copyright (C) 2008 The Android Open Source Project
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

/* android.os.Process's natives: AOSP's android_util_Process.cpp (android14-release) on Linux */

#define _GNU_SOURCE
#include <sys/resource.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <inttypes.h>
#include <limits.h>
#include <pthread.h>
#include <pwd.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/sysinfo.h>
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_os_Process.h"

static void throw_fmt(JNIEnv *env, const char *cls, const char *fmt, int arg)
{
	char buf[128];
	snprintf(buf, sizeof(buf), fmt, arg);
	(*env)->ThrowNew(env, (*env)->FindClass(env, cls), buf);
}

/* AOSP signalExceptionForError / ForPriorityError / ForGroupError (err: an errno) */
static void signal_exception_for_error(JNIEnv *env, int err, int tid)
{
	switch (err) {
		case EINVAL:
			throw_fmt(env, "java/lang/IllegalArgumentException", "Invalid argument: %d", tid);
			break;
		case ESRCH:
			throw_fmt(env, "java/lang/IllegalArgumentException", "Given thread %d does not exist", tid);
			break;
		case EPERM:
			throw_fmt(env, "java/lang/SecurityException", "No permission to modify given thread %d", tid);
			break;
		default:
			(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), "Unknown error");
			break;
	}
}

static void signal_exception_for_priority_error(JNIEnv *env, int err, int tid)
{
	if (err == EACCES)
		throw_fmt(env, "java/lang/SecurityException", "No permission to set the priority of %d", tid);
	else
		signal_exception_for_error(env, err, tid);
}

static void signal_exception_for_group_error(JNIEnv *env, int err, int tid)
{
	if (err == EACCES)
		throw_fmt(env, "java/lang/SecurityException", "No permission to set the group of %d", tid);
	else
		signal_exception_for_error(env, err, tid);
}

/* getpwnam/getgrnam, or the number when the name is all digits; -1 when unknown */
static jint id_for_name(JNIEnv *env, jstring name, int group)
{
	if (!name) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return -1;
	}
	const char *str = (*env)->GetStringUTFChars(env, name, NULL);
	jint ret = -1;
	if (str && str[0]) {
		int digits = 1;
		for (const char *p = str; *p; p++) {
			if (*p < '0' || *p > '9') {
				digits = 0;
				break;
			}
		}
		if (digits) {
			ret = atoi(str);
		} else if (group) {
			struct group *grp = getgrnam(str);
			ret = grp ? (jint)grp->gr_gid : -1;
		} else {
			struct passwd *pwd = getpwnam(str);
			ret = pwd ? (jint)pwd->pw_uid : -1;
		}
	}
	if (str)
		(*env)->ReleaseStringUTFChars(env, name, str);
	return ret;
}

JNIEXPORT jint JNICALL Java_android_os_Process_getUidForName(JNIEnv *env, jclass clazz, jstring name)
{
	return id_for_name(env, name, 0);
}

JNIEXPORT jint JNICALL Java_android_os_Process_getGidForName(JNIEnv *env, jclass clazz, jstring name)
{
	return id_for_name(env, name, 1);
}

/*
 * Scheduling groups. AOSP applies libprocessgroup's task profiles (task_profiles.json) for the
 * group's SchedPolicy; their cgroup actions need Android's cgroup hierarchy (cgroups.json), which a
 * Linux host does not have, and libprocessgroup leaves out an action whose controller is missing.
 * What remains on such a kernel is the timer slack of the SCHED_SP_* profiles (TimerSlackHigh,
 * 40 ms, for the background group; TimerSlackNormal, 50 us, for the others), applied here as
 * SetTimerSlackAction does; and get_sched_policy, with no cpu/cpuset group to read, reports
 * SP_FOREGROUND.
 */
enum { SP_DEFAULT = -1, SP_BACKGROUND = 0, SP_FOREGROUND = 1, SP_CNT = 9 };

static int verify_group(JNIEnv *env, int grp)
{
	if (grp < SP_DEFAULT || grp >= SP_CNT) {
		signal_exception_for_error(env, EINVAL, grp);
		return 0;
	}
	return 1;
}

static void set_timer_slack(int tid, int grp)
{
	unsigned long slack = grp == SP_BACKGROUND ? 40000000UL : 50000UL;
	char path[64], value[32];
	snprintf(path, sizeof(path), "/proc/%d/timerslack_ns", tid);
	int fd = open(path, O_WRONLY | O_CLOEXEC);
	int len = snprintf(value, sizeof(value), "%lu", slack);
	if (fd >= 0 && write(fd, value, len) == len) {
		close(fd);
		return;
	}
	int err = errno;
	if (fd >= 0)
		close(fd);
	if (err == ENOENT)
		return; /* the thread has exited */
	if (tid == (int)syscall(SYS_gettid) && prctl(PR_SET_TIMERSLACK, slack) == 0)
		return;
	android_log_printf(ANDROID_LOG_ERROR, "libprocessgroup", "set_timerslack_ns write failed: %s", strerror(err));
}

JNIEXPORT void JNICALL Java_android_os_Process_setThreadGroup(JNIEnv *env, jclass clazz, jint tid, jint grp)
{
	if (!verify_group(env, grp))
		return;
	set_timer_slack(tid ? tid : (int)syscall(SYS_gettid), grp);
}

JNIEXPORT void JNICALL Java_android_os_Process_setProcessGroup(JNIEnv *env, jclass clazz, jint pid, jint grp)
{
	if (!verify_group(env, grp))
		return;
	if (grp == SP_FOREGROUND) {
		signal_exception_for_group_error(env, EINVAL, pid);
		return;
	}
	/* AOSP: the CPUSET_SP_* profile of every thread, cpuset actions only (none without Android's
	 * cgroups); the process must exist */
	if (kill(pid, 0) != 0 && errno == ESRCH)
		signal_exception_for_group_error(env, ESRCH, pid);
}

JNIEXPORT jint JNICALL Java_android_os_Process_getProcessGroup(JNIEnv *env, jclass clazz, jint pid)
{
	if (kill(pid, 0) != 0 && errno == ESRCH) {
		signal_exception_for_group_error(env, ESRCH, pid);
		return SP_FOREGROUND;
	}
	return SP_FOREGROUND;
}

/* AOSP: compiled without GUARD_THREAD_PRIORITY this records nothing (only the system process's
 * main looper used it, to refuse being put in the background) */
JNIEXPORT void JNICALL Java_android_os_Process_setCanSelfBackground(JNIEnv *env, jclass clazz, jboolean bg_ok)
{
}

JNIEXPORT void JNICALL Java_android_os_Process_setThreadScheduler(JNIEnv *env, jclass clazz, jint tid, jint policy, jint pri)
{
	struct sched_param param = { .sched_priority = pri };
	if (sched_setscheduler(tid, policy, &param))
		signal_exception_for_priority_error(env, errno, tid);
}

/* the legacy oom_adj scale (-17..15) as the kernel converts it for oom_score_adj (-1000..1000) */
JNIEXPORT jboolean JNICALL Java_android_os_Process_setOomAdj(JNIEnv *env, jclass clazz, jint pid, jint adj)
{
	char path[64], text[32];
	snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", pid);
	int fd = open(path, O_WRONLY | O_CLOEXEC);
	if (fd < 0)
		return JNI_FALSE;
	int score = adj == 15 ? 1000 : adj * 1000 / 17;
	if (adj <= -17)
		score = -1000;
	int len = snprintf(text, sizeof(text), "%d", score);
	ssize_t r = write(fd, text, len);
	close(fd);
	return r == len;
}

JNIEXPORT jboolean JNICALL Java_android_os_Process_setSwappiness(JNIEnv *env, jclass clazz, jint pid, jboolean is_increased)
{
	char text[64];
	strcpy(text, is_increased ? "/sys/fs/cgroup/memory/sw/tasks" : "/sys/fs/cgroup/memory/tasks");
	struct stat st;
	if (stat(text, &st) || !S_ISREG(st.st_mode))
		return JNI_FALSE;
	int fd = open(text, O_WRONLY | O_CLOEXEC);
	if (fd >= 0) {
		int len = snprintf(text, sizeof(text), "%" PRId32, (int32_t)pid);
		ssize_t r = write(fd, text, len);
		(void)r;
		close(fd);
	}
	return JNI_TRUE;
}

/* AndroidRuntime::setArgv0(name, true): the thread name (the last 15 bytes) and the process's whole
 * argument block, which /proc/PID/cmdline and ps show (its bounds from /proc/self/stat) */
JNIEXPORT void JNICALL Java_android_os_Process_setArgV0(JNIEnv *env, jclass clazz, jstring name)
{
	if (!name) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return;
	}
	const char *argv0 = (*env)->GetStringUTFChars(env, name, NULL);
	if (!argv0)
		return;
	size_t len = strlen(argv0);
	if (len == 0) {
		(*env)->ReleaseStringUTFChars(env, name, argv0);
		return;
	}
	pthread_setname_np(pthread_self(), len < 15 ? argv0 : argv0 + len - 15);
	unsigned long arg_start = 0, arg_end = 0;
	FILE *f = fopen("/proc/self/stat", "re");
	if (f) {
		char buf[2048];
		size_t n = fread(buf, 1, sizeof(buf) - 1, f);
		fclose(f);
		buf[n] = 0;
		/* fields after the command (which may hold spaces) start after the last ')': field 3 */
		char *p = strrchr(buf, ')');
		int field = 2;
		while (p && *p && field < 49) {
			p = strchr(p, ' ');
			if (!p)
				break;
			p++;
			field++;
			if (field == 48)
				arg_start = strtoul(p, NULL, 10);
			else if (field == 49)
				arg_end = strtoul(p, NULL, 10);
		}
	}
	if (arg_start && arg_end > arg_start) {
		char *block = (char *)arg_start;
		size_t block_len = arg_end - arg_start;
		memset(block, 0, block_len);
		strncpy(block, argv0, block_len - 1);
	}
	(*env)->ReleaseStringUTFChars(env, name, argv0);
}

JNIEXPORT jint JNICALL Java_android_os_Process_setUid(JNIEnv *env, jclass clazz, jint uid)
{
	return setuid(uid) == 0 ? 0 : errno;
}

JNIEXPORT jint JNICALL Java_android_os_Process_setGid(JNIEnv *env, jclass clazz, jint gid)
{
	return setgid(gid) == 0 ? 0 : errno;
}

JNIEXPORT void JNICALL Java_android_os_Process_sendSignal(JNIEnv *env, jclass clazz, jint pid, jint sig)
{
	if (pid > 0) {
		android_log_printf(ANDROID_LOG_INFO, "Process", "Sending signal. PID: %" PRId32 " SIG: %" PRId32, (int32_t)pid, (int32_t)sig);
		kill(pid, sig);
	}
}

JNIEXPORT void JNICALL Java_android_os_Process_sendSignalQuiet(JNIEnv *env, jclass clazz, jint pid, jint sig)
{
	if (pid > 0)
		kill(pid, sig);
}

/* /proc/meminfo's value of FIELD (kB), -1 when missing */
static long long meminfo_kb(const char *field)
{
	FILE *f = fopen("/proc/meminfo", "re");
	if (!f)
		return -1;
	char line[256];
	size_t flen = strlen(field);
	long long v = -1;
	while (fgets(line, sizeof(line), f)) {
		if (strncmp(line, field, flen) == 0 && line[flen] == ':') {
			v = strtoll(line + flen + 1, NULL, 10);
			break;
		}
	}
	fclose(f);
	return v;
}

/* AOSP: MemFree + Cached (SysMemInfo::ReadMemInfo), in bytes */
JNIEXPORT jlong JNICALL Java_android_os_Process_getFreeMemory(JNIEnv *env, jclass clazz)
{
	long long free_kb = meminfo_kb("MemFree");
	long long cached_kb = meminfo_kb("Cached");
	if (free_kb < 0 || cached_kb < 0) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), "SysMemInfo read failed to get Free Memory");
		return -1;
	}
	return (jlong)(free_kb + cached_kb) * 1024;
}

JNIEXPORT jlong JNICALL Java_android_os_Process_getTotalMemory(JNIEnv *env, jclass clazz)
{
	struct sysinfo si;
	if (sysinfo(&si) == -1)
		return -1;
	return (jlong)si.totalram * si.mem_unit;
}

/* AOSP readProcLines: for each requested field prefix, the number after it on its line; 0 for a
 * field not found; untouched (the caller's -1) when the file cannot be opened */
JNIEXPORT void JNICALL Java_android_os_Process_readProcLines(JNIEnv *env, jclass clazz, jstring file_str, jobjectArray req_fields, jlongArray out_fields)
{
	if (!file_str || !req_fields || !out_fields) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return;
	}
	jsize count = (*env)->GetArrayLength(env, req_fields);
	if (count > (*env)->GetArrayLength(env, out_fields)) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "Array lengths differ");
		return;
	}
	char **fields = calloc(count ? count : 1, sizeof(char *));
	for (jsize i = 0; i < count; i++) {
		jstring obj = (*env)->GetObjectArrayElement(env, req_fields, i);
		if (!obj) {
			for (jsize k = 0; k < i; k++)
				free(fields[k]);
			free(fields);
			(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), "Element in reqFields");
			return;
		}
		const char *s = (*env)->GetStringUTFChars(env, obj, NULL);
		fields[i] = strdup(s);
		(*env)->ReleaseStringUTFChars(env, obj, s);
		(*env)->DeleteLocalRef(env, obj);
	}
	const char *file = (*env)->GetStringUTFChars(env, file_str, NULL);
	int fd = open(file, O_RDONLY | O_CLOEXEC);
	(*env)->ReleaseStringUTFChars(env, file_str, file);
	if (fd >= 0) {
		jlong *sizes = (*env)->GetLongArrayElements(env, out_fields, NULL);
		for (jsize i = 0; i < count; i++)
			sizes[i] = 0;
		const size_t BUFFER_SIZE = 4096;
		char *buffer = malloc(BUFFER_SIZE);
		ssize_t len = read(fd, buffer, BUFFER_SIZE - 1);
		close(fd);
		if (len < 0)
			len = 0;
		buffer[len] = 0;
		int found = 0;
		char *p = buffer;
		while (*p && found < count) {
			int skip_to_eol = 1;
			for (jsize i = 0; i < count; i++) {
				size_t flen = strlen(fields[i]);
				if (strncmp(p, fields[i], flen) == 0) {
					p += flen;
					while (*p == ' ' || *p == '\t')
						p++;
					char *num = p;
					while (*p >= '0' && *p <= '9')
						p++;
					skip_to_eol = *p != '\n';
					if (*p != 0) {
						*p = 0;
						p++;
					}
					sizes[i] = strtoll(num, NULL, 10);
					found++;
					break;
				}
			}
			if (skip_to_eol) {
				while (*p && *p != '\n')
					p++;
				if (*p == '\n')
					p++;
			}
		}
		free(buffer);
		(*env)->ReleaseLongArrayElements(env, out_fields, sizes, 0);
	}
	for (jsize i = 0; i < count; i++)
		free(fields[i]);
	free(fields);
}

static int pid_compare(const void *v1, const void *v2)
{
	return *((const jint *)v1) - *((const jint *)v2);
}

/* AOSP getPids: the numeric entries of a directory, sorted, in LAST_ARRAY when they fit (the rest -1) */
JNIEXPORT jintArray JNICALL Java_android_os_Process_getPids(JNIEnv *env, jclass clazz, jstring file, jintArray last_array)
{
	if (!file) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return NULL;
	}
	const char *path = (*env)->GetStringUTFChars(env, file, NULL);
	DIR *dirp = opendir(path);
	(*env)->ReleaseStringUTFChars(env, file, path);
	if (!dirp)
		return NULL;
	jint *pids = NULL;
	jsize n = 0, cap = 0;
	struct dirent *entry;
	while ((entry = readdir(dirp)) != NULL) {
		const char *p = entry->d_name;
		while (*p >= '0' && *p <= '9')
			p++;
		if (*p != 0 || p == entry->d_name)
			continue;
		if (n == cap) {
			cap = cap ? cap * 2 : 64;
			pids = realloc(pids, cap * sizeof(jint));
		}
		pids[n++] = (jint)strtol(entry->d_name, NULL, 10);
	}
	closedir(dirp);
	if (n > 0)
		qsort(pids, n, sizeof(jint), pid_compare);
	jsize cur_count = last_array ? (*env)->GetArrayLength(env, last_array) : 0;
	jintArray out = last_array;
	if (n > cur_count || !last_array) {
		/* AOSP grows the array by doubling from 10 */
		jsize new_count = cur_count ? cur_count : 10;
		while (new_count < n)
			new_count *= 2;
		out = (*env)->NewIntArray(env, new_count);
		if (!out) {
			free(pids);
			return NULL;
		}
		cur_count = new_count;
	}
	jint *data = (*env)->GetIntArrayElements(env, out, NULL);
	for (jsize i = 0; i < cur_count; i++)
		data[i] = i < n ? pids[i] : -1;
	(*env)->ReleaseIntArrayElements(env, out, data, 0);
	free(pids);
	return out;
}

enum {
	PROC_TERM_MASK = 0xff,
	PROC_COMBINE = 0x100,
	PROC_PARENS = 0x200,
	PROC_QUOTES = 0x400,
	PROC_CHAR = 0x800,
	PROC_OUT_STRING = 0x1000,
	PROC_OUT_LONG = 0x2000,
	PROC_OUT_FLOAT = 0x4000,
};

/* AOSP parseProcLineArray (modifies the buffer while parsing) */
static jboolean parse_proc_line_array(JNIEnv *env, char *buffer, jint start_index, jint end_index, jintArray format,
                                      jobjectArray out_strings, jlongArray out_longs, jfloatArray out_floats)
{
	const jsize NF = (*env)->GetArrayLength(env, format);
	const jsize NS = out_strings ? (*env)->GetArrayLength(env, out_strings) : 0;
	const jsize NL = out_longs ? (*env)->GetArrayLength(env, out_longs) : 0;
	const jsize NR = out_floats ? (*env)->GetArrayLength(env, out_floats) : 0;
	jint *format_data = (*env)->GetIntArrayElements(env, format, NULL);
	jlong *longs_data = out_longs ? (*env)->GetLongArrayElements(env, out_longs, NULL) : NULL;
	jfloat *floats_data = out_floats ? (*env)->GetFloatArrayElements(env, out_floats, NULL) : NULL;
	jsize i = start_index;
	jsize di = 0;
	jboolean res = JNI_TRUE;
	for (jsize fi = 0; fi < NF; fi++) {
		jint mode = format_data[fi];
		if ((mode & PROC_PARENS) != 0) {
			i++;
		} else if ((mode & PROC_QUOTES) != 0) {
			if (buffer[i] == '"')
				i++;
			else
				mode &= ~PROC_QUOTES;
		}
		const char term = (char)(mode & PROC_TERM_MASK);
		const jsize start = i;
		if (i >= end_index) {
			res = JNI_FALSE;
			break;
		}
		jsize end = -1;
		if ((mode & PROC_PARENS) != 0) {
			while (i < end_index && buffer[i] != ')')
				i++;
			end = i;
			i++;
		} else if ((mode & PROC_QUOTES) != 0) {
			while (i < end_index && buffer[i] != '"')
				i++;
			end = i;
			i++;
		}
		while (i < end_index && buffer[i] != term)
			i++;
		if (end < 0)
			end = i;
		if (i < end_index) {
			i++;
			if ((mode & PROC_COMBINE) != 0) {
				while (i < end_index && buffer[i] == term)
					i++;
			}
		}
		if ((mode & (PROC_OUT_FLOAT | PROC_OUT_LONG | PROC_OUT_STRING)) != 0) {
			char c = buffer[end];
			buffer[end] = 0;
			if ((mode & PROC_OUT_FLOAT) != 0 && di < NR)
				floats_data[di] = strtof(buffer + start, NULL);
			if ((mode & PROC_OUT_LONG) != 0 && di < NL) {
				if ((mode & PROC_CHAR) != 0)
					longs_data[di] = buffer[start]; /* the single first character as one long */
				else
					longs_data[di] = strtoll(buffer + start, NULL, 10);
			}
			if ((mode & PROC_OUT_STRING) != 0 && di < NS) {
				jstring str = (*env)->NewStringUTF(env, buffer + start);
				(*env)->SetObjectArrayElement(env, out_strings, di, str);
				(*env)->DeleteLocalRef(env, str);
			}
			buffer[end] = c;
			di++;
		}
	}
	(*env)->ReleaseIntArrayElements(env, format, format_data, 0);
	if (longs_data)
		(*env)->ReleaseLongArrayElements(env, out_longs, longs_data, 0);
	if (floats_data)
		(*env)->ReleaseFloatArrayElements(env, out_floats, floats_data, 0);
	return res;
}

JNIEXPORT jboolean JNICALL Java_android_os_Process_parseProcLine(JNIEnv *env, jclass clazz, jbyteArray buffer, jint start_index, jint end_index,
                                                                 jintArray format, jobjectArray out_strings, jlongArray out_longs, jfloatArray out_floats)
{
	jbyte *data = (*env)->GetByteArrayElements(env, buffer, NULL);
	jboolean result = parse_proc_line_array(env, (char *)data, start_index, end_index, format, out_strings, out_longs, out_floats);
	(*env)->ReleaseByteArrayElements(env, buffer, data, 0);
	return result;
}

JNIEXPORT jboolean JNICALL Java_android_os_Process_readProcFile(JNIEnv *env, jclass clazz, jstring file, jintArray format,
                                                                jobjectArray out_strings, jlongArray out_longs, jfloatArray out_floats)
{
	if (!file || !format) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return JNI_FALSE;
	}
	const char *path = (*env)->GetStringUTFChars(env, file, NULL);
	int fd = open(path, O_RDONLY | O_CLOEXEC);
	(*env)->ReleaseStringUTFChars(env, file, path);
	if (fd < 0)
		return JNI_FALSE;
	/* the whole file: proc files are small, the buffer grows until a read comes back short */
	ssize_t size = 1024;
	char *buf = malloc(size);
	ssize_t n;
	for (;;) {
		n = pread(fd, buf, size, 0);
		if (n < 0 && errno == EINTR)
			continue;
		if (n < 0) {
			close(fd);
			free(buf);
			return JNI_FALSE;
		}
		if (n < size)
			break;
		size = size * 2 < 4096 ? 4096 : size * 2;
		buf = realloc(buf, size);
	}
	close(fd);
	jboolean result = parse_proc_line_array(env, buf, 0, (jint)n, format, out_strings, out_longs, out_floats);
	free(buf);
	return result;
}

/* AOSP getPidsForCommands: the processes whose command line's first word is one of CMDS */
JNIEXPORT jintArray JNICALL Java_android_os_Process_getPidsForCommands(JNIEnv *env, jclass clazz, jobjectArray command_names)
{
	if (!command_names) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return NULL;
	}
	jsize count = (*env)->GetArrayLength(env, command_names);
	char **commands = calloc(count ? count : 1, sizeof(char *));
	for (jsize i = 0; i < count; i++) {
		jstring obj = (*env)->GetObjectArrayElement(env, command_names, i);
		if (!obj) {
			for (jsize k = 0; k < i; k++)
				free(commands[k]);
			free(commands);
			(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), "Element in commandNames");
			return NULL;
		}
		const char *s = (*env)->GetStringUTFChars(env, obj, NULL);
		commands[i] = strdup(s);
		(*env)->ReleaseStringUTFChars(env, obj, s);
		(*env)->DeleteLocalRef(env, obj);
	}
	jint *pids = NULL;
	jsize n = 0, cap = 0;
	DIR *proc = opendir("/proc");
	if (proc) {
		struct dirent *d;
		while ((d = readdir(proc))) {
			int pid = atoi(d->d_name);
			if (pid <= 0)
				continue;
			char path[64], data[PATH_MAX];
			snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
			int fd = open(path, O_RDONLY | O_CLOEXEC);
			if (fd < 0)
				continue;
			ssize_t len = read(fd, data, sizeof(data) - 1);
			close(fd);
			if (len < 0)
				continue;
			data[len] = 0;
			for (ssize_t i = 0; i < len; i++) {
				if (data[i] == ' ') {
					data[i] = 0;
					break;
				}
			}
			for (jsize i = 0; i < count; i++) {
				if (strcmp(commands[i], data) == 0) {
					if (n == cap) {
						cap = cap ? cap * 2 : 16;
						pids = realloc(pids, cap * sizeof(jint));
					}
					pids[n++] = pid;
					break;
				}
			}
		}
		closedir(proc);
	}
	for (jsize i = 0; i < count; i++)
		free(commands[i]);
	free(commands);
	if (!proc)
		return NULL;
	jintArray out = (*env)->NewIntArray(env, n);
	if (out && n > 0)
		(*env)->SetIntArrayRegion(env, out, 0, n, pids);
	free(pids);
	return out;
}

/* AOSP getPss: ProcMemInfo::SmapsOrRollupPss, in bytes; -1 when it cannot be read */
JNIEXPORT jlong JNICALL Java_android_os_Process_getPss(JNIEnv *env, jclass clazz, jint pid)
{
	char path[64], line[256];
	snprintf(path, sizeof(path), "/proc/%d/smaps_rollup", pid);
	FILE *f = fopen(path, "re");
	int rollup = f != NULL;
	if (!f) {
		snprintf(path, sizeof(path), "/proc/%d/smaps", pid);
		f = fopen(path, "re");
	}
	if (!f)
		return -1;
	unsigned long long pss = 0, v;
	int found = 0;
	while (fgets(line, sizeof(line), f)) {
		if (sscanf(line, "Pss: %llu kB", &v) == 1) {
			pss += v;
			found = 1;
			if (rollup)
				break;
		}
	}
	fclose(f);
	return found ? (jlong)pss * 1024 : -1;
}

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
