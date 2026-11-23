/*
 * The part of libnativehelper's JNIHelp.h / JNIPlatformHelp.h that AOSP's generated GL bindings
 * (android_opengl_GLES*.cpp, com_google_android_gles_jni_GLImpl.cpp) use. ART standalone's
 * libnativehelper predates the NIO buffer helpers, so ATL implements them (gles_atl.c) after
 * AOSP's libnativehelper/JNIPlatformHelp.c: the fields of java.nio.Buffer and java.nio.NIOAccess.
 */
#ifndef ATL_NATIVEHELPER_JNIPLATFORMHELP_H
#define ATL_NATIVEHELPER_JNIPLATFORMHELP_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ATL_HIDDEN __attribute__((visibility("hidden")))

/* Throws a new exception of className with msg (may be NULL); 0 on success. */
ATL_HIDDEN int jniThrowException(JNIEnv *env, const char *className, const char *msg);

/* A direct buffer's address (0 for a heap buffer), with its position, limit and element size shift. */
ATL_HIDDEN jlong jniGetNioBufferFields(JNIEnv *env, jobject nioBuffer, jint *position, jint *limit, jint *elementSizeShift);
/* A direct buffer's address at its position, or 0 for a heap buffer. */
ATL_HIDDEN jlong jniGetNioBufferPointer(JNIEnv *env, jobject nioBuffer);
/* The array behind a heap buffer (NIOAccess.getBaseArray), or NULL. */
ATL_HIDDEN jarray jniGetNioBufferBaseArray(JNIEnv *env, jobject nioBuffer);
/* The byte offset of a heap buffer's position in that array (NIOAccess.getBaseArrayOffset). */
ATL_HIDDEN jint jniGetNioBufferBaseArrayOffset(JNIEnv *env, jobject nioBuffer);

#ifdef __cplusplus
}
#endif

#endif
