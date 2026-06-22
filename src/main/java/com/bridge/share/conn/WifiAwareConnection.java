package com.bridge.share.conn;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Wi-Fi Aware (NAN) variant of {@link Connection} — see docs/ENGINE_SPEC.md §2.2.
 *
 * Aware has no SSID/PSK and self-discovers by serviceName; the joiner learns the
 * peer's IPv6 + port from {@link WifiAwareNetworkInfo} at connect time (NOT from
 * the trigger payload). The data path is encrypted with a shared
 * {@code pskPassphrase} and brings up zero user prompts on API 29+ with no
 * privilege — gated only on {@link PackageManager#FEATURE_WIFI_AWARE} hardware.
 *
 * Flow (host = publisher):
 *   attach -> publish(serviceName); open ServerSocket(0) for the port; on the
 *   peer's onMessageReceived(peerHandle,..) build
 *   WifiAwareNetworkSpecifier(pubSession, peerHandle).setPskPassphrase().setPort(port)
 *   -> ConnectivityManager.requestNetwork; onAvailable -> onConnected(Endpoint).
 *
 * Flow (joiner = subscriber):
 *   attach -> subscribe(serviceName); onServiceDiscovered(peerHandle) ->
 *   subSession.sendMessage(peerHandle,..) to reveal self ->
 *   WifiAwareNetworkSpecifier(subSession, peerHandle).setPskPassphrase() (no port)
 *   -> requestNetwork; onCapabilitiesChanged -> WifiAwareNetworkInfo
 *   getPeerIpv6Addr()/getPort() -> onConnected(Endpoint).
 *
 * All framework callbacks are delivered on the main looper (the Handler passed to
 * attach/publish/subscribe/requestNetwork), and every Listener call is posted to
 * the main thread.
 */
public final class WifiAwareConnection implements Connection {

    /** Stable serviceName prefix; the body is sanitised onto the end. */
    private static final String SERVICE_PREFIX = "bridgeshare-";

    /** Aware service names are limited to 255 UTF-8 bytes; keep well under. */
    private static final int MAX_SERVICE_NAME_LEN = 48;

    /** Arbitrary message id for the subscriber's reveal message. */
    private static final int MSG_ID_REVEAL = 1;

    /** Minimum passphrase length the framework accepts is 8 chars. */
    private static final int PSK_LEN = 12;

    private final Context ctx;
    private final WifiAwareManager awareManager;
    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Live session/teardown state. Touched only on the main looper.
    private WifiAwareSession awareSession;
    private PublishDiscoverySession pubSession;
    private SubscribeDiscoverySession subSession;
    private ServerSocket serverSocket;
    private ConnectivityManager.NetworkCallback netCallback;
    private boolean stopped;

    public WifiAwareConnection(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.awareManager =
                (WifiAwareManager) this.ctx.getSystemService(Context.WIFI_AWARE_SERVICE);
        this.cm =
                (ConnectivityManager) this.ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public Variant variant() {
        return Variant.WIFI_AWARE;
    }

    @Override
    public boolean isSupported(Context ctx) {
        if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            return false;
        }
        WifiAwareManager wam = (WifiAwareManager)
                ctx.getApplicationContext().getSystemService(Context.WIFI_AWARE_SERVICE);
        return wam != null && wam.isAvailable();
    }

    // ---------------------------------------------------------------- host ----

    @Override
    public void startHost(final String body, final Listener cb) {
        stopped = false;
        if (awareManager == null) {
            fail(cb, "wifi-aware: WifiAwareManager unavailable");
            return;
        }
        final String serviceName = serviceNameFor(body);
        final String passphrase = generatePassphrase();
        log(cb, "wifi-aware: attach (host)…");
        awareManager.attach(new AttachCallback() {
            @Override
            public void onAttached(WifiAwareSession session) {
                if (stopped) { session.close(); return; }
                awareSession = session;
                log(cb, "wifi-aware: attached, publishing '" + serviceName + "'");
                publish(session, serviceName, passphrase, cb);
            }

            @Override
            public void onAttachFailed() {
                fail(cb, "wifi-aware: attach failed (host)");
            }
        }, main);
    }

    private void publish(WifiAwareSession session, final String serviceName,
                         final String passphrase, final Listener cb) {
        final int port;
        try {
            serverSocket = new ServerSocket(0);
            port = serverSocket.getLocalPort();
        } catch (IOException e) {
            fail(cb, "wifi-aware: ServerSocket open failed: " + e.getMessage());
            return;
        }

        // Hand the descriptor to the trigger layer right away; the actual IPv6 +
        // port reach the joiner via WifiAwareNetworkInfo at connect time, so the
        // descriptor only needs serviceName + passphrase (+ port as a hint).
        ConnDescriptor desc = new ConnDescriptor();
        desc.variant = Variant.WIFI_AWARE;
        desc.serviceName = serviceName;
        desc.pskPassphrase = passphrase;
        desc.port = port;
        post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onCredsReady(desc);
            }
        });

        PublishConfig config = new PublishConfig.Builder()
                .setServiceName(serviceName)
                .build();
        session.publish(config, new DiscoverySessionCallback() {
            @Override
            public void onPublishStarted(PublishDiscoverySession session) {
                if (stopped) { session.close(); return; }
                pubSession = session;
                log(cb, "wifi-aware: publish started, awaiting peer");
            }

            @Override
            public void onSessionConfigFailed() {
                fail(cb, "wifi-aware: publish config failed");
            }

            @Override
            public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
                // First contact from the subscriber: now we have the PeerHandle
                // needed to request the data path on the publisher side.
                if (stopped || pubSession == null) return;
                log(cb, "wifi-aware: peer revealed, requesting network (host)");
                NetworkSpecifier spec = new WifiAwareNetworkSpecifier.Builder(pubSession, peerHandle)
                        .setPskPassphrase(passphrase)
                        .setPort(port)
                        .build();
                requestNetwork(spec, port, /*isJoiner=*/false, cb);
            }
        }, main);
    }

    // -------------------------------------------------------------- joiner ----

    @Override
    public void join(final ConnDescriptor desc, final Listener cb) {
        stopped = false;
        if (awareManager == null) {
            fail(cb, "wifi-aware: WifiAwareManager unavailable");
            return;
        }
        if (desc == null || desc.serviceName == null) {
            fail(cb, "wifi-aware: descriptor missing serviceName");
            return;
        }
        log(cb, "wifi-aware: attach (joiner)…");
        awareManager.attach(new AttachCallback() {
            @Override
            public void onAttached(WifiAwareSession session) {
                if (stopped) { session.close(); return; }
                awareSession = session;
                log(cb, "wifi-aware: attached, subscribing '" + desc.serviceName + "'");
                subscribe(session, desc, cb);
            }

            @Override
            public void onAttachFailed() {
                fail(cb, "wifi-aware: attach failed (joiner)");
            }
        }, main);
    }

    private void subscribe(WifiAwareSession session, final ConnDescriptor desc,
                           final Listener cb) {
        SubscribeConfig config = new SubscribeConfig.Builder()
                .setServiceName(desc.serviceName)
                .build();
        session.subscribe(config, new DiscoverySessionCallback() {
            @Override
            public void onSubscribeStarted(SubscribeDiscoverySession session) {
                if (stopped) { session.close(); return; }
                subSession = session;
                log(cb, "wifi-aware: subscribe started, discovering");
            }

            @Override
            public void onSessionConfigFailed() {
                fail(cb, "wifi-aware: subscribe config failed");
            }

            @Override
            public void onServiceDiscovered(PeerHandle peerHandle, byte[] ssi,
                                            java.util.List<byte[]> matchFilter) {
                if (stopped || subSession == null) return;
                // Reveal ourselves to the publisher so it gets our PeerHandle.
                log(cb, "wifi-aware: service discovered, revealing + requesting network");
                subSession.sendMessage(peerHandle, MSG_ID_REVEAL,
                        "bridge.share:hello".getBytes(StandardCharsets.UTF_8));
                // No setPort() on the joiner — the publisher supplies the port via
                // WifiAwareNetworkInfo.getPort().
                NetworkSpecifier spec = new WifiAwareNetworkSpecifier.Builder(subSession, peerHandle)
                        .setPskPassphrase(desc.pskPassphrase)
                        .build();
                requestNetwork(spec, /*hostPortHint=*/desc.port, /*isJoiner=*/true, cb);
            }
        }, main);
    }

    // --------------------------------------------------- shared network req ----

    /**
     * Issue the Aware {@code requestNetwork}. On the host we already know the port
     * (we opened the ServerSocket) and our own address is irrelevant to the
     * Endpoint host field, so we report the host's own loopback-style endpoint via
     * the granted Network. On the joiner we resolve the peer's IPv6 + port from the
     * granted {@link WifiAwareNetworkInfo}.
     */
    private void requestNetwork(NetworkSpecifier spec, final int hostPort,
                                final boolean isJoiner, final Listener cb) {
        if (cm == null) {
            fail(cb, "wifi-aware: ConnectivityManager unavailable");
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(spec)
                .build();

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(final Network network) {
                if (isJoiner) {
                    // Endpoint resolved in onCapabilitiesChanged where the
                    // WifiAwareNetworkInfo (peer IPv6 + port) is present.
                    return;
                }
                // Host: data path is up; serve on the port we already chose.
                if (stopped) return;
                Endpoint ep = new Endpoint();
                ep.network = network;
                ep.socketFactory = network.getSocketFactory();
                ep.port = hostPort;
                ep.isIpv6 = true;
                // Host's own address: the HTTP server binds to all interfaces on
                // the Aware Network; the peer connects to us, so a host literal is
                // not required for the host's own Endpoint.
                ep.host = null;
                connected(cb, ep);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (stopped || !isJoiner) return;
                if (!(caps.getTransportInfo() instanceof WifiAwareNetworkInfo)) return;
                WifiAwareNetworkInfo info = (WifiAwareNetworkInfo) caps.getTransportInfo();
                Inet6Address peer = info.getPeerIpv6Addr();
                int peerPort = info.getPort();
                if (peer == null) return;
                Endpoint ep = new Endpoint();
                ep.host = peer.getHostAddress();
                ep.port = (peerPort > 0) ? peerPort : hostPort;
                ep.network = network;
                ep.socketFactory = network.getSocketFactory();
                ep.isIpv6 = true;
                connected(cb, ep);
            }

            @Override
            public void onUnavailable() {
                fail(cb, "wifi-aware: data path unavailable");
            }
        };
        cm.requestNetwork(request, netCallback, main);
    }

    // -------------------------------------------------------------- teardown --

    @Override
    public void stop() {
        stopped = true;
        if (cm != null && netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
            netCallback = null;
        }
        if (pubSession != null) {
            try { pubSession.close(); } catch (Exception ignored) {}
            pubSession = null;
        }
        if (subSession != null) {
            try { subSession.close(); } catch (Exception ignored) {}
            subSession = null;
        }
        if (awareSession != null) {
            try { awareSession.close(); } catch (Exception ignored) {}
            awareSession = null;
        }
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
    }

    // --------------------------------------------------------------- helpers --

    /** Expose the host-side ServerSocket so the HTTP server can reuse it. */
    public ServerSocket getServerSocket() {
        return serverSocket;
    }

    /** A stable, valid Aware service name derived from the identity body. */
    private static String serviceNameFor(String body) {
        String b = (body == null) ? "" : body.trim();
        // Aware service names: ASCII alphanumeric + '-' '.' '_' are safe.
        StringBuilder sb = new StringBuilder(SERVICE_PREFIX);
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String name = sb.toString();
        if (name.length() > MAX_SERVICE_NAME_LEN) {
            name = name.substring(0, MAX_SERVICE_NAME_LEN);
        }
        return name;
    }

    /** A random alphanumeric passphrase (>= 8 chars) for the Aware data path. */
    private static String generatePassphrase() {
        final String alphabet =
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(PSK_LEN);
        for (int i = 0; i < PSK_LEN; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private void connected(final Listener cb, final Endpoint ep) {
        post(new Runnable() {
            @Override public void run() {
                if (!stopped && cb != null) cb.onConnected(ep);
            }
        });
    }

    private void fail(final Listener cb, final String reason) {
        post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onFailed(reason);
            }
        });
    }

    private void log(final Listener cb, final String msg) {
        post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onLog(msg);
            }
        });
    }

    private void post(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main.post(r);
        }
    }
}
