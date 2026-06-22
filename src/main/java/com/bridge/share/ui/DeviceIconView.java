package com.bridge.share.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One discovered device in the send sheet: a circular {@link RingProgressView}
 * icon, the device name below it, and a status line ("Connecting", "Sending").
 * Tapping plays a bounce so the user sees the tap registered.
 */
public class DeviceIconView extends LinearLayout {

    private final RingProgressView ring;
    private final TextView nameView;
    private final TextView statusView;
    public final String peerId;

    public DeviceIconView(Context ctx, String peerId, String name) {
        super(ctx);
        this.peerId = peerId;
        float d = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (8 * d);
        setPadding(pad, pad, pad, pad);

        ring = new RingProgressView(ctx);
        int size = (int) (64 * d);
        addView(ring, new LayoutParams(size, size));

        nameView = new TextView(ctx);
        nameView.setText(name);
        nameView.setTextColor(0xFF111114);
        nameView.setTextSize(13);
        nameView.setGravity(Gravity.CENTER);
        nameView.setPadding(0, (int) (6 * d), 0, 0);
        addView(nameView);

        statusView = new TextView(ctx);
        statusView.setTextColor(0xFF0A84FF);
        statusView.setTextSize(11);
        statusView.setGravity(Gravity.CENTER);
        addView(statusView);
    }

    /** Bounce the icon to acknowledge a tap. */
    public void bounce() {
        ring.animate()
            .scaleX(0.82f).scaleY(0.82f)
            .setDuration(90)
            .withEndAction(() -> ring.animate()
                .scaleX(1f).scaleY(1f)
                .setInterpolator(new OvershootInterpolator(3.5f))
                .setDuration(320)
                .start())
            .start();
    }

    /** Transfer complete: icon morphs to a green check, with a little bounce. */
    public void complete() {
        ring.setComplete();
        statusView.setText("Sent");
        bounce();
    }

    public void setStatus(String status) { statusView.setText(status == null ? "" : status); }

    public void setProgress(int percent) { ring.setProgress(percent); }

    public void setName(String name) { nameView.setText(name); }
}
