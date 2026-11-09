#include <math.h>
#include <assert.h>
#include <stdio.h>

#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"

#include "../views/AndroidLayout.h"
#include "../widgets/WrapperWidget.h"

#include "../generated_headers/android_view_MotionEvent.h"
#include "../generated_headers/android_view_View.h"

#define JAVA_ENUM_CLASS android_view_MotionEvent
enum {
	JAVA_ENUM(ACTION_DOWN),
	JAVA_ENUM(ACTION_UP),
	JAVA_ENUM(ACTION_MOVE),
	JAVA_ENUM(ACTION_CANCEL),

	JAVA_ENUM(ACTION_POINTER_DOWN),
	JAVA_ENUM(ACTION_POINTER_UP),

	JAVA_ENUM(ACTION_HOVER_MOVE),
	JAVA_ENUM(ACTION_SCROLL),
	JAVA_ENUM(ACTION_HOVER_ENTER),
	JAVA_ENUM(ACTION_HOVER_EXIT),
};
#undef JAVA_ENUM_CLASS

#define SOURCE_TOUCHSCREEN   0x1002
#define SOURCE_CLASS_POINTER 0x2

#define MAX_POINTERS         50

struct pointer {
	int id;
	int index;
	float coord_x;
	float coord_y;
	float raw_x;
	float raw_y;
};

GPtrArray *pointer_indices = NULL;

gpointer canceled_event = NULL;
WrapperWidget *cancel_triggerer = NULL;
int canceled_action = 0;

static struct pointer pointers[MAX_POINTERS] = {};

bool tl_force_plain_dispatch = false; /* set by the synthetic propagation for the dispatching group itself */
bool view_dispatch_motionevent(JNIEnv *env, WrapperWidget *wrapper, GtkPropagationPhase phase, jobject motion_event, gpointer event, int action)
{
	int ret;

	jobject this = wrapper->jobj;
	bool is_synthetic = event == motion_event;
	static int debug_touch = -1;
	if (debug_touch < 0)
		debug_touch = getenv("ATL_DEBUG_TOUCH") != NULL;
	if (debug_touch) {
		jclass cls = (*env)->GetObjectClass(env, this);
		jstring name = (*env)->CallObjectMethod(env, cls, (*env)->GetMethodID(env, (*env)->GetObjectClass(env, cls), "getName", "()Ljava/lang/String;"));
		const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
		fprintf(stderr, "[touch] %s phase=%s action=0x%x custom_dispatch=%d intercepting=%d synthetic=%d\n", cname,
		        phase == GTK_PHASE_CAPTURE ? "capture" : phase == GTK_PHASE_BUBBLE ? "bubble" : "target",
		        action, wrapper->custom_dispatch_touch, wrapper->intercepting_touch, is_synthetic);
		(*env)->ReleaseStringUTFChars(env, name, cname);
	}

	if (cancel_triggerer == wrapper) { // cancel done
		canceled_event = NULL;
		cancel_triggerer = NULL;
		if (is_synthetic)
			_SET_INT_FIELD(motion_event, "action", canceled_action);
	}

	if (wrapper->custom_dispatch_touch && !tl_force_plain_dispatch) {
		static jfieldID synthetic_field;
		if (!synthetic_field)
			synthetic_field = (*env)->GetStaticFieldID(env, (*env)->FindClass(env, "android/view/View"), "tlNativeDispatchSynthetic", "Z");
		(*env)->SetStaticBooleanField(env, (*env)->FindClass(env, "android/view/View"), synthetic_field, is_synthetic);
		ret = (*env)->CallBooleanMethod(env, this, handle_cache.view.dispatchTouchEvent, motion_event);
		if (debug_touch)
			fprintf(stderr, "[touch]   custom dispatchTouchEvent returned %d%c", ret, 10);
	} else if (phase == GTK_PHASE_CAPTURE && _GET_BOOL_FIELD(this, "disallowIntercept")) {
		if (action == ACTION_UP || action == ACTION_CANCEL)
			_SET_BOOL_FIELD(this, "disallowIntercept", false);
		ret = false;
	} else if (phase == GTK_PHASE_CAPTURE && !wrapper->intercepting_touch) {
		wrapper->intercepting_touch = (*env)->CallBooleanMethod(env, this, handle_cache.view.onInterceptTouchEvent, motion_event);
		if (wrapper->intercepting_touch) {
			// store the event that was canceled and let it propagate to the child widgets
			canceled_event = event;
			cancel_triggerer = wrapper;
			if (is_synthetic) {
				canceled_action = _GET_INT_FIELD(motion_event, "action");
				_SET_INT_FIELD(motion_event, "action", ACTION_CANCEL);
			}
		}
		ret = false;
	} else {
		ret = (*env)->CallBooleanMethod(env, this, handle_cache.view.onTouchEventInternal, motion_event, (jboolean)is_synthetic);
	}

	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	if (action == ACTION_UP || action == ACTION_CANCEL)
		wrapper->intercepting_touch = false;

	if (action == ACTION_CANCEL)
		ret = false;

	return ret;
}

static bool call_ontouch_callback(WrapperWidget *wrapper, int action, struct pointer pointers[MAX_POINTERS], GPtrArray *pointer_indices, GtkPropagationPhase phase, guint32 timestamp, GdkEvent *event)
{
	bool ret;
	JNIEnv *env = get_jni_env();

	int num_pointers = pointer_indices->len;
	jintArray ids = (*env)->NewIntArray(env, num_pointers);
	jfloatArray coords = (*env)->NewFloatArray(env, num_pointers * 4);
	for (int i = 0; i < num_pointers; i++) {
		struct pointer *pointer = (struct pointer *)g_ptr_array_index(pointer_indices, i);
		(*env)->SetIntArrayRegion(env, ids, i, 1, &pointer->id);
		/* put in all four float values starting at coord_x */
		(*env)->SetFloatArrayRegion(env, coords, 4 * i, 4, &pointer->coord_x);
	}

	jobject motion_event = (*env)->NewObject(env, handle_cache.motion_event.class, handle_cache.motion_event.constructor, SOURCE_TOUCHSCREEN, action, timestamp, ids, coords);

	ret = view_dispatch_motionevent(env, wrapper, phase, motion_event, event, action);

	(*env)->DeleteLocalRef(env, motion_event);

	return ret;
}

static void call_hover_callback(jobject this, int action, float x, float y, float raw_x, float raw_y)
{
	JNIEnv *env = get_jni_env();
	jobject motion_event = (*env)->NewObject(env, handle_cache.motion_event.class, handle_cache.motion_event.constructor_single, SOURCE_CLASS_POINTER, action, 0, x, y, x, y);
	(*env)->CallBooleanMethod(env, this, handle_cache.view.dispatchHoverEvent, motion_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void transform_coords_to_widget_relative(GtkWidget *widget, double *x, double *y)
{
	int ret;

	graphene_point_t p;
	double off_x;
	double off_y;

	GtkWidget *window = GTK_WIDGET(gtk_widget_get_native(widget));
	gtk_native_get_surface_transform(GTK_NATIVE(window), &off_x, &off_y);
	ret = gtk_widget_compute_point(window, widget, &GRAPHENE_POINT_INIT(*x - off_x, *y - off_y), &p);
	assert(ret);

	*x = p.x;
	*y = p.y;
}

void remove_pointer_fast(GPtrArray *pointer_indices, struct pointer *pointer)
{
	g_ptr_array_remove_index_fast(pointer_indices, pointer->index);
	if (pointer->index != pointer_indices->len) {
		/* update the index field of the pointer that was moved in to fill the empty space */
		struct pointer *replacement_pointer = (struct pointer *)g_ptr_array_index(pointer_indices, pointer->index);
		replacement_pointer->index = pointer->index;
	}
	/* mark as empty, other fields don't matter */
	pointer->id = 0;
}


// TODO: find a way to reconcile this with libandroid/input.c?
static gboolean on_pointer_event(GtkEventControllerLegacy *event_controller, GdkEvent *event, gpointer user_data)
{
	double x;
	double y;

	uintptr_t id = (uintptr_t)gdk_event_get_event_sequence(event);
	if (id > MAX_POINTERS - 1) // sequence id is a real pointer for drag and drop events
		return false;

	/* FIXME: this will clash with touchscreen */
	if (id == 0)
		id = 1;

	int pointer_index;

	if (pointers[id].id) {
		pointer_index = pointers[id].index;
	} else {
		/* index of a hypothetical next appended element */
		pointer_index = pointer_indices->len;
	}

	int action;
	GdkEventType event_type = gdk_event_get_event_type(event);
	if (getenv("ATL_DEBUG_TOUCH")) {
		double ex = 0, ey = 0;
		gdk_event_get_position(event, &ex, &ey);
		fprintf(stderr, "[touch] gdk event type=%d at %.0f,%.0f on %s\n", event_type, ex, ey,
		        G_OBJECT_TYPE_NAME(gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(event_controller))));
	}
	switch (event_type) {
		case GDK_BUTTON_PRESS:
		case GDK_TOUCH_BEGIN:
			action = pointer_index > 0 ? (ACTION_POINTER_DOWN | (pointer_index << 8)) : ACTION_DOWN;
			break;
		case GDK_BUTTON_RELEASE:
		case GDK_TOUCH_END:
			action = pointer_index > 0 ? (ACTION_POINTER_UP | (pointer_index << 8)) : ACTION_UP;
			break;
		case GDK_MOTION_NOTIFY:
			if (!(gdk_event_get_modifier_state(event) & GDK_BUTTON1_MASK))
				return false;
		case GDK_TOUCH_UPDATE:
			action = ACTION_MOVE;
			break;
		default: // not a touch or mouse event, nothing to do here
			return false;
	}

	GtkWidget *widget = gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(event_controller));
	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	GtkPropagationPhase phase = gtk_event_controller_get_propagation_phase(GTK_EVENT_CONTROLLER(event_controller));
	guint32 timestamp = gdk_event_get_time(event);

	if (id >= MAX_POINTERS) {
		fprintf(stderr, "exiting - event sequence %w64d larger than %d, did Gtk implementation change?", id, MAX_POINTERS);
		exit(1);
	}

	double raw_x;
	double raw_y;
	gdk_event_get_position(event, &raw_x, &raw_y);
	x = raw_x, y = raw_y;
	transform_coords_to_widget_relative(widget, &x, &y);

	if (!pointers[id].id) {
		/* if this is a new sequence, add a slot for it */
		g_ptr_array_add(pointer_indices, &pointers[id]);
		/* we appended a single element, so it must be at pointer_index */
		pointers[id].index = pointer_index;
		pointers[id].id = id;
	}

	pointers[id].coord_x = x;
	pointers[id].coord_y = y;
	pointers[id].raw_x = raw_x;
	pointers[id].raw_y = raw_y;

	if (event == canceled_event && cancel_triggerer != wrapper) {
		action = ACTION_CANCEL;
	}

	gboolean ret = call_ontouch_callback(wrapper, action, pointers, pointer_indices, phase, timestamp, event);
	if (event_type == GDK_BUTTON_RELEASE || event_type == GDK_TOUCH_END) {
		remove_pointer_fast(pointer_indices, &pointers[id]);
	}
	if (pointer_indices->len == 0 && wrapper->hover_exit_pending) {
		wrapper->hover_exit_pending = FALSE;
		call_hover_callback(wrapper->jobj, ACTION_HOVER_EXIT, x, y, raw_x, raw_y);
	}
	return ret;
}

static gboolean on_event(GtkEventControllerLegacy *event_controller, GdkEvent *event, gpointer user_data)
{
	GdkEventType event_type = gdk_event_get_event_type(event);
	switch (event_type) {
		case GDK_BUTTON_PRESS:
		case GDK_TOUCH_BEGIN:
		case GDK_BUTTON_RELEASE:
		case GDK_TOUCH_END:
		case GDK_MOTION_NOTIFY:
		case GDK_TOUCH_UPDATE:
			return on_pointer_event(event_controller, event, user_data);
		default: // not a touch or mouse event, nothing to do here
			return false;
	}
}

#define MAGIC_SCROLL_FACTOR 32

/* Gtk4 for whatever reason doesn't report cursor position for GtkEventControllerScroll, thankfully
 * it's not too annoying to get it ourselves */
gboolean hacky_get_cursor_position(GtkWidget *widget, double *x, double *y)
{
	GdkSeat *seat;
	GdkSurface *surface;
	GdkDevice *device_pointer;

	seat = gdk_display_get_default_seat(gtk_widget_get_display(widget));

	device_pointer = gdk_seat_get_pointer(seat);
	surface = gtk_native_get_surface(gtk_widget_get_native(widget));

	return gdk_surface_get_device_position(surface,
	                                       device_pointer,
	                                       x, y, NULL);
}

static gboolean scroll_cb(GtkEventControllerScroll *controller, gdouble dx, gdouble dy, jobject this)
{
	double x;
	double y;
	double raw_x;
	double raw_y;

	JNIEnv *env = get_jni_env();
	GdkScrollUnit scroll_unit = gtk_event_controller_scroll_get_unit(controller);
	GdkEvent *event = gtk_event_controller_get_current_event(GTK_EVENT_CONTROLLER(controller));
	GtkWidget *widget = gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(controller));
	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	hacky_get_cursor_position(widget, &raw_x, &raw_y);
	x = raw_x, y = raw_y;
	transform_coords_to_widget_relative(widget, &x, &y);

	if (scroll_unit == GDK_SCROLL_UNIT_SURFACE) {
		dx /= MAGIC_SCROLL_FACTOR;
		dy /= MAGIC_SCROLL_FACTOR;
	}
	jobject motion_event = (*env)->NewObject(env, handle_cache.motion_event.class,
	                                         handle_cache.motion_event.constructor_scroll,
	                                         SOURCE_CLASS_POINTER, ACTION_SCROLL,
	                                         gdk_event_get_time(event), x, y, raw_x, raw_y, dx, -dy);

	gboolean ret = (*env)->CallBooleanMethod(env, this, handle_cache.view.dispatchGenericMotionEvent, motion_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	// HACK: replay last hover event to work around scrolling issues in composeUI apps
	if (wrapper->hover_x && wrapper->hover_y)
		call_hover_callback(this, ACTION_HOVER_MOVE, wrapper->hover_x, wrapper->hover_y, wrapper->hover_x, wrapper->hover_y);

	return ret;
}

/* tl_hover_raw: the pointer's position in the toplevel (AOSP raw coordinates), from the current
 * event when there is one, else from the widget-relative position of the callback */
static void tl_hover_raw(GtkEventController *controller, double x, double y, double *raw_x, double *raw_y)
{
	GdkEvent *event = gtk_event_controller_get_current_event(controller);
	if (event) {
		gdk_event_get_position(event, raw_x, raw_y);
		return;
	}
	GtkWidget *widget = gtk_event_controller_get_widget(controller);
	GtkRoot *root = widget ? gtk_widget_get_root(widget) : NULL;
	graphene_point_t p;
	if (root && gtk_widget_compute_point(widget, GTK_WIDGET(root), &GRAPHENE_POINT_INIT(x, y), &p)) {
		*raw_x = p.x;
		*raw_y = p.y;
	} else {
		*raw_x = x;
		*raw_y = y;
	}
}

static void hover_enter_cb(GtkEventControllerMotion *controller, double x, double y, WrapperWidget *wrapper)
{
	double raw_x;
	double raw_y;
	tl_hover_raw(GTK_EVENT_CONTROLLER(controller), x, y, &raw_x, &raw_y);
	wrapper->hover_exit_pending = FALSE;
	wrapper->hover_x = x;
	wrapper->hover_y = y;
	call_hover_callback(wrapper->jobj, ACTION_HOVER_ENTER, x, y, raw_x, raw_y);
}

static void hover_leave_cb(GtkEventControllerMotion *controller, WrapperWidget *wrapper)
{
	if (pointer_indices->len) // composeUI apps don't like ACTION_HOVER_EXIT events while a pointer is down
		wrapper->hover_exit_pending = TRUE;
	else
		call_hover_callback(wrapper->jobj, ACTION_HOVER_EXIT, 0, 0, 0, 0);
}

static void hover_motion_cb(GtkEventControllerMotion *controller, double x, double y, WrapperWidget *wrapper)
{
	double raw_x;
	double raw_y;
	tl_hover_raw(GTK_EVENT_CONTROLLER(controller), x, y, &raw_x, &raw_y);
	wrapper->hover_x = x;
	wrapper->hover_y = y;
	call_hover_callback(wrapper->jobj, ACTION_HOVER_MOVE, x, y, raw_x, raw_y);
}

void _setOnTouchListener(JNIEnv *env, jobject this, GtkWidget *widget)
{
	GtkEventController *old_controller = g_object_get_data(G_OBJECT(widget), "on_touch_listener");
	if (old_controller)
		return;

	if (!pointer_indices)
		pointer_indices = g_ptr_array_new_full(20, NULL);

	if (getenv("ATL_DEBUG_TOUCH")) {
		jclass cls = (*env)->GetObjectClass(env, this);
		jstring name = (*env)->CallObjectMethod(env, cls, (*env)->GetMethodID(env, (*env)->GetObjectClass(env, cls), "getName", "()Ljava/lang/String;"));
		const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
		fprintf(stderr, "[touch] attaching touch controller to %s (widget %p)\n", cname, widget);
		(*env)->ReleaseStringUTFChars(env, name, cname);
	}
	GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_event_controller_legacy_new());
	gtk_event_controller_set_propagation_phase(controller, GTK_PHASE_BUBBLE);

	g_signal_connect(controller, "event", G_CALLBACK(on_event), NULL);
	gtk_widget_add_controller(widget, controller);
	g_object_set_data(G_OBJECT(widget), "on_touch_listener", controller);

	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	jmethodID onintercepttouchevent_method = _METHOD(_CLASS(this), "onInterceptTouchEvent", "(Landroid/view/MotionEvent;)Z");
	if (onintercepttouchevent_method != handle_cache.view.onInterceptTouchEvent || wrapper->custom_dispatch_touch) {
		GtkEventController *old_controller = g_object_get_data(G_OBJECT(widget), "on_intercept_touch_listener");
		if (old_controller)
			gtk_widget_remove_controller(widget, old_controller);

		GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_event_controller_legacy_new());
		gtk_event_controller_set_propagation_phase(controller, GTK_PHASE_CAPTURE);

		g_signal_connect(controller, "event", G_CALLBACK(on_event), NULL);
		gtk_widget_add_controller(widget, controller);
		g_object_set_data(G_OBJECT(widget), "on_intercept_touch_listener", controller);
	}

	if (!wrapper->needs_allocation && (wrapper->real_width || wrapper->real_height))
		gtk_widget_size_allocate(GTK_WIDGET(wrapper), &(GtkAllocation){.x = 0, .y = 0, .width = wrapper->real_width, .height = wrapper->real_height}, 0);

	wrapper->needs_allocation = true;
	gtk_widget_set_overflow(GTK_WIDGET(wrapper), GTK_OVERFLOW_HIDDEN);
}

JNIEXPORT void JNICALL Java_android_view_View_nativeSetOnTouchListener(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkWidget *widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr)));

	_setOnTouchListener(env, this, widget);
}

JNIEXPORT void JNICALL Java_android_view_View_nativeSetOnClickListener(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));

	/* tl_click_from_java: AOSP performs the click from View.onTouchEvent on ACTION_UP */
	_setOnTouchListener(env, this, gtk_widget_get_parent(widget));
	widget_set_needs_allocation(widget);
}

JNIEXPORT jint JNICALL Java_android_view_View_getWidth(JNIEnv *env, jobject this)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(_GET_LONG_FIELD(this, "widget")));

	if (ATL_IS_ANDROID_LAYOUT(gtk_widget_get_layout_manager(widget))) {
		AndroidLayout *layout = ATL_ANDROID_LAYOUT(gtk_widget_get_layout_manager(widget));
		return layout->real_width;
	}
	return gtk_widget_get_width(widget);
}

JNIEXPORT jint JNICALL Java_android_view_View_getHeight(JNIEnv *env, jobject this)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(_GET_LONG_FIELD(this, "widget")));

	if (ATL_IS_ANDROID_LAYOUT(gtk_widget_get_layout_manager(widget))) {
		AndroidLayout *layout = ATL_ANDROID_LAYOUT(gtk_widget_get_layout_manager(widget));
		return layout->real_height;
	}
	return gtk_widget_get_height(widget);
}

#define GRAVITY_TOP               (1 << 5) //0x30
#define GRAVITY_BOTTOM            (1 << 6) //0x50
#define GRAVITY_LEFT              (1 << 1) //0x3
#define GRAVITY_RIGHT             (1 << 2) //0x5

#define GRAVITY_CENTER_VERTICAL   0x10
#define GRAVITY_CENTER_HORIZONTAL 0x01

#define GRAVITY_CENTER            (GRAVITY_CENTER_VERTICAL | GRAVITY_CENTER_HORIZONTAL)

#define MATCH_PARENT              (-1)

JNIEXPORT void JNICALL Java_android_view_View_native_1setLayoutParams(JNIEnv *env, jobject this, jlong widget_ptr, jint width, jint height, jint gravity, jfloat weight,
                                                                      jint leftMargin, jint topMargin, jint rightMargin, jint bottomMargin)
{
	GtkWidget *widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr)));

	GtkAlign halign = GTK_ALIGN_FILL;
	GtkAlign valign = GTK_ALIGN_FILL;
	gboolean hexpand = FALSE;
	gboolean vexpand = FALSE;

	if (gravity != -1) {
		if (gravity & GRAVITY_BOTTOM)
			valign = GTK_ALIGN_END;
		else if (gravity & GRAVITY_TOP)
			valign = GTK_ALIGN_START;
		else
			valign = GTK_ALIGN_FILL;

		if (gravity & GRAVITY_RIGHT)
			halign = GTK_ALIGN_END;
		else if (gravity & GRAVITY_LEFT)
			halign = GTK_ALIGN_START;
		else
			halign = GTK_ALIGN_FILL;

		if (gravity == GRAVITY_CENTER) {
			valign = GTK_ALIGN_CENTER; // GTK_ALIGN_CENTER doesn't seem to be the right one?
			halign = GTK_ALIGN_CENTER; // ditto (GTK_ALIGN_CENTER)
			hexpand = TRUE;            // haxx or not?
			vexpand = TRUE;            // seems to be the deciding factor for whether to expand, guess I should try on android
		}
	}

	if (weight > 0.f) {
		hexpand = TRUE;
		vexpand = TRUE;
	}

	if (width == MATCH_PARENT) {
		hexpand = true;
		halign = GTK_ALIGN_FILL;
	}
	if (height == MATCH_PARENT) {
		vexpand = true;
		valign = GTK_ALIGN_FILL;
	}

	gtk_widget_set_hexpand(widget, hexpand);
	gtk_widget_set_vexpand(widget, vexpand);
	gtk_widget_set_halign(widget, halign);
	gtk_widget_set_valign(widget, valign);

	if (width > 0)
		g_object_set(G_OBJECT(widget), "width-request", width, NULL);
	if (height > 0)
		g_object_set(G_OBJECT(widget), "height-request", height, NULL);

	GtkWidget *parent = gtk_widget_get_parent(widget);
	// if parent is Java widget, it will handle the margins by itself
	if (parent && !ATL_IS_ANDROID_LAYOUT(gtk_widget_get_layout_manager(parent))) {
		gtk_widget_set_margin_start(widget, leftMargin);
		gtk_widget_set_margin_top(widget, topMargin);
		gtk_widget_set_margin_end(widget, rightMargin);
		gtk_widget_set_margin_bottom(widget, bottomMargin);
	}

	GtkLayoutManager *layout_manager = gtk_widget_get_layout_manager(WRAPPER_WIDGET(widget)->child);
	if (ATL_IS_ANDROID_LAYOUT(layout_manager))
		android_layout_set_params(ATL_ANDROID_LAYOUT(layout_manager), width, height);

	wrapper_widget_set_layout_params(WRAPPER_WIDGET(widget), width, height);

	atl_ensure_widget_snapshotability(widget);
}

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
JNIEXPORT void JNICALL Java_android_view_View_native_1setPadding(JNIEnv *env, jobject this, jlong widget_ptr, jint left, jint top, jint right, jint bottom)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	if (ATL_IS_ANDROID_LAYOUT(gtk_widget_get_layout_manager(widget))) {
		return;
	}

	GtkStyleContext *style_context = gtk_widget_get_style_context(widget);
	GtkCssProvider *old_provider = g_object_get_data(G_OBJECT(widget), "padding_style_provider");
	if (old_provider)
		gtk_style_context_remove_provider(style_context, GTK_STYLE_PROVIDER(old_provider));
	GtkCssProvider *css_provider = gtk_css_provider_new();

	char *css_string = g_markup_printf_escaped("*{ padding-left: %dpx; padding-top: %dpx; padding-right: %dpx; padding-bottom: %dpx; }", left, top, right, bottom);
	gtk_css_provider_load_from_string(css_provider, css_string);
	g_free(css_string);

	gtk_style_context_add_provider(style_context, GTK_STYLE_PROVIDER(css_provider), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	g_object_set_data(G_OBJECT(widget), "padding_style_provider", css_provider);
}

JNIEXPORT void JNICALL Java_android_view_View_native_1setVisibility(JNIEnv *env, jobject this, jlong widget_ptr, jint visibility, jfloat alpha, jboolean sensitive)
{
	GtkWidget *widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr)));

	atl_safe_gtk_widget_set_visible(widget, visibility != android_view_View_GONE);
	gtk_widget_set_opacity(widget, (visibility != android_view_View_INVISIBLE) * alpha);
	gtk_widget_set_sensitive(widget, sensitive && visibility != android_view_View_INVISIBLE && alpha != 0.0f);
}

/** JavaWidget:
 * Minimal gtk widget class which does nothing.
 * Drawing will be overwritten by WrapperWidget.
 * If it holds children, they will be laid out by AndroidLayout
 */
struct _JavaWidget {
	GtkWidget parent_instance;
};
G_DECLARE_FINAL_TYPE(JavaWidget, java_widget, JAVA, WIDGET, GtkWidget)
static void java_widget_dispose(GObject *java_widget)
{
	GtkWidget *widget = GTK_WIDGET(java_widget);
	GtkWidget *child = gtk_widget_get_first_child(widget);
	while (child) {
		GtkWidget *_child = gtk_widget_get_next_sibling(child);
		gtk_widget_unparent(child);
		child = _child;
	}
}
static void java_widget_init(JavaWidget *java_widget) {}
/* tl_javawidget_contains: a touch-handling view's laid-out bounds, not the lazy GTK allocation */
static gboolean java_widget_contains(GtkWidget *widget, double x, double y)
{
	GtkWidget *parent = gtk_widget_get_parent(widget);
	int width = gtk_widget_get_width(widget);
	int height = gtk_widget_get_height(widget);
	if (parent && WRAPPER_IS_WIDGET(parent) && WRAPPER_WIDGET(parent)->tl_touch_target) {
		WrapperWidget *wrapper = WRAPPER_WIDGET(parent);
		if (wrapper->real_width > width)
			width = wrapper->real_width;
		if (wrapper->real_height > height)
			height = wrapper->real_height;
	}
	return x >= 0 && y >= 0 && x < width && y < height;
}
static void java_widget_class_init(JavaWidgetClass *class)
{
	G_OBJECT_CLASS(class)->dispose = java_widget_dispose;
	GTK_WIDGET_CLASS(class)->contains = java_widget_contains;
}
G_DEFINE_TYPE(JavaWidget, java_widget, GTK_TYPE_WIDGET)

JNIEXPORT jlong JNICALL Java_android_view_View_native_1constructor(JNIEnv *env, jobject this, jobject context, jobject attrs)
{
	WrapperWidget *wrapper = g_object_ref(WRAPPER_WIDGET(wrapper_widget_new()));
	GtkWidget *widget = g_object_new(java_widget_get_type(), NULL);
	gtk_widget_set_layout_manager(widget, android_layout_new(this));
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), widget);
	wrapper_widget_set_jobject(WRAPPER_WIDGET(wrapper), env, this);

	jclass class = _CLASS(this);
	jstring nameObj = (*env)->CallObjectMethod(env, class,
	                                           _METHOD(_CLASS(class), "getName", "()Ljava/lang/String;"));
	const char *name = (*env)->GetStringUTFChars(env, nameObj, NULL);
	gtk_widget_set_name(widget, name);
	(*env)->ReleaseStringUTFChars(env, nameObj, name);

	if (_METHOD(class, "onGenericMotionEvent", "(Landroid/view/MotionEvent;)Z") != handle_cache.view.onGenericMotionEvent
	    || _METHOD(class, "dispatchGenericMotionEvent", "(Landroid/view/MotionEvent;)Z") != handle_cache.view.dispatchGenericMotionEvent) {
		GtkEventController *controller = gtk_event_controller_scroll_new(GTK_EVENT_CONTROLLER_SCROLL_VERTICAL);

		g_signal_connect(controller, "scroll", G_CALLBACK(scroll_cb), wrapper->jobj);
		gtk_widget_add_controller(GTK_WIDGET(wrapper), controller);
	}
	if (_METHOD(class, "dispatchHoverEvent", "(Landroid/view/MotionEvent;)Z") != handle_cache.view.dispatchHoverEvent) {
		GtkEventController *controller = gtk_event_controller_motion_new();

		g_signal_connect(controller, "enter", G_CALLBACK(hover_enter_cb), wrapper);
		g_signal_connect(controller, "leave", G_CALLBACK(hover_leave_cb), wrapper);
		g_signal_connect(controller, "motion", G_CALLBACK(hover_motion_cb), wrapper);
		gtk_widget_add_controller(GTK_WIDGET(wrapper), controller);
	}

	return _INTPTR(widget);
}

JNIEXPORT void JNICALL Java_android_view_View_nativeInvalidate(JNIEnv *env, jclass class, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));

	wrapper_widget_queue_draw(WRAPPER_WIDGET(gtk_widget_get_parent(widget)));
}

JNIEXPORT void JNICALL Java_android_view_View_native_1destructor(JNIEnv *env, jobject this, jlong widget_ptr)
{
	g_object_unref(gtk_widget_get_parent(_PTR(widget_ptr)));
}

#define MEASURE_SPEC_UNSPECIFIED (0 << 30)
#define MEASURE_SPEC_EXACTLY     (1 << 30)
#define MEASURE_SPEC_AT_MOST     (2 << 30)
#define MEASURE_SPEC_MASK        (0x3 << 30)

JNIEXPORT void JNICALL Java_android_view_View_native_1measure(JNIEnv *env, jobject this, jlong widget_ptr, jint width_spec, jint height_spec)
{
	int width = -1;
	int height = -1;
	GtkWidget *widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr)));
	int width_spec_size = width_spec & ~MEASURE_SPEC_MASK;
	int height_spec_size = height_spec & ~MEASURE_SPEC_MASK;
	int width_spec_mode = width_spec & MEASURE_SPEC_MASK;
	int height_spec_mode = height_spec & MEASURE_SPEC_MASK;
	GtkSizeRequestMode request_mode;

	if (width_spec_mode == MEASURE_SPEC_EXACTLY) {
		width = width_spec_size;
		request_mode = GTK_SIZE_REQUEST_HEIGHT_FOR_WIDTH;
	}
	if (height_spec_mode == MEASURE_SPEC_EXACTLY) {
		height = height_spec_size;
		request_mode = GTK_SIZE_REQUEST_WIDTH_FOR_HEIGHT;
	}
	if (width == -1 && height == -1)
		request_mode = gtk_widget_get_request_mode(widget);
	if (width == -1 && request_mode == GTK_SIZE_REQUEST_HEIGHT_FOR_WIDTH) {
		gtk_widget_measure(widget, GTK_ORIENTATION_HORIZONTAL, height, NULL, &width, NULL, NULL);
		if (width_spec_mode == MEASURE_SPEC_AT_MOST && width > width_spec_size)
			width = width_spec_size;
	}
	if (height == -1) {
		gtk_widget_measure(widget, GTK_ORIENTATION_VERTICAL, width, NULL, &height, NULL, NULL);
		if (height_spec_mode == MEASURE_SPEC_AT_MOST && height > height_spec_size)
			height = height_spec_size;
	}
	if (width == -1) {
		gtk_widget_measure(widget, GTK_ORIENTATION_HORIZONTAL, height, NULL, &width, NULL, NULL);
		if (width_spec_mode == MEASURE_SPEC_AT_MOST && width > width_spec_size)
			width = width_spec_size;
	}

	(*env)->CallVoidMethod(env, this, handle_cache.view.setMeasuredDimension, width, height);
}

/* teslalinux: an android.graphics.Matrix (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y,
 * MPERSP_0, MPERSP_1, MPERSP_2) appended to a GSK transform chain (native-view-transform.sh) */
static GskTransform *tl_view_layer_transform(GskTransform *t, const jfloat *v)
{
	const float a = v[0], c = v[1], tx = v[2], b = v[3], d = v[4], ty = v[5];
	const float p0 = v[6], p1 = v[7], p2 = v[8];
	const float sx = sqrtf(a * a + b * b);
	const float det = a * d - b * c;
	if (p0 != 0.f || p1 != 0.f || p2 != 1.f || sx == 0.f || det == 0.f) {
		graphene_matrix_t m;
		/* graphene, row-vector convention: rows = x axis, y axis, z axis, translation */
		graphene_matrix_init_from_float(&m, (float[]){
			a, b, 0, p0,
			c, d, 0, p1,
			0, 0, 1, 0,
			tx, ty, 0, p2,
		});
		return gsk_transform_matrix(t, &m);
	}
	const float sy = det / sx;
	const float shear = (a * c + b * d) / (sx * sy);
	if (tx != 0.f || ty != 0.f)
		t = gsk_transform_translate(t, &GRAPHENE_POINT_INIT(tx, ty));
	if (b != 0.f || a < 0.f)
		t = gsk_transform_rotate(t, atan2f(b, a) * (180.f / (float)M_PI));
	if (shear != 0.f)
		t = gsk_transform_skew(t, atanf(shear) * (180.f / (float)M_PI), 0.f);
	if (sx != 1.f || sy != 1.f)
		t = gsk_transform_scale(t, sx, sy);
	return t;
}

JNIEXPORT void JNICALL Java_android_view_View_native_1layout(JNIEnv *env, jobject this, jlong widget_ptr, jint l, jint t, jint r, jint b)
{
	GtkWidget *widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr)));

	GtkAllocation allocation = {
		.x = l,
		.y = t,
	};
	int width = r - l;
	int height = b - t;
	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	if (wrapper->real_width != width || wrapper->real_height != height) {
		wrapper->real_width = width;
		wrapper->real_height = height;
		if (!wrapper->needs_allocation)
			gtk_widget_queue_allocate(widget);
	}
	if (wrapper->needs_allocation) {
		allocation.width = width;
		allocation.height = height;
	}

	if (wrapper->jobj) {
		/* teslalinux: the view's full matrix (scale, rotation, animation) when it has one */
		static jfieldID layer_matrix_field = NULL;
		if (!layer_matrix_field) {
			layer_matrix_field = (*env)->GetFieldID(env, handle_cache.view.class, "tlLayerMatrix", "[F");
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionClear(env);
		}
		jfloatArray layer_matrix = layer_matrix_field ? (*env)->GetObjectField(env, wrapper->jobj, layer_matrix_field) : NULL;
		if (layer_matrix) {
			jfloat v[9];
			(*env)->GetFloatArrayRegion(env, layer_matrix, 0, 9, v);
			(*env)->DeleteLocalRef(env, layer_matrix);
			GskTransform *t = gsk_transform_translate(NULL, &GRAPHENE_POINT_INIT(allocation.x, allocation.y));
			t = tl_view_layer_transform(t, v);
			gtk_widget_allocate(widget, allocation.width, allocation.height, -1, t);
			return;
		}
		allocation.x += _GET_FLOAT_FIELD(wrapper->jobj, "translationX");
		allocation.y += _GET_FLOAT_FIELD(wrapper->jobj, "translationY");
	}

	gtk_widget_size_allocate(widget, &allocation, -1);
}

JNIEXPORT void JNICALL Java_android_view_View_native_1requestLayout(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));

	atl_safe_gtk_widget_queue_resize(widget);
}

/* we kinda need per-widget css */
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
JNIEXPORT void JNICALL Java_android_view_View_native_1setBackgroundColor(JNIEnv *env, jobject this, jlong widget_ptr, jint color)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(widget));
	wrapper_widget_set_background(wrapper, NULL);

	GtkStyleContext *style_context = gtk_widget_get_style_context(widget);

	GtkCssProvider *old_provider = g_object_get_data(G_OBJECT(widget), "background_style_provider");
	if (old_provider)
		gtk_style_context_remove_provider(style_context, GTK_STYLE_PROVIDER(old_provider));

	GtkCssProvider *css_provider = gtk_css_provider_new();

	char *css_string = g_markup_printf_escaped("* { background-image: none; background-color: #%06x%02x; }", color & 0xFFFFFF, (color >> 24) & 0xFF);
	gtk_css_provider_load_from_string(css_provider, css_string);
	g_free(css_string);

	gtk_style_context_add_provider(style_context, GTK_STYLE_PROVIDER(css_provider), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	g_object_set_data(G_OBJECT(widget), "background_style_provider", css_provider);
	if (((color >> 24) & 0xFF) != 0)
		widget_set_needs_allocation(widget);
}

JNIEXPORT void JNICALL Java_android_view_View_native_1setBackgroundDrawable(JNIEnv *env, jobject this, jlong widget_ptr, jlong paintable_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GdkPaintable *paintable = GDK_PAINTABLE(_PTR(paintable_ptr));
	wrapper_widget_set_background(WRAPPER_WIDGET(gtk_widget_get_parent(widget)), paintable);
	GtkStyleContext *style_context = gtk_widget_get_style_context(widget);

	GtkCssProvider *old_provider = g_object_get_data(G_OBJECT(widget), "background_style_provider");
	if (old_provider)
		gtk_style_context_remove_provider(style_context, GTK_STYLE_PROVIDER(old_provider));

	GtkCssProvider *css_provider = gtk_css_provider_new();

	char *css_string = g_markup_printf_escaped("* { background-image: none; background-color: #00000000; }");
	gtk_css_provider_load_from_string(css_provider, css_string);
	g_free(css_string);

	gtk_style_context_add_provider(style_context, GTK_STYLE_PROVIDER(css_provider), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	g_object_set_data(G_OBJECT(widget), "background_style_provider", css_provider);
	if (paintable)
		widget_set_needs_allocation(widget);
}
#pragma GCC diagnostic pop

JNIEXPORT jboolean JNICALL Java_android_view_View_native_1getGlobalVisibleRect(JNIEnv *env, jobject this, jlong widget_ptr, jobject rect)
{
	GtkWidget *widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr)));
	graphene_point_t point_in = {0, 0};
	graphene_point_t point_out;
	double off_x;
	double off_y;
	gboolean ret;
	GtkNative *native = gtk_widget_get_native(widget);
	GtkWidget *window;
	if (native) {
		window = GTK_WIDGET(native);
		gtk_native_get_surface_transform(native, &off_x, &off_y);
	} else {
		/* tl_unrooted_rect: AOSP reports a detached view's rect relative to its outermost ancestor */
		window = widget;
		while (gtk_widget_get_parent(window))
			window = gtk_widget_get_parent(window);
		off_x = off_y = 0;
	}
	ret = gtk_widget_compute_point(widget, window, &point_in, &point_out);
	if (!ret)
		return false;
	_SET_INT_FIELD(rect, "left", point_out.x + off_x);
	_SET_INT_FIELD(rect, "top", point_out.y + off_y);
	point_in = (graphene_point_t){Java_android_view_View_getWidth(env, this), Java_android_view_View_getHeight(env, this)};
	ret = gtk_widget_compute_point(widget, window, &point_in, &point_out);
	if (!ret)
		return false;
	_SET_INT_FIELD(rect, "right", point_out.x + off_x);
	_SET_INT_FIELD(rect, "bottom", point_out.y + off_y);
	return true;
}

JNIEXPORT void JNICALL Java_android_view_View_nativeSetOnLongClickListener(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	/* AOSP: the long press comes from View.onTouchEvent's pending check (tl_click_from_java) */
	_setOnTouchListener(env, this, gtk_widget_get_parent(widget));
}

JNIEXPORT jboolean JNICALL Java_android_view_View_native_1getMatrix(JNIEnv *env, jobject this, jlong widget_ptr, jlong matrix_ptr)
{
	GtkWidget *widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr)));
	return gtk_widget_compute_transform(gtk_widget_get_parent(widget), widget, _PTR(matrix_ptr));
}

JNIEXPORT void JNICALL Java_android_view_View_native_1queueAllocate(JNIEnv *env, jobject this, jlong widget_ptr)
{
	atl_safe_gtk_widget_queue_allocate(GTK_WIDGET(_PTR(widget_ptr)));
}

JNIEXPORT void JNICALL Java_android_view_View_native_1drawBackground(JNIEnv *env, jobject this, jlong widget_ptr, jlong snapshot_ptr)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr))));
	GdkSnapshot *snapshot = GDK_SNAPSHOT(_PTR(snapshot_ptr));
	if (wrapper->background)
		gtk_widget_snapshot_child(&wrapper->parent_instance, wrapper->background, snapshot);
}

JNIEXPORT void JNICALL Java_android_view_View_native_1drawContent(JNIEnv *env, jobject this, jlong widget_ptr, jlong snapshot_ptr)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr))));
	GdkSnapshot *snapshot = GDK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_widget_snapshot_child(&wrapper->parent_instance, wrapper->child, snapshot);
}

JNIEXPORT void JNICALL Java_android_view_View_nativeSetFullscreen(JNIEnv *env, jobject this, jlong widget_ptr, jboolean fullscreen)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	/* tl_dialog_overlay: a dialog's decor lives in a layer of the main window; its system UI flags
	 * apply to the dialog window in AOSP and must not change the activity window */
	for (GtkWidget *p = widget; p; p = gtk_widget_get_parent(p)) {
		if (gtk_widget_has_css_class(p, "tl-dialog-dim"))
			return;
	}
	if (!gtk_widget_get_native(widget) || !GTK_IS_WINDOW(gtk_widget_get_native(widget)))
		return;
	GtkWindow *window = GTK_WINDOW(gtk_widget_get_native(widget));
	if (fullscreen) {
		if (gtk_window_is_maximized(window)) {
			gtk_window_fullscreen(window);
			// Android apps expect the fullscreen to happen immediately, so we need to mock the fullscreened window size
			if (ATL_IS_ANDROID_LAYOUT(gtk_widget_get_layout_manager(widget))) {
				AndroidLayout *layout = ATL_ANDROID_LAYOUT(gtk_widget_get_layout_manager(widget));
				GdkSurface *surface = gtk_native_get_surface(GTK_NATIVE(window));
				GdkDisplay *display = gdk_surface_get_display(surface);
				GdkMonitor *monitor = gdk_display_get_monitor_at_surface(display, surface);
				GdkRectangle geometry;
				gdk_monitor_get_geometry(monitor, &geometry);
				// check if fullscreen is slightly larger than the window, if so, we can safely mock the new size
				if (layout->real_width <= geometry.width && layout->real_width > geometry.width - 200 && layout->real_height <= geometry.height && layout->real_height > geometry.height - 200) {
					layout->real_width = geometry.width;
					layout->real_height = geometry.height;
					_SET_INT_FIELD(this, "right", geometry.width);
					_SET_INT_FIELD(this, "bottom", geometry.height);
				}
			}
		} else {
			printf("blocking fullscreen request, because window is not maximized\n");
		}
	} else {
		gtk_window_unfullscreen(window);
	}
}

JNIEXPORT void JNICALL Java_android_view_View_native_1addClass(JNIEnv *env, jobject this, jlong widget_ptr, jstring class_name_jstr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));

	const char *class_name = (*env)->GetStringUTFChars(env, class_name_jstr, NULL);
	gtk_widget_add_css_class(widget, class_name);

	(*env)->ReleaseStringUTFChars(env, class_name_jstr, class_name);
}

JNIEXPORT void JNICALL Java_android_view_View_native_1removeClass(JNIEnv *env, jobject this, jlong widget_ptr, jstring class_name_jstr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));

	const char *class_name = (*env)->GetStringUTFChars(env, class_name_jstr, NULL);
	gtk_widget_remove_css_class(widget, class_name);

	(*env)->ReleaseStringUTFChars(env, class_name_jstr, class_name);
}

JNIEXPORT void JNICALL Java_android_view_View_native_1addClasses(JNIEnv *env, jobject this, jlong widget_ptr, jobjectArray class_names_jarray)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));

	int length = (*env)->GetArrayLength(env, class_names_jarray);

	for (int i = 0; i < length; i++) {
		jstring class_name_jstr = (jstring)((*env)->GetObjectArrayElement(env, class_names_jarray, i));

		const char *class_name = (*env)->GetStringUTFChars(env, class_name_jstr, NULL);
		gtk_widget_add_css_class(widget, class_name);

		(*env)->ReleaseStringUTFChars(env, class_name_jstr, class_name);
	}
}

JNIEXPORT void JNICALL Java_android_view_View_native_1removeClasses(JNIEnv *env, jobject this, jlong widget_ptr, jobjectArray class_names_jarray)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));

	int length = (*env)->GetArrayLength(env, class_names_jarray);

	for (int i = 0; i < length; i++) {
		jstring class_name_jstr = (jstring)((*env)->GetObjectArrayElement(env, class_names_jarray, i));

		const char *class_name = (*env)->GetStringUTFChars(env, class_name_jstr, NULL);
		gtk_widget_remove_css_class(widget, class_name);

		(*env)->ReleaseStringUTFChars(env, class_name_jstr, class_name);
	}
}

/* tl_focus_on_map: a focus requested before the widget is mapped is applied when it maps (AOSP
 * keeps the focus request as view state and the window applies it) */
static void tl_focus_on_map(GtkWidget *widget, gpointer data)
{
	if (g_object_get_data(G_OBJECT(widget), "tl-focus-pending")) {
		g_object_set_data(G_OBJECT(widget), "tl-focus-pending", NULL);
		gboolean ok = gtk_widget_grab_focus(widget);
		if (getenv("ATL_DEBUG_FOCUS"))
			fprintf(stderr, "[focus] deferred grab on map %s -> %d has_focus=%d\n", G_OBJECT_TYPE_NAME(widget), ok, gtk_widget_has_focus(widget));
	}
	g_signal_handlers_disconnect_by_func(widget, tl_focus_on_map, data);
}

JNIEXPORT void JNICALL Java_android_view_View_nativeRequestFocus(JNIEnv *env, jobject this, jlong widget_ptr, jint direction)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *wrapper = gtk_widget_get_parent(widget);
	GtkWidget *target = gtk_widget_get_focusable(widget) ? widget : wrapper;
	if (gtk_widget_get_mapped(target)) {
		gboolean ok = gtk_widget_grab_focus(target);
		if (getenv("ATL_DEBUG_FOCUS"))
			fprintf(stderr, "[focus] grab %s -> %d has_focus=%d\n", G_OBJECT_TYPE_NAME(target), ok, gtk_widget_has_focus(target));
	} else {
		if (getenv("ATL_DEBUG_FOCUS"))
			fprintf(stderr, "[focus] grab deferred to map: %s\n", G_OBJECT_TYPE_NAME(target));
		g_object_set_data(G_OBJECT(target), "tl-focus-pending", GINT_TO_POINTER(1));
		g_signal_handlers_disconnect_by_func(target, tl_focus_on_map, NULL);
		g_signal_connect(target, "map", G_CALLBACK(tl_focus_on_map), NULL);
	}
}

JNIEXPORT jboolean JNICALL Java_android_view_View_nativeIsFocused(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *wrapper = gtk_widget_get_parent(widget);
	return gtk_widget_has_focus(widget) || gtk_widget_has_focus(wrapper);
}

JNIEXPORT void JNICALL Java_android_view_View_native_1keep_1screen_1on(JNIEnv *env, jobject this, jlong widget_ptr, jboolean enable)
{
	GtkApplication *application = GTK_APPLICATION(g_application_get_default());
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	guint cookie = GPOINTER_TO_INT(g_object_get_data(G_OBJECT(widget), "keep-screen-on-cookie"));
	if (cookie && !enable) {
		gtk_application_uninhibit(application, cookie);
		g_object_set_data(G_OBJECT(widget), "keep-screen-on-cookie", NULL);
	} else if (!cookie && enable) {
		GtkWindow *window = GTK_WINDOW(gtk_widget_get_native(widget));
		GtkApplicationInhibitFlags flags = GTK_APPLICATION_INHIBIT_SUSPEND | GTK_APPLICATION_INHIBIT_IDLE;
		cookie = gtk_application_inhibit(application, window, flags, "keep-screen-on");
		g_object_set_data(G_OBJECT(widget), "keep-screen-on-cookie", GINT_TO_POINTER(cookie));
	}
}

JNIEXPORT jboolean JNICALL Java_android_view_View_nativeIsAttachedToWindow(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	/* tl_window_attach: attached while in a window's hierarchy (and still during onDetachedFromWindow) */
	return currently_unmapping == widget || gtk_widget_get_root(widget) != NULL;
}

JNIEXPORT jobject JNICALL Java_android_view_View_native_1get_1window(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	/* tl_dialog_overlay_window: the nearest ancestor carrying a Window (a dialog's layer before the
	 * main window, which carries the activity's) */
	for (GtkWidget *w = widget; w; w = gtk_widget_get_parent(w)) {
		jobject jobj = g_object_get_data(G_OBJECT(w), "jobject");
		if (jobj)
			return jobj;
	}
	return NULL;
}

extern GtkWindow *window;

/* tl_window_frame: the height of the on-screen keyboard layer while it shows (0 otherwise) */
static int tl_window_frame_ime_height(void)
{
	extern GtkWidget *tl_main_overlay;
	if (!tl_main_overlay)
		return 0;
	for (GtkWidget *c = gtk_widget_get_first_child(tl_main_overlay); c; c = gtk_widget_get_next_sibling(c)) {
		if (!gtk_widget_has_css_class(c, "tl-osk") || !gtk_widget_get_visible(c))
			continue;
		int h = gtk_widget_get_height(c);
		if (h <= 0) { /* shown but not allocated yet: its requested height */
			int min = 0, nat = 0;
			gtk_widget_measure(c, GTK_ORIENTATION_VERTICAL, gtk_widget_get_width(tl_main_overlay), &min, &nat, NULL, NULL);
			h = nat;
		}
		return h;
	}
	return 0;
}

JNIEXPORT void JNICALL Java_android_view_View_getWindowVisibleDisplayFrame(JNIEnv *env, jobject this, jobject rect)
{
	int width;
	int height;
	/* tl_window_frame: the window as allocated (fullscreen, kiosk), not its default size */
	width = gtk_widget_get_width(GTK_WIDGET(window));
	height = gtk_widget_get_height(GTK_WIDGET(window));
	if (width <= 0 || height <= 0)
		gtk_window_get_default_size(window, &width, &height);
	height -= tl_window_frame_ime_height();
	if (height < 0)
		height = 0;
	_SET_INT_FIELD(rect, "left", 0);
	_SET_INT_FIELD(rect, "top", 0);
	_SET_INT_FIELD(rect, "right", width);
	_SET_INT_FIELD(rect, "bottom", height);
}

/* ---- teslalinux: view focus state from the window's GTK focus (tl_focus_state) ---- */

/* the widget that stands for the view in its parent: the WrapperWidget around the view's widget */
static GtkWidget *tl_focus_view_top(GtkWidget *widget)
{
	GtkWidget *wrapper = gtk_widget_get_parent(widget);
	return (wrapper && WRAPPER_IS_WIDGET(wrapper)) ? wrapper : widget;
}

/* the WrapperWidget of the view that owns the root's focus widget, or NULL */
static WrapperWidget *tl_focus_owner(GtkWidget *widget)
{
	GtkRoot *root = gtk_widget_get_root(widget);
	GtkWidget *focus = root ? gtk_root_get_focus(root) : NULL;
	for (GtkWidget *w = focus; w; w = gtk_widget_get_parent(w)) {
		if (WRAPPER_IS_WIDGET(w) && WRAPPER_WIDGET(w)->jobj)
			return WRAPPER_WIDGET(w);
	}
	return NULL;
}

JNIEXPORT jboolean JNICALL Java_android_view_View_nativeHasFocus(JNIEnv *env, jclass clazz, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkRoot *root = gtk_widget_get_root(widget);
	GtkWidget *focus = root ? gtk_root_get_focus(root) : NULL;
	if (!focus)
		return JNI_FALSE;
	GtkWidget *top = tl_focus_view_top(widget);
	return focus == top || gtk_widget_is_ancestor(focus, top);
}

JNIEXPORT jobject JNICALL Java_android_view_View_nativeFindFocus(JNIEnv *env, jclass clazz, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	WrapperWidget *owner = tl_focus_owner(widget);
	if (!owner)
		return NULL;
	GtkWidget *top = tl_focus_view_top(widget);
	if (GTK_WIDGET(owner) != top && !gtk_widget_is_ancestor(GTK_WIDGET(owner), top))
		return NULL;
	/* a weak reference: NULL once the view was collected */
	return (*env)->NewLocalRef(env, owner->jobj);
}

JNIEXPORT jboolean JNICALL Java_android_view_View_nativeClearFocus(JNIEnv *env, jclass clazz, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *top = tl_focus_view_top(widget);
	gboolean pending = g_object_get_data(G_OBJECT(widget), "tl-focus-pending") || g_object_get_data(G_OBJECT(top), "tl-focus-pending");
	g_object_set_data(G_OBJECT(widget), "tl-focus-pending", NULL);
	g_object_set_data(G_OBJECT(top), "tl-focus-pending", NULL);
	WrapperWidget *owner = tl_focus_owner(widget);
	if (owner && GTK_WIDGET(owner) == top) {
		gtk_root_set_focus(gtk_widget_get_root(widget), NULL);
		if (getenv("ATL_DEBUG_FOCUS"))
			fprintf(stderr, "[focus] clearFocus %s: window focus dropped\n", G_OBJECT_TYPE_NAME(widget));
		return JNI_TRUE;
	}
	return pending;
}

/* ---- teslalinux: touch mode input hooks (tl_touch_mode), connected in main.c ---- */
int map_key_code(int key_code);
int map_meta_state(GdkModifierType state);

/* pointer DOWN (button press, touch begin) or SCROLL: AOSP enters touch mode, and every app window
 * watching outside touches whose frame does not contain the point gets ACTION_OUTSIDE (app windows
 * are layers of this window, WindowManagerImpl); the event goes on to the views */
gboolean tl_touch_mode_pointer_event(GtkEventControllerLegacy *controller, GdkEvent *event, gpointer data)
{
	GdkEventType type = gdk_event_get_event_type(event);
	if (type != GDK_BUTTON_PRESS && type != GDK_TOUCH_BEGIN && type != GDK_SCROLL)
		return FALSE;
	double x = 0, y = 0;
	gdk_event_get_position(event, &x, &y);
	JNIEnv *env = get_jni_env();
	static jmethodID pointer_down = NULL;
	if (!pointer_down)
		pointer_down = _STATIC_METHOD(handle_cache.view.class, "tlPointerDown", "(FF)V");
	(*env)->CallStaticVoidMethod(env, handle_cache.view.class, pointer_down, (jfloat)x, (jfloat)y);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	return FALSE;
}

/* a key, before any view: AOSP ViewRootImpl leaves touch mode on navigation and typing keys (and may
 * consume the key for that), and the key goes to the focused window, which is an app-added
 * focusable window above the activity when one exists (WindowManagerImpl); TRUE when consumed.
 * The host keyboard's Escape is this platform's BACK key, as in the window-level dispatch. */
static gboolean tl_touch_mode_key(guint keyval, GdkModifierType state, int action)
{
	JNIEnv *env = get_jni_env();
	static jmethodID check = NULL, to_window = NULL;
	static jclass wm = NULL;
	if (!check)
		check = _STATIC_METHOD(handle_cache.view.class, "tlCheckForLeavingTouchModeAndConsume", "(Landroid/view/KeyEvent;)Z");
	if (!wm) {
		wm = _REF((*env)->FindClass(env, "android/view/WindowManagerImpl"));
		to_window = _STATIC_METHOD(wm, "tlDispatchKeyToFocusedWindow", "(Landroid/view/KeyEvent;)Z");
	}
	int code = keyval == GDK_KEY_Escape ? 4 /* KEYCODE_BACK */ : map_key_code(keyval);
	jobject key_event = (*env)->NewObject(env, handle_cache.key_event.class, handle_cache.key_event.constructor,
	                                      (jlong)0, (jlong)0, action, code, 0, map_meta_state(state));
	_SET_INT_FIELD(key_event, "unicodeValue", gdk_keyval_to_unicode(keyval));
	gboolean consumed = FALSE;
	if (action == 0 /* ACTION_DOWN */)
		consumed = (*env)->CallStaticBooleanMethod(env, handle_cache.view.class, check, key_event);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		consumed = FALSE;
	}
	if (!consumed)
		consumed = (*env)->CallStaticBooleanMethod(env, wm, to_window, key_event);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		consumed = FALSE;
	}
	(*env)->DeleteLocalRef(env, key_event);
	return consumed;
}

gboolean tl_touch_mode_key_pressed(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, gpointer data)
{
	return tl_touch_mode_key(keyval, state, 0 /* ACTION_DOWN */);
}

void tl_touch_mode_key_released(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, gpointer data)
{
	tl_touch_mode_key(keyval, state, 1 /* ACTION_UP */);
}
