package android.net;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public final class LinkProperties {
	public LinkProperties() {}
	public String getInterfaceName() { return "wlan0"; }
	public List<InetAddress> getDnsServers() { return new ArrayList<>(); }
	public List<Object> getLinkAddresses() { return new ArrayList<>(); }
	public List<Object> getRoutes() { return new ArrayList<>(); }
	public String getDomains() { return null; }
	public int getMtu() { return 1500; }
	public boolean isPrivateDnsActive() { return false; }
	public String getPrivateDnsServerName() { return null; }
	public boolean isWakeOnLanSupported() { return false; }
}
