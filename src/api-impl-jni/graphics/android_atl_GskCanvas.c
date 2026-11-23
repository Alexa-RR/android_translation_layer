#include <math.h>
#include <graphene.h>
#include <gtk/gtk.h>
#include <pango/pango.h>

#include "../defines.h"
#include "AndroidPaint.h"

#include "../generated_headers/android_atl_GskCanvas.h"

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawBitmap__JJIIIIJ(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong texture_ptr, jint x, jint y, jint width, jint height, jlong paint_ptr)
{
	GdkSnapshot *snapshot = (GdkSnapshot *)_PTR(snapshot_ptr);
	GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	const graphene_rect_t dst = GRAPHENE_RECT_INIT(x, y, width, height);
	if (!paint->use_color_filter) {
		gtk_snapshot_append_texture(snapshot, texture, &dst);
		return;
	}
	/* Skia: every texel of the bitmap through the paint's colour filter */
	GskRenderNode *node = gsk_texture_node_new(texture, &dst);
	tl_paint_append_filtered(snapshot, paint, node, &dst);
	gsk_render_node_unref(node);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawBitmap__JJIIIIIIIIJ(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong texture_ptr, jint x, jint y, jint width, jint height, jint src_x, jint src_y, jint src_width, jint src_height, jlong paint_ptr)
{
	GdkSnapshot *snapshot = (GdkSnapshot *)_PTR(snapshot_ptr);
	GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	const graphene_rect_t dst = GRAPHENE_RECT_INIT(x, y, width, height);
	float xscale = width / (float)src_width;
	float yscale = height / (float)src_height;
	float scaled_width = gdk_texture_get_width(texture) * xscale;
	float scaled_height = gdk_texture_get_height(texture) * yscale;
	GskRenderNode *tex = gsk_texture_node_new(texture, &GRAPHENE_RECT_INIT(x - src_x * xscale, y - src_y * yscale, scaled_width, scaled_height));
	GskRenderNode *node = gsk_clip_node_new(tex, &dst);
	gsk_render_node_unref(tex);
	/* Skia: every texel of the bitmap through the paint's colour filter */
	tl_paint_append_filtered(snapshot, paint, node, &dst);
	gsk_render_node_unref(node);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawRect(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom, jlong paint_ptr)
{
	GdkSnapshot *snapshot = (GdkSnapshot *)_PTR(snapshot_ptr);
	struct AndroidPaint *paint = _PTR(paint_ptr);
	graphene_rect_t bounds = GRAPHENE_RECT_INIT(left, top, right - left, bottom - top);
	GdkRGBA color;
	/* a solid colour: the colour filter is applied to it (no filter node) */
	tl_paint_filtered_color(paint, &color);
	gtk_snapshot_append_color(snapshot, &color, &bounds);
}

/* Skia FILL_AND_STROKE: one shape, the fill and the stroke together, coloured once; a translucent
 * colour must not cover their overlap twice, so both are drawn opaque inside an opacity group */
static void tl_fill_and_stroke(GtkSnapshot *snapshot, GskPath *path, GskFillRule rule, const GskStroke *stroke, const GdkRGBA *color)
{
	if (color->alpha >= 1.f) {
		gtk_snapshot_append_stroke(snapshot, path, stroke, color);
		gtk_snapshot_append_fill(snapshot, path, rule, color);
		return;
	}
	if (color->alpha <= 0.f)
		return;
	const GdkRGBA opaque = { color->red, color->green, color->blue, 1.f };
	gtk_snapshot_push_opacity(snapshot, color->alpha);
	gtk_snapshot_append_stroke(snapshot, path, stroke, &opaque);
	gtk_snapshot_append_fill(snapshot, path, rule, &opaque);
	gtk_snapshot_pop(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawPath(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong path_ptr, jint fill_rule, jlong paint_ptr)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskPath *path = _PTR(path_ptr);
	struct AndroidPaint *paint = _PTR(paint_ptr);
	GdkRGBA color;
	tl_paint_filtered_color(paint, &color);
	if (paint->is_stroke && paint->is_fill && paint->stroke_width > 0)
		tl_fill_and_stroke(snapshot, path, fill_rule, paint->gsk_stroke, &color);
	else if (paint->is_fill) /* FILL, or FILL_AND_STROKE at width 0 (Skia: a fill) */
		gtk_snapshot_append_fill(snapshot, path, fill_rule, &color);
	else if (paint->is_stroke)
		gtk_snapshot_append_stroke(snapshot, path, paint->gsk_stroke, &color);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1translate(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat dx, jfloat dy)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(dx, dy));
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1rotate(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat angle)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_rotate(snapshot, angle);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1save(JNIEnv *env, jclass this_class, jlong snapshot_ptr)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_save(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1restore(JNIEnv *env, jclass this_class, jlong snapshot_ptr)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_restore(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawLine(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat x0, jfloat y0, jfloat x1, jfloat y1, jlong paint_ptr)
{
	if (isnan(x0) || isnan(y0) || isnan(x1) || isnan(y1)) {
		return;
	}
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gtk_snapshot_save(snapshot);
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(x0, y0));
	float rotation = atan2(y1 - y0, x1 - x0);
	gtk_snapshot_rotate(snapshot, rotation * 180 / M_PI);
	float length = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0));
	float stroke_width = gsk_stroke_get_line_width(paint->gsk_stroke);
	GdkRGBA color;
	tl_paint_filtered_color(paint, &color);
	gtk_snapshot_append_color(snapshot, &color, &GRAPHENE_RECT_INIT(0, -stroke_width / 2, length, stroke_width));
	gtk_snapshot_restore(snapshot);
}

/* AOSP drawLines: count floats from offset, four per line, a trailing partial line ignored */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawLines(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloatArray points_arr, jint offset, jint count, jlong paint_ptr)
{
	jfloat *points = (*env)->GetFloatArrayElements(env, points_arr, NULL);
	for (int i = offset; i + 3 < offset + count; i += 4)
		Java_android_atl_GskCanvas_native_1drawLine(env, this_class, snapshot_ptr, points[i + 0], points[i + 1], points[i + 2], points[i + 3], paint_ptr);
	(*env)->ReleaseFloatArrayElements(env, points_arr, points, JNI_ABORT);
}

extern GtkWidget *window;

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawText(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jstring text, jfloat x, jfloat y, jlong paint_ptr)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	PangoLayout *layout = pango_layout_new(gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, paint->font);
	const char *str = (*env)->GetStringUTFChars(env, text, NULL);
	pango_layout_set_text(layout, str, -1);
	(*env)->ReleaseStringUTFChars(env, text, str);
	PangoRectangle rect;
	pango_layout_get_pixel_extents(layout, NULL, &rect);
	y -= (float)pango_layout_get_baseline(layout) / PANGO_SCALE;
	if (paint->alignment == PANGO_ALIGN_CENTER)
		x -= rect.width / 2.f;
	else if (paint->alignment == PANGO_ALIGN_RIGHT)
		x -= rect.width;
	GdkRGBA color;
	tl_paint_filtered_color(paint, &color);
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(x, y));
	gtk_snapshot_append_layout(snapshot, layout, &color);
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(-x, -y));
	g_object_unref(layout);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawRoundRect(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom, jfloat rx, jfloat ry, jlong paint_ptr)
{
	GdkSnapshot *snapshot = (GdkSnapshot *)_PTR(snapshot_ptr);
	struct AndroidPaint *paint = _PTR(paint_ptr);
	/* the filled rounded rectangle (GskCanvas strokes every outline through its path: AOSP centres
	 * a stroke on the outline, a GSK border lies inside it) */
	GdkRGBA color;
	tl_paint_filtered_color(paint, &color);
	GskRoundedRect round_rect = {
		.bounds = GRAPHENE_RECT_INIT(left, top, right - left, bottom - top),
		.corner = {{rx, ry}, {rx, ry}, {rx, ry}, {rx, ry}},
	};
	gtk_snapshot_push_rounded_clip(snapshot, &round_rect);
	gtk_snapshot_append_color(snapshot, &color, &round_rect.bounds);
	gtk_snapshot_pop(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1scale(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat x, jfloat y)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_scale(snapshot, x, y);
}

/* teslalinux: an Android canvas matrix (graphene, row-vector convention: rows 0/1 = x/y axes, row 3 =
 * translation, column 3 = perspective) as a 2D GSK transform when it is affine (native-gskcanvas-concat2d.sh) */
static void tl_snapshot_concat_android(GtkSnapshot *snapshot, const graphene_matrix_t *m)
{
	const float a = graphene_matrix_get_value(m, 0, 0), b = graphene_matrix_get_value(m, 0, 1);
	const float c = graphene_matrix_get_value(m, 1, 0), d = graphene_matrix_get_value(m, 1, 1);
	const float tx = graphene_matrix_get_value(m, 3, 0), ty = graphene_matrix_get_value(m, 3, 1);
	const float p0 = graphene_matrix_get_value(m, 0, 3), p1 = graphene_matrix_get_value(m, 1, 3), p2 = graphene_matrix_get_value(m, 3, 3);
	const float sx = sqrtf(a * a + b * b);
	const float det = a * d - b * c;
	if (p0 != 0.f || p1 != 0.f || p2 != 1.f || sx == 0.f || det == 0.f) {
		gtk_snapshot_transform_matrix(snapshot, m);
		return;
	}
	const float sy = det / sx;
	const float shear = (a * c + b * d) / (sx * sy);
	if (tx != 0.f || ty != 0.f)
		gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(tx, ty));
	if (b != 0.f || a < 0.f)
		gtk_snapshot_rotate(snapshot, atan2f(b, a) * (180.f / (float)M_PI));
	if (shear != 0.f) {
		GskTransform *t = gsk_transform_skew(NULL, atanf(shear) * (180.f / (float)M_PI), 0.f);
		gtk_snapshot_transform(snapshot, t);
		gsk_transform_unref(t);
	}
	if (sx != 1.f || sy != 1.f)
		gtk_snapshot_scale(snapshot, sx, sy);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1concat(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong matrix_ptr)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	tl_snapshot_concat_android(snapshot, matrix);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1clipRect(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_push_clip(snapshot, &GRAPHENE_RECT_INIT(left, top, right - left, bottom - top));
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1pop(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jint count)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	while (count--)
		gtk_snapshot_pop(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawRenderNode(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong node_ptr)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskRenderNode *node = _PTR(node_ptr);
	gtk_snapshot_append_node(snapshot, node);
}

/* ---- teslalinux: path clipping ---------------------------------------------------------------- */

/* clipPath: everything drawn until the matching pop is filled through the path (Canvas.clipPath with
 * Region.Op.INTERSECT, the only intersecting op hardware canvases accept). fill_rule: 0 winding, 1 even-odd. */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1clipPath(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong path_ptr, jint fill_rule)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	/* tl_clip_path_mask: the content is masked by the alpha of the filled path (a fill node lost
	 * GTK widget content); the mask child is closed here, the content's pop is the caller's */
	gtk_snapshot_push_mask(snapshot, GSK_MASK_MODE_ALPHA);
	gtk_snapshot_append_fill(snapshot, (GskPath *)_PTR(path_ptr), fill_rule ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING, &(GdkRGBA){0, 0, 0, 1});
	gtk_snapshot_pop(snapshot);
}

/* clipOutPath (Region.Op.DIFFERENCE): the drawn content is masked by the inverse alpha of the filled
 * path. The mask child is appended and closed here, so the caller still owes exactly one pop. */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1clipOutPath(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong path_ptr, jint fill_rule)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_push_mask(snapshot, GSK_MASK_MODE_INVERTED_ALPHA);
	gtk_snapshot_append_fill(snapshot, (GskPath *)_PTR(path_ptr), fill_rule ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING, &(GdkRGBA){0, 0, 0, 1});
	gtk_snapshot_pop(snapshot);
}

/* teslalinux: a colour matrix group from a paint's colour filter; closed with native_pop */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1pushColorMatrix(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong paint_ptr)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
}

/* teslalinux: drawPath with the path's fill type: 0 WINDING, 1 EVEN_ODD, 2 INVERSE_WINDING,
 * 3 INVERSE_EVEN_ODD (Path.FillType ordinals); the inverse types fill (left, top, right, bottom)
 * minus the path. */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawPathFillType(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong path_ptr, jlong paint_ptr,
		jint fill_type, jfloat left, jfloat top, jfloat right, jfloat bottom)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskPath *path = _PTR(path_ptr);
	struct AndroidPaint *paint = _PTR(paint_ptr);
	GskFillRule rule = (fill_type == 1 || fill_type == 3) ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING;
	gboolean inverse = fill_type >= 2;
	GdkRGBA color;
	tl_paint_filtered_color(paint, &color);
	if (paint->is_stroke && paint->is_fill && !inverse && paint->stroke_width > 0) {
		tl_fill_and_stroke(snapshot, path, rule, paint->gsk_stroke, &color);
		return;
	}
	/* FILL_AND_STROKE at width 0 is a fill (Skia) */
	if (paint->is_stroke && !(paint->is_fill && paint->stroke_width == 0))
		gtk_snapshot_append_stroke(snapshot, path, paint->gsk_stroke, &color);
	if (paint->is_fill) {
		if (!inverse) {
			gtk_snapshot_append_fill(snapshot, path, rule, &color);
		} else if (right > left && bottom > top) {
			/* the colour over the bounds, masked by the inverted coverage of the path */
			gtk_snapshot_push_mask(snapshot, GSK_MASK_MODE_INVERTED_ALPHA);
			gtk_snapshot_append_fill(snapshot, path, rule, &(GdkRGBA){0, 0, 0, 1});
			gtk_snapshot_pop(snapshot);
			gtk_snapshot_append_color(snapshot, &color, &GRAPHENE_RECT_INIT(left, top, right - left, bottom - top));
			gtk_snapshot_pop(snapshot);
		}
	}
}

/* teslalinux: content drawn into its own snapshot (GskCanvas.tlBeginFiltered) put into the canvas
 * through a colour filter that is no colour matrix, over the content's rectangle (l, t, r, b);
 * the node reference stays the caller's */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1appendFiltered(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong node_ptr, jlong paint_ptr,
		jfloat l, jfloat t, jfloat r, jfloat b)
{
	if (!node_ptr)
		return;
	tl_paint_append_filtered(_PTR(snapshot_ptr), _PTR(paint_ptr), _PTR(node_ptr), &GRAPHENE_RECT_INIT(l, t, r - l, b - t));
}
