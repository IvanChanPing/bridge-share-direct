package com.bridge.share.ui;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;

import com.bridge.share.ui.fx.TapFeedbackSound;
import com.bridge.share.ui.fx.TapFxOverlay;

/**
 * NFC-tap visual + audio feedback entry point.
 *
 * <p>This now plays the ACTUAL OnePlus "lingguang" tap effect ported verbatim from the
 * oshare woods app ({@code com.oneplus.oshare.port.fx}) — the real {@code barglow.agsl}
 * RuntimeShader edge/bar glow ({@link TapFxOverlay}/GlowView, exact LingguangAnimator
 * envelope) + the AOSP SystemUI circle ripple (RippleShader) + the system NFC tap tune
 * (tap_start/end/error.ogg via {@link TapFeedbackSound}) and a haptic tick.
 *
 * <p>The earlier hand-drawn Canvas glow/ripple recreation was replaced with this, the
 * woods original — this is the effect behind the woods "PLAY NFC TAP EFFECT
 * (GLOW + RIPPLE + SOUND)" button. The glow + ripple are {@link android.graphics.RuntimeShader}
 * based (API 33+); on older devices they are inert no-ops but the sound + haptic still fire.
 *
 * <p>Same public signature as before, so every existing caller (the MainActivity
 * "Test NFC animation" button and {@link NfcLaunchActivity} on a real tap) gets the
 * woods effect with no further changes.
 */
public final class NfcTapFx {

    private static final String TAG = "NfcTapFx";

    /** Detach the overlay after the effect finishes (slowed glow ~3.6s) with a little margin. */
    private static final long DETACH_DELAY_MS = 3800L;

    private NfcTapFx() {
    }

    /**
     * Plays the full OnePlus woods tap effect on {@code activity}: edge/bar glow + circle
     * ripple together, the NFC "start" tap tune, and a haptic tick. Attaches a transparent,
     * non-touchable overlay to the window decor and auto-detaches when the effect ends.
     * No-op if {@code activity} is null/finishing.
     */
    public static void play(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        // Sound + haptic first (work on every API level, independent of the shader effect).
        try {
            TapFeedbackSound snd = TapFeedbackSound.get(activity);
            snd.playStart(activity);
            snd.tick(activity);
        } catch (Throwable t) {
            Log.w(TAG, "tap sound/haptic failed", t);
        }
        // Visual glow + ripple (RuntimeShader; inert below API 33).
        final TapFxOverlay fx;
        try {
            fx = TapFxOverlay.attach(activity);
            // Anchor the two camera-origin pieces (gradient blob + ripple) to THIS device's real
            // front-camera punch-hole from the DisplayCutout, falling back to a centred top
            // punch-hole (0.5, 0.027) if the cutout isn't reported.
            float[] cam = cameraAnchor(activity);
            fx.setCameraAnchor(cam[0], cam[1]);
            // Match the edge ring's corner radius to THIS device's real screen corners.
            float crPx = roundedCornerRadiusPx(activity);
            if (crPx > 0f) {
                fx.setCornerRadiusPx(crPx);
            }
            // EXACT woods "Play NFC tap effect" wiring (NfcAnimButton.playTapFx) + a slight dim:
            //   piece 1 = edge/bar glow (border)        -> playGlow()
            //   piece 2 = gradient blob on the camera   -> seeded into the glow via setCameraAnchor
            //   piece 3 = circle ripple FROM the camera -> playRippleAtFraction(cam)
            // The ripple is at the camera, NOT screen-centre (playTapFx would centre it).
            fx.playDim(3600L, 0.34f);   // darker, quick ramp in, held for the full (slowed) glow
            fx.playGlow();
            fx.playRippleAtFraction(cam[0], cam[1], null);
        } catch (Throwable t) {
            Log.w(TAG, "tap fx overlay failed", t);
            return;
        }
        new Handler(activity.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    fx.detach();
                } catch (Throwable ignored) {
                    // best-effort cleanup
                }
            }
        }, DETACH_DELAY_MS);
    }

    /**
     * Returns the front-camera punch-hole centre as {x, y} fractions (0..1) of the screen, read
     * from the real {@link DisplayCutout}. Falls back to a centred top punch-hole (0.5, 0.027)
     * when no cutout is reported (e.g. the window isn't laid out yet, or no cutout hardware).
     */
    private static float[] cameraAnchor(Activity activity) {
        float fx = 0.5f;
        float fy = 0.027f;
        try {
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            if (decor != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowInsets insets = decor.getRootWindowInsets();
                DisplayCutout cutout = insets != null ? insets.getDisplayCutout() : null;
                if (cutout != null && !cutout.getBoundingRects().isEmpty()) {
                    Rect r = cutout.getBoundingRects().get(0);
                    DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                    float w = decor.getWidth() > 0 ? decor.getWidth() : dm.widthPixels;
                    float h = decor.getHeight() > 0 ? decor.getHeight() : dm.heightPixels;
                    if (w > 0f && h > 0f) {
                        fx = r.exactCenterX() / w;
                        fy = r.exactCenterY() / h;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cameraAnchor: falling back to (0.5, 0.027)", t);
        }
        return new float[]{fx, fy};
    }

    /**
     * Returns the device's real screen corner radius in PIXELS from the top-left
     * {@link RoundedCorner} (API 31+), or -1 if not reported (older API, no rounded display, or the
     * window isn't laid out yet) — in which case the shader keeps its seeded default radius.
     */
    private static float roundedCornerRadiusPx(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                View decor = activity.getWindow() != null
                        ? activity.getWindow().getDecorView() : null;
                WindowInsets insets = decor != null ? decor.getRootWindowInsets() : null;
                if (insets != null) {
                    RoundedCorner rc = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
                    if (rc != null && rc.getRadius() > 0) {
                        return rc.getRadius();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "roundedCornerRadiusPx: using shader default", t);
        }
        return -1f;
    }
}
