package com.bridge.share.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the receive-visibility setting (the single setting on the main page):
 * OFF / ALWAYS_ON / TIMED (on for 10 minutes). Modelled on oshare-port's
 * Off/Always-on/10-min "low-battery" approach: only ALWAYS_ON survives reboot;
 * TIMED auto-expires after 10 minutes so the receiver isn't draining battery.
 */
public final class ReceivePrefs {

    public enum Mode { OFF, ALWAYS_ON, TIMED }

    private static final String PREFS = "bridge_prefs";
    private static final String KEY_MODE = "receive_mode";
    private static final String KEY_EXPIRY = "receive_expiry";
    public static final long TIMED_DURATION_MS = 10 * 60 * 1000L; // 10 minutes

    private ReceivePrefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void setMode(Context ctx, Mode mode) {
        SharedPreferences.Editor e = sp(ctx).edit();
        e.putString(KEY_MODE, mode.name());
        if (mode == Mode.TIMED) {
            e.putLong(KEY_EXPIRY, System.currentTimeMillis() + TIMED_DURATION_MS);
        } else {
            e.remove(KEY_EXPIRY);
        }
        e.apply();
    }

    /** The stored mode, downgrading an expired TIMED to OFF. */
    public static Mode getMode(Context ctx) {
        Mode m = Mode.valueOf(sp(ctx).getString(KEY_MODE, Mode.OFF.name()));
        if (m == Mode.TIMED && System.currentTimeMillis() > getExpiry(ctx)) {
            return Mode.OFF;
        }
        return m;
    }

    public static long getExpiry(Context ctx) {
        return sp(ctx).getLong(KEY_EXPIRY, 0L);
    }

    /** True if the receiver should currently be discoverable. */
    public static boolean isReceiving(Context ctx) {
        return getMode(ctx) != Mode.OFF;
    }
}
