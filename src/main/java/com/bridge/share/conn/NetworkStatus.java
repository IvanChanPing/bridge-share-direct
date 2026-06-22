package com.bridge.share.conn;

/** Ported from SHAREit com.ushareit.nft.discovery.wifi.NetworkStatus. */
public enum NetworkStatus {
    IDLE("idle"),
    CLIENT("client"),
    SERVER("server");

    public final String mValue;

    NetworkStatus(String str) {
        this.mValue = str;
    }

    @Override
    public String toString() {
        return this.mValue;
    }
}
