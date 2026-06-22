package com.bridge.share.ui.fx.ripple;

/**
 * Plain-data config for a single CIRCLE ripple run, adapted from AOSP SystemUI
 * {@code com.android.systemui.surfaceeffects.ripple.RippleAnimationConfig}.
 * Drops the {@code RippleShape} field (CIRCLE only) and exposes only the knobs
 * the OnePlus NFC-tap effect uses.
 */
public final class RippleAnimationConfig {

    public long duration = 1750L;
    public float centerX = 0f;
    public float centerY = 0f;
    public float maxWidth = 0f;
    public float maxHeight = 0f;
    public float pixelDensity = 1f;
    /** Base color, RGB; alpha is applied separately via {@link #opacity}. */
    public int color = 0xFFFFFFFF;
    /** 0..255 alpha applied on top of {@link #color}. */
    public int opacity = 255;
    public float sparkleStrength = 0.3f;
    public boolean shouldDistort = true;

    public RippleShader.FadeParams baseRingFadeParams;
    public RippleShader.FadeParams sparkleRingFadeParams;
    public RippleShader.FadeParams centerFillFadeParams;

    public RippleAnimationConfig() {
    }

    public RippleAnimationConfig(long duration, float centerX, float centerY,
            float maxWidth, float maxHeight, float pixelDensity, int color, int opacity,
            float sparkleStrength, boolean shouldDistort) {
        this.duration = duration;
        this.centerX = centerX;
        this.centerY = centerY;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.pixelDensity = pixelDensity;
        this.color = color;
        this.opacity = opacity;
        this.sparkleStrength = sparkleStrength;
        this.shouldDistort = shouldDistort;
    }
}
