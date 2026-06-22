package com.bridge.share.ui;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * Picks the receive accept-prompt presentation: the {@link OverlayReceiver}
 * (TYPE_APPLICATION_OVERLAY) when draw-over-apps permission is granted, otherwise
 * the {@link ReceiveBottomSheetActivity}. The accept gate is shown either way.
 */
public final class ReceiveUi {

    private ReceiveUi() {}

    /** Hand-off slot: the real controller the bottom-sheet Activity should adopt. */
    private static ReceiveController pending;

    public static boolean overlayEnabled(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /** Show the incoming-transfer prompt driven by {@code controller}. */
    public static void show(Context ctx, ReceiveController controller) {
        // PROVEN: Wi-Fi-Direct connect() needs a foreground ACTIVITY (importance 100) at
        // the moment of Accept; a foreground service (125) is refused. AFTER the connect
        // is established the transfer continues fine in the background.
        //  - Overlay enabled: show the floating card over the current app; its Accept
        //    launches a brief invisible foreground hop (TransferAcceptActivity) just to
        //    run connect() at importance 100, then dismisses on join -> background.
        //  - No overlay: the foreground bottom-sheet activity IS the prompt (importance
        //    100 already), which adopts this controller.
        if (overlayEnabled(ctx) && OverlayReceiver.show(ctx, controller)) return;
        pending = controller; // consumed by the bottom-sheet activity
        startSheet(ctx);
    }

    /** Consumed once by {@link ReceiveBottomSheetActivity}; null on a preview launch. */
    static ReceiveController consumePending() {
        ReceiveController c = pending;
        pending = null;
        return c;
    }

    /** Preview entry: overlay path uses a DEMO controller; bottom sheet self-previews. */
    public static void preview(Context ctx) {
        if (overlayEnabled(ctx) && OverlayReceiver.show(ctx, new PreviewReceiveController())) return;
        startSheet(ctx);
    }

    private static void startSheet(Context ctx) {
        Intent i = new Intent(ctx, ReceiveBottomSheetActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
