/*
 * The natives AOSP's generated bindings leave as "not yet implemented" (they throw
 * UnsupportedOperationException on Android), implemented on the host's GL:
 * - KHR_debug's message callback (GLES32.glDebugMessageCallback, GLES31Ext.glDebugMessageCallbackKHR):
 *   the Java DebugProc/DebugProcKHR is called with each message the context emits, on the thread
 *   the GL emits it on (attached to the VM if it is not a Java thread), and
 *   glGetDebugMessageCallbackKHR gives it back;
 * - the message log, in all four forms of each class (int[] / IntBuffer outputs with a byte[] /
 *   ByteBuffer log, and the String[] forms that size the log themselves);
 * - glGetObjectLabel, glObjectPtrLabel and glGetObjectPtrLabel (KHR and core),
 *   GLES32.glGetPointerv, GLES31.glGetProgramResourceName.
 * Argument checks follow the generated bindings' conventions (IllegalArgumentException with
 * "x < 0" / "length - xOffset < n < needed"); a null output array or buffer is passed to GL as
 * NULL, which KHR_debug allows for every output of glGetDebugMessageLog.
 * gles_register.c registers these over AOSP's entries, after AOSP's tables.
 *
 * The deprecated GLES30.glGetTransformFeedbackVarying(..., byte name) overload stays as AOSP
 * has it (UnsupportedOperationException "deprecated"): a byte passed by value cannot receive the
 * name, so Android reports the overload as unusable and points to the String and ByteBuffer forms.
 */
#define GL_GLEXT_PROTOTYPES 1
#include <GLES3/gl32.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "compat/nativehelper/JNIPlatformHelp.h"

#define IAE "java/lang/IllegalArgumentException"

/* ---- strings ---- */

/* a String from len bytes of GL text (len < 0: up to the terminator), as the generated bindings
 * make strings (NewStringUTF) */
static jstring gl_string(JNIEnv *env, const char *s, GLsizei len)
{
	if (!s)
		return (*env)->NewStringUTF(env, "");
	if (len < 0)
		return (*env)->NewStringUTF(env, s);
	char *copy = malloc((size_t)len + 1);
	if (!copy) {
		jniThrowException(env, "java/lang/OutOfMemoryError", "out of memory");
		return NULL;
	}
	memcpy(copy, s, (size_t)len);
	copy[len] = '\0';
	jstring ret = (*env)->NewStringUTF(env, copy);
	free(copy);
	return ret;
}

/* ---- the debug message callback ---- */

#define DEBUG_PROC_MAGIC 0x44424750u /* "DBGP" */

struct debug_proc {
	uint32_t magic;
	jobject callback; /* global reference to the DebugProc / DebugProcKHR */
	jmethodID on_message;
};

static JavaVM *java_vm;

static void GL_APIENTRY debug_trampoline(GLenum source, GLenum type, GLuint id, GLenum severity, GLsizei length,
                                         const GLchar *message, const void *user)
{
	const struct debug_proc *p = user;
	if (!p || p->magic != DEBUG_PROC_MAGIC || !java_vm)
		return;
	JNIEnv *env;
	int attached = 0;
	if ((*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
		/* a GL thread of the driver's own (glthread): attach it for the call */
		if ((*java_vm)->AttachCurrentThreadAsDaemon(java_vm, (void **)&env, NULL) != JNI_OK)
			return;
		attached = 1;
	}
	/* a message emitted while an exception is pending (a GL call in a binding that is already
	 * throwing): the callback still runs, and that first exception is the one that propagates */
	jthrowable pending = (*env)->ExceptionOccurred(env);
	if (pending)
		(*env)->ExceptionClear(env);
	jstring msg = gl_string(env, message, length);
	if (msg) {
		(*env)->CallVoidMethod(env, p->callback, p->on_message, (jint)source, (jint)type, (jint)id, (jint)severity, msg);
		(*env)->DeleteLocalRef(env, msg);
	}
	if ((*env)->ExceptionCheck(env) && (pending || attached)) {
		/* the callback threw where it cannot propagate (nobody to receive it on a driver
		 * thread, or a first exception already on its way): report it */
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	/* otherwise the callback's exception stays pending and the GL method that emitted the
	 * message throws it to its caller */
	if (pending) {
		(*env)->Throw(env, pending);
		(*env)->DeleteLocalRef(env, pending);
	}
	if (attached)
		(*java_vm)->DetachCurrentThread(java_vm);
}

/* the callback of the current context, if it is one of ours */
static struct debug_proc *current_debug_proc(void)
{
	void *fn = NULL, *user = NULL;
	glGetPointerv(GL_DEBUG_CALLBACK_FUNCTION, &fn);
	glGetPointerv(GL_DEBUG_CALLBACK_USER_PARAM, &user);
	if (fn != (void *)debug_trampoline || !user)
		return NULL;
	struct debug_proc *p = user;
	return p->magic == DEBUG_PROC_MAGIC ? p : NULL;
}

static void set_debug_callback(JNIEnv *env, jobject callback)
{
	if (!java_vm)
		(*env)->GetJavaVM(env, &java_vm);
	struct debug_proc *old = current_debug_proc();
	struct debug_proc *p = NULL;
	if (callback) {
		jclass cls = (*env)->GetObjectClass(env, callback);
		jmethodID on_message = (*env)->GetMethodID(env, cls, "onMessage", "(IIIILjava/lang/String;)V");
		(*env)->DeleteLocalRef(env, cls);
		if (!on_message)
			return; /* NoSuchMethodError pending */
		p = malloc(sizeof(*p));
		if (!p) {
			jniThrowException(env, "java/lang/OutOfMemoryError", "out of memory");
			return;
		}
		p->magic = DEBUG_PROC_MAGIC;
		p->callback = (*env)->NewGlobalRef(env, callback);
		p->on_message = on_message;
	}
	/* with no callback, messages go to the context's message log again */
	glDebugMessageCallback(p ? debug_trampoline : NULL, p);
	if (old) {
		old->magic = 0;
		(*env)->DeleteGlobalRef(env, old->callback);
		free(old);
	}
}

static void android_glDebugMessageCallback(JNIEnv *env, jclass clazz, jobject callback)
{
	set_debug_callback(env, callback);
}

static jobject android_glGetDebugMessageCallbackKHR(JNIEnv *env, jclass clazz)
{
	struct debug_proc *p = current_debug_proc();
	if (!p)
		return NULL;
	/* a GLES32.DebugProc that is not also a DebugProcKHR has no DebugProcKHR to return */
	jclass khr = (*env)->FindClass(env, "android/opengl/GLES31Ext$DebugProcKHR");
	if (!khr)
		return NULL;
	jboolean is = (*env)->IsInstanceOf(env, p->callback, khr);
	(*env)->DeleteLocalRef(env, khr);
	return is ? (*env)->NewLocalRef(env, p->callback) : NULL;
}

/* ---- the message log ---- */

struct int_out {
	jintArray array;
	jint *elements;
	GLuint *ptr;
};

/* an int[] output with its offset, room for needed ints (NULL array: no output) */
static int int_array_out(JNIEnv *env, jintArray array, jint offset, jint needed, const char *name, struct int_out *out)
{
	char msg[96];
	out->array = array;
	out->elements = NULL;
	out->ptr = NULL;
	if (!array)
		return 0;
	if (offset < 0) {
		snprintf(msg, sizeof(msg), "%sOffset < 0", name);
		jniThrowException(env, IAE, msg);
		return -1;
	}
	if ((*env)->GetArrayLength(env, array) - offset < needed) {
		snprintf(msg, sizeof(msg), "length - %sOffset < count < needed", name);
		jniThrowException(env, IAE, msg);
		return -1;
	}
	out->elements = (*env)->GetIntArrayElements(env, array, NULL);
	out->ptr = (GLuint *)(out->elements + offset);
	return 0;
}

/* a java.nio buffer output; NULL buffer: no output. A direct buffer is written in place; an
 * array-backed one through a temporary, copied into its array by buffer_release (a short
 * critical section, so no Java runs while the array is pinned) */
struct buf_out {
	jobject buffer;
	jarray array;
	jint array_offset; /* bytes */
	void *tmp;
	void *ptr;
	jint remaining; /* bytes */
};

static int buffer_out(JNIEnv *env, jobject buffer, struct buf_out *out)
{
	memset(out, 0, sizeof(*out));
	if (!buffer)
		return 0;
	jint position, limit, shift;
	jlong address = jniGetNioBufferFields(env, buffer, &position, &limit, &shift);
	out->remaining = (limit - position) << shift;
	if (address) {
		out->ptr = (void *)(uintptr_t)(address + ((jlong)position << shift));
		return 0;
	}
	out->array = jniGetNioBufferBaseArray(env, buffer);
	if (!out->array) {
		jniThrowException(env, IAE, "Must use a native order direct Buffer");
		return -1;
	}
	out->buffer = buffer;
	out->array_offset = jniGetNioBufferBaseArrayOffset(env, buffer);
	out->tmp = calloc(out->remaining > 0 ? (size_t)out->remaining : 1, 1);
	if (!out->tmp) {
		jniThrowException(env, "java/lang/OutOfMemoryError", "out of memory");
		return -1;
	}
	out->ptr = out->tmp;
	return 0;
}

/* written: whether GL wrote the output (copy it back) */
static void buffer_release(JNIEnv *env, struct buf_out *out, int written)
{
	if (!out->tmp)
		return;
	if (written && out->remaining > 0) {
		void *elements = (*env)->GetPrimitiveArrayCritical(env, out->array, NULL);
		if (elements) {
			memcpy((char *)elements + out->array_offset, out->tmp, (size_t)out->remaining);
			(*env)->ReleasePrimitiveArrayCritical(env, out->array, elements, 0);
		}
	}
	free(out->tmp);
	out->tmp = NULL;
}

static void int_release(JNIEnv *env, struct int_out *out)
{
	if (out->elements)
		(*env)->ReleaseIntArrayElements(env, out->array, out->elements, 0);
}

/* an IntBuffer output with room for count ints */
static int int_buffer_out(JNIEnv *env, jobject buffer, jint count, struct buf_out *out)
{
	if (buffer_out(env, buffer, out))
		return -1;
	if (buffer && out->remaining / 4 < count) {
		buffer_release(env, out, 0);
		jniThrowException(env, IAE, "remaining() < count < needed");
		return -1;
	}
	return 0;
}

static jint android_glGetDebugMessageLog__II_3II_3II_3II_3II_3II_3BI(JNIEnv *env, jclass clazz, jint count, jint bufSize,
                                                                    jintArray sources, jint sourcesOffset, jintArray types, jint typesOffset,
                                                                    jintArray ids, jint idsOffset, jintArray severities, jint severitiesOffset,
                                                                    jintArray lengths, jint lengthsOffset, jbyteArray messageLog, jint messageLogOffset)
{
	struct int_out o[5] = {0};
	jbyte *log = NULL;
	jint ret = 0;
	if (count < 0) {
		jniThrowException(env, IAE, "count < 0");
		return 0;
	}
	if (int_array_out(env, sources, sourcesOffset, count, "sources", &o[0]) ||
	    int_array_out(env, types, typesOffset, count, "types", &o[1]) ||
	    int_array_out(env, ids, idsOffset, count, "ids", &o[2]) ||
	    int_array_out(env, severities, severitiesOffset, count, "severities", &o[3]) ||
	    int_array_out(env, lengths, lengthsOffset, count, "lengths", &o[4]))
		goto out;
	if (messageLog) {
		if (messageLogOffset < 0) {
			jniThrowException(env, IAE, "messageLogOffset < 0");
			goto out;
		}
		if (bufSize < 0 || (*env)->GetArrayLength(env, messageLog) - messageLogOffset < bufSize) {
			jniThrowException(env, IAE, "length - messageLogOffset < bufSize < needed");
			goto out;
		}
		log = (*env)->GetByteArrayElements(env, messageLog, NULL);
	}
	ret = (jint)glGetDebugMessageLog((GLuint)count, log ? bufSize : 0, (GLenum *)o[0].ptr, (GLenum *)o[1].ptr, o[2].ptr,
	                                 (GLenum *)o[3].ptr, (GLsizei *)o[4].ptr, log ? (GLchar *)(log + messageLogOffset) : NULL);
out:
	if (log)
		(*env)->ReleaseByteArrayElements(env, messageLog, log, 0);
	for (int i = 0; i < 5; i++)
		int_release(env, &o[i]);
	return ret;
}

static jint android_glGetDebugMessageLog__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_ByteBuffer_2(
    JNIEnv *env, jclass clazz, jint count, jobject sources, jobject types, jobject ids, jobject severities, jobject lengths, jobject messageLog)
{
	struct buf_out o[6] = {0};
	jint ret = 0;
	int called = 0;
	if (count < 0) {
		jniThrowException(env, IAE, "count < 0");
		return 0;
	}
	jobject bufs[5] = {sources, types, ids, severities, lengths};
	for (int i = 0; i < 5; i++)
		if (int_buffer_out(env, bufs[i], count, &o[i]))
			goto out;
	/* the log's size is what remains of the ByteBuffer */
	if (buffer_out(env, messageLog, &o[5]))
		goto out;
	ret = (jint)glGetDebugMessageLog((GLuint)count, o[5].ptr ? o[5].remaining : 0, o[0].ptr, o[1].ptr, o[2].ptr, o[3].ptr, o[4].ptr, o[5].ptr);
	called = 1;
out:
	for (int i = 0; i < 6; i++)
		buffer_release(env, &o[i], called);
	return ret;
}

/* the String[] forms: count messages (fewer if the log has fewer), each as a String */
static jobjectArray message_strings(JNIEnv *env, jint count, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities)
{
	GLint max_len = 0, logged = 0;
	glGetIntegerv(GL_MAX_DEBUG_MESSAGE_LENGTH, &max_len);
	glGetIntegerv(GL_DEBUG_LOGGED_MESSAGES, &logged);
	if (count > logged)
		count = logged;
	if (max_len < 1)
		max_len = 1;
	jclass string_class = (*env)->FindClass(env, "java/lang/String");
	if (!string_class)
		return NULL;
	GLsizei *lengths = calloc(count > 0 ? (size_t)count : 1, sizeof(GLsizei));
	size_t log_size = (size_t)max_len * (count > 0 ? (size_t)count : 1);
	GLchar *log = malloc(log_size);
	jobjectArray ret = NULL;
	if (!lengths || !log) {
		jniThrowException(env, "java/lang/OutOfMemoryError", "out of memory");
		goto out;
	}
	GLuint n = glGetDebugMessageLog((GLuint)count, (GLsizei)log_size, sources, types, ids, severities, lengths, log);
	ret = (*env)->NewObjectArray(env, (jsize)n, string_class, NULL);
	if (!ret)
		goto out;
	const GLchar *m = log;
	for (GLuint i = 0; i < n; i++) {
		/* each length counts the message's terminator */
		jstring s = gl_string(env, m, lengths[i] > 0 ? lengths[i] - 1 : 0);
		if (!s)
			break;
		(*env)->SetObjectArrayElement(env, ret, (jsize)i, s);
		(*env)->DeleteLocalRef(env, s);
		m += lengths[i];
	}
out:
	free(lengths);
	free(log);
	(*env)->DeleteLocalRef(env, string_class);
	return ret;
}

static jobjectArray android_glGetDebugMessageLog__I_3II_3II_3II_3II(JNIEnv *env, jclass clazz, jint count, jintArray sources, jint sourcesOffset,
                                                                   jintArray types, jint typesOffset, jintArray ids, jint idsOffset,
                                                                   jintArray severities, jint severitiesOffset)
{
	struct int_out o[4] = {0};
	jobjectArray ret = NULL;
	if (count < 0) {
		jniThrowException(env, IAE, "count < 0");
		return NULL;
	}
	if (int_array_out(env, sources, sourcesOffset, count, "sources", &o[0]) ||
	    int_array_out(env, types, typesOffset, count, "types", &o[1]) ||
	    int_array_out(env, ids, idsOffset, count, "ids", &o[2]) ||
	    int_array_out(env, severities, severitiesOffset, count, "severities", &o[3]))
		goto out;
	ret = message_strings(env, count, (GLenum *)o[0].ptr, (GLenum *)o[1].ptr, o[2].ptr, (GLenum *)o[3].ptr);
out:
	for (int i = 0; i < 4; i++)
		int_release(env, &o[i]);
	return ret;
}

static jobjectArray android_glGetDebugMessageLog__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2(
    JNIEnv *env, jclass clazz, jint count, jobject sources, jobject types, jobject ids, jobject severities)
{
	struct buf_out o[4] = {0};
	jobjectArray ret = NULL;
	if (count < 0) {
		jniThrowException(env, IAE, "count < 0");
		return NULL;
	}
	jobject bufs[4] = {sources, types, ids, severities};
	for (int i = 0; i < 4; i++)
		if (int_buffer_out(env, bufs[i], count, &o[i]))
			goto out;
	ret = message_strings(env, count, o[0].ptr, o[1].ptr, o[2].ptr, o[3].ptr);
out:
	for (int i = 0; i < 4; i++)
		buffer_release(env, &o[i], ret != NULL);
	return ret;
}

/* ---- labels ---- */

/* gl_passes.c (libandroid): the sync objects GLES30.glFenceSync hands out are its own, so are
 * their pointer labels; any other pointer goes on to GL */
void bionic_glObjectPtrLabel(const void *ptr, GLsizei length, const GLchar *label);
void bionic_glGetObjectPtrLabel(const void *ptr, GLsizei bufSize, GLsizei *length, GLchar *label);

static jstring android_glGetObjectLabel(JNIEnv *env, jclass clazz, jint identifier, jint name)
{
	GLsizei len = 0;
	/* KHR_debug: with no label buffer, the length of the label is returned */
	glGetObjectLabel((GLenum)identifier, (GLuint)name, 0, &len, NULL);
	GLchar *label = calloc((size_t)len + 1, 1);
	if (!label) {
		jniThrowException(env, "java/lang/OutOfMemoryError", "out of memory");
		return NULL;
	}
	GLsizei got = 0;
	glGetObjectLabel((GLenum)identifier, (GLuint)name, len + 1, &got, label);
	jstring ret = gl_string(env, label, got);
	free(label);
	return ret;
}

static void android_glObjectPtrLabel(JNIEnv *env, jclass clazz, jlong ptr, jstring label)
{
	/* a null label removes the object's label */
	const char *chars = label ? (*env)->GetStringUTFChars(env, label, NULL) : NULL;
	if (label && !chars)
		return;
	bionic_glObjectPtrLabel((const void *)(uintptr_t)ptr, chars ? -1 : 0, chars);
	if (chars)
		(*env)->ReleaseStringUTFChars(env, label, chars);
}

static jstring android_glGetObjectPtrLabel(JNIEnv *env, jclass clazz, jlong ptr)
{
	GLsizei len = 0;
	bionic_glGetObjectPtrLabel((const void *)(uintptr_t)ptr, 0, &len, NULL);
	GLchar *label = calloc((size_t)len + 1, 1);
	if (!label) {
		jniThrowException(env, "java/lang/OutOfMemoryError", "out of memory");
		return NULL;
	}
	GLsizei got = 0;
	bionic_glGetObjectPtrLabel((const void *)(uintptr_t)ptr, len + 1, &got, label);
	jstring ret = gl_string(env, label, got);
	free(label);
	return ret;
}

/* ---- the rest ---- */

static jlong android_glGetPointerv(JNIEnv *env, jclass clazz, jint pname)
{
	void *p = NULL;
	glGetPointerv((GLenum)pname, &p);
	return (jlong)(uintptr_t)p;
}

static jstring android_glGetProgramResourceName(JNIEnv *env, jclass clazz, jint program, jint programInterface, jint index)
{
	/* GL_MAX_NAME_LENGTH counts the terminator */
	GLint max_len = 0;
	glGetProgramInterfaceiv((GLuint)program, (GLenum)programInterface, GL_MAX_NAME_LENGTH, &max_len);
	if (max_len < 1)
		max_len = 1;
	GLchar *name = calloc((size_t)max_len, 1);
	if (!name) {
		jniThrowException(env, "java/lang/OutOfMemoryError", "out of memory");
		return NULL;
	}
	GLsizei len = 0;
	glGetProgramResourceName((GLuint)program, (GLenum)programInterface, (GLuint)index, max_len, &len, name);
	jstring ret = gl_string(env, name, len);
	free(name);
	return ret;
}

/* ---- registration ---- */

#define LOG_SIG_ARRAYS "(II[II[II[II[II[II[BI)I"
#define LOG_SIG_BUFFERS "(ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/ByteBuffer;)I"
#define LOG_SIG_STRINGS_ARRAYS "(I[II[II[II[II)[Ljava/lang/String;"
#define LOG_SIG_STRINGS_BUFFERS "(ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;)[Ljava/lang/String;"

static const JNINativeMethod gles31_methods[] = {
	{"glGetProgramResourceName", "(III)Ljava/lang/String;", (void *)android_glGetProgramResourceName},
};

static const JNINativeMethod gles31ext_methods[] = {
	{"glDebugMessageCallbackKHR", "(Landroid/opengl/GLES31Ext$DebugProcKHR;)V", (void *)android_glDebugMessageCallback},
	{"glGetDebugMessageLogKHR", LOG_SIG_ARRAYS, (void *)android_glGetDebugMessageLog__II_3II_3II_3II_3II_3II_3BI},
	{"glGetDebugMessageLogKHR", LOG_SIG_BUFFERS, (void *)android_glGetDebugMessageLog__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_ByteBuffer_2},
	{"glGetDebugMessageLogKHR", LOG_SIG_STRINGS_ARRAYS, (void *)android_glGetDebugMessageLog__I_3II_3II_3II_3II},
	{"glGetDebugMessageLogKHR", LOG_SIG_STRINGS_BUFFERS, (void *)android_glGetDebugMessageLog__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2},
	{"glGetObjectLabelKHR", "(II)Ljava/lang/String;", (void *)android_glGetObjectLabel},
	{"glObjectPtrLabelKHR", "(JLjava/lang/String;)V", (void *)android_glObjectPtrLabel},
	{"glGetObjectPtrLabelKHR", "(J)Ljava/lang/String;", (void *)android_glGetObjectPtrLabel},
	{"glGetDebugMessageCallbackKHR", "()Landroid/opengl/GLES31Ext$DebugProcKHR;", (void *)android_glGetDebugMessageCallbackKHR},
};

static const JNINativeMethod gles32_methods[] = {
	{"glDebugMessageCallback", "(Landroid/opengl/GLES32$DebugProc;)V", (void *)android_glDebugMessageCallback},
	{"glGetDebugMessageLog", LOG_SIG_ARRAYS, (void *)android_glGetDebugMessageLog__II_3II_3II_3II_3II_3II_3BI},
	{"glGetDebugMessageLog", LOG_SIG_BUFFERS, (void *)android_glGetDebugMessageLog__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_ByteBuffer_2},
	{"glGetDebugMessageLog", LOG_SIG_STRINGS_ARRAYS, (void *)android_glGetDebugMessageLog__I_3II_3II_3II_3II},
	{"glGetDebugMessageLog", LOG_SIG_STRINGS_BUFFERS, (void *)android_glGetDebugMessageLog__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2},
	{"glGetObjectLabel", "(II)Ljava/lang/String;", (void *)android_glGetObjectLabel},
	{"glObjectPtrLabel", "(JLjava/lang/String;)V", (void *)android_glObjectPtrLabel},
	{"glGetObjectPtrLabel", "(J)Ljava/lang/String;", (void *)android_glGetObjectPtrLabel},
	{"glGetPointerv", "(I)J", (void *)android_glGetPointerv},
};

static int register_methods(JNIEnv *env, jclass clazz, const JNINativeMethod *methods, int n)
{
	if (!java_vm)
		(*env)->GetJavaVM(env, &java_vm);
	return (*env)->RegisterNatives(env, clazz, methods, n);
}

int atl_gles_register_GLES31(JNIEnv *env, jclass clazz)
{
	return register_methods(env, clazz, gles31_methods, sizeof(gles31_methods) / sizeof(gles31_methods[0]));
}

int atl_gles_register_GLES31Ext(JNIEnv *env, jclass clazz)
{
	return register_methods(env, clazz, gles31ext_methods, sizeof(gles31ext_methods) / sizeof(gles31ext_methods[0]));
}

int atl_gles_register_GLES32(JNIEnv *env, jclass clazz)
{
	return register_methods(env, clazz, gles32_methods, sizeof(gles32_methods) / sizeof(gles32_methods[0]));
}
