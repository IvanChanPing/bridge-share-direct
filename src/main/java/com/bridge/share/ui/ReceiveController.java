package com.bridge.share.ui;

/**
 * UI-facing seam for the receive accept-prompt flow. The engine raises an
 * incoming transfer; the UI (bottom sheet OR overlay card) renders the
 * Accept/Decline prompt and reports the user's choice back. There is ALWAYS a
 * prompt — even for an NFC-initiated transfer (NFC only initiates; it never
 * auto-accepts).
 */
public interface ReceiveController {

    interface Ui {
        void onIncoming(IncomingTransfer transfer);
        void onProgress(int percent);
        void onComplete();
        void onCanceled();
    }

    void bind(Ui ui);
    void accept();
    void decline();
    void cancel();

    /**
     * Whether Accept must run through the invisible foreground-hop activity
     * ({@link TransferAcceptActivity}) that holds importance 100 so a real Wi-Fi-Direct
     * connect() survives. A REAL receive needs it; a DEMO/preview does not (there is no
     * peer to connect to), and launching it leaves a 30s invisible activity on screen with
     * nothing to dismiss it -> the screen "freezes" after the preview overlay disappears.
     */
    default boolean needsForegroundHop() { return true; }
}
