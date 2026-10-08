package android.net;

public final class NetworkCapabilities {
	public boolean hasCapability(int capability) {
		return false;
	}

	/* teslalinux: NetworkCapabilities additions (we always report an unmetered, validated Wi-Fi) */
	public static final int TRANSPORT_CELLULAR = 0, TRANSPORT_WIFI = 1, TRANSPORT_BLUETOOTH = 2, TRANSPORT_ETHERNET = 3, TRANSPORT_VPN = 4, TRANSPORT_WIFI_AWARE = 5, TRANSPORT_LOWPAN = 6, TRANSPORT_USB = 8;
	public boolean hasTransport(int transportType) { return transportType == TRANSPORT_WIFI || transportType == TRANSPORT_ETHERNET; }
	public int getLinkDownstreamBandwidthKbps() { return 100000; }
	public int getLinkUpstreamBandwidthKbps() { return 50000; }
	public int[] getCapabilities() { return new int[] { 12, 16, 11, 13 }; }
	public int getSignalStrength() { return -50; }
	public int getOwnerUid() { return -1; }
}
