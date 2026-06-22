package com.bridge.share.conn;

import java.security.MessageDigest;

/**
 * Ported from SHAREit com.lenovo.anyshare.C8768eFd ("HashUtils") — only the
 * string-MD5 path used by the Wi-Fi-Direct passphrase derivation
 * (WiDiNetworkManagerEx.a(networkName) = md5Hex(name).substring(0,8)).
 * C8768eFd.a(String) == TFd.a(md5(utf8(str))) == lowercase hex of the MD5.
 */
public final class HashUtils {

    private HashUtils() {}

    /** SHAREit C8768eFd.a(String): lowercase hex MD5 of the UTF-8 bytes, or null. */
    public static String md5Hex(String str) {
        if (str == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(str.getBytes("UTF-8"));
            return toHex(digest);
        } catch (Exception e) {
            return null;
        }
    }

    /** SHAREit TFd.a(byte[]): two-char lowercase hex per byte. */
    public static String toHex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 0x10) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
