package android.bluetooth;

import android.atl.ATLLoadedApp;
import android.atl.ATLPackageSettings;
import android.atl.annotation.Export;
import android.bluetooth.le.BluetoothLeScanner;
import android.os.Build;

/**
 * @hide teslalinux: the host side of android.bluetooth, BlueZ (bluetoothd) over the D-Bus system bus
 * (android_bluetooth_TlBluez.c). AOSP's Bluetooth stack runs in the Bluetooth app; here BlueZ owns
 * the controller and this process talks to it as one D-Bus client:
 * <ul>
 * <li>the adapter: the first org.bluez.Adapter1 (by object path, hci0 before hci1), or the one named
 * by ATL_BLUETOOTH_ADAPTER ("hci1"); it comes and goes with bluetoothd and with USB adapters, and
 * its Powered property is the adapter's ON/OFF state. ATL_BLUETOOTH=off hides Bluetooth from apps
 * (no feature, no adapter), as on a device without it.</li>
 * <li>LE scanning: one discovery session of this process (Adapter1.SetDiscoveryFilter with
 * Transport "le" and DuplicateData, so that BlueZ signals every advertisement, then StartDiscovery)
 * while at least one scanner that is not opportunistic is registered. Every advertisement arrives
 * as a PropertiesChanged of its org.bluez.Device1 with the RSSI and the data the advertisement
 * carried; the native side keeps each device's properties and rebuilds the advertising data
 * structures from them for ScanRecord.parseFromBytes (BlueZ does not hand out the raw PDU).</li>
 * </ul>
 * The natives start nothing until a Bluetooth API is used. The callbacks run on GLib's main loop
 * (the app's main thread in android-translation-layer).
 */
public final class TlBluez {
	private TlBluez() {}

	static final String BLUETOOTH = "android.permission.BLUETOOTH";
	static final String BLUETOOTH_ADMIN = "android.permission.BLUETOOTH_ADMIN";
	public static final String BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN";
	static final String BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";

	/** The app's target SDK (the running app; Integer.MAX_VALUE before it is known). */
	public static int targetSdk() {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		return app != null && app.pkg != null ? app.pkg.applicationInfo.targetSdkVersion : Integer.MAX_VALUE;
	}

	/**
	 * AOSP: Android 12's runtime permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)
	 * guard the APIs for apps targeting Android 12 or later on Android 12 or later; the others need
	 * the install-time BLUETOOTH and BLUETOOTH_ADMIN.
	 */
	public static boolean usesModernPermissions() {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		return app == null || app.effective_sdk_compat >= Build.VERSION_CODES.S;
	}

	/** Whether the running app holds the permission (ATL's permission model, ATLPackageSettings). */
	public static boolean isGranted(String permission) {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		if (app == null || app.pkg == null)
			return false;
		return ATLPackageSettings.isGrantedToApp(app.pkg, permission);
	}

	/**
	 * AOSP: an Android 12 target that declared BLUETOOTH_SCAN with usesPermissionFlags
	 * neverForLocation gets scan results without location permission, but no location beacons.
	 */
	public static boolean scanDisavowsLocation() {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		return usesModernPermissions() && app != null && app.pkg != null &&
		    app.pkg.requestedPermissionsNeverForLocation.contains(BLUETOOTH_SCAN);
	}

	/** AOSP's check of a Bluetooth API: the modern permission for Android 12 targets, else the legacy one. */
	public static void enforce(String modern, String legacy, String what) {
		String needed = usesModernPermissions() ? modern : legacy;
		if (!isGranted(needed))
			throw new SecurityException("Need " + needed + " permission for " + what);
	}

	/** AOSP: an API that needs a legacy permission before Android 12 and none after (getState, isEnabled). */
	static void enforceLegacyOnly(String legacy, String what) {
		if (!usesModernPermissions() && !isGranted(legacy))
			throw new SecurityException("Need " + legacy + " permission for " + what);
	}

	/** BlueZ is part of this host (bluetoothd runs or the bus can start it) and not turned off. */
	static native boolean nativeHasBluetooth();

	/** Starts watching BlueZ; reports the current adapter through tlOnAdapterState before it returns. */
	static native void nativeStartMonitor();

	/** Whether advertisements are reported (tlOnAdvertisement) at all: some scanner is registered. */
	public static native void nativeSetListening(boolean listening);

	/**
	 * Starts (filter + StartDiscovery) or stops this process's discovery session; the start's result
	 * comes as tlOnDiscoveryResult. serviceUuids, when not null, become BlueZ's UUIDs filter: only
	 * devices advertising one of them are reported (set again when it changes while discovering).
	 */
	public static native void nativeSetDiscovery(boolean discovering, String[] serviceUuids);

	/** Sets the adapter's Powered property; true when BlueZ accepted it. */
	static native boolean nativeSetPowered(boolean powered);

	/**
	 * What BlueZ knows of a remote device of the adapter, as "key=value" lines: name, addressType
	 * (public/random), bonded, paired, uuid (one line per service UUID), class (Class of Device),
	 * le (seen over LE), bredr (seen over BR/EDR); an empty string for an unknown device.
	 */
	static native String nativeDescribeDevice(String address);

	/** The addresses of the adapter's bonded devices (Device1 Bonded, or Paired for BlueZ before 5.70). */
	static native String[] nativeBondedDevices();

	/** The adapter came, went or changed (BlueZ's Adapter1 properties). */
	@Export
	static void tlOnAdapterState(boolean present, boolean powered, String address, String name) {
		BluetoothAdapter.tlOnHostState(present, powered, address, name);
	}

	/** The answer to a discovery start: ok, or BlueZ's error name and message. */
	@Export
	static void tlOnDiscoveryResult(boolean ok, String error) {
		BluetoothLeScanner.tlOnDiscoveryResult(ok, error);
	}

	/**
	 * One advertisement: the device's address and address type (0 public, 1 random), the RSSI, the
	 * advertising data structures (AD length, type, data, ...) and when it arrived
	 * (SystemClock.elapsedRealtimeNanos).
	 */
	@Export
	static void tlOnAdvertisement(String address, int addressType, int rssi, byte[] record, long timestampNanos) {
		BluetoothLeScanner.tlOnAdvertisement(address, addressType, rssi, record, timestampNanos);
	}
}
