#include <gdk/gdk.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

// FIXME: put the header in a common place
#include "../api-impl-jni/defines.h"
#include "../api-impl-jni/util.h"
#include "bitmap.h"

#define ANDROID_BITMAP_RESULT_SUCCESS 0
#define ANDROID_BITMAP_RESULT_BAD_PARAMETER -1
#define ANDROID_BITMAP_RESULT_JNI_EXCEPTION -2

/* the NDK's AndroidBitmapFormat values, as Bitmap.Config.android_memory_format holds them */
#define NDK_FORMAT_RGBA_8888 1
#define NDK_FORMAT_RGB_565 4
#define NDK_FORMAT_RGBA_4444 7
#define NDK_FORMAT_A_8 8
#define NDK_FORMAT_RGBA_F16 9

#define ANDROID_BITMAP_FLAGS_ALPHA_PREMUL 0
#define ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE 1
#define ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL 2

static int ndk_format(JNIEnv *env, jobject bitmap)
{
	return _GET_INT_FIELD(_GET_OBJ_FIELD(bitmap, "config", "Landroid/graphics/Bitmap$Config;"), "android_memory_format");
}

/* AOSP (ABitmap_getInfo): opaque without alpha, otherwise premultiplied unless setPremultiplied(false) */
static int alpha_flags(JNIEnv *env, jobject bitmap)
{
	if (!_GET_BOOL_FIELD(bitmap, "hasAlpha") || ndk_format(env, bitmap) == NDK_FORMAT_RGB_565)
		return ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
	return _GET_BOOL_FIELD(bitmap, "tlPremultiplied") ? ANDROID_BITMAP_FLAGS_ALPHA_PREMUL : ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL;
}

int AndroidBitmap_getInfo(JNIEnv *env, jobject bitmap, struct AndroidBitmapInfo *info)
{
	if (!bitmap)
		return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
	if (info) {
		info->width = _GET_INT_FIELD(bitmap, "width");
		info->height = _GET_INT_FIELD(bitmap, "height");
		info->stride = _GET_INT_FIELD(bitmap, "stride");
		info->format = ndk_format(env, bitmap);
		info->flags = alpha_flags(env, bitmap);
	}
	return ANDROID_BITMAP_RESULT_SUCCESS;
}

/*
 * AOSP hands native code the bitmap's pixel memory in its config's layout. The bitmap keeps its pixels
 * as premultiplied RGBA (Bitmap.tlLockPixels): a premultiplied or opaque ARGB_8888 bitmap gets that
 * memory itself. The 16-bit and alpha-only layouts are packed from it into a buffer of the bitmap's
 * row bytes and unpacked into it on unlock; RGBA_F16 and an un-premultiplied ARGB_8888 bitmap are
 * downloaded from its texture in that layout and become its texture on unlock.
 */
int AndroidBitmap_lockPixels(JNIEnv *env, jobject bitmap, void **pixels)
{
	if (!bitmap)
		return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
	if (_GET_LONG_FIELD(bitmap, "bytes"))
		return ANDROID_BITMAP_RESULT_BAD_PARAMETER; /* already locked */
	int width = _GET_INT_FIELD(bitmap, "width");
	int height = _GET_INT_FIELD(bitmap, "height");
	int stride = _GET_INT_FIELD(bitmap, "stride");
	int format = ndk_format(env, bitmap);
	int flags = alpha_flags(env, bitmap);
	if (format == NDK_FORMAT_RGBA_8888 && flags != ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL && stride == width * 4) {
		guint8 *mem = _PTR(J__Bitmap__tlLockPixels(env, bitmap));
		if ((*env)->ExceptionCheck(env))
			return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
		/* "bytes" holds the lock: the pixel memory is not ours to free (marked by its low bit) */
		_SET_LONG_FIELD(bitmap, "bytes", _INTPTR(mem) | 1);
		if (pixels)
			*pixels = mem;
		return ANDROID_BITMAP_RESULT_SUCCESS;
	}
	if (format == NDK_FORMAT_RGB_565 || format == NDK_FORMAT_RGBA_4444 || format == NDK_FORMAT_A_8) {
		const guint8 *mem = _PTR(J__Bitmap__tlLockPixels(env, bitmap));
		if ((*env)->ExceptionCheck(env))
			return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
		guint8 *packed = g_malloc0((gsize)stride * height);
		for (int y = 0; y < height; y++) {
			const guint8 *s = mem + (gsize)y * width * 4;
			guint8 *d = packed + (gsize)y * stride;
			for (int x = 0; x < width; x++, s += 4) {
				if (format == NDK_FORMAT_A_8) {
					d[x] = s[3];
				} else {
					guint16 v;
					if (format == NDK_FORMAT_RGB_565)
						v = (guint16)(((s[0] >> 3) << 11) | ((s[1] >> 2) << 5) | (s[2] >> 3));
					else
						v = (guint16)(((s[0] >> 4) << 12) | ((s[1] >> 4) << 8) | ((s[2] >> 4) << 4) | (s[3] >> 4));
					memcpy(d + x * 2, &v, 2);
				}
			}
		}
		_SET_LONG_FIELD(bitmap, "bytes", _INTPTR(packed));
		if (pixels)
			*pixels = packed;
		return ANDROID_BITMAP_RESULT_SUCCESS;
	}
	GdkMemoryFormat gdk_format;
	if (format == NDK_FORMAT_RGBA_8888)
		gdk_format = GDK_MEMORY_R8G8B8A8;
	else if (format == NDK_FORMAT_RGBA_F16)
		gdk_format = flags == ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL ? GDK_MEMORY_R16G16B16A16_FLOAT : GDK_MEMORY_R16G16B16A16_FLOAT_PREMULTIPLIED;
	else
		return ANDROID_BITMAP_RESULT_BAD_PARAMETER; /* HARDWARE: no pixel memory (AOSP) */
	GdkTexture *texture = _PTR(J__Bitmap__getTexture(env, bitmap));
	GdkTextureDownloader *downloader = gdk_texture_downloader_new(texture);
	gdk_texture_downloader_set_format(downloader, gdk_format);
	guint8 *data = g_malloc0((gsize)stride * height);
	gdk_texture_downloader_download_into(downloader, data, stride);
	gdk_texture_downloader_free(downloader);
	_SET_LONG_FIELD(bitmap, "bytes", _INTPTR(data));
	if (pixels)
		*pixels = data;
	return ANDROID_BITMAP_RESULT_SUCCESS;
}

int AndroidBitmap_unlockPixels(JNIEnv *env, jobject bitmap)
{
	if (!bitmap)
		return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
	jlong lock = _GET_LONG_FIELD(bitmap, "bytes");
	if (!lock)
		return ANDROID_BITMAP_RESULT_BAD_PARAMETER; /* not locked */
	int width = _GET_INT_FIELD(bitmap, "width");
	int height = _GET_INT_FIELD(bitmap, "height");
	int stride = _GET_INT_FIELD(bitmap, "stride");
	int format = ndk_format(env, bitmap);
	_SET_LONG_FIELD(bitmap, "bytes", 0);
	if (lock & 1) {
		J__Bitmap__tlUnlockPixels(env, bitmap);
		return ANDROID_BITMAP_RESULT_SUCCESS;
	}
	guint8 *data = _PTR(lock);
	if (format == NDK_FORMAT_RGB_565 || format == NDK_FORMAT_RGBA_4444 || format == NDK_FORMAT_A_8) {
		guint8 *mem = _PTR(J__Bitmap__tlLockPixels(env, bitmap));
		for (int y = 0; y < height; y++) {
			const guint8 *s = data + (gsize)y * stride;
			guint8 *d = mem + (gsize)y * width * 4;
			for (int x = 0; x < width; x++, d += 4) {
				if (format == NDK_FORMAT_A_8) {
					d[0] = d[1] = d[2] = 0;
					d[3] = s[x];
					continue;
				}
				guint16 v;
				memcpy(&v, s + x * 2, 2);
				if (format == NDK_FORMAT_RGB_565) {
					guint8 r = (v >> 11) & 0x1f, g = (v >> 5) & 0x3f, b = v & 0x1f;
					d[0] = (r << 3) | (r >> 2);
					d[1] = (g << 2) | (g >> 4);
					d[2] = (b << 3) | (b >> 2);
					d[3] = 255;
				} else {
					d[0] = ((v >> 12) & 0xf) * 17;
					d[1] = ((v >> 8) & 0xf) * 17;
					d[2] = ((v >> 4) & 0xf) * 17;
					d[3] = (v & 0xf) * 17;
				}
			}
		}
		g_free(data);
		J__Bitmap__tlUnlockPixels(env, bitmap);
		return ANDROID_BITMAP_RESULT_SUCCESS;
	}
	int flags = alpha_flags(env, bitmap);
	GdkMemoryFormat gdk_format;
	if (format == NDK_FORMAT_RGBA_F16)
		gdk_format = flags == ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL ? GDK_MEMORY_R16G16B16A16_FLOAT : GDK_MEMORY_R16G16B16A16_FLOAT_PREMULTIPLIED;
	else
		gdk_format = GDK_MEMORY_R8G8B8A8;
	GBytes *bytes = g_bytes_new_take(data, (gsize)stride * height);
	GdkTexture *texture = gdk_memory_texture_new(width, height, gdk_format, bytes, stride);
	g_bytes_unref(bytes);
	J__Bitmap__tlSetTexture(env, bitmap, _INTPTR(texture));
	return ANDROID_BITMAP_RESULT_SUCCESS;
}
