package com.bridge.share.trigger;

import android.content.Context;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * NFC creds channel — HOST side via Host Card Emulation. Lifted from oshare-port
 * CredsHceService (NFC Forum Type 4 Tag emulation serving the {@link Creds} JSON
 * as an NDEF external record). Two adaptations for this standalone engine:
 *   - LocalSendProtocol.UTF8 -> StandardCharsets.UTF_8
 *   - the proprietary request-host APDU now fires a pluggable {@link HostTrigger}
 *     (set by the engine) instead of WoodsHostService.requestHostOnDemand.
 *
 * AID F04C53574F4F4453 (must match res/xml/apduservice + manifest).
 */
public final class CredsHceService extends HostApduService {

    private static final String TAG = "CredsHce";

    /** Pluggable on-demand host bring-up (set by the engine). */
    public interface HostTrigger { void requestHost(Context ctx); }
    private static volatile HostTrigger sTrigger;
    public static void setHostTrigger(HostTrigger t) { sTrigger = t; }

    private static final byte[] AID = { (byte) 0xF0, 0x4C, 0x53, 0x57, 0x4F, 0x4F, 0x44, 0x53 };
    /** Standard NDEF Type-4 Tag application AID — selected by a stock-dispatch reader. */
    private static final byte[] NDEF_AID = { (byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01 };

    private static final byte[] OK           = { (byte) 0x90, 0x00 };
    private static final byte[] FAIL         = { (byte) 0x6A, (byte) 0x82 };
    private static final byte[] WRONG_LENGTH = { (byte) 0x67, 0x00 };

    private static final byte INS_REQUEST_HOST = 0x10;
    private static volatile boolean sArmed = false;

    private static final byte[] CC_FILE_ID   = { (byte) 0xE1, 0x03 };
    private static final byte[] NDEF_FILE_ID = { (byte) 0xE1, 0x04 };

    private static final byte[] CC = {
            0x00, 0x0F, 0x20, 0x00, 0x3B, 0x00, 0x34, 0x04, 0x06,
            (byte) 0xE1, 0x04, 0x7F, (byte) 0xFF, 0x00, (byte) 0xFF
    };

    private static volatile byte[] sNdefFile = buildEmptyNdef();
    private byte[] selectedFile = null;

    /** Publish the creds the next SELECT/READ returns (call before the tap). */
    public static void setCreds(byte[] credsBytes) {
        sNdefFile = buildNdefFile(credsBytes);
        sArmed = true;
        Log.i(TAG, "creds set, ndef file " + sNdefFile.length + " bytes");
    }

    /** Arm trigger-only (no creds yet); a tap fires the on-demand host bring-up. */
    public static void armTrigger() {
        sNdefFile = buildEmptyNdef();
        sArmed = true;
        Log.i(TAG, "TRIGGER armed (no creds; tap requests on-demand host)");
    }

    public static void clear() {
        sNdefFile = buildEmptyNdef();
        sArmed = false;
    }

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) return WRONG_LENGTH;

        if (apdu[0] == (byte) 0x80 && apdu[1] == INS_REQUEST_HOST) {
            if (sArmed) {
                Log.i(TAG, "request-host APDU -> on-demand host bring-up");
                try {
                    HostTrigger t = sTrigger;
                    if (t != null) t.requestHost(getApplicationContext());
                } catch (Throwable th) {
                    Log.w(TAG, "host trigger failed: " + th);
                }
            }
            return OK;
        }

        if (apdu[0] == 0x00 && apdu[1] == (byte) 0xA4) {
            if (apdu[2] == 0x04) {
                // Log the AID the reader asked for, so we can see on the failing phone whether
                // our HCE is even being selected (and with which AID) on a tap.
                int lc = apdu.length > 4 ? (apdu[4] & 0xff) : 0;
                StringBuilder aidHex = new StringBuilder();
                for (int i = 5; i < apdu.length && i < 5 + lc; i++) aidHex.append(String.format("%02X", apdu[i] & 0xff));
                boolean ours = selectMatches(apdu, AID);
                boolean ndef = selectMatches(apdu, NDEF_AID);
                com.bridge.share.diag.DiagLog.d(TAG, "SELECT AID req=" + aidHex
                        + " ours=" + ours + " ndef=" + ndef);
                if (ours || ndef) { selectedFile = null; return OK; }
                return FAIL;
            }
            if (apdu[2] == 0x00 && apdu.length >= 7) {
                byte[] fid = { apdu[5], apdu[6] };
                if (eq(fid, CC_FILE_ID)) { selectedFile = CC_FILE_ID; return OK; }
                if (eq(fid, NDEF_FILE_ID)) { selectedFile = NDEF_FILE_ID; return OK; }
                return FAIL;
            }
            return FAIL;
        }

        if (apdu[0] == 0x00 && apdu[1] == (byte) 0xB0) {
            int offset = ((apdu[2] & 0xff) << 8) | (apdu[3] & 0xff);
            int le = apdu.length >= 5 ? (apdu[4] & 0xff) : 0;
            if (le == 0) le = 0x3B;
            byte[] src;
            if (eq(selectedFile, CC_FILE_ID)) src = CC;
            else if (eq(selectedFile, NDEF_FILE_ID)) src = sNdefFile;
            else return FAIL;
            if (offset > src.length) return FAIL;
            int len = Math.min(le, src.length - offset);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(src, offset, len);
            bos.write(OK, 0, OK.length);
            return bos.toByteArray();
        }
        return FAIL;
    }

    @Override
    public void onDeactivated(int reason) {
        selectedFile = null;
        Log.i(TAG, "deactivated reason=" + reason);
    }

    // ---------------------------------------------------------- NDEF helpers
    /**
     * Leading NDEF URI record. On a phone WITHOUT the app, Android's tag-dispatch
     * opens this https link (so the user can install). A phone WITH the app uses
     * reader-mode + the creds external record below and ignores this.
     * TODO: replace with the real Play Store / install URL once published.
     */
    private static final String WOODS_URI = "https://bridgeshare.app/get";

    /** Android Application Record type — Android launches the named package on tap. */
    private static final String AAR_TYPE = "android.com:pkg";

    /**
     * The NDEF served on a tap. Every message ENDS with an Android Application Record
     * (AAR) for our installed package, so an app-having phone AUTO-LAUNCHES our app on tap
     * (the NFC-as-trigger design: the launch kicks off the normal BLE receive). The leading
     * https URI is the no-app fallback (note: when an AAR is present Android sends a no-app
     * phone to Play for the package, so the custom install URL only matters once we drop the
     * AAR / publish). When hosting, the creds external record is also included.
     */
    static byte[] buildNdefFile(byte[] payload) {
        byte[] pkg = com.bridge.share.BuildConfig.APPLICATION_ID.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream ndef = new ByteArrayOutputStream();
        if (payload == null || payload.length == 0) {
            byte[] uri = buildUriRecord(WOODS_URI, true, false);
            byte[] aar = buildExternalRecord(AAR_TYPE, pkg, false, true);
            ndef.write(uri, 0, uri.length);
            ndef.write(aar, 0, aar.length);
        } else {
            byte[] uri = buildUriRecord(WOODS_URI, true, false);
            byte[] ext = buildExternalRecord("bridge.share:creds", payload, false, false);
            byte[] aar = buildExternalRecord(AAR_TYPE, pkg, false, true);
            ndef.write(uri, 0, uri.length);
            ndef.write(ext, 0, ext.length);
            ndef.write(aar, 0, aar.length);
        }
        byte[] ndefMsg = ndef.toByteArray();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write((ndefMsg.length >> 8) & 0xff);
        bos.write(ndefMsg.length & 0xff);
        bos.write(ndefMsg, 0, ndefMsg.length);
        return bos.toByteArray();
    }

    private static byte[] buildEmptyNdef() { return buildNdefFile(new byte[0]); }

    private static byte[] buildUriRecord(String uri, boolean mb, boolean me) {
        byte[] uriBytes = uri.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[uriBytes.length + 1];
        payload[0] = 0x00;
        System.arraycopy(uriBytes, 0, payload, 1, uriBytes.length);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int header = 0x01;
        if (mb) header |= 0x80;
        if (me) header |= 0x40;
        if (payload.length < 256) header |= 0x10;
        bos.write(header);
        bos.write(1);
        bos.write(payload.length & 0xff);
        bos.write('U');
        bos.write(payload, 0, payload.length);
        return bos.toByteArray();
    }

    private static byte[] buildExternalRecord(String type, byte[] payload, boolean mb, boolean me) {
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        boolean shortRecord = payload.length < 256;
        int header = 0x04;
        if (mb) header |= 0x80;
        if (me) header |= 0x40;
        if (shortRecord) header |= 0x10;
        bos.write(header);
        bos.write(typeBytes.length);
        if (shortRecord) {
            bos.write(payload.length & 0xff);
        } else {
            bos.write((payload.length >> 24) & 0xff);
            bos.write((payload.length >> 16) & 0xff);
            bos.write((payload.length >> 8) & 0xff);
            bos.write(payload.length & 0xff);
        }
        bos.write(typeBytes, 0, typeBytes.length);
        bos.write(payload, 0, payload.length);
        return bos.toByteArray();
    }

    /** True if a SELECT-by-name APDU (00 A4 04 00 Lc ...) carries the given AID. */
    private static boolean selectMatches(byte[] apdu, byte[] aid) {
        if (apdu.length < 5 + aid.length) return false;
        for (int i = 0; i < aid.length; i++) if (apdu[5 + i] != aid[i]) return false;
        return true;
    }

    private static boolean eq(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
}
