/*
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* AOSP core/jni/android/opengl/util.cpp (the GLUtils part), on the NDK bitmap API of libandroid:
 * the bitmap's pixels in its config's layout (premultiplied for ARGB_8888) go to the GL call. */

#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <jni.h>

#include "../api-impl-jni/defines.h"
#include "generated_headers/android_opengl_GLUtils.h"
#include "../libandroid/bitmap.h"
#include "../libandroid/gl_passes.h"

#ifndef GL_PALETTE8_RGBA8_OES
#define GL_PALETTE8_RGBA8_OES 0x8B96
#endif

/* The internal format is no longer the same as pixel format, per Table 2 in
 * https://www.khronos.org/registry/OpenGL-Refpages/es3.1/html/glTexImage2D.xhtml */
static int check_internal_format(int32_t bitmap_format, int internalformat, int type)
{
	if (internalformat == GL_PALETTE8_RGBA8_OES)
		return 0;
	switch (bitmap_format) {
		case ANDROID_BITMAP_FORMAT_RGBA_8888:
			return (type == GL_UNSIGNED_BYTE && internalformat == GL_RGBA) ||
			       (type == GL_UNSIGNED_BYTE && internalformat == GL_SRGB8_ALPHA8);
		case ANDROID_BITMAP_FORMAT_A_8:
			return type == GL_UNSIGNED_BYTE && internalformat == GL_ALPHA;
		case ANDROID_BITMAP_FORMAT_RGBA_4444:
			return type == GL_UNSIGNED_SHORT_4_4_4_4 && internalformat == GL_RGBA;
		case ANDROID_BITMAP_FORMAT_RGB_565:
			return type == GL_UNSIGNED_SHORT_5_6_5 && internalformat == GL_RGB;
		case ANDROID_BITMAP_FORMAT_RGBA_F16:
			return type == GL_HALF_FLOAT && internalformat == GL_RGBA16F;
		case ANDROID_BITMAP_FORMAT_RGBA_1010102:
			return type == GL_UNSIGNED_INT_2_10_10_10_REV && internalformat == GL_RGB10_A2;
		default:
			return 0;
	}
}

static int get_pixel_format_from_internal_format(uint32_t internalformat)
{
	switch (internalformat) {
		/* sized internal formats */
		case GL_RGBA16F:
		case GL_SRGB8_ALPHA8:
			return GL_RGBA;
		/* base internal formats and pixel formats are still the same (Table 1) */
		default:
			return internalformat;
	}
}

static int get_internal_format(int32_t bitmap_format)
{
	switch (bitmap_format) {
		case ANDROID_BITMAP_FORMAT_A_8:
			return GL_ALPHA;
		case ANDROID_BITMAP_FORMAT_RGBA_4444:
			return GL_RGBA;
		case ANDROID_BITMAP_FORMAT_RGBA_8888:
			return GL_RGBA;
		case ANDROID_BITMAP_FORMAT_RGB_565:
			return GL_RGB;
		case ANDROID_BITMAP_FORMAT_RGBA_F16:
			return GL_RGBA16F;
		case ANDROID_BITMAP_FORMAT_RGBA_1010102:
			return GL_RGB10_A2;
		default:
			return -1;
	}
}

static int get_type(int32_t bitmap_format)
{
	switch (bitmap_format) {
		case ANDROID_BITMAP_FORMAT_A_8:
			return GL_UNSIGNED_BYTE;
		case ANDROID_BITMAP_FORMAT_RGBA_4444:
			return GL_UNSIGNED_SHORT_4_4_4_4;
		case ANDROID_BITMAP_FORMAT_RGBA_8888:
			return GL_UNSIGNED_BYTE;
		case ANDROID_BITMAP_FORMAT_RGB_565:
			return GL_UNSIGNED_SHORT_5_6_5;
		case ANDROID_BITMAP_FORMAT_RGBA_F16:
			return GL_HALF_FLOAT;
		case ANDROID_BITMAP_FORMAT_RGBA_1010102:
			return GL_UNSIGNED_INT_2_10_10_10_REV;
		default:
			return -1;
	}
}

JNIEXPORT jint JNICALL Java_android_opengl_GLUtils_native_1getInternalFormat(JNIEnv *env, jclass clazz, jobject bitmap)
{
	struct AndroidBitmapInfo info;
	AndroidBitmap_getInfo(env, bitmap, &info);
	return get_internal_format(info.format);
}

JNIEXPORT jint JNICALL Java_android_opengl_GLUtils_native_1getType(JNIEnv *env, jclass clazz, jobject bitmap)
{
	struct AndroidBitmapInfo info;
	AndroidBitmap_getInfo(env, bitmap, &info);
	return get_type(info.format);
}

JNIEXPORT jint JNICALL Java_android_opengl_GLUtils_native_1texImage2D(JNIEnv *env, jclass clazz, jint target, jint level, jint internalformat,
                                                                      jobject bitmap, jint type, jint border)
{
	struct AndroidBitmapInfo info;
	void *pixels = NULL;
	if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 || AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0 || !pixels)
		return -1;
	int ret = -1;
	if (internalformat < 0)
		internalformat = get_internal_format(info.format);
	if (type < 0)
		type = get_type(info.format);
	if (check_internal_format(info.format, internalformat, type)) {
		bionic_glTexImage2D(target, level, internalformat, info.width, info.height, border,
		                    get_pixel_format_from_internal_format(internalformat), type, pixels);
		ret = 0;
	}
	AndroidBitmap_unlockPixels(env, bitmap);
	return ret;
}

JNIEXPORT jint JNICALL Java_android_opengl_GLUtils_native_1texSubImage2D(JNIEnv *env, jclass clazz, jint target, jint level, jint xoffset, jint yoffset,
                                                                         jobject bitmap, jint format, jint type)
{
	struct AndroidBitmapInfo info;
	void *pixels = NULL;
	if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 || AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0 || !pixels)
		return -1;
	int ret = -1;
	int internal_format = get_internal_format(info.format);
	if (format < 0)
		format = get_pixel_format_from_internal_format(internal_format);
	if (format != GL_PALETTE8_RGBA8_OES && check_internal_format(info.format, internal_format, type)) {
		bionic_glTexSubImage2D(target, level, xoffset, yoffset, info.width, info.height, format, type, pixels);
		ret = 0;
	}
	AndroidBitmap_unlockPixels(env, bitmap);
	return ret;
}
