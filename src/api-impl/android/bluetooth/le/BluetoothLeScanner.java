/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.le;

import android.app.ActivityThread;
import android.app.Application;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.TlBluez;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Performs Bluetooth LE scan related operations (AOSP API).
 *
 * teslalinux: AOSP hands the scan to the Bluetooth service (GattService and ScanManager), which
 * programs the controller's filters. Here the scanner is a client of BlueZ (TlBluez): while a
 * scanner that is not opportunistic is registered this process runs one LE discovery session, and
 * every advertisement BlueZ reports is turned into a ScanResult and handed to each registered
 * scanner as GattService and the controller would:
 * <ul>
 * <li>the scanner's filters (any one of them matches; none means all);</li>
 * <li>CALLBACK_TYPE_ALL_MATCHES, with the report delay batching results (onBatchScanResults every
 * report delay, flushPendingScanResults at once); CALLBACK_TYPE_FIRST_MATCH and MATCH_LOST per
 * device, with ScanManager's sightings (1 aggressive, 4 sticky), its timeouts (1 s aggressive,
 * 3 s sticky without an advertisement) and the match count (one, few: 2, max);</li>
 * <li>AOSP's rules for apps: a callback already scanning fails with SCAN_FAILED_ALREADY_STARTED,
 * more than 5 starts in 30 s with SCAN_FAILED_SCANNING_TOO_FREQUENTLY, FIRST_MATCH or MATCH_LOST
 * without (non-empty) filters with SCAN_FAILED_FEATURE_UNSUPPORTED; a scan running for 30
 * minutes becomes opportunistic (unless it is one or uses FIRST_MATCH); results reach an app only
 * with location permission and location on, unless it declared BLUETOOTH_SCAN
 * neverForLocation, which hides location beacons (Eddystone, iBeacon) from it instead;</li>
 * <li>SCAN_MODE_OPPORTUNISTIC starts no discovery and sees the advertisements of other scans
 * (this app's, or any other BlueZ client's).</li>
 * </ul>
 * The scan modes all scan with BlueZ's discovery parameters (the kernel chooses the interval and
 * window; userspace cannot set them without CAP_NET_ADMIN). Callbacks run on the main thread, as
 * AOSP posts them to the main looper. When the adapter turns off, every scan ends without a
 * callback (AOSP: the Bluetooth service is gone; apps scan again after STATE_ON).
 */
public final class BluetoothLeScanner {
    private static final String TAG = "BluetoothLeScanner";

    /** AOSP: the PendingIntent extras of a scan started with a PendingIntent. */
    public static final String EXTRA_LIST_SCAN_RESULT = "android.bluetooth.le.extra.LIST_SCAN_RESULT";
    public static final String EXTRA_ERROR_CODE = "android.bluetooth.le.extra.ERROR_CODE";
    public static final String EXTRA_CALLBACK_TYPE = "android.bluetooth.le.extra.CALLBACK_TYPE";

    /* AOSP ScanManager / AppScanStats */
    private static final int ONFOUND_SIGHTINGS_AGGRESSIVE = 1;
    private static final int ONFOUND_SIGHTINGS_STICKY = 4;
    private static final int ONLOST_ONFOUND_BASE_TIMEOUT_MS = 500;
    private static final int MATCH_MODE_AGGRESSIVE_TIMEOUT_FACTOR = 1;
    private static final int MATCH_MODE_STICKY_TIMEOUT_FACTOR = 3;
    private static final int ONLOST_FACTOR = 2;
    private static final long SCAN_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int NUM_SCAN_DURATIONS_KEPT = 5;
    private static final long EXCESSIVE_SCANNING_PERIOD_MS = 30 * 1000L;

    private static final Object sLock = new Object();
    private static final List<Client> sClients = new ArrayList<>();
    private static final ArrayDeque<Long> sStartTimes = new ArrayDeque<>();
    private static BluetoothLeScanner sScanner;
    private static boolean sListening, sDiscovering, sDiscoveryConfirmed;
    private static String[] sHostUuids;
    private static LocationManager sLocationManager;

    private final BluetoothAdapter mBluetoothAdapter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private static final class Track {
        int sightings;
        boolean found;
        long lastSeen;
        ScanResult last;
    }

    private final class Client {
        final ScanCallback callback;
        final PendingIntent callbackIntent;
        final List<ScanFilter> filters;
        final ScanSettings settings;
        final boolean locationPermitted;
        final boolean disavowsLocation;
        boolean opportunistic;
        boolean confirmed;
        boolean ended;
        final List<ScanResult> batch = new ArrayList<>();
        final Map<String, Track> tracks = new HashMap<>();
        int foundCount;

        Client(ScanCallback callback, PendingIntent callbackIntent, List<ScanFilter> filters, ScanSettings settings,
               boolean locationPermitted, boolean disavowsLocation) {
            this.callback = callback;
            this.callbackIntent = callbackIntent;
            this.filters = filters == null ? null : new ArrayList<>(filters);
            this.settings = settings;
            this.locationPermitted = locationPermitted;
            this.disavowsLocation = disavowsLocation;
            this.opportunistic = settings.getScanMode() == ScanSettings.SCAN_MODE_OPPORTUNISTIC;
            this.confirmed = opportunistic; // an opportunistic scan starts nothing that could fail
        }

        /* the report delay: the batch so far, then again after the delay */
        final Runnable flushBatch = new Runnable() {
            @Override
            public void run() {
                List<ScanResult> results;
                synchronized (sLock) {
                    if (ended)
                        return;
                    results = new ArrayList<>(batch);
                    batch.clear();
                    mHandler.postDelayed(this, settings.getReportDelayMillis());
                }
                if (!results.isEmpty())
                    deliverBatch(Client.this, results);
            }
        };

        /* AOSP ScanManager.regularScanTimeout */
        final Runnable scanTimeout = new Runnable() {
            @Override
            public void run() {
                synchronized (sLock) {
                    if (ended || opportunistic)
                        return;
                    Log.w(TAG, "Moving scan client to opportunistic (it scanned for 30 minutes)");
                    opportunistic = true;
                    updateHostLocked();
                }
            }
        };

        /* devices that sent nothing for the lost timeout: MATCH_LOST */
        final Runnable lostCheck = new Runnable() {
            @Override
            public void run() {
                List<ScanResult> lost = new ArrayList<>();
                long timeout = lostTimeoutMillis(settings);
                synchronized (sLock) {
                    if (ended)
                        return;
                    long now = SystemClock.elapsedRealtime();
                    long next = Long.MAX_VALUE;
                    for (Iterator<Track> it = tracks.values().iterator(); it.hasNext();) {
                        Track t = it.next();
                        if (now - t.lastSeen >= timeout) {
                            if (t.found) {
                                foundCount--;
                                lost.add(t.last);
                            }
                            it.remove();
                        } else {
                            next = Math.min(next, t.lastSeen + timeout - now);
                        }
                    }
                    if (next != Long.MAX_VALUE)
                        mHandler.postDelayed(this, next);
                }
                if ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {
                    for (ScanResult r : lost)
                        deliver(Client.this, ScanSettings.CALLBACK_TYPE_MATCH_LOST, r);
                }
            }
        };
    }

    private BluetoothLeScanner(BluetoothAdapter adapter) {
        mBluetoothAdapter = adapter;
    }

    /** @hide the adapter's scanner (BluetoothAdapter.getBluetoothLeScanner) */
    public static BluetoothLeScanner tlCreate(BluetoothAdapter adapter) {
        synchronized (sLock) {
            if (sScanner == null)
                sScanner = new BluetoothLeScanner(adapter);
            return sScanner;
        }
    }

    /** Start Bluetooth LE scan with default parameters and no filters. */
    public void startScan(final ScanCallback callback) {
        startScan(null, new ScanSettings.Builder().build(), callback);
    }

    /** Start Bluetooth LE scan. */
    public void startScan(List<ScanFilter> filters, ScanSettings settings, final ScanCallback callback) {
        startScan(filters, settings, callback, null);
    }

    /**
     * Start Bluetooth LE scan using a PendingIntent: results arrive as EXTRA_LIST_SCAN_RESULT with
     * EXTRA_CALLBACK_TYPE, errors as EXTRA_ERROR_CODE. Returns 0 on success or an error code.
     */
    public int startScan(List<ScanFilter> filters, ScanSettings settings, final PendingIntent callbackIntent) {
        return startScan(filters, settings != null ? settings : new ScanSettings.Builder().build(), null, callbackIntent);
    }

    private int startScan(List<ScanFilter> filters, ScanSettings settings, final ScanCallback callback,
                          final PendingIntent callbackIntent) {
        BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
        if (callback == null && callbackIntent == null) {
            throw new IllegalArgumentException("callback is null");
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings is null");
        }
        TlBluez.enforce(TlBluez.BLUETOOTH_SCAN, "android.permission.BLUETOOTH_ADMIN", "startScan");
        boolean locationPermitted = hasLocationPermission();
        boolean disavowsLocation = TlBluez.scanDisavowsLocation();
        synchronized (sLock) {
            if (callback != null && find(callback) != null) {
                return postCallbackErrorOrReturn(callback, ScanCallback.SCAN_FAILED_ALREADY_STARTED);
            }
            if (!isSettingsAndFilterComboAllowed(settings, filters)) {
                return postCallbackErrorOrReturn(callback, ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
            }
            long now = SystemClock.elapsedRealtime();
            if (sStartTimes.size() >= NUM_SCAN_DURATIONS_KEPT
                    && now - sStartTimes.peekFirst() < EXCESSIVE_SCANNING_PERIOD_MS) {
                Log.e(TAG, "App is scanning too frequently");
                return postCallbackErrorOrReturn(callback, ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY);
            }
            sStartTimes.addLast(now);
            while (sStartTimes.size() > NUM_SCAN_DURATIONS_KEPT)
                sStartTimes.removeFirst();
            Client c = new Client(callback, callbackIntent, filters, settings, locationPermitted, disavowsLocation);
            sClients.add(c);
            if (settings.getReportDelayMillis() > 0)
                mHandler.postDelayed(c.flushBatch, settings.getReportDelayMillis());
            if (!c.opportunistic && (settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) == 0)
                mHandler.postDelayed(c.scanTimeout, SCAN_TIMEOUT_MS);
            updateHostLocked();
        }
        return 0;
    }

    /** Stops an ongoing Bluetooth LE scan. */
    public void stopScan(ScanCallback callback) {
        BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
        TlBluez.enforce(TlBluez.BLUETOOTH_SCAN, "android.permission.BLUETOOTH_ADMIN", "stopScan");
        synchronized (sLock) {
            Client c = find(callback);
            if (c == null) {
                return;
            }
            endLocked(c);
            updateHostLocked();
        }
    }

    /** Stops an ongoing Bluetooth LE scan started using a PendingIntent. */
    public void stopScan(PendingIntent callbackIntent) {
        BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
        TlBluez.enforce(TlBluez.BLUETOOTH_SCAN, "android.permission.BLUETOOTH_ADMIN", "stopScan");
        synchronized (sLock) {
            for (Client c : new ArrayList<>(sClients)) {
                if (c.callbackIntent != null && c.callbackIntent.equals(callbackIntent))
                    endLocked(c);
            }
            updateHostLocked();
        }
    }

    /** Flush pending batch scan results (here: the batch collected since the last report). */
    public void flushPendingScanResults(ScanCallback callback) {
        BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null!");
        }
        List<ScanResult> results;
        Client c;
        synchronized (sLock) {
            c = find(callback);
            if (c == null)
                return;
            results = new ArrayList<>(c.batch);
            c.batch.clear();
        }
        deliverBatch(c, results);
    }

    // ---- from TlBluez ----

    /** @hide the answer to this process's discovery start */
    public static void tlOnDiscoveryResult(boolean ok, String error) {
        List<Client> failed = new ArrayList<>();
        synchronized (sLock) {
            sDiscoveryConfirmed = ok && sDiscovering;
            for (Client c : new ArrayList<>(sClients)) {
                if (c.confirmed || c.opportunistic)
                    continue;
                if (ok) {
                    c.confirmed = true;
                } else {
                    failed.add(c);
                    endLocked(c);
                }
            }
            if (!ok) {
                Log.w(TAG, "LE scan could not start: " + error);
                updateHostLocked();
            }
        }
        for (Client c : failed)
            deliverError(c, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
    }

    /** @hide the adapter turned off: every scan ends */
    public static void tlOnAdapterOff() {
        synchronized (sLock) {
            for (Client c : new ArrayList<>(sClients))
                endLocked(c);
            updateHostLocked();
        }
    }

    /** @hide one advertisement (see TlBluez.tlOnAdvertisement) */
    public static void tlOnAdvertisement(String address, int addressType, int rssi, byte[] record, long timestampNanos) {
        List<Client> clients;
        BluetoothLeScanner scanner;
        synchronized (sLock) {
            if (sClients.isEmpty() || sScanner == null)
                return;
            clients = new ArrayList<>(sClients);
            scanner = sScanner;
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address))
            return;
        ScanRecord scanRecord = ScanRecord.parseFromBytes(record);
        BluetoothDevice device = scanner.mBluetoothAdapter.getRemoteLeDevice(address,
                addressType == 1 ? BluetoothDevice.ADDRESS_TYPE_RANDOM : BluetoothDevice.ADDRESS_TYPE_PUBLIC);
        /* a legacy advertisement on the LE 1M PHY; BlueZ does not tell whether it was connectable or
         * a scan response, so it is reported as AOSP's legacy ScanResult constructor reports one */
        int eventType = (ScanResult.DATA_COMPLETE << 5) | (1 << 4) /* ET_LEGACY_MASK */ | 0x01 /* ET_CONNECTABLE_MASK */;
        ScanResult result = new ScanResult(device, eventType, BluetoothDevice.PHY_LE_1M, ScanResult.PHY_UNUSED,
                ScanResult.SID_NOT_PRESENT, ScanResult.TX_POWER_NOT_PRESENT, rssi,
                ScanResult.PERIODIC_INTERVAL_NOT_PRESENT, scanRecord, timestampNanos);
        boolean locationOn = isLocationEnabled();
        for (Client c : clients)
            scanner.onResult(c, result, locationOn);
    }

    private void onResult(Client c, ScanResult result, boolean locationOn) {
        if (c.disavowsLocation) {
            if (isLocationBeacon(result.getScanRecord()))
                return;
        } else if (!c.locationPermitted || !locationOn) {
            return;
        }
        if (!matchesFilters(c.filters, result))
            return;
        int callbackType = c.settings.getCallbackType();
        boolean deliverNow = false, firstMatch = false;
        synchronized (sLock) {
            if (c.ended)
                return;
            if ((callbackType & ScanSettings.CALLBACK_TYPE_ALL_MATCHES) != 0) {
                if (c.settings.getReportDelayMillis() > 0)
                    c.batch.add(result);
                else
                    deliverNow = true;
            }
            if ((callbackType & (ScanSettings.CALLBACK_TYPE_FIRST_MATCH | ScanSettings.CALLBACK_TYPE_MATCH_LOST)) != 0) {
                String key = result.getDevice().getAddress();
                Track t = c.tracks.get(key);
                if (t == null) {
                    t = new Track();
                    c.tracks.put(key, t);
                    if (c.tracks.size() == 1)
                        mHandler.postDelayed(c.lostCheck, lostTimeoutMillis(c.settings));
                }
                t.sightings++;
                t.lastSeen = SystemClock.elapsedRealtime();
                t.last = result;
                if (!t.found && t.sightings >= sightingsNeeded(c.settings) && c.foundCount < maxMatches(c.settings)) {
                    t.found = true;
                    c.foundCount++;
                    firstMatch = (callbackType & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0;
                }
            }
        }
        if (deliverNow)
            deliver(c, ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
        if (firstMatch)
            deliver(c, ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result);
    }

    // ---- delivery ----

    private void deliver(final Client c, final int callbackType, final ScanResult result) {
        if (c.callback != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    c.callback.onScanResult(callbackType, result);
                }
            });
        } else {
            ArrayList<ScanResult> list = new ArrayList<>();
            list.add(result);
            Intent extras = new Intent();
            extras.putParcelableArrayListExtra(EXTRA_LIST_SCAN_RESULT, list);
            extras.putExtra(EXTRA_CALLBACK_TYPE, callbackType);
            sendIntent(c.callbackIntent, extras);
        }
    }

    private void deliverBatch(final Client c, final List<ScanResult> results) {
        if (c.callback != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    c.callback.onBatchScanResults(results);
                }
            });
        } else {
            Intent extras = new Intent();
            extras.putParcelableArrayListExtra(EXTRA_LIST_SCAN_RESULT, new ArrayList<>(results));
            extras.putExtra(EXTRA_CALLBACK_TYPE, ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
            sendIntent(c.callbackIntent, extras);
        }
    }

    private static void deliverError(final Client c, final int errorCode) {
        if (c.callback != null) {
            sScanner.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    c.callback.onScanFailed(errorCode);
                }
            });
        } else {
            Intent extras = new Intent();
            extras.putExtra(EXTRA_ERROR_CODE, errorCode);
            sendIntent(c.callbackIntent, extras);
        }
    }

    private static void sendIntent(PendingIntent pi, Intent extras) {
        try {
            pi.send(ActivityThread.currentApplication(), 0, extras);
        } catch (RuntimeException e) {
            Log.w(TAG, "scan result PendingIntent failed", e);
        }
    }

    private int postCallbackErrorOrReturn(final ScanCallback callback, final int errorCode) {
        if (callback == null) {
            return errorCode;
        } else {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onScanFailed(errorCode);
                }
            });
            return 0;
        }
    }

    // ---- helpers ----

    private static Client find(ScanCallback callback) {
        for (Client c : sClients) {
            if (c.callback == callback)
                return c;
        }
        return null;
    }

    private static void endLocked(Client c) {
        c.ended = true;
        sClients.remove(c);
        if (sScanner != null) {
            sScanner.mHandler.removeCallbacks(c.flushBatch);
            sScanner.mHandler.removeCallbacks(c.scanTimeout);
            sScanner.mHandler.removeCallbacks(c.lostCheck);
        }
    }

    /**
     * The service UUIDs BlueZ may filter the session on: when every scan that starts discovery has
     * filters and each filter asks for a service UUID (without a mask), the union of them (a device
     * matching none of them matches no scan); otherwise null (no UUID filter).
     */
    private static String[] hostUuidFilterLocked() {
        java.util.TreeSet<String> uuids = new java.util.TreeSet<>();
        for (Client c : sClients) {
            if (c.opportunistic)
                continue;
            if (c.filters == null || c.filters.isEmpty())
                return null;
            for (ScanFilter f : c.filters) {
                if (f.getServiceUuid() == null || f.getServiceUuidMask() != null)
                    return null;
                uuids.add(f.getServiceUuid().toString().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return uuids.isEmpty() ? null : uuids.toArray(new String[0]);
    }

    /** what the host must do: listen while any scanner is registered, discover while one is not opportunistic */
    private static void updateHostLocked() {
        boolean listen = !sClients.isEmpty(), discover = false;
        for (Client c : sClients) {
            if (!c.opportunistic)
                discover = true;
        }
        String[] uuids = discover ? hostUuidFilterLocked() : null;
        if (listen != sListening) {
            sListening = listen;
            TlBluez.nativeSetListening(listen);
        }
        if (discover != sDiscovering) {
            sDiscovering = discover;
            sDiscoveryConfirmed = false;
            sHostUuids = uuids;
            TlBluez.nativeSetDiscovery(discover, uuids);
        } else if (discover && !java.util.Arrays.equals(uuids, sHostUuids)) {
            sHostUuids = uuids;
            TlBluez.nativeSetDiscovery(true, uuids); // the same session, another UUID filter
        }
        if (discover && sDiscoveryConfirmed) {
            /* the session runs already: newcomers scan with it */
            for (Client c : sClients)
                c.confirmed = true;
        }
    }

    /** AOSP: FIRST_MATCH and MATCH_LOST need filters, none of them empty. */
    private static boolean isSettingsAndFilterComboAllowed(ScanSettings settings, List<ScanFilter> filterList) {
        final int callbackType = settings.getCallbackType();
        if ((callbackType & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0
                || (callbackType & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {
            if (filterList == null) {
                return false;
            }
            for (ScanFilter filter : filterList) {
                if (filter.isAllFieldsEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** AOSP GattService: no filters match everything, else any one filter. */
    private static boolean matchesFilters(List<ScanFilter> filters, ScanResult result) {
        if (filters == null || filters.isEmpty())
            return true;
        for (ScanFilter f : filters) {
            if (f.matches(result))
                return true;
        }
        return false;
    }

    private static int sightingsNeeded(ScanSettings s) {
        return s.getMatchMode() == ScanSettings.MATCH_MODE_AGGRESSIVE ? ONFOUND_SIGHTINGS_AGGRESSIVE : ONFOUND_SIGHTINGS_STICKY;
    }

    /** AOSP ScanManager.getOnFoundOnLostTimeoutMillis(settings, false) */
    private static long lostTimeoutMillis(ScanSettings s) {
        int factor = s.getMatchMode() == ScanSettings.MATCH_MODE_AGGRESSIVE ? MATCH_MODE_AGGRESSIVE_TIMEOUT_FACTOR
                                                                             : MATCH_MODE_STICKY_TIMEOUT_FACTOR;
        return (long) ONLOST_ONFOUND_BASE_TIMEOUT_MS * factor * ONLOST_FACTOR;
    }

    private static int maxMatches(ScanSettings s) {
        switch (s.getNumOfMatches()) {
            case ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT:
                return 1;
            case ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT:
                return 2;
            default:
                return Integer.MAX_VALUE;
        }
    }

    /**
     * AOSP (Utils.checkCallerHasFineLocation / checkCallerHasCoarseOrFineLocation): apps targeting
     * Android 10 or later need ACCESS_FINE_LOCATION for scan results, older ones either location
     * permission.
     */
    private static boolean hasLocationPermission() {
        if (TlBluez.isGranted("android.permission.ACCESS_FINE_LOCATION"))
            return true;
        return TlBluez.targetSdk() < 29 && TlBluez.isGranted("android.permission.ACCESS_COARSE_LOCATION");
    }

    private static boolean isLocationEnabled() {
        LocationManager lm;
        synchronized (sLock) {
            if (sLocationManager == null) {
                Application app = ActivityThread.currentApplication();
                if (app != null)
                    sLocationManager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            }
            lm = sLocationManager;
        }
        return lm != null && lm.isLocationEnabled();
    }

    private static final ParcelUuid EDDYSTONE = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB");
    private static final int APPLE = 0x004C;

    /**
     * AOSP's location deny list for apps that disavowed location (the default of DeviceConfig
     * bluetooth "location_denylist_advertising_data": Eddystone service data, Apple iBeacon
     * manufacturer data).
     */
    private static boolean isLocationBeacon(ScanRecord record) {
        if (record == null)
            return false;
        if (record.getServiceData(EDDYSTONE) != null)
            return true;
        byte[] apple = record.getManufacturerSpecificData(APPLE);
        return apple != null && apple.length > 0 && apple[0] == 0x02;
    }
}
