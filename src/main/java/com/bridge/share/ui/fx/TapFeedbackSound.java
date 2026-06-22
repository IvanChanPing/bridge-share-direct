package com.bridge.share.ui.fx;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

/**
 * Plays the OnePlus NFC-tap "tune" + a short haptic on connect, reproducing the
 * real tap sound from the system NfcNci .ogg assets that were copied into this
 * app's {@code res/raw} (tap_start.ogg / tap_end.ogg / tap_error.ogg, lifted
 * verbatim from {@code com.android.nfc} NfcService SoundPool: start/end/error).
 *
 * <p>Pure public Android APIs only &mdash; SoundPool for audio, the platform
 * {@link Vibrator} for haptics. ZERO heytap / pantaconnect / com.oplus.*
 * privileged calls. The RichTap lib (com.appaac.haptic) bundled in the APK is
 * obfuscated and exposes no clean public entry, so we deliberately use the
 * standard {@link VibrationEffect} (a single sharp TICK, falling back to a
 * tiny one-shot) which on a OnePlus is routed through the OEM vibrator HAL and
 * gives a crisp tap-like response without any privileged dependency.
 *
 * <p>Raw resources are resolved by name via {@code Resources.getIdentifier} so
 * this class compiles without the apktool-generated {@code R} class. apktool
 * assigns the real ids at build time; the equivalent generated constants would
 * be {@code R.raw.tap_start}, {@code R.raw.tap_end}, {@code R.raw.tap_error}.
 *
 * <p>Typical use on a successful connect:
 * <pre>
 *   TapFeedbackSound.get(ctx).playStart(ctx);   // out-going tap "tick"
 *   TapFeedbackSound.get(ctx).tick(ctx);         // haptic
 *   ... later, when the transfer is confirmed ...
 *   TapFeedbackSound.get(ctx).playEnd(ctx);      // closing chirp
 * </pre>
 * Call {@link #release()} when the owning component is destroyed.
 */
public final class TapFeedbackSound {

    private static final String TAG = "TapFeedbackSound";

    private static final String RAW_START = "tap_start";
    private static final String RAW_END = "tap_end";
    private static final String RAW_ERROR = "tap_error";

    /** Lazily-built process-wide instance (one SoundPool is plenty). */
    private static volatile TapFeedbackSound sInstance;

    private final SoundPool soundPool;

    // SoundPool sample ids (0 == not yet/failed to load), keyed by raw name.
    private int idStart;
    private int idEnd;
    private int idError;
    private boolean loaded;

    private TapFeedbackSound() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        this.soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attrs)
                .build();
    }

    /** Returns the shared instance, creating + loading samples on first use. */
    public static TapFeedbackSound get(Context context) {
        TapFeedbackSound local = sInstance;
        if (local == null) {
            synchronized (TapFeedbackSound.class) {
                local = sInstance;
                if (local == null) {
                    local = new TapFeedbackSound();
                    sInstance = local;
                }
            }
        }
        local.ensureLoaded(context);
        return local;
    }

    private synchronized void ensureLoaded(Context context) {
        if (loaded || context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        idStart = loadRaw(app, RAW_START);
        idEnd = loadRaw(app, RAW_END);
        idError = loadRaw(app, RAW_ERROR);
        loaded = true;
    }

    private int loadRaw(Context app, String name) {
        try {
            int resId = app.getResources().getIdentifier(
                    name, "raw", app.getPackageName());
            if (resId == 0) {
                Log.w(TAG, "raw resource not found: " + name);
                return 0;
            }
            return soundPool.load(app, resId, 1);
        } catch (Throwable t) {
            Log.w(TAG, "loadRaw failed for " + name, t);
            return 0;
        }
    }

    /** Plays the NFC tap "start" tone (the chirp emitted as the tap lands). */
    public void playStart(Context context) {
        play(context, RAW_START);
    }

    /** Plays the NFC tap "end" tone (the closing chirp once connected). */
    public void playEnd(Context context) {
        play(context, RAW_END);
    }

    /** Plays the NFC "error" tone. */
    public void playError(Context context) {
        play(context, RAW_ERROR);
    }

    private void play(Context context, String name) {
        ensureLoaded(context);
        int id = sampleIdFor(name);
        if (id == 0) {
            Log.w(TAG, "no loaded sample for " + name);
            return;
        }
        // left/right=1.0, no loop, default priority, normal rate.
        soundPool.play(id, 1.0f, 1.0f, 1, 0, 1.0f);
    }

    private int sampleIdFor(String name) {
        switch (name) {
            case RAW_START:
                return idStart;
            case RAW_END:
                return idEnd;
            case RAW_ERROR:
                return idError;
            default:
                return 0;
        }
    }

    /**
     * Fires a short connect haptic. Uses the platform {@link VibrationEffect}
     * (predefined TICK where available, else a short one-shot) routed through
     * the standard {@link Vibrator}. No RichTap / OEM dependency.
     */
    public void tick(Context context) {
        if (context == null) {
            return;
        }
        Vibrator vibrator = obtainVibrator(context.getApplicationContext());
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        try {
            VibrationEffect effect;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            } else {
                effect = VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE);
            }
            vibrator.vibrate(effect);
        } catch (Throwable t) {
            Log.w(TAG, "tick haptic failed", t);
        }
    }

    @SuppressWarnings("deprecation")
    private static Vibrator obtainVibrator(Context app) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm =
                        (VibratorManager) app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    return vm.getDefaultVibrator();
                }
            }
            return (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable t) {
            Log.w(TAG, "obtainVibrator failed", t);
            return null;
        }
    }

    /** Releases the SoundPool. Safe to call multiple times. */
    public synchronized void release() {
        try {
            soundPool.release();
        } catch (Throwable ignored) {
            // best-effort
        }
        loaded = false;
        idStart = idEnd = idError = 0;
        sInstance = null;
    }
}
