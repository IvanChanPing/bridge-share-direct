package com.bridge.share.ui.fx.ripple;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Color;

/**
 * Clean-room port of AOSP SystemUI
 * {@code com.android.systemui.surfaceeffects.ripple.RippleAnimation}, CIRCLE
 * only, driving a {@link RippleShader} from a {@link RippleAnimationConfig}.
 *
 * <p>The only behavioural deviation from the original is that AOSP applies the
 * opacity via {@code androidx.core.graphics.ColorUtils.setAlphaComponent};
 * that support library is not on this app's classpath, so the equivalent is
 * computed with the platform {@link android.graphics.Color} instead.
 *
 * <p>{@link RuntimeShader} is API 33+; only construct this when
 * {@code Build.VERSION.SDK_INT >= 33}.
 */
public final class RippleAnimation {

    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    private final RippleAnimationConfig config;
    private final RippleShader rippleShader;

    public RippleAnimation(RippleAnimationConfig config) {
        this.config = config;
        this.rippleShader = new RippleShader();
        applyConfigToShader();
    }

    private static void assignFadeParams(RippleShader.FadeParams dst, RippleShader.FadeParams src) {
        if (src != null) {
            dst.fadeInStart = src.fadeInStart;
            dst.fadeInEnd = src.fadeInEnd;
            dst.fadeOutStart = src.fadeOutStart;
            dst.fadeOutEnd = src.fadeOutEnd;
        }
    }

    private static int setAlphaComponent(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public void applyConfigToShader() {
        rippleShader.setCenter(config.centerX, config.centerY);
        rippleShader.getRippleSize().setMaxSize(config.maxWidth, config.maxHeight);
        rippleShader.setPixelDensity(config.pixelDensity);
        rippleShader.setColor(setAlphaComponent(config.color, config.opacity));
        rippleShader.setSparkleStrength(config.sparkleStrength);
        assignFadeParams(rippleShader.getBaseRingFadeParams(), config.baseRingFadeParams);
        assignFadeParams(rippleShader.getSparkleRingFadeParams(), config.sparkleRingFadeParams);
        assignFadeParams(rippleShader.getCenterFillFadeParams(), config.centerFillFadeParams);
    }

    public RippleShader getRippleShader() {
        return rippleShader;
    }

    public boolean isPlaying() {
        return animator.isRunning();
    }

    public void play(final Runnable onEnd) {
        if (animator.isRunning()) {
            return;
        }
        animator.setDuration(config.duration);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                long playTime = a.getCurrentPlayTime();
                float progress = (Float) a.getAnimatedValue();
                rippleShader.setRawProgress(progress);
                rippleShader.setDistortionStrength(config.shouldDistort ? 1f - progress : 0f);
                rippleShader.setTime(playTime);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
        animator.start();
    }

    public void updateColor(int color) {
        config.color = color;
        applyConfigToShader();
    }
}
