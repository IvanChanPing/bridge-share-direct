package com.bridge.share.conn;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;

import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;

/**
 * Ported bite-for-bite from SHAREit Lite com.lenovo.anyshare.C11158jIg ("SsidHelper"),
 * plus the two helpers it (and WiDiNetworkManagerEx) depend on:
 *   - C6471Zkh.a / C8768eFd.a : MD5 (see {@link HashUtils})
 *   - C17770xHg.b()           : deterministic per-device SSID char ({@link #randomCharForSsid})
 *
 * Responsibilities reproduced exactly:
 *   - the 62-char alphabet d = "0-9 a-z A-Z" and its length e=62
 *   - subnet(index)              == SHAREit a(int)
 *   - derivePassword(key)        == SHAREit a(String)  (legacy hotspot 8-char password)
 *   - directPassphrase(name,wm)  == WiDiNetworkManagerEx.a(name) (md5Hex(name)[:8] / "12345678")
 *   - buildDirectNetworkName(..) == WiDiNetworkManagerEx.e()'s "DIRECT-<band><c>-<body>" build
 *   - encode/decode of the identity body (workmode + token + "-" + base64(name))
 */
public final class SsidHelper {

    /** SHAREit alphabet d: '0'..'9','a'..'z','A'..'Z'. */
    public static final String ALPHABET;
    public static final int ALPHABET_LEN;
    private static final Random RANDOM = new Random(System.currentTimeMillis());

    /** Vendor SSID prefixes SHAREit rejects (C11158jIg.h[]). */
    private static final String[] VENDOR_PREFIXES = {
            "ASUS","AIGO","AIGALE","AIKA","BAIDU","APPLE","BELKIN","BUFFALO","BYZORO","BLINK",
            "CMCC","CMM","CHINA","CYBERDI","CISCO","DLINK","DNIXS","BEACON","FREEDOM","BUPT","BNRD"
    };

    static {
        StringBuilder sb = new StringBuilder();
        for (char c = '0'; c <= '9'; c++) sb.append(c);
        for (char c = 'a'; c <= 'z'; c++) sb.append(c);
        for (char c = 'A'; c <= 'Z'; c++) sb.append(c);
        ALPHABET = sb.toString();
        ALPHABET_LEN = ALPHABET.length();
    }

    private SsidHelper() {}

    // ---- alphabet helpers (C11158jIg.a()/a(char)) ----
    private static char randomAlphabetChar() { return ALPHABET.charAt(RANDOM.nextInt(ALPHABET_LEN)); }
    private static int alphabetIndex(char c) { return ALPHABET.indexOf(c); }

    /** SHAREit a(int subnetIndex): the /24 subnet for a hotspot index. */
    public static String subnet(int index) {
        switch (index) {
            case 1: return "192.168.1";
            case 2: return "192.168.173";
            case 3: return "192.168.137";
            default: return "192.168.43";
        }
    }

    /**
     * SHAREit C11158jIg.a(String key): an 8-char password derived from MD5(key).
     * For i in 0..7: ch = ALPHABET[((md5[i] | md5[15-i]) % 26) + 10] (i.e. 'a'..'z').
     */
    public static String derivePassword(String key) {
        StringBuilder sb = new StringBuilder();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] a = md.digest(key.getBytes("UTF-8"));
            for (int i = 0; i < 8; i++) {
                int i2 = a[i] & 0xFF;
                int i3 = a[a.length - 1 - i] & 0xFF;
                sb.append(ALPHABET.charAt(((i2 | i3) % 26) + 10));
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /**
     * C17770xHg.b(): deterministic SSID char from android_id, index = |hash| % 62.
     * Falls back to index 3 when android_id is unavailable.
     */
    public static char randomCharForSsid(Context ctx) {
        String aid = Settings.Secure.getString(ctx.getContentResolver(), "android_id");
        int idx = aid != null ? (int) (Math.abs(aid.hashCode()) % 62) : 3;
        return "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".charAt(idx);
    }

    /** WiDiNetworkManagerEx.b(boolean is5g): band prefix = ("5"|"2") + randomCharForSsid. */
    public static String bandPrefix(Context ctx, boolean is5g) {
        return (is5g ? "5" : "2") + randomCharForSsid(ctx);
    }

    /**
     * WiDiNetworkManagerEx.e() networkName build:
     *   "DIRECT-" + (workMode==INVITE ? "si" : bandPrefix) + "-" + body, truncated to 32.
     */
    public static String buildDirectNetworkName(Context ctx, WorkMode workMode, boolean is5g, String body) {
        String name = "DIRECT-" + (workMode == WorkMode.INVITE ? "si" : bandPrefix(ctx, is5g)) + "-" + body;
        if (name.length() > 32) name = name.substring(0, 32);
        return name;
    }

    /**
     * WiDiNetworkManagerEx.a(networkName): the Wi-Fi-Direct group passphrase.
     * INVITE mode uses fixed "12345678"; otherwise md5Hex(name).substring(0,8).
     */
    public static String directPassphrase(String networkName, WorkMode workMode) {
        if (workMode == WorkMode.INVITE) return "12345678";
        String md5 = HashUtils.md5Hex(networkName);
        return (md5 == null || md5.length() <= 8) ? randomAscii(8) : md5.substring(0, 8);
    }

    /** WiDiNetworkManagerEx.c(int): random ASCII letters/digits of given length. */
    public static String randomAscii(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (RANDOM.nextInt(2) % 2 == 0) {
                sb.append((char) (RANDOM.nextInt(26) + (RANDOM.nextInt(2) % 2 == 0 ? 65 : 97)));
            } else {
                sb.append(RANDOM.nextInt(10));
            }
        }
        return sb.toString();
    }

    // ---- identity body encode/decode (C11158jIg.a(str,str,str)/c/d) ----

    /** Trim a UTF-8 string to at most maxBytes (C11158jIg.a(str,int)). */
    public static String trimToBytes(String str, int maxBytes) {
        if (str == null) str = android.os.Build.MODEL;
        try {
            if (str.getBytes("UTF-8").length <= maxBytes) return str;
            for (int len = str.length() - 1; len > 0; len--) {
                String sub = str.substring(0, len);
                if (sub.getBytes("UTF-8").length <= maxBytes) return sub;
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    /**
     * C11158jIg.a(workMode, randomToken, name): compose the inner identity body
     * = workMode + randomToken + '-' + base64(name) (Base64 NO_PADDING|NO_WRAP == flag 3).
     */
    public static String composeBody(String workMode, String randomToken, String name) {
        String b64;
        try {
            b64 = Base64.encodeToString(name.getBytes("UTF-8"), Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            b64 = "unknown";
        }
        return workMode + randomToken + '-' + b64;
    }

    /** C11158jIg.d(ssidBody): decode the base64 name from an identity body. */
    public static String decodeName(String body) {
        try {
            String sub = body.substring(body.indexOf('-') + 1);
            return "unknown".equals(sub) ? "unknown"
                    : new String(Base64.decode(sub, Base64.NO_PADDING | Base64.NO_WRAP), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ---- SSID type predicates (C11158jIg) ----

    /** C11158jIg.p(str): a Wi-Fi-Direct SSID (and not a Windows "DESKTOP"). */
    public static boolean isDirect(String ssid) {
        return ssid != null && ssid.matches("^DIRECT-[a-zA-Z0-9]{2}\\S+") && !ssid.contains("DESKTOP");
    }

    /** C11158jIg.h(str): SHAREit LocalOnlyHotspot SSID "AndroidShare_1234". */
    public static boolean isAndroidShare(String ssid) {
        return ssid != null && ssid.matches("^AndroidShare_[0-9]{4}");
    }

    /** C11158jIg.n(str): a known consumer-router/vendor SSID we must ignore. */
    public static boolean isVendorSsid(String ssid) {
        if (ssid == null) return false;
        String up = ssid.toUpperCase(Locale.US);
        for (String p : VENDOR_PREFIXES) if (up.startsWith(p + '-')) return true;
        return false;
    }
}
