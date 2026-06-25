package com.bridge.share.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/**
 * Transparent, throwaway activity that handles an NFC tap WITHOUT pulling the user into the
 * full app. The sender's HCE NDEF carries an Android Application Record, so a tap launches us
 * here (NDEF_DISCOVERED). We play the edge-glow over the user's current screen (this window is
 * translucent), turn the radios on, kick off the normal BLE receive, and finish — so the
 * receive Accept prompt shows over whatever they were doing (overlay) rather than the settings
 * UI. taskAffinity="" + excludeFromRecents + noHistory keep it out of the task/recents and
 * collapse duplicate launches (avoids the "two instances" seen when MainActivity handled it).
 *
 * RADIOS-ON-TAP (2026-06-25): this is the FASTEST point to get the radios up. The moment a tap
 * lands we ask the universal radio-helper ({@link com.bridge.share.radio.BridgeRadioCoordinator
 * #acquireReceiveForNfc}) to SILENTLY enable BOTH Wi-Fi and Bluetooth (whichever are off) and
 * immediately start the receive — no "system Bluetooth dialog → tap again" detour. This makes
 * the transfer start much quicker when one/both radios were off. The helper restores the user's
 * original radio state when the receive window ends (with a heartbeat keep-alive so a long
 * transfer is never cut and a crash restores ~20 s later). If the helper isn't installed we
 * fall back to {@link #ensureBluetoothOn()} (the legacy system BT-enable dialog) so the tap
 * still works without the helper.
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

        startNfcReceive();
        // Finish after the glow so we never linger over the user's screen.
        new android.os.Handler(getMainLooper()).postDelayed(this::finish, FINISH_DELAY_MS);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // A second tap while still up: replay the glow + re-arm; singleTask keeps one instance.
        setIntent(intent);
        try { NfcTapFx.play(this); } catch (Throwable ignored) {}
        startNfcReceive();
    }

    /**
     * On an NFC tap: silently turn ON both Wi-Fi + Bluetooth via the radio-helper and start the
     * receive IMMEDIATELY. Single funnel for BOTH entry points (onCreate + onNewIntent) so the
     * tap behaviour never diverges between first tap and re-tap.
     */
    private void startNfcReceive() {
        // Kick the silent BOTH-radio enable the instant the tap lands (async; starts heartbeat).
        boolean helperWillEnable =
                com.bridge.share.radio.BridgeRadioCoordinator.acquireReceiveForNfc(this);
        if (helperWillEnable) {
            // Helper present → radios are coming up silently in parallel; proceed now. No dialog,
            // no second tap. (If BT lags the BLE arm, ReceiveService's BtStateReceiver re-arms
            // when BT actually comes on — so we never miss the handshake.)
            com.bridge.share.diag.DiagLog.d("NfcLaunch",
                    "radio-helper present -> silent Wi-Fi+BT enable; starting receive immediately");
            ReceiveService.nfcReceive(this);
        } else {
            // No helper installed → legacy fallback: ensure BT (may prompt), start receive when on.
            com.bridge.share.diag.DiagLog.d("NfcLaunch",
                    "radio-helper absent -> legacy BT-enable fallback");
            if (ensureBluetoothOn()) ReceiveService.nfcReceive(this);
        }
    }

    /** True if Bluetooth is already on; otherwise pop the system "turn on Bluetooth" dialog.
     *  Used ONLY as the fallback when the radio-helper isn't installed (see {@link #startNfcReceive}). */
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
