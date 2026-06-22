package com.bridge.share.conn;

import com.bridge.share.channel.ShareHttpServer;

/**
 * Variant-agnostic creds/handshake payload (see docs/ENGINE_SPEC.md §1.1).
 * Generalises the trigger-layer {@code Creds}; carried over BLE GATT / NFC NDEF.
 * The {@code variant} discriminator tells the joiner which {@link Connection} to build.
 */
public final class ConnDescriptor {
    public Connection.Variant variant;   // WHICH link the joiner must build
    public String alias;                 // human name

    // Wi-Fi Direct + Hotspot (Aware leaves these null):
    public String ssid;                  // Direct: DIRECT- networkName; Hotspot: AndroidShare_xxxx
    public String psk;                   // WPA2 passphrase
    public String hostIp;                // Direct: 192.168.49.1; Hotspot: reservation gateway ip

    // Wi-Fi Aware (Direct/Hotspot leave these null):
    public String serviceName;           // Aware publish/subscribe service name
    public byte[] matchFilter;           // optional Aware discovery hint (session nonce)
    public String pskPassphrase;         // Aware setPskPassphrase (>=8 chars)

    // Transfer layer (all variants):
    public int port = ShareHttpServer.DEFAULT_PORT; // 2999
}
