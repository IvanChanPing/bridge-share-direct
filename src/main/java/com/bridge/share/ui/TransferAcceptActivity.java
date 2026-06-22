package com.bridge.share.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Invisible, transparent helper activity used ONLY on the overlay receive path.
 *
 * Android (and especially ColorOS/OneUI) blocks initiating a Wi-Fi-Direct
 * {@code WifiP2pManager.connect()} while the app is in the BACKGROUND — the
 * overlay card floats over another app, so the receiver process stays background
 * and the join fails with reason=0 (verified on the user's phones: the join only
 * fails in the background, never in the foreground).
 *
 * Tapping Accept on the overlay launches this activity, which brings the app to
 * the foreground for a few seconds; from {@code onResume} (now foreground) it runs
 * the real accept -> join, so {@code connect()} is dispatched with foreground
 * privilege. Once the P2P connection is initiated it persists even after this
 * activity finishes, so the activity self-finishes shortly after. The overlay card
 * (a separate window) stays on top showing progress the whole time.
 */
public final class TransferAcceptActivity extends Activity {

    /** Hand-off slot: the real receive controller whose accept() starts the join. */
    private static ReceiveController pendingAccept;
    /** The live instance, so the service can dismiss it the moment the transfer ends. */
    private static volatile TransferAcceptActivity instance;

    /** Launch the foreground hop and run {@code controller.accept()} once visible. */
    public static void launch(Context ctx, ReceiveController controller) {
        pendingAccept = controller;
        Intent i = new Intent(ctx.getApplicationContext(), TransferAcceptActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        ctx.getApplicationContext().startActivity(i);
    }

    /** Clear the foreground scrim — called by ReceiveService when the transfer ends. */
    public static void dismissNow() {
        TransferAcceptActivity a = instance;
        if (a != null) a.runOnUiThread(a::finish);
    }

    /** Safety net: finish even if the join never reports back, so we never sit foreground
     *  forever. Must outlast the connect retry window + group formation (connector retries
     *  ~6s; group can take ~15s). dismissNow() finishes us earlier on a successful join. */
    private static final long HOLD_MS = 30_000L;

    private boolean accepted = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        overridePendingTransition(0, 0);
        // INVISIBLE but FOREGROUND: this activity exists only to hold importance 100 so the
        // Wi-Fi-Direct connect() AND ITS RETRIES run with foreground privilege (background
        // connect fails reason=0 — verified: the receiver retried 4× at importance 125 after
        // the old code finished too early, all failing).
        // Keep NOT_TOUCHABLE so all touches pass through to the overlay above / app beneath
        // (never blocks taps), but DO NOT set NOT_FOCUSABLE: a translucent activity that stays
        // RESUMED for up to HOLD_MS with no focusable window leaves the display with NO FOCUSED
        // WINDOW — exactly the ANR the device logged ("Input dispatching timed out (Application
        // does not have a focused window)"), which freezes input until it fires. Being focusable
        // (but not touchable) gives the dispatcher a focus target so it can't time out, while the
        // higher-z overlay window still owns every tap on the card/pill.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accepted) return;
        accepted = true;
        ReceiveController c = pendingAccept;
        pendingAccept = null;
        if (c != null) {
            com.bridge.share.diag.DiagLog.d("TransferAccept",
                    "foreground hop -> accept(); holding importance 100 through connect + retries");
            c.accept(); // dispatches connect() while foreground (100)
        }
        // Stay foreground (invisible, pass-through) until the join lands or HOLD_MS, so the
        // connect retries keep importance 100. dismissNow() finishes us early on join.
        handler.postDelayed(() -> {
            com.bridge.share.diag.DiagLog.d("TransferAccept", "hold timeout -> finishing foreground hop");
            finish();
        }, HOLD_MS);
    }

    @Override
    public void onBackPressed() {
        // Ignore Back: finishing early would drop importance 100 and break the connect() retries
        // (verified: an early finish caused reason=0 ×4). dismissNow() (on join) / HOLD_MS end us.
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
