package com.bridge.share.ui.fx.ripple;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

/**
 * Clean-room port of AOSP SystemUI
 * {@code com.android.systemui.surfaceeffects.ripple.RippleView}, restricted to
 * the CIRCLE shape (the OnePlus NFC-tap ripple). Drives a {@link RippleShader}
 * over a {@link ValueAnimator} 0&rarr;1 and paints with a hardware-accelerated
 * {@code drawCircle}.
 *
 * <p>On API &lt; 33 (no {@link android.graphics.RuntimeShader}) the shader is
 * never created and {@link #startRipple} is a graceful no-op that simply runs
 * the completion callback, so callers below 33 degrade silently.
 */
public class RippleView extends View {

    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    private final Paint ripplePaint = new Paint();

    private float centerX;
    private float centerY;
    private long duration = 1750L;

    /** Null below API 33 (RuntimeShader unavailable). */
    private RippleShader rippleShader;

    public RippleView(Context context) {
        super(context);
    }

    public RippleView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= 33;
    }

    public RippleShader getRippleShader() {
        return rippleShader;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long ms) {
        this.duration = ms;
    }

    @Override
    protected void onAttachedToWindow() {
        if (rippleShader != null) {
            rippleShader.setPixelDensity(getResources().getDisplayMetrics().density);
        }
        super.onAttachedToWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (rippleShader == null) {
            return;
        }
        if (canvas.isHardwareAccelerated()) {
            canvas.drawCircle(centerX, centerY,
                    rippleShader.getRippleSize().getCurrentWidth(), ripplePaint);
        }
    }

    public boolean rippleInProgress() {
        return animator.isRunning();
    }

    public void setCenter(float x, float y) {
        this.centerX = x;
        this.centerY = y;
        if (rippleShader != null) {
            rippleShader.setCenter(x, y);
        }
    }

    public void setColor(int argb) {
        if (rippleShader != null) {
            rippleShader.setColor(argb);
        }
    }

    public void setSparkleStrength(float s) {
        if (rippleShader != null) {
            rippleShader.setSparkleStrength(s);
        }
    }

    public void setMaxSize(float width, float height) {
        if (rippleShader != null) {
            rippleShader.getRippleSize().setMaxSize(width, height);
        }
    }

    public void setSizeAtProgresses(RippleShader.SizeAtProgress... arr) {
        if (rippleShader != null) {
            rippleShader.getRippleSize().setSizeAtProgresses(arr);
        }
    }

    /**
     * Builds the CIRCLE shader (API 33+ only) and wires it to the paint. Below
     * API 33 this is a no-op and the view never draws.
     */
    public void setupShader() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        RippleShader shader = new RippleShader();
        shader.setColor(0xFFFFFFFF);
        shader.setRawProgress(0f);
        shader.setSparkleStrength(0.3f);
        shader.setPixelDensity(getResources().getDisplayMetrics().density);
        this.rippleShader = shader;
        this.ripplePaint.setShader(shader);
    }

    /**
     * Starts the ripple. {@code onEnd} (nullable) runs when the animation
     * finishes. If the shader is unavailable (API &lt; 33) the callback runs
     * immediately.
     */
    public void startRipple(final Runnable onEnd) {
        if (rippleShader == null) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        if (animator.isRunning()) {
            return;
        }
        animator.setDuration(duration);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                long playTime = a.getCurrentPlayTime();
                float progress = (Float) a.getAnimatedValue();
                RippleShader s = rippleShader;
                if (s != null) {
                    s.setRawProgress(progress);
                    s.setDistortionStrength(1f - progress);
                    s.setTime(playTime);
                }
                invalidate();
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
}
