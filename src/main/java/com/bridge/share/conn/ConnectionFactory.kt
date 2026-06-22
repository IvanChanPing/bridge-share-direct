package com.bridge.share.conn

import android.content.Context
import com.bridge.share.BuildConfig

/**
 * Selects the [Connection] variant for this build flavor. The flavor sets
 * [BuildConfig.ENGINE] to one of "WIFI_DIRECT" / "WIFI_AWARE" / "HOTSPOT"
 * (see build.gradle productFlavors). The engine only ever talks to [Connection];
 * this factory is the single place that knows the concrete classes.
 */
object ConnectionFactory {

    /** Build the [Connection] matching [BuildConfig.ENGINE]. */
    @JvmStatic
    fun create(ctx: Context): Connection = when (BuildConfig.ENGINE) {
        "WIFI_AWARE" -> WifiAwareConnection(ctx)
        "HOTSPOT" -> HotspotConnection(ctx)
        else -> WifiDirectConnection(ctx)
    }
}
