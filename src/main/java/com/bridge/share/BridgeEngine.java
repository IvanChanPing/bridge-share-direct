package com.bridge.share;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.bridge.share.channel.DownloadClient;
import com.bridge.share.channel.ShareChannel;
import com.bridge.share.channel.ShareRecord;
import com.bridge.share.conn.ConnDescriptor;
import com.bridge.share.conn.Connection;
import com.bridge.share.conn.ConnectionFactory;
import com.bridge.share.conn.Endpoint;
import com.bridge.share.trigger.Creds;
import com.bridge.share.trigger.CredsBleClient;
import com.bridge.share.trigger.CredsBleServer;
import com.bridge.share.trigger.CredsHceService;
import com.bridge.share.trigger.CredsNfcReader;

import java.io.File;
import java.util.List;

/**
 * Public engine facade. Composes the three layers:
 *   - {@link Connection}   : pluggable link (Wi-Fi Direct / Wi-Fi Aware / Hotspot),
 *                            chosen by build flavor via {@link ConnectionFactory}
 *   - {@link ShareChannel} : SHAREit's HTTP transfer (manifest + /download)
 *   - trigger.*            : the NFC + BLE creds exchange (the only deviation)
 *
 * HOST flow:  startHostShare(items) -> conn.startHost -> onCredsReady(desc) =>
 *             serve items over HTTP + publish Creds over BLE advertise + NFC HCE;
 *             onConnected(ep) => link is up.
 * JOIN flow:  joinViaBle / joinViaNfc -> read Creds -> conn.join(desc) ->
 *             onConnected(ep) => pull all items via the HTTP channel into saveDir.
 *
 * UI is intentionally NOT part of the engine.
 */
public final class BridgeEngine {

    private static final String TAG = "BridgeEngine";

    public interface Events {
        void onLog(String msg);
        void onHostPublished(Creds creds);
        void onJoined(String hostIp, int port);
        void onProgress(ShareRecord record, long done, long total);
        void onComplete();
        void onError(String msg);
    }

    private final Context appCtx;
    private final String alias;
    private final Connection connection;
    private final ShareChannel channel;
    private CredsBleServer bleServer;
    private CredsBleClient bleClient;

    public BridgeEngine(Context ctx, String alias) {
        this.appCtx = ctx.getApplicationContext();
        this.alias = alias != null ? alias : Build.MODEL;
        this.connection = ConnectionFactory.create(appCtx);
        this.channel = new ShareChannel(appCtx);
    }

    /** HOST: bring up the link, serve `items`, and publish creds over BLE+NFC. */
    public void startHostShare(final List<ShareRecord> items, final Events ev) {
        // Remember the offered items so onConnected can re-bind the server to the
        // granted Network (Aware/Hotspot) once the link is up.
        final List<ShareRecord> hostItems = items;
        // The Wi-Fi-P2P CONNECTION_CHANGED broadcast fires onCredsReady repeatedly;
        // start the HTTP server + publish creds exactly ONCE, else each re-fire
        // rebinds port 2999 and the binds race into EADDRINUSE (host serves nothing).
        final java.util.concurrent.atomic.AtomicBoolean published =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        connection.startHost(alias, new Connection.Listener() {
            @Override public void onCredsReady(ConnDescriptor desc) {
                if (!published.compareAndSet(false, true)) {
                    ev.onLog("onCredsReady repeat ignored (already serving on this session)");
                    return;
                }
                // Start serving now; the channel owns the actual listen port.
                int port = channel.startHost(items);
                // Host-side completion: when the peer has fully downloaded every item,
                // tell the sender UI so it leaves "Sending" and shows the finished state.
                channel.setHostCompleteListener(() -> ev.onComplete());

                Creds creds = new Creds();
                creds.alias = alias;
                creds.ssid = desc.ssid;
                creds.psk = desc.psk;
                creds.hostIp = desc.hostIp;
                // Aware has no fixed transfer port in the descriptor; for all
                // variants the channel's real listen port is authoritative.
                creds.port = port;
                // Aware-only fields (null for Direct/Hotspot) — let the joiner
                // rebuild the Aware descriptor (serviceName + data-path psk).
                creds.serviceName = desc.serviceName;
                creds.pskPassphrase = desc.pskPassphrase;

                // BLE advertise the creds.
                bleServer = new CredsBleServer(appCtx, m -> ev.onLog("[ble] " + m));
                bleServer.start(creds);
                // NFC HCE serve the creds on tap.
                CredsHceService.setCreds(creds.toBytes());

                ev.onHostPublished(creds);
                ev.onLog("creds published (" + desc.variant + "): ssid=" + desc.ssid
                        + " svc=" + desc.serviceName + " ip=" + desc.hostIp + ":" + port);
            }

            @Override public void onConnected(Endpoint ep) {
                // The channel HTTP server was started in onCredsReady on the default
                // route. For Aware/Hotspot the granted Network only exists now, so
                // re-bind the server to it so it listens on the granted interface.
                if (ep != null && ep.network != null) {
                    int port = channel.startHost(hostItems, ep.network);
                    ev.onLog("host server re-bound to granted Network on :" + port
                            + " (" + endpointDesc(ep) + ")");
                } else {
                    ev.onLog("host link up: " + endpointDesc(ep));
                }
            }

            @Override public void onFailed(String reason) { ev.onError("host failed: " + reason); }
            @Override public void onLog(String msg) { ev.onLog(msg); }
        });
    }

    /** JOINER: discover the host's creds over BLE, then connect + pull into saveDir. */
    public void joinViaBle(final File saveDir, final Events ev) {
        bleClient = new CredsBleClient(appCtx, new CredsBleClient.Callback() {
            @Override public void onCreds(Creds creds) { joinWithCreds(creds, saveDir, ev); }
            @Override public void onError(String msg) { ev.onError("[ble] " + msg); }
            @Override public void onLog(String msg) { ev.onLog("[ble] " + msg); }
        });
        bleClient.start();
    }

    /** JOINER: read the host's creds via an NFC tap, then connect + pull into saveDir. */
    public CredsNfcReader joinViaNfc(final Activity activity, final File saveDir, final Events ev) {
        CredsNfcReader reader = new CredsNfcReader(activity, new CredsNfcReader.Callback() {
            @Override public void onCreds(Creds creds) { joinWithCreds(creds, saveDir, ev); }
            @Override public void onError(String msg) { ev.onError("[nfc] " + msg); }
            @Override public void onLog(String msg) { ev.onLog("[nfc] " + msg); }
            @Override public void onTriggerSentScanBle() { joinViaBle(saveDir, ev); }
        });
        reader.enable();
        return reader;
    }

    /**
     * JOINER (direct entry): connect using creds already received out-of-band
     * (e.g. a BLE GATT request-host write to our presence server) and pull all
     * items into {@code saveDir}. Public so {@code ReceiveService} can drive the
     * on-demand join without re-running BLE/NFC discovery.
     */
    public void joinWithCreds(final Creds creds, final File saveDir, final Events ev) {
        joinWithCredsInternal(creds, saveDir, ev);
    }

    private void joinWithCredsInternal(final Creds creds, final File saveDir, final Events ev) {
        ev.onLog("joining: ssid=" + creds.ssid + " svc=" + creds.serviceName
                + " ip=" + creds.hostIp + ":" + creds.port);

        // Rebuild the variant-agnostic descriptor from the received creds. The
        // variant is fixed by THIS build's flavor (both ends ship the same one).
        ConnDescriptor desc = new ConnDescriptor();
        desc.variant = variantForBuild();
        desc.alias = creds.alias;
        desc.ssid = creds.ssid;
        desc.psk = creds.psk;
        desc.hostIp = creds.hostIp;
        desc.port = creds.port;
        desc.serviceName = creds.serviceName;
        desc.pskPassphrase = creds.pskPassphrase;

        connection.join(desc, new Connection.Listener() {
            @Override public void onCredsReady(ConnDescriptor d) { /* host-only */ }

            @Override public void onConnected(Endpoint ep) {
                // For Aware the host IP/port come from the Endpoint (resolved via
                // WifiAwareNetworkInfo); for Direct/Hotspot fall back to creds.
                String host = (ep.host != null) ? ep.host : creds.hostIp;
                int port = (ep.port > 0) ? ep.port : creds.port;
                ev.onJoined(host, port);

                // Plumb the granted Network / SocketFactory / IPv6 flag (non-null
                // for Aware, and the granted Network for Hotspot) into the transfer
                // so the manifest + /download connections egress on the granted
                // interface. Direct/Hotspot-default => ep.network null => default route.
                if (ep.network != null) {
                    ev.onLog("transfer bound to granted Network (" + endpointDesc(ep) + ")");
                }

                channel.startClient(host, port, saveDir,
                        ep.network, ep.socketFactory, ep.isIpv6,
                        new DownloadClient.Listener() {
                    @Override public void onManifest(List<ShareRecord> records) {
                        ev.onLog("manifest: " + records.size() + " item(s)");
                    }
                    @Override public void onProgress(ShareRecord record, long completed, long total) {
                        ev.onProgress(record, completed, total);
                    }
                    @Override public void onRecordDone(ShareRecord record) { ev.onLog("done: " + record.name); }
                    @Override public void onAllDone() { ev.onComplete(); }
                    @Override public void onError(String message) { ev.onError(message); }
                });
            }

            @Override public void onFailed(String reason) { ev.onError(reason); }
            @Override public void onLog(String msg) { ev.onLog(msg); }
        });
    }

    /** Register the HCE on-demand host trigger (a tap with no creds yet starts hosting). */
    public void registerNfcHostTrigger(final Runnable startHosting) {
        CredsHceService.setHostTrigger(ctx -> {
            Log.i(TAG, "NFC request-host -> starting host");
            startHosting.run();
        });
    }

    public void stop() {
        if (bleServer != null) { bleServer.stop(); bleServer = null; }
        if (bleClient != null) { bleClient.stop(); bleClient = null; }
        CredsHceService.clear();
        channel.stop();
        connection.stop();
    }

    /** The {@link Connection.Variant} this build flavor selected. */
    private static Connection.Variant variantForBuild() {
        switch (BuildConfig.ENGINE) {
            case "WIFI_AWARE": return Connection.Variant.WIFI_AWARE;
            case "HOTSPOT":    return Connection.Variant.HOTSPOT;
            default:           return Connection.Variant.WIFI_DIRECT;
        }
    }

    private static String endpointDesc(Endpoint ep) {
        if (ep == null) return "<null>";
        String h = ep.isIpv6 && ep.host != null ? "[" + ep.host + "]" : String.valueOf(ep.host);
        return h + ":" + ep.port + (ep.network != null ? " net=" + ep.network : "");
    }
}
