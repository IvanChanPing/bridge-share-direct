package com.bridge.share.conn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ported bite-for-bite from SHAREit Lite com.lenovo.anyshare.MHg
 * ("WiDiNetworkManagerEx"): the HOST-side Wi-Fi-Direct group owner. It calls
 * createGroup with an explicit DIRECT- networkName + passphrase (API 29+), which
 * brings up a group owner with no system dialog and a known SSID/PSK that can be
 * handed to the joiner out-of-band.
 *
 * Reproduces MHg.e() (doCreateGroup, SDK>=29 branch) exactly:
 *   networkName = SsidHelper.buildDirectNetworkName(...)   ("DIRECT-<band><c>-<body>", <=32)
 *   passphrase  = SsidHelper.directPassphrase(name, wm)    (md5Hex(name)[:8], or "12345678" INVITE)
 *   config      = setNetworkName + setPassphrase + setGroupOperatingBand(is5g?2:1)
 *                 + enablePersistentMode(false)
 *   manager.createGroup(channel, config, listener)
 * and resolves the group-owner IP via requestConnectionInfo (always 192.168.49.1).
 */
public final class WiDiNetworkManager {

    private static final String TAG = "WiDiNetworkManagerEx";

    public interface Callback {
        /** Group is up: hand these creds to the joiner (BLE/NFC). ip is the GO ip. */
        void onGroupReady(String networkName, String passphrase, String groupOwnerIp);
        void onGroupFailed(String reason);
    }

    private final Context appCtx;
    private final WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private final Object lock = new Object();
    private final AtomicBoolean receiverRegistered = new AtomicBoolean(false);

    private Callback callback;
    private WorkMode workMode = WorkMode.INVITE; // trigger flow == out-of-band creds == INVITE
    private boolean is5g = false;
    private String deviceBody;       // identity body in the DIRECT- name
    private String networkName;
    private String passphrase;
    private WifiP2pConfig pendingConfig;     // last createGroup config, kept for a one-shot BUSY retry
    private boolean clearedForBusy = false;  // guard: at most one removeGroup+retry per start()
    private final Handler main = new Handler(Looper.getMainLooper());

    private final WifiP2pManager.ChannelListener channelListener = new WifiP2pManager.ChannelListener() {
        @Override public void onChannelDisconnected() { Log.w(TAG, "channel disconnected"); }
    };

    private final WifiP2pManager.ActionListener createListener = new WifiP2pManager.ActionListener() {
        @Override public void onSuccess() { Log.i(TAG, "createGroup onSuccess"); }
        @Override public void onFailure(int reason) {
            Log.w(TAG, "createGroup onFailure reason=" + reasonStr(reason));
            // reason 2 == BUSY: the framework still holds a stale/lingering P2P group from a prior
            // session/process. Clear it and retry ONCE — done only on this failure path, so a clean
            // host pays no extra delay. If it still fails after the clear, surface the error.
            if (reason == WifiP2pManager.BUSY && !clearedForBusy && pendingConfig != null) {
                clearedForBusy = true;
                Log.w(TAG, "createGroup BUSY -> removeGroup + retry");
                clearGroupThenRetry();
                return;
            }
            if (callback != null) callback.onGroupFailed(reasonStr(reason));
        }
    };

    private final WifiP2pManager.GroupInfoListener groupInfoListener = new WifiP2pManager.GroupInfoListener() {
        @Override public void onGroupInfoAvailable(WifiP2pGroup group) {
            if (group == null) return;
            // MHg.a(WifiP2pGroup): the real on-air SSID/PSK chosen by the framework.
            String ssid = group.getNetworkName();
            String psk = group.getPassphrase();
            Log.i(TAG, "onGroupInfoAvailable ssid=" + ssid + " psk=" + psk);
            WiDiNetworkManager.this.networkName = ssid;
            WiDiNetworkManager.this.passphrase = psk;
            manager.requestConnectionInfo(channel(), connectionInfoListener);
        }
    };

    private final WifiP2pManager.ConnectionInfoListener connectionInfoListener =
            new WifiP2pManager.ConnectionInfoListener() {
        @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
            String go = info.groupOwnerAddress == null ? null : info.groupOwnerAddress.getHostAddress();
            Log.i(TAG, "host connectionInfo groupFormed=" + info.groupFormed + " owner=" + go);
            if (info.groupFormed && info.isGroupOwner && callback != null) {
                callback.onGroupReady(networkName, passphrase, go);
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { handleEvent(intent); }
    };

    public WiDiNetworkManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.manager = (WifiP2pManager) appCtx.getSystemService(Context.WIFI_P2P_SERVICE);
    }

    public void setWorkMode(WorkMode wm) { this.workMode = wm; }
    public void set5g(boolean v) { this.is5g = v; }

    /** Set the identity body to embed in the DIRECT- name (e.g. encoded device name). */
    public void setDeviceBody(String body) { this.deviceBody = body; }

    /** Start hosting: build the config exactly as MHg.e() and createGroup. */
    public void start(Callback cb) {
        this.callback = cb;
        registerReceiver();
        String body = deviceBody != null ? deviceBody : "bridge";
        this.networkName = SsidHelper.buildDirectNetworkName(appCtx, workMode, is5g, body);
        this.passphrase = SsidHelper.directPassphrase(networkName, workMode);
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(passphrase)
                .setGroupOperatingBand(is5g ? 2 : 1)
                .enablePersistentMode(false)
                .build();
        Log.i(TAG, "doCreateGroup config: " + config);
        this.pendingConfig = config;
        this.clearedForBusy = false;
        manager.createGroup(channel(), config, createListener);
    }

    /** BUSY recovery: drop the lingering group, then retry createGroup ONCE. removeGroup's callback
     *  fires whether or not a group existed; a short settle delay lets the framework free the P2P
     *  channel before the retry. */
    private void clearGroupThenRetry() {
        WifiP2pManager.ActionListener afterRemove = new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { retryCreateGroup(); }
            @Override public void onFailure(int reason) { retryCreateGroup(); }
        };
        try {
            manager.removeGroup(channel(), afterRemove);
        } catch (Exception e) {
            retryCreateGroup();
        }
    }

    private void retryCreateGroup() {
        main.postDelayed(() -> {
            if (pendingConfig == null) return;
            Log.i(TAG, "createGroup retry after BUSY clear");
            manager.createGroup(channel(), pendingConfig, createListener);
        }, 500);
    }

    /** MHg handleEvent: on CONNECTION_STATE_CHANGE resolve group/connection info. */
    private void handleEvent(Intent intent) {
        String action = intent.getAction();
        if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
            if (ni != null && ni.isConnected()) {
                manager.requestGroupInfo(channel(), groupInfoListener);
            }
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

    private void registerReceiver() {
        if (receiverRegistered.compareAndSet(false, true)) {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            appCtx.registerReceiver(receiver, f);
        }
    }

    private void unregisterReceiver() {
        if (receiverRegistered.compareAndSet(true, false)) {
            try { appCtx.unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
    }

    /** MHg.k()/i(): remove the group and tear down. */
    public void stop() {
        if (manager != null && channel != null) {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {}
                @Override public void onFailure(int reason) {}
            });
        }
        unregisterReceiver();
        if (channel != null) {
            try { channel.close(); } catch (Exception ignored) {}
            channel = null;
        }
    }

    /** MHg.b(int): map a WifiP2pManager failure code to text. */
    private static String reasonStr(int reason) {
        switch (reason) {
            case 0: return "0/ERROR";
            case 1: return "1/P2P_UNSUPPORTED";
            case 2: return "2/BUSY";
            default: return reason + "/UNKNOWN";
        }
    }
}
