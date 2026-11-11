#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdlib.h>
#include <stdio.h>
#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_view_View.h"

#include "WrapperWidget.h"
#include "src/api-impl-jni/views/AndroidLayout.h"

G_DEFINE_TYPE(WrapperWidget, wrapper_widget, GTK_TYPE_WIDGET)

typedef enum { ATL_ID = 1,
	       ATL_ID_NAME,
	       ATL_CLASS_NAME,
	       ATL_SUPER_CLASS_NAMES,
	       N_PROPERTIES } WrapperWidgetProperty;
static GParamSpec *wrapper_widget_properties[N_PROPERTIES] = {
	NULL,
};

static void wrapper_widget_set_property(GObject *object, guint property_id, const GValue *value, GParamSpec *pspec)
{
	switch ((WrapperWidgetProperty)property_id) {
		default:
			G_OBJECT_WARN_INVALID_PROPERTY_ID(object, property_id, pspec);
			break;
	}
}

static void wrapper_widget_get_property(GObject *object, guint property_id, GValue *value, GParamSpec *pspec)
{
	WrapperWidget *self = WRAPPER_WIDGET(object);

	JNIEnv *env = get_jni_env();

	jobject jobj = self->jobj;
	jclass class = _CLASS(jobj);

	switch ((WrapperWidgetProperty)property_id) {
		case ATL_ID: {
			jint id_jint = J__View__getId(env, jobj);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);

			const char *id = g_markup_printf_escaped("0x%08x", id_jint);
			g_value_set_string(value, id);
			break;
		}

		case ATL_ID_NAME: {
			jstring id_name_jstring = J__View__getIdName(env, jobj);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);

			const char *id_name = (*env)->GetStringUTFChars(env, id_name_jstring, NULL);
			g_value_set_string(value, id_name);

			(*env)->ReleaseStringUTFChars(env, id_name_jstring, id_name);
			break;
		}

		case ATL_CLASS_NAME: {
			jstring class_name_jstring = J__Class__getName(env, class);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);

			const char *class_name = (*env)->GetStringUTFChars(env, class_name_jstring, NULL);
			g_value_set_string(value, class_name);

			(*env)->ReleaseStringUTFChars(env, class_name_jstring, class_name);
			break;
		}

		case ATL_SUPER_CLASS_NAMES: {
			jstring super_classes_names_obj = J__View__getAllSuperClasses(env, jobj);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);

			const char *super_classes_names = (*env)->GetStringUTFChars(env, super_classes_names_obj, NULL);
			g_value_set_string(value, super_classes_names);

			(*env)->ReleaseStringUTFChars(env, super_classes_names_obj, super_classes_names);
			break;
		}

		default:
			G_OBJECT_WARN_INVALID_PROPERTY_ID(object, property_id, pspec);
			break;
	}
}

static void wrapper_widget_init(WrapperWidget *wrapper_widget)
{
}

static void wrapper_widget_dispose(GObject *wrapper_widget)
{
	GtkWidget *widget = GTK_WIDGET(wrapper_widget);
	GtkWidget *child = gtk_widget_get_first_child(widget);
	while (child) {
		GtkWidget *_child = gtk_widget_get_next_sibling(child);
		gtk_widget_unparent(child);
		child = _child;
	}
	WrapperWidget *wrapper = WRAPPER_WIDGET(wrapper_widget);
	if (wrapper->jvm) {
		JNIEnv *env;
		(*wrapper->jvm)->GetEnv(wrapper->jvm, (void **)&env, JNI_VERSION_1_6);
		if (wrapper->jobj)
			_WEAK_UNREF(wrapper->jobj);
		if (wrapper->canvas)
			_UNREF(wrapper->canvas);
	}
	G_OBJECT_CLASS(wrapper_widget_parent_class)->dispose(wrapper_widget);
}

GtkSizeRequestMode wrapper_widget_get_request_mode(GtkWidget *widget)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	return gtk_widget_get_request_mode(wrapper->child);
}

void wrapper_widget_measure(GtkWidget *widget, GtkOrientation orientation, int for_size, int *minimum, int *natural, int *minimum_baseline, int *natural_baseline)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	gtk_widget_measure(wrapper->child, orientation, for_size, minimum, natural, minimum_baseline, natural_baseline);
	if (orientation == GTK_ORIENTATION_HORIZONTAL && (wrapper->layout_width > 0)) {
		*minimum = *natural = wrapper->layout_width;
	} else if (orientation == GTK_ORIENTATION_VERTICAL && (wrapper->layout_height > 0)) {
		*minimum = *natural = wrapper->layout_height;
	}
}

void wrapper_widget_allocate(GtkWidget *widget, int width, int height, int baseline)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	if (!width && !height) {
		width = wrapper->real_width;
		height = wrapper->real_height;
	}
	GtkAllocation allocation = {
		.x = 0,
		.y = 0,
		.width = width,
		.height = height,
	};

	if (wrapper->have_custom_computeScroll) {
		// The child needs to know its size before calling computeScroll, so we allocate it twice.
		// second allocate will not trigger onLayout, because of unchanged size
		gtk_widget_size_allocate(wrapper->child, &allocation, baseline);

		JNIEnv *env;
		(*wrapper->jvm)->GetEnv(wrapper->jvm, (void **)&env, JNI_VERSION_1_6);
		J__View__computeScroll(env, wrapper->jobj);
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);
		allocation.x = -J__View__getScrollX(env, wrapper->jobj);
		allocation.y = -J__View__getScrollY(env, wrapper->jobj);
	}

	if (ATL_IS_ANDROID_LAYOUT(gtk_widget_get_layout_manager(wrapper->child))) {
		AndroidLayout *layout = ATL_ANDROID_LAYOUT(gtk_widget_get_layout_manager(wrapper->child));
		if (layout->real_width != width || layout->real_height != height) {
			layout->real_width = width;
			layout->real_height = height;
			if (!layout->needs_allocation)
				atl_safe_gtk_widget_queue_allocate(wrapper->child);
		}
		if (layout->needs_allocation)
			gtk_widget_size_allocate(wrapper->child, &allocation, baseline);
		else
			gtk_widget_size_allocate(wrapper->child, &(GtkAllocation){.x = allocation.x, .y = allocation.y}, baseline);
	} else {
		gtk_widget_size_allocate(wrapper->child, &allocation, baseline);
		/* tl_leaf_layout: AOSP delivers onSizeChanged/onLayout to every view */
		extern int snapshot_in_progress;
		if (wrapper->tl_layout && !snapshot_in_progress && wrapper->jobj) {
			JNIEnv *env = get_jni_env();
			J__View__layoutInternal(env, wrapper->jobj, width, height);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);
		}
	}
	if (wrapper->background)
		gtk_widget_size_allocate(wrapper->background, &allocation, baseline);
}

/* this is used to avoid queing layout changes in the middle of snapshotting */
int snapshot_in_progress = 0;
/* teslalinux: ATL_DEBUG_NODE support, see native-node-debug.sh */
static gboolean tl_node_debug(JNIEnv *env, WrapperWidget *wrapper)
{
	if ((*env)->ExceptionCheck(env))
		return FALSE;
	static const char *pattern = NULL;
	static int checked = 0;
	if (!checked) {
		pattern = getenv("ATL_DEBUG_NODE");
		checked = 1;
	}
	if (!pattern)
		return FALSE;
	gpointer cached = g_object_get_data(G_OBJECT(wrapper), "tl-node-debug");
	if (cached) {
		/* while /tmp/atl-capturenode exists every matching wrapper is captured again (tap.sh capture) */
		if (GPOINTER_TO_INT(cached) == 2 && g_file_test("/tmp/atl-capturenode", G_FILE_TEST_EXISTS))
			g_object_set_data(G_OBJECT(wrapper), "tl-node-debug-count", GINT_TO_POINTER(0));
		return GPOINTER_TO_INT(cached) == 2;
	}
	int match = 1;
	if (wrapper->jobj && !(*env)->IsSameObject(env, wrapper->jobj, NULL)) {
		jclass cls = (*env)->GetObjectClass(env, wrapper->jobj);
		jmethodID get_name = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, cls), "getName", "()Ljava/lang/String;");
		jstring name = (*env)->CallObjectMethod(env, cls, get_name);
		const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
		if (strstr(cname, pattern))
			match = 2;
		(*env)->ReleaseStringUTFChars(env, name, cname);
	}
	g_object_set_data(G_OBJECT(wrapper), "tl-node-debug", GINT_TO_POINTER(match));
	return match == 2;
}


/* teslalinux: tl_close_view_canvas (native-wrapper-canvas-close.sh): what the View's draw left open
 * on the widget's canvas is closed before the widget's snapshot goes on */
static void tl_close_view_canvas(JNIEnv *env, jobject canvas)
{
	static jmethodID close_method = NULL;
	if (!canvas)
		return;
	if (!close_method) {
		/* the widget canvas is an android.atl.GskCanvas (WrapperWidget creates it); its own class
		 * resolves the method without a class loader lookup from this native callback */
		jclass canvas_class = (*env)->GetObjectClass(env, canvas);
		close_method = (*env)->GetMethodID(env, canvas_class, "tlCloseRecording", "()V");
		(*env)->DeleteLocalRef(env, canvas_class);
		if ((*env)->ExceptionCheck(env)) {
			(*env)->ExceptionClear(env);
			return;
		}
	}
	if (!close_method)
		return;
	(*env)->CallVoidMethod(env, canvas, close_method);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void tl_node_debug_report(WrapperWidget *wrapper, GskRenderNode *node)
{
	int n = GPOINTER_TO_INT(g_object_get_data(G_OBJECT(wrapper), "tl-node-debug-count"));
	g_object_set_data(G_OBJECT(wrapper), "tl-node-debug-count", GINT_TO_POINTER(n + 1));
	if (!node) {
		fprintf(stderr, "[node] wrapper %p draw #%d produced no node%c", (void *)wrapper, n, 10);
		return;
	}
	graphene_rect_t b;
	gsk_render_node_get_bounds(node, &b);
	fprintf(stderr, "[node] wrapper %p draw #%d type=%d bounds=%.0f,%.0f %.0fx%.0f%c", (void *)wrapper, n, gsk_render_node_get_node_type(node), b.origin.x, b.origin.y, b.size.width, b.size.height, 10);
	if (n < 3) {
		char *path = g_strdup_printf("/tmp/atl-node-%p-%d.node", (void *)wrapper, n);
		GError *err = NULL;
		gsk_render_node_write_to_file(node, path, &err);
		if (err) {
			fprintf(stderr, "[node] could not write %s: %s%c", path, err->message, 10);
			g_error_free(err);
		}
		g_free(path);
	}
}

/* tl_layout_clip: AOSP ViewGroup.dispatchDraw clips the children to the padding box while
 * clipToPadding is set (FLAG_CLIP_TO_PADDING) and the group has padding (FLAG_PADDING_NOT_NULL) */
gboolean tl_wrapper_push_padding_clip(WrapperWidget *wrapper, GtkSnapshot *snapshot)
{
	if (!g_object_get_data(G_OBJECT(wrapper), "tl-padding-clip"))
		return FALSE;
	if (wrapper->real_width <= 0 || wrapper->real_height <= 0 || !wrapper->jobj)
		return FALSE;
	JNIEnv *env = get_jni_env();
	if ((*env)->IsSameObject(env, wrapper->jobj, NULL))
		return FALSE;
	static jfieldID f_left, f_top, f_right, f_bottom;
	if (!f_left) {
		f_left = (*env)->GetFieldID(env, _CACHED_CLASS(View), "paddingLeft", "I");
		f_top = (*env)->GetFieldID(env, _CACHED_CLASS(View), "paddingTop", "I");
		f_right = (*env)->GetFieldID(env, _CACHED_CLASS(View), "paddingRight", "I");
		f_bottom = (*env)->GetFieldID(env, _CACHED_CLASS(View), "paddingBottom", "I");
	}
	int left = (*env)->GetIntField(env, wrapper->jobj, f_left);
	int top = (*env)->GetIntField(env, wrapper->jobj, f_top);
	int right = (*env)->GetIntField(env, wrapper->jobj, f_right);
	int bottom = (*env)->GetIntField(env, wrapper->jobj, f_bottom);
	if (!left && !top && !right && !bottom)
		return FALSE;
	float width = wrapper->real_width - left - right;
	float height = wrapper->real_height - top - bottom;
	gtk_snapshot_push_clip(snapshot, &GRAPHENE_RECT_INIT(left, top, width > 0 ? width : 0, height > 0 ? height : 0));
	return TRUE;
}

static void wrapper_widget_snapshot(GtkWidget *widget, GdkSnapshot *snapshot)
{
	snapshot_in_progress++;

	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	g_object_set_data(G_OBJECT(wrapper), "tl-frame-pending", NULL); /* tl_pending_draw: drawn now */
	/* tl_layout_clip: the view clips to its bounds unless its parent has clipChildren off */
	const gboolean tl_clip_bounds = wrapper->real_height > 0 && wrapper->real_width > 0 && !g_object_get_data(G_OBJECT(wrapper), "tl-no-clip");
	if (tl_clip_bounds) {
		gtk_snapshot_push_clip(snapshot, &GRAPHENE_RECT_INIT(0, 0, wrapper->real_width, wrapper->real_height));
	}
	/* tl_view_clip: the view's clip bounds and outline clip (AOSP RenderNode clipping) */
	int tl_view_clips = 0;
	if (wrapper->tl_clip_bounds_set) {
		gtk_snapshot_push_clip(snapshot, &wrapper->tl_clip_bounds);
		tl_view_clips++;
	}
	if (wrapper->tl_outline_clip) {
		gtk_snapshot_push_rounded_clip(snapshot, &wrapper->tl_outline);
		tl_view_clips++;
	}
	if (wrapper->draw_method) {
		JNIEnv *env = get_jni_env();
		if (tl_node_debug(env, wrapper)) {
			GtkSnapshot *sub = gtk_snapshot_new();
			_SET_LONG_FIELD(wrapper->canvas, "snapshot", _INTPTR(sub));
			(*env)->CallVoidMethod(env, wrapper->jobj, wrapper->draw_method, wrapper->canvas);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);
			tl_close_view_canvas(env, wrapper->canvas);
			GskRenderNode *node = gtk_snapshot_free_to_node(sub);
			tl_node_debug_report(wrapper, node);
			if (node) {
				gtk_snapshot_append_node(snapshot, node);
				gsk_render_node_unref(node);
			}
		} else {
			_SET_LONG_FIELD(wrapper->canvas, "snapshot", _INTPTR(snapshot));
			(*env)->CallVoidMethod(env, wrapper->jobj, wrapper->draw_method, wrapper->canvas);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);
			tl_close_view_canvas(env, wrapper->canvas);
		}
	} else {
		GtkWidget *widget = &wrapper->parent_instance;
		GtkWidget *child = gtk_widget_get_first_child(widget);
		JNIEnv *env = get_jni_env();
		GtkSnapshot *target = snapshot;
		GtkSnapshot *sub = NULL;
		if (tl_node_debug(env, wrapper))
			target = sub = gtk_snapshot_new();
		while (child) {
			/* tl_layout_clip: the children widget of a group clips to the padding box */
			const gboolean tl_padding_clip = child == wrapper->child && tl_wrapper_push_padding_clip(wrapper, target);
			tl_snapshot_child(widget, child, target);
			if (tl_padding_clip)
				gtk_snapshot_pop(target);
			child = gtk_widget_get_next_sibling(child);
		}
		if (sub) {
			GskRenderNode *node = gtk_snapshot_free_to_node(sub);
			tl_node_debug_report(wrapper, node);
			if (node) {
				gtk_snapshot_append_node(snapshot, node);
				gsk_render_node_unref(node);
			}
		}
	}
	while (tl_view_clips-- > 0)
		gtk_snapshot_pop(snapshot);
	if (tl_clip_bounds) {
		gtk_snapshot_pop(snapshot);
	}

	snapshot_in_progress--;
}

/* tl_window_attach: AOSP dispatchAttachedToWindow/dispatchDetachedFromWindow happen when the view
 * enters or leaves a window's hierarchy; GTK's root/unroot are exactly that */
static void map_cb(WrapperWidget *wrapper);
static void unmap_cb(WrapperWidget *wrapper);
static gboolean wrapper_has_jobject(WrapperWidget *wrapper)
{
	if (!wrapper->jobj)
		return FALSE;
	JNIEnv *env = get_jni_env();
	return !(*env)->IsSameObject(env, wrapper->jobj, NULL);
}
static void wrapper_widget_root(GtkWidget *widget)
{
	GTK_WIDGET_CLASS(wrapper_widget_parent_class)->root(widget);
	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	if (wrapper_has_jobject(wrapper))
		map_cb(wrapper);
}
static void wrapper_widget_unroot(GtkWidget *widget)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	if (wrapper_has_jobject(wrapper))
		unmap_cb(wrapper);
	GTK_WIDGET_CLASS(wrapper_widget_parent_class)->unroot(widget);
}

static void wrapper_widget_class_init(WrapperWidgetClass *class)
{
	GObjectClass *object_class = G_OBJECT_CLASS(class);
	GtkWidgetClass *widget_class = GTK_WIDGET_CLASS(class);

	object_class->dispose = wrapper_widget_dispose;

	widget_class->get_request_mode = wrapper_widget_get_request_mode;
	widget_class->measure = wrapper_widget_measure;
	widget_class->size_allocate = wrapper_widget_allocate;
	widget_class->snapshot = wrapper_widget_snapshot;
	widget_class->root = wrapper_widget_root;     /* tl_window_attach */
	widget_class->unroot = wrapper_widget_unroot;

	object_class->set_property = wrapper_widget_set_property;
	object_class->get_property = wrapper_widget_get_property;

	// According to testing, these properties are not evaluated till we open the GtkInspector
	wrapper_widget_properties[ATL_ID] = g_param_spec_string("ATL-id", "ATL: ID", "ID of the component", "", G_PARAM_READABLE);
	wrapper_widget_properties[ATL_ID_NAME] = g_param_spec_string("ATL-id-name", "ATL: ID name", "Name of the ID of the component", "", G_PARAM_READABLE);
	wrapper_widget_properties[ATL_CLASS_NAME] = g_param_spec_string("ATL-class-name", "ATL: Class name", "Name of the class of the component", "", G_PARAM_READABLE);
	wrapper_widget_properties[ATL_SUPER_CLASS_NAMES] = g_param_spec_string("ATL-superclasses-names", "ATL: Super classes names", "Names of all the superclasses of the component class", "", G_PARAM_READABLE);

	g_object_class_install_properties(object_class, N_PROPERTIES, wrapper_widget_properties);
}

GtkWidget *wrapper_widget_new(void)
{
	return g_object_new(wrapper_widget_get_type(), NULL);
}

void wrapper_widget_set_child(WrapperWidget *parent, GtkWidget *child) // TODO: make sure there can only be one child
{
	gtk_widget_insert_before(child, GTK_WIDGET(parent), NULL);
	parent->child = child;
}

static guint queue_queue_redraw(GtkWidget *widget)
{
	gtk_widget_queue_draw(widget);
	g_object_unref(widget);
	return G_SOURCE_REMOVE;
}

void wrapper_widget_queue_draw(WrapperWidget *wrapper)
{
	if (wrapper->draw_method) {
		/* tl_pending_draw: a parent recording before the idle below finds the mark and snapshots this widget */
		g_object_set_data(G_OBJECT(wrapper), "tl-frame-pending", GINT_TO_POINTER(1));
		/* schedule the call to gtk_widget_queue_draw for a future event loop pass in case we're currently inside the snapshot */
		/* GTK+ uses G_PRIORITY_HIGH_IDLE + 10 for resizing operations, and G_PRIORITY_HIGH_IDLE + 20 for redrawing operations. */
		g_idle_add_full(G_PRIORITY_HIGH_IDLE + 20, G_SOURCE_FUNC(queue_queue_redraw), g_object_ref(wrapper), NULL);
	}

	if (wrapper->child)
		gtk_widget_queue_draw(wrapper->child);
	if (wrapper->have_custom_computeScroll) {
		atl_safe_gtk_widget_queue_allocate(GTK_WIDGET(wrapper));
	}
}

#define KEYCODE_0            7 /* KEYCODE_[1-9] = [8-16] */
#define KEYCODE_DPAD_UP       19
#define KEYCODE_DPAD_DOWN     20
#define KEYCODE_DPAD_LEFT     21
#define KEYCODE_DPAD_RIGHT    22
#define KEYCODE_A             29 /* KEYCODE_[B-Z] = [30-54] */
#define KEYCODE_COMMA         55
#define KEYCODE_PERIOD        56
#define KEYCODE_TAB           61
#define KEYCODE_SPACE         62
#define KEYCODE_ENTER         66
#define KEYCODE_DEL           67
#define KEYCODE_GRAVE         68
#define KEYCODE_MINUS         69
#define KEYCODE_EQUALS        70
#define KEYCODE_LEFT_BRACKET  71
#define KEYCODE_RIGHT_BRACKET 72
#define KEYCODE_BACKSLASH     73
#define KEYCODE_SEMICOLON     74
#define KEYCODE_APOSTROPHE    75
#define KEYCODE_SLASH         76
#define KEYCODE_AT            77
#define KEYCODE_PLUS          81
#define KEYCODE_PAGE_UP       92
#define KEYCODE_PAGE_DOWN     93
#define KEYCODE_ESCAPE        111
#define KEYCODE_FORWARD_DEL   112
#define KEYCODE_MOVE_HOME     122
#define KEYCODE_MOVE_END      123
#define KEYCODE_INSERT        124
#define KEYCODE_F1            131 /* KEYCODE_F[2-12] = [132-142] */
#define KEYCODE_NUMPAD_0      144 /* KEYCODE_NUMPAD_[1-9] = [145-153] */

int map_key_code(int key_code) /* tl_window_keys: shared with the window-level dispatch */
{
	if (key_code >= GDK_KEY_0 && key_code <= GDK_KEY_9)
		return key_code - GDK_KEY_0 + KEYCODE_0;
	if (key_code >= GDK_KEY_a && key_code <= GDK_KEY_z)
		return key_code - GDK_KEY_a + KEYCODE_A;
	else if (key_code >= GDK_KEY_A && key_code <= GDK_KEY_Z)
		return key_code - GDK_KEY_A + KEYCODE_A;
	else if (key_code >= GDK_KEY_F1 && key_code <= GDK_KEY_F12)
		return key_code - GDK_KEY_F1 + KEYCODE_F1;
	else if (key_code >= GDK_KEY_KP_0 && key_code <= GDK_KEY_KP_9)
		return key_code - GDK_KEY_KP_0 + KEYCODE_NUMPAD_0;
	switch (key_code) {
		case GDK_KEY_Up:
			return KEYCODE_DPAD_UP;
		case GDK_KEY_Down:
			return KEYCODE_DPAD_DOWN;
		case GDK_KEY_Left:
			return KEYCODE_DPAD_LEFT;
		case GDK_KEY_Right:
			return KEYCODE_DPAD_RIGHT;
		case GDK_KEY_comma:
			return KEYCODE_COMMA;
		case GDK_KEY_period:
			return KEYCODE_PERIOD;
		case GDK_KEY_Tab:
			return KEYCODE_TAB;
		case GDK_KEY_space:
			return KEYCODE_SPACE;
		case GDK_KEY_Return:
			return KEYCODE_ENTER;
		case GDK_KEY_BackSpace:
			return KEYCODE_DEL;
		case GDK_KEY_grave:
			return KEYCODE_GRAVE;
		case GDK_KEY_minus:
			return KEYCODE_MINUS;
		case GDK_KEY_equal:
			return KEYCODE_EQUALS;
		case GDK_KEY_bracketleft:
			return KEYCODE_LEFT_BRACKET;
		case GDK_KEY_bracketright:
			return KEYCODE_RIGHT_BRACKET;
		case GDK_KEY_backslash:
			return KEYCODE_BACKSLASH;
		case GDK_KEY_semicolon:
			return KEYCODE_SEMICOLON;
		case GDK_KEY_apostrophe:
			return KEYCODE_APOSTROPHE;
		case GDK_KEY_slash:
			return KEYCODE_SLASH;
		case GDK_KEY_at:
			return KEYCODE_AT;
		case GDK_KEY_plus:
			return KEYCODE_PLUS;
		case GDK_KEY_Page_Up:
			return KEYCODE_PAGE_UP;
		case GDK_KEY_Page_Down:
			return KEYCODE_PAGE_DOWN;
		case GDK_KEY_Escape:
			return KEYCODE_ESCAPE;
		case GDK_KEY_Delete:
			return KEYCODE_FORWARD_DEL;
		case GDK_KEY_Home:
			return KEYCODE_MOVE_HOME;
		case GDK_KEY_End:
			return KEYCODE_MOVE_END;
		case GDK_KEY_Insert:
			return KEYCODE_INSERT;
		default:
			return key_code;
	}
}

#define META_SHIFT_ON     (1 << 0)
#define META_ALT_ON       (1 << 1)
#define META_CTRL_ON      (1 << 12)
#define META_META_ON      (1 << 16)
#define META_CAPS_LOCK_ON (1 << 20)

int map_meta_state(GdkModifierType state)
{
	int meta_state = 0;
	if (state & GDK_SHIFT_MASK)
		meta_state |= META_SHIFT_ON;
	if (state & GDK_CONTROL_MASK)
		meta_state |= META_CTRL_ON;
	if (state & GDK_META_MASK)
		meta_state |= META_META_ON;
	if (state & GDK_ALT_MASK)
		meta_state |= META_ALT_ON;
	if (state & GDK_LOCK_MASK)
		meta_state |= META_CAPS_LOCK_ON;
	return meta_state;
}

#define ACTION_DOWN 0
#define ACTION_UP   1

/* teslalinux: ATL_DEBUG_KEY trace of key events forwarded to Java, see native-key-debug.sh */
static void tl_key_debug(JNIEnv *env, WrapperWidget *wrapper, guint keyval, gboolean ret)
{
	if ((*env)->ExceptionCheck(env))
		return;
	static int enabled = -1;
	if (enabled < 0)
		enabled = getenv("ATL_DEBUG_KEY") != NULL;
	if (!enabled)
		return;
	const char *cname = "?";
	jstring name = NULL;
	if (wrapper->jobj && !(*env)->IsSameObject(env, wrapper->jobj, NULL)) {
		jclass cls = (*env)->GetObjectClass(env, wrapper->jobj);
		jmethodID get_name = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, cls), "getName", "()Ljava/lang/String;");
		name = (*env)->CallObjectMethod(env, cls, get_name);
		cname = (*env)->GetStringUTFChars(env, name, NULL);
	}
	fprintf(stderr, "[key] wrapper %p %s dispatchKeyEvent keyval=0x%x -> %d%c", (void *)wrapper, cname, keyval, ret, 10);
	if (name)
		(*env)->ReleaseStringUTFChars(env, name, cname);
}

static gboolean on_key_pressed(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, WrapperWidget *wrapper)
{
	JNIEnv *env = get_jni_env();

	jobject key_event = J_new__KeyEvent(env, (jlong)0, (jlong)0, ACTION_DOWN, map_key_code(keyval), 0, map_meta_state(state));
	_SET_INT_FIELD(key_event, "unicodeValue", gdk_keyval_to_unicode(keyval));
	gboolean ret = J__View__dispatchKeyEvent(env, wrapper->jobj, key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	tl_key_debug(env, wrapper, keyval, ret);
	return ret;
}

static gboolean on_key_released(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, WrapperWidget *wrapper)
{
	JNIEnv *env = get_jni_env();

	jobject key_event = J_new__KeyEvent(env, (jlong)0, (jlong)0, ACTION_UP, map_key_code(keyval), 0, map_meta_state(state));
	_SET_INT_FIELD(key_event, "unicodeValue", gdk_keyval_to_unicode(keyval));
	gboolean ret = J__View__dispatchKeyEvent(env, wrapper->jobj, key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	tl_key_debug(env, wrapper, keyval, ret);
	return ret;
}

static void map_cb(WrapperWidget *wrapper)
{
	JNIEnv *env = get_jni_env();
	J__View__onAttachedToWindow(env, wrapper->jobj);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

GtkWidget *currently_unmapping = NULL;

static void unmap_cb(WrapperWidget *wrapper)
{
	JNIEnv *env = get_jni_env();
	currently_unmapping = wrapper->child;
	J__View__onDetachedFromWindow(env, wrapper->jobj);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	currently_unmapping = NULL;
}

void wrapper_widget_set_jobject(WrapperWidget *wrapper, JNIEnv *env, jobject jobj)
{
	JavaVM *jvm;
	(*env)->GetJavaVM(env, &jvm);
	wrapper->jvm = jvm;
	wrapper->jobj = _WEAK_REF(jobj);
	jmethodID on_draw_method = _METHOD(_CLASS(jobj), "onDraw", "(Landroid/graphics/Canvas;)V");
	jmethodID dispatch_draw_method = _METHOD(_CLASS(jobj), "dispatchDraw", "(Landroid/graphics/Canvas;)V");
	jmethodID draw_method = _METHOD(_CLASS(jobj), "draw", "(Landroid/graphics/Canvas;)V");
	/* tl_textview_java_hooks: TextView's own onDraw (compound drawables) and onTouchEvent (movement
	 * method) count as View's; a TextView asks for the Java path itself when it needs it
	 * (TextView.native_setJavaDraw, setClickable). Subclasses that override them again still count. */
	static jmethodID tl_tv_on_draw = NULL, tl_tv_on_touch = NULL, tl_view_on_size_changed = NULL;
	if (!tl_tv_on_draw) {
		jclass tv_class = (*env)->FindClass(env, "android/widget/TextView");
		tl_tv_on_draw = _METHOD(tv_class, "onDraw", "(Landroid/graphics/Canvas;)V");
		tl_tv_on_touch = _METHOD(tv_class, "onTouchEvent", "(Landroid/view/MotionEvent;)Z");
		(*env)->DeleteLocalRef(env, tv_class);
		tl_view_on_size_changed = _METHOD(_CACHED_CLASS(View), "onSizeChanged", "(IIII)V");
	}
	if (on_draw_method == tl_tv_on_draw)
		on_draw_method = _CACHED_METHOD(View__onDraw);
	if (on_draw_method != _CACHED_METHOD(View__onDraw) || draw_method != _CACHED_METHOD(View__draw) || dispatch_draw_method != _CACHED_METHOD(View__dispatchDraw)) {
		wrapper->draw_method = draw_method;
		wrapper->canvas = _REF(J_new__GskCanvas(env, 0));
	}

	/* tl_click_from_java: a performClick override (CompoundButton & co.) needs the touch path */
	jmethodID performClick_method = _METHOD(_CLASS(jobj), "performClick", "()Z");

	jmethodID ontouchevent_method = _METHOD(_CLASS(jobj), "onTouchEvent", "(Landroid/view/MotionEvent;)Z");
	if (ontouchevent_method == tl_tv_on_touch) /* tl_textview_java_hooks */
		ontouchevent_method = _CACHED_METHOD(View__onTouchEvent);
	jmethodID dispatchtouchevent_method = _METHOD(_CLASS(jobj), "dispatchTouchEvent", "(Landroid/view/MotionEvent;)Z");
	wrapper->custom_dispatch_touch = (dispatchtouchevent_method != _CACHED_METHOD(View__dispatchTouchEvent) && dispatchtouchevent_method != _CACHED_METHOD(ViewGroup__dispatchTouchEvent));
	wrapper->tl_touch_target = ontouchevent_method != _CACHED_METHOD(View__onTouchEvent) || wrapper->custom_dispatch_touch; /* tl_javawidget_contains */
	if (ontouchevent_method != _CACHED_METHOD(View__onTouchEvent) || wrapper->custom_dispatch_touch
	    || performClick_method != _CACHED_METHOD(View__performClick)) {
		_setOnTouchListener(env, jobj, GTK_WIDGET(wrapper));
	}

	/* tl_leaf_layout */
	jmethodID on_layout_method = _METHOD(_CLASS(jobj), "onLayout", "(ZIIII)V");
	jmethodID on_size_changed_method = _METHOD(_CLASS(jobj), "onSizeChanged", "(IIII)V");
	wrapper->tl_layout = on_layout_method != _CACHED_METHOD(View__onLayout)
	                     || on_size_changed_method != tl_view_on_size_changed;
	jmethodID computeScroll_method = _METHOD(_CLASS(jobj), "computeScroll", "()V");
	if (computeScroll_method != _CACHED_METHOD(View__computeScroll)) {
		wrapper->have_custom_computeScroll = true;
	}

	jmethodID dispatch_key_event_method = _METHOD(_CLASS(jobj), "dispatchKeyEvent", "(Landroid/view/KeyEvent;)Z");
	jmethodID on_key_down_method = _METHOD(_CLASS(jobj), "onKeyDown", "(ILandroid/view/KeyEvent;)Z");
	if (dispatch_key_event_method != _CACHED_METHOD(View__dispatchKeyEvent) || on_key_down_method != _CACHED_METHOD(View__onKeyDown)) {
		GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_event_controller_key_new());
		g_signal_connect(controller, "key-pressed", G_CALLBACK(on_key_pressed), wrapper);
		g_signal_connect(controller, "key-released", G_CALLBACK(on_key_released), wrapper);
		gtk_widget_add_controller(GTK_WIDGET(wrapper), controller);
		gtk_widget_set_focusable(GTK_WIDGET(wrapper), TRUE);
	}
	/* tl_window_attach: attach/detach follow the root/unroot vfuncs (in a window), not map/unmap */
	if (gtk_widget_get_root(GTK_WIDGET(wrapper)))
		map_cb(wrapper);
}

void wrapper_widget_set_layout_params(WrapperWidget *wrapper, int width, int height)
{
	wrapper->layout_width = width;
	wrapper->layout_height = height;
}

void wrapper_widget_set_background(WrapperWidget *wrapper, GdkPaintable *paintable)
{
	if (!paintable) {
		if (wrapper->background) {
			gtk_widget_unparent(wrapper->background);
			wrapper->background = NULL;
		}
		return;
	}
	if (!wrapper->background) {
		wrapper->background = gtk_picture_new();
		gtk_widget_insert_after(wrapper->background, GTK_WIDGET(wrapper), NULL);
	}
	gtk_picture_set_paintable(GTK_PICTURE(wrapper->background), paintable);
}

static gboolean on_touch_event_consume(GtkEventControllerLegacy *controller, GdkEvent *event)
{
	switch (gdk_event_get_event_type(event)) {
		case GDK_BUTTON_PRESS:
		case GDK_TOUCH_BEGIN:
		case GDK_BUTTON_RELEASE:
		case GDK_TOUCH_END:
		case GDK_MOTION_NOTIFY:
		case GDK_TOUCH_UPDATE:
			return TRUE;
		default:
			return FALSE;
	}
}

// Add default on touch listener, which just consumes all events to prevent bubbling to the parent
void wrapper_widget_consume_touch_events(WrapperWidget *wrapper)
{
	GtkEventController *old_controller = g_object_get_data(G_OBJECT(wrapper), "on_touch_listener");
	if (old_controller)
		return;
	GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_event_controller_legacy_new());
	g_signal_connect(controller, "event", G_CALLBACK(on_touch_event_consume), NULL);
	gtk_widget_add_controller(GTK_WIDGET(wrapper), controller);
	g_object_set_data(G_OBJECT(wrapper), "on_touch_listener", controller);
}

/*
 * native-zoffscreen-draw.sh: gtk_widget_snapshot_child(), and for a child of an unmapped parent (a
 * view tree that is not in a window, drawn into a Bitmap by View.draw) what GTK would draw if it were
 * mapped: the child's transform relative to the parent, its opacity, then its class's snapshot (a plain
 * container's children through this function again). Invisible children are skipped as GTK skips them.
 */
/* the child's transform as 2D GSK transforms (translate, rotate, skew, scale), which every GSK
 * renderer draws; a matrix3d transform node is one the cairo renderer cannot draw */
static void tl_offscreen_transform(GtkSnapshot *snapshot, const graphene_matrix_t *m)
{
	double sx, ky, kx, sy, tx, ty;
	if (!graphene_matrix_to_2d(m, &sx, &ky, &kx, &sy, &tx, &ty)) {
		gtk_snapshot_transform_matrix(snapshot, m);
		return;
	}
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(tx, ty));
	if (kx == 0 && ky == 0) {
		if (sx != 1 || sy != 1)
			gtk_snapshot_scale(snapshot, sx, sy);
		return;
	}
	const double a = sqrt(sx * sx + ky * ky);
	if (a == 0)
		return;
	const double theta = atan2(ky, sx);
	const double c = cos(theta), s = sin(theta);
	const double k = kx * c + sy * s;
	const double d = sy * c - kx * s;
	gtk_snapshot_rotate(snapshot, theta * 180.0 / G_PI);
	if (k != 0 && d != 0) {
		GskTransform *skew = gsk_transform_skew(NULL, atan(k / d) * 180.0 / G_PI, 0);
		gtk_snapshot_transform(snapshot, skew);
		gsk_transform_unref(skew);
	}
	gtk_snapshot_scale(snapshot, a, d);
}

void tl_snapshot_child(GtkWidget *parent, GtkWidget *child, GtkSnapshot *snapshot)
{
	if (gtk_widget_get_mapped(parent) || gtk_widget_get_mapped(child)) {
		gtk_widget_snapshot_child(parent, child, snapshot);
		return;
	}
	if (!gtk_widget_get_visible(child) || !gtk_widget_get_child_visible(child))
		return;
	const double opacity = gtk_widget_get_opacity(child);
	if (opacity <= 0)
		return;
	/* a view's widgets get their Android size in GTK's allocation pass, which never runs for a tree
	 * outside a window: allocate the view to its laid-out size (its children follow) */
	if (WRAPPER_IS_WIDGET(child)) {
		WrapperWidget *wrapper = WRAPPER_WIDGET(child);
		if (gtk_widget_get_width(child) != wrapper->real_width || gtk_widget_get_height(child) != wrapper->real_height) {
			graphene_point_t origin;
			if (!gtk_widget_compute_point(child, parent, &GRAPHENE_POINT_INIT(0, 0), &origin))
				origin = GRAPHENE_POINT_INIT(0, 0);
			gtk_widget_size_allocate(child, &(GtkAllocation){.x = origin.x, .y = origin.y, .width = wrapper->real_width, .height = wrapper->real_height}, -1);
		}
	}
	graphene_matrix_t transform;
	if (!gtk_widget_compute_transform(child, parent, &transform))
		return;
	static void (*base_snapshot)(GtkWidget *, GtkSnapshot *);
	if (!base_snapshot)
		base_snapshot = GTK_WIDGET_CLASS(g_type_class_peek(GTK_TYPE_WIDGET))->snapshot;
	gtk_snapshot_save(snapshot);
	tl_offscreen_transform(snapshot, &transform);
	if (opacity < 1)
		gtk_snapshot_push_opacity(snapshot, opacity);
	/* the widget's CSS background and border (View.setBackgroundColor is widget CSS), as GTK draws
	 * them before the class's snapshot */
	const int width = gtk_widget_get_width(child), height = gtk_widget_get_height(child);
	if (width > 0 && height > 0) {
		G_GNUC_BEGIN_IGNORE_DEPRECATIONS
		GtkStyleContext *style = gtk_widget_get_style_context(child);
		gtk_snapshot_render_background(snapshot, style, 0, 0, width, height);
		gtk_snapshot_render_frame(snapshot, style, 0, 0, width, height);
		G_GNUC_END_IGNORE_DEPRECATIONS
	}
	GtkWidgetClass *klass = GTK_WIDGET_GET_CLASS(child);
	if (klass->snapshot == base_snapshot) {
		for (GtkWidget *c = gtk_widget_get_first_child(child); c; c = gtk_widget_get_next_sibling(c))
			tl_snapshot_child(child, c, snapshot);
	} else if (klass->snapshot) {
		klass->snapshot(child, snapshot);
	}
	if (opacity < 1)
		gtk_snapshot_pop(snapshot);
	gtk_snapshot_restore(snapshot);
}

// ComposeUI layouts need to know when to redraw native widgets. There is no way in GTK to get
// notified about invalidations on specific widgets, but we get that global invalidation from the
// frame clock. Just flag it as invalidated on every global invalidation. The redraw will be a
// no-op in GTK anyway if redraw was not needed for the specific widget.
static void on_paint(GdkFrameClock *self, WrapperWidget *wrapper)
{
	JNIEnv *env = get_jni_env();
	J__View__propagateInvalidation(env, wrapper->jobj);
}

static void on_realize(GtkWidget *wrapper, gpointer user_data)
{
	GdkFrameClock *clock = gtk_widget_get_frame_clock(wrapper);
	g_signal_connect(clock, "paint", G_CALLBACK(on_paint), wrapper);
}

static void on_unrealize(GtkWidget *wrapper, gpointer user_data)
{
	GdkFrameClock *clock = gtk_widget_get_frame_clock(wrapper);
	g_signal_handlers_disconnect_by_data(clock, wrapper);
}

void wrapper_widget_register_invalidation_listener(WrapperWidget *wrapper)
{
	g_signal_connect(wrapper, "realize", G_CALLBACK(on_realize), NULL);
	g_signal_connect(wrapper, "unrealize", G_CALLBACK(on_unrealize), NULL);
}
