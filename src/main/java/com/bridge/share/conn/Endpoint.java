package com.bridge.share.conn;

import android.net.Network;

import javax.net.SocketFactory;

/**
 * How the transfer layer reaches the peer (see docs/ENGINE_SPEC.md §1.2).
 * Resolves "where do I bind / what host do I GET" per variant.
 */
public final class Endpoint {
    public String host;                  // IPv4 for Direct(192.168.49.1)/Hotspot; IPv6 literal for Aware
    public int port;                     // 2999
    public Network network;              // Aware: granted Network; Direct/Hotspot: null (default route)
    public SocketFactory socketFactory;  // Aware: network.getSocketFactory(); else null
    public boolean isIpv6;               // true for Aware (bracket the host in URLs)

    public Endpoint() {}

    public Endpoint(String host, int port) {
        this.host = host;
        this.port = port;
    }
}
