package com.bridge.share.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A clean rounded determinate progress bar for the receive card: a pill-shaped
 * light track with a blue rounded fill that animates smoothly between values.
 * Nicer than the stock {@link android.widget.ProgressBar} for the Quick-Share look.
 */
public class RoundedProgressBar extends View {

    private static final int TRACK = 0x22000000;
    private static final int FILL  = 0xFF0A84FF;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float fraction = 0f; // 0..1 currently drawn
    private ValueAnimator animator;

    public RoundedProgressBar(Context ctx) {
        super(ctx);
        trackPaint.setColor(TRACK);
        fillPaint.setColor(FILL);
    }

    /** Animate to the given percent (0..100). */
    public void setProgress(int percent) {
        float target = Math.max(0, Math.min(100, percent)) / 100f;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(fraction, target);
        animator.setDuration(180);
        animator.addUpdateListener(a -> { fraction = (float) a.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = (int) (6 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, r, r, trackPaint);
        float fw = Math.max(h, w * fraction); // at least a dot so it reads as "started"
        rect.set(0, 0, fw, h);
        c.drawRoundRect(rect, r, r, fillPaint);
    }
}
