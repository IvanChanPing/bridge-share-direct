package com.bridge.share.diag;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.bridge.share.BuildConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Debug-only diagnostic logger for figuring out why a real-phone transfer fails.
 *
 * It captures THIS process's own logcat (an app may read its own log entries with
 * no READ_LOGS permission), mirrors every line to an app-private fallback file,
 * and POSTs batches to the {@link #ENDPOINT} collector (diagserver/collector.py
 * behind a cloudflared quick tunnel). Uploads are bound to an internet-capable
 * Network so that when this device becomes a Wi-Fi-Direct group owner the P2P
 * interface can't steal the default route and silently drop the logs.
 *
 * All existing {@code Log.i/Log.w} calls across the engine (BridgeEngine,
 * ShareHttpServer, DownloadClient, PresenceAdvertiser/Scanner, CredsBleServer/
 * GattWriter, WiDiNetworkManagerEx, WifiP2pConnector, ...) are captured for free;
 * {@link #d} adds extra explicit points at otherwise-silent branches.
 */
public final class DiagLog {

    private static final String TAG = "DiagLog";

    /** Collector endpoint — live cloudflared quick tunnel to diagserver/collector.py. */
    public static final String ENDPOINT = "https://department-buzz-carry-bills.trycloudflare.com/log";

    private static final String PREFS = "bridge_diag";
    private static final String KEY_ENABLED = "enabled";
    /** Default OFF: this is debug-only machinery. Shipping it ON pegged the CPU and piled up
     *  HttpURLConnection threads on real phones (native mmap-abort + ANR). User can toggle on. */
    private static final boolean DEFAULT_ENABLED = false;

    private static final AtomicBoolean started = new AtomicBoolean(false);
    /** Hard cap so a slow/failing upload can't grow the queue until the heap OOMs. */
    private static final int MAX_QUEUE = 4000;
    private static final int MAX_LINE = 2000;
    private static final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

    private static Context appCtx;
    private static File logFile;
    private static String device = "device";
    private static String session = "sess";

    private static volatile boolean running;
    private static volatile java.lang.Process logcatProc;
    private static Thread logcatThread;
    private static Thread uploadThread;

    private DiagLog() {}

    /** Whether the user has diagnostic logging switched on (persisted, default ON). */
    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    /** Settings-page toggle: persist the choice and start/stop capture immediately. */
    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply();
        if (on) start(ctx);
        else stop();
    }

    /** Honour the persisted toggle; safe to call from every entry point onCreate. */
    public static void init(Context ctx) {
        if (isEnabled(ctx)) start(ctx);
    }

    /**
     * Capture uncaught exceptions to a file so a CRASH is not lost: a crashing process can't
     * upload anything (it's dying), so we write the stack trace to disk synchronously here and
     * upload it on the NEXT launch via {@link #uploadPendingCrashes}. Chains to the previous
     * handler so the OS still records + kills the process normally.
     */
    public static void installCrashHandler(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                File dir = new File(app.getExternalFilesDir(null), "diag");
                dir.mkdirs();
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                pw.println("==== CRASH " + new java.util.Date()
                        + " device=" + safe(Build.MANUFACTURER) + "-" + safe(Build.MODEL)
                        + " sdk=" + Build.VERSION.SDK_INT + " thread=" + thread.getName()
                        + " engine=" + BuildConfig.ENGINE + " ====");
                ex.printStackTrace(pw);
                pw.flush();
                try (FileOutputStream fos = new FileOutputStream(
                        new File(dir, "crash-" + System.currentTimeMillis() + ".txt"))) {
                    fos.write(sw.toString().getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(thread, ex); // let it crash normally
        });
    }

    /** Upload + delete any crash files saved by a previous (crashed) run. Call after {@link #start}. */
    public static void uploadPendingCrashes(Context ctx) {
        try {
            File dir = new File(ctx.getApplicationContext().getExternalFilesDir(null), "diag");
            File[] fs = dir.listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".txt"));
            if (fs == null) return;
            for (File f : fs) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                    enqueue("==== REPLAYING SAVED CRASH from " + f.getName() + " ====");
                    String line;
                    while ((line = r.readLine()) != null) enqueue("CRASH " + line);
                } catch (Throwable ignored) {}
                try { f.delete(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /** Idempotent start of capture + upload. */
    public static synchronized void start(Context ctx) {
        if (!started.compareAndSet(false, true)) return;
        appCtx = ctx.getApplicationContext();
        device = safe(Build.MANUFACTURER) + "-" + safe(Build.MODEL);
        session = Long.toHexString(System.currentTimeMillis());
        running = true;

        try {
            File dir = new File(appCtx.getExternalFilesDir(null), "diag");
            dir.mkdirs();
            logFile = new File(dir, "diag-" + session + ".log");
        } catch (Throwable t) {
            Log.w(TAG, "logfile init failed: " + t);
        }

        enqueue("==== DiagLog start engine=" + BuildConfig.ENGINE
                + " app=" + BuildConfig.APPLICATION_ID
                + " device=" + device + " sdk=" + Build.VERSION.SDK_INT
                + " session=" + session + " endpoint=" + ENDPOINT + " ====");

        startLogcatReader();
        startUploader();
        Log.i(TAG, "DiagLog started (endpoint=" + ENDPOINT + ")");
    }

    /** Stop capture + upload and let it be restarted later via {@link #start}. */
    public static synchronized void stop() {
        if (!started.get()) return;
        Log.i(TAG, "DiagLog stopping");
        running = false;
        try { java.lang.Process p = logcatProc; if (p != null) p.destroy(); } catch (Throwable ignored) {}
        if (uploadThread != null) uploadThread.interrupt();
        if (logcatThread != null) logcatThread.interrupt();
        logcatProc = null;
        logcatThread = null;
        uploadThread = null;
        queue.clear();
        started.set(false);
    }

    /** Explicit diagnostic line at a branch that wouldn't otherwise log. */
    public static void d(String tag, String msg) {
        // Deliberately do NOT Log.i while capturing: the reader tails THIS process's own
        // logcat (*:I), so emitting here would be re-read and re-enqueued, amplifying volume
        // (OverlayCard calls this every animation/progress tick). Enqueue directly instead.
        if (running) enqueue("DIAG " + tag + ": " + msg);
        else Log.i(tag, msg); // not capturing -> at least surface it in normal logcat
    }

    /**
     * Synchronously POST whatever is currently queued, then return. MUST be called off the
     * main thread (it does network I/O). Used by short-lived entry points (e.g. the wake
     * broadcast) that may be killed before the async uploader's 3s poll fires, so the
     * diagnostic lines would otherwise never reach the collector. Best-effort: races the
     * async uploader for queue lines, which is fine for diagnostics.
     */
    public static void flushBlocking() {
        if (!running) return;
        StringBuilder b = new StringBuilder();
        String line;
        int count = 0;
        while (count < 3000 && (line = queue.poll()) != null) { b.append(line).append('\n'); count++; }
        if (b.length() > 0) post(b.toString());
    }

    // ---- internals ----

    private static void enqueue(String s) {
        if (s == null) return;
        if (s.length() > MAX_LINE) s = s.substring(0, MAX_LINE);
        // Bound memory: drop oldest lines if the uploader falls behind (prevents OOM).
        while (queue.size() >= MAX_QUEUE) queue.poll();
        queue.offer(s);
        File f = logFile;
        if (f != null) {
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write((s + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {}
        }
    }

    private static void startLogcatReader() {
        logcatThread = new Thread(() -> {
            try {
                try { Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor(); }
                catch (Throwable ignored) {}
                // Filter to Info+ : our Log.i/Log.w and the engine logs are all kept,
                // but the heavy framework DEBUG spam (VRI/BufferQueue/etc.) is dropped,
                // so the capture volume stays low enough not to pressure the heap.
                // "DiagLog:S" silences OUR OWN tag so the reader can't re-capture DiagLog's own
                // output (upload-failed/http/started lines) and feed itself in an endless loop.
                java.lang.Process p = Runtime.getRuntime().exec(new String[]{
                        "logcat", "-v", "time", "--pid=" + Process.myPid(), "DiagLog:S", "*:I"});
                logcatProc = p;
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String l;
                while (running && (l = r.readLine()) != null) enqueue(l);
            } catch (Throwable t2) {
                if (running) { Log.w(TAG, "logcat reader died: " + t2); enqueue("logcat reader died: " + t2); }
            }
        }, "DiagLog-logcat");
        logcatThread.setDaemon(true);
        logcatThread.start();
    }

    private static void startUploader() {
        uploadThread = new Thread(() -> {
            StringBuilder batch = new StringBuilder();
            int fails = 0;
            while (running) {
                try {
                    String first = queue.poll(3, TimeUnit.SECONDS);
                    if (first == null) continue;
                    batch.setLength(0);
                    batch.append(first).append('\n');
                    String more;
                    int count = 1;
                    while (count < 3000 && (more = queue.poll()) != null) {
                        batch.append(more).append('\n');
                        count++;
                    }
                    if (post(batch.toString())) {
                        fails = 0;
                    } else {
                        // Dead/unreachable endpoint: back off exponentially (cap 60s). Opening a
                        // fresh HttpURLConnection (= com.android.okhttp) every 3s with no backoff
                        // piled up pool threads until the process hit its thread/mmap ceiling.
                        if (fails < 6) fails++;
                        Thread.sleep(Math.min(60000L, 2000L * (1L << (fails - 1))));
                    }
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable up) {
                    Log.w(TAG, "upload loop: " + up);
                }
            }
        }, "DiagLog-upload");
        uploadThread.setDaemon(true);
        uploadThread.start();
    }

    /** @return true on HTTP 200, false on any non-200 / exception (so the uploader can back off). */
    private static boolean post(String body) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(ENDPOINT);
            Network net = internetNetwork();
            URLConnection uc = (net != null) ? net.openConnection(url) : url.openConnection();
            c = (HttpURLConnection) uc;
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setRequestProperty("X-Device", device);
            c.setRequestProperty("X-Engine", BuildConfig.ENGINE);
            c.setRequestProperty("X-Session", session);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.getOutputStream().write(b);
            int code = c.getResponseCode();
            try { c.getInputStream().close(); } catch (Throwable ignored) {}
            if (code != 200) { Log.w(TAG, "upload http " + code); return false; }
            return true;
        } catch (Throwable t) {
            // Failure (e.g. no internet route during P2P) -> already saved to file; drop batch.
            Log.w(TAG, "upload failed: " + t);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Best-effort: an INTERNET+VALIDATED Network that is NOT the Wi-Fi-Direct interface. */
    private static Network internetNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network best = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null) continue;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                if (validated) return n;       // prefer a validated internet network
                if (best == null) best = n;     // fall back to any internet-capable one
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safe(String s) {
        if (s == null || s.isEmpty()) return "x";
        return s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
