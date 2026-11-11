#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"
#include "../generated_headers/android_view_ViewTreeObserver.h"

static void on_global_layout_callback(GdkFrameClock *clock, jobject view_tree_observer)
{
	JNIEnv *env = get_jni_env();
	J__ViewTreeObserver__tlDispatchFrameGlobalLayout(env, view_tree_observer); /* tl_vto_frame */
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
}

static void on_pre_draw_callback(GdkFrameClock *clock, jobject view_tree_observer)
{
	JNIEnv *env = get_jni_env();
	J__ViewTreeObserver__dispatchOnPreDraw(env, view_tree_observer);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
}

extern GtkWidget *window;

void _gdb_force_java_stack_trace(void);

JNIEXPORT void JNICALL Java_android_view_ViewTreeObserver_native_1set_1have_1global_1layout_1listeners(JNIEnv *env, jobject this, jboolean have_listeners)
{
	GtkWidget *window = _PTR(_GET_LONG_FIELD(this, "window"));

	if (!window) {
		fprintf(stderr, "Java_android_view_ViewTreeObserver_native_1set_1have_1global_1layout_1listeners: no window\n");
		return;
	}

	gulong signal_handle = _GET_LONG_FIELD(this, "onGlobalLayout_signal_handle");
	GdkFrameClock *clock = gtk_widget_get_frame_clock(window);
	if (!clock) {
		/* tl_vto_idempotent: not realized, nothing to connect; the handle is stale if any */
		_SET_LONG_FIELD(this, "onGlobalLayout_signal_handle", 0);
		return;
	}

	if (have_listeners && !signal_handle) {
		/* GTKs own handler is always the first, so our handler gets called after all widgets have been measured and allocated */
		signal_handle = g_signal_connect(G_OBJECT(clock), "layout", G_CALLBACK(on_global_layout_callback), _REF(this)); // FIXME: cleanup callback for _UNREF
		_SET_LONG_FIELD(this, "onGlobalLayout_signal_handle", signal_handle);
	} else if (!have_listeners && signal_handle) {
		if (g_signal_handler_is_connected(G_OBJECT(clock), signal_handle))
			g_signal_handler_disconnect(G_OBJECT(clock), signal_handle);
		_SET_LONG_FIELD(this, "onGlobalLayout_signal_handle", 0);
	} else if (getenv("ATL_DEBUG_LAYOUT")) {
		/* tl_vto_idempotent: AOSP's listener list makes this a no-op (a second removal, an add while listening) */
		fprintf(stderr, "Java_android_view_ViewTreeObserver_native_1set_1have_1global_1layout_1listeners: redundant: have_listeners: %d, signal_handle: 0x%016w64x\n", have_listeners, signal_handle);
	}
}

/* ---- teslalinux: Choreographer vsync from the GdkFrameClock (tl_vsync) ---- */
static jobject tl_vsync_choreographer = NULL; /* global ref of the main looper's Choreographer */
static jmethodID tl_vsync_method = NULL;
static gboolean tl_vsync_pending = FALSE;
static GdkFrameClock *tl_vsync_clock = NULL;
static gulong tl_vsync_handler = 0;

static void tl_on_frame_update(GdkFrameClock *clock, gpointer data)
{
	if (!tl_vsync_pending || !tl_vsync_choreographer)
		return;
	tl_vsync_pending = FALSE;
	JNIEnv *env = get_jni_env();
	jlong frame_time_ns = (jlong)gdk_frame_clock_get_frame_time(clock) * 1000;
	if (getenv("ATL_DEBUG_VSYNC")) {
		static int n = 0;
		static gint64 t0 = 0;
		if (n == 0)
			t0 = g_get_monotonic_time();
		if (++n % 120 == 0) {
			gint64 t = g_get_monotonic_time();
			fprintf(stderr, "[vsync] %d frames, %.1f Hz over the last 120\n", n, 120.0 * 1000000.0 / (double)(t - t0));
			t0 = t;
		}
	}
	(*env)->CallVoidMethod(env, tl_vsync_choreographer, tl_vsync_method, frame_time_ns);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static gint64 tl_vsync_requested_at = 0; /* when the pending request was made */
static gint64 tl_vsync_requested_counter = 0;
/* watchdog: a request the frame clock has not served for 500 ms (the clock is frozen until the
 * compositor's first frame callback right after map) is requested again, so the Choreographer can
 * never wait forever for a vsync; ATL_DEBUG_VSYNC reports it */
static gboolean tl_vsync_watchdog(gpointer data)
{
	if (tl_vsync_pending && tl_vsync_clock) {
		gint64 waited = g_get_monotonic_time() - tl_vsync_requested_at;
		if (waited > 500000) {
			if (getenv("ATL_DEBUG_VSYNC"))
				fprintf(stderr, "[vsync] STALLED: request pending for %.0f ms, frame counter %" G_GINT64_FORMAT " (was %" G_GINT64_FORMAT "), window mapped=%d visible=%d\n",
				        waited / 1000.0, gdk_frame_clock_get_frame_counter(tl_vsync_clock), tl_vsync_requested_counter,
				        window ? gtk_widget_get_mapped(window) : -1, window ? gtk_widget_get_visible(window) : -1);
			gdk_frame_clock_request_phase(tl_vsync_clock, GDK_FRAME_CLOCK_PHASE_UPDATE);
		}
	}
	return G_SOURCE_CONTINUE;
}
JNIEXPORT jboolean JNICALL Java_android_view_Choreographer_nativeScheduleVsync(JNIEnv *env, jobject this)
{
	static int calls = 0;
	static guint watchdog = 0;
	GdkFrameClock *clock = window ? gtk_widget_get_frame_clock(window) : NULL;
	if (getenv("ATL_DEBUG_VSYNC") && ++calls <= 3)
		fprintf(stderr, "[vsync] scheduleVsync #%d window=%p clock=%p\n", calls, (void *)window, (void *)clock);
	if (!watchdog)
		watchdog = g_timeout_add(1000, tl_vsync_watchdog, NULL);
	if (!clock)
		return JNI_FALSE;
	tl_vsync_requested_at = g_get_monotonic_time();
	tl_vsync_requested_counter = gdk_frame_clock_get_frame_counter(clock);
	if (tl_vsync_clock != clock) {
		if (tl_vsync_clock) {
			g_signal_handler_disconnect(tl_vsync_clock, tl_vsync_handler);
			g_object_unref(tl_vsync_clock);
		}
		tl_vsync_clock = g_object_ref(clock);
		tl_vsync_handler = g_signal_connect(clock, "update", G_CALLBACK(tl_on_frame_update), NULL);
	}
	if (!tl_vsync_choreographer || !(*env)->IsSameObject(env, tl_vsync_choreographer, this)) {
		if (tl_vsync_choreographer)
			(*env)->DeleteGlobalRef(env, tl_vsync_choreographer);
		tl_vsync_choreographer = (*env)->NewGlobalRef(env, this);
		tl_vsync_method = _METHOD(_CLASS(this), "tlOnVsync", "(J)V");
	}
	tl_vsync_pending = TRUE;
	gdk_frame_clock_request_phase(clock, GDK_FRAME_CLOCK_PHASE_UPDATE);
	return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_android_view_ViewTreeObserver_native_1set_1have_1predraw_1listeners(JNIEnv *env, jobject this, jboolean have_listeners)
{
	GtkWidget *window = _PTR(_GET_LONG_FIELD(this, "window"));

	if (!window) {
		fprintf(stderr, "Java_android_view_ViewTreeObserver_native_1set_1have_1predraw_1listeners: no window\n");
		return;
	}

	gulong signal_handle = _GET_LONG_FIELD(this, "onPreDraw_signal_handle");
	GdkFrameClock *clock = gtk_widget_get_frame_clock(window);
	if (!clock) {
		/* tl_vto_idempotent: not realized, nothing to connect; the handle is stale if any */
		_SET_LONG_FIELD(this, "onPreDraw_signal_handle", 0);
		return;
	}

	if (have_listeners && !signal_handle) {
		/* Unlike AOSP, our hook gets invoked after all widgets have been drawn. */
		/* Switching to ::layout would fix the timing, but we would miss frames where no layout was requested. */
		/* TODO: Hook into the DecorView snapshot function to get in before widget drawing. */
		signal_handle = g_signal_connect(G_OBJECT(clock), "paint", G_CALLBACK(on_pre_draw_callback), _REF(this)); // FIXME: cleanup callback for _UNREF
		_SET_LONG_FIELD(this, "onPreDraw_signal_handle", signal_handle);
	} else if (!have_listeners && signal_handle) {
		if (g_signal_handler_is_connected(G_OBJECT(clock), signal_handle))
			g_signal_handler_disconnect(G_OBJECT(clock), signal_handle);
		_SET_LONG_FIELD(this, "onPreDraw_signal_handle", 0);
	} else if (getenv("ATL_DEBUG_LAYOUT")) {
		/* tl_vto_idempotent: AOSP's listener list makes this a no-op (a second removal, an add while listening) */
		fprintf(stderr, "Java_android_view_ViewTreeObserver_native_1set_1have_1predraw_1listeners: redundant: have_listeners: %d, signal_handle: 0x%016w64x\n", have_listeners, signal_handle);
	}
}
