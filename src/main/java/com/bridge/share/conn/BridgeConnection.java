package com.bridge.share.conn;

import android.content.Context;
import android.util.Log;

/**
 * Trigger-driven orchestrator that plays the role of SHAREit's
 * com.ushareit.nft.discovery.wifi.WifiMaster, but without SHAREit's Wi-Fi-scan /
 * BLE-radar discovery loop — discovery is replaced by the out-of-band NFC/BLE
 * creds exchange (the sanctioned deviation). The actual link establishment is
 * SHAREit's exact method:
 *   HOST   -> {@link WiDiNetworkManager} (createGroup, DIRECT- name + md5 passphrase)
 *   CLIENT -> {@link WifiP2pConnector}   (connect by networkName + passphrase)
 *
 * NetworkStatus mirrors WifiMaster: IDLE / SERVER (host) / CLIENT (joiner).
 */
public final class BridgeConnection {

    private static final String TAG = "BridgeConnection";

    public interface HostCallback {
        void onHostReady(String networkName, String passphrase, String groupOwnerIp);
        void onHostFailed(String reason);
    }

    public interface JoinCallback {
        void onJoined(String groupOwnerIp);
        void onJoinFailed();
    }

    private final Context appCtx;
    private NetworkStatus status = NetworkStatus.IDLE;
    private WiDiNetworkManager host;
    private WifiP2pConnector client;

    public BridgeConnection(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public synchronized NetworkStatus status() { return status; }

    /** Become the Wi-Fi-Direct group owner (SHAREit SERVER mode), INVITE work mode. */
    public synchronized void startHost(String deviceBody, final HostCallback cb) {
        stop();
        status = NetworkStatus.SERVER;
        host = new WiDiNetworkManager(appCtx);
        host.setWorkMode(WorkMode.INVITE);
        host.setDeviceBody(deviceBody);
        host.start(new WiDiNetworkManager.Callback() {
            @Override public void onGroupReady(String name, String psk, String ip) {
                Log.i(TAG, "host ready name=" + name + " ip=" + ip);
                cb.onHostReady(name, psk, ip);
            }
            @Override public void onGroupFailed(String reason) {
                Log.w(TAG, "host failed: " + reason);
                cb.onHostFailed(reason);
            }
        });
    }

    /** Join a peer's group (SHAREit CLIENT mode) using creds from NFC/BLE. */
    public synchronized void join(String networkName, String passphrase, final JoinCallback cb) {
        stop();
        status = NetworkStatus.CLIENT;
        client = new WifiP2pConnector(appCtx);
        client.connect(networkName, passphrase, new WifiP2pConnector.Callback() {
            @Override public void onConnected(String groupOwnerIp) { cb.onJoined(groupOwnerIp); }
            @Override public void onResult(boolean success) { if (!success) cb.onJoinFailed(); }
        });
    }

    public synchronized void stop() {
        if (host != null) { host.stop(); host = null; }
        if (client != null) { client.cleanup(); client = null; }
        status = NetworkStatus.IDLE;
    }
}
