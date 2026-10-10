#include <gtk/gtk.h>
#include <jni.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_app_Dialog.h"

/* tl_dialog_overlay: AOSP dialogs are windows layered over the activity with a dim behind them
 * (FLAG_DIM_BEHIND, dimAmount 0.6 in the Material dialog themes). Under a kiosk compositor every
 * toplevel is fullscreen, so the dialog is a layer in the main window's GtkOverlay: a dim box that
 * fills the window with the dialog's decor centred in it. */
extern GtkWidget *tl_main_overlay;
static GList *tl_dialog_stack = NULL; /* layers in show order; the last one is on top */

static void on_dim_pressed(GtkGestureClick *gesture, int n_press, double x, double y, GtkWidget *layer)
{
	GtkWidget *content = gtk_widget_get_first_child(layer);
	graphene_rect_t bounds;
	if (content && gtk_widget_compute_bounds(content, layer, &bounds) && graphene_rect_contains_point(&bounds, &GRAPHENE_POINT_INIT(x, y)))
		return; /* inside the dialog: its own views handle the touch */
	gtk_gesture_set_state(GTK_GESTURE(gesture), GTK_EVENT_SEQUENCE_CLAIMED);
	JNIEnv *env = get_jni_env();
	jobject jobj = g_object_get_data(G_OBJECT(layer), "dialog");
	(*env)->CallVoidMethod(env, jobj, _METHOD(_CLASS(jobj), "tlTouchOutside", "()V"));
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

/* the dialog's decor (or ATL's AlertDialog content) becomes the centred child of the layer */
void tl_dialog_set_content(GtkWidget *layer, GtkWidget *content)
{
	GtkWidget *old;
	while ((old = gtk_widget_get_first_child(layer)))
		gtk_box_remove(GTK_BOX(layer), old);
	gtk_widget_set_halign(content, GTK_ALIGN_CENTER);
	gtk_widget_set_valign(content, GTK_ALIGN_CENTER);
	gtk_widget_set_hexpand(content, TRUE);
	gtk_widget_set_vexpand(content, TRUE);
	gtk_box_append(GTK_BOX(layer), content);
}

/* AOSP: the focused window gets the key, and while a dialog shows, that is the dialog */
jboolean tl_dialog_dispatch_key(JNIEnv *env, jobject key_event)
{
	if (!tl_dialog_stack)
		return JNI_FALSE;
	GtkWidget *layer = g_list_last(tl_dialog_stack)->data;
	jobject jobj = g_object_get_data(G_OBJECT(layer), "dialog");
	(*env)->CallBooleanMethod(env, jobj, _METHOD(_CLASS(jobj), "dispatchKeyEvent", "(Landroid/view/KeyEvent;)Z"), key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	return JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_android_app_Dialog_nativeInit(JNIEnv *env, jobject this)
{
	GtkWidget *layer = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
	gtk_widget_add_css_class(layer, "tl-dialog-dim");
	gtk_widget_set_hexpand(layer, TRUE);
	gtk_widget_set_vexpand(layer, TRUE);
	gtk_widget_set_halign(layer, GTK_ALIGN_FILL);
	gtk_widget_set_valign(layer, GTK_ALIGN_FILL);
	gtk_widget_set_focusable(layer, TRUE);
	GtkGesture *click = gtk_gesture_click_new();
	gtk_gesture_single_set_button(GTK_GESTURE_SINGLE(click), 0);
	g_signal_connect(click, "pressed", G_CALLBACK(on_dim_pressed), layer);
	gtk_widget_add_controller(layer, GTK_EVENT_CONTROLLER(click));
	g_object_set_data(G_OBJECT(layer), "dialog", _REF(this));
	return _INTPTR(g_object_ref_sink(layer));
}

/* AOSP: the title is a view of the dialog's decor; ATL's AlertDialog content has a title label,
 * a custom content view (every Waze dialog) draws its own title */
JNIEXPORT void JNICALL Java_android_app_Dialog_nativeSetTitle(JNIEnv *env, jobject this, jlong ptr, jstring title)
{
	GtkWidget *layer = GTK_WIDGET(_PTR(ptr));
	const char *nativeTitle = (*env)->GetStringUTFChars(env, title, NULL);
	GtkWidget *label = g_object_get_data(G_OBJECT(layer), "title-label");
	if (label) {
		gtk_label_set_text(GTK_LABEL(label), nativeTitle);
		gtk_widget_set_visible(label, nativeTitle[0] != 0);
	}
	g_object_set_data_full(G_OBJECT(layer), "title", g_strdup(nativeTitle), g_free);
	(*env)->ReleaseStringUTFChars(env, title, nativeTitle);
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeSetContentView(JNIEnv *env, jobject this, jlong ptr, jlong widget_ptr)
{
	GtkWidget *layer = GTK_WIDGET(_PTR(ptr));
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	tl_dialog_set_content(layer, gtk_widget_get_parent(widget));
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeShow(JNIEnv *env, jobject this, jlong ptr)
{
	GtkWidget *layer = GTK_WIDGET(_PTR(ptr));
	if (!tl_main_overlay) {
		fprintf(stderr, "Dialog.nativeShow: no main overlay yet\n");
		return;
	}
	if (!gtk_widget_get_parent(layer)) {
		gtk_overlay_add_overlay(GTK_OVERLAY(tl_main_overlay), layer);
		gtk_overlay_set_measure_overlay(GTK_OVERLAY(tl_main_overlay), layer, FALSE);
		gtk_overlay_set_clip_overlay(GTK_OVERLAY(tl_main_overlay), layer, TRUE);
	}
	gtk_widget_set_visible(layer, TRUE);
	tl_dialog_stack = g_list_append(g_list_remove(tl_dialog_stack, layer), layer);
	gtk_widget_grab_focus(layer);
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeClose(JNIEnv *env, jobject this, jlong ptr)
{
	GtkWidget *layer = GTK_WIDGET(_PTR(ptr));
	gtk_widget_set_visible(layer, FALSE);
	if (tl_main_overlay && gtk_widget_get_parent(layer) == tl_main_overlay)
		gtk_overlay_remove_overlay(GTK_OVERLAY(tl_main_overlay), layer);
	tl_dialog_stack = g_list_remove(tl_dialog_stack, layer);
}

JNIEXPORT jboolean JNICALL Java_android_app_Dialog_nativeIsShowing(JNIEnv *env, jobject this, jlong ptr)
{
	GtkWidget *layer = GTK_WIDGET(_PTR(ptr));
	return gtk_widget_get_parent(layer) != NULL && gtk_widget_get_visible(layer);
}
