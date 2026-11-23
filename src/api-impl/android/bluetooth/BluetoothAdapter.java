/*
 * Copyright 2009-2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.bluetooth;

import android.app.ActivityThread;
import android.app.Application;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Represents the local device Bluetooth adapter (AOSP API).
 *
 * teslalinux: the adapter is BlueZ's (TlBluez). The device has Bluetooth (FEATURE_BLUETOOTH,
 * FEATURE_BLUETOOTH_LE, an adapter object) when BlueZ is part of the host; without it
 * {@link #getDefaultAdapter()} returns null, as AOSP does on hardware without Bluetooth. The
 * adapter's state is BlueZ's: STATE_ON while a controller is present and powered, STATE_OFF while
 * none is (bluetoothd stopped, the USB adapter unplugged, the controller powered off), with AOSP's
 * ACTION_STATE_CHANGED broadcasts (OFF, TURNING_ON, ON and back) as it comes and goes.
 *
 * Supported: the adapter's state, name and address, LE scanning (BluetoothLeScanner and the legacy
 * startLeScan), the remote devices BlueZ knows (name, type, bond state, UUIDs) and the bonded ones.
 * Classic (BR/EDR) device discovery, profiles, GATT connections, LE advertising and sockets are
 * not implemented by this runtime: discovery reports that it could not start (false), as AOSP
 * does when the stack refuses it, the profile queries report nothing connected and no profile
 * proxy is offered, and there is no LE advertiser.
 */
public final class BluetoothAdapter {
    private static final String TAG = "BluetoothAdapter";

    public static final int ERROR = Integer.MIN_VALUE;

    public static final String ACTION_STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED";
    public static final String EXTRA_STATE = "android.bluetooth.adapter.extra.STATE";
    public static final String EXTRA_PREVIOUS_STATE = "android.bluetooth.adapter.extra.PREVIOUS_STATE";
    public static final String ACTION_REQUEST_ENABLE = "android.bluetooth.adapter.action.REQUEST_ENABLE";
    public static final String ACTION_REQUEST_DISABLE = "android.bluetooth.adapter.action.REQUEST_DISABLE";
    public static final String ACTION_REQUEST_DISCOVERABLE = "android.bluetooth.adapter.action.REQUEST_DISCOVERABLE";
    public static final String ACTION_SCAN_MODE_CHANGED = "android.bluetooth.adapter.action.SCAN_MODE_CHANGED";
    public static final String ACTION_DISCOVERY_STARTED = "android.bluetooth.adapter.action.DISCOVERY_STARTED";
    public static final String ACTION_DISCOVERY_FINISHED = "android.bluetooth.adapter.action.DISCOVERY_FINISHED";
    public static final String ACTION_LOCAL_NAME_CHANGED = "android.bluetooth.adapter.action.LOCAL_NAME_CHANGED";
    public static final String ACTION_CONNECTION_STATE_CHANGED = "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED";
    public static final String EXTRA_CONNECTION_STATE = "android.bluetooth.adapter.extra.CONNECTION_STATE";
    public static final String EXTRA_PREVIOUS_CONNECTION_STATE = "android.bluetooth.adapter.extra.PREVIOUS_CONNECTION_STATE";
    public static final String EXTRA_SCAN_MODE = "android.bluetooth.adapter.extra.SCAN_MODE";
    public static final String EXTRA_PREVIOUS_SCAN_MODE = "android.bluetooth.adapter.extra.PREVIOUS_SCAN_MODE";
    public static final String EXTRA_DISCOVERABLE_DURATION = "android.bluetooth.adapter.extra.DISCOVERABLE_DURATION";
    public static final String EXTRA_LOCAL_NAME = "android.bluetooth.adapter.extra.LOCAL_NAME";

    public static final int STATE_OFF = 10;
    public static final int STATE_TURNING_ON = 11;
    public static final int STATE_ON = 12;
    public static final int STATE_TURNING_OFF = 13;

    public static final int SCAN_MODE_NONE = 20;
    public static final int SCAN_MODE_CONNECTABLE = 21;
    public static final int SCAN_MODE_CONNECTABLE_DISCOVERABLE = 23;

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_DISCONNECTING = 3;

    /** AOSP: the address apps without LOCAL_MAC_ADDRESS (privileged) see since Android 6. */
    static final String DEFAULT_MAC_ADDRESS = "02:00:00:00:00:00";

    private static final int ADDRESS_LENGTH = 17;
    /** AOSP: the advertising data length of legacy advertising, the only kind the scanner reports. */
    private static final int LEGACY_ADVERTISING_DATA_LENGTH = 31;

    private static final Object sLock = new Object();
    private static Boolean sHasBluetooth;
    private static BluetoothAdapter sAdapter;
    private static boolean sMonitoring;
    /* BlueZ's adapter as TlBluez last reported it */
    private static boolean sPresent;
    private static String sHostAddress, sHostName;
    private static int sState = STATE_OFF;

    private BluetoothLeScanner mLeScanner;
    private final Map<LeScanCallback, ScanCallback> mLeScanClients = new HashMap<>();

    private BluetoothAdapter() {
    }

    /** @hide whether the host has Bluetooth (PackageManager FEATURE_BLUETOOTH / FEATURE_BLUETOOTH_LE) */
    public static boolean hostHasBluetooth() {
        synchronized (sLock) {
            if (sHasBluetooth == null)
                sHasBluetooth = TlBluez.nativeHasBluetooth();
            return sHasBluetooth;
        }
    }

    /** AOSP: the adapter, or null when the device has no Bluetooth. */
    public static BluetoothAdapter getDefaultAdapter() {
        if (!hostHasBluetooth())
            return null;
        boolean start = false;
        BluetoothAdapter adapter;
        synchronized (sLock) {
            if (sAdapter == null)
                sAdapter = new BluetoothAdapter();
            adapter = sAdapter;
            if (!sMonitoring) {
                sMonitoring = true;
                start = true;
            }
        }
        if (start)
            TlBluez.nativeStartMonitor(); // reports the adapter's state before it returns
        return adapter;
    }

    /** From TlBluez: BlueZ's adapter came, went or changed. */
    static void tlOnHostState(boolean present, boolean powered, String address, String name) {
        int previous, now;
        String oldName;
        synchronized (sLock) {
            previous = sState;
            oldName = sHostName;
            sPresent = present;
            sHostAddress = address;
            sHostName = name;
            now = present && powered ? STATE_ON : STATE_OFF;
            sState = now;
        }
        if (previous == STATE_OFF && now == STATE_ON) {
            broadcastState(STATE_TURNING_ON, STATE_OFF);
            broadcastState(STATE_ON, STATE_TURNING_ON);
        } else if (previous == STATE_ON && now == STATE_OFF) {
            broadcastState(STATE_TURNING_OFF, STATE_ON);
            /* AOSP: the scanners end with the Bluetooth service; apps scan again after STATE_ON */
            BluetoothLeScanner.tlOnAdapterOff();
            synchronized (sLock) {
                if (sAdapter != null) {
                    synchronized (sAdapter.mLeScanClients) {
                        sAdapter.mLeScanClients.clear();
                    }
                }
            }
            broadcastState(STATE_OFF, STATE_TURNING_OFF);
        } else if (previous == STATE_ON && now == STATE_ON && name != null && !name.equals(oldName)) {
            Intent intent = new Intent(ACTION_LOCAL_NAME_CHANGED);
            intent.putExtra(EXTRA_LOCAL_NAME, name);
            broadcast(intent);
        }
    }

    private static void broadcastState(int state, int previous) {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.putExtra(EXTRA_PREVIOUS_STATE, previous);
        intent.putExtra(EXTRA_STATE, state);
        broadcast(intent);
    }

    /** As the Bluetooth service sends them: to the app's receivers, from the main thread, for holders of the permission. */
    private static void broadcast(final Intent intent) {
        final String permission = TlBluez.usesModernPermissions() ? TlBluez.BLUETOOTH_CONNECT : TlBluez.BLUETOOTH;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Application app = ActivityThread.currentApplication();
                if (app != null)
                    app.sendBroadcast(intent, permission);
            }
        });
    }

    /** AOSP BluetoothAdapter.checkBluetoothAddress: "00:11:22:AA:BB:CC", upper case hex. */
    public static boolean checkBluetoothAddress(String address) {
        if (address == null || address.length() != ADDRESS_LENGTH) {
            return false;
        }
        for (int i = 0; i < ADDRESS_LENGTH; i++) {
            char c = address.charAt(i);
            switch (i % 3) {
                case 0:
                case 1:
                    if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) {
                        break;
                    }
                    return false;
                case 2:
                    if (c == ':') {
                        break;
                    }
                    return false;
            }
        }
        return true;
    }

    public BluetoothDevice getRemoteDevice(String address) {
        return new BluetoothDevice(address);
    }

    public BluetoothDevice getRemoteDevice(byte[] address) {
        if (address == null || address.length != 6) {
            throw new IllegalArgumentException("Bluetooth address must have 6 bytes");
        }
        return new BluetoothDevice(String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                address[0], address[1], address[2], address[3], address[4], address[5]));
    }

    /** AOSP (API 33): a device with an LE address type (BluetoothDevice.ADDRESS_TYPE_PUBLIC or _RANDOM). */
    public BluetoothDevice getRemoteLeDevice(String address, int addressType) {
        if (addressType != BluetoothDevice.ADDRESS_TYPE_PUBLIC && addressType != BluetoothDevice.ADDRESS_TYPE_RANDOM) {
            throw new IllegalArgumentException(addressType + " is not a Bluetooth LE address type");
        }
        return new BluetoothDevice(address, addressType);
    }

    public boolean isEnabled() {
        return getState() == STATE_ON;
    }

    /** AOSP: STATE_OFF, STATE_TURNING_ON, STATE_ON or STATE_TURNING_OFF (legacy BLUETOOTH before Android 12). */
    public int getState() {
        TlBluez.enforceLegacyOnly(TlBluez.BLUETOOTH, "getState");
        return tlState();
    }

    /** @hide the state without a permission check (the framework's own use) */
    public static int tlState() {
        synchronized (sLock) {
            return sState;
        }
    }

    /** AOSP: LE is usable whenever the adapter is on (there is no BLE-only state here). */
    public boolean isLeEnabled() {
        return tlState() == STATE_ON;
    }

    /** AOSP: the scanner while the adapter is on, else null (the same object each time). */
    public BluetoothLeScanner getBluetoothLeScanner() {
        if (tlState() != STATE_ON)
            return null;
        synchronized (this) {
            if (mLeScanner == null)
                mLeScanner = BluetoothLeScanner.tlCreate(this);
            return mLeScanner;
        }
    }

    /** AOSP: the bonded (paired) devices; an empty set while Bluetooth is off. */
    public Set<BluetoothDevice> getBondedDevices() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getBondedDevices");
        if (tlState() != STATE_ON)
            return Collections.emptySet();
        Set<BluetoothDevice> out = new HashSet<>();
        for (String address : TlBluez.nativeBondedDevices()) {
            if (checkBluetoothAddress(address))
                out.add(new BluetoothDevice(address));
        }
        return out;
    }

    /** AOSP: the adapter's friendly name (BlueZ's Alias), null while it is off. */
    public String getName() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getName");
        synchronized (sLock) {
            return sState == STATE_ON ? sHostName : null;
        }
    }

    /** AOSP: apps without LOCAL_MAC_ADDRESS (a privileged permission) see 02:00:00:00:00:00. */
    public String getAddress() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getAddress");
        if (TlBluez.isGranted("android.permission.LOCAL_MAC_ADDRESS")) {
            synchronized (sLock) {
                if (sHostAddress != null)
                    return sHostAddress;
            }
        }
        return DEFAULT_MAC_ADDRESS;
    }

    /**
     * AOSP: whether other devices can find or connect to this one. This runtime offers no classic
     * services, so from the apps' side the adapter is neither connectable nor discoverable.
     */
    public int getScanMode() {
        TlBluez.enforce(TlBluez.BLUETOOTH_SCAN, TlBluez.BLUETOOTH, "getScanMode");
        return SCAN_MODE_NONE;
    }

    /** AOSP: classic device discovery (see the class comment: not implemented, so never running). */
    public boolean isDiscovering() {
        TlBluez.enforce(TlBluez.BLUETOOTH_SCAN, TlBluez.BLUETOOTH, "isDiscovering");
        return false;
    }

    /**
     * AOSP: starts classic device discovery and returns false when it cannot. Classic discovery
     * (ACTION_FOUND broadcasts) is not implemented by this runtime, which answers as AOSP does
     * when the stack cannot start it.
     */
    public boolean startDiscovery() {
        TlBluez.enforce(TlBluez.BLUETOOTH_SCAN, TlBluez.BLUETOOTH_ADMIN, "startDiscovery");
        return false;
    }

    /** AOSP: false when no discovery could be cancelled (none runs, see startDiscovery). */
    public boolean cancelDiscovery() {
        TlBluez.enforce(TlBluez.BLUETOOTH_SCAN, TlBluez.BLUETOOTH_ADMIN, "cancelDiscovery");
        return false;
    }

    /**
     * AOSP: turns Bluetooth on. Apps targeting Android 13 or later always get false (they must
     * ask the user with ACTION_REQUEST_ENABLE); older ones need BLUETOOTH_CONNECT (BLUETOOTH_ADMIN
     * before Android 12). Here the request sets BlueZ's Powered, which the host's D-Bus policy may
     * refuse: then false.
     */
    @Deprecated
    public boolean enable() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH_ADMIN, "enable");
        if (TlBluez.targetSdk() >= 33) {
            Log.w(TAG, "enable(): apps targeting Android 13 or later cannot turn Bluetooth on");
            return false;
        }
        if (tlState() == STATE_ON)
            return true;
        synchronized (sLock) {
            if (!sPresent)
                return false;
        }
        return TlBluez.nativeSetPowered(true);
    }

    /** AOSP: turns Bluetooth off; the same rules as enable(). */
    @Deprecated
    public boolean disable() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH_ADMIN, "disable");
        if (TlBluez.targetSdk() >= 33) {
            Log.w(TAG, "disable(): apps targeting Android 13 or later cannot turn Bluetooth off");
            return false;
        }
        if (tlState() != STATE_ON)
            return true;
        return TlBluez.nativeSetPowered(false);
    }

    /** AOSP: no profile (headset, A2DP, ...) is connected: this runtime implements none. */
    public int getProfileConnectionState(int profile) {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getProfileConnectionState");
        return STATE_DISCONNECTED;
    }

    /** AOSP: false for a profile the stack does not run (no proxy is ever delivered); none runs here. */
    public boolean getProfileProxy(Context context, BluetoothProfile.ServiceListener listener, int profile) {
        return false;
    }

    /** AOSP: closing a proxy that was never handed out does nothing. */
    public void closeProfileProxy(int profile, BluetoothProfile proxy) {
    }

    /** There is no LE advertiser in this runtime. */
    public boolean isMultipleAdvertisementSupported() {
        return false;
    }

    /**
     * AOSP: whether scan filters work in the controller. The scanner here applies every filter to
     * every advertisement as the controller would (ScanFilter, CALLBACK_TYPE_FIRST_MATCH and
     * MATCH_LOST), so apps may use them: true while the adapter is on (AOSP answers false while off).
     */
    public boolean isOffloadedFilteringSupported() {
        return tlState() == STATE_ON;
    }

    /** AOSP: whether batched scan results (a ScanSettings report delay) work: they do, while on. */
    public boolean isOffloadedScanBatchingSupported() {
        return tlState() == STATE_ON;
    }

    /** The scanner reports legacy advertisements on the LE 1M PHY (BlueZ's discovery). */
    public boolean isLe2MPhySupported() {
        return false;
    }

    public boolean isLeCodedPhySupported() {
        return false;
    }

    public boolean isLeExtendedAdvertisingSupported() {
        return false;
    }

    public boolean isLePeriodicAdvertisingSupported() {
        return false;
    }

    /** AOSP: the legacy 31 bytes while on, 0 while off. */
    public int getLeMaximumAdvertisingDataLength() {
        return tlState() == STATE_ON ? LEGACY_ADVERTISING_DATA_LENGTH : 0;
    }

    // ---- the legacy LE scan API (AOSP's implementation over BluetoothLeScanner) ----

    /** AOSP: the callback of startLeScan. */
    public interface LeScanCallback {
        void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord);
    }

    @Deprecated
    public boolean startLeScan(LeScanCallback callback) {
        return startLeScan(null, callback);
    }

    /** AOSP: a low-latency scan of all matches, filtered by the advertised service UUIDs. */
    @Deprecated
    public boolean startLeScan(final UUID[] serviceUuids, final LeScanCallback callback) {
        if (callback == null) {
            return false;
        }
        BluetoothLeScanner scanner = getBluetoothLeScanner();
        if (scanner == null) {
            return false;
        }
        synchronized (mLeScanClients) {
            if (mLeScanClients.containsKey(callback)) {
                return false;
            }
            ScanCallback scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    if (callbackType != ScanSettings.CALLBACK_TYPE_ALL_MATCHES) {
                        return;
                    }
                    ScanRecord scanRecord = result.getScanRecord();
                    if (scanRecord == null) {
                        return;
                    }
                    if (serviceUuids != null) {
                        List<ParcelUuid> uuids = new ArrayList<ParcelUuid>();
                        for (UUID uuid : serviceUuids) {
                            uuids.add(new ParcelUuid(uuid));
                        }
                        List<ParcelUuid> scanServiceUuids = scanRecord.getServiceUuids();
                        if (scanServiceUuids == null || !scanServiceUuids.containsAll(uuids)) {
                            return;
                        }
                    }
                    callback.onLeScan(result.getDevice(), result.getRssi(), scanRecord.getBytes());
                }
            };
            ScanSettings settings = new ScanSettings.Builder()
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            List<ScanFilter> filters = new ArrayList<ScanFilter>();
            if (serviceUuids != null && serviceUuids.length > 0) {
                // Note scan filter does not support matching an UUID array so we put one
                // UUID to hardware and match the whole array in callback.
                ScanFilter filter = new ScanFilter.Builder().setServiceUuid(
                        new ParcelUuid(serviceUuids[0])).build();
                filters.add(filter);
            }
            scanner.startScan(filters, settings, scanCallback);
            mLeScanClients.put(callback, scanCallback);
            return true;
        }
    }

    @Deprecated
    public void stopLeScan(LeScanCallback callback) {
        BluetoothLeScanner scanner = getBluetoothLeScanner();
        if (scanner == null) {
            return;
        }
        synchronized (mLeScanClients) {
            ScanCallback scanCallback = mLeScanClients.remove(callback);
            if (scanCallback == null) {
                return;
            }
            scanner.stopScan(scanCallback);
        }
    }
}
