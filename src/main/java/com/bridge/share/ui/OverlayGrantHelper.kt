/*
 * OverlayGrantHelper — client side of the ONE-TAP overlay grant.
 *
 * WHAT: binds the universal radio-helper (dev.superdrop.radiohelper) and asks it to
 *   grant THIS app SYSTEM_ALERT_WINDOW ("draw over other apps") via the helper's
 *   shell-UID self-ADB channel — no Settings toggle, no per-boot step (the appop is
 *   stored in /data/system/appops.xml and persists across reboot, so it's a one-shot).
 * WHY: with overlay granted, the incoming-transfer prompt can show as the floating
 *   overlay card instead of the bottom sheet. The grant normally needs the user to
 *   flip "Display over other apps" in Settings; this does it in one tap IF the helper
 *   is installed + its one-time Wireless-Debugging pairing is done.
 * CALLED BY: the "Enable overlay" button on {@link MainActivity} — on a false result
 *   the button falls back to ACTION_MANAGE_OVERLAY_PERMISSION (the manual toggle).
 * JAVA-FRIENDLY: RadioHelperClient's API is Kotlin lambdas; this exposes a
 *   @JvmStatic method taking a java.util.function.Consumer<Boolean> so MainActivity
 *   (Java) can call it with a plain lambda. Callbacks arrive on the main looper
 *   (RadioHelperClient guarantee), so onResult may touch UI directly.
 * STATUS: compile-only. Runs only after the helper's one-time pairing; the self-ADB
 *   grant path is device-UNVERIFIED on the user's ColorOS phones.
 */
package com.bridge.share.ui

import android.content.Context
import dev.superdrop.radiohelper.client.RadioHelperClient
import java.util.function.Consumer

object OverlayGrantHelper {

    /**
     * Bind the helper, ask it to grant this app overlay, deliver the verified result
     * to [onResult] (true only if the helper confirmed the appop is 'allow'), then
     * unbind. Emits false if the helper isn't installed / bind denied (wrong signing
     * key) / not paired for self-ADB / the grant didn't take.
     */
    @JvmStatic
    fun requestViaHelper(
        context: Context,
        onResult: Consumer<Boolean>,
    ) {
        val client = RadioHelperClient(context)
        client.connect { connected ->
            if (!connected) {
                onResult.accept(false)
                client.disconnect()
                return@connect
            }
            client.grantOverlay { ok ->
                onResult.accept(ok)
                client.disconnect()
            }
        }
    }
}
