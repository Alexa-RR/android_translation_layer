#include <string.h>
#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"
#include "gdk/gdk.h"
#include "glib.h"

#include "../generated_headers/android_graphics_Bitmap.h"

JNIEXPORT jlong JNICALL Java_android_graphics_Bitmap_native_1create_1snapshot(JNIEnv *env, jclass class, jlong texture_ptr)
{
	GtkSnapshot *snapshot = gtk_snapshot_new();
	if (texture_ptr) {
		GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
		gtk_snapshot_append_texture(snapshot, texture, &GRAPHENE_RECT_INIT(0, 0, gdk_texture_get_width(texture), gdk_texture_get_height(texture)));
		g_object_unref(texture);
	}
	return _INTPTR(snapshot);
}

extern GThread *main_thread_id;

JNIEXPORT jlong JNICALL Java_android_graphics_Bitmap_native_1create_1texture(JNIEnv *env, jclass class, jlong snapshot_ptr, jint width, jint height, jint stride, jint format)
{
	/* teslalinux (native-bitmap-software-render.sh): software rendering, one renderer for the main thread */
	static GskRenderer *tl_bitmap_renderer;
	GtkSnapshot *snapshot = _PTR(snapshot_ptr);
	GskRenderNode *node = snapshot ? gtk_snapshot_free_to_node(snapshot) : NULL;
	GdkTexture *texture = NULL;
	if (node) {
		graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, width, height);
		GskRenderer *renderer;
		const gboolean main_thread = g_thread_self() == main_thread_id;
		if (main_thread && tl_bitmap_renderer) {
			renderer = tl_bitmap_renderer;
		} else {
			renderer = gsk_cairo_renderer_new();
			gsk_renderer_realize(renderer, NULL, NULL);
			if (main_thread)
				tl_bitmap_renderer = renderer;
		}
		texture = gsk_renderer_render_texture(renderer, node, &bounds);
		gsk_render_node_unref(node);
		if (renderer != tl_bitmap_renderer) {
			gsk_renderer_unrealize(renderer);
			g_object_unref(renderer);
		}
	} else {
		if (format == -1) {
			format = GDK_MEMORY_R8G8B8A8;
			stride = width * 4;
		}
		GBytes *bytes = g_bytes_new_take(g_malloc0(height * stride), height * stride);
		texture = gdk_memory_texture_new(width, height, format, bytes, stride);
		g_bytes_unref(bytes);
	}

	return _INTPTR(texture);
}

JNIEXPORT jint JNICALL Java_android_graphics_Bitmap_native_1get_1width(JNIEnv *env, jclass class, jlong texture_ptr)
{
	return gdk_texture_get_width(GDK_TEXTURE(_PTR(texture_ptr)));
}

JNIEXPORT jint JNICALL Java_android_graphics_Bitmap_native_1get_1height(JNIEnv *env, jclass class, jlong texture_ptr)
{
	return gdk_texture_get_height(GDK_TEXTURE(_PTR(texture_ptr)));
}

JNIEXPORT jlong JNICALL Java_android_graphics_Bitmap_native_1erase_1color(JNIEnv *env, jclass class, jint color, jint width, jint height)
{
	GdkRGBA rgba = {
		.red = ((color >> 16) & 0xFF) / 255.f,
		.green = ((color >> 8) & 0xFF) / 255.f,
		.blue = ((color >> 0) & 0xFF) / 255.f,
		.alpha = ((color >> 24) & 0xFF) / 255.f,
	};
	graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, width, height);
	GtkSnapshot *snapshot = gtk_snapshot_new();
	gtk_snapshot_append_color(snapshot, &rgba, &bounds);
	return _INTPTR(snapshot);
}

JNIEXPORT void JNICALL Java_android_graphics_Bitmap_native_1recycle(JNIEnv *env, jclass class, jlong texture_ptr, jlong snapshot_ptr)
{
	if (texture_ptr)
		g_object_unref(GDK_TEXTURE(_PTR(texture_ptr)));
	if (snapshot_ptr)
		g_object_unref(GTK_SNAPSHOT(_PTR(snapshot_ptr)));
}

JNIEXPORT jlong JNICALL Java_android_graphics_Bitmap_native_1ref_1texture(JNIEnv *env, jclass class, jlong texture_ptr)
{
	return _INTPTR(g_object_ref(GDK_TEXTURE(_PTR(texture_ptr))));
}

/*
 * The bitmap's pixel memory (Bitmap.tlPixels): what Skia keeps for a raster bitmap, kN32 premultiplied
 * in RGBA byte order (Android's little-endian N32), rows of width * 4 bytes. The pixel accessors
 * (getPixel(s), setPixel(s), copyPixels*Buffer, AndroidBitmap_lockPixels) work on it; the bitmap's
 * texture is made from it when the bitmap is drawn, and it is dropped when the bitmap is drawn into.
 * The conversions are Skia's: un-premultiplied colour ints are premultiplied with rounding when
 * written, premultiplied pixels are divided by their alpha with rounding when read (SkConvertPixels).
 */

static inline guint8 tl_premul(guint32 c, guint32 a)
{
	return (guint8)((c * a + 127) / 255);
}

static inline guint8 tl_unpremul(guint32 c, guint32 a)
{
	guint32 v = (c * 255 + a / 2) / a;
	return v > 255 ? 255 : (guint8)v;
}

JNIEXPORT jlong JNICALL Java_android_graphics_Bitmap_native_1pixels_1from_1texture(JNIEnv *env, jclass class, jlong texture_ptr, jint width, jint height)
{
	guint8 *pixels = g_malloc0((gsize)width * height * 4);
	if (texture_ptr) {
		GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
		GdkTextureDownloader *downloader = gdk_texture_downloader_new(texture);
		gdk_texture_downloader_set_format(downloader, GDK_MEMORY_R8G8B8A8_PREMULTIPLIED);
		if (gdk_texture_get_width(texture) == width && gdk_texture_get_height(texture) == height) {
			gdk_texture_downloader_download_into(downloader, pixels, (gsize)width * 4);
		} else {
			/* a texture of another size (a reconfigured bitmap): its top-left part */
			int tw = gdk_texture_get_width(texture), th = gdk_texture_get_height(texture);
			guint8 *whole = g_malloc((gsize)tw * th * 4);
			gdk_texture_downloader_download_into(downloader, whole, (gsize)tw * 4);
			for (int row = 0; row < MIN(th, height); row++)
				memcpy(pixels + (gsize)row * width * 4, whole + (gsize)row * tw * 4, (gsize)MIN(tw, width) * 4);
			g_free(whole);
		}
		gdk_texture_downloader_free(downloader);
	}
	return _INTPTR(pixels);
}

JNIEXPORT jlong JNICALL Java_android_graphics_Bitmap_native_1pixels_1to_1texture(JNIEnv *env, jclass class, jlong pixels_ptr, jint width, jint height)
{
	GBytes *bytes = g_bytes_new(_PTR(pixels_ptr), (gsize)width * height * 4);
	GdkTexture *texture = gdk_memory_texture_new(width, height, GDK_MEMORY_R8G8B8A8_PREMULTIPLIED, bytes, (gsize)width * 4);
	g_bytes_unref(bytes);
	return _INTPTR(texture);
}

JNIEXPORT void JNICALL Java_android_graphics_Bitmap_native_1pixels_1free(JNIEnv *env, jclass class, jlong pixels_ptr)
{
	g_free(_PTR(pixels_ptr));
}

/* AOSP getPixels: the rectangle as un-premultiplied ARGB colour ints, at offset with the given stride */
JNIEXPORT void JNICALL Java_android_graphics_Bitmap_native_1pixels_1read(JNIEnv *env, jclass class, jlong pixels_ptr, jint bitmap_width, jintArray out, jint offset, jint stride, jint x, jint y, jint width, jint height)
{
	const guint8 *pixels = _PTR(pixels_ptr);
	jint *array = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
	for (int row = 0; row < height; row++) {
		const guint8 *p = pixels + ((gsize)(y + row) * bitmap_width + x) * 4;
		jint *o = array + offset + (gssize)row * stride;
		for (int col = 0; col < width; col++, p += 4) {
			guint32 a = p[3], r = p[0], g = p[1], b = p[2];
			if (a == 0) {
				r = g = b = 0;
			} else if (a != 255) {
				r = tl_unpremul(r, a);
				g = tl_unpremul(g, a);
				b = tl_unpremul(b, a);
			}
			o[col] = (jint)((a << 24) | (r << 16) | (g << 8) | b);
		}
	}
	(*env)->ReleasePrimitiveArrayCritical(env, out, array, 0);
}

/*
 * AOSP setPixels: the rectangle's pixels are replaced by the colours (not drawn over them),
 * premultiplied and stored in the bitmap's config: RGB_565 keeps 5/6/5 bits and no alpha, ARGB_4444
 * four bits per premultiplied channel, ALPHA_8 only the alpha (config: 0 8888, 1 565, 2 4444, 3 A8).
 */
JNIEXPORT void JNICALL Java_android_graphics_Bitmap_native_1pixels_1write(JNIEnv *env, jclass class, jlong pixels_ptr, jint bitmap_width, jintArray in, jint offset, jint stride, jint x, jint y, jint width, jint height, jint config)
{
	guint8 *pixels = _PTR(pixels_ptr);
	jint *array = (*env)->GetPrimitiveArrayCritical(env, in, NULL);
	for (int row = 0; row < height; row++) {
		guint8 *p = pixels + ((gsize)(y + row) * bitmap_width + x) * 4;
		const jint *s = array + offset + (gssize)row * stride;
		for (int col = 0; col < width; col++, p += 4) {
			guint32 c = (guint32)s[col];
			guint32 a = c >> 24, r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
			switch (config) {
			case 1: /* RGB_565: opaque, 5/6/5 bits widened as Skia reads them back */
				r = (r >> 3 << 3) | (r >> 5);
				g = (g >> 2 << 2) | (g >> 6);
				b = (b >> 3 << 3) | (b >> 5);
				a = 255;
				break;
			case 3: /* ALPHA_8 */
				r = g = b = 0;
				break;
			}
			if (a != 255) {
				r = tl_premul(r, a);
				g = tl_premul(g, a);
				b = tl_premul(b, a);
			}
			if (config == 2) { /* ARGB_4444: four bits of each premultiplied channel */
				r = (r >> 4) * 17;
				g = (g >> 4) * 17;
				b = (b >> 4) * 17;
				a = (a >> 4) * 17;
			}
			p[0] = r;
			p[1] = g;
			p[2] = b;
			p[3] = a;
		}
	}
	(*env)->ReleasePrimitiveArrayCritical(env, in, array, 0);
}

/*
 * Canvas.drawVertices / drawBitmapMesh with vertex colours (Skia: the colours interpolated across
 * each triangle, kModulate with the paint's shader or bitmap). Each triangle is a Gouraud-shaded
 * patch of a cairo mesh pattern (three sides; cairo interpolates the corner colours), drawn in order
 * into a premultiplied image of width x height device pixels; with source pixels (the shader drawn
 * into a device-aligned bitmap, premultiplied RGBA) every pixel is the product of the two.
 */
JNIEXPORT jlong JNICALL Java_android_graphics_Bitmap_native_1gouraud(JNIEnv *env, jclass class, jlong source_ptr, jint width, jint height, jfloatArray positions_arr, jintArray colors_arr, jintArray triangles_arr)
{
	if (width <= 0 || height <= 0)
		return 0;
	cairo_surface_t *surface = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, width, height);
	if (cairo_surface_status(surface) != CAIRO_STATUS_SUCCESS) {
		cairo_surface_destroy(surface);
		return 0;
	}
	jsize ntri = (*env)->GetArrayLength(env, triangles_arr) / 3;
	jfloat *pos = (*env)->GetFloatArrayElements(env, positions_arr, NULL);
	jint *colors = (*env)->GetIntArrayElements(env, colors_arr, NULL);
	jint *tris = (*env)->GetIntArrayElements(env, triangles_arr, NULL);
	cairo_pattern_t *mesh = cairo_pattern_create_mesh();
	for (jsize t = 0; t < ntri; t++) {
		cairo_mesh_pattern_begin_patch(mesh);
		for (int k = 0; k < 3; k++) {
			int v = tris[t * 3 + k];
			if (k == 0)
				cairo_mesh_pattern_move_to(mesh, pos[v * 2], pos[v * 2 + 1]);
			else
				cairo_mesh_pattern_line_to(mesh, pos[v * 2], pos[v * 2 + 1]);
		}
		for (int k = 0; k < 3; k++) {
			guint32 c = (guint32)colors[tris[t * 3 + k]];
			cairo_mesh_pattern_set_corner_color_rgba(mesh, k, ((c >> 16) & 0xff) / 255.0, ((c >> 8) & 0xff) / 255.0,
			                                         (c & 0xff) / 255.0, (c >> 24) / 255.0);
		}
		cairo_mesh_pattern_end_patch(mesh);
	}
	(*env)->ReleaseIntArrayElements(env, triangles_arr, tris, JNI_ABORT);
	(*env)->ReleaseIntArrayElements(env, colors_arr, colors, JNI_ABORT);
	(*env)->ReleaseFloatArrayElements(env, positions_arr, pos, JNI_ABORT);
	cairo_t *cr = cairo_create(surface);
	cairo_set_source(cr, mesh);
	cairo_paint(cr);
	cairo_destroy(cr);
	cairo_pattern_destroy(mesh);
	cairo_surface_flush(surface);

	/* to premultiplied RGBA bytes, times the source */
	const guint8 *src = _PTR(source_ptr);
	const guint8 *img = cairo_image_surface_get_data(surface);
	int img_stride = cairo_image_surface_get_stride(surface);
	guint8 *out = g_malloc((gsize)width * height * 4);
	for (int y = 0; y < height; y++) {
		const guint32 *row = (const guint32 *)(img + (gsize)y * img_stride);
		guint8 *o = out + (gsize)y * width * 4;
		const guint8 *s = src ? src + (gsize)y * width * 4 : NULL;
		for (int x = 0; x < width; x++, o += 4) {
			guint32 p = row[x]; /* cairo: native-endian premultiplied ARGB */
			guint32 a = p >> 24, r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
			if (s) {
				r = (r * s[0] + 127) / 255;
				g = (g * s[1] + 127) / 255;
				b = (b * s[2] + 127) / 255;
				a = (a * s[3] + 127) / 255;
				s += 4;
			}
			o[0] = r;
			o[1] = g;
			o[2] = b;
			o[3] = a;
		}
	}
	cairo_surface_destroy(surface);
	GBytes *bytes = g_bytes_new_take(out, (gsize)width * height * 4);
	GdkTexture *texture = gdk_memory_texture_new(width, height, GDK_MEMORY_R8G8B8A8_PREMULTIPLIED, bytes, (gsize)width * 4);
	g_bytes_unref(bytes);
	return _INTPTR(texture);
}

/* AOSP copyPixelsFromBuffer for ARGB_8888: the bytes are the pixel memory itself (RGBA, rows of
 * row_bytes), premultiplied unless the bitmap is not (then they are premultiplied here) */
JNIEXPORT void JNICALL Java_android_graphics_Bitmap_native_1pixels_1put_1raw(JNIEnv *env, jclass class, jlong pixels_ptr, jint width, jint height, jbyteArray raw, jint row_bytes, jboolean premultiplied)
{
	guint8 *pixels = _PTR(pixels_ptr);
	guint8 *src = (*env)->GetPrimitiveArrayCritical(env, raw, NULL);
	for (int row = 0; row < height; row++) {
		guint8 *d = pixels + (gsize)row * width * 4;
		memcpy(d, src + (gsize)row * row_bytes, (gsize)width * 4);
		if (!premultiplied) {
			for (int col = 0; col < width; col++, d += 4) {
				guint32 a = d[3];
				if (a != 255) {
					d[0] = tl_premul(d[0], a);
					d[1] = tl_premul(d[1], a);
					d[2] = tl_premul(d[2], a);
				}
			}
		}
	}
	(*env)->ReleasePrimitiveArrayCritical(env, raw, src, 0);
}

JNIEXPORT void JNICALL Java_android_graphics_Bitmap_native_1copy_1to_1buffer(JNIEnv *env, jclass class, jlong texture_ptr, jobject buffer, jint memory_format, jint stride)
{
	GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
	GdkTextureDownloader *downloader = gdk_texture_downloader_new(texture);
	gdk_texture_downloader_set_format(downloader, memory_format);
	jarray array_ref;
	jbyte *array;
	guchar *data = get_nio_buffer(env, buffer, &array_ref, &array);
	gdk_texture_downloader_download_into(downloader, data, stride);
	release_nio_buffer(env, array_ref, array);
	gdk_texture_downloader_free(downloader);
}

JNIEXPORT jbyteArray JNICALL Java_android_graphics_Bitmap_native_1save_1to_1png(JNIEnv *env, jclass class, jlong texture_ptr)
{
	GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
	GBytes *bytes = gdk_texture_save_to_png_bytes(texture);
	jbyteArray result = (*env)->NewByteArray(env, g_bytes_get_size(bytes));
	gsize size;
	gconstpointer data = g_bytes_get_data(bytes, &size);
	(*env)->SetByteArrayRegion(env, result, 0, size, data);
	g_bytes_unref(bytes);
	return result;
}
