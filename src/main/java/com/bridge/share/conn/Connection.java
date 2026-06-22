package com.bridge.share.conn;

import android.content.Context;

/**
 * One pluggable link-layer (see docs/ENGINE_SPEC.md). Implemented by
 * WifiDirectConnection, WifiAwareConnection, HotspotConnection. The engine talks
 * ONLY to this; it never references WifiP2pManager / WifiAwareManager /
 * WifiManager directly.
 *
 * Lifecycle (host):   startHost(body) -> onCredsReady(desc) -> [peer joins] -> onConnected(ep)
 * Lifecycle (joiner): join(desc)      -> onConnected(ep)
 * Both ends call stop() to tear down. All callbacks are posted to the main thread.
 */
public interface Connection {

    enum Variant { WIFI_DIRECT, WIFI_AWARE, HOTSPOT }

    /** Which of the 3 variants this is. */
    Variant variant();

    /** Is this variant usable on THIS device right now (feature + radio)? */
    boolean isSupported(Context ctx);

    /**
     * HOST: bring up the link as the server side and publish a descriptor the
     * joiner needs out-of-band (over BLE/NFC). {@code body} is the identity body
     * embedded in the SSID/serviceName. Non-blocking; result via Listener.
     */
    void startHost(String body, Listener cb);

    /**
     * JOINER: connect using a descriptor received out-of-band (BLE/NFC). For
     * Aware the descriptor carries serviceName + passphrase (no ip/port at join
     * time — those arrive in onConnected via WifiAwareNetworkInfo).
     */
    void join(ConnDescriptor desc, Listener cb);

    /** Idempotent teardown: remove group / unregister network / stop hotspot. */
    void stop();

    /** All methods invoked on the main thread. */
    interface Listener {
        /** HOST only: descriptor is ready to hand to the joiner (BLE/NFC). */
        void onCredsReady(ConnDescriptor desc);
        /** BOTH: link is up; endpoint tells the transfer layer how to reach the peer. */
        void onConnected(Endpoint ep);
        /** Terminal failure for this attempt. */
        void onFailed(String reason);
        void onLog(String msg);
    }
}
