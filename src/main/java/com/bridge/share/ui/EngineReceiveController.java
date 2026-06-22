package com.bridge.share.ui;

/**
 * REAL receive controller (replaces {@link PreviewReceiveController} on the live
 * path). It does NOT run the engine itself — {@link ReceiveService} owns the
 * on-demand join (BATTERY_SPEC §2) and pushes engine events in through the
 * {@code on*} methods below. This controller simply forwards those engine events
 * to the {@link ReceiveController.Ui} (the card) and forwards the user's
 * accept/decline choice back to the service via {@link Decision}.
 *
 * There is ALWAYS an accept gate — even a BLE/NFC-initiated transfer only wakes
 * the host; the bytes are pulled only after the user taps Accept.
 */
public final class EngineReceiveController implements ReceiveController {

    /** Callback to the service for the user's accept/decline/cancel choice. */
    public interface Decision {
        void onAccept();
        void onDecline();
        void onCancel();
    }

    private final Decision decision;
    private Ui ui;

    public EngineReceiveController(Decision decision) {
        this.decision = decision;
    }

    @Override
    public void bind(Ui ui) {
        this.ui = ui;
    }

    @Override
    public void accept() {
        com.bridge.share.diag.DiagLog.d("EngineReceiveController", "ACCEPT -> join+pull");
        if (decision != null) decision.onAccept();
    }

    @Override
    public void decline() {
        com.bridge.share.diag.DiagLog.d("EngineReceiveController", "DECLINE");
        if (decision != null) decision.onDecline();
    }

    @Override
    public void cancel() {
        com.bridge.share.diag.DiagLog.d("EngineReceiveController", "CANCEL");
        if (decision != null) decision.onCancel();
    }

    // ---- engine -> UI (called by ReceiveService on the main thread) ----

    public void postIncoming(IncomingTransfer t) {
        if (ui != null) ui.onIncoming(t);
    }

    public void postProgress(int percent) {
        if (ui != null) ui.onProgress(percent);
    }

    public void postComplete() {
        if (ui != null) ui.onComplete();
    }

    public void postCanceled() {
        if (ui != null) ui.onCanceled();
    }
}
