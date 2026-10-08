package android.media.metrics;

public final class LogSessionId {
	public static final LogSessionId LOG_SESSION_ID_NONE = new LogSessionId("");
	private final String id;
	public LogSessionId(String id) { this.id = id; }
	public String getStringId() { return id; }
	@Override public boolean equals(Object o) { return o instanceof LogSessionId && ((LogSessionId) o).id.equals(id); }
	@Override public int hashCode() { return id.hashCode(); }
}
