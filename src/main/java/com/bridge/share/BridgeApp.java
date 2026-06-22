package com.bridge.share;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.bridge.share.diag.DiagLog;

/**
 * Application entry point. Initialises {@link DiagLog} as early as possible and
 * logs EVERY activity lifecycle transition app-wide, so the diagnostic log shows
 * exactly when the UI goes foreground/background during a transfer (the moments
 * that matter for the Wi-Fi-Direct background restriction).
 */
public final class BridgeApp extends Application {

    /** elapsedRealtime at process creation. A small (now - this) delta inside a broadcast
     *  receiver proves the process was freshly spawned to handle it (i.e. a killed receiver
     *  was revived by the OS-held wake scan). */
    public static volatile long PROCESS_START_ELAPSED;

    @Override
    public void onCreate() {
        super.onCreate();
        PROCESS_START_ELAPSED = android.os.SystemClock.elapsedRealtime();
        DiagLog.init(this);
        // Crash capture: record uncaught exceptions to disk (a crashing process can't upload),
        // and ship any crash saved by a previous run now that we're back up.
        DiagLog.installCrashHandler(this);
        DiagLog.uploadPendingCrashes(this);
        DiagLog.d("App", "Application.onCreate (process start)");
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            private String n(Activity a) { return a.getClass().getSimpleName(); }
            @Override public void onActivityCreated(Activity a, Bundle b) { DiagLog.d("Lifecycle", n(a) + " onCreate"); }
            @Override public void onActivityStarted(Activity a) { DiagLog.d("Lifecycle", n(a) + " onStart"); }
            @Override public void onActivityResumed(Activity a) { DiagLog.d("Lifecycle", n(a) + " onResume (FOREGROUND)"); }
            @Override public void onActivityPaused(Activity a) { DiagLog.d("Lifecycle", n(a) + " onPause"); }
            @Override public void onActivityStopped(Activity a) { DiagLog.d("Lifecycle", n(a) + " onStop (BACKGROUNDED)"); }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) { DiagLog.d("Lifecycle", n(a) + " onDestroy"); }
        });
    }
}
