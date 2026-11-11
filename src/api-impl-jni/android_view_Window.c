#include <gtk/gtk.h>

#include "defines.h"

#include "generated_headers/android_view_Window.h"

/* tl_dialog_overlay: the main window's child is a GtkOverlay (activity content below, dialog layers above);
 * a dialog's root is its layer, whose content is the decor */
void tl_dialog_set_content(GtkWidget *layer, GtkWidget *content);

JNIEXPORT void JNICALL Java_android_view_Window_set_1widget_1as_1root(JNIEnv *env, jobject this, jlong window, jlong widget)
{
	GtkWidget *root = GTK_WIDGET(_PTR(window));
	GtkWidget *gtk_widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget)));
	if (GTK_IS_WINDOW(root)) {
		GtkWidget *child = gtk_window_get_child(GTK_WINDOW(root));
		if (GTK_IS_OVERLAY(child)) {
			GtkWidget *stack = gtk_overlay_get_child(GTK_OVERLAY(child));
			if (GTK_IS_STACK(stack)) { /* tl_window_attach: the decor is a page; the current activity's page is shown */
				if (gtk_widget_get_parent(gtk_widget) != stack)
					gtk_stack_add_child(GTK_STACK(stack), gtk_widget);
				gtk_stack_set_visible_child(GTK_STACK(stack), gtk_widget);
			} else if (stack != gtk_widget) {
				gtk_overlay_set_child(GTK_OVERLAY(child), gtk_widget);
			}
		} else if (gtk_widget != child) {
			gtk_window_set_child(GTK_WINDOW(root), gtk_widget);
		}
	} else {
		tl_dialog_set_content(root, gtk_widget);
	}
}

JNIEXPORT void JNICALL Java_android_view_Window_set_1title(JNIEnv *env, jobject this, jlong window, jstring title_jstr)
{
	const char *title = (*env)->GetStringUTFChars(env, title_jstr, NULL);
	if (GTK_IS_WINDOW(_PTR(window))) /* a dialog layer has no window title; Dialog.setTitle draws it in the decor */
		gtk_window_set_title(GTK_WINDOW(_PTR(window)), title);
	(*env)->ReleaseStringUTFChars(env, title_jstr, title);
}

// FIXME put this in a header file
struct input_queue {
	int fd;
	GtkEventController *controller;
};

JNIEXPORT void JNICALL Java_android_view_Window_take_1input_1queue(JNIEnv *env, jobject this, jlong native_window, jobject callback, jobject queue)
{
	GtkWidget *window = _PTR(native_window);
	printf("in Java_android_view_Window_take_1input_1queue\n");

	GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_event_controller_legacy_new());
	gtk_widget_add_controller(window, controller);

	struct input_queue *input_queue = malloc(sizeof(struct input_queue));
	input_queue->fd = -1;
	input_queue->controller = controller;

	_SET_LONG_FIELD(queue, "native_ptr", _INTPTR(input_queue));

	// we need to keep these for later, so they can be called after OnCreate finishes
	g_object_set_data(G_OBJECT(window), "input_queue_callback", (gpointer)_REF(callback));
	g_object_set_data(G_OBJECT(window), "input_queue", (gpointer)_REF(queue));
}

JNIEXPORT void JNICALL Java_android_view_Window_set_1layout(JNIEnv *env, jobject this, jlong window, jint width, jint height)
{
	GtkWidget *root = GTK_WIDGET(_PTR(window));
	if (GTK_IS_WINDOW(root)) {
		if (width > 0 && height > 0)
			gtk_window_set_default_size(GTK_WINDOW(root), width, height);
	} else { /* a dialog layer: Window.setLayout sizes the decor (MATCH_PARENT/WRAP_CONTENT keep the decor's own measure) */
		GtkWidget *content = gtk_widget_get_first_child(root);
		if (content)
			gtk_widget_set_size_request(content, width > 0 ? width : -1, height > 0 ? height : -1);
	}
}

JNIEXPORT void JNICALL Java_android_view_Window_set_1jobject(JNIEnv *env, jclass this, jlong window, jobject window_jobj)
{
	g_object_set_data(G_OBJECT(window), "jobject", _WEAK_REF(window_jobj));
}

JNIEXPORT void JNICALL Java_android_view_Window_remove_1gtk_1background(JNIEnv *env, jobject this, jlong window)
{
	/* tl_dialog_overlay: a dialog layer's background is AOSP's dim behind the dialog, not a window background */
	if (gtk_widget_has_css_class(GTK_WIDGET(_PTR(window)), "tl-dialog-dim"))
		return;
	gtk_widget_add_css_class(GTK_WIDGET(_PTR(window)), "ATL-no-background");
}

#define FLOAT_TO_POINTER(f) GINT_TO_POINTER(*(uint32_t *)(&f))
#define POINTER_TO_FLOAT(p) (*((float *)(&p)))

void set_brightness_done(GObject *source_object, GAsyncResult *res, gpointer data)
{
	float brightness = POINTER_TO_FLOAT(data);
	GVariant *result = g_dbus_connection_call_finish(G_DBUS_CONNECTION(source_object), res, NULL);
	if (result) {
		g_variant_unref(result);
	} else { // GNOME settings daemon not available. Try fallback to sysfs
		GDir *dir = g_dir_open("/sys/class/backlight", 0, NULL);
		if (!dir)
			return;

		const gchar *name;
		gchar *path;
		FILE *fp;
		while ((name = g_dir_read_name(dir))) {
			path = g_build_filename("/sys/class/backlight/", name, "/max_brightness", NULL);
			fp = fopen(path, "r");
			if (!fp) {
				g_printerr("Failed to read %s: %s\n", path, g_strerror(errno));
				g_free(path);
				continue;
			}
			g_free(path);
			int max = 0;
			fscanf(fp, "%d", &max);
			fclose(fp);

			path = g_build_filename("/sys/class/backlight/", name, "/brightness", NULL);
			fp = fopen(path, "w");
			if (!fp) {
				g_printerr("Failed to write %s: %s\n", path, g_strerror(errno));
				g_free(path);
				continue;
			}
			g_free(path);
			fprintf(fp, "%d", (int)(brightness * max));
			fclose(fp);
		}

		g_dir_close(dir);
	}
}

JNIEXPORT void JNICALL Java_android_view_Window_set_1screen_1brightness(JNIEnv *env, jobject this, jfloat brightness)
{
	GDBusConnection *connection = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, NULL);
	if (!connection)
		return;

	g_dbus_connection_call(connection, "org.gnome.SettingsDaemon.Power", "/org/gnome/SettingsDaemon/Power", "org.freedesktop.DBus.Properties", "Set",
	                       g_variant_new("(ssv)", "org.gnome.SettingsDaemon.Power.Screen", "Brightness", g_variant_new_int32(brightness * 100)),
	                       NULL, G_DBUS_CALL_FLAGS_NONE, -1, NULL, set_brightness_done, FLOAT_TO_POINTER(brightness));

	g_object_unref(connection);
}

/* tl_window_attach: a destroyed activity's decor leaves the window (AOSP: its window is removed) */
JNIEXPORT void JNICALL Java_android_view_Window_tlRemoveRoot(JNIEnv *env, jobject this, jlong window, jlong widget)
{
	GtkWidget *gtk_widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget)));
	GtkWidget *parent = gtk_widget ? gtk_widget_get_parent(gtk_widget) : NULL;
	if (parent && GTK_IS_STACK(parent))
		gtk_stack_remove(GTK_STACK(parent), gtk_widget);
}
