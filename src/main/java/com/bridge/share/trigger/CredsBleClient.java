package com.bridge.share.trigger;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * BLE creds channel — JOINER / CENTRAL side. Lifted from oshare-port
 * CredsBleClient. Scans for {@link CredsBleServer#SERVICE_UUID}, connects to the
 * first match, reads the creds characteristic, decodes {@link Creds}.
 */
public final class CredsBleClient {

    private static final String TAG = "CredsBleClient";

    public interface Callback {
        void onCreds(Creds creds);
        void onError(String msg);
        void onLog(String msg);
    }

    private final Context appCtx;
    private final Callback callback;

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private volatile boolean done;

    public CredsBleClient(Context ctx, Callback callback) {
        this.appCtx = ctx.getApplicationContext();
        this.callback = callback;
    }

    @SuppressLint("MissingPermission")
    public synchronized boolean start() {
        BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) {
            err("bluetooth unavailable/disabled");
            return false;
        }
        scanner = bm.getAdapter().getBluetoothLeScanner();
        if (scanner == null) { err("no BLE scanner"); return false; }

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(CredsBleServer.SERVICE_UUID)).build());
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                if (done) return;
                BluetoothDevice dev = result.getDevice();
                log("found peripheral " + dev.getAddress() + ", connecting");
                stopScan();
                connect(dev);
            }
            @Override public void onScanFailed(int errorCode) { err("scan failed code=" + errorCode); }
        };
        try {
            scanner.startScan(filters, settings, scanCallback);
            log("scanning for service " + CredsBleServer.SERVICE_UUID);
            return true;
        } catch (Exception e) {
            err("startScan threw: " + e);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice dev) {
        gatt = dev.connectGatt(appCtx, false, new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    log("connected, discovering services");
                    g.discoverServices();
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    log("disconnected");
                }
            }
            @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                BluetoothGattService svc = g.getService(CredsBleServer.SERVICE_UUID);
                if (svc == null) { err("creds service not found after discovery"); return; }
                BluetoothGattCharacteristic chr = svc.getCharacteristic(CredsBleServer.CREDS_CHAR_UUID);
                if (chr == null) { err("creds characteristic not found"); return; }
                g.requestMtu(512);
            }
            @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                log("mtu=" + mtu + ", reading creds characteristic");
                BluetoothGattService svc = g.getService(CredsBleServer.SERVICE_UUID);
                if (svc != null) {
                    BluetoothGattCharacteristic chr = svc.getCharacteristic(CredsBleServer.CREDS_CHAR_UUID);
                    if (chr != null) g.readCharacteristic(chr);
                }
            }
            @Override public void onCharacteristicRead(BluetoothGatt g,
                    BluetoothGattCharacteristic chr, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) { err("characteristic read status=" + status); return; }
                byte[] value = chr.getValue();
                if (value == null || value.length == 0) { err("empty creds value"); return; }
                try {
                    Creds creds = Creds.fromBytes(value);
                    log("creds received: ssid=" + creds.ssid + " ip=" + creds.hostIp);
                    deliver(creds);
                } catch (Exception e) {
                    err("creds parse failed: " + e);
                }
            }
        });
    }

    private synchronized void deliver(Creds creds) {
        if (done) return;
        done = true;
        if (callback != null) callback.onCreds(creds);
        stop();
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        try { if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        stopScan();
        try { if (gatt != null) gatt.close(); } catch (Exception ignored) {}
        gatt = null;
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (callback != null) callback.onLog(msg);
    }

    private void err(String msg) {
        Log.w(TAG, msg);
        if (callback != null) callback.onError(msg);
    }
}
