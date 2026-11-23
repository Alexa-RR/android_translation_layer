/* teslalinux: the natives of android.graphics.SurfaceTexture (AOSP
 * frameworks/base/core/jni/android/graphics/SurfaceTexture.cpp) over libandroid's buffer queue
 * (surface_texture.c). A frame queued by the producer calls back into
 * SurfaceTexture.postEventFromNative on the producer's thread, as AOSP's JNISurfaceTextureContext
 * does; the Java side posts onFrameAvailable to the listener's Handler. */
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../defines.h"
#include "../util.h"

#include "../../libandroid/surface_texture.h"

#include "../generated_headers/android_graphics_SurfaceTexture.h"

extern JavaVM *jvm;

struct tl_st_listener {
	jclass cls;
	jmethodID post_event;
	jobject weak_this; /* a global reference to the WeakReference<SurfaceTexture> */
};

static struct tl_surface_texture *st_of(jlong ptr)
{
	return (struct tl_surface_texture *)(intptr_t)ptr;
}

/* AOSP SurfaceTexture_updateTexImage/releaseTexImage: INVALID_OPERATION (not attached to the
 * current context) is an IllegalStateException, any other error (an abandoned queue) a
 * RuntimeException */
static void throw_for(JNIEnv *env, int err, const char *invalid_operation_message)
{
	fprintf(stderr, "SurfaceTexture: %s\n", err == -ENODEV ? "abandoned" : "not attached to the current GL context");
	if (err == -EINVAL)
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), invalid_operation_message);
	else
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), "Error during updateTexImage (see logcat for details)");
}

/* the producer's thread may be a native one: attach it for the call (AOSP JNISurfaceTextureContext::getJNIEnv) */
static void on_frame_available(void *data)
{
	struct tl_st_listener *l = data;
	JNIEnv *env = NULL;
	gboolean attached = FALSE;
	if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
		if ((*jvm)->AttachCurrentThreadAsDaemon(jvm, (void **)&env, NULL) != JNI_OK)
			return;
		attached = TRUE;
	}
	(*env)->CallStaticVoidMethod(env, l->cls, l->post_event, l->weak_this);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	if (attached)
		(*jvm)->DetachCurrentThread(jvm);
}

JNIEXPORT jlong JNICALL Java_android_graphics_SurfaceTexture_nativeInit(JNIEnv *env, jclass cls, jboolean is_detached, jint tex_name, jobject weak_this)
{
	struct tl_surface_texture *st = tl_surface_texture_new((unsigned int)tex_name, !is_detached);
	struct tl_st_listener *l = calloc(1, sizeof(*l));
	l->cls = (*env)->NewGlobalRef(env, cls);
	l->post_event = (*env)->GetStaticMethodID(env, cls, "postEventFromNative", "(Ljava/lang/ref/WeakReference;)V");
	l->weak_this = (*env)->NewGlobalRef(env, weak_this);
	tl_surface_texture_set_frame_listener(st, on_frame_available, l);
	return _INTPTR(st);
}

JNIEXPORT void JNICALL Java_android_graphics_SurfaceTexture_nativeSetDefaultBufferSize(JNIEnv *env, jclass cls, jlong ptr, jint width, jint height)
{
	tl_surface_texture_set_default_buffer_size(st_of(ptr), width, height);
}

JNIEXPORT void JNICALL Java_android_graphics_SurfaceTexture_nativeUpdateTexImage(JNIEnv *env, jclass cls, jlong ptr)
{
	int err = tl_surface_texture_update(st_of(ptr));
	if (err)
		throw_for(env, err, "Unable to update texture contents (see logcat for details)");
}

JNIEXPORT void JNICALL Java_android_graphics_SurfaceTexture_nativeReleaseTexImage(JNIEnv *env, jclass cls, jlong ptr)
{
	int err = tl_surface_texture_release_tex_image(st_of(ptr));
	if (err)
		throw_for(env, err, "Unable to release texture contents (see logcat for details)");
}

JNIEXPORT jint JNICALL Java_android_graphics_SurfaceTexture_nativeDetachFromGLContext(JNIEnv *env, jclass cls, jlong ptr)
{
	return tl_surface_texture_detach(st_of(ptr));
}

JNIEXPORT jint JNICALL Java_android_graphics_SurfaceTexture_nativeAttachToGLContext(JNIEnv *env, jclass cls, jlong ptr, jint tex_name)
{
	return tl_surface_texture_attach(st_of(ptr), (unsigned int)tex_name);
}

JNIEXPORT void JNICALL Java_android_graphics_SurfaceTexture_nativeGetTransformMatrix(JNIEnv *env, jclass cls, jlong ptr, jfloatArray mtx)
{
	float m[16];
	tl_surface_texture_get_transform_matrix(st_of(ptr), m);
	(*env)->SetFloatArrayRegion(env, mtx, 0, 16, m);
}

JNIEXPORT jlong JNICALL Java_android_graphics_SurfaceTexture_nativeGetTimestamp(JNIEnv *env, jclass cls, jlong ptr)
{
	return tl_surface_texture_get_timestamp(st_of(ptr));
}

JNIEXPORT void JNICALL Java_android_graphics_SurfaceTexture_nativeRelease(JNIEnv *env, jclass cls, jlong ptr)
{
	tl_surface_texture_abandon(st_of(ptr));
}

JNIEXPORT jboolean JNICALL Java_android_graphics_SurfaceTexture_nativeIsReleased(JNIEnv *env, jclass cls, jlong ptr)
{
	return tl_surface_texture_is_abandoned(st_of(ptr));
}

/* the Surface(SurfaceTexture) of the Java side holds a reference of its own */
JNIEXPORT jlong JNICALL Java_android_graphics_SurfaceTexture_nativeRef(JNIEnv *env, jclass cls, jlong ptr)
{
	return _INTPTR(tl_surface_texture_ref(st_of(ptr)));
}

JNIEXPORT void JNICALL Java_android_graphics_SurfaceTexture_nativeUnref(JNIEnv *env, jclass cls, jlong ptr)
{
	tl_surface_texture_unref(st_of(ptr));
}

JNIEXPORT void JNICALL Java_android_graphics_SurfaceTexture_nativeFinalize(JNIEnv *env, jclass cls, jlong ptr)
{
	struct tl_surface_texture *st = st_of(ptr);
	/* the consumer goes: the listener with it, the queue when the last producer lets go */
	tl_surface_texture_abandon(st);
	struct tl_st_listener *l = tl_surface_texture_set_frame_listener(st, NULL, NULL);
	if (l) {
		(*env)->DeleteGlobalRef(env, l->weak_this);
		(*env)->DeleteGlobalRef(env, l->cls);
		free(l);
	}
	tl_surface_texture_unref(st);
}
