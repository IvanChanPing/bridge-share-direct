package com.bridge.share.trigger;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Cheap BLE "presence" channel — RECEIVER / PERIPHERAL side (see
 * docs/BATTERY_SPEC §1, §3). While the receiver is merely armed (Always-on /
 * Timed) this is the ONLY radio that runs: a LOW_POWER connectable advertise of
 * the presence {@link #SERVICE_UUID} carrying a short alias, plus a GATT server
 * exposing a single WRITABLE request-host characteristic ({@link #REQUEST_CHAR_UUID}).
 *
 * It is deliberately distinct from {@link CredsBleServer} (the post-trigger,
 * high-power creds advertiser). A sender's {@link PresenceScanner} discovers us
 * here; the sender's {@link CredsGattWriter} then WRITES the host's creds to the
 * request characteristic, which wakes us via {@code onCredsWritten}.
 */
public final class PresenceAdvertiser {

    private static final String TAG = "PresenceAdvertiser";

    // Per-flavor UUIDs: all three engine APKs can be installed side-by-side, so a
    // sender of one engine MUST NOT discover/trigger a receiver of another (a
    // Hotspot sender once handed "AndroidShare_*" creds to the Direct receiver,
    // which crashed in WifiP2pConfig.setNetworkName). Same-flavor ends still match.
    private static final String UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb";
    public static final UUID SERVICE_UUID;
    public static final UUID REQUEST_CHAR_UUID;
    /** Sender→receiver WAKE beacon: the sender advertises this when the send sheet opens;
     *  the receiver runs a system-held scan (startScan+PendingIntent) for it so the OS can
     *  wake/restart the receiver even if its process was killed. Per-flavor like the others. */
    public static final UUID BEACON_UUID;
    static {
        String svc, chr, beacon;
        switch (com.bridge.share.BuildConfig.ENGINE) {
            case "WIFI_AWARE": svc = "0000a2ee"; chr = "0000a2ef"; beacon = "0000a2eb"; break;
            case "HOTSPOT":    svc = "0000a3ee"; chr = "0000a3ef"; beacon = "0000a3eb"; break;
            default:           svc = "0000a1ee"; chr = "0000a1ef"; beacon = "0000a1eb"; break; // WIFI_DIRECT
        }
        SERVICE_UUID = UUID.fromString(svc + UUID_SUFFIX);
        REQUEST_CHAR_UUID = UUID.fromString(chr + UUID_SUFFIX);
        BEACON_UUID = UUID.fromString(beacon + UUID_SUFFIX);
    }

    /** Fired (any thread) when a peer writes bytes to the request characteristic. */
    public interface OnCredsWritten { void onCredsWritten(byte[] value); }

    private final Context appCtx;

    /** Presence service-data layout: byte[0] = flags, byte[1..] = short alias (UTF-8). */
    public static final byte FLAG_NFC_TAPPED = 0x01;

    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advCallback;
    private OnCredsWritten onCredsWritten;
    private volatile boolean running;
    private String alias;
    private volatile boolean nfcTapped;

    public PresenceAdvertiser(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public boolean isRunning() { return running; }

    /** BLE GATT-server + advertise need BLUETOOTH_CONNECT + BLUETOOTH_ADVERTISE on API 31+. */
    public static boolean hasBlePerms(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        }
        return true; // pre-31: BLUETOOTH/BLUETOOTH_ADMIN are normal (manifest) perms
    }

    public synchronized boolean start(String alias, OnCredsWritten cb) {
        return start(alias, false, cb);
    }

    @SuppressLint("MissingPermission")
    public synchronized boolean start(String alias, boolean nfcTapped, OnCredsWritten cb) {
        if (running) return true;
        this.onCredsWritten = cb;
        this.alias = alias;
        this.nfcTapped = nfcTapped;

        // Guard: missing runtime BLE perms must NOT crash the foreground service.
        if (!hasBlePerms(appCtx)) { Log.w(TAG, "BLE permissions not granted; presence advertise skipped"); return false; }

        try {
        BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null) { Log.w(TAG, "no BluetoothManager/adapter"); return false; }
        if (!bm.getAdapter().isEnabled()) { Log.w(TAG, "bluetooth disabled"); return false; }

        gattServer = bm.openGattServer(appCtx, new BluetoothGattServerCallback() {
            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                    BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                    boolean responseNeeded, int offset, byte[] value) {
                if (REQUEST_CHAR_UUID.equals(characteristic.getUuid())) {
                    Log.i(TAG, "request-host write from " + device.getAddress()
                            + " len=" + (value == null ? 0 : value.length));
                    if (responseNeeded) {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                    }
                    OnCredsWritten c = onCredsWritten;
                    if (c != null && value != null) c.onCredsWritten(value);
                } else if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                }
            }
            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                Log.i(TAG, "conn state device=" + device.getAddress()
                        + " status=" + status + " newState=" + newState);
            }
        });
        if (gattServer == null) { Log.w(TAG, "openGattServer returned null"); return false; }

        BluetoothGattService service =
                new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic chr = new BluetoothGattCharacteristic(
                REQUEST_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        service.addCharacteristic(chr);
        gattServer.addService(service);

        advertiser = bm.getAdapter().getBluetoothLeAdvertiser();
        if (advertiser == null) { Log.w(TAG, "no BluetoothLeAdvertiser (radio unsupported)"); return false; }

        // Long-lived presence advert. LOW_POWER advertise MODE keeps the duty cycle (and thus
        // battery) minimal, but TX power is HIGH so the sender reliably detects us across a
        // normal arm's-length gap (TX_POWER_LOW caused intermittent non-detection on-device;
        // TX power's energy cost is negligible next to the advertise interval).
        AdvertiseSettings settings = buildAdvertiseSettings();
        AdvertiseData data = buildAdvertiseData();

        advCallback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings s) { Log.i(TAG, "presence advertise onStartSuccess"); }
            @Override public void onStartFailure(int errorCode) { Log.w(TAG, "presence advertise onStartFailure code=" + errorCode); }
        };
        try {
            advertiser.startAdvertising(settings, data, advCallback);
            running = true;
            Log.i(TAG, "presence advertise dispatched (service " + SERVICE_UUID + ", alias=" + alias + ")");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startAdvertising threw: " + e);
            return false;
        }
        } catch (SecurityException se) {
            Log.w(TAG, "BLE SecurityException; presence not started", se);
            try { stop(); } catch (Exception ignored) {}
            return false;
        } catch (Exception e) {
            Log.w(TAG, "presence start failed: " + e);
            try { stop(); } catch (Exception ignored) {}
            return false;
        }
    }

    /** Service data = [flags][alias…]. The sender's scanner reads the flag to auto-pick an
     *  NFC-tapped receiver, and the alias to label it. */
    private AdvertiseData buildAdvertiseData() {
        AdvertiseData.Builder db = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID));
        byte[] a = (alias != null) ? alias.getBytes(StandardCharsets.UTF_8) : new byte[0];
        // Keep the PDU small — service data + a 16-bit UUID + flags must fit 31 bytes.
        if (a.length > 7) { byte[] t = new byte[7]; System.arraycopy(a, 0, t, 0, 7); a = t; }
        byte[] sd = new byte[1 + a.length];
        sd[0] = nfcTapped ? FLAG_NFC_TAPPED : 0x00;
        System.arraycopy(a, 0, sd, 1, a.length);
        db.addServiceData(new ParcelUuid(SERVICE_UUID), sd);
        return db.build();
    }

    /** Update the NFC-tapped flag and re-advertise so a scanning sender can auto-pick us. */
    @SuppressLint("MissingPermission")
    public synchronized void setNfcTapped(boolean tapped) {
        this.nfcTapped = tapped;
        if (!running || advertiser == null || advCallback == null) return;
        try {
            advertiser.stopAdvertising(advCallback);
            advertiser.startAdvertising(buildAdvertiseSettings(), buildAdvertiseData(), advCallback);
            Log.i(TAG, "re-advertised with nfcTapped=" + tapped);
        } catch (Exception e) {
            Log.w(TAG, "setNfcTapped re-advertise failed: " + e);
        }
    }

    private AdvertiseSettings buildAdvertiseSettings() {
        // NFC-tapped is a brief, active window → LOW_LATENCY so the sender discovers us FAST
        // (snappier NFC flow). Idle/armed → LOW_POWER for battery. TX power HIGH either way for
        // reliable range.
        int mode = nfcTapped
                ? AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                : AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
        return new AdvertiseSettings.Builder()
                .setAdvertiseMode(mode)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();
    }

    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        running = false;
        onCredsWritten = null;
        try { if (advertiser != null && advCallback != null) advertiser.stopAdvertising(advCallback); } catch (Exception ignored) {}
        try { if (gattServer != null) gattServer.close(); } catch (Exception ignored) {}
        advertiser = null;
        gattServer = null;
        Log.i(TAG, "stopped");
    }
}
