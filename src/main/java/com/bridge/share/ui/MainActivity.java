package com.bridge.share.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * The single main page (opened directly), like oshare-port: one setting with
 * three states — Off / Always on / On for 10 minutes — controlling receive
 * visibility. Selecting a mode persists it and starts/stops {@link ReceiveService}.
 */
public class MainActivity extends Activity {

    private android.widget.Button overlayPermButton;
    private android.widget.Button islandButton;
    private android.widget.Button batteryButton;
    private android.widget.Button diagButton;

    @Override
    protected void onResume() {
        super.onResume();
        if (overlayPermButton != null) {
            overlayPermButton.setText(ReceiveUi.overlayEnabled(this)
                    ? "Overlay: ON (receive shows as overlay)"
                    : "Enable overlay — tap to grant (via helper)");
        }
        if (islandButton != null) {
            islandButton.setText(IslandA11yService.isConnected()
                    ? "Island: ON (tappable over the camera)"
                    : "Enable island over camera — see steps below");
        }
        updateDiagButton();
        updateBatteryButton();
    }

    private void updateDiagButton() {
        if (diagButton != null) {
            diagButton.setText(com.bridge.share.diag.DiagLog.isEnabled(this)
                    ? "Diagnostic logging: ON (tap to turn off)"
                    : "Diagnostic logging: OFF (tap to turn on)");
        }
    }

    private boolean batteryUnrestricted() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void updateBatteryButton() {
        if (batteryButton != null) {
            batteryButton.setText(batteryUnrestricted()
                    ? "Background: unrestricted (transfers survive leaving the app)"
                    : "Allow background transfers (disable battery optimization)");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        com.bridge.share.diag.DiagLog.init(this);
        android.content.Intent li = getIntent();
        com.bridge.share.diag.DiagLog.d("MainActivity", "onCreate; mode=" + ReceivePrefs.getMode(this)
                + " overlayEnabled=" + ReceiveUi.overlayEnabled(this)
                + " launchAction=" + (li != null ? li.getAction() : null)
                + " launchData=" + (li != null ? li.getDataString() : null)
                + " (if launchAction is an NFC action, the AAR launched the LAUNCHER instead of"
                + " NfcLaunchActivity → NFC routing needs adjusting)");

        requestNeededPermissions();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("Bridge Share");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Receiving");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.GRAY);
        subtitle.setPadding(0, dp(28), 0, dp(8));
        root.addView(subtitle);

        final RadioGroup group = new RadioGroup(this);
        final RadioButton off = makeOption(group, "Off");
        final RadioButton always = makeOption(group, "Always on");
        final RadioButton timed = makeOption(group, "On for 10 minutes");
        root.addView(group);

        final TextView hint = new TextView(this);
        hint.setTextSize(13);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, dp(16), 0, 0);
        root.addView(hint);

        android.widget.Button preview = new android.widget.Button(this);
        preview.setText("Preview send sheet");
        preview.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, SendSheetActivity.class)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(40);
        root.addView(preview, plp);

        android.widget.Button previewRecv = new android.widget.Button(this);
        previewRecv.setText("Preview receive (sheet/overlay)");
        previewRecv.setOnClickListener(v -> ReceiveUi.preview(this));
        root.addView(previewRecv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // "Enable overlay" button — ONE-TAP grant via the universal radio-helper
        // (silent, appop persists across reboot). If overlay is already on, tapping
        // opens the system toggle (so the user can turn it off). If off, it asks the
        // helper to grant SYSTEM_ALERT_WINDOW in one tap; on failure (helper absent /
        // not paired / grant refused) it falls back to the manual Settings toggle.
        android.widget.Button overlayPerm = new android.widget.Button(this);
        overlayPerm.setOnClickListener(v -> {
            if (ReceiveUi.overlayEnabled(this)) {
                openOverlaySettings();
                return;
            }
            android.widget.Toast.makeText(this, "Granting overlay via helper…",
                    android.widget.Toast.LENGTH_SHORT).show();
            OverlayGrantHelper.requestViaHelper(this, ok -> {
                // Delivered on the main looper (RadioHelperClient guarantee) → UI-safe.
                if (ok) {
                    android.widget.Toast.makeText(this, "Overlay enabled",
                            android.widget.Toast.LENGTH_SHORT).show();
                    if (overlayPermButton != null) {
                        overlayPermButton.setText("Overlay: ON (receive shows as overlay)");
                    }
                } else {
                    android.widget.Toast.makeText(this,
                            "Couldn't grant via helper — opening Settings. "
                                    + "(Set up the helper's one-time pairing for one-tap.)",
                            android.widget.Toast.LENGTH_LONG).show();
                    openOverlaySettings();
                }
            });
        });
        root.addView(overlayPerm, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        this.overlayPermButton = overlayPerm;

        // The island is tappable on top of the status bar / over the camera ONLY when the
        // IslandA11yService is enabled. On a sideloaded build (Android 13+), the accessibility
        // toggle is blocked by "Restricted settings" until the user allows it from App info — so
        // the button opens Accessibility settings and the hint spells out the unlock steps.
        android.widget.Button island = new android.widget.Button(this);
        island.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
        });
        root.addView(island, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        this.islandButton = island;

        TextView islandHint = new TextView(this);
        islandHint.setText("Island over the camera (sideloaded build):\n"
                + "1. Settings ▸ Apps ▸ Bridge Share ▸ ⋮ ▸ Allow restricted settings\n"
                + "2. Settings ▸ Accessibility ▸ Bridge Share island ▸ turn ON\n"
                + "3. Open this app's App info ▸ tap the button above to test");
        islandHint.setTextSize(12);
        islandHint.setTextColor(Color.GRAY);
        islandHint.setPadding(0, dp(4), 0, dp(4));
        root.addView(islandHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.Button testNfc = new android.widget.Button(this);
        testNfc.setText("Test NFC animation");
        testNfc.setOnClickListener(v -> NfcTapFx.play(this));
        root.addView(testNfc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        diagButton = new android.widget.Button(this);
        diagButton.setOnClickListener(v -> {
            boolean now = !com.bridge.share.diag.DiagLog.isEnabled(this);
            com.bridge.share.diag.DiagLog.setEnabled(this, now);
            updateDiagButton();
        });
        root.addView(diagButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateDiagButton();

        batteryButton = new android.widget.Button(this);
        batteryButton.setOnClickListener(v -> {
            // ColorOS/OnePlus freezes the foreground-service process in the background;
            // exempting the app from battery optimization is the standard way to let a
            // transfer keep running after you leave the app.
            try {
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        root.addView(batteryButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateBatteryButton();

        switch (ReceivePrefs.getMode(this)) {
            case ALWAYS_ON: always.setChecked(true); break;
            case TIMED:     timed.setChecked(true); break;
            default:        off.setChecked(true); break;
        }
        updateHint(hint);

        group.setOnCheckedChangeListener((g, checkedId) -> {
            ReceivePrefs.Mode mode;
            if (checkedId == always.getId()) mode = ReceivePrefs.Mode.ALWAYS_ON;
            else if (checkedId == timed.getId()) mode = ReceivePrefs.Mode.TIMED;
            else mode = ReceivePrefs.Mode.OFF;
            ReceivePrefs.setMode(this, mode);
            // arm() dispatches by mode: ALWAYS_ON registers the OS-held wake scan only (no
            // persistent foreground service); TIMED runs the active discoverability window.
            ReceiveService.arm(this);
            updateHint(hint);
        });

        setContentView(root);
    }

    private void updateHint(TextView hint) {
        switch (ReceivePrefs.getMode(this)) {
            case ALWAYS_ON:
                hint.setText("Other devices can send to you anytime. Re-armed after reboot.");
                break;
            case TIMED:
                hint.setText("Discoverable for 10 minutes, then automatically off to save battery.");
                break;
            default:
                hint.setText("You won't receive files. Turn on to let nearby devices send to you.");
                break;
        }
    }

    private RadioButton makeOption(RadioGroup group, String label) {
        RadioButton rb = new RadioButton(this);
        rb.setText(label);
        rb.setTextSize(17);
        rb.setPadding(dp(8), dp(14), 0, dp(14));
        group.addView(rb, new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
        return rb;
    }

    /** Manual fallback: open the system "Display over other apps" toggle for this app. */
    private void openOverlaySettings() {
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /**
     * Request the dangerous runtime permissions the engine needs. Without these
     * granted, BLE advertise/scan/connect and Wi-Fi discovery silently no-op, so
     * the whole transfer path fails. Requested at launch.
     */
    private void requestNeededPermissions() {
        java.util.List<String> need = new java.util.ArrayList<>();
        // BLE scan needs location pre-31 (and is harmless to hold on 31+).
        addIfMissing(need, android.Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addIfMissing(need, android.Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(need, android.Manifest.permission.BLUETOOTH_CONNECT);
            addIfMissing(need, android.Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(need, android.Manifest.permission.NEARBY_WIFI_DEVICES);
            addIfMissing(need, android.Manifest.permission.POST_NOTIFICATIONS);
            addIfMissing(need, android.Manifest.permission.READ_MEDIA_IMAGES);
            addIfMissing(need, android.Manifest.permission.READ_MEDIA_VIDEO);
            addIfMissing(need, android.Manifest.permission.READ_MEDIA_AUDIO);
        }
        com.bridge.share.diag.DiagLog.d("MainActivity", "requesting perms: " + need);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1001);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        StringBuilder sb = new StringBuilder("perm results:");
        for (int i = 0; i < permissions.length; i++) {
            boolean granted = i < grantResults.length
                    && grantResults[i] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            sb.append("\n  ").append(permissions[i]).append(" = ").append(granted ? "GRANTED" : "DENIED");
        }
        com.bridge.share.diag.DiagLog.d("MainActivity", sb.toString());
    }

    private void addIfMissing(java.util.List<String> list, String perm) {
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            list.add(perm);
        }
    }
}
