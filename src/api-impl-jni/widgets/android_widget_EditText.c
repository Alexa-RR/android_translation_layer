#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"

#include "WrapperWidget.h"

#include "../generated_headers/android_widget_EditText.h"

/* ---- teslalinux: EditText as an input method target (tl_edittext_ime) ---- */
static void tl_edittext_focus_cb(GtkWidget *gtk_text, GParamSpec *pspec, gpointer data)
{
	GtkWidget *wrapper = gtk_widget_get_parent(gtk_text);
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper))
		return;
	WrapperWidget *w = WRAPPER_WIDGET(wrapper);
	JNIEnv *env = get_jni_env();
	if (!w->jobj || (*env)->IsSameObject(env, w->jobj, NULL))
		return;
	jmethodID m = _METHOD(_CLASS(w->jobj), "tlGtkFocusChanged", "(Z)V");
	(*env)->CallVoidMethod(env, w->jobj, m, (jboolean)gtk_widget_has_focus(gtk_text));
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

/* ---- teslalinux: GtkText cursor/selection changes reach TextView.onSelectionChanged (tl_edittext_selection) ---- */
static void tl_edittext_selection_cb(GtkWidget *gtk_text, GParamSpec *pspec, gpointer data)
{
	GtkWidget *wrapper = gtk_widget_get_parent(gtk_text);
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper))
		return;
	WrapperWidget *w = WRAPPER_WIDGET(wrapper);
	JNIEnv *env = get_jni_env();
	if (!w->jobj || (*env)->IsSameObject(env, w->jobj, NULL))
		return;
	jmethodID m = _METHOD(_CLASS(w->jobj), "tlGtkSelectionChanged", "()V");
	if (!m) {
		(*env)->ExceptionClear(env);
		return;
	}
	(*env)->CallVoidMethod(env, w->jobj, m);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

JNIEXPORT jlong JNICALL Java_android_widget_EditText_native_1constructor(JNIEnv *env, jobject this, jobject context, jobject attrs)
{
	GtkWidget *wrapper = g_object_ref(wrapper_widget_new());
	GtkWidget *gtk_text = gtk_text_new();
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), gtk_text);
	g_signal_connect(gtk_text, "notify::has-focus", G_CALLBACK(tl_edittext_focus_cb), NULL); /* tl_edittext_ime */
	g_signal_connect(gtk_text, "notify::cursor-position", G_CALLBACK(tl_edittext_selection_cb), NULL); /* tl_edittext_selection */
	g_signal_connect(gtk_text, "notify::selection-bound", G_CALLBACK(tl_edittext_selection_cb), NULL);
	wrapper_widget_set_jobject(WRAPPER_WIDGET(wrapper), env, this);
	wrapper_widget_register_invalidation_listener(WRAPPER_WIDGET(wrapper));
	return _INTPTR(gtk_text);
}

JNIEXPORT jstring JNICALL Java_android_widget_EditText_native_1getText(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));
	const char *text = gtk_entry_buffer_get_text(gtk_text_get_buffer(gtk_text));
	return _JSTRING(text);
}

struct changed_callback_data {
	jobject this;
	jobject listener;
	jmethodID getText;
};

static void changed_cb(GtkEditable *self, jobject listener)
{
	JNIEnv *env = get_jni_env();

	const char *text = gtk_editable_get_text(self);
	jobject text_obj = J_new__SpannableStringBuilder(env, _JSTRING(text));
	J__TextWatcher__onTextChanged(env, listener, text_obj, 0, 0, strlen(text));
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	J__TextWatcher__afterTextChanged(env, listener, text_obj);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1addTextChangedListener(JNIEnv *env, jobject this, jlong widget_ptr, jobject listener)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));
	listener = _REF(listener);

	GList *listeners = g_object_get_data(G_OBJECT(gtk_text), "text_changed_listeners");
	listeners = g_list_append(listeners, listener);
	g_object_set_data(G_OBJECT(gtk_text), "text_changed_listeners", listeners);
	g_signal_connect(GTK_EDITABLE(gtk_text), "changed", G_CALLBACK(changed_cb), listener);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1removeTextChangedListener(JNIEnv *env, jobject this, jlong widget_ptr, jobject listener)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));

	GList *listeners = g_object_get_data(G_OBJECT(gtk_text), "text_changed_listeners");
	GList *l;
	for (l = listeners; l != NULL; l = l->next) {
		if ((*env)->IsSameObject(env, l->data, listener)) {
			g_signal_handlers_disconnect_by_func(GTK_EDITABLE(gtk_text), changed_cb, l->data);
			_UNREF(l->data);
			listeners = g_list_delete_link(listeners, l);
			break;
		}
	}
	g_object_set_data(G_OBJECT(gtk_text), "text_changed_listeners", listeners);
}

#define IME_ACTION_SEARCH 3
#define KEYCODE_ENTER     66

static void on_activate(GtkText *gtk_text, struct changed_callback_data *d)
{
	JNIEnv *env = get_jni_env();

	jobject key_event = J_new__KeyEvent(env, (jlong)0, (jlong)0, IME_ACTION_SEARCH, KEYCODE_ENTER, 0, 0);
	J__TextView__OnEditorActionListener__onEditorAction(env, d->listener, d->this, 0, key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setOnEditorActionListener(JNIEnv *env, jobject this, jlong widget_ptr, jobject listener)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));

	if (!listener)
		return;

	struct changed_callback_data *callback_data = malloc(sizeof(struct changed_callback_data));
	callback_data->this = _WEAK_REF(this);
	callback_data->listener = _REF(listener);

	g_signal_handlers_disconnect_matched(gtk_text, G_SIGNAL_MATCH_FUNC, 0, 0, NULL, on_activate, NULL);
	g_signal_connect(gtk_text, "activate", G_CALLBACK(on_activate), callback_data);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setText(JNIEnv *env, jobject this, jlong widget_ptr, jstring text_jstr)
{
	const char *gtk_text = (*env)->GetStringUTFChars(env, text_jstr, NULL);
	jsize length = (*env)->GetStringUTFLength(env, text_jstr);
	gtk_entry_buffer_set_text(gtk_text_get_buffer(GTK_TEXT(_PTR(widget_ptr))), gtk_text, length);
	(*env)->ReleaseStringUTFChars(env, text_jstr, gtk_text);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setHint(JNIEnv *env, jobject this, jlong widget_ptr, jstring text_jstr)
{
	const char *text = (*env)->GetStringUTFChars(env, text_jstr, NULL);
	gtk_text_set_placeholder_text(GTK_TEXT(_PTR(widget_ptr)), text);
	(*env)->ReleaseStringUTFChars(env, text_jstr, text);
}

JNIEXPORT jstring JNICALL Java_android_widget_EditText_native_1getHint(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));
	const char *text = gtk_text_get_placeholder_text(gtk_text);
	return _JSTRING(text);
}

/* positions are GtkEditable character offsets */
JNIEXPORT void JNICALL Java_android_widget_EditText_native_1insertText(JNIEnv *env, jobject this, jlong widget_ptr, jstring text_jstr)
{
	GtkEditable *editable = GTK_EDITABLE(_PTR(widget_ptr));
	const char *text = (*env)->GetStringUTFChars(env, text_jstr, NULL);
	int start, end;
	if (gtk_editable_get_selection_bounds(editable, &start, &end))
		gtk_editable_delete_text(editable, start, end);
	int pos = gtk_editable_get_position(editable);
	gtk_editable_insert_text(editable, text, -1, &pos);
	gtk_editable_set_position(editable, pos);
	(*env)->ReleaseStringUTFChars(env, text_jstr, text);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1deleteRange(JNIEnv *env, jobject this, jlong widget_ptr, jint start, jint end)
{
	GtkEditable *editable = GTK_EDITABLE(_PTR(widget_ptr));
	if (start < 0)
		start = 0;
	if (end > start)
		gtk_editable_delete_text(editable, start, end);
	gtk_editable_set_position(editable, start);
}

JNIEXPORT jint JNICALL Java_android_widget_EditText_native_1getCursor(JNIEnv *env, jobject this, jlong widget_ptr)
{
	return gtk_editable_get_position(GTK_EDITABLE(_PTR(widget_ptr)));
}

/* out: selection start and end (equal when there is none) */
JNIEXPORT void JNICALL Java_android_widget_EditText_native_1getSelection(JNIEnv *env, jobject this, jlong widget_ptr, jintArray out_array)
{
	GtkEditable *editable = GTK_EDITABLE(_PTR(widget_ptr));
	jint out[2];
	int start, end;
	if (gtk_editable_get_selection_bounds(editable, &start, &end)) {
		out[0] = start;
		out[1] = end;
	} else {
		out[0] = out[1] = gtk_editable_get_position(editable);
	}
	(*env)->SetIntArrayRegion(env, out_array, 0, 2, out);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setSelection(JNIEnv *env, jobject this, jlong widget_ptr, jint start, jint end)
{
	GtkEditable *editable = GTK_EDITABLE(_PTR(widget_ptr));
	if (start == end)
		gtk_editable_set_position(editable, start);
	else
		gtk_editable_select_region(editable, start, end);
}

/* the editor action: GtkText's "activate" (what Enter does), which reaches OnEditorActionListener */
JNIEXPORT void JNICALL Java_android_widget_EditText_native_1activate(JNIEnv *env, jobject this, jlong widget_ptr)
{
	g_signal_emit_by_name(_PTR(widget_ptr), "activate");
}

JNIEXPORT jint JNICALL Java_android_widget_EditText_native_1getLength(JNIEnv *env, jobject this, jlong widget_ptr)
{
	return gtk_entry_buffer_get_length(gtk_text_get_buffer(GTK_TEXT(_PTR(widget_ptr))));
}
