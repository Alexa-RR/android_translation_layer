#include <gtk/gtk.h>
#include <libportal/portal.h>

#include <jni.h>
#include <string.h>

#include "../defines.h"
#include "../util.h"
#include "../generated_headers/android_app_Activity.h"
#include "../../main-executable/back_button.h"
#include "android_app_Activity.h"

static GList *activity_backlog = NULL;
static jobject activity_current = NULL;

/* tl_window_focus: Activity.tlWindowFocusChanged dispatches to the decor views, then onWindowFocusChanged */
static jmethodID tl_window_focus_method(JNIEnv *env)
{
	static jmethodID method = NULL;
	if (!method)
		method = _METHOD(handle_cache.activity.class, "tlWindowFocusChanged", "(Z)V");
	return method;
}

static void activity_close(JNIEnv *env, jobject activity)
{
	// in case some exception was left unhandled in native code, print it here so we don't confuse it with an exception thrown by onDestroy
	if ((*env)->ExceptionCheck(env)) {
		fprintf(stderr, "activity.onDestroy: seems there was a pending exception... :");
		(*env)->ExceptionDescribe(env);
	}

	/* -- run the activity's onDestroy -- */
	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onDestroy);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

/* teslalinux: the transition is split into AOSP's steps (native-activity-order.sh) */
static void activity_pause(JNIEnv *env, jobject activity)
{
	if (!_GET_BOOL_FIELD(activity, "paused")) {
		(*env)->CallVoidMethod(env, activity, handle_cache.activity.onPause);
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);
	}
}

static void activity_window_focus(JNIEnv *env, jobject activity, jboolean focused)
{
	(*env)->CallVoidMethod(env, activity, tl_window_focus_method(env), focused);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void activity_stop(JNIEnv *env, jobject activity)
{
	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onStop);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

/* the activity stays current (a start that was abandoned): back to resumed */
static void activity_resume_only(JNIEnv *env, jobject activity)
{
	if (_GET_BOOL_FIELD(activity, "finishing") || !_GET_BOOL_FIELD(activity, "paused"))
		return;
	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onResume);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onPostResume);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void activity_unfocus(JNIEnv *env, jobject activity)
{
	activity_pause(env, activity);
	activity_stop(env, activity);
	activity_window_focus(env, activity, false);
}

static void activity_focus(JNIEnv *env, jobject activity)
{
	if (_GET_BOOL_FIELD(activity, "finishing"))
		return;

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onStart);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	if (_GET_BOOL_FIELD(activity, "finishing"))
		return;

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onResume);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	if (_GET_BOOL_FIELD(activity, "finishing"))
		return;

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onPostResume);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	if (_GET_BOOL_FIELD(activity, "finishing"))
		return;

	(*env)->CallVoidMethod(env, activity, tl_window_focus_method(env), true);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void activity_update_current(JNIEnv *env)
{
	jobject activity_new = activity_backlog ? g_list_first(activity_backlog)->data : NULL;

	if (activity_current != activity_new) {
		jobject activity_old = activity_current;
		if (activity_old) {
			activity_pause(env, activity_old);
			activity_window_focus(env, activity_old, false);
		}
		activity_current = NULL;

		if (activity_new)
			activity_focus(env, activity_new);
		if (activity_old)
			activity_stop(env, activity_old);
		if (activity_new && _GET_BOOL_FIELD(activity_new, "finishing"))
			return;

		activity_current = activity_new;
	}

	if (activity_current != NULL) {
		jclass current_activity_class = (*env)->GetObjectClass(env, activity_current);
		jmethodID current_activity_on_back_pressed_method_id = (*env)->GetMethodID(env, current_activity_class, "onBackPressed", "()V");
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);

		if (g_list_length(activity_backlog) > 1 || handle_cache.activity.onBackPressed != current_activity_on_back_pressed_method_id) {
			back_button_set_sensitive(true);
		} else {
			back_button_set_sensitive(false);
		}
	}
}

void activity_window_ready(void)
{
	JNIEnv *env = get_jni_env();

	for (GList *l = activity_backlog; l != NULL; l = l->next) {
		(*env)->CallVoidMethod(env, l->data, tl_window_focus_method(env), true);
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);
	}
}

void current_activity_back_pressed(void)
{
	JNIEnv *env = get_jni_env();

	jclass current_activity_class = (*env)->GetObjectClass(env, activity_current);
	jmethodID current_activity_on_back_pressed_method_id = (*env)->GetMethodID(env, current_activity_class, "onBackPressed", "()V");
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	// Either a new activity was added to the backlog or the current activity's onBackPressed method was changed
	if (g_list_length(activity_backlog) > 1 || handle_cache.activity.onBackPressed != current_activity_on_back_pressed_method_id) {
		(*env)->CallVoidMethod(env, activity_current, handle_cache.activity.onBackPressed);
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);
	} else {
		back_button_set_sensitive(false);
	}
}

void activity_close_all(void)
{
	GList *activities, *l;
	JNIEnv *env = get_jni_env();
	// local backup of the backlog
	activities = activity_backlog;
	// deactivate all activities
	activity_backlog = NULL;
	activity_update_current(env);
	// destroy all activities
	for (l = activities; l != NULL; l = l->next) {
		activity_close(env, l->data);
		_UNREF(l->data);
	}
	g_list_free(activities);
}

static gboolean delayed_close_window(gpointer window)
{
	if (activity_backlog == NULL && GTK_IS_WINDOW(window))
		gtk_window_close(GTK_WINDOW(window));
	return G_SOURCE_REMOVE;
}

void activity_start(JNIEnv *env, jobject activity_object)
{
	/* AOSP: the previous activity is paused first; it is stopped only once the new one is resumed */
	jobject activity_previous = activity_current;
	if (activity_previous)
		activity_pause(env, activity_previous);
	activity_current = NULL;
	/* -- run the activity's onCreate -- */
	(*env)->CallVoidMethod(env, activity_object, handle_cache.activity.onCreate, NULL);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	if (_GET_BOOL_FIELD(activity_object, "finishing")) { // finish() was called before the activity was created
		activity_current = activity_previous;
		if (activity_previous)
			activity_resume_only(env, activity_previous);
		return;
	}

	(*env)->CallVoidMethod(env, activity_object, handle_cache.activity.onPostCreate, NULL);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	activity_backlog = g_list_prepend(activity_backlog, _REF(activity_object));

	if (activity_previous)
		activity_window_focus(env, activity_previous, false);
	activity_update_current(env);
	if (activity_previous && activity_previous != activity_current)
		activity_stop(env, activity_previous);
}

JNIEXPORT void JNICALL Java_android_app_Activity_nativeFinish(JNIEnv *env, jobject this, jlong window)
{
	GList *l;
	jobject removed_activity = NULL;
	for (l = activity_backlog; l != NULL; l = l->next) {
		if ((*env)->IsSameObject(env, this, l->data)) {
			removed_activity = l->data;
			activity_backlog = g_list_delete_link(activity_backlog, l);
			break;
		}
	}
	activity_update_current(env);
	if (removed_activity) {
		activity_close(env, removed_activity);
		_UNREF(removed_activity);
	}
	if (activity_backlog == NULL && window)
		g_idle_add(delayed_close_window, _PTR(window));
}

JNIEXPORT void JNICALL Java_android_app_Activity_nativeStartActivity(JNIEnv *env, jclass class, jobject activity)
{
	activity_start(env, activity);
}

JNIEXPORT jboolean JNICALL Java_android_app_Activity_nativeResumeActivity(JNIEnv *env, jclass class, jclass activity_class, jobject intent)
{
	GList *l;
	GList *activities_to_close = NULL;
	jboolean found = JNI_FALSE;
	for (l = activity_backlog; l != NULL; l = l->next) {
		if ((*env)->IsSameObject(env, activity_class, _CLASS(l->data))) {
			if (l != activity_backlog) {
				activities_to_close = activity_backlog;
				activity_backlog = l;
				l->prev->next = NULL;
				l->prev = NULL;
			}

			/* -- run the activity's onNewIntent -- */
			(*env)->CallVoidMethod(env, l->data, handle_cache.activity.onNewIntent, intent);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);
			found = JNI_TRUE;
			break;
		}
	}
	activity_update_current(env);

	for (l = activities_to_close; l != NULL; l = l->next) {
		activity_close(env, l->data);
		_UNREF(l->data);
	}
	g_list_free(activities_to_close);

	return found;
}

JNIEXPORT void JNICALL Java_android_app_Activity_nativeOpenURI(JNIEnv *env, jclass class, jstring uriString)
{
	static XdpPortal *portal = NULL;
	if (!portal) {
		portal = xdp_portal_new();
	}

	const char *uri = (*env)->GetStringUTFChars(env, uriString, NULL);
	xdp_portal_open_uri(portal, NULL, uri, XDP_OPEN_URI_FLAG_NONE, NULL, NULL, NULL);
	(*env)->ReleaseStringUTFChars(env, uriString, uri);
}

extern GtkWindow *window; // TODO: get this in a better way

struct filechooser_callback_data {
	jobject activity;
	jint request_code;
	jint action;
};

#define RESULT_OK       -1
#define RESULT_CANCELED 0

static void file_dialog_callback(GObject *source_object, GAsyncResult *res, gpointer data)
{
	struct filechooser_callback_data *d = data;
	GtkFileDialog *dialog = GTK_FILE_DIALOG(source_object);
	GFile *(*const finish_functions[])(GtkFileDialog *, GAsyncResult *, GError **) = {
		gtk_file_dialog_open_finish,
		gtk_file_dialog_save_finish,
		gtk_file_dialog_select_folder_finish,
	};

	GFile *file = finish_functions[d->action](dialog, res, NULL);
	JNIEnv *env = get_jni_env();
	jmethodID fileChooserResultCallback = _METHOD(handle_cache.activity.class, "fileChooserResultCallback", "(IIILjava/lang/String;)V");

	if (file) {
		char *uri = g_file_get_uri(file);

		(*env)->CallVoidMethod(env, d->activity, fileChooserResultCallback, d->request_code, RESULT_OK, d->action, _JSTRING(uri));
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);

		g_free(uri);
		g_object_unref(file);
	} else {
		(*env)->CallVoidMethod(env, d->activity, fileChooserResultCallback, d->request_code, RESULT_CANCELED, d->action, NULL);
	}
	free(d);
}

JNIEXPORT void JNICALL Java_android_app_Activity_nativeFileChooser(JNIEnv *env, jobject this, jint action, jstring type_jstring, jstring filename_jstring, jint request_code)
{
	const char *chooser_title = ((char *[]){"Open File", "Save File", "Select Folder"})[action];
	GtkFileDialog *dialog = gtk_file_dialog_new();
	gtk_file_dialog_set_title(GTK_FILE_DIALOG(dialog), chooser_title);

	const char *type = type_jstring ? (*env)->GetStringUTFChars(env, type_jstring, NULL) : NULL;
	if (type && !strchr(type, '*')) {
		GtkFileFilter *filter = gtk_file_filter_new();
		gtk_file_filter_add_mime_type(filter, type);
		gtk_file_filter_set_name(filter, type);
		gtk_file_dialog_set_default_filter(GTK_FILE_DIALOG(dialog), filter);
		(*env)->ReleaseStringUTFChars(env, type_jstring, type);
	}
	const char *filename = filename_jstring ? (*env)->GetStringUTFChars(env, filename_jstring, NULL) : NULL;
	if (filename) {
		gtk_file_dialog_set_initial_name(GTK_FILE_DIALOG(dialog), filename);
		(*env)->ReleaseStringUTFChars(env, filename_jstring, filename);
	}

	struct filechooser_callback_data *callback_data = malloc(sizeof(struct filechooser_callback_data));
	callback_data->activity = _REF(this);
	callback_data->request_code = request_code;
	callback_data->action = action;
	void (*const file_dialog_functions[])(GtkFileDialog *, GtkWindow *, GCancellable *, GAsyncReadyCallback, gpointer) = {
		gtk_file_dialog_open,
		gtk_file_dialog_save,
		gtk_file_dialog_select_folder,
	};
	file_dialog_functions[action](dialog, window, NULL, file_dialog_callback, callback_data);
}

/* tl_multiwindow: the activity fills the display unless its window is a smaller, freeform one */
JNIEXPORT jboolean JNICALL Java_android_app_Activity_isInMultiWindowMode(JNIEnv *env, jobject this)
{
	if (!window || gtk_window_is_maximized(window) || gtk_window_is_fullscreen(window))
		return JNI_FALSE;
	GdkSurface *surface = gtk_native_get_surface(GTK_NATIVE(window));
	if (!surface)
		return JNI_FALSE;
	GdkMonitor *monitor = gdk_display_get_monitor_at_surface(gdk_surface_get_display(surface), surface);
	if (!monitor)
		return JNI_FALSE;
	GdkRectangle geometry;
	gdk_monitor_get_geometry(monitor, &geometry);
	int width = gdk_surface_get_width(surface);
	int height = gdk_surface_get_height(surface);
	return (width < geometry.width || height < geometry.height) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_android_app_Activity_isTaskRoot(JNIEnv *env, jobject this)
{
	jobject root_activity = activity_backlog ? g_list_last(activity_backlog)->data : NULL;
	// NULL means that we are currently creating the root activity, so no other activity can exist yet
	return root_activity == NULL || (*env)->IsSameObject(env, this, root_activity);
}

/* tl_activity_transition: the transition of the next page change of the activity stack */
JNIEXPORT void JNICALL Java_android_app_Activity_nativeSetPageTransition(JNIEnv *env, jclass klass, jlong native_window, jint type, jint duration_ms)
{
	GtkWidget *root = native_window ? GTK_WIDGET(_PTR(native_window)) : NULL;
	if (!root || !GTK_IS_WINDOW(root))
		return;
	GtkWidget *child = gtk_window_get_child(GTK_WINDOW(root));
	if (!child || !GTK_IS_OVERLAY(child))
		return;
	GtkWidget *stack = gtk_overlay_get_child(GTK_OVERLAY(child));
	if (!stack || !GTK_IS_STACK(stack))
		return;
	gtk_stack_set_transition_type(GTK_STACK(stack), (GtkStackTransitionType)type);
	gtk_stack_set_transition_duration(GTK_STACK(stack), duration_ms > 0 ? (guint)duration_ms : 0);
}

/* teslalinux: AOSP delivers configuration changes to the resumed activity (native-config.sh) */
void activity_configuration_changed(JNIEnv *env, jobject config)
{
	if (!activity_current)
		return;
	jmethodID method = _METHOD(_CLASS(activity_current), "performConfigurationChanged", "(Landroid/content/res/Configuration;)V");
	(*env)->CallVoidMethod(env, activity_current, method, config);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

/* ---- teslalinux: window-level key dispatch (tl_window_keys) ---- */
int map_key_code(int key_code);
int map_meta_state(GdkModifierType state);
#define TL_KEYCODE_BACK 4
#define TL_ACTION_DOWN 0
#define TL_ACTION_UP 1

gboolean current_activity_dispatch_key(guint keyval, GdkModifierType state, int action)
{
	if (!activity_current)
		return FALSE;
	JNIEnv *env = get_jni_env();
	int code = map_key_code(keyval);
	if (keyval == GDK_KEY_Escape)
		code = TL_KEYCODE_BACK;
	jobject key_event = (*env)->NewObject(env, handle_cache.key_event.class, handle_cache.key_event.constructor,
	                                      (jlong)0, (jlong)0, action, code, 0, map_meta_state(state));
	_SET_INT_FIELD(key_event, "unicodeValue", gdk_keyval_to_unicode(keyval));
	/* tl_edittext_ime: the on-screen keyboard consumes BACK while it shows (AOSP: the IME window) */
	static gboolean tl_osk_back_down = FALSE;
	if (code == TL_KEYCODE_BACK) {
		if (action == TL_ACTION_DOWN) {
			jclass imm = (*env)->FindClass(env, "android/view/inputmethod/InputMethodManager");
			jmethodID handle = (*env)->GetStaticMethodID(env, imm, "tlOskHandleBack", "()Z");
			tl_osk_back_down = (*env)->CallStaticBooleanMethod(env, imm, handle);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);
			if (tl_osk_back_down) {
				(*env)->DeleteLocalRef(env, key_event);
				return TRUE;
			}
		} else if (tl_osk_back_down) {
			tl_osk_back_down = FALSE;
			(*env)->DeleteLocalRef(env, key_event);
			return TRUE;
		}
	}
	/* tl_dialog_overlay: a showing dialog is the focused window and gets the key instead of the activity */
	jboolean tl_dialog_dispatch_key(JNIEnv *env, jobject key_event);
	if (tl_dialog_dispatch_key(env, key_event)) {
		(*env)->DeleteLocalRef(env, key_event);
		return TRUE;
	}
	jmethodID dispatch = _METHOD(_CLASS(activity_current), "dispatchKeyEvent", "(Landroid/view/KeyEvent;)Z");
	gboolean ret = (*env)->CallBooleanMethod(env, activity_current, dispatch, key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	(*env)->DeleteLocalRef(env, key_event);
	return ret;
}
