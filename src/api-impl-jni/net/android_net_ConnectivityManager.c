/* teslalinux: the host side of android.net.ConnectivityManager (tl_connectivity).
 * GLib's GNetworkMonitor knows whether a default route exists, whether the connection is metered
 * and whether it reaches the internet (full), a captive portal or only the local network.
 * NetworkManager (and ModemManager for modems) describe the interface of the default route: its
 * device type, NetworkManager's metered state, the link speed and, for a modem, the access
 * technology, signal and roaming state (nativeDescribe). The Java side turns that into
 * Network/NetworkCapabilities/NetworkInfo and delivers callbacks; "network-changed" and
 * "notify::network-metered" of the monitor and NetworkManager's and ModemManager's
 * PropertiesChanged signals (all on the GLib main loop) feed ConnectivityManager.tlOnNetworkChanged,
 * coalesced into one call per main loop turn. */
#include <gio/gio.h>
#include <string.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_net_ConnectivityManager.h"

#define NM_BUS "org.freedesktop.NetworkManager"
#define NM_PATH "/org/freedesktop/NetworkManager"
#define NM_DEVICE NM_BUS ".Device"
#define MM_BUS "org.freedesktop.ModemManager1"
#define MM_PATH "/org/freedesktop/ModemManager1"
#define MM_MODEM MM_BUS ".Modem"
/* the time one D-Bus query may take: the system bus answers in microseconds; a hung daemon must
 * not hold up the app's main thread for long */
#define TL_DBUS_TIMEOUT_MS 300

static gboolean tl_cm_started;
static guint tl_cm_idle;

JNIEXPORT jboolean JNICALL Java_android_net_ConnectivityManager_nativeGetNetworkAvailable(JNIEnv *env, jclass this)
{
	return g_network_monitor_get_network_available(g_network_monitor_get_default());
}

JNIEXPORT jboolean JNICALL Java_android_net_ConnectivityManager_nativeIsMetered(JNIEnv *env, jclass this)
{
	return g_network_monitor_get_network_metered(g_network_monitor_get_default());
}

JNIEXPORT jint JNICALL Java_android_net_ConnectivityManager_nativeGetConnectivity(JNIEnv *env, jclass this)
{
	return (jint)g_network_monitor_get_connectivity(g_network_monitor_get_default());
}

/* the system bus, or NULL (no D-Bus here: the Java side then reads sysfs only) */
static GDBusConnection *tl_system_bus(void)
{
	static GDBusConnection *bus;
	static gboolean tried;
	if (!tried) {
		tried = TRUE;
		GError *err = NULL;
		bus = g_bus_get_sync(G_BUS_TYPE_SYSTEM, NULL, &err);
		if (!bus) {
			if (getenv("ATL_DEBUG_NETWORK"))
				fprintf(stderr, "[network] no system bus: %s\n", err ? err->message : "?");
			g_clear_error(&err);
		}
	}
	return bus;
}

static gboolean tl_has_owner(GDBusConnection *bus, const char *name)
{
	GVariant *r = g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
	                                          "NameHasOwner", g_variant_new("(s)", name), G_VARIANT_TYPE("(b)"),
	                                          G_DBUS_CALL_FLAGS_NONE, TL_DBUS_TIMEOUT_MS, NULL, NULL);
	if (!r)
		return FALSE;
	gboolean has;
	g_variant_get(r, "(b)", &has);
	g_variant_unref(r);
	return has;
}

/* one property (org.freedesktop.DBus.Properties.Get), unboxed; NULL when it cannot be read */
static GVariant *tl_get_prop(GDBusConnection *bus, const char *name, const char *path, const char *iface, const char *prop)
{
	GVariant *r = g_dbus_connection_call_sync(bus, name, path, "org.freedesktop.DBus.Properties", "Get",
	                                          g_variant_new("(ss)", iface, prop), G_VARIANT_TYPE("(v)"),
	                                          G_DBUS_CALL_FLAGS_NONE, TL_DBUS_TIMEOUT_MS, NULL, NULL);
	if (!r)
		return NULL;
	GVariant *v;
	g_variant_get(r, "(v)", &v);
	g_variant_unref(r);
	return v;
}

static void tl_add_u32(GString *out, const char *key, GVariant *v)
{
	if (!v)
		return;
	if (g_variant_is_of_type(v, G_VARIANT_TYPE_UINT32))
		g_string_append_printf(out, "%s=%u\n", key, g_variant_get_uint32(v));
	else if (g_variant_is_of_type(v, G_VARIANT_TYPE_INT32))
		g_string_append_printf(out, "%s=%d\n", key, g_variant_get_int32(v));
	else if (g_variant_is_of_type(v, G_VARIANT_TYPE_BYTE))
		g_string_append_printf(out, "%s=%u\n", key, g_variant_get_byte(v));
	g_variant_unref(v);
}

static void tl_add_str(GString *out, const char *key, GVariant *v)
{
	if (!v)
		return;
	if (g_variant_is_of_type(v, G_VARIANT_TYPE_STRING) || g_variant_is_of_type(v, G_VARIANT_TYPE_OBJECT_PATH)) {
		const char *s = g_variant_get_string(v, NULL);
		if (!strchr(s, '\n'))
			g_string_append_printf(out, "%s=%s\n", key, s);
	}
	g_variant_unref(v);
}

/* ModemManager: the modem whose ports include iface as its network port (MM_MODEM_PORT_TYPE_NET) */
static void tl_describe_mm(GDBusConnection *bus, const char *iface, GString *out)
{
	if (!tl_has_owner(bus, MM_BUS))
		return;
	g_string_append(out, "mm=1\n");
	GVariant *r = g_dbus_connection_call_sync(bus, MM_BUS, MM_PATH, "org.freedesktop.DBus.ObjectManager", "GetManagedObjects",
	                                          NULL, G_VARIANT_TYPE("(a{oa{sa{sv}}})"), G_DBUS_CALL_FLAGS_NONE,
	                                          TL_DBUS_TIMEOUT_MS, NULL, NULL);
	if (!r)
		return;
	GVariant *objects = g_variant_get_child_value(r, 0);
	GVariantIter it;
	const char *path;
	GVariant *ifaces;
	g_variant_iter_init(&it, objects);
	while (g_variant_iter_next(&it, "{&o@a{sa{sv}}}", &path, &ifaces)) {
		GVariant *modem = g_variant_lookup_value(ifaces, MM_MODEM, G_VARIANT_TYPE("a{sv}"));
		gboolean ours = FALSE;
		if (modem) {
			GVariant *ports = g_variant_lookup_value(modem, "Ports", G_VARIANT_TYPE("a(su)"));
			if (ports) {
				GVariantIter pit;
				const char *pname;
				guint32 ptype;
				g_variant_iter_init(&pit, ports);
				while (g_variant_iter_next(&pit, "(&su)", &pname, &ptype)) {
					if (ptype == 2 /* MM_MODEM_PORT_TYPE_NET */ && !strcmp(pname, iface))
						ours = TRUE;
				}
				g_variant_unref(ports);
			}
		}
		if (ours) {
			g_string_append_printf(out, "mm_modem=%s\n", path);
			guint32 u;
			gint32 i;
			if (g_variant_lookup(modem, "AccessTechnologies", "u", &u))
				g_string_append_printf(out, "mm_access=%u\n", u);
			if (g_variant_lookup(modem, "State", "i", &i))
				g_string_append_printf(out, "mm_state=%d\n", i);
			GVariant *sq = g_variant_lookup_value(modem, "SignalQuality", G_VARIANT_TYPE("(ub)"));
			if (sq) {
				gboolean recent;
				g_variant_get(sq, "(ub)", &u, &recent);
				g_string_append_printf(out, "mm_signal=%u\n", u);
				g_variant_unref(sq);
			}
			GVariant *gpp = g_variant_lookup_value(ifaces, MM_MODEM ".Modem3gpp", G_VARIANT_TYPE("a{sv}"));
			if (gpp) {
				if (g_variant_lookup(gpp, "RegistrationState", "u", &u))
					g_string_append_printf(out, "mm_registration=%u\n", u);
				const char *op;
				if (g_variant_lookup(gpp, "OperatorName", "&s", &op) && !strchr(op, '\n'))
					g_string_append_printf(out, "mm_operator=%s\n", op);
				g_variant_unref(gpp);
			}
		}
		if (modem)
			g_variant_unref(modem);
		g_variant_unref(ifaces);
		if (ours)
			break;
	}
	g_variant_unref(objects);
	g_variant_unref(r);
}

/* NetworkManager's device for iface: type, metered state, driver, link speed */
static void tl_describe_nm(GDBusConnection *bus, const char *iface, GString *out)
{
	if (!tl_has_owner(bus, NM_BUS))
		return;
	g_string_append(out, "nm=1\n");
	tl_add_u32(out, "nm_global_metered", tl_get_prop(bus, NM_BUS, NM_PATH, NM_BUS, "Metered"));
	tl_add_u32(out, "nm_connectivity", tl_get_prop(bus, NM_BUS, NM_PATH, NM_BUS, "Connectivity"));
	GVariant *r = g_dbus_connection_call_sync(bus, NM_BUS, NM_PATH, NM_BUS, "GetDeviceByIpIface", g_variant_new("(s)", iface),
	                                          G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE, TL_DBUS_TIMEOUT_MS, NULL, NULL);
	if (!r)
		return;
	const char *dev;
	g_variant_get(r, "(&o)", &dev);
	g_string_append_printf(out, "nm_device=%s\n", dev);
	GVariant *type = tl_get_prop(bus, NM_BUS, dev, NM_DEVICE, "DeviceType");
	guint32 t = type && g_variant_is_of_type(type, G_VARIANT_TYPE_UINT32) ? g_variant_get_uint32(type) : 0;
	tl_add_u32(out, "nm_type", type);
	tl_add_u32(out, "nm_metered", tl_get_prop(bus, NM_BUS, dev, NM_DEVICE, "Metered"));
	tl_add_u32(out, "nm_state", tl_get_prop(bus, NM_BUS, dev, NM_DEVICE, "State"));
	tl_add_str(out, "nm_driver", tl_get_prop(bus, NM_BUS, dev, NM_DEVICE, "Driver"));
	tl_add_str(out, "nm_udi", tl_get_prop(bus, NM_BUS, dev, NM_DEVICE, "Udi"));
	if (t == 2 /* NM_DEVICE_TYPE_WIFI */) {
		tl_add_u32(out, "nm_wifi_bitrate_kbps", tl_get_prop(bus, NM_BUS, dev, NM_DEVICE ".Wireless", "Bitrate"));
		GVariant *ap = tl_get_prop(bus, NM_BUS, dev, NM_DEVICE ".Wireless", "ActiveAccessPoint");
		if (ap) {
			const char *ap_path = g_variant_get_string(ap, NULL);
			if (strcmp(ap_path, "/"))
				tl_add_u32(out, "nm_wifi_strength", tl_get_prop(bus, NM_BUS, ap_path, NM_BUS ".AccessPoint", "Strength"));
			g_variant_unref(ap);
		}
	} else if (t == 1 /* NM_DEVICE_TYPE_ETHERNET */) {
		tl_add_u32(out, "nm_wired_speed_mbps", tl_get_prop(bus, NM_BUS, dev, NM_DEVICE ".Wired", "Speed"));
	} else if (t == 8 /* NM_DEVICE_TYPE_MODEM */) {
		tl_add_u32(out, "nm_modem_caps", tl_get_prop(bus, NM_BUS, dev, NM_DEVICE ".Modem", "CurrentCapabilities"));
	}
	g_variant_unref(r);
}

/**
 * What the host knows about the interface of the default route, as "key=value" lines: the
 * NetworkManager device (nm_*), the ModemManager modem driving it (mm_*). Empty when neither
 * daemon is running; the Java side adds sysfs and decides.
 */
JNIEXPORT jstring JNICALL Java_android_net_ConnectivityManager_nativeDescribe(JNIEnv *env, jclass this, jstring jiface)
{
	GString *out = g_string_new(NULL);
	GDBusConnection *bus = tl_system_bus();
	if (bus && jiface) {
		const char *iface = (*env)->GetStringUTFChars(env, jiface, NULL);
		tl_describe_nm(bus, iface, out);
		tl_describe_mm(bus, iface, out);
		(*env)->ReleaseStringUTFChars(env, jiface, iface);
	}
	jstring ret = (*env)->NewStringUTF(env, out->str);
	g_string_free(out, TRUE);
	return ret;
}

static gboolean tl_cm_dispatch(gpointer data)
{
	tl_cm_idle = 0;
	JNIEnv *env = get_jni_env();
	J__ConnectivityManager__tlOnNetworkChanged(env, g_network_monitor_get_network_available(g_network_monitor_get_default()));
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	return G_SOURCE_REMOVE;
}

/* every change source lands here: one re-observation per main loop turn */
static void tl_cm_schedule(void)
{
	if (!tl_cm_idle)
		tl_cm_idle = g_idle_add(tl_cm_dispatch, NULL);
}

static void on_network_changed(GNetworkMonitor *monitor, gboolean network_available, gpointer user_data)
{
	tl_cm_schedule();
}

static void on_monitor_notify(GObject *monitor, GParamSpec *pspec, gpointer user_data)
{
	tl_cm_schedule();
}

static void on_daemon_signal(GDBusConnection *bus, const char *sender, const char *path, const char *iface,
                             const char *signal, GVariant *params, gpointer user_data)
{
	if (getenv("ATL_DEBUG_NETWORK"))
		fprintf(stderr, "[network] %s %s.%s\n", path, iface, signal);
	tl_cm_schedule();
}

JNIEXPORT void JNICALL Java_android_net_ConnectivityManager_nativeStartMonitor(JNIEnv *env, jclass this)
{
	if (tl_cm_started)
		return;
	tl_cm_started = TRUE;
	GNetworkMonitor *monitor = g_network_monitor_get_default();
	g_signal_connect(monitor, "network-changed", G_CALLBACK(on_network_changed), NULL);
	g_signal_connect(monitor, "notify::network-metered", G_CALLBACK(on_monitor_notify), NULL);
	g_signal_connect(monitor, "notify::connectivity", G_CALLBACK(on_monitor_notify), NULL);
	GDBusConnection *bus = tl_system_bus();
	if (bus) {
		/* the callbacks run in the thread-default context of the subscribing thread: the global
		 * default one, which the main thread's loop iterates (a manager may be made on any thread) */
		g_main_context_push_thread_default(g_main_context_default());
		/* a device's Metered, State or speed, the manager's PrimaryConnection or Metered; a modem's
		 * access technology, signal or registration; and the daemons coming and going */
		g_dbus_connection_signal_subscribe(bus, NM_BUS, "org.freedesktop.DBus.Properties", "PropertiesChanged", NULL, NULL,
		                                   G_DBUS_SIGNAL_FLAGS_NONE, on_daemon_signal, NULL, NULL);
		g_dbus_connection_signal_subscribe(bus, MM_BUS, "org.freedesktop.DBus.Properties", "PropertiesChanged", NULL, NULL,
		                                   G_DBUS_SIGNAL_FLAGS_NONE, on_daemon_signal, NULL, NULL);
		g_dbus_connection_signal_subscribe(bus, "org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged", "/org/freedesktop/DBus",
		                                   NM_BUS, G_DBUS_SIGNAL_FLAGS_MATCH_ARG0_NAMESPACE, on_daemon_signal, NULL, NULL);
		g_dbus_connection_signal_subscribe(bus, "org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged", "/org/freedesktop/DBus",
		                                   MM_BUS, G_DBUS_SIGNAL_FLAGS_MATCH_ARG0_NAMESPACE, on_daemon_signal, NULL, NULL);
		g_main_context_pop_thread_default(g_main_context_default());
	}
}
