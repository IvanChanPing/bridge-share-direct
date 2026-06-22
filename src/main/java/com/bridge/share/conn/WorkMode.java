package com.bridge.share.conn;

/**
 * Ported bite-for-bite from SHAREit Lite 3.17.58
 * com.ushareit.nft.discovery.wifi.WorkMode. The single-letter mValue tokens are
 * the on-the-wire identity prefix embedded in the encoded SSID, so they must
 * match SHAREit exactly.
 */
public enum WorkMode {
    GROUP("A"),
    P2P("B"),
    SHARE("C"),
    CLONE("D"),
    PC("E"),
    PC_S("S"),
    SENDER("F"),
    INVITE("I"),
    SHARECENTER("G");

    public final String mValue;

    WorkMode(String str) {
        this.mValue = str;
    }

    public static WorkMode parseWorkMode(String str) {
        for (WorkMode w : values()) {
            if (w.mValue.equals(str)) return w;
        }
        throw new IllegalArgumentException();
    }

    @Override
    public String toString() {
        return this.mValue;
    }
}
