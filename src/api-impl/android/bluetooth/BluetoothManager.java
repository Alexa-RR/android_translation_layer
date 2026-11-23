package android.bluetooth;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * High level manager used to obtain an instance of an {@link BluetoothAdapter} and to conduct
 * overall Bluetooth management (AOSP API).
 *
 * teslalinux: Context.getSystemService(BLUETOOTH_SERVICE) hands one out only when the host has
 * Bluetooth (PackageManager FEATURE_BLUETOOTH, see BluetoothAdapter), as AOSP does. GATT
 * connections are not implemented by this runtime, so nothing is ever connected over GATT and no
 * GATT server can be opened (null, as AOSP answers when the stack cannot register one).
 */
public class BluetoothManager {
	private final Context mContext;

	/** @hide */
	public BluetoothManager() {
		this(null);
	}

	/** @hide */
	public BluetoothManager(Context context) {
		mContext = context;
	}

	/** AOSP: the default adapter, null without Bluetooth. */
	public BluetoothAdapter getAdapter() {
		return BluetoothAdapter.getDefaultAdapter();
	}

	/** AOSP: the connection state of a device over GATT or GATT_SERVER: never connected here. */
	public int getConnectionState(BluetoothDevice device, int profile) {
		TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getConnectionState");
		if (profile != BluetoothProfile.GATT && profile != BluetoothProfile.GATT_SERVER)
			throw new IllegalArgumentException("Profile not supported: " + profile);
		return BluetoothProfile.STATE_DISCONNECTED;
	}

	/** AOSP: the devices connected over GATT or GATT_SERVER: none here. */
	public List<BluetoothDevice> getConnectedDevices(int profile) {
		TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getConnectedDevices");
		if (profile != BluetoothProfile.GATT && profile != BluetoothProfile.GATT_SERVER)
			throw new IllegalArgumentException("Profile not supported: " + profile);
		return new ArrayList<>();
	}

	/** AOSP: the devices in one of the states over GATT or GATT_SERVER: only disconnected ones exist here, and the stack lists none. */
	public List<BluetoothDevice> getDevicesMatchingConnectionStates(int profile, int[] states) {
		TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getDevicesMatchingConnectionStates");
		if (profile != BluetoothProfile.GATT && profile != BluetoothProfile.GATT_SERVER)
			throw new IllegalArgumentException("Profile not supported: " + profile);
		return new ArrayList<>();
	}
}
