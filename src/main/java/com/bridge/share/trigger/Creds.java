package com.bridge.share.trigger;

import com.bridge.share.channel.ShareHttpServer;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Bootstrap payload handed from the HOST (the device that became the Wi-Fi-Direct
 * group owner) to the JOINER over either trigger channel (BLE GATT / NFC HCE).
 * Lifted from oshare-port Creds, trimmed to what the SHAREit bridge needs:
 *   alias  - human name
 *   ssid   - the DIRECT- networkName (SHAREit WiDiNetworkManagerEx group name)
 *   psk    - the Wi-Fi-Direct passphrase (md5Hex(name)[:8])
 *   hostIp - the group-owner ip (192.168.49.1)
 *   port   - the SHAREit channel/HTTP port (2999)
 * Compact JSON so a joiner decodes it identically regardless of arrival channel.
 */
public final class Creds {

    public String alias;
    public String ssid;
    public String psk;
    public String hostIp;
    public int    port = ShareHttpServer.DEFAULT_PORT;

    // Wi-Fi Aware carries no ssid/ip; the joiner needs the publish serviceName +
    // data-path passphrase instead. Left null for Wi-Fi Direct / Hotspot.
    public String serviceName;
    public String pskPassphrase;

    public Creds() {}

    public Creds(String alias, String ssid, String psk, String hostIp, int port) {
        this.alias = alias;
        this.ssid = ssid;
        this.psk = psk;
        this.hostIp = hostIp;
        this.port = port;
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("alias", alias == null ? "" : alias);
            o.put("ssid", ssid == null ? "" : ssid);
            o.put("psk", psk == null ? "" : psk);
            o.put("host_ip", hostIp == null ? "" : hostIp);
            o.put("port", port);
            o.put("service_name", serviceName == null ? "" : serviceName);
            o.put("psk_passphrase", pskPassphrase == null ? "" : pskPassphrase);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public byte[] toBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    public static Creds fromJson(String json) {
        Creds c = new Creds();
        try {
            JSONObject o = new JSONObject(json);
            c.alias = o.optString("alias", null);
            c.ssid = o.optString("ssid", null);
            c.psk = o.optString("psk", null);
            c.hostIp = o.optString("host_ip", null);
            c.port = o.optInt("port", ShareHttpServer.DEFAULT_PORT);
            c.serviceName = emptyToNull(o.optString("service_name", null));
            c.pskPassphrase = emptyToNull(o.optString("psk_passphrase", null));
        } catch (Exception ignored) {}
        return c;
    }

    public static Creds fromBytes(byte[] b) {
        return fromJson(new String(b, StandardCharsets.UTF_8));
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
