package com.bridge.share.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.bridge.share.BridgeApp;
import com.bridge.share.diag.DiagLog;

/**
 * Fired by the system when a sender's wake beacon is seen by the receiver's system-held
 * BLE scan (see {@link com.bridge.share.trigger.WakeBeacon}). Revives the receive service
 * so it advertises presence + GATT — even if the process had been killed.
 *
 * Instrumented to answer the two open questions for the OS-only model:
 *   1. Does a KILLED receiver actually get woken? -> we log the ms since process start;
 *      a tiny delta means this broadcast spawned a fresh process (the killed receiver was
 *      revived by the OS-held scan).
 *   2. Is the foreground-service start from this background broadcast allowed on ColorOS
 *      12+? -> ReceiveService.wake uses startForegroundService, which THROWS
 *      ForegroundServiceStartNotAllowedException when disallowed; we log the exact outcome.
 * Uses goAsync() + a synchronous DiagLog flush so the lines reach the collector even if the
 * process is torn down right after (e.g. when the FGS start is blocked).
 */
public class BeaconWakeReceiver extends BroadcastReceiver {
    private static final String TAG = "BeaconWakeReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ReceivePrefs.getMode(ctx) == ReceivePrefs.Mode.OFF) {
            Log.i(TAG, "wake beacon seen but mode=OFF; ignoring");
            return;
        }
        final PendingResult pr = goAsync();
        try {
            DiagLog.init(ctx);
            long sinceStart = SystemClock.elapsedRealtime() - BridgeApp.PROCESS_START_ELAPSED;
            DiagLog.d(TAG, "WAKE beacon seen; msSinceProcessStart=" + sinceStart
                    + " (small => process was freshly spawned = killed receiver revived)"
                    + " mode=" + ReceivePrefs.getMode(ctx) + " sdk=" + android.os.Build.VERSION.SDK_INT);

            boolean fgStartOk;
            String fgErr = null;
            try {
                ReceiveService.wake(ctx);
                fgStartOk = true;
            } catch (Throwable t) {
                fgStartOk = false;
                fgErr = t.getClass().getName() + ": " + t.getMessage();
            }
            DiagLog.d(TAG, "WAKE fgStartOk=" + fgStartOk + (fgErr != null ? " err=" + fgErr : ""));
        } finally {
            // Flush + finish on a background thread (network must not run on the main thread).
            new Thread(() -> {
                try { DiagLog.flushBlocking(); } catch (Throwable ignored) {}
                try { pr.finish(); } catch (Throwable ignored) {}
            }, "WakeFlush").start();
        }
    }
}
