package com.bridge.share.ui;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * DEFAULT receive accept-prompt presentation (no overlay permission): the shared
 * {@link ReceiveCard} hosted in a translucent Activity as a bottom sheet. The
 * accept gate is ALWAYS shown, even for NFC. Launched directly it uses
 * {@link PreviewReceiveController}.
 */
public class ReceiveBottomSheetActivity extends Activity implements ReceiveController.Ui {

    private ReceiveController controller;
    private ReceiveCard card;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        getWindow().setBackgroundDrawable(new ColorDrawable(0x88000000));

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> card.view.dismiss());

        // Adopt the real engine controller handed off by ReceiveUi; fall back to the
        // demo only on a direct preview launch (no pending controller).
        controller = ReceiveUi.consumePending();
        if (controller == null) controller = new PreviewReceiveController();
        com.bridge.share.diag.DiagLog.d("ReceiveSheet", "controller=" + controller.getClass().getSimpleName());
        card = new ReceiveCard(this, new ReceiveCard.Actions() {
            @Override public void onAccept() { controller.accept(); }
            @Override public void onDeclineOrClose() { controller.decline(); card.view.dismiss(); }
            @Override public void onOpen() { ReceiveService.openReceived(ReceiveBottomSheetActivity.this); card.view.dismiss(); }
        });
        card.view.setOnDismiss(this::finish);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(300),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        clp.bottomMargin = dp(12);
        root.addView(card.view, clp);
        setContentView(root);

        DraggableSheetLayout.applyBottomInset(root, card.view, dp(22));
        card.view.playEntrance();
        controller.bind(this);
    }

    @Override public void onIncoming(IncomingTransfer t) { runOnUiThread(() -> card.setIncoming(t)); }
    @Override public void onProgress(int percent) { runOnUiThread(() -> card.setProgress(percent)); }
    @Override public void onComplete() { runOnUiThread(() -> card.setComplete()); }
    @Override public void onCanceled() { runOnUiThread(() -> card.view.dismiss()); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
