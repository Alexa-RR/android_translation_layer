#include <glib.h>
#include <gsk/gsk.h>
#include <gtk/gtk.h>

#include "../defines.h"
#include "AndroidPaint.h"
#include "pango/pango-font.h"

#include "../generated_headers/android_graphics_Paint.h"

JNIEXPORT jlong JNICALL Java_android_graphics_Paint_native_1create(JNIEnv *env, jclass clazz)
{
	struct AndroidPaint *paint = g_new0(struct AndroidPaint, 1);
	paint->color.alpha = 1.f;
	paint->gsk_stroke = gsk_stroke_new(1);
	paint->font = pango_font_description_new();
	paint->is_fill = true;
	return _INTPTR(paint);
}

JNIEXPORT jlong JNICALL Java_android_graphics_Paint_native_1clone(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	struct AndroidPaint *clone = g_memdup2(paint, sizeof(struct AndroidPaint));
	clone->gsk_stroke = gsk_stroke_copy(paint->gsk_stroke);
	clone->font = pango_font_description_copy(paint->font);
	return _INTPTR(clone);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1recycle(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gsk_stroke_free(paint->gsk_stroke);
	pango_font_description_free(paint->font);
	g_free(paint);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1color(JNIEnv *env, jclass clazz, jlong paint_ptr, jint color)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->color.red = ((color >> 16) & 0xFF) / 255.f;
	paint->color.green = ((color >> 8) & 0xFF) / 255.f;
	paint->color.blue = ((color >> 0) & 0xFF) / 255.f;
	paint->color.alpha = ((color >> 24) & 0xFF) / 255.f;
}

JNIEXPORT jint JNICALL Java_android_graphics_Paint_native_1get_1color(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return ((int)(paint->color.red * 0xFF) << 16) + ((int)(paint->color.green * 0xFF) << 8) + ((int)(paint->color.blue * 0xFF) << 0) + ((int)(paint->color.alpha * 0xFF) << 24);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1alpha(JNIEnv *env, jclass clazz, jlong paint_ptr, jint alpha)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->color.alpha = (alpha & 0xFF) / 255.f;
}

JNIEXPORT int JNICALL Java_android_graphics_Paint_native_1get_1alpha(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return (int)(paint->color.alpha * 0xFF);
}

#define STYLE_FILL            0
#define STYLE_STROKE          1
#define STYLE_FILL_AND_STROKE 2

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1style(JNIEnv *env, jclass clazz, jlong paint_ptr, jint style)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->is_fill = style == STYLE_FILL || style == STYLE_FILL_AND_STROKE;
	paint->is_stroke = style == STYLE_STROKE || style == STYLE_FILL_AND_STROKE;
}

JNIEXPORT jint JNICALL Java_android_graphics_Paint_native_1get_1style(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (paint->is_fill && paint->is_stroke)
		return STYLE_FILL_AND_STROKE;
	else if (paint->is_fill)
		return STYLE_FILL;
	else
		return STYLE_STROKE;
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1stroke_1width(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloat width)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	/* TODO: width of 0 means "single pixel width, no matter what",
	 * meanwile width of 1 should care about scaling;
	 * The problem is, 0 is not a valid value for gsk_stroke_set_line_width,
	 * so at least change it to 1 for now */
	if (width == 0)
		width = 1;
	gsk_stroke_set_line_width(paint->gsk_stroke, width);
}

JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1get_1stroke_1width(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return gsk_stroke_get_line_width(paint->gsk_stroke);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1stroke_1cap(JNIEnv *env, jclass clazz, jlong paint_ptr, jint cap)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gsk_stroke_set_line_cap(paint->gsk_stroke, cap);
}

JNIEXPORT jint JNICALL Java_android_graphics_Paint_native_1get_1stroke_1cap(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return gsk_stroke_get_line_cap(paint->gsk_stroke);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1stroke_1join(JNIEnv *env, jclass clazz, jlong paint_ptr, jint join)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gsk_stroke_set_line_join(paint->gsk_stroke, join);
}

JNIEXPORT jint JNICALL Java_android_graphics_Paint_native_1get_1stroke_1join(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return gsk_stroke_get_line_join(paint->gsk_stroke);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1text_1size(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloat size)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	pango_font_description_set_absolute_size(paint->font, roundf(size * PANGO_SCALE));
}

JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1get_1text_1size(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return (jfloat)pango_font_description_get_size(paint->font) / PANGO_SCALE;
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1color_1filter(JNIEnv *env, jclass clazz, jlong paint_ptr, jint mode, jint color)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	/* clang-format off */
	graphene_matrix_init_from_float(&paint->color_matrix, (float[]){
		0, 0, 0, 0,
		0, 0, 0, 0,
		0, 0, 0, 0,
		0, 0, 0, ((color >> 24) & 0xFF) / 255.f,
	});
	/* clang-format on */
	graphene_vec4_init(&paint->color_offset, ((color >> 16) & 0xFF) / 255.f, ((color >> 8) & 0xFF) / 255.f, ((color >> 0) & 0xFF) / 255.f, 0);
	paint->use_color_filter = mode != -1;
}

extern GtkWidget *window;
PangoLayout *tl_paint_layout(struct AndroidPaint *paint, const char *utf8, int len);
void tl_paint_apply_attrs(struct AndroidPaint *paint, PangoLayout *layout);


JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1get_1text_1bounds(JNIEnv *env, jclass clazz, jlong paint_ptr, jstring text_ptr, jobject bounds)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	PangoLayout *layout = pango_layout_new(gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, paint->font);
	const char *str = (*env)->GetStringUTFChars(env, text_ptr, NULL);
	pango_layout_set_text(layout, str, -1);
	(*env)->ReleaseStringUTFChars(env, text_ptr, str);
	PangoRectangle rect;
	pango_layout_get_pixel_extents(layout, NULL, &rect);
	rect.y -= (float)pango_layout_get_baseline(layout) / PANGO_SCALE;
	if (paint->alignment == PANGO_ALIGN_CENTER)
		rect.x -= rect.width / 2.f;
	else if (paint->alignment == PANGO_ALIGN_RIGHT)
		rect.x -= rect.width;
	_SET_INT_FIELD(bounds, "left", rect.x);
	_SET_INT_FIELD(bounds, "top", rect.y);
	_SET_INT_FIELD(bounds, "right", rect.x + rect.width);
	_SET_INT_FIELD(bounds, "bottom", rect.y + rect.height);
	g_object_unref(layout);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1text_1align(JNIEnv *env, jclass clazz, jlong paint_ptr, jint align)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->alignment = align;
}

/* teslalinux: blend-mode colour filter, mode = PorterDuff.Mode ordinal (-1: no filter) */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1color_1filter_1mode(JNIEnv *env, jclass clazz, jlong paint_ptr, jint mode, jint color)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	const float ca = ((color >> 24) & 0xFF) / 255.f;
	const float c[3] = { ((color >> 16) & 0xFF) / 255.f, ((color >> 8) & 0xFF) / 255.f, ((color >> 0) & 0xFF) / 255.f };
	float diag[4] = { 0, 0, 0, ca }; /* SRC_IN, also the fallback */
	float off[4] = { c[0], c[1], c[2], 0 };
	switch (mode) {
	case -1:
		paint->use_color_filter = FALSE;
		return;
	case 0: /* CLEAR */
		diag[3] = 0; off[0] = off[1] = off[2] = 0;
		break;
	case 1: /* SRC */
		diag[3] = 0; off[3] = ca;
		break;
	case 2: /* DST */
		diag[0] = diag[1] = diag[2] = diag[3] = 1; off[0] = off[1] = off[2] = 0;
		break;
	case 3: /* SRC_OVER */
		if (ca >= 1.f) { diag[3] = 0; off[3] = 1; }
		break;
	case 6: /* DST_IN */
		diag[0] = diag[1] = diag[2] = 1; off[0] = off[1] = off[2] = 0;
		break;
	case 7: /* SRC_OUT */
		diag[3] = -ca; off[3] = ca;
		break;
	case 8: /* DST_OUT */
		diag[0] = diag[1] = diag[2] = 1; off[0] = off[1] = off[2] = 0; diag[3] = 1.f - ca;
		break;
	case 9: /* SRC_ATOP */
		for (int i = 0; i < 3; i++) { diag[i] = 1.f - ca; off[i] = ca * c[i]; }
		diag[3] = 1;
		break;
	case 14: /* MULTIPLY */
		for (int i = 0; i < 3; i++) { diag[i] = c[i]; off[i] = 0; }
		break;
	default: /* 5 SRC_IN and the non-linear modes (see the script header) */
		break;
	}
	/* clang-format off */
	graphene_matrix_init_from_float(&paint->color_matrix, (float[]){
		diag[0], 0, 0, 0,
		0, diag[1], 0, 0,
		0, 0, diag[2], 0,
		0, 0, 0, diag[3],
	});
	/* clang-format on */
	graphene_vec4_init(&paint->color_offset, off[0], off[1], off[2], off[3]);
	paint->use_color_filter = TRUE;
}

/* ---- teslalinux: text measurement (native-paint-metrics.sh) ---- */

/* The layout a paint would draw `utf8` with: same context, font and letter spacing as drawing. */
PangoLayout *tl_paint_layout(struct AndroidPaint *paint, const char *utf8, int len)
{
	PangoLayout *layout = pango_layout_new(gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, paint->font);
	tl_paint_apply_attrs(paint, layout);
	pango_layout_set_text(layout, utf8, len);
	return layout;
}

/* Applies paint attributes that are not part of the font description (letter spacing). */
void tl_paint_apply_attrs(struct AndroidPaint *paint, PangoLayout *layout)
{
	if (paint->letter_spacing != 0.0f) {
		PangoAttrList *attrs = pango_attr_list_new();
		double size = pango_font_description_get_size(paint->font); /* absolute size in Pango units */
		pango_attr_list_insert(attrs, pango_attr_letter_spacing_new((int)(paint->letter_spacing * size)));
		pango_layout_set_attributes(layout, attrs);
		pango_attr_list_unref(attrs);
	}
}

/* Converts UTF-16 units to UTF-8, recording the byte offset of every unit (a low surrogate shares
 * its pair's offset); returns a malloc'd string, its length in *out_len. */
static char *tl_utf16_to_utf8(const jchar *chars, int count, int *offsets, int *out_len)
{
	GString *out = g_string_sized_new(count * 2 + 1);
	for (int i = 0; i < count; i++) {
		offsets[i] = out->len;
		gunichar c = chars[i];
		if (c >= 0xD800 && c <= 0xDBFF && i + 1 < count && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
			c = 0x10000 + ((c - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
			g_string_append_unichar(out, c);
			i++;
			offsets[i] = offsets[i - 1];
		} else {
			if (c >= 0xD800 && c <= 0xDFFF)
				c = 0xFFFD;
			g_string_append_unichar(out, c);
		}
	}
	*out_len = out->len;
	return g_string_free(out, FALSE);
}

/* AOSP Paint.getFontMetrics: top, ascent, descent, bottom, leading in pixels (ascent/top negative).
 * Pango exposes ascent, descent and the line height of the font; it has no separate maximum glyph
 * extents, so top/bottom equal ascent/descent and leading is the font's extra line gap. */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1get_1font_1metrics(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloatArray out)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	PangoContext *context = gtk_widget_get_pango_context(window);
	PangoFontMetrics *metrics = pango_context_get_metrics(context, paint->font, pango_context_get_language(context));
	float ascent = (float)pango_font_metrics_get_ascent(metrics) / PANGO_SCALE;
	float descent = (float)pango_font_metrics_get_descent(metrics) / PANGO_SCALE;
	float height = (float)pango_font_metrics_get_height(metrics) / PANGO_SCALE;
	pango_font_metrics_unref(metrics);
	float leading = height - ascent - descent;
	if (leading < 0)
		leading = 0;
	jfloat values[5] = { -ascent, -ascent, descent, descent, leading };
	(*env)->SetFloatArrayRegion(env, out, 0, 5, values);
}

/* Advance width of the text (logical extents, including letter spacing), in pixels. */
JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1measure_1text(JNIEnv *env, jclass clazz, jlong paint_ptr, jcharArray text, jint index, jint count)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (count <= 0)
		return 0;
	jchar *chars = (*env)->GetCharArrayElements(env, text, NULL);
	int *offsets = g_new(int, count);
	int len;
	char *utf8 = tl_utf16_to_utf8(chars + index, count, offsets, &len);
	(*env)->ReleaseCharArrayElements(env, text, chars, JNI_ABORT);
	g_free(offsets);
	PangoLayout *layout = tl_paint_layout(paint, utf8, len);
	PangoRectangle logical;
	pango_layout_get_extents(layout, NULL, &logical);
	g_object_unref(layout);
	g_free(utf8);
	return (float)logical.width / PANGO_SCALE;
}

/* AOSP getTextRunAdvances: the advance of every UTF-16 unit of text[index, index+count) into
 * advances[advances_index...]; a cluster's advance goes to its first unit, the others get 0.
 * Returns the total advance. */
JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1get_1text_1advances(JNIEnv *env, jclass clazz, jlong paint_ptr, jcharArray text, jint index, jint count, jfloatArray advances, jint advances_index)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (count <= 0)
		return 0;
	jchar *chars = (*env)->GetCharArrayElements(env, text, NULL);
	int *offsets = g_new(int, count + 1);
	int len;
	char *utf8 = tl_utf16_to_utf8(chars + index, count, offsets, &len);
	offsets[count] = len;
	(*env)->ReleaseCharArrayElements(env, text, chars, JNI_ABORT);
	PangoLayout *layout = tl_paint_layout(paint, utf8, len);
	int n_attrs = 0;
	const PangoLogAttr *attrs = pango_layout_get_log_attrs_readonly(layout, &n_attrs);
	jfloat *out = g_new0(jfloat, count);
	float total = 0;
	int char_index = 0;   /* index of the current byte offset in `attrs` (one entry per character) */
	int prev_offset = -1;
	for (int i = 0; i < count; i++) {
		int offset = offsets[i];
		if (offset == prev_offset)
			continue; /* low surrogate: belongs to the previous unit */
		if (prev_offset >= 0) {
			/* advance char_index over the UTF-8 characters between prev_offset and offset */
			for (const char *p = utf8 + prev_offset; p < utf8 + offset; p = g_utf8_next_char(p))
				char_index++;
		}
		prev_offset = offset;
		if (char_index < n_attrs && !attrs[char_index].is_cursor_position)
			continue; /* inside a grapheme cluster: AOSP gives its advance to the first unit */
		PangoRectangle pos;
		pango_layout_index_to_pos(layout, offset, &pos);
		float advance = (float)ABS(pos.width) / PANGO_SCALE;
		out[i] = advance;
		total += advance;
	}
	if (advances)
		(*env)->SetFloatArrayRegion(env, advances, advances_index, count, out);
	g_free(out);
	g_object_unref(layout);
	g_free(utf8);
	g_free(offsets);
	return total;
}

/* AOSP hasGlyph: true when the font renders the string as a single, known glyph. */
JNIEXPORT jboolean JNICALL Java_android_graphics_Paint_native_1has_1glyph(JNIEnv *env, jclass clazz, jlong paint_ptr, jstring text)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	const char *str = (*env)->GetStringUTFChars(env, text, NULL);
	PangoLayout *layout = tl_paint_layout(paint, str, -1);
	(*env)->ReleaseStringUTFChars(env, text, str);
	jboolean result = JNI_FALSE;
	PangoLayoutLine *line = pango_layout_get_line_readonly(layout, 0);
	if (line && line->runs && !line->runs->next) {
		PangoGlyphString *glyphs = ((PangoGlyphItem *)line->runs->data)->glyphs;
		if (glyphs->num_glyphs == 1 && !(glyphs->glyphs[0].glyph & PANGO_GLYPH_UNKNOWN_FLAG) && glyphs->glyphs[0].glyph != PANGO_GLYPH_EMPTY)
			result = JNI_TRUE;
	}
	g_object_unref(layout);
	return result;
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1letter_1spacing(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloat spacing)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->letter_spacing = spacing;
}
