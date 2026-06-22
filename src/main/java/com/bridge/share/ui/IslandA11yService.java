package com.bridge.share.ui;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Minimal AccessibilityService whose ONLY job is to host the receive "island"
 * ({@link OverlayCard}) in a {@code TYPE_ACCESSIBILITY_OVERLAY} window so the pill is
 * tappable on top of the status bar / over the camera (a normal TYPE_APPLICATION_OVERLAY
 * is forced below the status bar, which steals the touches). This is the same mechanism
 * the real Dynamic-Island apps (e.g. DynamicSpot) use.
 *
 * Pattern matches a verified working example (github.com/thbecker/android-accessibility-overlay):
 * the WindowManager is obtained from the SERVICE context in {@link #onServiceConnected()},
 * and the overlay is added with TYPE_ACCESSIBILITY_OVERLAY. This service does NOT read window
 * content (canRetrieveWindowContent=false) and ignores all events — it is purely the window host.
 *
 * DiagLog is intentionally chatty here so the collector log shows, on a real device, whether the
 * service actually connects (i.e. was successfully enabled past Restricted Settings) and whether
 * the overlay add succeeds — we were shipping this blind before.
 */
public final class IslandA11yService extends AccessibilityService {

    private static final String TAG = "IslandA11y";

    private static volatile IslandA11yService sInstance;

    /** The connected service instance, or null if the user hasn't enabled it. */
    public static IslandA11yService get() { return sInstance; }
    public static boolean isConnected() { return sInstance != null; }

    private WindowManager wm;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        com.bridge.share.diag.DiagLog.init(this);
        // getSystemService on the SERVICE context (the verified pattern), NOT applicationContext.
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        sInstance = this;
        com.bridge.share.diag.DiagLog.d(TAG, "onServiceConnected -> island host READY (service enabled)");
    }

    /**
     * Add {@code view} in a TYPE_ACCESSIBILITY_OVERLAY window using THIS service's WindowManager.
     * The caller sets type/flags on {@code lp}. Returns false on failure (logged).
     */
    public boolean addOverlay(View view, WindowManager.LayoutParams lp) {
        try {
            wm.addView(view, lp);
            com.bridge.share.diag.DiagLog.d(TAG, "addOverlay OK (TYPE_ACCESSIBILITY_OVERLAY)");
            return true;
        } catch (Exception e) {
            com.bridge.share.diag.DiagLog.d(TAG, "addOverlay FAILED: " + e);
            return false;
        }
    }

    public void removeOverlay(View view) {
        try { if (wm != null) wm.removeView(view); } catch (Exception ignored) {}
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* host only: ignore */ }
    @Override public void onInterrupt() { /* host only: nothing to interrupt */ }

    @Override
    public boolean onUnbind(Intent intent) {
        com.bridge.share.diag.DiagLog.d(TAG, "onUnbind -> island host gone (service disabled)");
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }
}
