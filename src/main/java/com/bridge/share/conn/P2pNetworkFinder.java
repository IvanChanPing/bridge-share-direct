package com.bridge.share.conn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import java.net.InetAddress;

/**
 * Finds the {@link Network} object backing the Wi-Fi-Direct group interface so the file
 * transfer can be bound to it.
 *
 * WHY: a VPN (VpnService) captures an app's traffic on the DEFAULT network regardless of
 * routes, so the receiver's outbound HTTP pull to the group owner (192.168.49.1) gets pulled
 * into the VPN tunnel — where the P2P peer is unreachable — and the transfer fails. Binding
 * the socket to the P2P Network (via {@code Network.openConnection}) keeps the pull on the
 * p2p interface, out of the tunnel. Wi-Fi Direct exposes no public NetworkRequest transport
 * (unlike Aware's TRANSPORT_WIFI_AWARE), so we locate the Network by its interface/address/route:
 * the group is always in 192.168.49.0/24 on a "p2p-*" interface.
 *
 * Best-effort: returns null if no matching Network is found (e.g. the OEM doesn't register
 * the P2P interface as a ConnectivityManager Network — observed on these OnePlus phones), in
 * which case the caller falls back to the default route — no regression for the non-VPN case.
 *
 * Diagnostics: this logs EVERY network's transports (vpn/wifi/cell), interface, addresses and
 * whether it carries a route to the Direct subnet, plus whether a VPN is active — so a failing
 * "VPN on" capture pinpoints exactly what the routing looks like.
 */
public final class P2pNetworkFinder {

    private static final String TAG = "P2pNetworkFinder";
    /** Wi-Fi-Direct group-owner subnet is fixed by the framework. */
    private static final String DIRECT_SUBNET_PREFIX = "192.168.49.";

    private P2pNetworkFinder() {}

    /** The non-VPN Network whose iface is p2p-*, or whose address/route is in the Direct subnet. */
    public static Network findDirectNetwork(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;

            Network[] nets = cm.getAllNetworks();
            Network match = null;
            Network wifiNet = null;       // first non-VPN Wi-Fi network (last-resort fallback)
            boolean vpnActive = false;
            StringBuilder dump = new StringBuilder();

            for (Network n : nets) {
                LinkProperties lp = cm.getLinkProperties(n);
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);

                String iface = lp != null ? lp.getInterfaceName() : null;
                boolean isVpn = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                boolean isWifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean isCell = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                if (isVpn) vpnActive = true;

                boolean ifaceIsP2p = iface != null && iface.startsWith("p2p");

                StringBuilder addrs = new StringBuilder();
                boolean addrInSubnet = false;
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        String h = a != null ? a.getHostAddress() : null;
                        if (h != null) {
                            addrs.append(h).append(',');
                            if (h.startsWith(DIRECT_SUBNET_PREFIX)) addrInSubnet = true;
                        }
                    }
                }

                boolean routeToSubnet = false;
                if (lp != null) {
                    for (RouteInfo r : lp.getRoutes()) {
                        if (r.getDestination() != null
                                && String.valueOf(r.getDestination()).contains(DIRECT_SUBNET_PREFIX)) {
                            routeToSubnet = true;
                            break;
                        }
                    }
                }

                dump.append("[iface=").append(iface)
                        .append(" vpn=").append(isVpn)
                        .append(" wifi=").append(isWifi)
                        .append(" cell=").append(isCell)
                        .append(" addrs=").append(addrs)
                        .append(" directRoute=").append(routeToSubnet)
                        .append("] ");

                // NEVER bind the pull to a VPN network (that's the tunnel we're trying to escape).
                if (!isVpn && (ifaceIsP2p || addrInSubnet || routeToSubnet) && match == null) {
                    match = n;
                }
                if (!isVpn && isWifi && wifiNet == null) {
                    wifiNet = n;
                }
            }

            // "Try harder" (user-opted): when a VPN is active and we found NO Direct-subnet network to
            // bind, the default route IS the tunnel and the pull is doomed. As a last resort, bind to the
            // non-VPN Wi-Fi network — on OEMs that run the p2p group in concurrent mode the group owner is
            // reachable that way, and it can't be worse than the captured default route. Gated to
            // vpnActive ONLY, so the working non-VPN (default-route) path is left exactly as-is.
            boolean wifiFallback = false;
            if (match == null && vpnActive && wifiNet != null) {
                match = wifiNet;
                wifiFallback = true;
            }

            com.bridge.share.diag.DiagLog.d(TAG, "networks(" + nets.length + ") vpnActive=" + vpnActive
                    + " " + dump + "-> " + (match != null
                        ? ((wifiFallback ? "WIFI-FALLBACK bind to " : "BOUND to ") + match)
                        : "no P2P network; default route"));
            return match;
        } catch (Throwable t) {
            com.bridge.share.diag.DiagLog.d(TAG, "findDirectNetwork threw: " + t);
        }
        return null;
    }
}
