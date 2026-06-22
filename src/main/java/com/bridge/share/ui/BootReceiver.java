package com.bridge.share.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms the receive service after reboot, but ONLY if the mode is Always-on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (ReceivePrefs.getMode(ctx) == ReceivePrefs.Mode.ALWAYS_ON) {
            // Re-arm the persistent receive service so the device is discoverable after reboot.
            ReceiveService.start(ctx);
        }
    }
}
