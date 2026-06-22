package com.bridge.share.ui.fx;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.bridge.share.ui.fx.ripple.RippleView;

/**
 * Single public entry point for the OnePlus NFC-tap visual effects:
 * <ol>
 *   <li>an expanding {@link RippleView} CIRCLE ripple, and</li>
 *   <li>an edge/bar {@link GlowView} "lingguang" glow.</li>
 * </ol>
 *
 * <p>It hosts both effect views in a transparent, non-touchable overlay
 * {@link FrameLayout} added to the host {@link Activity}'s content view (the
 * android.R.id.content frame), so callers do not have to add anything to their
 * own layout.
 *
 * <p>All work uses public Android APIs only (View, FrameLayout, RuntimeShader,
 * ValueAnimator). There are no OEM / privileged / {@code com.oplus.*} calls.
 *
 * <p>{@link android.graphics.RuntimeShader} is API 33+. On older devices the
 * effect views are inert no-ops, so {@link #playRipple}, {@link #playGlow} and
 * {@link #playTapFx} simply do nothing visible (and run their callbacks).
 *
 * <p>Typical use on a successful NFC tap / connect:
 * <pre>
 *   TapFxOverlay fx = TapFxOverlay.attach(activity);
 *   fx.playTapFx();          // glow + a centered ripple together
 *   // ... or target a specific view:
 *   fx.playRipple(connectButton);
 *   // when the screen is torn down:
 *   fx.detach();
 * </pre>
 */
public final class TapFxOverlay {

    private final FrameLayout overlay;
    private final View dimView;
    private final GlowView glowView;
    private final RippleView rippleView;

    private TapFxOverlay(FrameLayout overlay, View dimView, GlowView glowView, RippleView rippleView) {
        this.overlay = overlay;
        this.dimView = dimView;
        this.glowView = glowView;
        this.rippleView = rippleView;
    }

    /**
     * Builds the overlay and attaches it to {@code activity}'s content frame.
     * The overlay is transparent and does not intercept touches.
     */
    public static TapFxOverlay attach(Activity activity) {
        Context ctx = activity;
        FrameLayout overlay = new FrameLayout(ctx);
        overlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Don't steal touches from the underlying UI.
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setBackgroundColor(Color.TRANSPARENT);

        GlowView glowView = new GlowView(ctx);
        glowView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        RippleView rippleView = new RippleView(ctx);
        rippleView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rippleView.setupShader();

        // Slight screen dim BEHIND the glow + ripple, so the bright effects pop against a gently
        // darkened screen. Alpha is animated 0 -> max -> 0 by playDim().
        View dimView = new View(ctx);
        dimView.setBackgroundColor(Color.BLACK);
        dimView.setAlpha(0f);
        dimView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay.addView(dimView);     // back-most: the dim scrim
        overlay.addView(glowView);    // edge glow above the dim
        overlay.addView(rippleView);  // ripple on top

        // Add to the window's decorView (full screen, spans UNDER the status/nav bars)
        // so the edge glow reaches the TRUE screen edges, not the inset content frame.
        View decor = (activity.getWindow() != null)
                ? activity.getWindow().getDecorView() : null;
        if (decor instanceof ViewGroup) {
            ((ViewGroup) decor).addView(overlay);
        } else {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content != null) {
                content.addView(overlay);
            }
        }
        return new TapFxOverlay(overlay, dimView, glowView, rippleView);
    }

    /**
     * Fades a slight black scrim in then out behind the effect so the glow/ripple pop against a
     * gently darkened screen. {@code maxAlpha} is the peak dim.
     * Fades in QUICKLY over the first 8%, HOLDS dark, fades out over only the last 18% of
     * {@code durationMs} (so the screen stays darkened for most of the effect).
     */
    public void playDim(long durationMs, final float maxAlpha) {
        if (dimView == null) {
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(durationMs);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                float p = (Float) va.getAnimatedValue();
                float env;
                if (p < 0.08f) {
                    env = p / 0.08f;               // quick fade in
                } else if (p > 0.82f) {
                    env = 1f - (p - 0.82f) / 0.18f; // fade out only at the very end
                } else {
                    env = 1f;                       // hold dark
                }
                if (env < 0f) {
                    env = 0f;
                } else if (env > 1f) {
                    env = 1f;
                }
                dimView.setAlpha(maxAlpha * env);
            }
        });
        a.start();
    }

    /** Anchors the glow's gradient blob to the front-camera punch-hole (fractions 0..1). */
    public void setCameraAnchor(float fx, float fy) {
        glowView.setCameraAnchor(fx, fy);
    }

    /** Sets the edge ring's corner radius from the device's real screen corner radius, in pixels. */
    public void setCornerRadiusPx(float px) {
        glowView.setCornerRadiusPx(px);
    }

    /**
     * Plays the expanding CIRCLE ripple centered on {@code anchor} (mapped into
     * overlay coordinates). If {@code anchor} is null the ripple is centered on
     * the overlay.
     */
    public void playRipple(View anchor) {
        playRipple(anchor, null);
    }

    /** {@link #playRipple(View)} with a completion callback. */
    public void playRipple(View anchor, Runnable onEnd) {
        final float cx;
        final float cy;
        int overlayW = overlay.getWidth();
        int overlayH = overlay.getHeight();
        if (anchor != null && overlayW > 0 && overlayH > 0) {
            int[] aLoc = new int[2];
            int[] oLoc = new int[2];
            anchor.getLocationOnScreen(aLoc);
            overlay.getLocationOnScreen(oLoc);
            cx = (aLoc[0] - oLoc[0]) + anchor.getWidth() * 0.5f;
            cy = (aLoc[1] - oLoc[1]) + anchor.getHeight() * 0.5f;
        } else {
            cx = overlayW * 0.5f;
            cy = overlayH * 0.5f;
        }

        // Size the ripple to comfortably cover the overlay from the center.
        float maxDim = Math.max(overlayW, overlayH);
        if (maxDim <= 0f) {
            // Not laid out yet; fall back to a screen-diagonal estimate.
            DisplayMetrics dm = overlay.getResources().getDisplayMetrics();
            maxDim = Math.max(dm.widthPixels, dm.heightPixels);
        }
        final float size = maxDim * 1.5f;

        rippleView.setColor(0xFFFFFFFF);
        rippleView.setSparkleStrength(0.3f);
        rippleView.setCenter(cx, cy);
        rippleView.setMaxSize(size, size);
        rippleView.setDuration(1750L);
        rippleView.startRipple(onEnd);
    }

    /**
     * Plays the expanding ripple centered at a FRACTION of the overlay (0..1 in each
     * axis) — e.g. (0.5, 0.027) for the front-camera punch-hole. Falls back to the
     * display metrics if the overlay is not laid out yet.
     */
    public void playRippleAtFraction(float fx, float fy, Runnable onEnd) {
        float w = overlay.getWidth();
        float h = overlay.getHeight();
        if (w <= 0f || h <= 0f) {
            DisplayMetrics dm = overlay.getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        float cx = w * fx;
        float cy = h * fy;
        float size = Math.max(w, h) * 1.5f;
        rippleView.setColor(0xFFFFFFFF);
        rippleView.setSparkleStrength(0.3f);
        rippleView.setCenter(cx, cy);
        rippleView.setMaxSize(size, size);
        rippleView.setDuration(1750L);
        rippleView.startRipple(onEnd);
    }

    /** Plays the edge/bar glow once. */
    public void playGlow() {
        playGlow(null);
    }

    /** {@link #playGlow()} with a completion callback. */
    public void playGlow(Runnable onEnd) {
        glowView.play(onEnd);
    }

    /**
     * Plays the full tap effect: the edge glow and a centered ripple together.
     */
    public void playTapFx() {
        playGlow();
        playRipple(null);
    }

    /** True when the running device supports the shader-based effects (API 33+). */
    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= 33 && rippleView.isSupported() && glowView.isSupported();
    }

    /** Detaches the overlay and releases shader resources. */
    public void detach() {
        glowView.release();
        if (overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
    }
}
