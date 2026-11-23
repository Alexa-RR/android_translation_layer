/*
 * AndroidRuntime::registerNativeMethods for AOSP's generated GL bindings: ATL has no
 * AndroidRuntime; each binding class registers its natives from its own _nativeClassInit
 * (gles_register.c), as libandroid_runtime does at startup.
 */
#ifndef ATL_ANDROID_RUNTIME_ANDROIDRUNTIME_H
#define ATL_ANDROID_RUNTIME_ANDROIDRUNTIME_H

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>

namespace android {

class AndroidRuntime {
public:
	/* as AndroidRuntime::registerNativeMethods (jniRegisterNativeMethods): a missing class or
	 * method is fatal, because the bindings and their Java classes are built together */
	static int registerNativeMethods(JNIEnv *env, const char *className, const JNINativeMethod *gMethods, int numMethods)
	{
		jclass clazz = env->FindClass(className);
		if (!clazz || env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
			if (env->ExceptionCheck())
				env->ExceptionDescribe();
			fprintf(stderr, "RegisterNatives failed for '%s'; aborting...\n", className);
			abort();
		}
		env->DeleteLocalRef(clazz);
		return 0;
	}
};

}

#endif
