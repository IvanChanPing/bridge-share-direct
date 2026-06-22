package com.bridge.share.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A circular avatar: a filled blue disc with a centered glyph, ringed by a
 * progress arc (12 o'clock origin, sweeps clockwise). Used for each discovered
 * device in the send bottom sheet; the ring tracks transfer progress.
 */
public class RingProgressView extends View {

    private static final int ACCENT = 0xFF2F8BFF;     // blue disc
    private static final int TRACK  = 0x33FFFFFF;     // faint ring track
    private static final int RING    = 0xFFFFFFFF;    // progress arc (white)

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    private static final int GREEN = 0xFF00BD13;
    private int progress = -1; // -1 = no ring shown (idle); 0..100 shows the arc
    private String glyph = "📱"; // phone emoji as default glyph
    private boolean complete = false;
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RingProgressView(Context ctx) {
        super(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        discPaint.setColor(ACCENT);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(3 * density);
        trackPaint.setColor(TRACK);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3 * density);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setColor(RING);
        glyphPaint.setColor(Color.WHITE);
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        tickPaint.setColor(Color.WHITE);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setStrokeWidth(4 * density);
    }

    /** Morph the icon into a green disc with a white check (transfer complete). */
    public void setComplete() {
        this.complete = true;
        invalidate();
    }

    public void setProgress(int percent) {
        this.progress = percent;
        invalidate();
    }

    public void setGlyph(String g) {
        this.glyph = g;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float ringInset = ringPaint.getStrokeWidth();
        float discR = Math.min(w, h) / 2f - ringInset * 2f;

        if (complete) {
            discPaint.setColor(GREEN);
            c.drawCircle(cx, cy, discR, discPaint);
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(cx - discR * 0.40f, cy + discR * 0.02f);
            p.lineTo(cx - discR * 0.08f, cy + discR * 0.32f);
            p.lineTo(cx + discR * 0.44f, cy - discR * 0.30f);
            c.drawPath(p, tickPaint);
            return;
        }

        c.drawCircle(cx, cy, discR, discPaint);

        glyphPaint.setTextSize(discR);
        float ty = cy - (glyphPaint.descent() + glyphPaint.ascent()) / 2f;
        c.drawText(glyph, cx, ty, glyphPaint);

        if (progress >= 0) {
            float r = Math.min(w, h) / 2f - ringInset;
            arc.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(arc, 0, 360, false, trackPaint);
            c.drawArc(arc, -90, 360f * Math.max(0, Math.min(100, progress)) / 100f, false, ringPaint);
        }
    }
}
