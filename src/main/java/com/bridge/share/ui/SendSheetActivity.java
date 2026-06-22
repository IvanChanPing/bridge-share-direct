package com.bridge.share.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Share-sheet target. Opens a bottom sheet that immediately scans for nearby
 * receivers; each appears as a circular {@link DeviceIconView}. Tapping one
 * bounces the icon, shows a status line, and a ring progress as it sends.
 *
 * Discovery/transfer go through {@link SendController}. When launched WITHOUT a
 * shared stream (direct "preview" launch) it uses {@link PreviewSendController}
 * so the interaction can be reviewed without a second phone; a real share intent
 * uses the engine controller (wired per docs/ENGINE_SPEC.md).
 */
public class SendSheetActivity extends Activity implements SendController.Listener {

    private LinearLayout deviceRow;
    private TextView scanLabel;
    private DraggableSheetLayout sheet;
    private SendController controller;
    private final List<Uri> uris = new ArrayList<>();
    private final Map<String, DeviceIconView> views = new HashMap<>();   // by BLE address (status routing)
    private final Map<String, DeviceIconView> byName = new HashMap<>();  // by device name (dedupe icons)
    private boolean sending = false;
    /** Floating "Sending…" pop-up (same card the receiver gets), shown alongside this sheet. */
    private OverlayReceiver.SendHandle sendOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.bridge.share.diag.DiagLog.init(this);
        overridePendingTransition(0, 0); // no window fade; only our sheet slides up
        getWindow().setBackgroundDrawable(new ColorDrawable(0x33000000)); // light scrim (not fully dark)
        // NOTE: do NOT use FLAG_DIM_BEHIND — it dims the whole background heavily on top of the scrim.

        collectUris(getIntent());

        // Root: push the sheet to the bottom.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.BOTTOM);
        root.setOnClickListener(v -> sheet.dismiss()); // tap scrim → slide down

        sheet = new DraggableSheetLayout(this);
        android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
        sbg.setColor(0xFFF4F4F7);
        sbg.setCornerRadius(dp(28));
        sheet.setBackground(sbg);
        int pad = dp(20);
        sheet.setPadding(pad, pad, pad, dp(20));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Send to nearby device");
        title.setTextColor(0xFF111114);
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); // takes the row, pushes X right

        // Small X (close) in the top-right corner: dismiss the send sheet.
        TextView close = new TextView(this);
        close.setText("✕"); // ✕
        close.setTextColor(0xFF9A9AA0);
        close.setTextSize(18);
        close.setGravity(Gravity.CENTER);
        int cs = dp(32);
        close.setOnClickListener(v -> sheet.dismiss());
        titleRow.addView(close, new LinearLayout.LayoutParams(cs, cs));

        sheet.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        scanRow.setGravity(Gravity.CENTER_VERTICAL);
        scanRow.setPadding(0, dp(12), 0, dp(8));
        ProgressBar spinner = new ProgressBar(this);
        int s = dp(18);
        scanRow.addView(spinner, new LinearLayout.LayoutParams(s, s));
        scanLabel = new TextView(this);
        scanLabel.setText("  Scanning for nearby devices…");
        scanLabel.setTextColor(0xFF9A9AA0);
        scanLabel.setTextSize(13);
        scanRow.addView(scanLabel);
        sheet.addView(scanRow);

        deviceRow = new LinearLayout(this);
        deviceRow.setOrientation(LinearLayout.HORIZONTAL);
        deviceRow.setPadding(0, dp(8), 0, 0);
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(deviceRow);
        sheet.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(12); slp.rightMargin = dp(12); slp.bottomMargin = dp(12);
        root.addView(sheet, slp);
        setContentView(root);

        sheet.setOnDismiss(this::finish);
        DraggableSheetLayout.applyBottomInset(root, sheet, dp(20));
        sheet.playEntrance();

        controller = uris.isEmpty() ? new PreviewSendController() : engineController();
        com.bridge.share.diag.DiagLog.d("SendSheet", "onCreate uris=" + uris.size()
                + " controller=" + controller.getClass().getSimpleName());
        controller.startScan(this);
    }

    /** Engine-backed controller (BLE presence scan + on-demand host + creds GATT write). */
    private SendController engineController() {
        return new EngineSendController(this);
    }

    private void collectUris(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
    }

    // --- SendController.Listener (UI thread via runOnUiThread) ---
    @Override public void onPeerFound(SendController.Peer peer) {
        runOnUiThread(() -> {
            // Dedupe by device NAME, not BLE address: a receiver's MAC rotates (privacy), so
            // the same phone is seen under several addresses and used to show as several icons
            // (the "two CPH2583" duplicate). Collapse to one icon per name; register the view
            // under every address so status callbacks (which arrive by address) still route.
            DeviceIconView existing = byName.get(peer.name);
            if (existing != null) {
                views.put(peer.id, existing); // route this address's status to the same icon
                if (peer.tapped) beginSend(peer, existing); // NFC re-advertised → auto-send
                return;
            }
            DeviceIconView v = new DeviceIconView(this, peer.id, peer.name);
            com.bridge.share.diag.DiagLog.d("SendSheet", "peer found id=" + peer.id
                    + " name=" + peer.name + " tapped=" + peer.tapped);
            v.setOnClickListener(x -> beginSend(peer, v)); // manual tap → send
            byName.put(peer.name, v);
            views.put(peer.id, v);
            deviceRow.addView(v);
            sheet.animateGrow(); // smooth slide-up + overscroll settle as the sheet grows
            scanLabel.setText("  Tap a device to send");
            // NFC-tapped receiver → auto-send, no icon tap needed.
            if (peer.tapped) {
                com.bridge.share.diag.DiagLog.d("SendSheet", "peer " + peer.id + " is NFC-tapped -> auto-send");
                beginSend(peer, v);
            }
        });
    }

    /** Start the send to {@code peer} (manual tap or NFC auto-pick). One send per sheet. */
    private void beginSend(SendController.Peer peer, DeviceIconView v) {
        if (sending) {
            com.bridge.share.diag.DiagLog.d("SendSheet", "send ignored; already sending");
            return;
        }
        sending = true;
        com.bridge.share.diag.DiagLog.d("SendSheet", "sendTo " + peer.id + " (" + uris.size() + " uri(s))");
        v.bounce();
        v.setProgress(0); // show the circular ring around the icon
        scanLabel.setText("  Sending…");
        controller.sendTo(peer, uris);
        // Also float the same pop-up the receiver gets (island pill), mirroring send status.
        // Returns null if no overlay can be added (no island a11y AND no draw-over-apps perm) —
        // in that case the sheet remains the only sender UI.
        try { sendOverlay = OverlayReceiver.showSend(this, peer.name); }
        catch (Throwable t) { sendOverlay = null; }
    }
    @Override public void onPeerLost(String peerId) {
        runOnUiThread(() -> {
            DeviceIconView v = views.remove(peerId);
            if (v != null) deviceRow.removeView(v);
        });
    }
    @Override public void onStatus(String peerId, String status) {
        runOnUiThread(() -> {
            DeviceIconView v = views.get(peerId);
            if (v != null) v.setStatus(status);
            // "No response" is terminal (the picked receiver never pulled) — release the latch so
            // tapping the device again retries.
            if ("No response".equals(status)) {
                sending = false;
                scanLabel.setText("  No response – tap to retry");
                if (sendOverlay != null) sendOverlay.failed();
            }
        });
    }
    @Override public void onProgress(String peerId, int percent) {
        runOnUiThread(() -> {
            DeviceIconView v = views.get(peerId); if (v != null) v.setProgress(percent);
            if (sendOverlay != null) sendOverlay.progress(percent);
        });
    }
    @Override public void onComplete(String peerId) {
        runOnUiThread(() -> {
            DeviceIconView v = views.get(peerId);
            if (v != null) v.complete();
            scanLabel.setText("  Sent");
            if (sendOverlay != null) sendOverlay.complete();
        });
    }
    @Override public void onError(String peerId, String message) {
        com.bridge.share.diag.DiagLog.d("SendSheet", "ERROR peer=" + peerId + " msg=" + message);
        runOnUiThread(() -> {
            sending = false;                 // release the latch so tapping the device again retries
            DeviceIconView v = views.get(peerId);
            if (v != null) v.setStatus("Failed – tap to retry");
            scanLabel.setText("  Tap to retry");
            if (sendOverlay != null) sendOverlay.failed();
        });
    }

    @Override protected void onDestroy() {
        if (controller != null) controller.stop();
        // The send dies with this activity (controller.stop above), so the pop-up must not linger.
        if (sendOverlay != null) { sendOverlay.dismiss(); sendOverlay = null; }
        // Sending brings up a Wi-Fi-Direct group / hotspot and a creds BLE advertiser;
        // once the sheet closes, re-arm the receive listeners so the device can RECEIVE
        // again (it wasn't returning to armed-receive after a send).
        if (ReceivePrefs.getMode(this) != ReceivePrefs.Mode.OFF) {
            // arm() keeps ALWAYS_ON OS-only (wake scan, no persistent service); TIMED stays active.
            ReceiveService.arm(this);
        }
        super.onDestroy();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
