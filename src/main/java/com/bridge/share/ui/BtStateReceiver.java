package com.bridge.share.ui;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Runtime-registered receiver for Bluetooth on/off. ACTION_STATE_CHANGED is NOT
 * in the manifest implicit-broadcast exemption list, so this must be registered
 * dynamically (done by {@link ReceiveService}, which Always-on keeps alive).
 *
 * When BT turns ON and the mode is Always-on, re-arm the receiver (BLE advertise
 * needs BT). When BT turns OFF, BLE can't run — left to the service to idle.
 */
public class BtStateReceiver extends BroadcastReceiver {

    private static final String TAG = "BtStateReceiver";

    public interface Callback { void onBluetoothOn(); void onBluetoothOff(); }
    private final Callback callback;

    public BtStateReceiver(Callback callback) { this.callback = callback; }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
        if (state == BluetoothAdapter.STATE_ON) {
            Log.i(TAG, "bluetooth ON");
            if (ReceivePrefs.getMode(ctx) == ReceivePrefs.Mode.ALWAYS_ON && callback != null) {
                callback.onBluetoothOn();
            }
        } else if (state == BluetoothAdapter.STATE_OFF) {
            Log.i(TAG, "bluetooth OFF");
            if (callback != null) callback.onBluetoothOff();
        }
    }
}
