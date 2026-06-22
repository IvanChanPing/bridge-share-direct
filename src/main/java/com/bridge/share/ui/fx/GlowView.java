package com.bridge.share.ui.fx;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reproduces the OnePlus SystemUI edge/bar "lingguang" glow effect by loading
 * the real {@code barglow.agsl} shader (copied verbatim from SystemUI assets
 * into this app's {@code assets/}) into a {@link RuntimeShader} and animating
 * the same uniforms that {@code com.effects.lingguang.LingguangEffect} +
 * {@code LingguangAnimator} drive.
 *
 * <p><b>Static uniforms</b> are seeded from the exact defaults in
 * {@code LingguangEffect.initializeOliveLink}:
 * <ul>
 *   <li>{@code paraboladims} = (glowWidth 0.16, baseBend 0.0, glowThickness 0.002)</li>
 *   <li>{@code glowcolor} = (1,1,1,1)</li>
 *   <li>{@code center} = (0.5, 1 - 0.23)  [glowCenter y is flipped, per source]</li>
 *   <li>{@code glowparams} = (chromaticOffset 0.005, glowIntensity 0.6)</li>
 *   <li>chromatic up/down shift colors for the Red&amp;Blue channel split</li>
 * </ul>
 *
 * <p><b>Animated uniforms</b> ({@code bar_alpha}, {@code glow_alpha},
 * {@code gradient_alpha}, {@code bend}, {@code offsets_bar_gradient}) follow the
 * LingguangAnimator two-phase envelope: a fade-in (500ms) to the entrance/
 * activation peak (bar 0.9, glow 0.7, gradient 0.9) then a deactivation
 * fade-out (750ms) back to 0, with the bar bending and the gradient sliding.
 *
 * <p>The gradient texture is {@code glow.png} (copied alongside the shaders),
 * bound as {@code texIn}. AOSP's LingguangEffect binds a {@code gradient.png};
 * this app ships {@code glow.png} as the texture per the task, so it is used as
 * the {@code texIn} input and {@code texIn_dims} is set from its size.
 *
 * <p>{@link RuntimeShader} is API 33+. On older devices this view is an inert
 * no-op: it never builds the shader, never draws, and {@link #play} immediately
 * runs the completion callback.
 */
public class GlowView extends View {

    private static final String TAG = "GlowView";

    // LingguangEffect.initializeOliveLink defaults.
    private static final float UV_GLOW_WIDTH = 0.16f;
    private static final float UV_BASE_BEND = 0.0f;
    private static final float UV_GLOW_THICKNESS = 0.002f;
    private static final float GLOW_INTENSITY = 0.6f;
    private static final float CHROMATIC_OFFSET = 0.005f;
    private static final float GLOW_CENTER_X = 0.5f;
    private static final float GLOW_CENTER_Y = 0.23f;

    // ---- LingguangAnimator EXACT envelope (verified LingguangAnimator.initializeOliveLink) ----
    // Two stages, each split into two halves. The OEM uses linear ValueAnimators; the
    // easing is encoded by the start/mid/end keyframes, so we drive one linear master
    // timeline and piecewise-interpolate each value across the four segment boundaries.
    // Durations are the OEM values x SLOWDOWN so the dim->bright pulse plays back slower (user request).
    // OEM base: 500 / 250 / 400 / 750 = 1900ms total. At 1.9x that's ~3610ms.
    private static final float SLOWDOWN = 1.9f;
    private static final long D_FADE_IN  = (long) (500L * SLOWDOWN);  // stage-1 first half  (fadeInDuration)
    private static final long D_FADE_OUT = (long) (250L * SLOWDOWN);  // stage-1 second half (fadeOutDuration)
    private static final long D_ACT      = (long) (400L * SLOWDOWN);  // stage-2 first half  (barActivationDuration)
    private static final long D_DEACT    = (long) (750L * SLOWDOWN);  // stage-2 second half (barDeactivationDuration)
    private static final long T1 = D_FADE_IN;
    private static final long T2 = T1 + D_FADE_OUT;
    private static final long T3 = T2 + D_ACT;
    private static final long T4 = T3 + D_DEACT;              // total (~3610ms at 1.9x)

    // Bar alpha keyframes: entrance 0.65->0.9->0.65, activation 0.65->0.7->0.0.
    private static final float BAR_A_START = 0.65f, BAR_A_ENTR_MID = 0.9f, BAR_A_ACT_MID = 0.7f;
    // Bar-glow alpha keyframes: entrance 0->0.9->0, activation 0->0.7->0.
    private static final float GLOW_A_ENTR_MID = 0.9f, GLOW_A_ACT_MID = 0.7f;
    // Gradient alpha: stage-2 only, 0->0.9->0.
    private static final float GRAD_A_MID = 0.9f;
    // Bar vertical offset: entrance 0.0->0.07, activation 0.07->0.15.
    private static final float BAR_OFF_ENTR_END = 0.07f, BAR_OFF_ACT_END = 0.15f;
    // Gradient vertical offset over stage 2: 0.015->0.3.
    private static final float GRAD_OFF_START = 0.015f, GRAD_OFF_END = 0.3f;
    // Bend added on top of baseBend over stage 2: 0.0015->0.035.
    private static final float BEND_START = 0.0015f, BEND_END = 0.035f;

    private final Paint paint = new Paint();

    private RuntimeShader barGlowShader; // null below API 33
    private Bitmap gradientTexture;
    private AnimatorSet animatorSet;
    private float cornerRadiusPx = -1f;  // device screen corner radius (px); <=0 = use seeded default

    // Current animated values.
    private float barAlpha = 0f;
    private float glowAlpha = 0f;
    private float gradientAlpha = 0f;
    private float bend = 0f;
    private float barGlowOffsetY = 0f;
    private float gradientOffsetY = 0f;
    private float shimmerTimeSec = 0f;   // elapsed seconds -> shader shimmer (per-position brightness)

    public GlowView(Context context) {
        super(context);
        init(context);
    }

    public GlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= 33;
    }

    private void init(Context context) {
        setWillNotDraw(false);
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            String src = readAsset(context, "barglow.agsl");
            barGlowShader = new RuntimeShader(src);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load barglow.agsl; glow disabled", t);
            barGlowShader = null;
            return;
        }
        try {
            // OEM LingguangEffect binds gradient.png (NOT glow.png) as texIn
            // (LingguangEffect.java:106). Fall back to glow.png if gradient.png is absent.
            gradientTexture = readBitmap(context, "gradient.png");
            if (gradientTexture == null) {
                gradientTexture = readBitmap(context, "glow.png");
            }
            if (gradientTexture != null) {
                Shader.TileMode clamp = Shader.TileMode.CLAMP;
                BitmapShader bs = new BitmapShader(gradientTexture, clamp, clamp);
                barGlowShader.setInputShader("texIn", bs);
                barGlowShader.setFloatUniform("texIn_dims",
                        gradientTexture.getWidth(), gradientTexture.getHeight());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load gradient.png texture", t);
        }
        seedStaticUniforms();
        paint.setShader(barGlowShader);
    }

    /** Seeds the non-animated uniforms with the LingguangEffect defaults. */
    private void seedStaticUniforms() {
        if (barGlowShader == null) {
            return;
        }
        // paraboladims = {width, baseBend, thickness}
        barGlowShader.setFloatUniform("paraboladims",
                UV_GLOW_WIDTH, UV_BASE_BEND, UV_GLOW_THICKNESS);
        barGlowShader.setFloatUniform("glowcolor", 1f, 1f, 1f, 1f);
        // center.y is flipped in the source: center = (x, 1 - y)
        barGlowShader.setFloatUniform("center", GLOW_CENTER_X, 1f - GLOW_CENTER_Y);
        // glowparams = {chromatic offset, glow intensity}
        barGlowShader.setFloatUniform("glowparams", CHROMATIC_OFFSET, GLOW_INTENSITY);
        // Red&Blue chromatic split (LingguangEffect default channels = RedAndBlue):
        // upShift pushes red, downShift pushes blue (center keeps green).
        barGlowShader.setFloatUniform("upShiftColor", 1f, 0f, 0f, 1f);
        barGlowShader.setFloatUniform("downShiftColor", 0f, 0f, 1f, 1f);
        // Camera punch-hole anchor for the gradient blob — default centred top (0.5, 0.027);
        // overridden at runtime by setCameraAnchor() from the real DisplayCutout.
        barGlowShader.setFloatUniform("cameraCenter", GLOW_CENTER_X, 0.027f);
        barGlowShader.setFloatUniform("cameraSize", 0.16f);
        // Corner radius default (uv-height units); overridden by setCornerRadiusPx() from the real
        // device RoundedCorner. 0.05 * height is a sane fallback if no rounded-corner is reported.
        barGlowShader.setFloatUniform("cornerRadius", 0.05f);
        barGlowShader.setFloatUniform("shimmerTime", 0f);
    }

    /**
     * Anchors the soft gradient blob to the front-camera punch-hole, as fractions (0..1) of the
     * view. Pass the real DisplayCutout centre so the blob lands on THIS device's camera.
     */
    public void setCameraAnchor(float fx, float fy) {
        if (barGlowShader == null) {
            return;
        }
        barGlowShader.setFloatUniform("cameraCenter", fx, fy);
        invalidate();
    }

    /**
     * Sets the ring's corner radius from the device's REAL screen corner radius in PIXELS
     * (WindowInsets.getRoundedCorner). Converted to the shader's uv-height units (px / viewHeight)
     * so the ring corners match the actual screen corners exactly. Re-applied on size changes.
     */
    public void setCornerRadiusPx(float px) {
        this.cornerRadiusPx = px;
        applyCornerRadius(getHeight());
    }

    private void applyCornerRadius(int h) {
        if (barGlowShader == null || cornerRadiusPx <= 0f || h <= 0) {
            return;
        }
        barGlowShader.setFloatUniform("cornerRadius", cornerRadiusPx / (float) h);
        invalidate();
    }

    private void pushAnimatedUniforms() {
        if (barGlowShader == null) {
            return;
        }
        barGlowShader.setFloatUniform("glow_alpha", glowAlpha);
        barGlowShader.setFloatUniform("bar_alpha", barAlpha);
        barGlowShader.setFloatUniform("gradient_alpha", gradientAlpha);
        barGlowShader.setFloatUniform("bend", bend);
        // offsets_bar_gradient = (bar.x, bar.y, gradient.x, gradient.y)
        barGlowShader.setFloatUniform("offsets_bar_gradient",
                0f, barGlowOffsetY, 0f, gradientOffsetY);
        barGlowShader.setFloatUniform("shimmerTime", shimmerTimeSec);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (barGlowShader != null) {
            barGlowShader.setFloatUniform("viewport", (float) w, (float) h);
            applyCornerRadius(h);
            pushAnimatedUniforms();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (barGlowShader == null) {
            return;
        }
        pushAnimatedUniforms();
        canvas.drawPaint(paint);
    }

    /**
     * Plays one entrance&rarr;activation&rarr;deactivation glow cycle. Below API
     * 33 this immediately runs {@code onEnd} and does nothing else.
     */
    public void play(final Runnable onEnd) {
        if (barGlowShader == null) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        cancel();

        // One linear master timeline over the full 1900ms (500+250+400+750); each
        // animated value is piecewise-interpolated across the four OEM segment
        // boundaries by applyAt(), matching LingguangAnimator's keyframes exactly.
        ValueAnimator master = ValueAnimator.ofFloat(0f, (float) T4);
        master.setDuration(T4);
        master.setInterpolator(null); // linear, like the OEM ValueAnimators
        master.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                applyAt((Float) a.getAnimatedValue());
                invalidate();
            }
        });

        animatorSet = new AnimatorSet();
        animatorSet.play(master);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                barAlpha = glowAlpha = gradientAlpha = 0f;
                bend = barGlowOffsetY = gradientOffsetY = 0f;
                invalidate();
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
        animatorSet.start();
    }

    /** Piecewise-linear evaluation of every animated value at master-time {@code t} (ms). */
    private void applyAt(float t) {
        shimmerTimeSec = t / 1000f;  // feed elapsed seconds to the shader's per-position shimmer
        // Bar alpha: entrance 0.65->0.9->0.65, activation 0.65->0.7->0.0.
        if (t <= T1)      barAlpha = lerp(t, 0L, T1, BAR_A_START, BAR_A_ENTR_MID);
        else if (t <= T2) barAlpha = lerp(t, T1, T2, BAR_A_ENTR_MID, BAR_A_START);
        else if (t <= T3) barAlpha = lerp(t, T2, T3, BAR_A_START, BAR_A_ACT_MID);
        else              barAlpha = lerp(t, T3, T4, BAR_A_ACT_MID, 0f);

        // Bar-glow alpha: entrance 0->0.9->0, activation 0->0.7->0.
        if (t <= T1)      glowAlpha = lerp(t, 0L, T1, 0f, GLOW_A_ENTR_MID);
        else if (t <= T2) glowAlpha = lerp(t, T1, T2, GLOW_A_ENTR_MID, 0f);
        else if (t <= T3) glowAlpha = lerp(t, T2, T3, 0f, GLOW_A_ACT_MID);
        else              glowAlpha = lerp(t, T3, T4, GLOW_A_ACT_MID, 0f);

        // Gradient alpha: stage-2 only, 0->0.9->0.
        if (t <= T2)      gradientAlpha = 0f;
        else if (t <= T3) gradientAlpha = lerp(t, T2, T3, 0f, GRAD_A_MID);
        else              gradientAlpha = lerp(t, T3, T4, GRAD_A_MID, 0f);

        // Bar vertical offset: entrance 0->0.07, hold, activation 0.07->0.15 over stage 2.
        // Stored NEGATED to match the OEM update listeners (value * -1).
        float barOff;
        if (t <= T1)      barOff = lerp(t, 0L, T1, 0f, BAR_OFF_ENTR_END);
        else if (t <= T2) barOff = BAR_OFF_ENTR_END;
        else              barOff = lerp(t, T2, T4, BAR_OFF_ENTR_END, BAR_OFF_ACT_END);
        barGlowOffsetY = -barOff;

        // Gradient vertical offset: stage-2 only, 0.015->0.3 (negated).
        float gradOff = (t <= T2) ? 0f : lerp(t, T2, T4, GRAD_OFF_START, GRAD_OFF_END);
        gradientOffsetY = -gradOff;

        // Bend on top of baseBend: stage-2 only, 0.0015->0.035.
        bend = (t <= T2) ? 0f : lerp(t, T2, T4, BEND_START, BEND_END);
    }

    private static float lerp(float t, long t0, long t1, float v0, float v1) {
        if (t1 <= t0) return v1;
        float f = (t - t0) / (float) (t1 - t0);
        if (f < 0f) f = 0f;
        else if (f > 1f) f = 1f;
        return v0 + (v1 - v0) * f;
    }

    /** Cancels any running glow animation. */
    public void cancel() {
        if (animatorSet != null) {
            animatorSet.cancel();
            animatorSet = null;
        }
    }

    /** Releases the gradient bitmap. Safe to call multiple times. */
    public void release() {
        cancel();
        paint.setShader(null);
        barGlowShader = null;
        if (gradientTexture != null) {
            try {
                gradientTexture.recycle();
            } catch (Throwable ignored) {
                // best-effort
            }
            gradientTexture = null;
        }
    }

    private static String readAsset(Context ctx, String name) throws IOException {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    private static Bitmap readBitmap(Context ctx, String name) throws IOException {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            return BitmapFactory.decodeStream(in);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }
}
