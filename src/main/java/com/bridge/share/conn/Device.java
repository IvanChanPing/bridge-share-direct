package com.bridge.share.conn;

import android.text.TextUtils;

/**
 * Ported from SHAREit com.ushareit.nft.discovery.Device, with the obfuscated
 * single-letter fields given their real names (recovered from usage in
 * WifiMaster / WiDiNetworkManagerEx / WifiProfile / SsidHelper):
 *   f24325a -> deviceId   b -> ip        c -> name     d -> iconIndex
 *   i       -> ssid       j -> passphrase h -> pwdType  q -> discoverType
 *   u       -> is5g       o -> portIndex
 */
public class Device {

    public enum Type { WIFI, LAN, WEB }

    public enum DiscoverType {
        WIFI("wifi"), BT("bt"), QRCODE("qrcode"), LAN("lan"),
        WIDI("widi"), CLOUD("cloud"), BLE("ble"), NFC("nfc"), UNKNOWN("unknown");

        public final String mValue;
        DiscoverType(String s) { this.mValue = s; }
        @Override public String toString() { return mValue; }
    }

    public final Type type;
    public String deviceId;      // encoded short identity used in the SSID
    public String ip;            // group-owner / host ip once connected
    public String name;          // human alias
    public int    iconIndex;
    public int    pwdType;       // 0 open, 2 WPA2-PSK (SHAREit pwdType)
    public String ssid;          // DIRECT-xx-<id>
    public String passphrase;    // Wi-Fi-Direct group passphrase
    public int    portIndex;
    public DiscoverType discoverType = DiscoverType.UNKNOWN;
    public boolean is5g = false;

    public Device(Type type) {
        this.type = type;
    }

    public Device(Type type, String deviceId, String name, int iconIndex) {
        this(type);
        this.deviceId = deviceId;
        this.name = name;
        this.iconIndex = iconIndex;
    }

    /** SHAREit Device.b(): the SSID to connect to (ssid if set, else deviceId for WIFI). */
    public String connectSsid() {
        if (!TextUtils.isEmpty(ssid)) return ssid;
        if (type == Type.WIFI) return deviceId;
        throw new IllegalArgumentException("non-WIFI device has no ssid");
    }

    @Override
    public int hashCode() {
        return 31 + (deviceId == null ? 0 : deviceId.hashCode());
    }

    @Override
    public String toString() {
        return "Device[id=" + deviceId + ", name=" + name + ", ssid=" + ssid
                + ", ip=" + ip + ", pwdType=" + pwdType + ", method=" + discoverType + "]";
    }
}
