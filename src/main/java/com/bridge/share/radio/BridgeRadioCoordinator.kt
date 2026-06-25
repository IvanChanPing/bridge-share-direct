/*
 * ════════════════════════════════════════════════════════════════════════════
 *  BridgeRadioCoordinator — refcounted, single-process Wi-Fi gate for Bridge
 *  Share Direct, routed through the universal radio-helper APK.
 * ════════════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS IS
 *   A process-wide singleton that turns Wi-Fi ON (silently, via the installed
 *   `radio-helper` app) when ANY Bridge Share Direct flow that needs Wi-Fi
 *   becomes active, and restores Wi-Fi to the user's ORIGINAL state when the
 *   LAST such flow ends. It is the app's only entry point to the helper.
 *
 *   NOTE — Wi-Fi, not Bluetooth. Bridge Share Direct is the SHAREit Wi-Fi-Direct
 *   port: the link (Wi-Fi-Direct group / LocalOnlyHotspot / Wi-Fi Aware) runs over
 *   Wi-Fi, while the trigger/discovery handshake runs over BLE. So the radio this
 *   gate enables is Wi-Fi ([RadioHelperClient.RADIO_WIFI]). (The O+ Connect bridge's
 *   coordinator gates Bluetooth instead, because OShare discovers over BT — same
 *   shape, different radio.) BLE/BT is left to the OS/user as before; we don't touch it.
 *
 * WHAT IT'S CALLED / HOW IT'S INVOKED (no UI of its own — called from code):
 *   - [acquireReceive] / [releaseReceive] — from {@code ReceiveService}. Per the
 *     user's choice (2026-06-25) Wi-Fi is held for the WHOLE armed-receive window
 *     (not just the active transfer): acquire on each successful startForeground
 *     (ALWAYS_ON / TIMED / NFC receive), release in {@code fullTeardown()} (the
 *     single "everything down" funnel reached on mode OFF / expiry / onDestroy).
 *   - [acquireSend] / [releaseSend] — from {@code EngineSendController}: acquire at
 *     the top of {@code sendTo()} (just before the Wi-Fi-Direct group is created),
 *     release in {@code stop()} (send sheet closed — the terminal funnel; holding
 *     across back-to-back sends avoids flapping Wi-Fi between files).
 *   Java call sites use the @JvmStatic forms, e.g.
 *     {@code BridgeRadioCoordinator.acquireSend(context);}
 *
 * WHY IT EXISTS
 *   The app targets SDK 35, where it CANNOT enable Wi-Fi itself
 *   ({@code WifiManager.setWifiEnabled} is a no-op past targetSdk 28). The
 *   radio-helper (targetSdk 28) holds that capability; the app binds it via
 *   {@link dev.superdrop.radiohelper.client.RadioHelperClient} and asks it to
 *   enable + later restore Wi-Fi via the helper's SESSION mode (prepareForShare /
 *   transferFinished) so the user's original Wi-Fi state is restored afterwards.
 *   Because receive is held for the whole armed window and sends are discrete, a
 *   naive "enable per transfer / restore per transfer" would flap Wi-Fi between
 *   back-to-back transfers — so this class REFCOUNTS active owners and only
 *   restores when the count hits zero.
 *
 * HOW IT FITS (flow)
 *   first acquire (0→1): connect the helper → prepareForShare(RADIO_WIFI) → helper
 *     captures the user's pre-share Wi-Fi state and turns Wi-Fi on; a 5 s heartbeat
 *     starts.
 *   nested acquire (n→n+1) and release (n+1→n>0): no radio change (no flap).
 *   last release (1→0): stop heartbeat → transferFinished() → helper restores Wi-Fi
 *     to the captured original.
 *   Heartbeat: while count>0 we beat every 5 s; if the app is killed mid-transfer
 *   (never reaches releaseX) the helper restores ~20 s after the last beat instead
 *   of stranding Wi-Fi on (helper also has a 20-min watchdog + boot-restore).
 *
 * GRACEFUL DEGRADATION
 *   If the helper isn't installed or the bind is denied (wrong signing key),
 *   {@code connect} reports false and we simply do nothing — the app's existing
 *   behaviour (the transport just fails if Wi-Fi is off, as before) remains the
 *   fallback. We never block or crash.
 *
 * THREADING
 *   All helper calls run on the main looper (RadioHelperClient's Messenger callback
 *   is main-looper bound). acquire/release post to the main handler, so they are
 *   safe to call from any thread; the helper IPC is async → no main-thread block,
 *   no ANR.
 *
 * STATUS: compile-stage only as of 2026-06-25. The end-to-end on-device path
 *   (Wi-Fi actually flips on silently via the helper and restores) is UNVERIFIED —
 *   requires installing the (re-keyed) app alongside the helper and driving a real
 *   transfer. See docs/RADIO_HELPER_INTEGRATION_JOURNAL.md.
 */
package com.bridge.share.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.superdrop.radiohelper.client.RadioHelperClient

object BridgeRadioCoordinator {
    private const val TAG = "BridgeRadioCoord"
    private const val HEARTBEAT_MS = 5_000L

    // Wi-Fi is held for the whole armed-receive window (user choice 2026-06-25).
    const val OWNER_RECEIVE = "receive"

    // Send is a discrete owner bounded by the send sheet's lifecycle (sendTo → stop).
    const val OWNER_SEND = "send"

    private val main = Handler(Looper.getMainLooper())

    // Helper client, created lazily on first acquire with the application context.
    private var client: RadioHelperClient? = null

    // Distinct active owners. A Set (not a raw int) makes acquire/release
    // idempotent per owner — a duplicate acquireReceive() or a double releaseSend()
    // can't unbalance the count and strand or prematurely drop Wi-Fi.
    private val owners = HashSet<String>()

    // True once we've sent prepareForShare and Wi-Fi is (being) held on.
    private var prepared = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (owners.isEmpty()) return
            client?.heartbeat()
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    // ---- public API (Java-friendly) ----

    @JvmStatic fun acquireReceive(ctx: Context) = acquire(ctx, OWNER_RECEIVE)
    @JvmStatic fun releaseReceive() = release(OWNER_RECEIVE)
    @JvmStatic fun acquireSend(ctx: Context) = acquire(ctx, OWNER_SEND)
    @JvmStatic fun releaseSend() = release(OWNER_SEND)

    /** Mark [owner] active; if it's the first active owner, enable Wi-Fi via the helper. */
    @JvmStatic
    fun acquire(ctx: Context, owner: String) {
        val appCtx = ctx.applicationContext
        main.post {
            val wasEmpty = owners.isEmpty()
            val added = owners.add(owner)
            Log.i(TAG, "acquire($owner) wasEmpty=$wasEmpty added=$added owners=$owners")
            if (!wasEmpty) return@post // already holding Wi-Fi — no flap

            if (client == null) client = RadioHelperClient(appCtx)
            client!!.connect { connected ->
                if (!connected) {
                    // Helper absent / bind denied → leave Wi-Fi to the existing fallback.
                    Log.w(TAG, "helper not connected; Wi-Fi left to existing fallback")
                    return@connect
                }
                if (owners.isEmpty()) {
                    // Everyone released during the bind → restore immediately.
                    client?.transferFinished()
                    return@connect
                }
                prepared = true
                client!!.prepareForShare(RadioHelperClient.RADIO_WIFI) { radiosOn ->
                    Log.i(TAG, "prepareForShare done; radiosNowOn=$radiosOn")
                }
                main.removeCallbacks(heartbeat)
                main.postDelayed(heartbeat, HEARTBEAT_MS)
                client?.heartbeat() // arm the ~20 s restore window immediately
            }
        }
    }

    /** Mark [owner] inactive; if it was the last active owner, restore Wi-Fi. */
    @JvmStatic
    fun release(owner: String) {
        main.post {
            val removed = owners.remove(owner)
            Log.i(TAG, "release($owner) removed=$removed owners=$owners")
            if (owners.isNotEmpty()) return@post // others still need Wi-Fi — keep it on
            main.removeCallbacks(heartbeat)
            if (prepared) {
                prepared = false
                client?.transferFinished {
                    Log.i(TAG, "transferFinished; Wi-Fi restored to original")
                }
            }
        }
    }
}
