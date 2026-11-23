/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a remote Bluetooth device (AOSP API).
 *
 * teslalinux: AOSP asks the Bluetooth stack (through the adapter service) for everything but the
 * address; here the stack is BlueZ (TlBluez.nativeDescribeDevice: what BlueZ knows of the device
 * from its advertisements, inquiry results and pairing). While Bluetooth is off, or for a device
 * BlueZ never saw, the queries answer as AOSP does when the adapter service has nothing: no name,
 * no class, no UUIDs, BOND_NONE, DEVICE_TYPE_UNKNOWN. Bonding and connections (createBond, GATT,
 * sockets) are not implemented by this runtime and report that they could not start (false).
 */
public final class BluetoothDevice implements Parcelable {
    public static final int ERROR = Integer.MIN_VALUE;

    public static final String ACTION_FOUND = "android.bluetooth.device.action.FOUND";
    public static final String ACTION_CLASS_CHANGED = "android.bluetooth.device.action.CLASS_CHANGED";
    public static final String ACTION_ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED";
    public static final String ACTION_ACL_DISCONNECT_REQUESTED = "android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED";
    public static final String ACTION_ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED";
    public static final String ACTION_NAME_CHANGED = "android.bluetooth.device.action.NAME_CHANGED";
    public static final String ACTION_BOND_STATE_CHANGED = "android.bluetooth.device.action.BOND_STATE_CHANGED";
    public static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    public static final String EXTRA_NAME = "android.bluetooth.device.extra.NAME";
    public static final String EXTRA_RSSI = "android.bluetooth.device.extra.RSSI";
    public static final String EXTRA_CLASS = "android.bluetooth.device.extra.CLASS";
    public static final String EXTRA_BOND_STATE = "android.bluetooth.device.extra.BOND_STATE";
    public static final String EXTRA_PREVIOUS_BOND_STATE = "android.bluetooth.device.extra.PREVIOUS_BOND_STATE";

    public static final int BOND_NONE = 10;
    public static final int BOND_BONDING = 11;
    public static final int BOND_BONDED = 12;

    public static final int DEVICE_TYPE_UNKNOWN = 0;
    public static final int DEVICE_TYPE_CLASSIC = 1;
    public static final int DEVICE_TYPE_LE = 2;
    public static final int DEVICE_TYPE_DUAL = 3;

    public static final int TRANSPORT_AUTO = 0;
    public static final int TRANSPORT_BREDR = 1;
    public static final int TRANSPORT_LE = 2;

    public static final int PHY_LE_1M = 1;
    public static final int PHY_LE_2M = 2;
    public static final int PHY_LE_CODED = 3;
    public static final int PHY_LE_1M_MASK = 1;
    public static final int PHY_LE_2M_MASK = 2;
    public static final int PHY_LE_CODED_MASK = 4;
    public static final int PHY_OPTION_NO_PREFERRED = 0;
    public static final int PHY_OPTION_S2 = 1;
    public static final int PHY_OPTION_S8 = 2;

    /** AOSP (API 33): the LE address types. */
    public static final int ADDRESS_TYPE_PUBLIC = 0;
    public static final int ADDRESS_TYPE_RANDOM = 1;
    public static final int ADDRESS_TYPE_UNKNOWN = 0xFFFF;

    private final String mAddress;
    private final int mAddressType;

    /** @hide AOSP: only the adapter creates devices, after checking the address. */
    BluetoothDevice(String address) {
        this(address, ADDRESS_TYPE_PUBLIC);
    }

    /** @hide */
    BluetoothDevice(String address, int addressType) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IllegalArgumentException(address + " is not a valid Bluetooth address");
        }
        mAddress = address;
        mAddressType = addressType;
    }

    public String getAddress() {
        return mAddress;
    }

    /** AOSP (API 35): the address type the device was created with. */
    public int getAddressType() {
        return mAddressType;
    }

    /** What BlueZ knows of this device ("key=value" lines), empty while Bluetooth is off. */
    private List<String[]> describe() {
        List<String[]> out = new ArrayList<>();
        if (BluetoothAdapter.tlState() != BluetoothAdapter.STATE_ON)
            return out;
        for (String line : TlBluez.nativeDescribeDevice(mAddress).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0)
                out.add(new String[] {line.substring(0, eq), line.substring(eq + 1)});
        }
        return out;
    }

    private String fact(String key) {
        for (String[] kv : describe()) {
            if (kv[0].equals(key))
                return kv[1];
        }
        return null;
    }

    /** AOSP: the remote device's name, null when the stack does not know it. */
    public String getName() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getName");
        return fact("name");
    }

    /** AOSP: the alias (user-set name), the name when none is set; BlueZ's Alias defaults to the name. */
    public String getAlias() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getAlias");
        return fact("name");
    }

    /** AOSP: classic, LE, dual or unknown, from what the stack has seen of the device. */
    public int getType() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getType");
        boolean le = false, bredr = false;
        for (String[] kv : describe()) {
            if (kv[0].equals("le"))
                le = true;
            else if (kv[0].equals("bredr"))
                bredr = true;
        }
        return le && bredr ? DEVICE_TYPE_DUAL : le ? DEVICE_TYPE_LE : bredr ? DEVICE_TYPE_CLASSIC : DEVICE_TYPE_UNKNOWN;
    }

    /** AOSP: the Class of Device from inquiry, null when none is known (LE-only devices). */
    public BluetoothClass getBluetoothClass() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getBluetoothClass");
        String cls = fact("class");
        if (cls == null)
            return null;
        try {
            return new BluetoothClass((int) Long.parseLong(cls));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** AOSP: BOND_BONDED for a paired device (BlueZ's Bonded, or Paired), else BOND_NONE. */
    public int getBondState() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getBondState");
        for (String[] kv : describe()) {
            if ((kv[0].equals("bonded") || kv[0].equals("paired")) && kv[1].equals("1"))
                return BOND_BONDED;
        }
        return BOND_NONE;
    }

    /** AOSP: pairing is not implemented by this runtime; false, as when the stack cannot start it. */
    public boolean createBond() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH_ADMIN, "createBond");
        return false;
    }

    /** AOSP: the service UUIDs the stack knows for the device, null when none. */
    public ParcelUuid[] getUuids() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "getUuids");
        List<ParcelUuid> uuids = new ArrayList<>();
        for (String[] kv : describe()) {
            if (kv[0].equals("uuid")) {
                try {
                    uuids.add(ParcelUuid.fromString(kv[1]));
                } catch (IllegalArgumentException e) {
                    // not a UUID
                }
            }
        }
        return uuids.isEmpty() ? null : uuids.toArray(new ParcelUuid[0]);
    }

    /** AOSP: an SDP query is not implemented by this runtime (no classic connections); false. */
    public boolean fetchUuidsWithSdp() {
        TlBluez.enforce(TlBluez.BLUETOOTH_CONNECT, TlBluez.BLUETOOTH, "fetchUuidsWithSdp");
        return false;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BluetoothDevice && mAddress.equals(((BluetoothDevice) o).getAddress());
    }

    @Override
    public int hashCode() {
        return mAddress.hashCode();
    }

    @Override
    public String toString() {
        return mAddress;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mAddress);
        out.writeInt(mAddressType);
    }

    public static final Parcelable.Creator<BluetoothDevice> CREATOR = new Parcelable.Creator<BluetoothDevice>() {
        public BluetoothDevice createFromParcel(Parcel in) {
            return new BluetoothDevice(in.readString(), in.readInt());
        }

        public BluetoothDevice[] newArray(int size) {
            return new BluetoothDevice[size];
        }
    };
}
