package com.bridge.share.conn;

import android.content.Context;
import android.content.pm.PackageManager;

import com.bridge.share.channel.ShareHttpServer;

/**
 * Wi-Fi Direct (connect-by-config) variant of {@link Connection} — see
 * docs/ENGINE_SPEC.md §2.1. Thin wrapper over the existing, already-ported
 * {@link WiDiNetworkManager} (host {@code createGroup}) and
 * {@link WifiP2pConnector} (client {@code connect}); it adds no link logic of
 * its own. Both wrapped classes already marshal their framework callbacks on the
 * main looper (broadcast receivers registered against {@code getMainLooper()},
 * 30 s connect timeout on the connector), so this class simply forwards their
 * results to the {@link Listener} — no extra threading required.
 *
 * Lifecycle (host):   startHost(body) -> onCredsReady(desc) + onConnected(ep)
 * Lifecycle (joiner): join(desc)      -> onConnected(ep)
 */
public final class WifiDirectConnection implements Connection {

    /** Group-owner address is fixed by the Wi-Fi-Direct framework. */
    private static final String GO_IP = "192.168.49.1";

    private final Context ctx;

    // Exactly one of these is created, depending on the role taken.
    private WiDiNetworkManager host;
    private WifiP2pConnector connector;

    public WifiDirectConnection(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public Variant variant() {
        return Variant.WIFI_DIRECT;
    }

    @Override
    public boolean isSupported(Context ctx) {
        return ctx.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    }

    @Override
    public void startHost(String body, final Listener cb) {
        host = new WiDiNetworkManager(ctx);
        host.setWorkMode(WorkMode.INVITE); // out-of-band creds == INVITE flow
        host.setDeviceBody(body);
        if (cb != null) cb.onLog("WifiDirect: createGroup (INVITE)…");
        host.start(new WiDiNetworkManager.Callback() {
            @Override
            public void onGroupReady(String networkName, String passphrase, String groupOwnerIp) {
                String ip = (groupOwnerIp != null) ? groupOwnerIp : GO_IP;
                ConnDescriptor desc = new ConnDescriptor();
                desc.variant = Variant.WIFI_DIRECT;
                desc.ssid = networkName;
                desc.psk = passphrase;
                desc.hostIp = ip;
                desc.port = ShareHttpServer.DEFAULT_PORT;
                if (cb != null) {
                    cb.onCredsReady(desc);
                    // Host endpoint = its own group-owner ip.
                    cb.onConnected(new Endpoint(ip, ShareHttpServer.DEFAULT_PORT));
                }
            }

            @Override
            public void onGroupFailed(String reason) {
                if (cb != null) cb.onFailed(reason);
            }
        });
    }

    @Override
    public void join(final ConnDescriptor desc, final Listener cb) {
        connector = new WifiP2pConnector(ctx);
        if (cb != null) cb.onLog("WifiDirect: connect to " + desc.ssid + "…");
        connector.connect(desc.ssid, desc.psk, new WifiP2pConnector.Callback() {
            @Override
            public void onConnected(String groupOwnerIp) {
                String host = (groupOwnerIp != null) ? groupOwnerIp : desc.hostIp;
                Endpoint ep = new Endpoint(host, desc.port);
                // VPN compatibility: bind the pull to the Wi-Fi-Direct Network so an active
                // VPN can't capture the outbound HTTP to 192.168.49.1. Null => default route
                // (unchanged behaviour when no P2P Network is exposed / no VPN present).
                ep.network = P2pNetworkFinder.findDirectNetwork(ctx);
                if (cb != null) cb.onConnected(ep);
            }

            @Override
            public void onResult(boolean success) {
                if (!success && cb != null) cb.onFailed("wifi-direct join failed");
            }
        });
    }

    @Override
    public void stop() {
        if (host != null) {
            host.stop();
            host = null;
        }
        if (connector != null) {
            connector.cleanup();
            connector = null;
        }
    }
}
