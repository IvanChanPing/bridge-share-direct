package com.bridge.share.trigger;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Battery-efficient, OS-held wake mechanism (the closest a non-privileged app gets to the
 * way Quick Share / OnePlus Share stay reachable in the background):
 *
 *  - SENDER: {@link #startAdvertise} advertises the per-flavor {@link PresenceAdvertiser#BEACON_UUID}
 *    while the send sheet is open (sender is foreground, advertising is fine).
 *  - RECEIVER: {@link #registerWakeScan} registers a SYSTEM-HELD BLE scan
 *    ({@code startScan(filters, settings, PendingIntent)}) for that beacon. The system holds
 *    the low-power scan and fires the PendingIntent (-> {@code BeaconWakeReceiver}) when a
 *    sender beacons, even if the receiver's process was killed — reviving it to advertise
 *    presence + GATT. This is ADDITIVE to the existing continuous presence advertise, so
 *    today's flow is unchanged; this only adds resilience to being killed. If the OEM blocks
 *    the wake-start it degrades gracefully (no regression).
 */
public final class WakeBeacon {

    private static final String TAG = "WakeBeacon";
    public static final String ACTION_WAKE = "com.bridge.share.action.WAKE";

    private WakeBeacon() {}

    // ---- SENDER side: advertise the wake beacon ----
    private static BluetoothLeAdvertiser sAdvertiser;
    private static AdvertiseCallback sAdvCb;

    @SuppressLint("MissingPermission")
    public static synchronized void startAdvertise(Context ctx) {
        if (sAdvCb != null) return; // already advertising
        if (!PresenceAdvertiser.hasBlePerms(ctx)) { Log.w(TAG, "no BLE perms; beacon skipped"); return; }
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) return;
            sAdvertiser = bm.getAdapter().getBluetoothLeAdvertiser();
            if (sAdvertiser == null) return;
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(PresenceAdvertiser.BEACON_UUID))
                    .build();
            sAdvCb = new AdvertiseCallback() {
                @Override public void onStartSuccess(AdvertiseSettings s) { Log.i(TAG, "wake beacon advertising"); }
                @Override public void onStartFailure(int code) { Log.w(TAG, "beacon advertise fail code=" + code); }
            };
            sAdvertiser.startAdvertising(settings, data, sAdvCb);
        } catch (Throwable t) { Log.w(TAG, "startAdvertise threw: " + t); }
    }

    @SuppressLint("MissingPermission")
    public static synchronized void stopAdvertise() {
        try { if (sAdvertiser != null && sAdvCb != null) sAdvertiser.stopAdvertising(sAdvCb); } catch (Exception ignored) {}
        sAdvertiser = null; sAdvCb = null;
    }

    // ---- RECEIVER side: system-held scan for the beacon ----
    @SuppressLint("MissingPermission")
    public static void registerWakeScan(Context ctx) {
        if (!PresenceAdvertiser.hasBlePerms(ctx)) { Log.w(TAG, "no BLE perms; wake scan skipped"); return; }
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null || bm.getAdapter() == null) return;
            BluetoothLeScanner scanner = bm.getAdapter().getBluetoothLeScanner();
            if (scanner == null) return;
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(PresenceAdvertiser.BEACON_UUID)).build());
            ScanSettings.Builder sb = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER);
            // Battery: we only need to WAKE on the first sighting of a beacon, not a stream
            // of results — FIRST_MATCH + a batch report delay minimises wakeups for this
            // always-on system-held scan (per Android BLE battery guidance).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sb.setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH);
                sb.setReportDelay(0); // PendingIntent scans deliver per-match; FIRST_MATCH limits churn
            }
            int rc = scanner.startScan(filters, sb.build(), wakePendingIntent(ctx));
            com.bridge.share.diag.DiagLog.d(TAG, "registered system-held wake scan for "
                    + PresenceAdvertiser.BEACON_UUID + " startScanResult=" + rc + " (0=success)");
        } catch (Throwable t) {
            com.bridge.share.diag.DiagLog.d(TAG, "registerWakeScan threw: " + t);
        }
    }

    @SuppressLint("MissingPermission")
    public static void unregisterWakeScan(Context ctx) {
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null || bm.getAdapter() == null) return;
            BluetoothLeScanner scanner = bm.getAdapter().getBluetoothLeScanner();
            if (scanner != null) scanner.stopScan(wakePendingIntent(ctx));
        } catch (Throwable ignored) {}
    }

    private static PendingIntent wakePendingIntent(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(),
                com.bridge.share.ui.BeaconWakeReceiver.class).setAction(ACTION_WAKE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // The system fills in scan results, so the PendingIntent must be MUTABLE (API 31+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(ctx.getApplicationContext(), 0, i, flags);
    }
}
