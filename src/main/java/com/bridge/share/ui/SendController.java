package com.bridge.share.ui;

import android.net.Uri;

import java.util.List;

/**
 * UI-facing seam for the send flow. The bottom sheet talks ONLY to this; the
 * concrete implementation (BLE presence scan + chosen connection variant +
 * HTTP host) is wired to the engine per docs/ENGINE_SPEC.md. Keeping this an
 * interface lets the three connection variants drop in without touching the UI.
 */
public interface SendController {

    /** A discovered nearby receiver. id is stable for the session; name is shown under the icon. */
    final class Peer {
        public final String id;
        public final String name;
        /** The receiver signalled (over BLE presence) that it was NFC-tapped → auto-pick it. */
        public final boolean tapped;
        public Peer(String id, String name) { this(id, name, false); }
        public Peer(String id, String name, boolean tapped) {
            this.id = id; this.name = name; this.tapped = tapped;
        }
    }

    interface Listener {
        void onPeerFound(Peer peer);
        void onPeerLost(String peerId);
        /** Status text under the tapped icon: "Connecting", "Sending", etc. */
        void onStatus(String peerId, String status);
        void onProgress(String peerId, int percent);
        void onComplete(String peerId);
        void onError(String peerId, String message);
    }

    /** Begin BLE scanning for receivers; results stream to the listener. */
    void startScan(Listener listener);

    /** Send the given content to the chosen peer (host + creds + transfer). */
    void sendTo(Peer peer, List<Uri> uris);

    /** Stop scanning / cancel any in-flight send and release radios. */
    void stop();
}
