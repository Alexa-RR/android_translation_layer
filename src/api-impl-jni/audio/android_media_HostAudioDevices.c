/*
 * The audio devices of the host's sound server, for android.media.AudioManager (HostAudioDevices.java):
 * the outputs (sinks) and inputs (sources other than monitors) that PulseAudio, or PipeWire's pulse
 * server, has, which of them the app's sound reaches, and every change as it happens (a USB sound card
 * plugged in or out, the server restarted). Android's audio policy knows its devices the same way,
 * from the HAL's ports; here the sound server is the policy and its sinks and sources are the ports.
 *
 * The app's sound goes to the default sink (AudioTrack opens ALSA's "default" device, which the
 * pipewire/pulse ALSA plugin plays into the default sink). A loopback that records the default sink's
 * monitor and plays it into another sink (PipeWire's module-loopback / pw-loopback: both ends carry the
 * same node.link-group) carries the sound on to that sink, so that sink counts as routed too.
 *
 * A thread of libpulse's threaded main loop keeps a snapshot: one text record per device, a counter
 * bumped at every change. Java waits for the next one (nativeWait) on a thread of its own and tells the
 * app's AudioDeviceCallbacks what was added and removed.
 */
#include <glib.h>
#include <pulse/pulseaudio.h>
#include <string.h>

#include "../generated_headers/android_media_HostAudioDevices.h"

struct tl_node {
	uint32_t index;
	char *name, *description, *bus, *form_factor, *card_name, *device_class, *link_group;
	uint32_t port_type;
	int channels, rate;
	uint32_t monitor_of_sink; /* sources: the sink this one monitors, else PA_INVALID_INDEX */
	uint32_t target;          /* streams: their sink or source */
};

static pa_threaded_mainloop *mainloop;
static pa_context *context;

/* what one refresh collects (main loop thread only) */
static GPtrArray *sinks, *sources, *sink_inputs, *source_outputs;
static char *default_sink, *default_source;
static int pending;
static gboolean refreshing, dirty;

/* the published snapshot */
static GMutex lock;
static GCond changed;
static GPtrArray *snapshot; /* char *records */
static gint64 generation;
static gboolean connected;

static void tl_node_free(gpointer p)
{
	struct tl_node *n = p;
	g_free(n->name);
	g_free(n->description);
	g_free(n->bus);
	g_free(n->form_factor);
	g_free(n->card_name);
	g_free(n->device_class);
	g_free(n->link_group);
	g_free(n);
}

/* a property under its PulseAudio name or PipeWire's (pipewire-pulse passes the node's own keys) */
static char *prop(pa_proplist *p, const char *pa_key, const char *pw_key)
{
	const char *v = pa_proplist_gets(p, pa_key);
	if (!v && pw_key)
		v = pa_proplist_gets(p, pw_key);
	return g_strdup(v ? v : "");
}

/* one field of a record: no tabs or line breaks */
static void field(GString *s, const char *v)
{
	g_string_append_c(s, '\t');
	for (; v && *v; v++)
		g_string_append_c(s, (*v == '\t' || *v == '\n' || *v == '\r') ? ' ' : *v);
}

static struct tl_node *node_new(uint32_t index, const char *name, const char *description, pa_proplist *p)
{
	struct tl_node *n = g_new0(struct tl_node, 1);
	n->index = index;
	n->name = g_strdup(name ? name : "");
	n->description = g_strdup(description ? description : "");
	n->bus = prop(p, PA_PROP_DEVICE_BUS, NULL);
	n->form_factor = prop(p, PA_PROP_DEVICE_FORM_FACTOR, "device.form-factor");
	n->card_name = prop(p, "alsa.card_name", NULL);
	n->device_class = prop(p, PA_PROP_DEVICE_CLASS, NULL);
	n->link_group = prop(p, "node.link-group", NULL);
	n->monitor_of_sink = PA_INVALID_INDEX;
	n->target = PA_INVALID_INDEX;
	return n;
}

static gboolean in_list(GPtrArray *list, const char *s)
{
	for (guint i = 0; i < list->len; i++)
		if (!strcmp(g_ptr_array_index(list, i), s))
			return TRUE;
	return FALSE;
}

/* record: S|I, server index, name, description, bus, form factor, active port type, ALSA card name,
 * channels, rate, flags (d: the default device, r: the app's sound reaches it) */
static char *record(const struct tl_node *n, gboolean sink, const char *flags)
{
	GString *s = g_string_new(sink ? "S" : "I");
	char num[32];
	g_snprintf(num, sizeof num, "%u", n->index);
	field(s, num);
	field(s, n->name);
	field(s, n->description);
	field(s, n->bus);
	field(s, n->form_factor);
	g_snprintf(num, sizeof num, "%u", n->port_type);
	field(s, num);
	field(s, n->card_name);
	g_snprintf(num, sizeof num, "%d", n->channels);
	field(s, num);
	g_snprintf(num, sizeof num, "%d", n->rate);
	field(s, num);
	field(s, flags);
	return g_string_free(s, FALSE);
}

static void publish(GPtrArray *records, gboolean is_connected)
{
	g_mutex_lock(&lock);
	if (snapshot)
		g_ptr_array_unref(snapshot);
	snapshot = records;
	connected = is_connected;
	generation++;
	g_cond_broadcast(&changed);
	g_mutex_unlock(&lock);
}

static void build(void)
{
	GPtrArray *records = g_ptr_array_new_with_free_func(g_free);
	uint32_t default_index = PA_INVALID_INDEX;
	for (guint i = 0; i < sinks->len; i++) {
		struct tl_node *n = g_ptr_array_index(sinks, i);
		if (default_sink && !strcmp(n->name, default_sink))
			default_index = n->index;
	}
	/* the loopbacks that record the default sink's monitor, by link group */
	GPtrArray *groups = g_ptr_array_new();
	for (guint i = 0; i < source_outputs->len; i++) {
		struct tl_node *so = g_ptr_array_index(source_outputs, i);
		if (!*so->link_group)
			continue;
		for (guint j = 0; j < sources->len; j++) {
			struct tl_node *src = g_ptr_array_index(sources, j);
			if (src->index == so->target && src->monitor_of_sink != PA_INVALID_INDEX && src->monitor_of_sink == default_index)
				g_ptr_array_add(groups, so->link_group);
		}
	}
	for (guint i = 0; i < sinks->len; i++) {
		struct tl_node *n = g_ptr_array_index(sinks, i);
		gboolean routed = n->index == default_index;
		for (guint j = 0; !routed && j < sink_inputs->len; j++) {
			struct tl_node *si = g_ptr_array_index(sink_inputs, j);
			routed = si->target == n->index && *si->link_group && in_list(groups, si->link_group);
		}
		char flags[3] = "";
		if (n->index == default_index)
			strcat(flags, "d");
		if (routed)
			strcat(flags, "r");
		g_ptr_array_add(records, record(n, TRUE, flags));
	}
	for (guint i = 0; i < sources->len; i++) {
		struct tl_node *n = g_ptr_array_index(sources, i);
		/* a monitor is a sink's own sound, not an input device */
		if (n->monitor_of_sink != PA_INVALID_INDEX || !strcmp(n->device_class, "monitor"))
			continue;
		gboolean dflt = default_source && !strcmp(n->name, default_source);
		g_ptr_array_add(records, record(n, FALSE, dflt ? "dr" : ""));
	}
	g_ptr_array_free(groups, TRUE);
	publish(records, TRUE);
}

static void refresh(void);

static void one_done(void)
{
	if (--pending > 0)
		return;
	build();
	refreshing = FALSE;
	if (dirty) {
		dirty = FALSE;
		refresh();
	}
}

static void server_cb(pa_context *c, const pa_server_info *i, void *userdata)
{
	if (i) {
		g_free(default_sink);
		g_free(default_source);
		default_sink = g_strdup(i->default_sink_name);
		default_source = g_strdup(i->default_source_name);
	}
	one_done();
}

static void sink_cb(pa_context *c, const pa_sink_info *i, int eol, void *userdata)
{
	if (eol) {
		one_done();
		return;
	}
	struct tl_node *n = node_new(i->index, i->name, i->description, i->proplist);
	n->port_type = i->active_port ? i->active_port->type : PA_DEVICE_PORT_TYPE_UNKNOWN;
	n->channels = i->sample_spec.channels;
	n->rate = i->sample_spec.rate;
	g_ptr_array_add(sinks, n);
}

static void source_cb(pa_context *c, const pa_source_info *i, int eol, void *userdata)
{
	if (eol) {
		one_done();
		return;
	}
	struct tl_node *n = node_new(i->index, i->name, i->description, i->proplist);
	n->port_type = i->active_port ? i->active_port->type : PA_DEVICE_PORT_TYPE_UNKNOWN;
	n->channels = i->sample_spec.channels;
	n->rate = i->sample_spec.rate;
	n->monitor_of_sink = i->monitor_of_sink;
	g_ptr_array_add(sources, n);
}

static void sink_input_cb(pa_context *c, const pa_sink_input_info *i, int eol, void *userdata)
{
	if (eol) {
		one_done();
		return;
	}
	struct tl_node *n = node_new(i->index, i->name, "", i->proplist);
	n->target = i->sink;
	g_ptr_array_add(sink_inputs, n);
}

static void source_output_cb(pa_context *c, const pa_source_output_info *i, int eol, void *userdata)
{
	if (eol) {
		one_done();
		return;
	}
	struct tl_node *n = node_new(i->index, i->name, "", i->proplist);
	n->target = i->source;
	g_ptr_array_add(source_outputs, n);
}

static void op(pa_operation *o)
{
	if (o)
		pa_operation_unref(o);
	else
		one_done(); /* the request did not go out: counts as answered (empty) */
}

/* everything again, at once (the main loop thread); a change during a refresh asks for another */
static void refresh(void)
{
	if (refreshing) {
		dirty = TRUE;
		return;
	}
	refreshing = TRUE;
	g_ptr_array_set_size(sinks, 0);
	g_ptr_array_set_size(sources, 0);
	g_ptr_array_set_size(sink_inputs, 0);
	g_ptr_array_set_size(source_outputs, 0);
	pending = 5;
	op(pa_context_get_server_info(context, server_cb, NULL));
	op(pa_context_get_sink_info_list(context, sink_cb, NULL));
	op(pa_context_get_source_info_list(context, source_cb, NULL));
	op(pa_context_get_sink_input_info_list(context, sink_input_cb, NULL));
	op(pa_context_get_source_output_info_list(context, source_output_cb, NULL));
}

static void subscribe_cb(pa_context *c, pa_subscription_event_type_t t, uint32_t idx, void *userdata)
{
	refresh();
}

static void connect_server(void);

static void reconnect_cb(pa_mainloop_api *api, pa_time_event *e, const struct timeval *tv, void *userdata)
{
	api->time_free(e);
	connect_server();
}

static void state_cb(pa_context *c, void *userdata)
{
	switch (pa_context_get_state(c)) {
		case PA_CONTEXT_READY:
			pa_context_set_subscribe_callback(c, subscribe_cb, NULL);
			pa_operation_unref(pa_context_subscribe(c, PA_SUBSCRIPTION_MASK_SINK | PA_SUBSCRIPTION_MASK_SOURCE | PA_SUBSCRIPTION_MASK_SINK_INPUT | PA_SUBSCRIPTION_MASK_SOURCE_OUTPUT | PA_SUBSCRIPTION_MASK_SERVER, NULL, NULL));
			refresh();
			break;
		case PA_CONTEXT_FAILED:
		case PA_CONTEXT_TERMINATED: {
			/* the server went away (or never answered): no devices until it is back; try again in a second */
			pa_context_unref(c);
			context = NULL;
			refreshing = dirty = FALSE;
			g_mutex_lock(&lock);
			gboolean news = connected || generation == 0; /* a server that stays away is reported once */
			g_mutex_unlock(&lock);
			if (news)
				publish(g_ptr_array_new_with_free_func(g_free), FALSE);
			struct timeval tv;
			pa_gettimeofday(&tv);
			pa_timeval_add(&tv, 1000000);
			pa_mainloop_api *api = pa_threaded_mainloop_get_api(mainloop);
			api->time_new(api, &tv, reconnect_cb, NULL);
			break;
		}
		default:
			break;
	}
}

static void connect_server(void)
{
	pa_proplist *p = pa_proplist_new();
	pa_proplist_sets(p, PA_PROP_APPLICATION_NAME, "android-translation-layer audio devices");
	context = pa_context_new_with_proplist(pa_threaded_mainloop_get_api(mainloop), NULL, p);
	pa_proplist_free(p);
	pa_context_set_state_callback(context, state_cb, NULL);
	/* never start a server; a missing one fails at once (state_cb runs from here with FAILED) and is
	 * tried again a second later */
	pa_context_connect(context, NULL, PA_CONTEXT_NOAUTOSPAWN, NULL);
}

/* Starts the monitor once and waits up to timeout_ms for its first answer; whether a sound server answered */
JNIEXPORT jboolean JNICALL Java_android_media_HostAudioDevices_nativeStart(JNIEnv *env, jclass clazz, jint timeout_ms)
{
	static gsize started;
	if (g_once_init_enter(&started)) {
		sinks = g_ptr_array_new_with_free_func(tl_node_free);
		sources = g_ptr_array_new_with_free_func(tl_node_free);
		sink_inputs = g_ptr_array_new_with_free_func(tl_node_free);
		source_outputs = g_ptr_array_new_with_free_func(tl_node_free);
		mainloop = pa_threaded_mainloop_new();
		pa_threaded_mainloop_set_name(mainloop, "atl-audio-devs");
		pa_threaded_mainloop_lock(mainloop);
		connect_server();
		pa_threaded_mainloop_unlock(mainloop);
		pa_threaded_mainloop_start(mainloop);
		g_once_init_leave(&started, 1);
	}
	gint64 end = g_get_monotonic_time() + (gint64)timeout_ms * 1000;
	g_mutex_lock(&lock);
	while (generation == 0)
		if (!g_cond_wait_until(&changed, &lock, end))
			break;
	jboolean ret = connected;
	g_mutex_unlock(&lock);
	return ret;
}

/*
 * The snapshot after `seen` (blocks until there is a newer one): [0] "<generation>\t<1|0 connected>",
 * then one record per device
 */
JNIEXPORT jobjectArray JNICALL Java_android_media_HostAudioDevices_nativeWait(JNIEnv *env, jclass clazz, jlong seen)
{
	g_mutex_lock(&lock);
	while (generation == seen)
		g_cond_wait(&changed, &lock);
	GPtrArray *records = snapshot ? g_ptr_array_ref(snapshot) : NULL;
	char head[48];
	g_snprintf(head, sizeof head, "%" G_GINT64_FORMAT "\t%d", generation, connected ? 1 : 0);
	g_mutex_unlock(&lock);

	int n = records ? records->len : 0;
	jobjectArray out = (*env)->NewObjectArray(env, n + 1, (*env)->FindClass(env, "java/lang/String"), NULL);
	(*env)->SetObjectArrayElement(env, out, 0, (*env)->NewStringUTF(env, head));
	for (int i = 0; i < n; i++) {
		jstring s = (*env)->NewStringUTF(env, g_ptr_array_index(records, i));
		(*env)->SetObjectArrayElement(env, out, i + 1, s);
		(*env)->DeleteLocalRef(env, s);
	}
	if (records)
		g_ptr_array_unref(records);
	return out;
}
