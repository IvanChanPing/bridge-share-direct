package com.bridge.share.trigger;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.util.Log;

/**
 * Sender-side helper that wakes a receiver by WRITING the host's creds bytes to
 * the receiver's presence request characteristic (see docs/BATTERY_SPEC §2,
 * trigger via BLE GATT write to {@link PresenceAdvertiser#REQUEST_CHAR_UUID}).
 *
 * Flow: connectGatt(address) -> discoverServices ->
 * write creds to {@link PresenceAdvertiser#REQUEST_CHAR_UUID} on
 * {@link PresenceAdvertiser#SERVICE_UUID} -> close. The receiver's GATT server
 * decodes the bytes back into {@link Creds} and joins the host on-demand.
 */
public final class CredsGattWriter {

    private static final String TAG = "CredsGattWriter";

    public interface Callback {
        void onWritten();
        void onError(String msg);
    }

    private final Context appCtx;
    private BluetoothGatt gatt;
    private byte[] payload;
    private Callback callback;
    private volatile boolean done;

    public CredsGattWriter(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    /** Connect to {@code address} and write {@code creds} to the request characteristic. */
    @SuppressLint("MissingPermission")
    public synchronized boolean write(String address, byte[] creds, Callback cb) {
        this.payload = creds;
        this.callback = cb;
        this.done = false;

        // Guard: missing BLUETOOTH_CONNECT must not crash (connectGatt would throw SecurityException).
        if (!PresenceAdvertiser.hasBlePerms(appCtx)) { err("BLE permissions not granted"); return false; }

        BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) {
            err("bluetooth unavailable/disabled");
            return false;
        }
        BluetoothDevice dev;
        try {
            dev = bm.getAdapter().getRemoteDevice(address);
        } catch (Exception e) {
            err("bad device address " + address + ": " + e);
            return false;
        }
        gatt = dev.connectGatt(appCtx, false, new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    // Default ATT MTU (23) caps a write at ~20 bytes; the creds JSON is
                    // ~140 bytes and would be TRUNCATED (verified on real phones: receiver
                    // saw len=18 -> null creds). Negotiate a large MTU FIRST so the whole
                    // payload fits in one Write Request, then discover + write.
                    // Drop to a fast connection interval first: the default interval makes
                    // the MTU exchange + write take ~1.7s on these phones. HIGH priority
                    // shrinks the interval so the trigger lands much faster.
                    try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); }
                    catch (Exception ignored) {}
                    Log.i(TAG, "connected to " + address + ", high priority + requesting MTU 517");
                    boolean req = g.requestMtu(517);
                    if (!req) {
                        Log.w(TAG, "requestMtu returned false; discovering with default MTU");
                        g.discoverServices();
                    }
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.i(TAG, "disconnected from " + address);
                }
            }
            @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                Log.i(TAG, "onMtuChanged mtu=" + mtu + " status=" + status + "; discovering services");
                g.discoverServices();
            }
            @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                BluetoothGattService svc = g.getService(PresenceAdvertiser.SERVICE_UUID);
                if (svc == null) { err("presence service not found after discovery"); return; }
                BluetoothGattCharacteristic chr =
                        svc.getCharacteristic(PresenceAdvertiser.REQUEST_CHAR_UUID);
                if (chr == null) { err("request characteristic not found"); return; }
                chr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                chr.setValue(payload != null ? payload : new byte[0]);
                boolean ok = g.writeCharacteristic(chr);
                Log.i(TAG, "writeCharacteristic dispatched=" + ok
                        + " len=" + (payload == null ? 0 : payload.length));
                if (!ok) err("writeCharacteristic returned false");
            }
            @Override public void onCharacteristicWrite(BluetoothGatt g,
                    BluetoothGattCharacteristic chr, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "creds write success");
                    deliverWritten();
                } else {
                    err("creds write status=" + status);
                }
            }
        });
        if (gatt == null) { err("connectGatt returned null"); return false; }
        return true;
    }

    private synchronized void deliverWritten() {
        if (done) return;
        done = true;
        Callback c = callback;
        if (c != null) c.onWritten();
        close();
    }

    private synchronized void err(String msg) {
        if (done) return;
        done = true;
        Log.w(TAG, msg);
        Callback c = callback;
        if (c != null) c.onError(msg);
        close();
    }

    @SuppressLint("MissingPermission")
    public synchronized void close() {
        try { if (gatt != null) gatt.close(); } catch (Exception ignored) {}
        gatt = null;
    }
}
