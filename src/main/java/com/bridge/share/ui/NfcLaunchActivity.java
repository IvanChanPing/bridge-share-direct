package com.bridge.share.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/**
 * Transparent, throwaway activity that handles an NFC tap WITHOUT pulling the user into the
 * full app. The sender's HCE NDEF carries an Android Application Record, so a tap launches us
 * here (NDEF_DISCOVERED). We play the edge-glow over the user's current screen (this window is
 * translucent), make sure Bluetooth is on, kick off the normal BLE receive, and finish — so
 * the receive Accept prompt shows over whatever they were doing (overlay) rather than the
 * settings UI. taskAffinity="" + excludeFromRecents + noHistory keep it out of the task/recents
 * and collapse duplicate launches (avoids the "two instances" seen when MainActivity handled it).
 */
public class NfcLaunchActivity extends Activity {

    /** Keep the translucent window up long enough for the glow to play, then finish. */
    private static final long FINISH_DELAY_MS = 2600L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.bridge.share.diag.DiagLog.init(this);
        // A content view for NfcTapFx to attach its glow overlay to.
        setContentView(new FrameLayout(this));

        com.bridge.share.diag.DiagLog.d("NfcLaunch", "NFC tap -> launch; action="
                + (getIntent() != null ? getIntent().getAction() : null));

        try { NfcTapFx.play(this); } catch (Throwable ignored) {}

        if (ensureBluetoothOn()) {
            ReceiveService.nfcReceive(this);
        }
        // Finish after the glow so we never linger over the user's screen.
        new android.os.Handler(getMainLooper()).postDelayed(this::finish, FINISH_DELAY_MS);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // A second tap while still up: replay the glow + re-arm; singleTask keeps one instance.
        setIntent(intent);
        try { NfcTapFx.play(this); } catch (Throwable ignored) {}
        if (ensureBluetoothOn()) ReceiveService.nfcReceive(this);
    }

    /** True if Bluetooth is already on; otherwise pop the system "turn on Bluetooth" dialog. */
    private boolean ensureBluetoothOn() {
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            if (ad == null) return true;            // no BT hardware; nothing to enable
            if (ad.isEnabled()) return true;
            com.bridge.share.diag.DiagLog.d("NfcLaunch", "Bluetooth off -> requesting enable");
            startActivity(new android.content.Intent(
                    android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return false;                            // user enables BT, then taps again
        } catch (Throwable t) {
            com.bridge.share.diag.DiagLog.d("NfcLaunch", "ensureBluetoothOn failed: " + t);
            return true;
        }
    }
}
