package com.bridge.share.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * The Quick-Share-style receive card UI, shared by BOTH presentations:
 *  - {@link ReceiveBottomSheetActivity} (default, no overlay permission), and
 *  - {@link OverlayReceiver} (TYPE_APPLICATION_OVERLAY, when overlay permission is on).
 *
 * Builds the draggable card and exposes the accept→progress→complete states so
 * the two hosts share identical visuals/animation. The host supplies
 * {@link Actions} (button taps) and a dismiss hook on the card itself.
 */
public final class ReceiveCard {

    public interface Actions {
        void onAccept();
        void onDeclineOrClose();
        void onOpen();
    }

    public final DraggableSheetLayout view;
    private final Context ctx;
    private final TextView title, subtitle, btnLeft, btnRight;
    private final CheckOverlay check;
    private final LinearLayout row;
    private final View rowDivider;
    private final RoundedProgressBar progress;
    private final Actions actions;

    public ReceiveCard(Context ctx, Actions actions) {
        this.ctx = ctx;
        this.actions = actions;

        view = new DraggableSheetLayout(ctx);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF4F4F7);
        bg.setCornerRadius(dp(28));
        view.setBackground(bg);
        int pad = dp(22);
        view.setPadding(pad, pad, pad, pad);

        title = new TextView(ctx);
        title.setText("Bridge Share");
        title.setTextColor(0xFF111114);
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        view.addView(title);

        subtitle = new TextView(ctx);
        subtitle.setTextColor(0xFF6A6A70);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, dp(16));
        view.addView(subtitle);

        FrameLayout thumbWrap = new FrameLayout(ctx);
        int ts = dp(120);
        View thumb = new View(ctx);
        GradientDrawable td = new GradientDrawable();
        td.setColor(0xFFB9C2D0);
        td.setCornerRadius(dp(18));
        thumb.setBackground(td);
        thumbWrap.addView(thumb, new FrameLayout.LayoutParams(ts, ts));
        check = new CheckOverlay(ctx);
        check.setVisibility(View.GONE);
        thumbWrap.addView(check, new FrameLayout.LayoutParams(ts, ts));
        view.addView(thumbWrap, new LinearLayout.LayoutParams(ts, ts));

        progress = new RoundedProgressBar(ctx);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ts, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(16);
        view.addView(progress, plp);

        rowDivider = new View(ctx);
        rowDivider.setBackgroundColor(0x14000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(18);
        view.addView(rowDivider, dlp);

        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnLeft = textButton("Decline", 0xFF6A6A70);
        btnRight = textButton("Accept", 0xFF0A84FF);
        btnLeft.setOnClickListener(v -> actions.onDeclineOrClose());
        btnRight.setOnClickListener(v -> onAcceptTapped());
        row.addView(btnLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View vdiv = new View(ctx);
        vdiv.setBackgroundColor(0x14000000);
        row.addView(vdiv, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(btnRight, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        view.addView(row, rlp);
    }

    private boolean completed = false;

    private void onAcceptTapped() {
        title.setText("Receiving…");
        rowDivider.setVisibility(View.GONE);
        row.animate().alpha(0f).setDuration(150).withEndAction(() -> row.setVisibility(View.GONE)).start();
        progress.setAlpha(0f);
        progress.setVisibility(View.VISIBLE);
        progress.animate().alpha(1f).setDuration(200).start();
        actions.onAccept();
    }

    public void setIncoming(IncomingTransfer t) {
        title.setText("Bridge Share");
        subtitle.setText(t.summary());
    }

    public void setProgress(int percent) {
        progress.setProgress(percent);
        subtitle.setText(percent + "%");
    }

    public void setComplete() {
        completed = true;
        title.setText("Bridge Share");
        subtitle.setText("1 file received");
        progress.animate().alpha(0f).setDuration(150).withEndAction(() -> progress.setVisibility(View.GONE)).start();
        check.setScaleX(0.5f); check.setScaleY(0.5f); check.setAlpha(0f);
        check.setVisibility(View.VISIBLE);
        check.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
        btnLeft.setText("Close");
        btnRight.setText("Open");
        btnLeft.setOnClickListener(v -> actions.onDeclineOrClose());
        btnRight.setOnClickListener(v -> actions.onOpen());
        rowDivider.setVisibility(View.VISIBLE);
        row.setVisibility(View.VISIBLE);
        row.setAlpha(0f);
        row.animate().alpha(1f).setDuration(220).start();
    }

    private TextView textButton(String text, int color) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextColor(color);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(14), dp(8), dp(6));
        b.setClickable(true);
        return b;
    }

    private int dp(int v) { return (int) (v * ctx.getResources().getDisplayMetrics().density); }

    /** Translucent green disc + white check, overlaid on the thumbnail on completion. */
    static class CheckOverlay extends View {
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        CheckOverlay(Context c) {
            super(c);
            disc.setColor(0xC000BD13);
            tick.setColor(Color.WHITE);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeWidth(c.getResources().getDisplayMetrics().density * 5);
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * 0.26f;
            cv.drawCircle(cx, cy, r, disc);
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(cx - r * 0.42f, cy + r * 0.02f);
            p.lineTo(cx - r * 0.08f, cy + r * 0.34f);
            p.lineTo(cx + r * 0.46f, cy - r * 0.34f);
            cv.drawPath(p, tick);
        }
    }
}
