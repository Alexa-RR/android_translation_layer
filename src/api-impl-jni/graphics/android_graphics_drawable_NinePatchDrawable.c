#include <arpa/inet.h>
#include <gdk/gdk.h>
#include <gtk/gtk.h>
#include <string.h>

#include "../defines.h"
#include "NinePatchPaintable.h"

#include "../generated_headers/android_graphics_drawable_NinePatchDrawable.h"

JNIEXPORT jlong JNICALL Java_android_graphics_drawable_NinePatchDrawable_nativeCreate___3BJ(JNIEnv *env, jobject this, jbyteArray chunk_array, jlong texture_ptr)
{
	GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
	int size = (*env)->GetArrayLength(env, chunk_array);
	struct Res_png_9patch *chunk = malloc(size);
	(*env)->GetByteArrayRegion(env, chunk_array, 0, size, (jbyte *)chunk);
	GdkPaintable *paintable = ninepatch_paintable_new(chunk, size, g_object_ref(texture));
	return _INTPTR(paintable);
}

/* The PNG file could theoretically come from untrusted sources. So, our parsing code must be
 * safe against buffer overflows and other possible attacks. */
JNIEXPORT jlong JNICALL Java_android_graphics_drawable_NinePatchDrawable_nativeCreate__Ljava_lang_String_2(JNIEnv *env, jobject this, jstring path_jstr)
{
	GdkPaintable *paintable = NULL;
	struct Res_png_9patch *chunk = NULL;
	uint32_t chunk_size = 0;
	uint8_t buf[8];

	const char *path = (*env)->GetStringUTFChars(env, path_jstr, NULL);
	GdkTexture *texture = gdk_texture_new_from_filename(path, NULL);
	FILE *f = fopen(path, "rb");
	(*env)->ReleaseStringUTFChars(env, path_jstr, path);

	if (!f)
		return 0;
	// always check that we actually read all bytes, to avoid uninitialized data
	if (fread(buf, 1, 8, f) != 8) {
		fclose(f);
		return 0;
	}
	if (buf[0] != 0x89 || buf[1] != 'P' || buf[2] != 'N' || buf[3] != 'G') {
		fclose(f);
		return 0;
	}
	while (fread(buf, 1, 8, f) == 8) {

		chunk_size = ntohl(((uint32_t *)buf)[0]);
		chunk_size += 4; // 4 bytes checksum

		if (!strncmp((char *)&buf[4], "npTc", 4)) {
			// check minimal size to avoid out-of-bounds access and limit maximum size to 1MB to make potential unknown vulnerabilities harder to exploit
			if (chunk_size < sizeof(struct Res_png_9patch) || chunk_size > 1024 * 1024) {
				fclose(f);
				return 0;
			}
			chunk = malloc(chunk_size);
			if (!chunk) {
				fclose(f);
				return 0;
			}
			if (fread(chunk, 1, chunk_size, f) != chunk_size) {
				free(chunk);
				fclose(f);
				return 0;
			}
			break;
		} else {
			fseek(f, chunk_size, SEEK_CUR);
		}
	}
	fclose(f);

	if (chunk)
		paintable = ninepatch_paintable_new(chunk, chunk_size, texture);

	return _INTPTR(paintable);
}

JNIEXPORT void JNICALL Java_android_graphics_drawable_NinePatchDrawable_nativeSetTint(JNIEnv *env, jobject this, jlong paintable_ptr, jint color)
{
	NinePatchPaintable *paintable = NINEPATCH_PAINTABLE(_PTR(paintable_ptr));
	paintable->tint = color;
}

/* ---- teslalinux: android.graphics.NinePatch natives (tl_ninepatch) ---- */

JNIEXPORT jlong JNICALL Java_android_graphics_NinePatch_nativeCreate(JNIEnv *env, jclass class, jbyteArray chunk_array, jlong texture_ptr)
{
	return Java_android_graphics_drawable_NinePatchDrawable_nativeCreate___3BJ(env, NULL, chunk_array, texture_ptr);
}

/* the PNG comes from an APK, which is untrusted data: every chunk walk is bounds-checked */
/* insets_array (11 ints, may be NULL), AOSP NinePatchPeeker: [0] 1 when an npLb or npOl chunk exists,
 * [1..4] optical insets (npLb, layout bounds), [5..8] outline insets, [9] outline radius (float bits),
 * [10] outline alpha (npOl). aapt writes both chunks as host-order int32 (memcpy), as AOSP reads them. */
JNIEXPORT jlong JNICALL Java_android_graphics_NinePatch_nativeCreateFromBytes(JNIEnv *env, jclass class, jbyteArray png_array, jintArray insets_array)
{
	jsize size = (*env)->GetArrayLength(env, png_array);
	if (size < 8)
		return 0;
	uint8_t *png = malloc(size);
	(*env)->GetByteArrayRegion(env, png_array, 0, size, (jbyte *)png);
	GBytes *bytes = g_bytes_new_take(png, size);
	GdkTexture *texture = gdk_texture_new_from_bytes(bytes, NULL);
	if (!texture) {
		g_bytes_unref(bytes);
		return 0;
	}
	struct Res_png_9patch *chunk = NULL;
	uint32_t chunk_size = 0;
	jint insets[11] = {0};
	if (png[0] == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G') {
		size_t pos = 8;
		while (pos + 8 <= (size_t)size) {
			uint32_t len = ntohl(*(uint32_t *)(png + pos));
			const char *tag = (const char *)png + pos + 4;
			const uint8_t *data = png + pos + 8;
			if ((size_t)len > (size_t)size - pos - 8)
				break;
			if (!strncmp(tag, "npTc", 4) && !chunk) {
				chunk_size = len + 4; /* the file loader keeps the 4 byte checksum too */
				if (chunk_size >= sizeof(struct Res_png_9patch) && chunk_size <= 1024 * 1024 && pos + 8 + chunk_size <= (size_t)size) {
					chunk = malloc(chunk_size);
					memcpy(chunk, data, chunk_size);
				}
			} else if (!strncmp(tag, "npLb", 4) && len == 4 * sizeof(int32_t)) {
				insets[0] = 1;
				memcpy(&insets[1], data, 4 * sizeof(int32_t));
			} else if (!strncmp(tag, "npOl", 4) && len == 24) { /* 4 int32, 1 float, 1 int32 holding a byte */
				int32_t alpha;
				insets[0] = 1;
				memcpy(&insets[5], data, 4 * sizeof(int32_t));
				memcpy(&insets[9], data + 16, sizeof(float));
				memcpy(&alpha, data + 20, sizeof(int32_t));
				insets[10] = alpha & 0xff;
			} else if (!strncmp(tag, "IEND", 4)) {
				break;
			}
			pos += 8 + (size_t)len + 4;
		}
	}
	if (insets_array)
		(*env)->SetIntArrayRegion(env, insets_array, 0, 11, insets);
	g_bytes_unref(bytes);
	GdkPaintable *paintable = chunk ? ninepatch_paintable_new(chunk, chunk_size, texture) : NULL;
	if (!paintable) {
		free(chunk);
		g_object_unref(texture);
		return 0;
	}
	return _INTPTR(paintable);
}

/* out: width, height, padding left/top/right/bottom, numXDivs, numYDivs, numColors */
JNIEXPORT void JNICALL Java_android_graphics_NinePatch_nativeGetInfo(JNIEnv *env, jclass class, jlong paintable_ptr, jintArray out_array)
{
	NinePatchPaintable *p = NINEPATCH_PAINTABLE(_PTR(paintable_ptr));
	struct Res_png_9patch *c = p->chunk;
	jint out[9];
	out[0] = p->width;
	out[1] = p->height;
	if (c->wasDeserialized) {
		out[2] = c->paddingLeft;
		out[3] = c->paddingTop;
		out[4] = c->paddingRight;
		out[5] = c->paddingBottom;
	} else { /* serialized form: network byte order */
		out[2] = ntohl(c->paddingLeft);
		out[3] = ntohl(c->paddingTop);
		out[4] = ntohl(c->paddingRight);
		out[5] = ntohl(c->paddingBottom);
	}
	out[6] = c->numXDivs;
	out[7] = c->numYDivs;
	out[8] = c->numColors;
	(*env)->SetIntArrayRegion(env, out_array, 0, 9, out);
}

/* the divs and colours were converted to host order by ninepatch_paintable_new */
JNIEXPORT void JNICALL Java_android_graphics_NinePatch_nativeGetDivs(JNIEnv *env, jclass class, jlong paintable_ptr, jintArray x_array, jintArray y_array, jintArray color_array)
{
	NinePatchPaintable *p = NINEPATCH_PAINTABLE(_PTR(paintable_ptr));
	struct Res_png_9patch *c = p->chunk;
	(*env)->SetIntArrayRegion(env, x_array, 0, c->numXDivs, (jint *)((void *)c + c->xDivsOffset));
	(*env)->SetIntArrayRegion(env, y_array, 0, c->numYDivs, (jint *)((void *)c + c->yDivsOffset));
	(*env)->SetIntArrayRegion(env, color_array, 0, c->numColors, (jint *)((void *)c + c->colorsOffset));
}

JNIEXPORT void JNICALL Java_android_graphics_NinePatch_nativeDraw(JNIEnv *env, jclass class, jlong paintable_ptr, jlong snapshot_ptr, jfloat x, jfloat y, jfloat width, jfloat height, jfloat alpha,
                                                               jfloat scale_x, jfloat offset_x, jfloat scale_y, jfloat offset_y)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_save(snapshot);
	if (alpha < 1.0f)
		gtk_snapshot_push_opacity(snapshot, alpha);
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(x, y));
	ninepatch_paintable_draw(NINEPATCH_PAINTABLE(_PTR(paintable_ptr)), GDK_SNAPSHOT(snapshot), width, height, scale_x, offset_x, scale_y, offset_y);
	if (alpha < 1.0f)
		gtk_snapshot_pop(snapshot);
	gtk_snapshot_restore(snapshot);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_NinePatch_nativeHasAlpha(JNIEnv *env, jclass class, jlong paintable_ptr)
{
	NinePatchPaintable *p = NINEPATCH_PAINTABLE(_PTR(paintable_ptr));
	int w = gdk_texture_get_width(p->texture), h = gdk_texture_get_height(p->texture);
	size_t n = (size_t)w * h * 4;
	guchar *data = malloc(n);
	gdk_texture_download(p->texture, data, w * 4); /* CAIRO_FORMAT_ARGB32 in native endian: alpha is byte 3 on little-endian */
	jboolean has = JNI_FALSE;
	for (size_t i = 3; i < n; i += 4) {
		if (data[i] != 0xff) {
			has = JNI_TRUE;
			break;
		}
	}
	free(data);
	return has;
}

JNIEXPORT void JNICALL Java_android_graphics_NinePatch_nativeRef(JNIEnv *env, jclass class, jlong paintable_ptr)
{
	g_object_ref(_PTR(paintable_ptr));
}

JNIEXPORT void JNICALL Java_android_graphics_NinePatch_nativeUnref(JNIEnv *env, jclass class, jlong paintable_ptr)
{
	g_object_unref(_PTR(paintable_ptr));
}
