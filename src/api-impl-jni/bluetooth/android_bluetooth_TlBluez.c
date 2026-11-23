/* teslalinux: the host side of android.bluetooth (android.bluetooth.TlBluez): BlueZ over the D-Bus
 * system bus.
 *
 * The adapter is BlueZ's first org.bluez.Adapter1 (by object path), or the one ATL_BLUETOOTH_ADAPTER
 * names ("hci1"). Its Powered, Address and Alias properties are the adapter's state, address and name.
 * ObjectManager's InterfacesAdded/InterfacesRemoved and bluetoothd's NameOwnerChanged make the
 * adapter come and go (a USB adapter plugged in or out, bluetoothd restarting); every change reaches
 * Java as TlBluez.tlOnAdapterState.
 *
 * LE scanning is this process's discovery session: SetDiscoveryFilter {Transport: "le",
 * DuplicateData: true, and UUIDs when every scan filters on service UUIDs} and StartDiscovery
 * (the filter is set again when the scans change). With DuplicateData BlueZ signals every advertisement it
 * receives as a PropertiesChanged of the device's org.bluez.Device1 holding the RSSI (when it
 * changed) and the data the advertisement carried (ManufacturerData, ServiceData, AdvertisingData).
 * The device's properties are kept here (an InterfacesAdded starts them, PropertiesChanged merges
 * into them) and every advertisement is reported to Java (TlBluez.tlOnAdvertisement) with the
 * advertising data structures rebuilt from them: BlueZ parses the advertisement and scan response
 * and does not hand out the PDU, so the record holds the Flags, the service UUIDs (16, 32 and 128
 * bit lists), the local name, the TX power level, the service data, the manufacturer specific data
 * and every other AD type BlueZ passes on (AdvertisingData), which is what AOSP's
 * ScanRecord.parseFromBytes reads. The PDU type (connectable or not, scan response or not) is not
 * part of BlueZ's API.
 *
 * Discovery started by another D-Bus client (bluetoothctl, another app) produces the same signals;
 * they are reported too while a scanner listens, which is how AOSP's opportunistic scanners get
 * results from other apps' scans.
 *
 * Everything that touches the D-Bus subscriptions runs on GLib's default main context (the main
 * thread of android-translation-layer); the device table and adapter state are also read from other
 * threads (TlBluez.nativeDescribeDevice), under bz_lock. Calls to Java are made without the lock. */
#include <gio/gio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_bluetooth_TlBluez.h"

#define BZ_BUS "org.bluez"
#define BZ_ADAPTER BZ_BUS ".Adapter1"
#define BZ_DEVICE BZ_BUS ".Device1"
#define DBUS_PROPS "org.freedesktop.DBus.Properties"
#define DBUS_OM "org.freedesktop.DBus.ObjectManager"
/* how long one query may take: bluetoothd answers in milliseconds; a hung daemon must not hold up
 * the app's main thread for long */
#define TL_BZ_TIMEOUT_MS 1000
/* powering the controller on or off takes HCI commands (and firmware on some controllers) */
#define TL_BZ_POWER_TIMEOUT_MS 5000

typedef struct {
	char *path;
	char address[18];
	int addr_type; /* 0 public, 1 random */
	char *name;
	GVariant *flags;        /* ay: AdvertisingFlags */
	GVariant *uuids;        /* as: UUIDs */
	GVariant *manufacturer; /* a{qv}: ManufacturerData */
	GVariant *service_data; /* a{sv}: ServiceData */
	GVariant *ad;           /* a{yv}: AdvertisingData */
	gboolean has_tx;
	gint16 tx;
	gboolean has_rssi;
	gint16 rssi;
	gboolean bonded, paired;
	gboolean has_class;
	guint32 cls;
	gboolean has_appearance;
} TlBzDevice;

static GMutex bz_lock;
static gboolean bz_started;
static GHashTable *bz_devices; /* object path -> TlBzDevice */
static char *bz_adapter;       /* the adapter's object path, or NULL */
static gboolean bz_powered;
static char *bz_address;
static char *bz_alias;
/* the last state reported to Java */
static gboolean rep_present, rep_powered;
static char *rep_address, *rep_alias;
static gboolean rep_valid;

static gboolean bz_listening;         /* Java wants advertisements */
static gboolean bz_want_discovery;    /* Java wants this process's discovery session */
static gboolean bz_discovery_active;  /* StartDiscovery succeeded and no StopDiscovery since */
static gboolean bz_discovery_pending; /* a start or filter change is in flight */
static char **bz_want_uuids;          /* the service UUIDs Java's scans filter on, NULL: no UUID filter */
static char **bz_applied_uuids;       /* the UUID filter of the running session */
static guint bz_discovery_apply_source;

static GDBusConnection *tl_bz_bus(void)
{
	static GDBusConnection *bus;
	static gsize once;
	if (g_once_init_enter(&once)) {
		GError *err = NULL;
		bus = g_bus_get_sync(G_BUS_TYPE_SYSTEM, NULL, &err);
		if (!bus) {
			if (getenv("ATL_DEBUG_BLUETOOTH"))
				fprintf(stderr, "[bluetooth] no system bus: %s\n", err ? err->message : "?");
			g_clear_error(&err);
		}
		g_once_init_leave(&once, 1);
	}
	return bus;
}

/* ATL_DEBUG_BLUETOOTH: 1 logs the adapter and the discovery session, 2 also every advertisement
 * handed to the app */
static int tl_bz_debug(void)
{
	static int debug = -1;
	if (debug < 0) {
		const char *v = getenv("ATL_DEBUG_BLUETOOTH");
		debug = !v ? 0 : atoi(v) > 1 ? 2 : 1;
	}
	return debug;
}

static void tl_bz_device_free(gpointer p)
{
	TlBzDevice *d = p;
	g_free(d->path);
	g_free(d->name);
	g_clear_pointer(&d->flags, g_variant_unref);
	g_clear_pointer(&d->uuids, g_variant_unref);
	g_clear_pointer(&d->manufacturer, g_variant_unref);
	g_clear_pointer(&d->service_data, g_variant_unref);
	g_clear_pointer(&d->ad, g_variant_unref);
	g_free(d);
}

static void tl_bz_set_variant(GVariant **slot, GVariant *v, const char *type)
{
	g_clear_pointer(slot, g_variant_unref);
	if (v && g_variant_is_of_type(v, G_VARIANT_TYPE(type)))
		*slot = g_variant_ref(v);
}

/* merges Device1 properties (a{sv}) into d; returns whether they hold an advertisement (RSSI or
 * advertising data: what BlueZ signals for each advertisement it receives while discovering) */
static gboolean tl_bz_device_merge(TlBzDevice *d, GVariant *props)
{
	gboolean advert = FALSE;
	GVariantIter it;
	const char *key;
	GVariant *v;
	g_variant_iter_init(&it, props);
	while (g_variant_iter_next(&it, "{&sv}", &key, &v)) {
		if (!strcmp(key, "Address") && g_variant_is_of_type(v, G_VARIANT_TYPE_STRING)) {
			g_strlcpy(d->address, g_variant_get_string(v, NULL), sizeof(d->address));
		} else if (!strcmp(key, "AddressType") && g_variant_is_of_type(v, G_VARIANT_TYPE_STRING)) {
			d->addr_type = !strcmp(g_variant_get_string(v, NULL), "random") ? 1 : 0;
		} else if (!strcmp(key, "Name") && g_variant_is_of_type(v, G_VARIANT_TYPE_STRING)) {
			g_free(d->name);
			d->name = g_variant_dup_string(v, NULL);
		} else if (!strcmp(key, "AdvertisingFlags")) {
			tl_bz_set_variant(&d->flags, v, "ay");
		} else if (!strcmp(key, "UUIDs")) {
			tl_bz_set_variant(&d->uuids, v, "as");
		} else if (!strcmp(key, "ManufacturerData")) {
			tl_bz_set_variant(&d->manufacturer, v, "a{qv}");
			advert = TRUE;
		} else if (!strcmp(key, "ServiceData")) {
			tl_bz_set_variant(&d->service_data, v, "a{sv}");
			advert = TRUE;
		} else if (!strcmp(key, "AdvertisingData")) {
			tl_bz_set_variant(&d->ad, v, "a{yv}");
			advert = TRUE;
		} else if (!strcmp(key, "TxPower") && g_variant_is_of_type(v, G_VARIANT_TYPE_INT16)) {
			d->has_tx = TRUE;
			d->tx = g_variant_get_int16(v);
		} else if (!strcmp(key, "RSSI") && g_variant_is_of_type(v, G_VARIANT_TYPE_INT16)) {
			d->has_rssi = TRUE;
			d->rssi = g_variant_get_int16(v);
			advert = TRUE;
		} else if (!strcmp(key, "Bonded") && g_variant_is_of_type(v, G_VARIANT_TYPE_BOOLEAN)) {
			d->bonded = g_variant_get_boolean(v);
		} else if (!strcmp(key, "Paired") && g_variant_is_of_type(v, G_VARIANT_TYPE_BOOLEAN)) {
			d->paired = g_variant_get_boolean(v);
		} else if (!strcmp(key, "Class") && g_variant_is_of_type(v, G_VARIANT_TYPE_UINT32)) {
			d->has_class = TRUE;
			d->cls = g_variant_get_uint32(v);
		} else if (!strcmp(key, "Appearance")) {
			d->has_appearance = TRUE;
		}
		g_variant_unref(v);
	}
	return advert;
}

/* invalidated Device1 properties (BlueZ invalidates RSSI and TxPower when discovery ends) */
static void tl_bz_device_invalidate(TlBzDevice *d, GVariant *names)
{
	GVariantIter it;
	const char *key;
	g_variant_iter_init(&it, names);
	while (g_variant_iter_next(&it, "&s", &key)) {
		if (!strcmp(key, "RSSI"))
			d->has_rssi = FALSE;
		else if (!strcmp(key, "TxPower"))
			d->has_tx = FALSE;
		else if (!strcmp(key, "ManufacturerData"))
			g_clear_pointer(&d->manufacturer, g_variant_unref);
		else if (!strcmp(key, "ServiceData"))
			g_clear_pointer(&d->service_data, g_variant_unref);
		else if (!strcmp(key, "AdvertisingData"))
			g_clear_pointer(&d->ad, g_variant_unref);
		else if (!strcmp(key, "AdvertisingFlags"))
			g_clear_pointer(&d->flags, g_variant_unref);
		else if (!strcmp(key, "Name"))
			g_clear_pointer(&d->name, g_free);
	}
}

/* ---- advertising data rebuilt from the device's properties ---- */

static void tl_ad_put(GByteArray *out, guint8 type, const guint8 *data, gsize len)
{
	if (len > 254)
		return; /* not one AD structure; BlueZ never parses such a thing out of an advertisement */
	guint8 hdr[2] = {(guint8)(len + 1), type};
	g_byte_array_append(out, hdr, 2);
	if (len)
		g_byte_array_append(out, data, len);
}

/* a UUID string as 16 bytes, most significant first; FALSE when it is not one */
static gboolean tl_uuid_parse(const char *s, guint8 out[16])
{
	int n = 0;
	for (const char *p = s; *p && n < 32; p++) {
		if (*p == '-')
			continue;
		int v = g_ascii_xdigit_value(*p);
		if (v < 0)
			return FALSE;
		if (n % 2 == 0)
			out[n / 2] = v << 4;
		else
			out[n / 2] |= v;
		n++;
	}
	return n == 32;
}

/* the Bluetooth base UUID 0000xxxx-0000-1000-8000-00805F9B34FB: 2 (16 bit), 4 (32 bit) or 16 bytes */
static int tl_uuid_short(const guint8 u[16])
{
	static const guint8 base[12] = {0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb};
	if (memcmp(u + 4, base, 12))
		return 16;
	return (u[0] == 0 && u[1] == 0) ? 2 : 4;
}

/* the UUID in the AD byte order (little endian) of its short form */
static void tl_uuid_le(const guint8 u[16], int size, guint8 *out)
{
	if (size == 16) {
		for (int i = 0; i < 16; i++)
			out[i] = u[15 - i];
	} else {
		/* the short value is bytes 0..3 of the big-endian UUID */
		for (int i = 0; i < size; i++)
			out[i] = u[3 - i];
	}
}

static gboolean tl_ad_type_rebuilt(guint8 type)
{
	switch (type) {
		case 0x01:                         /* Flags */
		case 0x02: case 0x03:              /* 16-bit service UUIDs */
		case 0x04: case 0x05:              /* 32-bit */
		case 0x06: case 0x07:              /* 128-bit */
		case 0x08: case 0x09:              /* local name */
		case 0x0a:                         /* TX power level */
		case 0x16: case 0x20: case 0x21:   /* service data */
		case 0xff:                         /* manufacturer specific data */
			return TRUE;
	}
	return FALSE;
}

static GByteArray *tl_bz_build_record(TlBzDevice *d)
{
	GByteArray *out = g_byte_array_new();
	if (d->flags) {
		gsize n;
		const guint8 *f = g_variant_get_fixed_array(d->flags, &n, 1);
		if (n)
			tl_ad_put(out, 0x01, f, n);
	}
	if (d->uuids) {
		GByteArray *lists[3] = {g_byte_array_new(), g_byte_array_new(), g_byte_array_new()};
		GVariantIter it;
		const char *s;
		g_variant_iter_init(&it, d->uuids);
		while (g_variant_iter_next(&it, "&s", &s)) {
			guint8 u[16], le[16];
			if (!tl_uuid_parse(s, u))
				continue;
			int size = tl_uuid_short(u);
			tl_uuid_le(u, size, le);
			g_byte_array_append(lists[size == 2 ? 0 : size == 4 ? 1 : 2], le, size);
		}
		static const guint8 types[3] = {0x03, 0x05, 0x07};
		for (int i = 0; i < 3; i++) {
			if (lists[i]->len)
				tl_ad_put(out, types[i], lists[i]->data, lists[i]->len);
			g_byte_array_free(lists[i], TRUE);
		}
	}
	if (d->name && *d->name)
		tl_ad_put(out, 0x09, (const guint8 *)d->name, strlen(d->name));
	if (d->has_tx) {
		guint8 tx = (guint8)(gint8)d->tx;
		tl_ad_put(out, 0x0a, &tx, 1);
	}
	if (d->service_data) {
		GVariantIter it;
		const char *s;
		GVariant *v;
		g_variant_iter_init(&it, d->service_data);
		while (g_variant_iter_next(&it, "{&sv}", &s, &v)) {
			guint8 u[16];
			if (tl_uuid_parse(s, u) && g_variant_is_of_type(v, G_VARIANT_TYPE_BYTESTRING)) {
				int size = tl_uuid_short(u);
				gsize n;
				const guint8 *data = g_variant_get_fixed_array(v, &n, 1);
				guint8 buf[16 + 254];
				if (n + size <= 254) {
					tl_uuid_le(u, size, buf);
					memcpy(buf + size, data, n);
					tl_ad_put(out, size == 2 ? 0x16 : size == 4 ? 0x20 : 0x21, buf, size + n);
				}
			}
			g_variant_unref(v);
		}
	}
	if (d->manufacturer) {
		GVariantIter it;
		guint16 company;
		GVariant *v;
		g_variant_iter_init(&it, d->manufacturer);
		while (g_variant_iter_next(&it, "{qv}", &company, &v)) {
			if (g_variant_is_of_type(v, G_VARIANT_TYPE_BYTESTRING)) {
				gsize n;
				const guint8 *data = g_variant_get_fixed_array(v, &n, 1);
				guint8 buf[2 + 254];
				if (n + 2 <= 254) {
					buf[0] = company & 0xff;
					buf[1] = company >> 8;
					memcpy(buf + 2, data, n);
					tl_ad_put(out, 0xff, buf, n + 2);
				}
			}
			g_variant_unref(v);
		}
	}
	if (d->ad) {
		GVariantIter it;
		guint8 type;
		GVariant *v;
		g_variant_iter_init(&it, d->ad);
		while (g_variant_iter_next(&it, "{yv}", &type, &v)) {
			if (!tl_ad_type_rebuilt(type) && g_variant_is_of_type(v, G_VARIANT_TYPE_BYTESTRING)) {
				gsize n;
				const guint8 *data = g_variant_get_fixed_array(v, &n, 1);
				tl_ad_put(out, type, data, n);
			}
			g_variant_unref(v);
		}
	}
	return out;
}

/* ---- Java ---- */

static void tl_bz_report_adapter(void)
{
	g_mutex_lock(&bz_lock);
	gboolean present = bz_adapter != NULL, powered = present && bz_powered;
	char *address = g_strdup(present ? bz_address : NULL), *alias = g_strdup(present ? bz_alias : NULL);
	gboolean changed = !rep_valid || present != rep_present || powered != rep_powered ||
	                   g_strcmp0(address, rep_address) || g_strcmp0(alias, rep_alias);
	if (changed) {
		rep_valid = TRUE;
		rep_present = present;
		rep_powered = powered;
		g_free(rep_address);
		rep_address = g_strdup(address);
		g_free(rep_alias);
		rep_alias = g_strdup(alias);
	}
	g_mutex_unlock(&bz_lock);
	if (changed) {
		if (tl_bz_debug())
			fprintf(stderr, "[bluetooth] adapter present=%d powered=%d address=%s name=%s\n", present, powered, address, alias);
		JNIEnv *env = get_jni_env();
		jstring jaddress = address ? (*env)->NewStringUTF(env, address) : NULL;
		jstring jalias = alias ? (*env)->NewStringUTF(env, alias) : NULL;
		J__TlBluez__tlOnAdapterState(env, present, powered, jaddress, jalias);
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);
		if (jaddress)
			(*env)->DeleteLocalRef(env, jaddress);
		if (jalias)
			(*env)->DeleteLocalRef(env, jalias);
	}
	g_free(address);
	g_free(alias);
}

static gint64 tl_bz_now_nanos(void)
{
	struct timespec t;
	clock_gettime(CLOCK_BOOTTIME, &t); /* SystemClock.elapsedRealtimeNanos */
	return (gint64)t.tv_sec * 1000000000LL + t.tv_nsec;
}

/* called with bz_lock held; releases it */
static void tl_bz_report_advertisement_unlock(TlBzDevice *d)
{
	if (!bz_listening || !d->has_rssi || !d->address[0]) {
		g_mutex_unlock(&bz_lock);
		return;
	}
	gint64 when = tl_bz_now_nanos();
	GByteArray *record = tl_bz_build_record(d);
	char address[18];
	g_strlcpy(address, d->address, sizeof(address));
	int addr_type = d->addr_type, rssi = d->rssi;
	g_mutex_unlock(&bz_lock);
	if (tl_bz_debug() > 1) {
		char *hex = g_malloc(record->len * 2 + 1);
		for (guint i = 0; i < record->len; i++)
			sprintf(hex + 2 * i, "%02X", record->data[i]);
		hex[record->len * 2] = 0;
		fprintf(stderr, "[bluetooth] advertisement %s rssi %d %s\n", address, rssi, hex);
		g_free(hex);
	}

	JNIEnv *env = get_jni_env();
	jstring jaddress = (*env)->NewStringUTF(env, address);
	jbyteArray jrecord = (*env)->NewByteArray(env, record->len);
	(*env)->SetByteArrayRegion(env, jrecord, 0, record->len, (const jbyte *)record->data);
	J__TlBluez__tlOnAdvertisement(env, jaddress, addr_type, rssi, jrecord, when);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	(*env)->DeleteLocalRef(env, jaddress);
	(*env)->DeleteLocalRef(env, jrecord);
	g_byte_array_free(record, TRUE);
}

/* ---- the adapter and its devices ---- */

static gboolean tl_bz_under_adapter(const char *path)
{
	size_t n = bz_adapter ? strlen(bz_adapter) : 0;
	return n && !strncmp(path, bz_adapter, n) && path[n] == '/';
}

static void tl_bz_adapter_props(GVariant *props)
{
	GVariantIter it;
	const char *key;
	GVariant *v;
	g_variant_iter_init(&it, props);
	while (g_variant_iter_next(&it, "{&sv}", &key, &v)) {
		if (!strcmp(key, "Powered") && g_variant_is_of_type(v, G_VARIANT_TYPE_BOOLEAN)) {
			bz_powered = g_variant_get_boolean(v);
			if (!bz_powered) { /* BlueZ ends every discovery session when the adapter powers off */
				bz_discovery_active = FALSE;
				g_clear_pointer(&bz_applied_uuids, g_strfreev);
			}
		} else if (!strcmp(key, "Address") && g_variant_is_of_type(v, G_VARIANT_TYPE_STRING)) {
			g_free(bz_address);
			bz_address = g_variant_dup_string(v, NULL);
		} else if (!strcmp(key, "Alias") && g_variant_is_of_type(v, G_VARIANT_TYPE_STRING)) {
			g_free(bz_alias);
			bz_alias = g_variant_dup_string(v, NULL);
		}
		g_variant_unref(v);
	}
}

/* whether path is the adapter ATL_BLUETOOTH_ADAPTER asks for (its last path element, "hci1") */
static gboolean tl_bz_adapter_wanted(const char *path)
{
	const char *want = getenv("ATL_BLUETOOTH_ADAPTER");
	if (!want || !*want)
		return TRUE;
	const char *slash = strrchr(path, '/');
	return !strcmp(slash ? slash + 1 : path, want);
}

/* re-reads BlueZ's objects (GetManagedObjects): the adapter and its devices. Called with bz_lock held. */
static void tl_bz_reload_locked(void)
{
	g_clear_pointer(&bz_adapter, g_free);
	g_clear_pointer(&bz_address, g_free);
	g_clear_pointer(&bz_alias, g_free);
	bz_powered = FALSE;
	bz_discovery_active = FALSE;
	g_clear_pointer(&bz_applied_uuids, g_strfreev);
	bz_discovery_pending = FALSE;
	g_hash_table_remove_all(bz_devices);
	GDBusConnection *bus = tl_bz_bus();
	if (!bus)
		return;
	GError *err = NULL;
	/* this call may start bluetoothd (bus activation, when bluetooth.service is enabled but not yet
	 * running); its adapter then arrives with NameOwnerChanged if it takes longer than the timeout */
	GVariant *r = g_dbus_connection_call_sync(bus, BZ_BUS, "/", DBUS_OM, "GetManagedObjects", NULL,
	                                          G_VARIANT_TYPE("(a{oa{sa{sv}}})"), G_DBUS_CALL_FLAGS_NONE,
	                                          TL_BZ_TIMEOUT_MS, NULL, &err);
	if (!r) {
		if (tl_bz_debug())
			fprintf(stderr, "[bluetooth] GetManagedObjects: %s\n", err ? err->message : "?");
		g_clear_error(&err);
		return;
	}
	GVariant *objects = g_variant_get_child_value(r, 0);
	GVariantIter it;
	const char *path;
	GVariant *ifaces;
	/* the adapter: the lowest object path among the wanted ones */
	g_variant_iter_init(&it, objects);
	while (g_variant_iter_next(&it, "{&o@a{sa{sv}}}", &path, &ifaces)) {
		GVariant *adapter = g_variant_lookup_value(ifaces, BZ_ADAPTER, G_VARIANT_TYPE("a{sv}"));
		if (adapter && tl_bz_adapter_wanted(path) && (!bz_adapter || strcmp(path, bz_adapter) < 0)) {
			g_free(bz_adapter);
			bz_adapter = g_strdup(path);
			g_clear_pointer(&bz_address, g_free);
			g_clear_pointer(&bz_alias, g_free);
			bz_powered = FALSE;
			tl_bz_adapter_props(adapter);
		}
		if (adapter)
			g_variant_unref(adapter);
		g_variant_unref(ifaces);
	}
	/* its devices */
	g_variant_iter_init(&it, objects);
	while (g_variant_iter_next(&it, "{&o@a{sa{sv}}}", &path, &ifaces)) {
		GVariant *dev = tl_bz_under_adapter(path) ? g_variant_lookup_value(ifaces, BZ_DEVICE, G_VARIANT_TYPE("a{sv}")) : NULL;
		if (dev) {
			TlBzDevice *d = g_new0(TlBzDevice, 1);
			d->path = g_strdup(path);
			tl_bz_device_merge(d, dev);
			/* the RSSI of a device BlueZ remembers is from some earlier discovery */
			d->has_rssi = FALSE;
			g_hash_table_replace(bz_devices, d->path, d);
			g_variant_unref(dev);
		}
		g_variant_unref(ifaces);
	}
	g_variant_unref(objects);
	g_variant_unref(r);
}

static void tl_bz_discovery_schedule(void);

static void tl_bz_reload(void)
{
	g_mutex_lock(&bz_lock);
	tl_bz_reload_locked();
	g_mutex_unlock(&bz_lock);
	tl_bz_report_adapter();
	tl_bz_discovery_schedule();
}

static void on_interfaces_added(GDBusConnection *bus, const char *sender, const char *opath, const char *iface,
                                const char *signal, GVariant *params, gpointer user_data)
{
	const char *path;
	GVariant *ifaces;
	g_variant_get(params, "(&o@a{sa{sv}})", &path, &ifaces);
	GVariant *adapter = g_variant_lookup_value(ifaces, BZ_ADAPTER, G_VARIANT_TYPE("a{sv}"));
	if (adapter) {
		g_mutex_lock(&bz_lock);
		gboolean better = tl_bz_adapter_wanted(path) && (!bz_adapter || strcmp(path, bz_adapter) < 0);
		g_mutex_unlock(&bz_lock);
		g_variant_unref(adapter);
		g_variant_unref(ifaces);
		if (better)
			tl_bz_reload();
		return;
	}
	GVariant *dev = g_variant_lookup_value(ifaces, BZ_DEVICE, G_VARIANT_TYPE("a{sv}"));
	if (dev) {
		g_mutex_lock(&bz_lock);
		if (tl_bz_under_adapter(path)) {
			TlBzDevice *d = g_hash_table_lookup(bz_devices, path);
			if (!d) {
				d = g_new0(TlBzDevice, 1);
				d->path = g_strdup(path);
				g_hash_table_replace(bz_devices, d->path, d);
			}
			/* a device BlueZ has just found: its first advertisement */
			if (tl_bz_device_merge(d, dev)) {
				tl_bz_report_advertisement_unlock(d);
				g_mutex_lock(&bz_lock);
			}
		}
		g_mutex_unlock(&bz_lock);
		g_variant_unref(dev);
	}
	g_variant_unref(ifaces);
}

static void on_interfaces_removed(GDBusConnection *bus, const char *sender, const char *opath, const char *iface,
                                  const char *signal, GVariant *params, gpointer user_data)
{
	const char *path;
	GVariant *names;
	g_variant_get(params, "(&o@as)", &path, &names);
	gboolean adapter_gone = FALSE;
	GVariantIter it;
	const char *name;
	g_mutex_lock(&bz_lock);
	g_variant_iter_init(&it, names);
	while (g_variant_iter_next(&it, "&s", &name)) {
		if (!strcmp(name, BZ_ADAPTER) && bz_adapter && !strcmp(path, bz_adapter))
			adapter_gone = TRUE;
		else if (!strcmp(name, BZ_DEVICE))
			g_hash_table_remove(bz_devices, path);
	}
	g_mutex_unlock(&bz_lock);
	g_variant_unref(names);
	if (adapter_gone) /* another adapter takes its place, if there is one */
		tl_bz_reload();
}

static void on_properties_changed(GDBusConnection *bus, const char *sender, const char *path, const char *iface,
                                  const char *signal, GVariant *params, gpointer user_data)
{
	const char *which;
	GVariant *changed, *invalidated;
	g_variant_get(params, "(&s@a{sv}@as)", &which, &changed, &invalidated);
	if (!strcmp(which, BZ_DEVICE)) {
		g_mutex_lock(&bz_lock);
		TlBzDevice *d = tl_bz_under_adapter(path) ? g_hash_table_lookup(bz_devices, path) : NULL;
		if (d) {
			tl_bz_device_invalidate(d, invalidated);
			if (tl_bz_device_merge(d, changed)) {
				tl_bz_report_advertisement_unlock(d);
				g_mutex_lock(&bz_lock);
			}
		}
		g_mutex_unlock(&bz_lock);
	} else if (!strcmp(which, BZ_ADAPTER)) {
		g_mutex_lock(&bz_lock);
		gboolean ours = bz_adapter && !strcmp(path, bz_adapter);
		if (ours)
			tl_bz_adapter_props(changed);
		g_mutex_unlock(&bz_lock);
		if (ours) {
			tl_bz_report_adapter();
			tl_bz_discovery_schedule();
		}
	}
	g_variant_unref(changed);
	g_variant_unref(invalidated);
}

static void on_name_owner_changed(GDBusConnection *bus, const char *sender, const char *path, const char *iface,
                                  const char *signal, GVariant *params, gpointer user_data)
{
	const char *name, *old_owner, *new_owner;
	g_variant_get(params, "(&s&s&s)", &name, &old_owner, &new_owner);
	if (strcmp(name, BZ_BUS))
		return;
	if (tl_bz_debug())
		fprintf(stderr, "[bluetooth] bluetoothd %s\n", *new_owner ? "started" : "stopped");
	if (*new_owner) {
		tl_bz_reload();
	} else {
		g_mutex_lock(&bz_lock);
		g_clear_pointer(&bz_adapter, g_free);
		bz_powered = FALSE;
		bz_discovery_active = FALSE;
		g_clear_pointer(&bz_applied_uuids, g_strfreev);
		bz_discovery_pending = FALSE;
		g_hash_table_remove_all(bz_devices);
		g_mutex_unlock(&bz_lock);
		tl_bz_report_adapter();
	}
}

/* ---- this process's discovery session ---- */

static void tl_bz_report_discovery(gboolean ok, const char *error)
{
	JNIEnv *env = get_jni_env();
	jstring jerror = error ? (*env)->NewStringUTF(env, error) : NULL;
	J__TlBluez__tlOnDiscoveryResult(env, ok, jerror);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	if (jerror)
		(*env)->DeleteLocalRef(env, jerror);
}

/* one start or filter change in flight: the adapter it is for and the UUID filter it sets */
typedef struct {
	char *path;
	char **uuids;
	gboolean start;
} TlBzRequest;

static void tl_bz_request_free(TlBzRequest *q)
{
	g_free(q->path);
	g_strfreev(q->uuids);
	g_free(q);
}

static gboolean tl_bz_strv_same(char **a, char **b)
{
	if (!a || !b)
		return a == b;
	return g_strv_equal((const char *const *)a, (const char *const *)b);
}

static void on_start_discovery(GObject *source, GAsyncResult *res, gpointer user_data)
{
	GError *err = NULL;
	GVariant *r = g_dbus_connection_call_finish(G_DBUS_CONNECTION(source), res, &err);
	TlBzRequest *q = user_data;
	gboolean ok = r != NULL;
	char *error = NULL;
	if (r)
		g_variant_unref(r);
	if (err) {
		char *remote = g_dbus_error_get_remote_error(err);
		/* InProgress: this client's session is already running */
		ok = remote && !strcmp(remote, "org.bluez.Error.InProgress");
		if (!ok)
			error = g_strdup_printf("%s: %s", remote ? remote : "error", err->message);
		g_free(remote);
		g_clear_error(&err);
	}
	g_mutex_lock(&bz_lock);
	gboolean same = bz_adapter && !g_strcmp0(q->path, bz_adapter);
	bz_discovery_pending = FALSE;
	if (same) {
		bz_discovery_active = ok;
		g_strfreev(bz_applied_uuids);
		bz_applied_uuids = ok ? g_strdupv(q->uuids) : NULL;
	}
	g_mutex_unlock(&bz_lock);
	tl_bz_request_free(q);
	if (tl_bz_debug())
		fprintf(stderr, "[bluetooth] StartDiscovery: %s\n", ok ? "ok" : error);
	if (same)
		tl_bz_report_discovery(ok, error);
	g_free(error);
	tl_bz_discovery_schedule();
}

static void on_set_filter(GObject *source, GAsyncResult *res, gpointer user_data)
{
	GError *err = NULL;
	GVariant *r = g_dbus_connection_call_finish(G_DBUS_CONNECTION(source), res, &err);
	if (r)
		g_variant_unref(r);
	TlBzRequest *q = user_data;
	if (err) {
		/* without the filter BlueZ still discovers (BR/EDR and LE, duplicates filtered): fewer results */
		if (tl_bz_debug())
			fprintf(stderr, "[bluetooth] SetDiscoveryFilter: %s\n", err->message);
		g_clear_error(&err);
	}
	if (q->start) {
		g_dbus_connection_call(G_DBUS_CONNECTION(source), BZ_BUS, q->path, BZ_ADAPTER, "StartDiscovery", NULL, NULL,
		                       G_DBUS_CALL_FLAGS_NO_AUTO_START, TL_BZ_POWER_TIMEOUT_MS, NULL, on_start_discovery, q);
		return;
	}
	/* a new filter for the running session */
	g_mutex_lock(&bz_lock);
	bz_discovery_pending = FALSE;
	if (bz_adapter && !g_strcmp0(q->path, bz_adapter)) {
		g_strfreev(bz_applied_uuids);
		bz_applied_uuids = g_strdupv(q->uuids);
	}
	g_mutex_unlock(&bz_lock);
	tl_bz_request_free(q);
	tl_bz_discovery_schedule();
}

/* the session's filter: LE only, every advertisement (not only changes), and the service UUIDs
 * the scans filter on, when every scan that starts discovery has such filters (BlueZ then reports
 * only devices that advertise one of them, which keeps the signal traffic of a busy street off
 * the main thread; the scans still apply their own filters) */
static GVariant *tl_bz_filter(char **uuids)
{
	GVariantBuilder b;
	g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
	g_variant_builder_add(&b, "{sv}", "Transport", g_variant_new_string("le"));
	g_variant_builder_add(&b, "{sv}", "DuplicateData", g_variant_new_boolean(TRUE));
	if (uuids && *uuids)
		g_variant_builder_add(&b, "{sv}", "UUIDs", g_variant_new_strv((const char *const *)uuids, -1));
	return g_variant_new("(a{sv})", &b);
}

static void on_stop_discovery(GObject *source, GAsyncResult *res, gpointer user_data)
{
	GError *err = NULL;
	GVariant *r = g_dbus_connection_call_finish(G_DBUS_CONNECTION(source), res, &err);
	if (r)
		g_variant_unref(r);
	if (err) {
		if (tl_bz_debug())
			fprintf(stderr, "[bluetooth] StopDiscovery: %s\n", err->message);
		g_clear_error(&err);
	}
	tl_bz_discovery_schedule();
}

/* brings the session to what Java wants (on the main context) */
static gboolean tl_bz_discovery_apply(gpointer data)
{
	GDBusConnection *bus = tl_bz_bus();
	g_mutex_lock(&bz_lock);
	bz_discovery_apply_source = 0;
	gboolean stop = FALSE, fail = FALSE;
	TlBzRequest *q = NULL;
	char *path = NULL;
	if (!bz_discovery_pending) {
		if (bz_want_discovery && !bz_discovery_active) {
			if (bus && bz_adapter && bz_powered) {
				q = g_new0(TlBzRequest, 1);
				q->start = TRUE;
			} else {
				fail = TRUE;
			}
		} else if (bz_want_discovery && bus && bz_adapter && !tl_bz_strv_same(bz_want_uuids, bz_applied_uuids)) {
			q = g_new0(TlBzRequest, 1); /* the scans' service UUIDs changed */
		} else if (!bz_want_discovery && bz_discovery_active) {
			bz_discovery_active = FALSE;
			g_clear_pointer(&bz_applied_uuids, g_strfreev);
			if (bus && bz_adapter) {
				stop = TRUE;
				path = g_strdup(bz_adapter);
			}
		}
		if (q) {
			bz_discovery_pending = TRUE;
			q->path = g_strdup(bz_adapter);
			q->uuids = g_strdupv(bz_want_uuids);
		}
	}
	g_mutex_unlock(&bz_lock);
	if (q) {
		g_dbus_connection_call(bus, BZ_BUS, q->path, BZ_ADAPTER, "SetDiscoveryFilter", tl_bz_filter(q->uuids), NULL,
		                       G_DBUS_CALL_FLAGS_NO_AUTO_START, TL_BZ_TIMEOUT_MS, NULL, on_set_filter, q);
	} else if (stop) {
		g_dbus_connection_call(bus, BZ_BUS, path, BZ_ADAPTER, "StopDiscovery", NULL, NULL,
		                       G_DBUS_CALL_FLAGS_NO_AUTO_START, TL_BZ_TIMEOUT_MS, NULL, on_stop_discovery, NULL);
		g_free(path);
	} else if (fail) {
		tl_bz_report_discovery(FALSE, "org.bluez.Error.NotReady: no powered adapter");
	}
	return G_SOURCE_REMOVE;
}

static void tl_bz_discovery_schedule(void)
{
	g_mutex_lock(&bz_lock);
	gboolean differs = bz_want_discovery != bz_discovery_active ||
	                   (bz_want_discovery && !tl_bz_strv_same(bz_want_uuids, bz_applied_uuids));
	gboolean needed = bz_started && !bz_discovery_apply_source && differs && !bz_discovery_pending;
	if (needed)
		bz_discovery_apply_source = g_idle_add(tl_bz_discovery_apply, NULL);
	g_mutex_unlock(&bz_lock);
}

/* ---- natives ---- */

static gboolean tl_bz_disabled(void)
{
	const char *v = getenv("ATL_BLUETOOTH");
	return v && (!strcmp(v, "off") || !strcmp(v, "0") || !strcmp(v, "no"));
}

JNIEXPORT jboolean JNICALL Java_android_bluetooth_TlBluez_nativeHasBluetooth(JNIEnv *env, jclass this)
{
	if (tl_bz_disabled())
		return JNI_FALSE;
	GDBusConnection *bus = tl_bz_bus();
	if (!bus)
		return JNI_FALSE;
	GVariant *r = g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
	                                          "NameHasOwner", g_variant_new("(s)", BZ_BUS), G_VARIANT_TYPE("(b)"),
	                                          G_DBUS_CALL_FLAGS_NONE, TL_BZ_TIMEOUT_MS, NULL, NULL);
	gboolean has = FALSE;
	if (r) {
		g_variant_get(r, "(b)", &has);
		g_variant_unref(r);
	}
	if (has)
		return JNI_TRUE;
	/* not running now, but the bus starts it on demand (bluetooth.service enabled) */
	r = g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
	                                "ListActivatableNames", NULL, G_VARIANT_TYPE("(as)"),
	                                G_DBUS_CALL_FLAGS_NONE, TL_BZ_TIMEOUT_MS, NULL, NULL);
	if (r) {
		GVariantIter *it;
		const char *name;
		g_variant_get(r, "(as)", &it);
		while (g_variant_iter_next(it, "&s", &name)) {
			if (!strcmp(name, BZ_BUS))
				has = TRUE;
		}
		g_variant_iter_free(it);
		g_variant_unref(r);
	}
	return has;
}

static gboolean tl_bz_subscribe(gpointer data)
{
	GDBusConnection *bus = data;
	g_dbus_connection_signal_subscribe(bus, BZ_BUS, DBUS_OM, "InterfacesAdded", NULL, NULL,
	                                   G_DBUS_SIGNAL_FLAGS_NONE, on_interfaces_added, NULL, NULL);
	g_dbus_connection_signal_subscribe(bus, BZ_BUS, DBUS_OM, "InterfacesRemoved", NULL, NULL,
	                                   G_DBUS_SIGNAL_FLAGS_NONE, on_interfaces_removed, NULL, NULL);
	g_dbus_connection_signal_subscribe(bus, BZ_BUS, DBUS_PROPS, "PropertiesChanged", NULL, NULL,
	                                   G_DBUS_SIGNAL_FLAGS_NONE, on_properties_changed, NULL, NULL);
	g_dbus_connection_signal_subscribe(bus, "org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged", "/org/freedesktop/DBus",
	                                   BZ_BUS, G_DBUS_SIGNAL_FLAGS_NONE, on_name_owner_changed, NULL, NULL);
	return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_android_bluetooth_TlBluez_nativeStartMonitor(JNIEnv *env, jclass this)
{
	g_mutex_lock(&bz_lock);
	if (bz_started) {
		g_mutex_unlock(&bz_lock);
		return;
	}
	bz_started = TRUE;
	bz_devices = g_hash_table_new_full(g_str_hash, g_str_equal, NULL, tl_bz_device_free);
	GDBusConnection *bus = tl_bz_disabled() ? NULL : tl_bz_bus();
	if (bus) {
		/* the callbacks run in the thread-default context of the subscribing thread, which must be
		 * the global default one (the main thread's loop iterates it): a thread without a context
		 * of its own subscribes here, one with its own context hands the subscription over (the
		 * default context is owned by the main loop, so it cannot be pushed from here) */
		GMainContext *own = g_main_context_get_thread_default();
		if (!own || own == g_main_context_default())
			tl_bz_subscribe(bus);
		else
			g_main_context_invoke(NULL, tl_bz_subscribe, bus);
		tl_bz_reload_locked();
	}
	g_mutex_unlock(&bz_lock);
	tl_bz_report_adapter();
}

JNIEXPORT void JNICALL Java_android_bluetooth_TlBluez_nativeSetListening(JNIEnv *env, jclass this, jboolean listening)
{
	g_mutex_lock(&bz_lock);
	bz_listening = listening;
	g_mutex_unlock(&bz_lock);
}

JNIEXPORT void JNICALL Java_android_bluetooth_TlBluez_nativeSetDiscovery(JNIEnv *env, jclass this, jboolean discovering, jobjectArray juuids)
{
	char **uuids = NULL;
	jsize n = juuids ? (*env)->GetArrayLength(env, juuids) : 0;
	if (n > 0) {
		uuids = g_new0(char *, n + 1);
		for (jsize i = 0; i < n; i++) {
			jstring s = (*env)->GetObjectArrayElement(env, juuids, i);
			const char *c = (*env)->GetStringUTFChars(env, s, NULL);
			uuids[i] = g_ascii_strdown(c, -1);
			(*env)->ReleaseStringUTFChars(env, s, c);
			(*env)->DeleteLocalRef(env, s);
		}
	}
	g_mutex_lock(&bz_lock);
	bz_want_discovery = discovering;
	g_strfreev(bz_want_uuids);
	bz_want_uuids = discovering ? uuids : NULL;
	if (!discovering)
		g_strfreev(uuids);
	g_mutex_unlock(&bz_lock);
	tl_bz_discovery_schedule();
}

JNIEXPORT jboolean JNICALL Java_android_bluetooth_TlBluez_nativeSetPowered(JNIEnv *env, jclass this, jboolean powered)
{
	GDBusConnection *bus = tl_bz_bus();
	g_mutex_lock(&bz_lock);
	char *path = g_strdup(bz_adapter);
	g_mutex_unlock(&bz_lock);
	if (!bus || !path)
		return JNI_FALSE;
	GError *err = NULL;
	GVariant *r = g_dbus_connection_call_sync(bus, BZ_BUS, path, DBUS_PROPS, "Set",
	                                          g_variant_new("(ssv)", BZ_ADAPTER, "Powered", g_variant_new_boolean(powered)),
	                                          NULL, G_DBUS_CALL_FLAGS_NO_AUTO_START, TL_BZ_POWER_TIMEOUT_MS, NULL, &err);
	g_free(path);
	if (!r) {
		if (tl_bz_debug())
			fprintf(stderr, "[bluetooth] Powered=%d: %s\n", powered, err ? err->message : "?");
		g_clear_error(&err);
		return JNI_FALSE;
	}
	g_variant_unref(r);
	return JNI_TRUE;
}

static TlBzDevice *tl_bz_find_address_locked(const char *address)
{
	if (!bz_devices)
		return NULL;
	GHashTableIter it;
	gpointer key, value;
	g_hash_table_iter_init(&it, bz_devices);
	while (g_hash_table_iter_next(&it, &key, &value)) {
		TlBzDevice *d = value;
		if (!g_ascii_strcasecmp(d->address, address))
			return d;
	}
	return NULL;
}

JNIEXPORT jstring JNICALL Java_android_bluetooth_TlBluez_nativeDescribeDevice(JNIEnv *env, jclass this, jstring jaddress)
{
	GString *out = g_string_new(NULL);
	const char *address = (*env)->GetStringUTFChars(env, jaddress, NULL);
	g_mutex_lock(&bz_lock);
	TlBzDevice *d = tl_bz_find_address_locked(address);
	if (d) {
		if (d->name && !strchr(d->name, '\n'))
			g_string_append_printf(out, "name=%s\n", d->name);
		g_string_append_printf(out, "addressType=%s\n", d->addr_type ? "random" : "public");
		g_string_append_printf(out, "bonded=%d\npaired=%d\n", d->bonded, d->paired);
		if (d->has_class)
			g_string_append_printf(out, "class=%u\nbredr=1\n", d->cls);
		/* advertising flags, appearance and random addresses exist only on LE */
		if (d->flags || d->has_appearance || d->addr_type == 1)
			g_string_append(out, "le=1\n");
		if (d->uuids) {
			GVariantIter it;
			const char *s;
			g_variant_iter_init(&it, d->uuids);
			while (g_variant_iter_next(&it, "&s", &s))
				g_string_append_printf(out, "uuid=%s\n", s);
		}
	}
	g_mutex_unlock(&bz_lock);
	(*env)->ReleaseStringUTFChars(env, jaddress, address);
	jstring ret = (*env)->NewStringUTF(env, out->str);
	g_string_free(out, TRUE);
	return ret;
}

JNIEXPORT jobjectArray JNICALL Java_android_bluetooth_TlBluez_nativeBondedDevices(JNIEnv *env, jclass this)
{
	GPtrArray *found = g_ptr_array_new_with_free_func(g_free);
	g_mutex_lock(&bz_lock);
	if (bz_devices) {
		GHashTableIter it;
		gpointer key, value;
		g_hash_table_iter_init(&it, bz_devices);
		while (g_hash_table_iter_next(&it, &key, &value)) {
			TlBzDevice *d = value;
			if ((d->bonded || d->paired) && d->address[0])
				g_ptr_array_add(found, g_strdup(d->address));
		}
	}
	g_mutex_unlock(&bz_lock);
	jobjectArray ret = (*env)->NewObjectArray(env, found->len, (*env)->FindClass(env, "java/lang/String"), NULL);
	for (guint i = 0; i < found->len; i++) {
		jstring s = (*env)->NewStringUTF(env, found->pdata[i]);
		(*env)->SetObjectArrayElement(env, ret, i, s);
		(*env)->DeleteLocalRef(env, s);
	}
	g_ptr_array_free(found, TRUE);
	return ret;
}
