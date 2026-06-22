package com.bridge.share.channel;

import android.content.Context;
import android.net.Network;

import java.io.File;
import java.util.List;

import javax.net.SocketFactory;

/**
 * Reconstruction of SHAREit's com.ushareit.nft.channel.impl.DefaultChannel role:
 * the seam between the connected Wi-Fi-Direct link and the file transfer. The
 * HOST runs {@link ShareHttpServer} (content + manifest on the channel port); the
 * CLIENT runs {@link DownloadClient} to pull from it. SHAREit's separate TCP
 * message channel (VDg) is folded into the HTTP manifest endpoint here.
 */
public final class ShareChannel {

    private final Context appCtx;
    private ShareHttpServer server;
    private Thread clientThread;
    private Runnable hostComplete;

    public ShareChannel(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    /** HOST: notified once all offered items have been fully downloaded by the peer. */
    public synchronized void setHostCompleteListener(Runnable r) {
        this.hostComplete = r;
        if (server != null) server.setOnAllServed(r);
    }

    /** HOST: start serving the given items. Returns the channel port (2999). */
    public synchronized int startHost(List<ShareRecord> items) {
        return startHost(items, null);
    }

    /**
     * HOST: start serving the given items, binding the listener to {@code network}
     * (Aware/Hotspot granted Network) when non-null. Returns the channel port (2999).
     */
    public synchronized int startHost(List<ShareRecord> items, Network network) {
        stop();
        server = new ShareHttpServer(appCtx, ShareHttpServer.DEFAULT_PORT, network);
        server.setManifest(items);
        if (hostComplete != null) server.setOnAllServed(hostComplete);
        server.start();
        return server.port();
    }

    /** CLIENT: pull everything the host is offering into saveDir (async). */
    public synchronized void startClient(String hostIp, int port, File saveDir,
                                         DownloadClient.Listener listener) {
        startClient(hostIp, port, saveDir, null, null, false, listener);
    }

    /**
     * CLIENT: pull everything the host is offering into saveDir (async), routing all
     * connections through {@code network} (Aware/Hotspot granted Network) when non-null
     * and bracketing the host in URLs when {@code isIpv6} (Aware data-path).
     */
    public synchronized void startClient(String hostIp, int port, File saveDir,
                                         Network network, SocketFactory socketFactory,
                                         boolean isIpv6,
                                         DownloadClient.Listener listener) {
        final DownloadClient client = new DownloadClient(
                hostIp, port, saveDir, listener, network, socketFactory, isIpv6);
        clientThread = new Thread(client::run, "ShareChannel-client");
        clientThread.setDaemon(true);
        clientThread.start();
    }

    public synchronized void stop() {
        if (server != null) { server.stop(); server = null; }
        if (clientThread != null) { clientThread.interrupt(); clientThread = null; }
    }
}
