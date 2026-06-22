package com.bridge.share.trigger;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * NFC creds channel — JOINER side (reader mode). Lifted from oshare-port
 * CredsNfcReader: NfcAdapter.enableReaderMode taps a host running
 * {@link CredsHceService}, drives the Type-4-Tag APDU sequence over IsoDep,
 * parses the NDEF external creds record, returns {@link Creds}. UTF8 ->
 * StandardCharsets; external type matched to the HCE ("bridge.share:creds").
 */
public final class CredsNfcReader {

    private static final String TAG = "CredsNfcReader";

    public interface Callback {
        void onCreds(Creds creds);
        void onError(String msg);
        void onLog(String msg);
        /** Host accepted the trigger but has no creds yet -> joiner should scan BLE. */
        default void onTriggerSentScanBle() {}
    }

    private static final byte[] SELECT_AID = {
            0x00, (byte) 0xA4, 0x04, 0x00, 0x08,
            (byte) 0xF0, 0x4C, 0x53, 0x57, 0x4F, 0x4F, 0x44, 0x53, 0x00 };
    private static final byte[] REQUEST_HOST = { (byte) 0x80, 0x10, 0x00, 0x00 };
    private static final byte[] SELECT_NDEF = { 0x00, (byte) 0xA4, 0x00, 0x0C, 0x02, (byte) 0xE1, 0x04 };

    private static final String WOODS_EXT_TYPE = "bridge.share:creds";

    private final Activity activity;
    private final Callback callback;
    private NfcAdapter adapter;

    public CredsNfcReader(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public boolean isNfcAvailable() {
        adapter = NfcAdapter.getDefaultAdapter(activity);
        return adapter != null;
    }

    public boolean enable() {
        adapter = NfcAdapter.getDefaultAdapter(activity);
        if (adapter == null) { err("no NFC adapter on this device"); return false; }
        if (!adapter.isEnabled()) { err("NFC is turned off"); return false; }
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
        Bundle opts = new Bundle();
        opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
        adapter.enableReaderMode(activity, new NfcAdapter.ReaderCallback() {
            @Override public void onTagDiscovered(Tag tag) { handleTag(tag); }
        }, flags, opts);
        log("reader mode enabled, tap the host");
        return true;
    }

    public void disable() {
        if (adapter != null) {
            try { adapter.disableReaderMode(activity); } catch (Exception ignored) {}
        }
    }

    private void handleTag(Tag tag) {
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) { err("tag is not IsoDep (not our HCE host)"); return; }
        try {
            iso.connect();
            iso.setTimeout(3000);

            byte[] r = iso.transceive(SELECT_AID);
            log("SELECT AID -> " + hex(r));
            if (!ok(r)) { err("SELECT AID failed"); return; }

            byte[] tr = iso.transceive(REQUEST_HOST);
            log("request-host trigger -> " + hex(tr));

            r = iso.transceive(SELECT_NDEF);
            if (!ok(r)) { err("SELECT NDEF failed"); return; }

            byte[] nlen = readBinary(iso, 0, 2);
            if (nlen == null || nlen.length < 2) { err("read NLEN failed"); return; }
            int msgLen = ((nlen[0] & 0xff) << 8) | (nlen[1] & 0xff);
            log("NDEF message length = " + msgLen);

            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            int offset = 2;
            int remaining = msgLen;
            while (remaining > 0) {
                int chunk = Math.min(0x3B, remaining);
                byte[] part = readBinary(iso, offset, chunk);
                if (part == null) { err("read NDEF chunk failed at " + offset); return; }
                msg.write(part, 0, part.length);
                offset += part.length;
                remaining -= part.length;
                if (part.length == 0) break;
            }

            byte[] payload = (msgLen > 0) ? parseCredsRecordPayload(msg.toByteArray()) : null;
            if (payload == null) {
                log("no creds in NDEF yet -> scan BLE for creds");
                if (callback != null) callback.onTriggerSentScanBle();
                return;
            }
            Creds creds = Creds.fromBytes(payload);
            log("creds received via NFC: ssid=" + creds.ssid + " ip=" + creds.hostIp);
            if (callback != null) callback.onCreds(creds);
        } catch (Exception e) {
            err("NFC transceive error: " + e);
        } finally {
            try { iso.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readBinary(IsoDep iso, int offset, int len) throws Exception {
        byte[] cmd = { 0x00, (byte) 0xB0, (byte) ((offset >> 8) & 0xff), (byte) (offset & 0xff), (byte) (len & 0xff) };
        byte[] r = iso.transceive(cmd);
        if (!ok(r)) return null;
        byte[] data = new byte[r.length - 2];
        System.arraycopy(r, 0, data, 0, data.length);
        return data;
    }

    static byte[] parseCredsRecordPayload(byte[] msg) {
        if (msg == null || msg.length < 3) return null;
        int i = 0;
        while (i < msg.length) {
            int start = i;
            int header = msg[i++] & 0xff;
            boolean sr = (header & 0x10) != 0;
            boolean il = (header & 0x08) != 0;
            if (i >= msg.length) break;
            int typeLen = msg[i++] & 0xff;
            int payloadLen;
            if (sr) {
                if (i >= msg.length) break;
                payloadLen = msg[i++] & 0xff;
            } else {
                if (i + 4 > msg.length) break;
                payloadLen = ((msg[i] & 0xff) << 24) | ((msg[i + 1] & 0xff) << 16)
                        | ((msg[i + 2] & 0xff) << 8) | (msg[i + 3] & 0xff);
                i += 4;
            }
            int idLen = 0;
            if (il) {
                if (i >= msg.length) break;
                idLen = msg[i++] & 0xff;
            }
            int typeStart = i;
            i += typeLen;
            i += idLen;
            int payloadStart = i;
            if (payloadStart + payloadLen > msg.length) payloadLen = msg.length - payloadStart;
            if (payloadLen < 0) break;
            if (typeLen > 0 && typeStart + typeLen <= msg.length) {
                String type = new String(msg, typeStart, typeLen, StandardCharsets.UTF_8);
                if (WOODS_EXT_TYPE.equalsIgnoreCase(type)) {
                    byte[] payload = new byte[payloadLen];
                    System.arraycopy(msg, payloadStart, payload, 0, payloadLen);
                    return payload;
                }
            }
            i = payloadStart + payloadLen;
            if (i <= start) break;
            if ((header & 0x40) != 0) break;
        }
        return null;
    }

    private static boolean ok(byte[] r) {
        return r != null && r.length >= 2
                && (r[r.length - 2] & 0xff) == 0x90 && (r[r.length - 1] & 0xff) == 0x00;
    }

    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x & 0xff));
        return sb.toString();
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (callback != null) callback.onLog(msg);
    }

    private void err(String msg) {
        Log.w(TAG, msg);
        if (callback != null) callback.onError(msg);
    }
}
