package android.system;

public final class StructUtsname {
	public final String sysname, nodename, release, version, machine;
	public StructUtsname(String sysname, String nodename, String release, String version, String machine) {
		this.sysname = sysname; this.nodename = nodename; this.release = release; this.version = version; this.machine = machine;
	}
}
