package com.bridge.share.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.WindowManager

/**
 * Receive presentation when overlay (draw-over-apps) permission is granted.
 *
 * Phase 2: hosts the native Kotlin {@link OverlayCard} (the 1:1 reimplementation
 * of card-test-app's card.html) in a TYPE_APPLICATION_OVERLAY window so it floats
 * over whatever app is open (an overlay can draw over the status bar). Replaces
 * the Phase-1 WebView host.
 */
object OverlayReceiver {

    private const val TAG = "OverlayReceiver"

    /** Show the native card overlay. Returns false if the window could not be added. */
    @JvmStatic
    fun show(ctx: Context, controller: ReceiveController?): Boolean {
        com.bridge.share.diag.DiagLog.d(TAG, "show overlay card")
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // AppCompat theme: LottieAnimationView (used in the complete state) is an
        // AppCompat widget and logs/fails under a plain DeviceDefault theme.
        val themed = ContextThemeWrapper(
            app, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)

        // A connected IslandA11yService lets us host the card in a TYPE_ACCESSIBILITY_OVERLAY,
        // which floats ABOVE the status bar and is tappable there (a TYPE_APPLICATION_OVERLAY is
        // forced below it). Null when the user hasn't enabled the service -> fall back to the app overlay.
        val islandSvc = IslandA11yService.get()
        com.bridge.share.diag.DiagLog.d(TAG, "show overlay: islandA11yConnected=" + (islandSvc != null))
        var card: OverlayCard? = null
        var addedViaA11y = false
        val remove = Runnable {
            val c = card ?: return@Runnable
            try { if (addedViaA11y) islandSvc?.removeOverlay(c) else wm.removeView(c) }
            catch (e: Exception) { /* already gone */ }
        }
        card = OverlayCard(
            themed,
            onDismiss = { controller?.decline(); card?.post(remove) },
            onOpen = { ReceiveService.openReceived(app); card?.post(remove) },
            // PROVEN: connect() needs a foreground activity (importance 100); the FGS
            // (125) is refused. So a REAL Accept hops through a brief invisible foreground
            // activity to run connect() at 100, which dismisses itself on join — the
            // transfer then continues in the background. The overlay card stays on top.
            // A DEMO/preview controller has no peer, so it runs accept() directly: launching
            // the 30s foreground hop with nothing to dismiss it freezes the screen after the
            // preview overlay disappears.
            onAccept = {
                val c = controller
                if (c != null) {
                    if (c.needsForegroundHop()) TransferAcceptActivity.launch(app, c) else c.accept()
                }
            }
        )
        // Drive the card's live progress/complete/cancel from real engine events.
        controller?.bind(object : ReceiveController.Ui {
            override fun onIncoming(transfer: IncomingTransfer) {}
            override fun onProgress(percent: Int) { val c = card; c?.post { c.liveProgress(percent) } }
            override fun onComplete() {
                val c = card ?: return
                // Show the complete state, then auto-remove so the overlay can never
                // hang on screen (user can tap OK/View sooner).
                c.post { c.liveComplete(); c.postDelayed(remove, 8000) }
            }
            override fun onCanceled() { val c = card; c?.post { c.liveCanceled(); c.postDelayed(remove, 2000) } }
        })

        // ---- Preferred path: accessibility overlay (tappable over the status bar / camera) ----
        // IMPORTANT: use the SAME LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS as the app-overlay path below.
        // Without them a TYPE_ACCESSIBILITY_OVERLAY is laid out BELOW the status bar, so its window
        // origin is not y=0 and the pill's cutout-based resting position lands lower than the
        // app-overlay path (the one that's positioned right). With these flags both windows start at
        // the true screen top, so the pill sits over the camera identically in both. Cutout mode
        // SHORT_EDGES lets the window reach into the punch-hole AND makes rootWindowInsets report the
        // real cutout bounds, which OverlayCard.pillRestPx() uses to centre the pill on the camera.
        if (islandSvc != null) {
            val alp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            alp.gravity = android.view.Gravity.TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                alp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (islandSvc.addOverlay(card, alp)) {
                addedViaA11y = true
                com.bridge.share.diag.DiagLog.d(TAG, "island added via accessibility overlay (tappable over status bar)")
                return true
            }
            com.bridge.share.diag.DiagLog.d(TAG, "a11y overlay add failed; falling back to app overlay")
        }

        // ---- Fallback: normal app overlay (below the status bar; top strip not tappable) ----
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        // NON-BLOCKING overlay sized to WRAP the card: with FLAG_NOT_TOUCH_MODAL, only touches
        // INSIDE the window's bounds are consumed and everything OUTSIDE passes through.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = android.view.Gravity.TOP
        return try {
            wm.addView(card, lp)
            com.bridge.share.diag.DiagLog.d(TAG, "overlay window added (app overlay, wrap_content height)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "addView failed (no overlay permission?)", e)
            com.bridge.share.diag.DiagLog.d(TAG, "overlay addView FAILED: " + e)
            false
        }
    }

    /** Handle returned by [showSend] so the send sheet can push live status into the pop-up. */
    class SendHandle internal constructor() {
        internal var card: OverlayCard? = null
        internal var removeRunnable: Runnable? = null
        fun progress(percent: Int) { val c = card ?: return; c.post { c.liveProgress(percent) } }
        fun complete() {
            val c = card ?: return
            c.post { c.liveComplete(); removeRunnable?.let { c.postDelayed(it, 6000) } }
        }
        fun failed() {
            val c = card ?: return
            c.post { c.liveCanceled(); removeRunnable?.let { c.postDelayed(it, 2000) } }
        }
        /** Tear the pop-up down now (e.g. the send sheet is closing). */
        fun dismiss() { val c = card ?: return; removeRunnable?.let { c.post(it) } }
    }

    /**
     * SENDER pop-up: the SAME native card the receiver gets, but started straight in the
     * "Sending to <peerName>" progress state (no Accept/Decline). Returns a [SendHandle] to push
     * progress/complete/failed, or null if no overlay window could be added (no island a11y AND no
     * draw-over-apps permission) — in that case the bottom sheet is the only sender UI.
     *
     * NOTE: the window-add logic below is duplicated from show() ON PURPOSE — to avoid refactoring
     * the device-verified receive path while adding the sender path.
     */
    @JvmStatic
    fun showSend(ctx: Context, peerName: String): SendHandle? {
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val themed = ContextThemeWrapper(
            app, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val islandSvc = IslandA11yService.get()
        com.bridge.share.diag.DiagLog.d(TAG, "showSend peer=" + peerName
                + " islandA11yConnected=" + (islandSvc != null))
        val handle = SendHandle()
        var card: OverlayCard? = null
        var addedViaA11y = false
        val remove = Runnable {
            val c = card ?: return@Runnable
            try { if (addedViaA11y) islandSvc?.removeOverlay(c) else wm.removeView(c) }
            catch (e: Exception) { /* already gone */ }
        }
        card = OverlayCard(
            themed,
            onDismiss = { card?.post(remove) },  // user tapped the pop-up away; the sheet still governs the send
            onOpen = {},
            onAccept = {},
            role = "send",
            peerName = peerName
        )
        handle.card = card
        handle.removeRunnable = remove

        // ---- Preferred path: accessibility overlay (tappable over the status bar / camera) ----
        if (islandSvc != null) {
            val alp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            alp.gravity = android.view.Gravity.TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                alp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (islandSvc.addOverlay(card, alp)) {
                addedViaA11y = true
                com.bridge.share.diag.DiagLog.d(TAG, "send pop-up added via accessibility overlay")
                return handle
            }
            com.bridge.share.diag.DiagLog.d(TAG, "send a11y overlay add failed; falling back to app overlay")
        }

        // ---- Fallback: normal app overlay (needs draw-over-apps permission) ----
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = android.view.Gravity.TOP
        return try {
            wm.addView(card, lp)
            com.bridge.share.diag.DiagLog.d(TAG, "send pop-up added (app overlay)")
            handle
        } catch (e: Exception) {
            Log.w(TAG, "showSend addView failed (no overlay permission?)", e)
            null
        }
    }

    private fun openGallery(app: Context) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_APP_GALLERY)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(i)
        } catch (t: Throwable) {
            try {
                val v = Intent(Intent.ACTION_VIEW).apply { type = "image/*" }
                app.startActivity(Intent.createChooser(v, "Open").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ignored: Throwable) {
            }
        }
    }
}
