/*
 * Support for AOSP's generated GL bindings (gles_atl.h):
 * - libnativehelper's jniThrowException and NIO buffer helpers, after AOSP's
 *   libnativehelper (JNIHelp.c, JNIPlatformHelp.c, Apache License 2.0): ART standalone's
 *   libnativehelper predates the NIO helpers;
 * - the *Bounds entry points of AOSP's GLES_CM wrapper (frameworks/native/opengl/libs/GLES_CM/gl.cpp),
 *   which the bindings call to pass a buffer's remaining size along: AOSP's wrapper hands the call
 *   to the driver without the count, and so does this.
 */
#define GL_GLEXT_PROTOTYPES 1
#include <GLES/gl.h>
#include <GLES/glext.h>

#include "compat/nativehelper/JNIPlatformHelp.h"

/* ---- libnativehelper ---- */

int jniThrowException(JNIEnv *env, const char *className, const char *msg)
{
	jclass exceptionClass = (*env)->FindClass(env, className);
	if (!exceptionClass)
		return -1; /* FindClass threw (NoClassDefFoundError) */
	int ret = (*env)->ThrowNew(env, exceptionClass, msg) == JNI_OK ? 0 : -1;
	(*env)->DeleteLocalRef(env, exceptionClass);
	return ret;
}

static jfieldID buffer_address, buffer_position, buffer_limit, buffer_element_size_shift;
static jclass nio_access;
static jmethodID nio_access_get_base_array, nio_access_get_base_array_offset;

static void nio_ids(JNIEnv *env)
{
	if (nio_access)
		return;
	jclass buffer = (*env)->FindClass(env, "java/nio/Buffer");
	buffer_address = (*env)->GetFieldID(env, buffer, "address", "J");
	buffer_position = (*env)->GetFieldID(env, buffer, "position", "I");
	buffer_limit = (*env)->GetFieldID(env, buffer, "limit", "I");
	buffer_element_size_shift = (*env)->GetFieldID(env, buffer, "_elementSizeShift", "I");
	(*env)->DeleteLocalRef(env, buffer);
	jclass access = (*env)->FindClass(env, "java/nio/NIOAccess");
	nio_access_get_base_array = (*env)->GetStaticMethodID(env, access, "getBaseArray", "(Ljava/nio/Buffer;)Ljava/lang/Object;");
	nio_access_get_base_array_offset = (*env)->GetStaticMethodID(env, access, "getBaseArrayOffset", "(Ljava/nio/Buffer;)I");
	nio_access = (*env)->NewGlobalRef(env, access);
	(*env)->DeleteLocalRef(env, access);
}

jlong jniGetNioBufferFields(JNIEnv *env, jobject nioBuffer, jint *position, jint *limit, jint *elementSizeShift)
{
	nio_ids(env);
	*position = (*env)->GetIntField(env, nioBuffer, buffer_position);
	*limit = (*env)->GetIntField(env, nioBuffer, buffer_limit);
	*elementSizeShift = (*env)->GetIntField(env, nioBuffer, buffer_element_size_shift);
	return (*env)->GetLongField(env, nioBuffer, buffer_address);
}

jlong jniGetNioBufferPointer(JNIEnv *env, jobject nioBuffer)
{
	nio_ids(env);
	jlong address = (*env)->GetLongField(env, nioBuffer, buffer_address);
	if (address != 0) {
		jint position = (*env)->GetIntField(env, nioBuffer, buffer_position);
		jint shift = (*env)->GetIntField(env, nioBuffer, buffer_element_size_shift);
		address += (jlong)position << shift;
	}
	return address;
}

jarray jniGetNioBufferBaseArray(JNIEnv *env, jobject nioBuffer)
{
	nio_ids(env);
	return (jarray)(*env)->CallStaticObjectMethod(env, nio_access, nio_access_get_base_array, nioBuffer);
}

jint jniGetNioBufferBaseArrayOffset(JNIEnv *env, jobject nioBuffer)
{
	nio_ids(env);
	return (*env)->CallStaticIntMethod(env, nio_access, nio_access_get_base_array_offset, nioBuffer);
}

/* ---- AOSP's GLES_CM wrapper ---- */

ATL_HIDDEN void glColorPointerBounds(GLint size, GLenum type, GLsizei stride, const GLvoid *ptr, GLsizei count)
{
	glColorPointer(size, type, stride, ptr);
}

ATL_HIDDEN void glNormalPointerBounds(GLenum type, GLsizei stride, const GLvoid *pointer, GLsizei count)
{
	glNormalPointer(type, stride, pointer);
}

ATL_HIDDEN void glTexCoordPointerBounds(GLint size, GLenum type, GLsizei stride, const GLvoid *pointer, GLsizei count)
{
	glTexCoordPointer(size, type, stride, pointer);
}

ATL_HIDDEN void glVertexPointerBounds(GLint size, GLenum type, GLsizei stride, const GLvoid *pointer, GLsizei count)
{
	glVertexPointer(size, type, stride, pointer);
}

ATL_HIDDEN void glPointSizePointerOESBounds(GLenum type, GLsizei stride, const GLvoid *pointer, GLsizei count)
{
	glPointSizePointerOES(type, stride, pointer);
}

ATL_HIDDEN void glMatrixIndexPointerOESBounds(GLint size, GLenum type, GLsizei stride, const GLvoid *pointer, GLsizei count)
{
	glMatrixIndexPointerOES(size, type, stride, pointer);
}

ATL_HIDDEN void glWeightPointerOESBounds(GLint size, GLenum type, GLsizei stride, const GLvoid *pointer, GLsizei count)
{
	glWeightPointerOES(size, type, stride, pointer);
}
