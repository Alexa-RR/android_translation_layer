/* teslalinux: the host side of android.net.ConnectivityManager (tl_connectivity).
 * GLib's GNetworkMonitor knows whether a default route exists, whether the connection is metered
 * and whether it reaches the internet (full), a captive portal or only the local network. The
 * Java side turns that into Network/NetworkCapabilities/NetworkInfo and delivers callbacks;
 * "network-changed" (emitted on the GLib main loop) feeds ConnectivityManager.tlOnNetworkChanged. */
#include <gio/gio.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_net_ConnectivityManager.h"

static gboolean tl_cm_started;

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

static void on_network_changed(GNetworkMonitor *monitor, gboolean network_available, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	J__ConnectivityManager__tlOnNetworkChanged(env, (jboolean)network_available);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

JNIEXPORT void JNICALL Java_android_net_ConnectivityManager_nativeStartMonitor(JNIEnv *env, jclass this)
{
	if (tl_cm_started)
		return;
	tl_cm_started = TRUE;
	g_signal_connect(g_network_monitor_get_default(), "network-changed", G_CALLBACK(on_network_changed), NULL);
}
