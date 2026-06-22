package com.bridge.share.ui;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * A clearly-labelled DEMO controller for reviewing the send-sheet interaction
 * (scan → device icon appears → tap bounce → status → ring progress → complete)
 * WITHOUT a second phone or the real engine. It injects devices named "Demo …"
 * so it can never be mistaken for real discovery. Used only when the sheet is
 * launched directly for UI preview (no shared file).
 */
public final class PreviewSendController implements SendController {

    private final Handler h = new Handler(Looper.getMainLooper());
    private Listener listener;

    @Override
    public void startScan(Listener listener) {
        this.listener = listener;
        h.postDelayed(() -> listener.onPeerFound(new Peer("demo1", "Demo Pixel")), 900);
        h.postDelayed(() -> listener.onPeerFound(new Peer("demo2", "Demo Galaxy")), 1700);
    }

    @Override
    public void sendTo(Peer peer, List<Uri> uris) {
        if (listener == null) return;
        listener.onStatus(peer.id, "Connecting");
        h.postDelayed(() -> {
            listener.onStatus(peer.id, "Sending");
            simulateProgress(peer, 0);
        }, 700);
    }

    private void simulateProgress(Peer peer, int p) {
        if (listener == null) return;
        listener.onProgress(peer.id, p);
        if (p >= 100) { listener.onComplete(peer.id); return; }
        h.postDelayed(() -> simulateProgress(peer, p + 4), 60);
    }

    @Override
    public void stop() { h.removeCallbacksAndMessages(null); }
}
