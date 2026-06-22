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
import android.os.ParcelUuid;
import android.util.Log;

import java.util.UUID;

/**
 * BLE creds channel — HOST / PERIPHERAL side. Lifted from oshare-port
 * CredsBleServer (raw android.bluetooth.le, zero OEM API). Advertises a custom
 * service UUID and exposes one readable characteristic carrying the {@link Creds}
 * JSON; a joiner (central) scans, connects and reads it.
 */
public final class CredsBleServer {

    private static final String TAG = "CredsBleServer";

    public static final UUID SERVICE_UUID =
            UUID.fromString("0000a1ec-0000-1000-8000-00805f9b34fb");
    public static final UUID CREDS_CHAR_UUID =
            UUID.fromString("0000a1ed-0000-1000-8000-00805f9b34fb");

    public interface Listener { void onLog(String msg); }

    private final Context appCtx;
    private final Listener listener;

    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advCallback;
    private byte[] credsBytes;
    private volatile boolean running;

    public CredsBleServer(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() { return running; }

    @SuppressLint("MissingPermission")
    public synchronized boolean start(Creds creds) {
        if (running) return true;
        this.credsBytes = creds.toBytes();

        // Guard: missing BLUETOOTH_CONNECT/ADVERTISE must not crash (openGattServer would throw).
        if (!PresenceAdvertiser.hasBlePerms(appCtx)) { log("BLE permissions not granted; creds advertise skipped"); return false; }

        BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null) { log("no BluetoothManager/adapter"); return false; }
        if (!bm.getAdapter().isEnabled()) { log("bluetooth disabled"); return false; }

        gattServer = bm.openGattServer(appCtx, new BluetoothGattServerCallback() {
            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                    int offset, BluetoothGattCharacteristic characteristic) {
                if (!CREDS_CHAR_UUID.equals(characteristic.getUuid())) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                    return;
                }
                byte[] value = credsBytes;
                if (offset > value.length) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                    return;
                }
                int len = value.length - offset;
                byte[] chunk = new byte[len];
                System.arraycopy(value, offset, chunk, 0, len);
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk);
                log("served creds read to " + device.getAddress() + " offset=" + offset + " len=" + len);
            }
            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                log("conn state device=" + device.getAddress() + " status=" + status + " newState=" + newState);
            }
        });
        if (gattServer == null) { log("openGattServer returned null"); return false; }

        BluetoothGattService service =
                new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic chr = new BluetoothGattCharacteristic(
                CREDS_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        chr.setValue(credsBytes);
        service.addCharacteristic(chr);
        gattServer.addService(service);

        advertiser = bm.getAdapter().getBluetoothLeAdvertiser();
        if (advertiser == null) { log("no BluetoothLeAdvertiser (radio unsupported)"); return false; }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        advCallback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) { log("advertise onStartSuccess"); }
            @Override public void onStartFailure(int errorCode) { log("advertise onStartFailure code=" + errorCode); }
        };
        try {
            advertiser.startAdvertising(settings, data, advCallback);
            running = true;
            log("startAdvertising dispatched (service " + SERVICE_UUID + ")");
            return true;
        } catch (Exception e) {
            log("startAdvertising threw: " + e);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        running = false;
        try { if (advertiser != null && advCallback != null) advertiser.stopAdvertising(advCallback); } catch (Exception ignored) {}
        try { if (gattServer != null) gattServer.close(); } catch (Exception ignored) {}
        advertiser = null;
        gattServer = null;
        log("stopped");
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (listener != null) listener.onLog(msg);
    }
}
