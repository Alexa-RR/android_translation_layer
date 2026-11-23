/*
 * The GL binding classes' natives are registered as libandroid_runtime registers them at startup
 * (register_android_opengl_jni_GLES20 etc., AOSP's generated method tables): here each class does
 * it from its static initializer's _nativeClassInit(), the one native of each class found by
 * name. The methods AOSP's tables leave unimplemented are registered over them from gles_debug.c.
 * Once registered, _nativeClassInit is AOSP's own (the table has it), which is then run.
 */
#include <jni.h>

#include "gles_atl.h"

static void register_and_init(JNIEnv *env, jclass clazz, int (*reg)(JNIEnv *), int (*extra)(JNIEnv *, jclass))
{
	reg(env);
	if (extra)
		extra(env, clazz);
	jmethodID init = (*env)->GetStaticMethodID(env, clazz, "_nativeClassInit", "()V");
	if (init)
		(*env)->CallStaticVoidMethod(env, clazz, init);
}

#define TL_GL_CLASS(jni_name, reg, extra)                                                  \
	JNIEXPORT void JNICALL Java_##jni_name##__1nativeClassInit(JNIEnv *env, jclass clazz) \
	{                                                                                     \
		register_and_init(env, clazz, reg, extra);                                        \
	}

TL_GL_CLASS(android_opengl_GLES10, register_android_opengl_jni_GLES10, NULL)
TL_GL_CLASS(android_opengl_GLES10Ext, register_android_opengl_jni_GLES10Ext, NULL)
TL_GL_CLASS(android_opengl_GLES11, register_android_opengl_jni_GLES11, NULL)
TL_GL_CLASS(android_opengl_GLES11Ext, register_android_opengl_jni_GLES11Ext, NULL)
TL_GL_CLASS(android_opengl_GLES20, register_android_opengl_jni_GLES20, NULL)
TL_GL_CLASS(android_opengl_GLES30, register_android_opengl_jni_GLES30, NULL)
TL_GL_CLASS(android_opengl_GLES31, register_android_opengl_jni_GLES31, atl_gles_register_GLES31)
TL_GL_CLASS(android_opengl_GLES31Ext, register_android_opengl_jni_GLES31Ext, atl_gles_register_GLES31Ext)
TL_GL_CLASS(android_opengl_GLES32, register_android_opengl_jni_GLES32, atl_gles_register_GLES32)
TL_GL_CLASS(com_google_android_gles_1jni_GLImpl, register_com_google_android_gles_jni_GLImpl, NULL)
