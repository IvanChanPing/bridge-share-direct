package com.bridge.share.ui;

import android.os.Handler;
import android.os.Looper;

/**
 * Clearly-labelled DEMO controller to review the receive accept→progress→complete
 * flow without a real sender. Raises a "Demo Pixel" incoming transfer; on accept
 * it animates progress to completion.
 */
public final class PreviewReceiveController implements ReceiveController {

    private final Handler h = new Handler(Looper.getMainLooper());
    private Ui ui;

    @Override
    public void bind(Ui ui) {
        this.ui = ui;
        h.postDelayed(() -> ui.onIncoming(
                new IncomingTransfer("Demo Pixel", 1, 2_400_000L, "photo.jpg")), 150);
    }

    /** DEMO: no real peer, so skip the importance-100 foreground hop entirely (otherwise the
     *  invisible 30s TransferAcceptActivity lingers and freezes the screen after the preview). */
    @Override
    public boolean needsForegroundHop() { return false; }

    @Override
    public void accept() {
        step(0);
    }

    private void step(int p) {
        if (ui == null) return;
        ui.onProgress(p);
        if (p >= 100) { ui.onComplete(); return; }
        h.postDelayed(() -> step(p + 4), 60);
    }

    @Override
    public void decline() { if (ui != null) ui.onCanceled(); h.removeCallbacksAndMessages(null); }

    @Override
    public void cancel() { if (ui != null) ui.onCanceled(); h.removeCallbacksAndMessages(null); }
}
