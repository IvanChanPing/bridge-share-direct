package com.bridge.share.conn;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.bridge.share.channel.ShareHttpServer;

/**
 * LocalOnlyHotspot + WifiNetworkSpecifier variant of {@link Connection} — see
 * docs/ENGINE_SPEC.md §2.3. The universal fallback that works without Wi-Fi
 * Aware hardware and without Wi-Fi Direct.
 *
 * Host: {@link WifiManager#startLocalOnlyHotspot} brings up a system-chosen
 * {@code AndroidShare_xxxx} SSID with a random WPA2 passphrase; we read those
 * from the reservation and publish a {@link ConnDescriptor}.
 *
 * Joiner: {@link WifiNetworkSpecifier} + {@link ConnectivityManager#requestNetwork}.
 * NOTE: for a non-privileged (sideloaded) app this ALWAYS shows ONE system
 * "Connect to &lt;SSID&gt;?" dialog → a single user tap on the joiner. That tap is
 * unavoidable here (per reference_silent_wifi_join_constraint_2026_05_29); only a
 * system/priv-app, root, or device-owner can join silently. The granted
 * {@link Network} is handed to the transfer layer via {@link Endpoint#network} so
 * sockets can be bound to it.
 *
 * Lifecycle (host):   startHost(body) -> onCredsReady(desc) + onConnected(ep)
 * Lifecycle (joiner): join(desc)      -> onConnected(ep)
 * All {@link Listener} callbacks are posted on the main thread.
 */
public final class HotspotConnection implements Connection {

    /**
     * The LocalOnlyHotspot gateway address is fixed by the Android framework's
     * tethering/SoftAp stack; used as a fallback when DhcpInfo is unavailable.
     */
    private static final String LOHS_GATEWAY = "192.168.43.1";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Host state: the live hotspot reservation (kept until stop()).
    private WifiManager.LocalOnlyHotspotReservation reservation;

    // Joiner state: the network request callback (kept until stop()).
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;

    public HotspotConnection(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public Variant variant() {
        return Variant.HOTSPOT;
    }

    @Override
    public boolean isSupported(Context ctx) {
        return ctx.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_WIFI);
    }

    // -------------------------------------------------------------------------
    // HOST
    // -------------------------------------------------------------------------

    @Override
    public void startHost(String body, final Listener cb) {
        final WifiManager wifi =
                (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            post(cb, () -> { if (cb != null) cb.onFailed("hotspot: no WifiManager"); });
            return;
        }
        if (cb != null) cb.onLog("Hotspot: startLocalOnlyHotspot…");
        // LocalOnlyHotspotCallback fires on the supplied handler's thread (main).
        wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                reservation = res;

                String ssid = null;
                String psk = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // API 30+: WifiConfiguration is deprecated for LOHS; use
                    // SoftApConfiguration.
                    android.net.wifi.SoftApConfiguration sac = res.getSoftApConfiguration();
                    if (sac != null) {
                        ssid = sac.getSsid();
                        psk = sac.getPassphrase();
                    }
                } else {
                    // API 29: only WifiConfiguration is available.
                    WifiConfiguration wc = res.getWifiConfiguration();
                    if (wc != null) {
                        // SSID comes quoted from the framework — strip the quotes.
                        ssid = unquote(wc.SSID);
                        psk = wc.preSharedKey;
                    }
                }

                final String hostIp = gatewayIp(wifi);

                ConnDescriptor desc = new ConnDescriptor();
                desc.variant = Variant.HOTSPOT;
                desc.ssid = ssid;
                desc.psk = psk;
                desc.hostIp = hostIp;
                desc.port = ShareHttpServer.DEFAULT_PORT;

                if (cb != null) {
                    cb.onLog("Hotspot up: " + desc.ssid + " @ " + hostIp);
                    cb.onCredsReady(desc);
                    // Host endpoint = its own LOHS gateway; default route, no
                    // bound Network (host owns the hotspot interface).
                    cb.onConnected(new Endpoint(hostIp, ShareHttpServer.DEFAULT_PORT));
                }
            }

            @Override
            public void onFailed(int reason) {
                reservation = null;
                if (cb != null) cb.onFailed("hotspot start failed: " + reasonStr(reason));
            }

            @Override
            public void onStopped() {
                if (cb != null) cb.onLog("Hotspot stopped");
            }
        }, main);
    }

    // -------------------------------------------------------------------------
    // JOINER
    // -------------------------------------------------------------------------

    @Override
    public void join(final ConnDescriptor desc, final Listener cb) {
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            if (cb != null) cb.onFailed("hotspot join: no ConnectivityManager");
            return;
        }

        // Build the specifier from the host's SSID + WPA2 passphrase.
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(desc.ssid)
                .setWpa2Passphrase(desc.psk)
                .build();

        // Request a Wi-Fi network matching that specifier, with no internet
        // capability (LOHS has no upstream). requestNetwork triggers ONE system
        // "Connect to <SSID>?" dialog — a single unavoidable joiner tap for a
        // non-privileged app.
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();

        if (cb != null) cb.onLog("Hotspot: requesting join to " + desc.ssid
                + " (system Connect? tap)…");

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // Hand the granted Network to the transfer layer so it can bind
                // its sockets to this (non-default, internet-less) network.
                Endpoint ep = new Endpoint(desc.hostIp, desc.port);
                ep.network = network;
                if (cb != null) cb.onConnected(ep);
            }

            @Override
            public void onUnavailable() {
                if (cb != null) cb.onFailed("hotspot join not confirmed");
            }

            @Override
            public void onLost(Network network) {
                if (cb != null) cb.onLog("Hotspot network lost");
            }
        };

        // Callbacks are posted to the main thread via our Handler.
        cm.requestNetwork(req, netCallback, main);
    }

    // -------------------------------------------------------------------------
    // TEARDOWN
    // -------------------------------------------------------------------------

    @Override
    public void stop() {
        if (reservation != null) {
            reservation.close(); // drops the LocalOnlyHotspot
            reservation = null;
        }
        if (cm != null && netCallback != null) {
            try {
                cm.unregisterNetworkCallback(netCallback);
            } catch (IllegalArgumentException ignored) {
                // already unregistered — idempotent teardown.
            }
            netCallback = null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** LOHS gateway from DhcpInfo if exposed, else the framework default. */
    private static String gatewayIp(WifiManager wifi) {
        try {
            DhcpInfo dhcp = wifi.getDhcpInfo();
            if (dhcp != null && dhcp.serverAddress != 0) {
                int a = dhcp.serverAddress; // little-endian int
                return (a & 0xff) + "." + ((a >> 8) & 0xff) + "."
                        + ((a >> 16) & 0xff) + "." + ((a >> 24) & 0xff);
            }
        } catch (Throwable ignored) {
            // fall through to default below
        }
        return LOHS_GATEWAY;
    }

    /** WifiConfiguration.SSID is wrapped in double quotes on API 29; strip them. */
    private static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String reasonStr(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "no channel";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
                return "generic";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "incompatible mode";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "tethering disallowed";
            default:
                return "code " + reason;
        }
    }

    private void post(Listener cb, Runnable r) {
        main.post(r);
    }
}
