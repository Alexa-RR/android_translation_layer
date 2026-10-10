#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"
#include "../generated_headers/android_view_ViewTreeObserver.h"

static void on_global_layout_callback(GdkFrameClock *clock, jobject view_tree_observer)
{
	JNIEnv *env = get_jni_env();
	(*env)->CallVoidMethod(env, view_tree_observer, handle_cache.view_tree_observer.dispatchOnGlobalLayout);
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

	if (have_listeners && !signal_handle) {
		/* this adds our callback before the existing handler, which means we effectively execute before the paint phase (after layout) */
		signal_handle = g_signal_connect(G_OBJECT(clock), "paint", G_CALLBACK(on_global_layout_callback), _REF(this)); // FIXME: cleanup callback for _UNREF
		_SET_LONG_FIELD(this, "onGlobalLayout_signal_handle", signal_handle);
	} else if (!have_listeners && signal_handle) {
		g_signal_handler_disconnect(G_OBJECT(clock), signal_handle);
		_SET_LONG_FIELD(this, "onGlobalLayout_signal_handle", 0);
	} else {
		fprintf(stderr, "Java_android_view_ViewTreeObserver_native_1set_1have_1global_1layout_1listeners: invalid state: have_listeners: %d, signal_handle: 0x%016w64x\n", have_listeners, signal_handle);
		exit(1);
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

JNIEXPORT jboolean JNICALL Java_android_view_Choreographer_nativeScheduleVsync(JNIEnv *env, jobject this)
{
	static int calls = 0;
	GdkFrameClock *clock = window ? gtk_widget_get_frame_clock(window) : NULL;
	if (getenv("ATL_DEBUG_VSYNC") && ++calls <= 3)
		fprintf(stderr, "[vsync] scheduleVsync #%d window=%p clock=%p\n", calls, (void *)window, (void *)clock);
	if (!clock)
		return JNI_FALSE;
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
