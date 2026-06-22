package com.bridge.share.conn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ported bite-for-bite from SHAREit Lite com.lenovo.anyshare.JIg ("WifiP2pConnector"):
 * the CLIENT-side Wi-Fi-Direct join that produces NO system "use a temporary
 * Wi-Fi network" dialog. It connects to a group by explicit networkName +
 * passphrase (API 29+), then resolves the group-owner IP via requestConnectionInfo.
 *
 * The only behavioural change vs JIg: the connection result (incl. the group-owner
 * IP) is reported through {@link Callback} instead of SHAREit's internal listener.
 */
public final class WifiP2pConnector {

    private static final String TAG = "WifiP2pConnector";
    private static final int MSG_TIMEOUT = 100;
    private static final long CONNECT_TIMEOUT_MS = 30_000L; // JIg used exoplayer h.n.f2202a (30s)
    private static final int MAX_RETRIES = 4;
    private static final long RETRY_DELAY_MS = 1500L;
    private int retries;

    public interface Callback {
        /** Connected; groupOwnerIp is the host to talk to (e.g. 192.168.49.1). */
        void onConnected(String groupOwnerIp);
        void onResult(boolean success);
    }

    private enum State { INIT, CONNECTING, CONNECTED }

    private final Object lock = new Object();
    private final WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private Callback callback;
    private String networkName;
    private String passphrase;
    private State state = State.INIT;
    private final AtomicBoolean receiverRegistered = new AtomicBoolean(false);
    private final Context appCtx;
    private final Handler timeoutHandler;

    private final WifiP2pManager.ConnectionInfoListener connectionInfoListener =
            new WifiP2pManager.ConnectionInfoListener() {
        @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
            timeoutHandler.removeMessages(MSG_TIMEOUT);
            String go = info.groupOwnerAddress == null ? null : info.groupOwnerAddress.getHostAddress();
            Log.i(TAG, "onConnectionInfoAvailable groupFormed=" + info.groupFormed
                    + " isGO=" + info.isGroupOwner + " owner=" + go);
            state = State.CONNECTED;
            if (callback != null) {
                callback.onConnected(go);
                callback.onResult(true);
            }
        }
    };

    private final WifiP2pManager.ChannelListener channelListener = new WifiP2pManager.ChannelListener() {
        @Override public void onChannelDisconnected() { Log.w(TAG, "channel disconnected"); }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { handleEvent(intent); }
    };

    public WifiP2pConnector(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.manager = (WifiP2pManager) appCtx.getSystemService(Context.WIFI_P2P_SERVICE);
        this.timeoutHandler = new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message msg) {
                if (msg.what == MSG_TIMEOUT) {
                    Log.w(TAG, "connect timeout");
                    finish();
                }
            }
        };
    }

    /** JIg.a(name, pwd, listener): start the no-dialog Wi-Fi-Direct join. */
    public void connect(String networkName, String passphrase, Callback cb) {
        this.callback = cb;
        this.networkName = networkName;
        this.passphrase = passphrase;
        this.state = State.CONNECTING;
        this.retries = 0;
        registerReceiver();
        // Call connect() SYNCHRONOUSLY here so manager.connect() is dispatched while the
        // foreground hop activity is still resumed (importance 100, which the connect
        // requires). The hop then finishes immediately. (The earlier async removeGroup
        // pre-step is dropped: it delayed connect() past the hop's lifetime, and the
        // reason=0 it was meant to fix was actually the background/importance-125 issue,
        // now solved by doing this from a foreground activity.)
        doConnect();
    }

    /**
     * Fire-and-forget: clear any stale Wi-Fi-Direct group on a fresh channel. Call this
     * EARLY (when the receive prompt appears) so by the time the user taps Accept the
     * supplicant is idle and the synchronous Accept-time connect() doesn't get rejected
     * with reason=0 / fail to form a group (the repeat-join race). Async on purpose — it
     * has the prompt-reading time to finish, and is kept OFF the connect() path so the
     * connect stays synchronous within the foreground hop.
     */
    public static void clearStaleGroup(Context ctx) {
        try {
            final WifiP2pManager m = (WifiP2pManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_P2P_SERVICE);
            if (m == null) return;
            final WifiP2pManager.Channel[] ch = new WifiP2pManager.Channel[1];
            ch[0] = m.initialize(ctx.getApplicationContext(),
                    ctx.getApplicationContext().getMainLooper(), null);
            m.removeGroup(ch[0], new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { Log.i(TAG, "clearStaleGroup: removed"); close(); }
                @Override public void onFailure(int r) { Log.i(TAG, "clearStaleGroup: none/failed=" + r); close(); }
                private void close() { try { if (ch[0] != null) ch[0].close(); } catch (Exception ignored) {} }
            });
        } catch (Throwable t) {
            Log.w(TAG, "clearStaleGroup threw: " + t);
        }
    }

    /** JIg.b(): the actual WifiP2pConfig connect (API 29+). */
    private void doConnect() {
        WifiP2pConfig config;
        try {
            config = new WifiP2pConfig.Builder()
                    .setNetworkName(networkName)
                    .setPassphrase(passphrase)
                    .enablePersistentMode(false)
                    .build();
        } catch (Exception e) {
            // e.g. a non-DIRECT- ssid (wrong-flavor creds): report failure, never crash.
            Log.w(TAG, "invalid wifi-direct config ssid=" + networkName + ": " + e);
            finish();
            return;
        }
        logProcessState();
        Log.i(TAG, "doConnectByWifiP2pConfig config: " + config);
        timeoutHandler.removeMessages(MSG_TIMEOUT);
        timeoutHandler.sendEmptyMessageDelayed(MSG_TIMEOUT, CONNECT_TIMEOUT_MS);
        manager.connect(channel(), config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { Log.i(TAG, "connect onSuccess"); }
            @Override public void onFailure(int reason) {
                // reason 0=ERROR / 2=BUSY are transient (the supplicant is mid-teardown
                // of the prior group); they fail ~most rapid retries but succeed on a
                // later try. Retry a few times with a short delay instead of giving up.
                Log.w(TAG, "connect onFailure reason=" + reason + " (retry " + retries + "/" + MAX_RETRIES + ")");
                if (reason != 1 && retries < MAX_RETRIES && state == State.CONNECTING) {
                    retries++;
                    timeoutHandler.postDelayed(() -> { if (state == State.CONNECTING) doConnect(); }, RETRY_DELAY_MS);
                } else {
                    finish();
                }
            }
        });
    }

    /** JIg.a(Intent): on CONNECTION_STATE_CHANGE, resolve connection info or clean up. */
    private void handleEvent(Intent intent) {
        if (!WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(intent.getAction())) return;
        if (manager == null) return;
        NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
        Log.i(TAG, "networkInfo=" + networkInfo);
        if (networkInfo != null && networkInfo.isConnected()) {
            manager.requestConnectionInfo(channel(), connectionInfoListener);
        } else if (networkInfo != null && networkInfo.getState() == NetworkInfo.State.DISCONNECTED) {
            finish();
        }
    }

    /** JIg.c(): timeout/disconnect path — clean up and report the final state. */
    private void finish() {
        timeoutHandler.removeMessages(MSG_TIMEOUT);
        cleanup();
        State s = this.state;
        if (s != State.INIT) {
            if (callback != null) {
                if (s != State.CONNECTED) callback.onResult(false);
            }
            this.state = State.INIT;
        }
    }

    /** Log this app's process importance + foreground state at connect time, so the
     *  log PROVES whether a foreground service is foreground-enough for connect(). */
    private void logProcessState() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) appCtx.getSystemService(Context.ACTIVITY_SERVICE);
            int myPid = android.os.Process.myPid();
            int imp = -1;
            if (am != null && am.getRunningAppProcesses() != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                    if (p.pid == myPid) { imp = p.importance; break; }
                }
            }
            com.bridge.share.diag.DiagLog.d(TAG, "process importance=" + imp + " (" + impName(imp)
                    + ") at connect");
        } catch (Throwable t) {
            com.bridge.share.diag.DiagLog.d(TAG, "importance read failed: " + t);
        }
    }

    private static String impName(int i) {
        switch (i) {
            case 100: return "FOREGROUND";
            case 125: return "FOREGROUND_SERVICE";
            case 200: return "VISIBLE";
            case 230: return "PERCEPTIBLE";
            case 300: return "SERVICE";
            case 400: return "CACHED/BACKGROUND";
            case 1000: return "GONE";
            default: return "code" + i;
        }
    }

    private WifiP2pManager.Channel channel() {
        synchronized (lock) {
            if (channel == null) {
                channel = manager.initialize(appCtx, appCtx.getMainLooper(), channelListener);
            }
        }
        return channel;
    }

    public boolean isActive() { return state == State.CONNECTING || state == State.CONNECTED; }

    private void registerReceiver() {
        if (receiverRegistered.compareAndSet(false, true)) {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            appCtx.registerReceiver(receiver, f);
        }
    }

    private void unregisterReceiver() {
        if (receiverRegistered.compareAndSet(true, false)) {
            try { appCtx.unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
    }

    /** JIg.a()/g(): unregister, remove the group, close the channel. */
    public void cleanup() {
        unregisterReceiver();
        if (manager != null && channel != null) {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {}
                @Override public void onFailure(int reason) {}
            });
        }
        if (channel != null) {
            try { channel.close(); } catch (Exception ignored) {}
            channel = null;
        }
    }
}
