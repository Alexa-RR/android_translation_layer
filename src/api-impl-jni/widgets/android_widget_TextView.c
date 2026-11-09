#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"

#include "WrapperWidget.h"

#include "../generated_headers/android_widget_TextView.h"

static GtkLabel *box_get_label(JNIEnv *env, GtkWidget *box)
{
	GtkWidget *label = gtk_widget_get_last_child(GTK_WIDGET(box));
	if (!GTK_IS_LABEL(label))
		label = gtk_widget_get_prev_sibling(label);
	return GTK_LABEL(label);
}

JNIEXPORT jlong JNICALL Java_android_widget_TextView_native_1constructor(JNIEnv *env, jobject this, jobject context, jobject attrs)
{
	const char *text = attribute_set_get_string(env, attrs, "text", NULL);

	//	_SET_OBJ_FIELD(this, "text", "Ljava/lang/String;", _JSTRING(text)); //TODO: sadly this might be needed, but it's not atm

	GtkWidget *wrapper = g_object_ref(wrapper_widget_new());
	GtkWidget *box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 0);
	GtkWidget *label = gtk_label_new(text);
	gtk_label_set_wrap(GTK_LABEL(label), TRUE);
	gtk_label_set_wrap_mode(GTK_LABEL(label), PANGO_WRAP_WORD_CHAR); /* tl_text_styled: AOSP breaks overlong words */
	gtk_label_set_xalign(GTK_LABEL(label), 0.f);
	gtk_label_set_yalign(GTK_LABEL(label), 0.f);
	gtk_widget_set_hexpand(label, TRUE);
	gtk_box_append(GTK_BOX(box), label);
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), box);
	wrapper_widget_set_jobject(WRAPPER_WIDGET(wrapper), env, this);

	PangoAttrList *pango_attrs = pango_attr_list_new();
	pango_attr_list_insert(pango_attrs, pango_attr_font_features_new("tnum"));
	gtk_label_set_attributes(GTK_LABEL(label), pango_attrs);
	pango_attr_list_unref(pango_attrs);

	return _INTPTR(box);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setText(JNIEnv *env, jobject this, jobject charseq)
{
	const char *text = charseq ? (*env)->GetStringUTFChars(env, charseq, NULL) : NULL;
	atl_safe_gtk_label_set_text(box_get_label(env, _PTR(_GET_LONG_FIELD(this, "widget"))), text ?: "");
	if (text)
		(*env)->ReleaseStringUTFChars(env, charseq, text);
}

/* we kinda need per-widget css */
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setTextColor(JNIEnv *env, jobject this, jint color)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(_GET_LONG_FIELD(this, "widget")));

	GtkStyleContext *style_context = gtk_widget_get_style_context(widget);

	GtkCssProvider *old_provider = g_object_get_data(G_OBJECT(widget), "color_style_provider");
	if (old_provider)
		gtk_style_context_remove_provider(style_context, GTK_STYLE_PROVIDER(old_provider));

	GtkCssProvider *css_provider = gtk_css_provider_new();

	char *css_string = g_markup_printf_escaped("* { color: #%06x%02x; }", color & 0xFFFFFF, (color >> 24) & 0xFF);
	gtk_css_provider_load_from_string(css_provider, css_string);
	g_free(css_string);

	gtk_style_context_add_provider(style_context, GTK_STYLE_PROVIDER(css_provider), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	g_object_set_data(G_OBJECT(widget), "color_style_provider", css_provider);
}
#pragma GCC diagnostic pop

JNIEXPORT void JNICALL Java_android_widget_TextView_setTextSize(JNIEnv *env, jobject this, jfloat size)
{
	GtkLabel *label = box_get_label(env, _PTR(_GET_LONG_FIELD(this, "widget")));
	PangoAttrList *attrs;

	PangoAttrList *old_attrs = gtk_label_get_attributes(label);
	if (old_attrs)
		attrs = pango_attr_list_copy(old_attrs);
	else
		attrs = pango_attr_list_new();

	PangoAttribute *size_attr = pango_attr_size_new(size * PANGO_SCALE);
	pango_attr_list_change(attrs, size_attr);
	gtk_label_set_attributes(label, attrs);

	pango_attr_list_unref(attrs);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1set_1markup(JNIEnv *env, jobject this, jint value)
{
	GtkLabel *label = box_get_label(env, _PTR(_GET_LONG_FIELD(this, "widget")));

	gtk_label_set_use_markup(label, value);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setCompoundDrawables(JNIEnv *env, jobject this, jlong widget_ptr, jlong left, jlong top, jlong right, jlong bottom)
{
	GtkWidget *box = GTK_WIDGET(_PTR(widget_ptr));
	gtk_orientable_set_orientation(GTK_ORIENTABLE(box), (left || right) ? GTK_ORIENTATION_HORIZONTAL : GTK_ORIENTATION_VERTICAL);

	GdkPaintable *paintable = _PTR(left ?: top); // paintable before text
	GtkWidget *picture = gtk_widget_get_first_child(box);
	if (GTK_IS_PICTURE(picture)) {
		gtk_picture_set_paintable(GTK_PICTURE(picture), paintable);
	} else if (paintable) {
		picture = gtk_picture_new_for_paintable(paintable);
		gtk_widget_insert_after(picture, box, NULL);
	}

	paintable = _PTR(right ?: bottom); // paintable after text
	picture = gtk_widget_get_last_child(box);
	if (GTK_IS_PICTURE(picture)) {
		gtk_picture_set_paintable(GTK_PICTURE(picture), paintable);
	} else if (paintable) {
		picture = gtk_picture_new_for_paintable(paintable);
		gtk_widget_insert_before(picture, box, NULL);
	}
}

/* ---- teslalinux: AOSP text styling on the GTK label (tl_text_styled, native-text-textview.sh) ---- */
#include <string.h>

/* the widget that shows the text: the GtkLabel inside a TextView's box, or an EditText's GtkText */
static GtkWidget *tl_tv_target(GtkWidget *widget)
{
	if (!widget)
		return NULL;
	if (GTK_IS_BOX(widget)) {
		for (GtkWidget *c = gtk_widget_get_first_child(widget); c; c = gtk_widget_get_next_sibling(c)) {
			if (GTK_IS_LABEL(c))
				return c;
		}
		return NULL;
	}
	return widget;
}

/* the Pango attribute list builder lives in android_text_Layout.c (native-text-layout.sh) */
PangoAttrList *tl_text_attr_list(JNIEnv *env, jstring text, jintArray attrs_arr, jobjectArray strings, char **utf8_out);

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setStyledText(JNIEnv *env, jobject this, jlong widget_ptr, jstring text, jintArray attrs_arr, jobjectArray strings)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target)
		return;
	char *utf8 = NULL;
	PangoAttrList *list = tl_text_attr_list(env, text, attrs_arr, strings, &utf8);
	pango_attr_list_insert_before(list, pango_attr_font_features_new("tnum")); /* ATL's default */
	if (GTK_IS_LABEL(target)) {
		GtkLabel *label = GTK_LABEL(target);
		if (utf8)
			atl_safe_gtk_label_set_text(label, utf8); /* also turns markup off */
		gtk_label_set_attributes(label, list);
	} else if (GTK_IS_TEXT(target)) {
		gtk_text_set_attributes(GTK_TEXT(target), list);
	}
	pango_attr_list_unref(list);
	g_free(utf8);
}

/* ellipsize is a PangoEllipsizeMode; TextView passes NONE unless the lines are limited, as AOSP ellipsizes only then */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setLineConfig(JNIEnv *env, jobject this, jlong widget_ptr, jint max_lines, jint ellipsize, jboolean single_line)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_LABEL(target))
		return;
	GtkLabel *label = GTK_LABEL(target);
	gtk_label_set_wrap(label, !single_line);
	gtk_label_set_wrap_mode(label, PANGO_WRAP_WORD_CHAR);
	gtk_label_set_ellipsize(label, (PangoEllipsizeMode)CLAMP(ellipsize, PANGO_ELLIPSIZE_NONE, PANGO_ELLIPSIZE_END));
	gtk_label_set_lines(label, (ellipsize != PANGO_ELLIPSIZE_NONE && !single_line && max_lines > 0) ? max_lines : -1);
}

/* the height of the first `lines` lines of the label's text laid out `width` px wide (unlimited
 * lines, no ellipsis); lines past the end of the text count as high as the last one */
JNIEXPORT jint JNICALL Java_android_widget_TextView_native_1getLinesHeight(JNIEnv *env, jobject this, jlong widget_ptr, jint width, jint lines)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_LABEL(target) || lines <= 0)
		return -1;
	GtkLabel *label = GTK_LABEL(target);
	PangoLayout *layout = pango_layout_copy(gtk_label_get_layout(label));
	pango_layout_set_ellipsize(layout, PANGO_ELLIPSIZE_NONE);
	pango_layout_set_height(layout, -1);
	pango_layout_set_width(layout, (width > 0 && gtk_label_get_wrap(label)) ? width * PANGO_SCALE : -1);
	PangoLayoutIter *iter = pango_layout_get_iter(layout);
	int n = 0, bottom = 0, last = 0;
	do {
		PangoRectangle r;
		pango_layout_iter_get_line_extents(iter, NULL, &r);
		bottom = r.y + r.height;
		last = r.height;
		n++;
	} while (n < lines && pango_layout_iter_next_line(iter));
	pango_layout_iter_free(iter);
	if (n < lines)
		bottom += (lines - n) * last;
	g_object_unref(layout);
	return PANGO_PIXELS_CEIL(bottom);
}

/* a new PangoLayout with what the widget shows, at its allocated width (owned by android.text.Layout) */
JNIEXPORT jlong JNICALL Java_android_widget_TextView_native_1getLayout(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target)
		return 0;
	PangoLayout *layout = NULL;
	if (GTK_IS_LABEL(target)) {
		GtkLabel *label = GTK_LABEL(target);
		layout = pango_layout_copy(gtk_label_get_layout(label));
		int w = gtk_widget_get_width(target);
		if (w > 0 && (gtk_label_get_wrap(label) || gtk_label_get_ellipsize(label) != PANGO_ELLIPSIZE_NONE))
			pango_layout_set_width(layout, w * PANGO_SCALE);
	} else if (GTK_IS_TEXT(target)) {
		layout = gtk_widget_create_pango_layout(target, gtk_editable_get_text(GTK_EDITABLE(target)));
		PangoAttrList *attrs = gtk_text_get_attributes(GTK_TEXT(target));
		if (attrs)
			pango_layout_set_attributes(layout, attrs);
	}
	return _INTPTR(layout);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setCompoundPadding4(JNIEnv *env, jobject this, jlong widget_ptr, jint left, jint top, jint right, jint bottom)
{
	GtkWidget *child = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!child)
		return;
	gboolean rtl = gtk_widget_get_direction(child) == GTK_TEXT_DIR_RTL;
	gtk_widget_set_margin_start(child, MAX(0, rtl ? right : left));
	gtk_widget_set_margin_end(child, MAX(0, rtl ? left : right));
	gtk_widget_set_margin_top(child, MAX(0, top));
	gtk_widget_set_margin_bottom(child, MAX(0, bottom));
}

/* the wrapper calls View.draw (and so TextView.onDraw, which draws the compound drawables) only
 * while the TextView asks for it; see the tl_textview_java_hooks part in WrapperWidget.c */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setJavaDraw(JNIEnv *env, jobject this, jlong widget_ptr, jboolean enable)
{
	GtkWidget *parent = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr)));
	if (!parent || !WRAPPER_IS_WIDGET(parent))
		return;
	WrapperWidget *wrapper = WRAPPER_WIDGET(parent);
	if (enable) {
		if (!wrapper->draw_method) {
			wrapper->draw_method = _METHOD(_CLASS(this), "draw", "(Landroid/graphics/Canvas;)V");
			if (!wrapper->canvas) {
				jclass canvas_class = (*env)->FindClass(env, "android/atl/GskCanvas");
				jmethodID canvas_constructor = _METHOD(canvas_class, "<init>", "(J)V");
				wrapper->canvas = _REF((*env)->NewObject(env, canvas_class, canvas_constructor, 0));
				(*env)->DeleteLocalRef(env, canvas_class);
			}
			g_object_set_data(G_OBJECT(wrapper), "tl-textview-java-draw", GINT_TO_POINTER(1));
		}
	} else if (g_object_get_data(G_OBJECT(wrapper), "tl-textview-java-draw")) {
		wrapper->draw_method = NULL;
		g_object_set_data(G_OBJECT(wrapper), "tl-textview-java-draw", NULL);
	}
	gtk_widget_queue_draw(parent);
}

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
/* a per-widget CSS provider under `key` (NULL css removes it) */
static void tl_tv_css(GtkWidget *w, const char *key, const char *css)
{
	GtkStyleContext *sc = gtk_widget_get_style_context(w);
	GtkCssProvider *old = g_object_get_data(G_OBJECT(w), key);
	if (old)
		gtk_style_context_remove_provider(sc, GTK_STYLE_PROVIDER(old));
	if (!css) {
		g_object_set_data(G_OBJECT(w), key, NULL);
		return;
	}
	GtkCssProvider *p = gtk_css_provider_new();
	gtk_css_provider_load_from_string(p, css);
	gtk_style_context_add_provider(sc, GTK_STYLE_PROVIDER(p), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	g_object_set_data_full(G_OBJECT(w), key, p, g_object_unref);
}
#pragma GCC diagnostic pop

static char *tl_tv_rgba(jint color)
{
	char alpha[G_ASCII_DTOSTR_BUF_SIZE];
	g_ascii_formatd(alpha, sizeof(alpha), "%.3f", ((color >> 24) & 0xff) / 255.0);
	return g_strdup_printf("rgba(%d,%d,%d,%s)", (color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff, alpha);
}

/* AOSP Paint.setShadowLayer on the text: CSS text-shadow (radius 0 removes it) */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setShadow(JNIEnv *env, jobject this, jlong widget_ptr, jfloat radius, jfloat dx, jfloat dy, jint color)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target)
		return;
	if (radius <= 0) {
		tl_tv_css(target, "tl-text-shadow", NULL);
		return;
	}
	char x[G_ASCII_DTOSTR_BUF_SIZE], y[G_ASCII_DTOSTR_BUF_SIZE], r[G_ASCII_DTOSTR_BUF_SIZE];
	g_ascii_formatd(x, sizeof(x), "%.2f", dx);
	g_ascii_formatd(y, sizeof(y), "%.2f", dy);
	g_ascii_formatd(r, sizeof(r), "%.2f", radius);
	char *rgba = tl_tv_rgba(color);
	char *css = g_strdup_printf("* { text-shadow: %spx %spx %spx %s; }", x, y, r, rgba);
	tl_tv_css(target, "tl-text-shadow", css);
	g_free(css);
	g_free(rgba);
}

/* an EditText's hint: GtkText draws it with its placeholder label */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setHintColor(JNIEnv *env, jobject this, jlong widget_ptr, jint color)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_TEXT(target))
		return;
	char *rgba = tl_tv_rgba(color);
	char *css = g_strdup_printf("* { color: %s; opacity: 1; }", rgba);
	for (GtkWidget *c = gtk_widget_get_first_child(target); c; c = gtk_widget_get_next_sibling(c)) {
		if (GTK_IS_LABEL(c))
			tl_tv_css(c, "tl-hint-color", css);
	}
	g_free(css);
	g_free(rgba);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setCursorVisible(JNIEnv *env, jobject this, jlong widget_ptr, jboolean visible)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_TEXT(target))
		return;
	tl_tv_css(target, "tl-cursor-visible", visible ? NULL : "* { caret-color: transparent; }");
}

/* an InputFilter.LengthFilter on an EditText: GtkText refuses longer input itself (0: unlimited) */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setMaxLength(JNIEnv *env, jobject this, jlong widget_ptr, jint max)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_TEXT(target))
		return;
	gtk_text_set_max_length(GTK_TEXT(target), CLAMP(max, 0, 65535));
}

/* PasswordTransformationMethod on an EditText: GtkText shows its invisible char */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setTextVisibility(JNIEnv *env, jobject this, jlong widget_ptr, jboolean visible)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_TEXT(target))
		return;
	gtk_text_set_visibility(GTK_TEXT(target), visible);
	if (!visible)
		gtk_text_set_invisible_char(GTK_TEXT(target), 0x2022); /* AOSP PasswordTransformationMethod's DOT */
}

/* ---- teslalinux: android.widget.Magnifier shows a view's live rendering (tl_magnifier) ---- */
JNIEXPORT jlong JNICALL Java_android_widget_Magnifier_native_1widgetPaintable(JNIEnv *env, jclass clazz, jlong widget_ptr)
{
	GtkWidget *w = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *wrapper = gtk_widget_get_parent(w);
	return _INTPTR(gtk_widget_paintable_new((wrapper && WRAPPER_IS_WIDGET(wrapper)) ? wrapper : w));
}

JNIEXPORT void JNICALL Java_android_widget_Magnifier_native_1unref(JNIEnv *env, jclass clazz, jlong ptr)
{
	if (ptr)
		g_object_unref(_PTR(ptr));
}

/* ---- teslalinux: compound padding (tl_textview_compound) ---- */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setCompoundPadding(JNIEnv *env, jobject this, jlong widget_ptr, jint left, jint right)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *child = GTK_IS_BOX(widget) ? gtk_widget_get_first_child(widget) : widget;
	if (!child)
		return;
	gboolean rtl = gtk_widget_get_direction(child) == GTK_TEXT_DIR_RTL;
	gtk_widget_set_margin_start(child, rtl ? right : left);
	gtk_widget_set_margin_end(child, rtl ? left : right);
}

/* ---- teslalinux: text gravity (tl_textview_gravity) ---- */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setGravity(JNIEnv *env, jobject this, jlong widget_ptr, jint gravity)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *child = GTK_IS_BOX(widget) ? gtk_widget_get_first_child(widget) : widget;
	if (!child)
		return;
	int h = gravity & 0x7;                      /* Gravity.HORIZONTAL_GRAVITY_MASK: CENTER_HORIZONTAL 1, LEFT 3, RIGHT 5 */
	int relative = (gravity & 0x800000) != 0;   /* Gravity.RELATIVE_LAYOUT_DIRECTION: START/END */
	int v = gravity & 0x70;                     /* Gravity.VERTICAL_GRAVITY_MASK: CENTER_VERTICAL 0x10, TOP 0x30, BOTTOM 0x50 */
	gboolean rtl = gtk_widget_get_direction(child) == GTK_TEXT_DIR_RTL;
	float xalign;
	if (h == 1)
		xalign = 0.5f;
	else if (relative)
		xalign = (h == 5) ? 1.f : 0.f;          /* GTK mirrors xalign for RTL, like START/END */
	else
		xalign = ((h == 5) != rtl) ? 1.f : 0.f; /* absolute LEFT/RIGHT */
	float yalign = v == 0x10 ? 0.5f : (v == 0x50 ? 1.f : 0.f);
	GtkJustification justify = h == 1 ? GTK_JUSTIFY_CENTER : ((xalign == 1.f) != rtl ? GTK_JUSTIFY_RIGHT : GTK_JUSTIFY_LEFT);
	if (GTK_IS_LABEL(child)) {
		gtk_label_set_xalign(GTK_LABEL(child), xalign);
		gtk_label_set_yalign(GTK_LABEL(child), yalign);
		gtk_label_set_justify(GTK_LABEL(child), justify);
	} else if (GTK_IS_TEXT_VIEW(child)) {
		gtk_text_view_set_justification(GTK_TEXT_VIEW(child), justify);
	} else if (g_object_class_find_property(G_OBJECT_GET_CLASS(child), "xalign")) {
		g_object_set(child, "xalign", xalign, NULL); /* GtkText / GtkEntry */
	}
}
