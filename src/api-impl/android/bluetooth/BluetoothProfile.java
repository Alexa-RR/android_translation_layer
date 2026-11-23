/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.util.List;

/**
 * Public APIs for the Bluetooth Profiles (AOSP API).
 *
 * <p> Clients should call {@link BluetoothAdapter#getProfileProxy}, to get the Profile Proxy. Each
 * public profile implements this interface.
 *
 * teslalinux: this runtime implements no profile, so BluetoothAdapter.getProfileProxy returns
 * false and no proxy implementing this interface is ever handed out.
 */
public interface BluetoothProfile {

    /** Extra for the connection state intents of the individual profiles. */
    String EXTRA_STATE = "android.bluetooth.profile.extra.STATE";

    /** Extra for the connection state intents of the individual profiles. */
    String EXTRA_PREVIOUS_STATE = "android.bluetooth.profile.extra.PREVIOUS_STATE";

    /** The profile is in disconnected state */
    int STATE_DISCONNECTED = 0;
    /** The profile is in connecting state */
    int STATE_CONNECTING = 1;
    /** The profile is in connected state */
    int STATE_CONNECTED = 2;
    /** The profile is in disconnecting state */
    int STATE_DISCONNECTING = 3;

    /** Headset and Handsfree profile */
    int HEADSET = 1;
    /** A2DP profile. */
    int A2DP = 2;
    /** Health Profile */
    @Deprecated
    int HEALTH = 3;
    /** HID Host */
    int HID_HOST = 4;
    /** PAN Profile */
    int PAN = 5;
    /** PBAP */
    int PBAP = 6;
    /** GATT */
    int GATT = 7;
    /** GATT_SERVER */
    int GATT_SERVER = 8;
    /** MAP Profile */
    int MAP = 9;
    /** SAP Profile */
    int SAP = 10;
    /** A2DP Sink Profile */
    int A2DP_SINK = 11;
    /** AVRCP Controller Profile */
    int AVRCP_CONTROLLER = 12;
    /** HID Device */
    int HID_DEVICE = 19;
    /** Hearing Aid Device */
    int HEARING_AID = 21;
    /** LE Audio Device */
    int LE_AUDIO = 22;
    /** Coordinated Set Identification Profile set coordinator */
    int CSIP_SET_COORDINATOR = 25;
    /** LE Audio Broadcast Source */
    int LE_AUDIO_BROADCAST = 26;
    /** Hearing Access Profile */
    int HAP_CLIENT = 28;
    /** LE Audio Broadcast Assistant */
    int LE_AUDIO_BROADCAST_ASSISTANT = 29;

    /** Default priority for devices that we try to auto-connect to and allow incoming connections. */
    @Deprecated
    int PRIORITY_ON = 100;
    /** Default priority for devices that allow incoming and outgoing connections. */
    @Deprecated
    int PRIORITY_AUTO_CONNECT = 1000;
    /** Default priority for devices that does not allow incoming connections. */
    @Deprecated
    int PRIORITY_OFF = 0;
    /** Default priority when not set or when the device is unpaired */
    @Deprecated
    int PRIORITY_UNDEFINED = -1;

    /** Get connected devices for this specific profile. */
    List<BluetoothDevice> getConnectedDevices();

    /** Get a list of devices that match any of the given connection states. */
    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states);

    /** Get the current connection state of the profile */
    int getConnectionState(BluetoothDevice device);

    /** An interface for notifying BluetoothProfile IPC clients when they have been connected or disconnected to the service. */
    interface ServiceListener {
        /** Called to notify the client when the proxy object has been connected to the service. */
        void onServiceConnected(int profile, BluetoothProfile proxy);

        /** Called to notify the client that this proxy object has been disconnected from the service. */
        void onServiceDisconnected(int profile);
    }
}
