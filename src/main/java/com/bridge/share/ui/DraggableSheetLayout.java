package com.bridge.share.ui;

import android.content.Context;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;

/**
 * A bottom-sheet card container shared by the send and receive sheets:
 *  - slides up on entrance with a small bounce (no fade),
 *  - is draggable downward and dismisses on a sufficient swipe-down,
 *  - snaps back with a bounce otherwise.
 * Child buttons still receive taps (drag only engages past touch-slop downward).
 */
public class DraggableSheetLayout extends LinearLayout {

    private final int touchSlop;
    private float downRawY;
    private float startTransY;
    private boolean dragging;
    private Runnable onDismiss;

    public DraggableSheetLayout(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setClickable(true);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    public void setOnDismiss(Runnable r) { this.onDismiss = r; }

    /** Slide up from below with a gentle overshoot bounce. */
    public void playEntrance() {
        post(() -> {
            setTranslationY(getHeight() + getPaddingBottom() + 80);
            animate().translationY(0).setDuration(340)
                    .setInterpolator(new OvershootInterpolator(1.1f)).start();
        });
    }

    /**
     * Call AFTER adding content that makes the sheet taller (e.g. the first device
     * icon). Animates the height increase as a smooth slide-up with a slight overscroll
     * settle (translationY overshoot — up then settle), instead of an instant pop. Not a
     * scale/bounce of the whole card.
     */
    public void animateGrow() {
        final int before = getHeight();
        post(() -> {
            int delta = getHeight() - before;
            if (delta <= 0) return; // didn't grow
            setTranslationY(delta);  // start at the old visual position
            animate().translationY(0).setDuration(420)
                    .setInterpolator(new OvershootInterpolator(1.4f)).start(); // up, overshoot, settle
        });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawY = e.getRawY();
                startTransY = getTranslationY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getRawY() - downRawY > touchSlop) { dragging = true; return true; }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawY = e.getRawY();
                startTransY = getTranslationY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getRawY() - downRawY;
                if (dy > 0) setTranslationY(startTransY + dy);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getTranslationY() > getHeight() * 0.28f) {
                    dismiss();
                } else {
                    animate().translationY(0).setDuration(220)
                            .setInterpolator(new OvershootInterpolator(1.0f)).start();
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    public void dismiss() {
        animate().translationY(getHeight() + getPaddingBottom() + 80).setDuration(200)
                .withEndAction(() -> { if (onDismiss != null) onDismiss.run(); }).start();
    }

    /**
     * Edge-to-edge: pad the sheet's bottom by the navigation-bar inset so its
     * content sits above the nav buttons, keeping {@code baseBottomPx} as the
     * design padding.
     */
    public static void applyBottomInset(View root, final DraggableSheetLayout sheet, final int baseBottomPx) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                navBottom = insets.getSystemWindowInsetBottom();
            }
            sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(),
                    sheet.getPaddingRight(), baseBottomPx + navBottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
