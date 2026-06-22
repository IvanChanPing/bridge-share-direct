package com.bridge.share.ui;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.bridge.share.BridgeEngine;
import com.bridge.share.channel.ShareRecord;
import com.bridge.share.trigger.Creds;
import com.bridge.share.trigger.CredsHceService;
import com.bridge.share.trigger.PresenceAdvertiser;

import java.io.File;

/**
 * Foreground service that owns the armed receive lifecycle, implementing the
 * proven oshare-port on-demand host model (docs/BATTERY_SPEC §2/§4/§5).
 *
 * ARMED (ALWAYS_ON / TIMED) = cheap only: BLE presence advertise (LOW_POWER) via
 * {@link PresenceAdvertiser} + HCE TRIGGER armed. NO hotspot, NO wake lock while
 * merely armed.
 *
 * On a sender's BLE GATT request-host WRITE (creds bytes to the presence request
 * characteristic) or an NFC request-host tap, the expensive link is brought up
 * ON DEMAND: a partial wake lock is held, {@link BridgeEngine#joinWithCreds}
 * joins the host and PULLS the files into Downloads/BridgeShare, driving the
 * receive card. The wake lock is released and the link torn down on completion
 * or a watchdog timeout.
 *
 * TIMED auto-expiry uses {@link AlarmManager#setExactAndAllowWhileIdle} (Doze-safe),
 * cancel-then-set on every re-arm, firing {@link #ACTION_EXPIRE} for full teardown.
 */
public class ReceiveService extends Service {

    private static final String TAG = "ReceiveService";

    private static final String CHANNEL_ID = "bridge_receive";
    private static final int NOTIF_ID = 1001;

    /** Full teardown + reset mode to OFF (fired by the TIMED expiry alarm). */
    public static final String ACTION_EXPIRE = "com.bridge.share.action.EXPIRE";

    /** Revival from the OS-held wake scan (a sender's beacon was seen): bring up the
     *  presence advertise + GATT briefly so the sender can hand over creds. */
    public static final String ACTION_WOKEN = "com.bridge.share.action.WOKEN";

    /** NFC tap launched the app: force an active receive window so the sender's normal BLE
     *  handshake can reach us — EVEN IF the persisted mode is OFF or an expired TIMED. Does
     *  NOT change the saved mode; after the window we return to the persisted resting state. */
    public static final String ACTION_NFC_RECEIVE = "com.bridge.share.action.NFC_RECEIVE";

    /** Woken-but-no-transfer window: if no sender creds-write arrives this long after a
     *  wake, drop back to OS-only scan idle (don't stay foreground/advertising forever). */
    private static final long WOKEN_IDLE_MS = 60_000L;

    /** ~90s post-trigger no-completion watchdog; ~30s post-join idle watchdog. */
    private static final long WATCHDOG_TRIGGER_MS = 90_000L;
    private static final long WATCHDOG_JOIN_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable watchdog;
    private BtStateReceiver btReceiver;

    private PresenceAdvertiser presence;
    private BridgeEngine engine;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean transferActive;
    /** elapsedRealtime until which a routine re-arm must keep advertising NFC-tapped, so a
     *  START_STICKY/arm() re-arm can't clobber the tapped flag right after an NFC tap (verified
     *  on-phone: a re-arm reset tapped ~5ms after the tap → sender saw tapped=false → no auto-send). */
    private volatile long nfcTappedUntil;

    private static volatile ReceiveService sInstance;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ReceiveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    /** Called from {@link BeaconWakeReceiver} when the OS-held wake scan fires: bring the
     *  service up in the active (foreground + advertise) state so the sender can connect. */
    public static void wake(Context ctx) {
        Intent i = new Intent(ctx, ReceiveService.class).setAction(ACTION_WOKEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    /** An NFC tap launched us: force an active receive window regardless of the saved mode. */
    public static void nfcReceive(Context ctx) {
        Intent i = new Intent(ctx, ReceiveService.class).setAction(ACTION_NFC_RECEIVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    /**
     * Arm receive for the current mode:
     *  - OFF       → stop everything + drop the OS-held scan.
     *  - ALWAYS_ON → {@link #start} (persistent foreground service: minimal LOW_POWER presence
     *                advertise + GATT, kept up so a scanning sender can always find us).
     *  - TIMED     → {@link #start} (same, bounded to the 10-min window).
     * Entry point for settings/boot/tile/re-arm.
     */
    public static void arm(Context ctx) {
        if (ReceivePrefs.getMode(ctx) == ReceivePrefs.Mode.OFF) { stop(ctx); return; }
        start(ctx);
    }

    /** Register ONLY the OS-held wake scan + NFC trigger, with no foreground service. Used as a
     *  fallback when a foreground-service start is refused (the wake scan can still revive us). */
    public static void armOsOnly(Context ctx) {
        com.bridge.share.trigger.WakeBeacon.registerWakeScan(ctx.getApplicationContext());
        CredsHceService.armTrigger();
        Log.i(TAG, "armOsOnly: system-held wake scan registered (no foreground service)");
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ReceiveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        com.bridge.share.diag.DiagLog.init(this);
        long sinceStart = android.os.SystemClock.elapsedRealtime()
                - com.bridge.share.BridgeApp.PROCESS_START_ELAPSED;
        com.bridge.share.diag.DiagLog.d(TAG, "onCreate mode=" + ReceivePrefs.getMode(this)
                + " msSinceProcessStart=" + sinceStart
                + " (small => service created in a freshly-spawned process)");
        sInstance = this;
        createChannel();
        btReceiver = new BtStateReceiver(new BtStateReceiver.Callback() {
            @Override public void onBluetoothOn() {
                // BLE advertise needs BT; re-arm now that it's back on (OS-only for ALWAYS_ON).
                ReceiveService.arm(ReceiveService.this);
            }
            @Override public void onBluetoothOff() { pauseForBluetoothOff(); }
        });
        registerReceiver(btReceiver,
                new android.content.IntentFilter(
                        android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_EXPIRE.equals(action)) {
            Log.i(TAG, "ACTION_EXPIRE -> full teardown");
            ReceivePrefs.setMode(this, ReceivePrefs.Mode.OFF);
            com.bridge.share.trigger.WakeBeacon.unregisterWakeScan(getApplicationContext());
            fullTeardown();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        // NFC tap: force an active receive window even if the saved mode is OFF/expired. Bring
        // up presence advertise + GATT so the sender's normal BLE handshake reaches us; a
        // watchdog returns us to the persisted resting state if no transfer follows.
        if (ACTION_NFC_RECEIVE.equals(action)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, buildNotification(ReceivePrefs.Mode.TIMED),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                } else {
                    startForeground(NOTIF_ID, buildNotification(ReceivePrefs.Mode.TIMED));
                }
            } catch (Throwable t) {
                com.bridge.share.diag.DiagLog.d(TAG, "NFC receive startForeground refused: " + t);
                stopSelf();
                return START_NOT_STICKY;
            }
            com.bridge.share.diag.DiagLog.d(TAG, "NFC-initiated active receive (saved mode="
                    + ReceivePrefs.getMode(this) + ")");
            // Sticky window: keep advertising tapped through any routine re-arm for WOKEN_IDLE_MS.
            nfcTappedUntil = android.os.SystemClock.elapsedRealtime() + WOKEN_IDLE_MS;
            armCheapListeners(true); // advertise NFC-tapped so the sender auto-picks us
            armNfcIdleWatchdog();
            return START_STICKY;
        }

        ReceivePrefs.Mode mode = ReceivePrefs.getMode(this);
        if (mode == ReceivePrefs.Mode.OFF) {
            cancelExpireAlarm();
            // Turned OFF: stop the OS-held wake scan too (do NOT do this on mere onDestroy
            // — the wake scan must survive our process being killed while Always-on).
            com.bridge.share.trigger.WakeBeacon.unregisterWakeScan(getApplicationContext());
            fullTeardown();
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean woken = ACTION_WOKEN.equals(action);

        // ALWAYS_ON and TIMED both run a persistent foreground service that advertises presence
        // (LOW_POWER) + GATT continuously, so a scanning sender can ALWAYS find us. (The earlier
        // "OS-only" model — register the wake scan and run nothing — was unreliable on-device:
        // the receiver advertised nothing between wakes, so the sender couldn't detect it. The
        // wake scan is now an additive BACKUP that revives this service if the OEM kills it.)

        // Promote to foreground. On API 31+ a background start can be refused with
        // ForegroundServiceStartNotAllowedException — log the exact outcome so the wake path
        // (ACTION_WOKEN from the OS scan) can be judged on ColorOS 12+. If it's refused, drop
        // back to OS-only idle rather than crash.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(mode),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, buildNotification(mode));
            }
            com.bridge.share.diag.DiagLog.d(TAG, "startForeground OK woken=" + woken + " mode=" + mode);
        } catch (Throwable t) {
            com.bridge.share.diag.DiagLog.d(TAG, "startForeground REFUSED woken=" + woken
                    + " err=" + t.getClass().getName() + ": " + t.getMessage());
            // Can't be foreground; keep the OS-held scan registered and bail out cleanly.
            armOsOnly(getApplicationContext());
            stopSelf();
            return START_NOT_STICKY;
        }

        // TIMED: Doze-safe expiry alarm (cancel-then-set so re-arm resets the window).
        cancelExpireAlarm();
        if (mode == ReceivePrefs.Mode.TIMED) {
            long remaining = ReceivePrefs.getExpiry(this) - System.currentTimeMillis();
            if (remaining <= 0) {
                ReceivePrefs.setMode(this, ReceivePrefs.Mode.OFF);
                fullTeardown();
                stopForegroundCompat();
                stopSelf();
                return START_NOT_STICKY;
            }
            scheduleExpireAlarm(remaining);
        }

        // ARM = cheap only: BLE presence advertise (LOW_POWER) + HCE TRIGGER + wake scan.
        // Do NOT bring up any hotspot here, do NOT advertise creds. Persistent (START_STICKY)
        // so the receiver stays discoverable; a wake (ACTION_WOKEN) just re-enters this.
        armCheapListeners();
        return START_STICKY;
    }

    /** If the user swipes the app from recents, ColorOS often kills the service —
     *  restart it so receive stays armed (unless turned OFF). */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (ReceivePrefs.getMode(this) != ReceivePrefs.Mode.OFF) {
            // Restart the persistent receive service so swiping the app away doesn't stop us
            // being discoverable.
            try { arm(this); } catch (Exception ignored) {}
        }
        super.onTaskRemoved(rootIntent);
    }

    /** Cheap armed state: presence advert + HCE trigger. No hotspot, no wake lock. */
    private void armCheapListeners() { armCheapListeners(false); }

    /** @param nfcTapped advertise the NFC-tapped flag so a scanning sender auto-picks us. */
    private void armCheapListeners(boolean nfcTapped) {
        String alias = Build.MODEL;
        // Sticky: if we're inside the post-tap window, advertise tapped even on a ROUTINE re-arm
        // (nfcTapped=false), so a START_STICKY/arm() re-arm can't clobber the tapped advertise
        // before the sender scans it.
        boolean tapped = nfcTapped
                || android.os.SystemClock.elapsedRealtime() < nfcTappedUntil;
        if (presence == null) presence = new PresenceAdvertiser(this);
        if (!presence.isRunning()) {
            presence.start(alias, tapped, value ->
                    handler.post(() -> onCredsWritten(value)));
        } else {
            presence.setNfcTapped(tapped);
        }
        // HCE trigger-only (no creds yet; the hotspot SSID/PSK don't exist until bring-up).
        CredsHceService.armTrigger();
        // OS-held wake scan: if this service is later killed by the OEM, a sender's wake
        // beacon fires the system-held scan -> BeaconWakeReceiver -> restarts us. Additive
        // to the presence advertise above; survives our process being killed.
        com.bridge.share.trigger.WakeBeacon.registerWakeScan(getApplicationContext());
        Log.i(TAG, "armed (presence advertise + HCE trigger + OS wake scan); hotspot NOT up (on-demand)");
    }

    /**
     * Bluetooth turned OFF: BLE cannot run with the adapter disabled, so stop ALL BLE
     * activity (presence advertise + the OS-held wake scan) instead of leaving it "armed"
     * and burning battery on listeners that can't function. The NFC/HCE trigger is left
     * armed (NFC is unaffected by BT). The foreground service stays alive (idle, no radio)
     * so the runtime {@link BtStateReceiver} survives to catch BT coming back ON, which
     * re-arms via {@link #armCheapListeners}. An in-flight Wi-Fi-Direct transfer is left
     * running (it rides Wi-Fi, not BT).
     */
    private void pauseForBluetoothOff() {
        com.bridge.share.diag.DiagLog.d(TAG, "bluetooth OFF -> pausing BLE (advertise + wake scan)");
        if (transferActive) {
            Log.i(TAG, "bluetooth off during active transfer; leaving Wi-Fi transfer running");
            return;
        }
        if (presence != null) presence.stop();
        com.bridge.share.trigger.WakeBeacon.unregisterWakeScan(getApplicationContext());
        cancelWatchdog();
        // Reflect the paused state in the ongoing notification; the service stays foreground
        // (cheap, no radio) so we can detect BT turning back on.
        updateNotification("Paused — Bluetooth is off");
        Log.i(TAG, "paused for bluetooth off (NFC trigger still armed)");
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            Notification n = b.setContentTitle("Bridge Share")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .build();
            if (nm != null) nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) {}
    }

    /**
     * On-demand bring-up triggered by a sender's BLE GATT request-host write. The
     * sender writes the FULL creds bytes (its host is already up + serving), so we
     * can join + pull immediately. Acquire a wake lock for the transfer only.
     */
    private void onCredsWritten(byte[] bytes) {
        if (bytes == null || bytes.length == 0) { Log.w(TAG, "empty creds write ignored"); return; }

        final Creds c = Creds.fromBytes(bytes);
        // Reject truncated/garbage writes (e.g. a small-MTU partial write): valid creds
        // always carry an ssid+ip (Direct/Hotspot) or a serviceName (Aware). Without
        // this guard a stray fragment flips transferActive and blocks the real write.
        boolean valid = (c.ssid != null && c.hostIp != null) || c.serviceName != null;
        if (!valid) {
            Log.w(TAG, "ignoring invalid/truncated creds write len=" + bytes.length
                    + " ssid=" + c.ssid + " ip=" + c.hostIp + " svc=" + c.serviceName);
            return;
        }
        if (transferActive) { Log.i(TAG, "transfer already active; ignoring re-trigger"); return; }
        transferActive = true;

        acquireWakeLock();
        armTriggerWatchdog(); // ~90s to reach completion or we tear down

        // Pre-clear any stale Wi-Fi-Direct group NOW (while the user reads the prompt), so
        // the synchronous Accept-time connect() isn't rejected by a lingering group
        // (the repeat-join reason=0 / no-groupFormed race). Async; done before Accept.
        com.bridge.share.conn.WifiP2pConnector.clearStaleGroup(getApplicationContext());

        Log.i(TAG, "creds written -> joining host ssid=" + c.ssid + " ip=" + c.hostIp + ":" + c.port);

        final File saveDir = receivedDir();
        final IncomingTransfer[] incoming = { new IncomingTransfer(
                c.alias != null ? c.alias : "a device", 1, 0L, null) };

        // Real receive card driven by engine events; accept gate is always shown.
        final EngineReceiveController controller = new EngineReceiveController(
                new EngineReceiveController.Decision() {
                    @Override public void onAccept() {
                        // Accept = start pulling. Switch to the shorter post-join idle watchdog.
                        armJoinWatchdog();
                        joinAndPull(c, saveDir, sControllerRef);
                    }
                    @Override public void onDecline() { teardownToArmed(); }
                    @Override public void onCancel() { teardownToArmed(); }
                });
        sControllerRef = controller;

        // Show the accept prompt (overlay or bottom sheet) on the main thread.
        ReceiveUi.show(getApplicationContext(), controller);
        controller.postIncoming(incoming[0]);
    }

    private EngineReceiveController sControllerRef;

    /** After user accepts, join the host and pull the files, mapping events to the card. */
    private void joinAndPull(Creds c, File saveDir, final EngineReceiveController controller) {
        com.bridge.share.diag.DiagLog.d(TAG, "joinAndPull ssid=" + c.ssid + " ip=" + c.hostIp + ":" + c.port
                + " saveDir=" + saveDir.getAbsolutePath());
        // Snapshot existing files so onComplete can tell which are NEW (just received)
        // and publish only those to the gallery.
        final java.util.Set<String> beforeNames = namesIn(saveDir);
        sLastMediaUri = null; // "Open" should reflect THIS transfer's media
        if (engine != null) { engine.stop(); }
        engine = new BridgeEngine(getApplicationContext(), Build.MODEL);
        engine.joinWithCreds(c, saveDir, new BridgeEngine.Events() {
            @Override public void onLog(String msg) { Log.i(TAG, msg); }
            @Override public void onHostPublished(Creds creds) { /* host-only */ }
            @Override public void onJoined(String hostIp, int port) {
                Log.i(TAG, "joined host " + hostIp + ":" + port);
                // Connection established — the foreground hop's job is done. Dismiss it so
                // the user returns to their app; the download continues in the background.
                TransferAcceptActivity.dismissNow();
                armJoinWatchdog(); // reset the idle watchdog now that we're joined
            }
            @Override public void onProgress(ShareRecord record, long done, long total) {
                final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                armJoinWatchdog(); // progress = not idle
                handler.post(() -> { if (controller != null) controller.postProgress(pct); });
            }
            @Override public void onComplete() {
                // Publish newly-received photos/videos into MediaStore (Pictures/Movies)
                // so they show in Gallery/Photos; other files stay in Download + scanned.
                publishNewToGallery(saveDir, beforeNames);
                handler.post(() -> { if (controller != null) controller.postComplete(); });
                onTransferDone();
            }
            @Override public void onError(String msg) {
                Log.w(TAG, "join/pull error: " + msg);
                handler.post(() -> { if (controller != null) controller.postCanceled(); });
                teardownToArmed();
            }
        });
    }

    /** Transfer finished: release wake lock, drop the expensive link, stay armed. */
    private void onTransferDone() {
        Log.i(TAG, "transfer complete -> tear down to armed");
        teardownToArmed();
    }

    /** Drop the expensive link + wake lock; return to the cheap armed state. */
    private void teardownToArmed() {
        cancelWatchdog();
        if (engine != null) { engine.stop(); engine = null; }
        CredsHceService.armTrigger(); // re-arm trigger (creds dropped)
        releaseWakeLock();
        // Clear the foreground scrim (overlay-accept path) now that the transfer ended,
        // so it never lingers as a stuck dim screen.
        TransferAcceptActivity.dismissNow();
        transferActive = false;
        sControllerRef = null;
        // Clear any NFC-tapped advertise flag so a sender doesn't keep auto-picking us after
        // an NFC window/transfer ends.
        if (presence != null) presence.setNfcTapped(false);
        ReceivePrefs.Mode mode = ReceivePrefs.getMode(this);
        if (mode == ReceivePrefs.Mode.OFF) {
            // e.g. an NFC-triggered receive on an OFF device: go fully down, scan included.
            com.bridge.share.trigger.WakeBeacon.unregisterWakeScan(getApplicationContext());
            fullTeardown();
            stopForegroundCompat();
            stopSelf();
        }
        // ALWAYS_ON / TIMED: stay armed — the presence advert stays up so the device keeps
        // being discoverable (TIMED within its 10-min window; ALWAYS_ON indefinitely).
    }

    /** Everything down (server + BLE + hotspot + wake lock). */
    private void fullTeardown() {
        cancelWatchdog();
        if (engine != null) { engine.stop(); engine = null; }
        if (presence != null) { presence.stop(); presence = null; }
        CredsHceService.clear();
        releaseWakeLock();
        transferActive = false;
        sControllerRef = null;
    }

    // ---- watchdogs ----

    private void armTriggerWatchdog() {
        cancelWatchdog();
        watchdog = () -> { Log.i(TAG, "trigger watchdog fired (no completion)"); teardownToArmed(); };
        handler.postDelayed(watchdog, WATCHDOG_TRIGGER_MS);
    }

    private void armJoinWatchdog() {
        cancelWatchdog();
        watchdog = () -> { Log.i(TAG, "join idle watchdog fired"); teardownToArmed(); };
        handler.postDelayed(watchdog, WATCHDOG_JOIN_MS);
    }

    /** After an NFC-initiated receive window with no transfer, return to the persisted
     *  resting state (OFF → fully down; ALWAYS_ON / TIMED → stay armed/advertising). */
    private void armNfcIdleWatchdog() {
        cancelWatchdog();
        watchdog = () -> {
            Log.i(TAG, "NFC-receive idle watchdog fired (no transfer) -> resting state");
            teardownToArmed();
        };
        handler.postDelayed(watchdog, WOKEN_IDLE_MS);
    }

    private void cancelWatchdog() {
        if (watchdog != null) { handler.removeCallbacks(watchdog); watchdog = null; }
    }

    // ---- wake lock ----

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bridge:receive-transfer");
        wakeLock.setReferenceCounted(false);
        try { wakeLock.acquire(WATCHDOG_TRIGGER_MS + WATCHDOG_JOIN_MS); } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    // ---- expiry alarm (Doze-safe) ----

    private PendingIntent expirePendingIntent() {
        Intent i = new Intent(this, ReceiveService.class).setAction(ACTION_EXPIRE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, 0, i, flags);
        }
        return PendingIntent.getService(this, 0, i, flags);
    }

    private void scheduleExpireAlarm(long remainingMs) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long triggerAt = SystemClock.elapsedRealtime() + remainingMs;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, expirePendingIntent());
            Log.i(TAG, "expire alarm set +" + remainingMs + "ms");
        } catch (Exception e) {
            Log.w(TAG, "scheduleExpireAlarm failed: " + e);
        }
    }

    private void cancelExpireAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) { try { am.cancel(expirePendingIntent()); } catch (Exception ignored) {} }
    }

    // ---- misc ----

    private File receivedDir() {
        // Download/ — NOT Pictures/. Under scoped storage (targetSdk 35, no legacy)
        // a raw File write to Pictures/ is denied with EPERM for arbitrary/non-media
        // files (e.g. an .apk), which broke the transfer. Download/ accepts direct
        // File writes; received media is still indexed via MediaScanner below.
        // (Gallery-visible media would need the MediaStore insert API — separate task.)
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(base, "BridgeShare");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** path -> MediaStore content uri, captured during scan so "Open" can view the file. */
    private static final java.util.Map<String, android.net.Uri> sScanned =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The last received photo/video published to MediaStore, so "Open" can view it. */
    private static volatile android.net.Uri sLastMediaUri;
    private static volatile String sLastMediaMime;

    private static java.util.Set<String> namesIn(File dir) {
        java.util.Set<String> s = new java.util.HashSet<>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.isFile()) s.add(f.getName());
        return s;
    }

    /**
     * Publish newly-received files. Photos/videos are INSERTED into MediaStore
     * (Pictures/Movies/BridgeShare) via ContentResolver so they appear in Gallery/Photos
     * (a raw File write to Pictures/ is EPERM under scoped storage; the MediaStore insert
     * is the correct path) and the Download copy is removed. Other file types stay in
     * Download/BridgeShare and are indexed via MediaScanner for the Downloads view.
     */
    private void publishNewToGallery(File dir, java.util.Set<String> before) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null) return;
            java.util.List<String> nonMedia = new java.util.ArrayList<>();
            for (File f : fs) {
                if (!f.isFile() || f.length() == 0 || before.contains(f.getName())) continue;
                String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                boolean video = VIDEO_EXT.matcher(name).matches();
                boolean image = IMAGE_EXT.matcher(name).matches();
                if (image || video) {
                    android.net.Uri u = insertIntoMediaStore(f, video);
                    if (u != null) {
                        sLastMediaUri = u;
                        sLastMediaMime = video ? "video/*" : "image/*";
                        Log.i(TAG, "published to gallery: " + f.getName() + " -> " + u);
                        f.delete(); // remove the Download copy; it now lives in MediaStore
                    } else {
                        nonMedia.add(f.getAbsolutePath()); // insert failed -> keep + scan
                    }
                } else {
                    nonMedia.add(f.getAbsolutePath());
                }
            }
            if (!nonMedia.isEmpty()) {
                android.media.MediaScannerConnection.scanFile(getApplicationContext(),
                        nonMedia.toArray(new String[0]), null,
                        (path, uri) -> { if (uri != null) sScanned.put(path, uri);
                                         Log.i(TAG, "scanned " + path + " -> " + uri); });
            }
        } catch (Exception e) {
            Log.w(TAG, "publishNewToGallery failed: " + e);
        }
    }

    private android.net.Uri insertIntoMediaStore(File f, boolean video) {
        try {
            android.content.ContentResolver cr = getContentResolver();
            android.net.Uri collection = video
                    ? android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeFor(f.getName(), video));
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/BridgeShare");
            v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            android.net.Uri item = cr.insert(collection, v);
            if (item == null) return null;
            try (java.io.InputStream in = new java.io.FileInputStream(f);
                 java.io.OutputStream out = cr.openOutputStream(item)) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            v.clear();
            v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, v, null, null);
            return item;
        } catch (Exception e) {
            Log.w(TAG, "insertIntoMediaStore failed: " + e);
            return null;
        }
    }

    private static String mimeFor(String name, boolean video) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (video) {
            if (n.endsWith(".mkv")) return "video/x-matroska";
            if (n.endsWith(".webm")) return "video/webm";
            if (n.endsWith(".3gp")) return "video/3gpp";
            if (n.endsWith(".mov")) return "video/quicktime";
            if (n.endsWith(".avi")) return "video/x-msvideo";
            return "video/mp4";
        }
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".heic") || n.endsWith(".heif")) return "image/heif";
        if (n.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    private static final java.util.regex.Pattern VIDEO_EXT =
            java.util.regex.Pattern.compile(".*\\.(mp4|mkv|webm|3gp|mov|avi|m4v)$");
    private static final java.util.regex.Pattern IMAGE_EXT =
            java.util.regex.Pattern.compile(".*\\.(jpg|jpeg|png|gif|webp|heic|heif|bmp)$");

    /** "Open" / "View" on the receive card: photo/video -> default viewer; otherwise the
     *  system Downloads view (where received files live). */
    static void openReceived(Context ctx) {
        try {
            // A photo/video was published to MediaStore -> open it in the default viewer.
            if (sLastMediaUri != null) {
                Intent i = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(sLastMediaUri, sLastMediaMime != null ? sLastMediaMime : "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            }
            openDownloadsUi(ctx); // other file types live in Download/BridgeShare
        } catch (Exception e) {
            Log.w(TAG, "openReceived failed: " + e);
            openDownloadsUi(ctx);
        }
    }

    private static void openDownloadsUi(Context ctx) {
        try {
            ctx.startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        cancelExpireAlarm();
        fullTeardown();
        if (btReceiver != null) {
            try { unregisterReceiver(btReceiver); } catch (Exception ignored) {}
            btReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Receiving", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(ReceivePrefs.Mode mode) {
        String text = mode == ReceivePrefs.Mode.TIMED
                ? "Discoverable for 10 minutes" : "Ready to receive";
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Bridge Share")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    /** Foreground type for startForeground on API 34+ where required. */
    static int fgType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        }
        return 0;
    }
}
