package android.net;

public class NetworkRequest {

	public class Builder {

		public NetworkRequest build() {
			return new NetworkRequest();
		}

		public Builder addCapability(int capability) {
			return this;
		}
	
		public Builder addTransportType(int transportType) { return this; }
		public Builder removeCapability(int capability) { return this; }
		public Builder removeTransportType(int transportType) { return this; }
		public Builder setIncludeOtherUidNetworks(boolean include) { return this; }
}

	/* teslalinux: NetworkRequest additions */
	public boolean canBeSatisfiedBy(NetworkCapabilities nc) { return true; }
	public int[] getCapabilities() { return new int[] { 12, 16 }; }
	public int[] getTransportTypes() { return new int[] { 1 }; }
	public boolean hasCapability(int capability) { return true; }
	public boolean hasTransport(int transportType) { return transportType == 1; }
}
