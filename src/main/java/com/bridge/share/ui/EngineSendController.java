package com.bridge.share.ui;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import com.bridge.share.BridgeEngine;
import com.bridge.share.channel.ShareRecord;
import com.bridge.share.trigger.Creds;
import com.bridge.share.trigger.CredsGattWriter;
import com.bridge.share.trigger.PresenceScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * REAL send controller (replaces {@link PreviewSendController} on the live path).
 *
 * Flow (see docs/BATTERY_SPEC §2/§3):
 *   1. startScan -> {@link PresenceScanner} (LOW_LATENCY) for receivers advertising
 *      the presence service; each new device -> {@link Listener#onPeerFound}.
 *   2. sendTo(peer, uris) -> build {@link ShareRecord}s from the picked uris,
 *      stop the scan, then {@link BridgeEngine#startHostShare} brings up the link,
 *      serves the files over /download, and publishes {@link Creds} via
 *      {@code onHostPublished}.
 *   3. onHostPublished(creds) -> {@link CredsGattWriter} connects to the peer's BLE
 *      address (peer.id) and WRITES creds.toBytes() to the receiver's request
 *      characteristic -> the receiver wakes, reads the creds, joins the host and
 *      PULLS the files. Engine progress/complete/error are mapped back to the UI.
 */
public final class EngineSendController implements SendController {

    private static final String TAG = "EngineSendController";

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Listener listener;
    private PresenceScanner scanner;
    private BridgeEngine engine;
    private CredsGattWriter gattWriter;
    private SendController.Peer activePeer;
    private Runnable sendTimeout;                 // fires "No response" if the peer never pulls

    /** If the picked receiver never starts pulling within this window (declined, walked away,
     *  failed to join), stop showing "Sending" forever and surface "No response". */
    private static final long SEND_TIMEOUT_MS = 45_000L;

    public EngineSendController(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    @Override
    public void startScan(Listener listener) {
        this.listener = listener;
        com.bridge.share.diag.DiagLog.d(TAG, "startScan: blePerms="
                + com.bridge.share.trigger.PresenceAdvertiser.hasBlePerms(appCtx)
                + " btEnabled=" + btEnabled());
        // Emit the wake beacon so nearby receivers whose process was killed get woken by
        // their system-held scan (and re-advertise presence so we can find them below).
        com.bridge.share.trigger.WakeBeacon.startAdvertise(appCtx);
        // Present the NFC "sender" HCE card ONLY while the send sheet is open. The
        // CredsHceService component is android:enabled="false" by default; enable it here so a
        // device NEVER presents the sender card unless it's actively sending. (Two always-on
        // cards made the NFC reader/card role a hardware coin-flip — the directional tap failure.)
        setSenderCard(true);
        // Serve the NFC launch NDEF (URI + Android Application Record) over HCE while the send
        // sheet is open, so a receiver tapping this phone auto-launches our app and starts the
        // normal BLE receive (NFC-as-trigger). Real creds get layered in once hosting starts.
        com.bridge.share.trigger.CredsHceService.armTrigger();
        scanner = new PresenceScanner(appCtx);
        boolean ok = scanner.start((deviceAddress, alias, tapped) ->
                main.post(() -> {
                    if (this.listener != null) {
                        this.listener.onPeerFound(new Peer(deviceAddress, alias, tapped));
                    }
                }));
        com.bridge.share.diag.DiagLog.d(TAG, "PresenceScanner.start() returned " + ok
                + " (false = no peers will appear)");
    }

    private boolean btEnabled() {
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager)
                    appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
            return bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
        } catch (Throwable t) { return false; }
    }

    @Override
    public void sendTo(Peer peer, List<Uri> uris) {
        this.activePeer = peer;
        // RADIO HELPER: a send needs Wi-Fi ON (the engine is about to create the Wi-Fi-Direct
        // group / LocalOnlyHotspot). Ask the universal radio-helper to enable Wi-Fi silently;
        // refcounted + idempotent, held until stop() (send sheet closed). No-op if the helper
        // isn't installed. See com.bridge.share.radio.BridgeRadioCoordinator.
        com.bridge.share.radio.BridgeRadioCoordinator.acquireSend(appCtx);
        if (scanner != null) { scanner.stop(); scanner = null; } // pick made: stop the scan

        // Tear down any previous attempt FIRST so every send starts from a CLEAN host. (Pre-warming
        // / reusing the host across sends was tried and reverted — it made things slower + less
        // reliable; a fresh group + server per send is the proven-reliable path. Reusing also risks
        // EADDRINUSE on the HTTP port + a muddled P2P group state.)
        cancelSendTimeout();
        if (gattWriter != null) { gattWriter.close(); gattWriter = null; }
        if (engine != null) { engine.stop(); engine = null; }

        status(peer, "Connecting");

        final List<ShareRecord> records = buildRecords(uris);
        com.bridge.share.diag.DiagLog.d(TAG, "sendTo peer=" + peer.id + " records=" + records.size());
        if (records.isEmpty()) { error(peer, "nothing to send"); return; }

        engine = new BridgeEngine(appCtx, Build.MODEL);
        engine.startHostShare(records, new BridgeEngine.Events() {
            @Override public void onLog(String msg) { Log.i(TAG, msg); }

            @Override public void onHostPublished(Creds creds) {
                // Link up + serving. Wake the picked receiver by writing creds to its GATT.
                status(peer, "Sending");
                if (gattWriter != null) gattWriter.close();
                gattWriter = new CredsGattWriter(appCtx);
                gattWriter.write(peer.id, creds.toBytes(), new CredsGattWriter.Callback() {
                    @Override public void onWritten() { Log.i(TAG, "creds delivered to " + peer.id); }
                    @Override public void onError(String msg) { error(peer, "trigger failed: " + msg); }
                });
                armSendTimeout(peer);
            }

            @Override public void onJoined(String hostIp, int port) { /* host side: nothing extra */ }

            @Override public void onProgress(ShareRecord record, long done, long total) {
                cancelSendTimeout(); // peer is pulling → it didn't decline
                progress(peer, total > 0 ? (int) (done * 100 / total) : 0);
            }

            @Override public void onComplete() {
                cancelSendTimeout();
                complete(peer);
                endGroupAfterSend(); // always drop the Wi-Fi-Direct group once the send is done
            }

            @Override public void onError(String msg) { cancelSendTimeout(); error(peer, msg); }
        });
    }

    /** Always tear the Wi-Fi-Direct group + HTTP server down after a completed send (don't leave
     *  the group owner running). Small grace delay so the receiver's final HTTP read finishes
     *  cleanly; the "Sent" UI stays. */
    private void endGroupAfterSend() {
        main.postDelayed(() -> {
            com.bridge.share.diag.DiagLog.d(TAG, "send complete -> tearing down Wi-Fi-Direct group");
            if (gattWriter != null) { gattWriter.close(); gattWriter = null; }
            if (engine != null) { engine.stop(); engine = null; }
        }, 1500);
    }

    /** Arm the "No response" watchdog; cancelled as soon as the peer starts pulling. */
    private void armSendTimeout(final Peer peer) {
        cancelSendTimeout();
        sendTimeout = () -> {
            com.bridge.share.diag.DiagLog.d(TAG, "send timeout: no pull from " + peer.id + " -> No response");
            sendTimeout = null;
            status(peer, "No response");
        };
        main.postDelayed(sendTimeout, SEND_TIMEOUT_MS);
    }

    private void cancelSendTimeout() {
        if (sendTimeout != null) { main.removeCallbacks(sendTimeout); sendTimeout = null; }
    }

    @Override
    public void stop() {
        cancelSendTimeout();
        // RADIO HELPER: send sheet closed → the send is over (success, error, timeout, or the
        // user dismissed the sheet all funnel here). Release our Wi-Fi hold; the helper restores
        // the user's original Wi-Fi state once no owner remains. See BridgeRadioCoordinator.
        com.bridge.share.radio.BridgeRadioCoordinator.releaseSend();
        com.bridge.share.trigger.WakeBeacon.stopAdvertise();
        if (scanner != null) { scanner.stop(); scanner = null; }
        if (gattWriter != null) { gattWriter.close(); gattWriter = null; }
        if (engine != null) { engine.stop(); engine = null; }
        // Send sheet closed -> stop presenting the NFC sender card so this device is only ever
        // the sender while the sheet is open (it goes back to read-only / receiver otherwise).
        setSenderCard(false);
        listener = null;
        activePeer = null;
    }

    /**
     * Present (enable) or hide (disable) the NFC HCE "sender" card by toggling the
     * {@link com.bridge.share.trigger.CredsHceService} component. It is android:enabled="false"
     * in the manifest, so a device presents the sender card ONLY between startScan (send sheet
     * opened) and stop (send sheet closed). Without this, the always-on HCE card on both phones
     * left the NFC reader/card role to hardware arbitration, so the intended sender frequently
     * lost the card role and the tap never started a transfer.
     */
    private void setSenderCard(boolean on) {
        try {
            android.content.pm.PackageManager pm = appCtx.getPackageManager();
            android.content.ComponentName cn = new android.content.ComponentName(
                    appCtx, com.bridge.share.trigger.CredsHceService.class);
            int state = on
                    ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(cn, state, android.content.pm.PackageManager.DONT_KILL_APP);
            com.bridge.share.diag.DiagLog.d(TAG, "sender NFC card " + (on ? "ENABLED (send sheet open)" : "DISABLED (send sheet closed)"));
        } catch (Exception e) {
            com.bridge.share.diag.DiagLog.d(TAG, "setSenderCard(" + on + ") failed: " + e);
        }
    }

    // ---- ShareRecord assembly from picked uris ----

    private List<ShareRecord> buildRecords(List<Uri> uris) {
        List<ShareRecord> out = new ArrayList<>();
        if (uris == null) return out;
        int i = 0;
        for (Uri u : uris) {
            if (u == null) continue;
            String id = "r" + System.currentTimeMillis() + "_" + (i++);
            String name = displayName(u);
            long size = fileSize(u);
            ShareRecord rec = new ShareRecord(id, id, ShareRecord.CT_FILE, name, size);
            rec.localUri = u.toString();
            out.add(rec);
        }
        return out;
    }

    private String displayName(Uri u) {
        String name = null;
        try (Cursor c = appCtx.getContentResolver()
                .query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            String last = u.getLastPathSegment();
            name = (last != null && !last.isEmpty()) ? last : "file";
        }
        return name;
    }

    private long fileSize(Uri u) {
        try (Cursor c = appCtx.getContentResolver()
                .query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    // ---- UI thread dispatch helpers ----

    private void status(Peer p, String s) {
        main.post(() -> { if (listener != null) listener.onStatus(p.id, s); });
    }
    private void progress(Peer p, int pct) {
        main.post(() -> { if (listener != null) listener.onProgress(p.id, pct); });
    }
    private void complete(Peer p) {
        main.post(() -> { if (listener != null) listener.onComplete(p.id); });
    }
    private void error(Peer p, String msg) {
        main.post(() -> { if (listener != null) listener.onError(p.id, msg); });
    }
}
