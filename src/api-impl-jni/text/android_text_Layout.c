#include <gtk/gtk.h>
#include <pango/pango.h>

#include "../defines.h"
#include "../util.h"
#include "../generated_headers/android_text_Layout.h"
struct AndroidPaint;
void tl_paint_apply_attrs(struct AndroidPaint *paint, PangoLayout *layout);
#include "../graphics/AndroidPaint.h"

extern GtkWidget *window;


/* ---- teslalinux: UTF-16 offsets and Pango attributes (tl_text_layout, native-text-layout.sh) ---- */
#include <string.h>

/* UTF-16 to UTF-8 with surrogate pairs decoded; map (n + 1 entries, may be NULL) gets the byte
 * offset of every UTF-16 unit, map[n] the length */
char *tl_text_utf16_to_utf8(const jchar *s, jsize n, int *map)
{
	char *out = g_malloc((gsize)n * 3 + 1);
	int o = 0;
	for (jsize i = 0; i < n; i++) {
		if (map)
			map[i] = o;
		gunichar c = s[i];
		if (c >= 0xD800 && c < 0xDC00 && i + 1 < n && s[i + 1] >= 0xDC00 && s[i + 1] < 0xE000) {
			c = 0x10000 + ((c - 0xD800) << 10) + (s[i + 1] - 0xDC00);
			o += g_unichar_to_utf8(c, out + o);
			i++;
			if (map)
				map[i] = o;
			continue;
		}
		if ((c >= 0xD800 && c < 0xE000) || c == 0)
			c = 0xFFFD; /* a lone surrogate or NUL has no UTF-8 form; one unit stays one character */
		o += g_unichar_to_utf8(c, out + o);
	}
	if (map)
		map[n] = o;
	out[o] = 0;
	return out;
}

/* UTF-16 index -> byte index in the layout's UTF-8 text (past the end: the length) */
static int tl_u16_to_byte(const char *text, int off)
{
	const char *p = text;
	int u = 0;
	while (*p && u < off) {
		gunichar c = g_utf8_get_char(p);
		u += c >= 0x10000 ? 2 : 1;
		p = g_utf8_next_char(p);
	}
	return p - text;
}

/* byte index -> UTF-16 index */
static int tl_byte_to_u16(const char *text, int idx)
{
	const char *p = text, *end = text + idx;
	int u = 0;
	while (*p && p < end) {
		gunichar c = g_utf8_get_char(p);
		u += c >= 0x10000 ? 2 : 1;
		p = g_utf8_next_char(p);
	}
	return u;
}

static void tl_layout_set_text_utf16(JNIEnv *env, PangoLayout *layout, jstring text)
{
	jsize n = text ? (*env)->GetStringLength(env, text) : 0;
	const jchar *chars = n ? (*env)->GetStringChars(env, text, NULL) : NULL;
	char *utf8 = tl_text_utf16_to_utf8(chars, n, NULL);
	if (chars)
		(*env)->ReleaseStringChars(env, text, chars);
	pango_layout_set_text(layout, utf8, -1);
	g_free(utf8);
}

/* attribute kinds, as android.text.TlTextAttrs encodes them (type, start, end, value); start and
 * end are UTF-16 offsets (end -1: to the end of the text) */
enum {
	TL_A_SIZE = 1,        /* float px */
	TL_A_FG = 2,          /* ARGB */
	TL_A_BG = 3,          /* ARGB */
	TL_A_WEIGHT = 4,      /* 100..1000 */
	TL_A_ITALIC = 5,      /* 0/1 */
	TL_A_UNDERLINE = 6,   /* 0/1 */
	TL_A_STRIKE = 7,      /* 0/1 */
	TL_A_FAMILY = 8,      /* index into strings */
	TL_A_LETTERSP = 9,    /* float px */
	TL_A_RISE = 10,       /* float px, up */
	TL_A_SCALE = 11,      /* float factor */
	TL_A_LINEHEIGHT = 12, /* float px */
	TL_A_FEATURES = 13,   /* index into strings, CSS font-feature-settings syntax */
	TL_A_STRETCH = 14,    /* PangoStretch */
	TL_A_LANGUAGE = 15,   /* index into strings, BCP 47 */
	TL_A_VARIATIONS = 16, /* index into strings, Pango syntax "wght=700,wdth=80" */
};

/* the attribute list for `text` (may be NULL: whole-text attributes only); *utf8_out (may be
 * NULL) receives the text as UTF-8, to be g_free'd */
PangoAttrList *tl_text_attr_list(JNIEnv *env, jstring text, jintArray attrs_arr, jobjectArray strings, char **utf8_out)
{
	jsize n = 0;
	int *map = NULL;
	char *utf8 = NULL;
	if (text) {
		n = (*env)->GetStringLength(env, text);
		const jchar *chars = (*env)->GetStringChars(env, text, NULL);
		map = g_new(int, n + 1);
		utf8 = tl_text_utf16_to_utf8(chars, n, map);
		(*env)->ReleaseStringChars(env, text, chars);
	}
	PangoAttrList *list = pango_attr_list_new();
	jsize count = attrs_arr ? (*env)->GetArrayLength(env, attrs_arr) : 0;
	jint *a = count ? (*env)->GetIntArrayElements(env, attrs_arr, NULL) : NULL;
	jsize nstrings = strings ? (*env)->GetArrayLength(env, strings) : 0;
	for (jsize i = 0; a && i + 3 < count; i += 4) {
		int type = a[i], start = a[i + 1], end = a[i + 2];
		jint value = a[i + 3];
		float f;
		memcpy(&f, &value, sizeof(f));
		guint16 r = ((value >> 16) & 0xff) * 257, g = ((value >> 8) & 0xff) * 257, b = (value & 0xff) * 257;
		int alpha = (value >> 24) & 0xff;
		PangoAttribute *attr = NULL, *attr2 = NULL;
		const char *str = NULL;
		jstring jstr = NULL;
		if ((type == TL_A_FAMILY || type == TL_A_FEATURES || type == TL_A_LANGUAGE || type == TL_A_VARIATIONS)
		    && value >= 0 && value < nstrings) {
			jstr = (*env)->GetObjectArrayElement(env, strings, value);
			if (jstr)
				str = (*env)->GetStringUTFChars(env, jstr, NULL);
		}
		switch (type) {
			case TL_A_SIZE:
				if (f > 0)
					attr = pango_attr_size_new_absolute((int)(f * PANGO_SCALE + 0.5f));
				break;
			case TL_A_FG:
				attr = pango_attr_foreground_new(r, g, b);
				if (alpha != 0xff)
					attr2 = pango_attr_foreground_alpha_new(MAX(1, alpha * 257));
				break;
			case TL_A_BG:
				if (alpha) {
					attr = pango_attr_background_new(r, g, b);
					if (alpha != 0xff)
						attr2 = pango_attr_background_alpha_new(alpha * 257);
				}
				break;
			case TL_A_WEIGHT:
				attr = pango_attr_weight_new(CLAMP(value, 100, 1000));
				break;
			case TL_A_ITALIC:
				attr = pango_attr_style_new(value ? PANGO_STYLE_ITALIC : PANGO_STYLE_NORMAL);
				break;
			case TL_A_UNDERLINE:
				attr = pango_attr_underline_new(value ? PANGO_UNDERLINE_SINGLE : PANGO_UNDERLINE_NONE);
				break;
			case TL_A_STRIKE:
				attr = pango_attr_strikethrough_new(value != 0);
				break;
			case TL_A_FAMILY:
				if (str && *str)
					attr = pango_attr_family_new(str);
				break;
			case TL_A_LETTERSP:
				attr = pango_attr_letter_spacing_new((int)(f * PANGO_SCALE));
				break;
			case TL_A_RISE:
				attr = pango_attr_rise_new((int)(f * PANGO_SCALE));
				break;
			case TL_A_SCALE:
				if (f > 0)
					attr = pango_attr_scale_new(f);
				break;
			case TL_A_LINEHEIGHT:
				if (f > 0)
					attr = pango_attr_line_height_new_absolute((int)(f * PANGO_SCALE + 0.5f));
				break;
			case TL_A_FEATURES:
				if (str && *str)
					attr = pango_attr_font_features_new(str);
				break;
			case TL_A_STRETCH:
				attr = pango_attr_stretch_new((PangoStretch)CLAMP(value, PANGO_STRETCH_ULTRA_CONDENSED, PANGO_STRETCH_ULTRA_EXPANDED));
				break;
			case TL_A_LANGUAGE:
				if (str && *str)
					attr = pango_attr_language_new(pango_language_from_string(str));
				break;
			case TL_A_VARIATIONS:
				if (str && *str) {
					PangoFontDescription *desc = pango_font_description_new();
					pango_font_description_set_variations(desc, str);
					attr = pango_attr_font_desc_new(desc);
					pango_font_description_free(desc);
				}
				break;
		}
		if (str)
			(*env)->ReleaseStringUTFChars(env, jstr, str);
		if (jstr)
			(*env)->DeleteLocalRef(env, jstr);
		guint s_idx = 0, e_idx = PANGO_ATTR_INDEX_TO_TEXT_END;
		if (map) {
			if (start > 0)
				s_idx = map[MIN(start, n)];
			if (end >= 0)
				e_idx = map[MIN(end, n)];
		}
		if (attr) {
			attr->start_index = s_idx;
			attr->end_index = e_idx;
			pango_attr_list_insert(list, attr);
		}
		if (attr2) {
			attr2->start_index = s_idx;
			attr2->end_index = e_idx;
			pango_attr_list_insert(list, attr2);
		}
	}
	if (a)
		(*env)->ReleaseIntArrayElements(env, attrs_arr, a, JNI_ABORT);
	g_free(map);
	if (utf8_out)
		*utf8_out = utf8;
	else
		g_free(utf8);
	return list;
}

JNIEXPORT jlong JNICALL Java_android_text_Layout_native_1constructor(JNIEnv *env, jobject object, jstring text, jlong paint, jint width)
{
	struct AndroidPaint *android_paint = _PTR(paint);
	PangoLayout *layout = pango_layout_new(gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, android_paint->font);
	tl_paint_apply_attrs(android_paint, layout);
	tl_layout_set_text_utf16(env, layout, text); /* tl_text_layout */
	pango_layout_set_width(layout, width == -1 ? -1 : width * PANGO_SCALE);
	return _INTPTR(layout);
}

JNIEXPORT void JNICALL Java_android_text_Layout_native_1set_1width(JNIEnv *env, jobject object, jlong layout, jint width)
{
	PangoLayout *pango_layout = _PTR(layout);
	pango_layout_set_width(pango_layout, width * PANGO_SCALE);
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1width(JNIEnv *env, jobject object, jlong layout)
{
	PangoLayout *pango_layout = _PTR(layout);
	return pango_layout_get_width(pango_layout) / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1height(JNIEnv *env, jobject object, jlong layout)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoRectangle ink_rect;
	PangoRectangle logical_rect;
	pango_layout_get_extents(pango_layout, &ink_rect, &logical_rect);
	return logical_rect.height / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1count(JNIEnv *env, jobject object, jlong layout)
{
	PangoLayout *pango_layout = _PTR(layout);
	return pango_layout_get_line_count(pango_layout);
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1start(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(pango_layout, line);
	int byte_index = pango_layout_line_get_start_index(pango_line);
	return tl_byte_to_u16(pango_layout_get_text(pango_layout), byte_index);
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1end(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(pango_layout, line);
	int byte_index = pango_layout_line_get_start_index(pango_line) + pango_layout_line_get_length(pango_line);
	return tl_byte_to_u16(pango_layout_get_text(pango_layout), byte_index);
}

static void tl_line_layout_extents(PangoLayout *pango_layout, int line, PangoRectangle *logical_rect)
{
	/* teslalinux: layout-relative logical extents of a line (pango_layout_line_get_extents is baseline-relative) */
	PangoLayoutIter *iter = pango_layout_get_iter(pango_layout);
	while (line-- > 0 && pango_layout_iter_next_line(iter))
		;
	pango_layout_iter_get_line_extents(iter, NULL, logical_rect);
	pango_layout_iter_free(iter);
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1top(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoRectangle logical_rect;
	tl_line_layout_extents(PANGO_LAYOUT(_PTR(layout)), line, &logical_rect);
	return logical_rect.y / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1bottom(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoRectangle logical_rect;
	tl_line_layout_extents(PANGO_LAYOUT(_PTR(layout)), line, &logical_rect);
	return (logical_rect.y + logical_rect.height) / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1left(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(PANGO_LAYOUT(_PTR(layout)), line);
	PangoRectangle logical_rect;
	pango_layout_line_get_extents(pango_line, &logical_rect, NULL);
	return logical_rect.x / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1right(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(PANGO_LAYOUT(_PTR(layout)), line);
	PangoRectangle logical_rect;
	pango_layout_line_get_extents(pango_line, &logical_rect, NULL);
	return (logical_rect.x + logical_rect.width) / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1width(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(PANGO_LAYOUT(_PTR(layout)), line);
	PangoRectangle logical_rect;
	pango_layout_line_get_extents(pango_line, &logical_rect, NULL);
	return logical_rect.width / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1baseline(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoLayoutIter *pango_iter = pango_layout_get_iter(pango_layout);
	while (line--)
		pango_layout_iter_next_line(pango_iter);

	return pango_layout_iter_get_baseline(pango_iter) / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1ascent(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(pango_layout, line);
	PangoRectangle logical_rect, ink_rect;
	pango_layout_line_get_extents(pango_line, &logical_rect, &ink_rect);
	return -PANGO_ASCENT(ink_rect) / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1descent(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(pango_layout, line);
	PangoRectangle logical_rect, ink_rect;
	pango_layout_line_get_extents(pango_line, &logical_rect, &ink_rect);
	return PANGO_DESCENT(ink_rect) / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1for_1vertical(JNIEnv *env, jobject object, jlong layout, jint y)
{
	PangoLayout *pango_layout = _PTR(layout);
	int index_, trailing;
	pango_layout_xy_to_index(pango_layout, 0, y * PANGO_SCALE, &index_, &trailing);
	int line, x_pos;
	pango_layout_index_to_line_x(pango_layout, index_, trailing, &line, &x_pos);
	return line;
}

JNIEXPORT void JNICALL Java_android_text_Layout_native_1set_1ellipsize(JNIEnv *env, jobject object, jlong layout, jint ellipsize_mode, jfloat ellipsize_width)
{
	PangoLayout *pango_layout = _PTR(layout);
	pango_layout_set_ellipsize(pango_layout, (PangoEllipsizeMode)ellipsize_mode);
	pango_layout_set_width(pango_layout, ellipsize_width * PANGO_SCALE);
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1ellipsis_1count(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayout *pango_layout = _PTR(layout);
	if (!pango_layout_is_ellipsized(pango_layout))
		return 0;
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(pango_layout, line);
	const char *text = pango_layout_get_text(pango_layout);
	for (GSList *l = pango_line ? pango_line->runs : NULL; l; l = l->next) {
		PangoGlyphItem *run = l->data;
		if (run && (run->item->analysis.flags & PANGO_ANALYSIS_FLAG_IS_ELLIPSIS))
			return tl_byte_to_u16(text, run->item->offset + run->item->length) - tl_byte_to_u16(text, run->item->offset);
	}
	return 0;
}

JNIEXPORT void JNICALL Java_android_text_Layout_native_1draw(JNIEnv *env, jobject object, jlong layout, jlong snapshot_ptr, jlong paint_ptr)
{
	PangoLayout *pango_layout = PANGO_LAYOUT(_PTR(layout));
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	struct AndroidPaint *android_paint = _PTR(paint_ptr);

	gtk_snapshot_append_layout(snapshot, pango_layout, &android_paint->color);
}

JNIEXPORT void JNICALL Java_android_text_Layout_native_1draw_1custom_1canvas(JNIEnv *env, jobject object, jlong layout, jobject canvas, jobject paint)
{
	PangoLayout *pango_layout = PANGO_LAYOUT(_PTR(layout));

	const gchar *text = pango_layout_get_text(pango_layout);
	/* tl_draw_lines_u16: the paragraph as one UTF-16 string; each line is its UTF-16 range */
	glong text_u16_len = 0;
	gunichar2 *text_u16 = g_utf8_to_utf16(text, -1, NULL, &text_u16_len, NULL);
	jstring text_jstr = (*env)->NewString(env, text_u16 ? text_u16 : (const gunichar2 *)"", text_u16 ? text_u16_len : 0);
	g_free(text_u16);
	PangoLayoutIter *pango_iter = pango_layout_get_iter(pango_layout);
	do {
		PangoLayoutLine *pango_line = pango_layout_iter_get_line_readonly(pango_iter);
		jint start = tl_byte_to_u16(text, pango_line->start_index);
		jint end = tl_byte_to_u16(text, pango_line->start_index + pango_line->length);
		if (end > start) {
			PangoRectangle logical;
			pango_layout_iter_get_line_extents(pango_iter, NULL, &logical);
			jfloat x = (float)logical.x / PANGO_SCALE;
			jfloat y = (float)pango_layout_iter_get_baseline(pango_iter) / PANGO_SCALE;
			(*env)->CallVoidMethod(env, canvas, handle_cache.canvas.drawText, text_jstr, start, end, x, y, paint);
			if ((*env)->ExceptionCheck(env)) {
				(*env)->ExceptionDescribe(env);
				break;
			}
		}
	} while (pango_layout_iter_next_line(pango_iter));
	(*env)->DeleteLocalRef(env, text_jstr);
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1for_1offset(JNIEnv *env, jclass class, jlong layout, jint offset)
{
	PangoLayout *pango_layout = _PTR(layout);
	int line;
	pango_layout_index_to_line_x(pango_layout, tl_u16_to_byte(pango_layout_get_text(pango_layout), offset), FALSE, &line, NULL);
	return line;
}

JNIEXPORT jfloat JNICALL Java_android_text_Layout_native_1get_1primary_1horizontal(JNIEnv *env, jclass class, jlong layout, jint offset)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoRectangle cursor_rect;
	pango_layout_get_cursor_pos(pango_layout, tl_u16_to_byte(pango_layout_get_text(pango_layout), offset), &cursor_rect, NULL);
	return (jfloat)cursor_rect.x / PANGO_SCALE;
}

JNIEXPORT jfloat JNICALL Java_android_text_Layout_native_1get_1secondary_1horizontal(JNIEnv *env, jclass class, jlong layout, jint offset)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoRectangle cursor_rect;
	pango_layout_get_cursor_pos(pango_layout, tl_u16_to_byte(pango_layout_get_text(pango_layout), offset), NULL, &cursor_rect);
	return (jfloat)cursor_rect.x / PANGO_SCALE;
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1offset_1for_1horizontal(JNIEnv *env, jclass class, jlong layout, jint line, jfloat x)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(pango_layout, line);
	int index, trailing;
	pango_layout_line_x_to_index(pango_line, x * PANGO_SCALE, &index, &trailing);
	const char *text = pango_layout_get_text(pango_layout);
	while (trailing-- > 0 && text[index])
		index = g_utf8_next_char(text + index) - text; /* AOSP: the offset nearest to x */
	return tl_byte_to_u16(text, index);
}

JNIEXPORT jfloat JNICALL Java_android_text_Layout_native_1get_1desired_1width(JNIEnv *env, jclass class, jlong layout)
{
	PangoLayout *pango_layout = _PTR(layout);
	int width;
	pango_layout_get_size(pango_layout, &width, NULL);
	return (jfloat)width / PANGO_SCALE;
}

JNIEXPORT void JNICALL Java_android_text_Layout_native_1free(JNIEnv *env, jclass class, jlong layout)
{
	PangoLayout *pango_layout = _PTR(layout);
	g_object_unref(pango_layout);
}

/* ---- teslalinux: tl_text_layout natives ---- */
JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1ellipsis_1start(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(pango_layout, line);
	if (!pango_line || !pango_layout_is_ellipsized(pango_layout))
		return 0;
	const char *text = pango_layout_get_text(pango_layout);
	for (GSList *l = pango_line->runs; l; l = l->next) {
		PangoGlyphItem *run = l->data;
		if (run && (run->item->analysis.flags & PANGO_ANALYSIS_FLAG_IS_ELLIPSIS))
			return tl_byte_to_u16(text, run->item->offset) - tl_byte_to_u16(text, pango_line->start_index);
	}
	return 0;
}

JNIEXPORT void JNICALL Java_android_text_Layout_native_1set_1text_1attrs(JNIEnv *env, jobject object, jlong layout, jstring text, jintArray attrs, jobjectArray strings)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoAttrList *list = tl_text_attr_list(env, text, attrs, strings, NULL);
	pango_layout_set_attributes(pango_layout, list);
	pango_attr_list_unref(list);
}

JNIEXPORT void JNICALL Java_android_text_Layout_native_1set_1max_1lines(JNIEnv *env, jobject object, jlong layout, jint lines)
{
	pango_layout_set_height(PANGO_LAYOUT(_PTR(layout)), lines > 0 ? -lines : -1);
}

/* Layout.Alignment ordinal: NORMAL, OPPOSITE, CENTER, LEFT, RIGHT. Pango's LEFT/RIGHT follow each
 * paragraph's direction (auto-dir), which is AOSP's NORMAL/OPPOSITE. */
JNIEXPORT void JNICALL Java_android_text_Layout_native_1set_1alignment(JNIEnv *env, jobject object, jlong layout, jint align)
{
	PangoAlignment a = PANGO_ALIGN_LEFT;
	switch (align) {
		case 1: a = PANGO_ALIGN_RIGHT; break;
		case 2: a = PANGO_ALIGN_CENTER; break;
		case 4: a = PANGO_ALIGN_RIGHT; break;
		default: a = PANGO_ALIGN_LEFT; break;
	}
	pango_layout_set_alignment(PANGO_LAYOUT(_PTR(layout)), a);
}
