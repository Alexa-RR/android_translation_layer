#include <stdio.h>
#include <stdlib.h>
#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"

#include "../views/AndroidLayout.h"
#include "../widgets/WrapperWidget.h"

#include "../generated_headers/android_view_View.h"
#include "../generated_headers/android_view_ViewGroup.h"

JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1addView(JNIEnv *env, jobject this, jlong widget, jlong child, jint index, jobject layout_params)
{
	if (layout_params) {
		/*
		GtkWidget *_child = gtk_widget_get_parent(GTK_WIDGET(_PTR(child)));
		jint child_width = -1;
		jint child_height = -1;

		jint child_width = _GET_INT_FIELD(layout_params, "width");
		jint child_height = _GET_INT_FIELD(layout_params, "height");

		jint child_gravity = _GET_INT_FIELD(layout_params, "gravity");

		if(child_width > 0)
			g_object_set(G_OBJECT(_child), "width-request", child_width, NULL);
		if(child_height > 0)
			g_object_set(G_OBJECT(_child), "height-request", child_height, NULL);

		if(child_gravity != -1) {
			printf(":::-: setting child gravity: %d", child_gravity);
			Java_android_view_View_setGravity(env, child, child_gravity);
		}*/
	}
	GtkWidget *parent = _PTR(widget);
	GtkWidget *iter = gtk_widget_get_first_child(parent);
	for (int i = 0; i < index; i++) {
		iter = gtk_widget_get_next_sibling(iter);
		if (iter == NULL)
			break;
	}

	gtk_widget_insert_before(gtk_widget_get_parent(GTK_WIDGET(_PTR(child))), parent, iter);
}

JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1removeView(JNIEnv *env, jobject this, jlong widget, jlong child)
{
	gtk_widget_unparent(gtk_widget_get_parent(GTK_WIDGET(_PTR(child))));
}

/* teslalinux: a SurfaceView below `child` has a frame nobody snapshotted yet: flag the path from it
 * up to `child` so the following gtk_widget_snapshot_child() re-runs the snapshot functions instead
 * of appending cached render nodes. Depth-limited walk; SurfaceView trees are shallow. */
static void tl_prepare_child_snapshot(GtkWidget *child, GtkWidget *w, int depth)
{
	if (depth > 8)
		return;
	if (g_object_get_data(G_OBJECT(w), "tl-frame-pending")) {
		for (GtkWidget *a = w; a; a = gtk_widget_get_parent(a)) {
			gtk_widget_queue_draw(a);
			if (a == child)
				break;
		}
		return;
	}
	for (GtkWidget *c = gtk_widget_get_first_child(w); c; c = gtk_widget_get_next_sibling(c))
		tl_prepare_child_snapshot(child, c, depth + 1);
}

JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1drawChildren(JNIEnv *env, jobject this, jlong widget_ptr, jlong snapshot_ptr)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr))));
	GdkSnapshot *snapshot = GDK_SNAPSHOT(_PTR(snapshot_ptr));
	tl_prepare_child_snapshot(wrapper->child, wrapper->child, 0);
	/* tl_layout_clip: AOSP dispatchDraw clips the children to the padding box (clipToPadding) */
	const gboolean tl_padding_clip = tl_wrapper_push_padding_clip(wrapper, snapshot);
	tl_snapshot_child(&wrapper->parent_instance, wrapper->child, snapshot);
	if (tl_padding_clip)
		gtk_snapshot_pop(snapshot);
}

JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1drawChild(JNIEnv *env, jobject this, jlong widget_ptr, jlong child_ptr, jlong snapshot_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *child = gtk_widget_get_parent(GTK_WIDGET(_PTR(child_ptr)));
	GdkSnapshot *snapshot = GDK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_widget_queue_draw(child); // FIXME: why didn't compose UI invalidate the child?
	tl_prepare_child_snapshot(child, child, 0);
	tl_snapshot_child(widget, child, snapshot);
}

/* FIXME: put this in a header */
G_DECLARE_FINAL_TYPE(JavaWidget, java_widget, JAVA, WIDGET, GtkWidget)
bool view_dispatch_motionevent(JNIEnv *env, WrapperWidget *wrapper, GtkPropagationPhase phase, jobject motion_event, gpointer event, int action);

extern bool tl_force_plain_dispatch;
static bool dispatch_motionevent_if_JavaWidget(GtkWidget *widget, GtkPropagationPhase phase, jobject motion_event, GtkWidget *toplevel)
{
	/* tl_view_widget_dispatch: any view's own widget (its WrapperWidget's child), not only JavaWidget:
	 * a SurfaceView's widget is a GtkGraphicsOffload, a TextView's a label */
	GtkWidget *view_parent = gtk_widget_get_parent(widget);
	if (!view_parent || !WRAPPER_IS_WIDGET(view_parent) || WRAPPER_WIDGET(view_parent)->child != widget
	    || !WRAPPER_WIDGET(view_parent)->jobj)
		return false;
	JNIEnv *env = get_jni_env();

	WrapperWidget *wrapper = WRAPPER_WIDGET(view_parent);
	if (getenv("ATL_DEBUG_TOUCH")) {
		jint n = J__MotionEvent__getPointerCount(env, motion_event);
		jint act = J__MotionEvent__getAction(env, motion_event);
		fprintf(stderr, "[touch]   view widget %s phase=%d action=0x%x pointers=%d", G_OBJECT_TYPE_NAME(widget), phase, act, n);
		for (jint i = 0; i < n && i < 4; i++)
			fprintf(stderr, " p%d=%.0f,%.0f", i, J__MotionEvent__getX_pointer(env, motion_event, i), J__MotionEvent__getY_pointer(env, motion_event, i));
		fprintf(stderr, "\n");
	}
	bool plain = false;
	if (widget == toplevel && wrapper->custom_dispatch_touch) {
		/* the group that is dispatching: its own onInterceptTouchEvent/onTouchEvent, never its
		 * dispatchTouchEvent again (AOSP super.dispatchTouchEvent after the children declined) */
		tl_force_plain_dispatch = true;
		plain = true;
	}
	int action = _GET_INT_FIELD(motion_event, "action");
	/* tl_relocate_event: the event is in the coordinates of `toplevel`; the view expects its own */
	jfloat orig_x = J__MotionEvent__getX(env, motion_event);
	jfloat orig_y = J__MotionEvent__getY(env, motion_event);
	graphene_point_t local;
	bool relocated = false;
	if (widget != toplevel && gtk_widget_compute_point(toplevel, widget, &GRAPHENE_POINT_INIT(orig_x, orig_y), &local)) {
		J__MotionEvent__setLocation(env, motion_event, local.x, local.y);
		relocated = true;
	}
	bool ret = view_dispatch_motionevent(env, wrapper, phase, motion_event, motion_event, action);
	if (plain)
		tl_force_plain_dispatch = false;
	if (relocated)
		J__MotionEvent__setLocation(env, motion_event, orig_x, orig_y);
	return ret;
}

/* based on gtk_propagate_event_internal © GTK Team (its GdkArray widget stack is a GPtrArray here) */
bool atl_propagate_synthetic_motionevent(GtkWidget *widget, jobject motionevent, GtkWidget *toplevel)
{
	int handled_event = false;
	GPtrArray *widget_array;
	int i;

	/* First, propagate event down */
	widget_array = g_ptr_array_new_full(16, g_object_unref);
	g_ptr_array_add(widget_array, g_object_ref(widget));

	for (;;) {
		widget = gtk_widget_get_parent(widget);
		if (!widget)
			break;

		if (widget == toplevel)
			break;

		g_ptr_array_add(widget_array, g_object_ref(widget));
	}

	i = widget_array->len - 1;
	for (;;) {
		widget = g_ptr_array_index(widget_array, i);

		if (!gtk_widget_is_sensitive(widget)) {
			handled_event = true;
		} else if (gtk_widget_get_realized(widget))
			handled_event = dispatch_motionevent_if_JavaWidget(widget, GTK_PHASE_CAPTURE, motionevent, toplevel);

		handled_event |= !gtk_widget_get_realized(widget);

		if (handled_event)
			break;

		if (i == 0)
			break;

		i--;
	}

	/* If not yet handled, also propagate back up */
	if (!handled_event) {
		/* Propagate event up the widget tree so that
		 * parents can see the button and motion
		 * events of the children.
		 */
		for (i = 0; i < (int)widget_array->len; i++) {
			widget = g_ptr_array_index(widget_array, i);

			/* Scroll events are special cased here because it
			 * feels wrong when scrolling a GtkViewport, say,
			 * to have children of the viewport eat the scroll
			 * event
			 */
			if (!gtk_widget_is_sensitive(widget))
				handled_event = true;
			else if (gtk_widget_get_realized(widget))
				handled_event = dispatch_motionevent_if_JavaWidget(widget, GTK_PHASE_BUBBLE, motionevent, toplevel);

			handled_event |= !gtk_widget_get_realized(widget);

			if (handled_event)
				break;
		}
	}

	g_ptr_array_unref(widget_array);
	return handled_event;
}

/* tl_pick_trace: why the pick stopped where it did (ATL_DEBUG_TOUCH) */
static const char *tl_pick_java_class(GtkWidget *w)
{
	static char buf[128];
	GtkWidget *wrapper = WRAPPER_IS_WIDGET(w) ? w : gtk_widget_get_parent(w);
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper) || !WRAPPER_WIDGET(wrapper)->jobj)
		return "-";
	JNIEnv *env = get_jni_env();
	jclass cls = (*env)->GetObjectClass(env, WRAPPER_WIDGET(wrapper)->jobj);
	jmethodID get_name = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, cls), "getName", "()Ljava/lang/String;");
	jstring name = (*env)->CallObjectMethod(env, cls, get_name);
	const char *str = (*env)->GetStringUTFChars(env, name, NULL);
	g_strlcpy(buf, str, sizeof(buf));
	(*env)->ReleaseStringUTFChars(env, name, str);
	return buf;
}
static void tl_pick_trace(GtkWidget *picked, double x, double y, GtkWidget *from)
{
	graphene_point_t p;
	if (!gtk_widget_compute_point(from, picked, &GRAPHENE_POINT_INIT(x, y), &p))
		p = GRAPHENE_POINT_INIT(-1, -1);
	fprintf(stderr, "[touch]   picked %s java=%s alloc=%dx%d local=%.0f,%.0f contains=%d children:%c", G_OBJECT_TYPE_NAME(picked), tl_pick_java_class(picked),
	        gtk_widget_get_width(picked), gtk_widget_get_height(picked), p.x, p.y, gtk_widget_contains(picked, p.x, p.y), 10);
	for (GtkWidget *c = gtk_widget_get_first_child(picked); c; c = gtk_widget_get_next_sibling(c)) {
		graphene_point_t cp;
		gboolean ok = gtk_widget_compute_point(picked, c, &p, &cp);
		fprintf(stderr, "[touch]     child %s java=%s alloc=%dx%d target=%d sensitive=%d mapped=%d visible=%d local=%.0f,%.0f contains=%d%c", G_OBJECT_TYPE_NAME(c), tl_pick_java_class(c),
		        gtk_widget_get_width(c), gtk_widget_get_height(c), gtk_widget_get_can_target(c), gtk_widget_is_sensitive(c), gtk_widget_get_mapped(c), gtk_widget_get_visible(c),
		        ok ? cp.x : -1.0, ok ? cp.y : -1.0, ok ? gtk_widget_contains(c, cp.x, cp.y) : -1, 10);
	}
}

JNIEXPORT jboolean JNICALL Java_android_view_ViewGroup_native_1dispatchTouchEvent(JNIEnv *env, jobject this, jlong widget_ptr, jobject motion_event, jdouble x, jdouble y)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	/* tl_next_child: AOSP tries the children under the point one after the other, topmost first */
	GtkWidget *hidden[8];
	gboolean hidden_target[8];
	int hidden_n = 0;
	bool handled = false;
	for (int attempt = 0; attempt < 8; attempt++) {
		GtkWidget *picked_child = gtk_widget_pick(widget, x, y, GTK_PICK_DEFAULT);
		if (getenv("ATL_DEBUG_TOUCH"))
			fprintf(stderr, "[touch] native_dispatchTouchEvent on %s (%dx%d) at %.0f,%.0f picks %s (attempt %d)%c", G_OBJECT_TYPE_NAME(widget), gtk_widget_get_width(widget), gtk_widget_get_height(widget), x, y, picked_child ? G_OBJECT_TYPE_NAME(picked_child) : "nothing", attempt, 10);
		if (!picked_child || picked_child == widget)
			break;
		if (getenv("ATL_DEBUG_TOUCH"))
			tl_pick_trace(picked_child, x, y, widget);
		handled = atl_propagate_synthetic_motionevent(picked_child, motion_event, widget);
		if (handled)
			break;
		/* this child declined: hide it from the pick and look for the next one under the point */
		hidden[hidden_n] = picked_child;
		hidden_target[hidden_n] = gtk_widget_get_can_target(picked_child);
		hidden_n++;
		gtk_widget_set_can_target(picked_child, FALSE);
	}
	for (int i = 0; i < hidden_n; i++)
		gtk_widget_set_can_target(hidden[i], hidden_target[i]);
	return handled;
}

/* ---- teslalinux: tl_layout_clip ---- */
/* AOSP RenderNode.setClipToBounds(parent clipChildren): whether the child's drawing (its own and its
 * descendants') is clipped to the child's bounds */
JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1setChildClipsToBounds(JNIEnv *env, jobject this, jlong child_ptr, jboolean clip)
{
	GtkWidget *wrapper = gtk_widget_get_parent(GTK_WIDGET(_PTR(child_ptr)));
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper))
		return;
	gboolean was_clipped = !g_object_get_data(G_OBJECT(wrapper), "tl-no-clip");
	if (was_clipped == !!clip)
		return;
	g_object_set_data(G_OBJECT(wrapper), "tl-no-clip", clip ? NULL : GINT_TO_POINTER(1));
	if (!clip)
		gtk_widget_set_overflow(wrapper, GTK_OVERFLOW_VISIBLE);
	else if (g_object_get_data(G_OBJECT(wrapper), "on_touch_listener"))
		gtk_widget_set_overflow(wrapper, GTK_OVERFLOW_HIDDEN); /* what _setOnTouchListener sets */
	gtk_widget_queue_draw(wrapper);
}

/* AOSP FLAG_CLIP_TO_PADDING: the group's children are clipped to its padding box when drawn */
JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1setPaddingClip(JNIEnv *env, jobject this, jlong widget_ptr, jboolean enabled)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *wrapper = gtk_widget_get_parent(widget);
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper))
		return;
	g_object_set_data(G_OBJECT(wrapper), "tl-padding-clip", enabled ? GINT_TO_POINTER(1) : NULL);
	gtk_widget_queue_draw(widget);
	gtk_widget_queue_draw(wrapper);
}

/* ---- teslalinux: tl_restack_child ---- */
JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1restackChild(JNIEnv *env, jobject this, jlong widget_ptr, jlong child_ptr, jlong next_ptr)
{
	GtkWidget *parent = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *child = gtk_widget_get_parent(GTK_WIDGET(_PTR(child_ptr)));
	GtkWidget *next = next_ptr ? gtk_widget_get_parent(GTK_WIDGET(_PTR(next_ptr))) : NULL;
	if (!child || gtk_widget_get_parent(child) != parent || child == next)
		return;
	if (next && gtk_widget_get_parent(next) != parent)
		return;
	if (gtk_widget_get_next_sibling(child) == next)
		return; /* already in place (next NULL: already the last) */
	gtk_widget_insert_before(child, parent, next);
	gtk_widget_queue_draw(parent);
}
