package com.bridge.share.trigger;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cheap BLE "presence" channel — SENDER / CENTRAL side (see docs/BATTERY_SPEC §3).
 * Runs ONLY while the share sheet is open. Scans (LOW_LATENCY) for receivers
 * advertising {@link PresenceAdvertiser#SERVICE_UUID}, extracts the short alias
 * from the advert's service data, and reports each new device address once
 * (deduped by address). It does NOT connect — the caller decides when to stop the
 * scan and, on user pick, hands the address to {@link CredsGattWriter}.
 */
public final class PresenceScanner {

    private static final String TAG = "PresenceScanner";

    /** Reported once per newly-seen receiver. tapped = the receiver was NFC-tapped (auto-pick). */
    public interface Callback { void onFound(String deviceAddress, String alias, boolean tapped); }

    private final Context appCtx;
    private final Set<String> seen = new HashSet<>();

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private Callback callback;
    private volatile boolean running;

    public PresenceScanner(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public boolean isRunning() { return running; }

    @SuppressLint("MissingPermission")
    public synchronized boolean start(Callback cb) {
        if (running) return true;
        this.callback = cb;
        this.seen.clear();

        BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) {
            Log.w(TAG, "bluetooth unavailable/disabled");
            return false;
        }
        scanner = bm.getAdapter().getBluetoothLeScanner();
        if (scanner == null) { Log.w(TAG, "no BLE scanner"); return false; }

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(PresenceAdvertiser.SERVICE_UUID)).build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                handle(result);
            }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                if (results != null) for (ScanResult r : results) handle(r);
            }
            @Override public void onScanFailed(int errorCode) { Log.w(TAG, "scan failed code=" + errorCode); }
        };
        try {
            scanner.startScan(filters, settings, scanCallback);
            running = true;
            Log.i(TAG, "scanning for presence service " + PresenceAdvertiser.SERVICE_UUID);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startScan threw: " + e);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void handle(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        String addr = result.getDevice().getAddress();
        boolean tapped = false;
        String alias = addr;
        ScanRecord rec = result.getScanRecord();
        if (rec != null) {
            byte[] sd = rec.getServiceData(new ParcelUuid(PresenceAdvertiser.SERVICE_UUID));
            if (sd != null && sd.length > 0) {
                // Layout: byte[0]=flags, byte[1..]=alias.
                tapped = (sd[0] & PresenceAdvertiser.FLAG_NFC_TAPPED) != 0;
                if (sd.length > 1) alias = new String(sd, 1, sd.length - 1, StandardCharsets.UTF_8);
            } else if (rec.getDeviceName() != null && !rec.getDeviceName().isEmpty()) {
                alias = rec.getDeviceName();
            }
        }
        // Dedupe by address — but a device that becomes tapped AFTER first being seen must be
        // reported again so the sender can auto-pick it.
        synchronized (seen) {
            String key = addr + (tapped ? "#t" : "");
            if (!seen.add(key)) return;
        }
        Log.i(TAG, "presence found " + addr + " alias=" + alias + " tapped=" + tapped);
        Callback c = callback;
        if (c != null) c.onFound(addr, alias, tapped);
    }

    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        running = false;
        callback = null;
        try { if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        scanner = null;
        scanCallback = null;
        Log.i(TAG, "stopped");
    }
}
