package com.bridge.share.channel;

import android.content.Context;
import android.net.Network;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reconstruction of SHAREit's content HTTP server (com.lenovo.anyshare.NBg
 * "HttpServer") at the wire-protocol level: a ServerSocket + cached thread pool
 * dispatching requests to handlers by path. We reproduce SHAREit's two relevant
 * endpoints rather than its servlet framework + content library:
 *
 *   GET /msg/collection                         -> JSON manifest of ShareRecords
 *   GET /download?recordid=..&metadataid=..&filetype=raw|thumbnail&position=N
 *       -> raw bytes of the item starting at byte `position` (SHAREit DownloadTask contract)
 *
 * Port defaults to SHAREit's channel port 2999 (DefaultChannel.e). SHAREit's
 * reliable "STP" socket is a native .so; per SHAREit's own fallback, we serve
 * over plain TCP (its ChannelType.TCP path).
 */
public final class ShareHttpServer {

    private static final String TAG = "ShareHttpServer";
    public static final int DEFAULT_PORT = 2999; // SHAREit DefaultChannel.e
    private static final int SO_TIMEOUT_MS = 300_000; // NBg http_server_so_timeout

    private final Context appCtx;
    private final int port;
    /** Aware/Hotspot: bind the ServerSocket to this granted Network; null = default route. */
    private final Network network;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;

    /** Items this host is offering, keyed by recordId|itemId. */
    private final Map<String, ShareRecord> records = new HashMap<>();
    private final List<ShareRecord> manifest = new CopyOnWriteArrayList<>();

    /** Fired once when every offered item has been fully downloaded (host-side "sent"). */
    private volatile Runnable onAllServed;
    private final java.util.Set<String> servedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean allServedFired = new AtomicBoolean(false);
    private final AtomicBoolean fallbackScheduled = new AtomicBoolean(false);
    /** Grace period to wait for the receiver's /complete ack after the last byte is served,
     *  before firing "Sent" anyway (so a lost ack never leaves the sender stuck on "Sending…"). */
    private static final long COMPLETE_FALLBACK_MS = 2500L;

    public void setOnAllServed(Runnable r) { this.onAllServed = r; }

    public ShareHttpServer(Context ctx) { this(ctx, DEFAULT_PORT, null); }

    public ShareHttpServer(Context ctx, int port) { this(ctx, port, null); }

    /** @param network when non-null, the ServerSocket is bound to this granted Network. */
    public ShareHttpServer(Context ctx, int port, Network network) {
        this.appCtx = ctx.getApplicationContext();
        this.port = port;
        this.network = network;
    }

    public int port() { return port; }

    /** Offer a set of items for download; builds the manifest the joiner pulls. */
    public synchronized void setManifest(List<ShareRecord> items) {
        records.clear();
        manifest.clear();
        for (ShareRecord r : items) {
            records.put(key(r.recordId, r.itemId), r);
            manifest.add(r);
        }
    }

    public boolean start() {
        if (running.get()) return true;
        try {
            // When a granted Network is supplied (Aware / Hotspot), bind the listening
            // socket to that Network so it listens on (and accepts via) the granted
            // interface rather than only the default route. We bind the underlying
            // FileDescriptor before bind()/listen() since Network exposes no
            // ServerSocket overload (only Socket / DatagramSocket / FileDescriptor).
            java.nio.channels.ServerSocketChannel channel = null;
            if (network != null) {
                channel = java.nio.channels.ServerSocketChannel.open();
                serverSocket = channel.socket();
                java.io.FileDescriptor fd = fileDescriptorOf(channel);
                if (fd != null) {
                    network.bindSocket(fd);
                } else {
                    Log.w(TAG, "could not resolve ServerSocket fd; binding to default route");
                }
            } else {
                serverSocket = new ServerSocket();
            }
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(port));
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "ShareHttpServer-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "listening on :" + port + (network != null ? " (net=" + network + ")" : ""));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            return false;
        }
    }

    /** Best-effort: pull the FileDescriptor out of a ServerSocketChannel for Network.bindSocket. */
    private static java.io.FileDescriptor fileDescriptorOf(java.nio.channels.ServerSocketChannel channel) {
        try {
            java.lang.reflect.Field f = channel.getClass().getDeclaredField("fd");
            f.setAccessible(true);
            return (java.io.FileDescriptor) f.get(channel);
        } catch (Throwable t) {
            return null;
        }
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket s = serverSocket.accept();
                s.setSoTimeout(SO_TIMEOUT_MS);
                Log.i(TAG, "accepted connection from " + s.getRemoteSocketAddress());
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "accept error: " + e);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            String requestLine = r.readLine();
            if (requestLine == null) return;
            // drain headers
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) { /* ignore */ }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { writeStatus(out, 400, "Bad Request"); return; }
            String target = parts[1];
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }

            Log.i(TAG, "request: " + requestLine);
            if (path.equals("/msg/collection") || path.equals("/manifest")) {
                serveManifest(out);
            } else if (path.equals("/download")) {
                serveDownload(out, parseQuery(query));
            } else if (path.equals("/complete") || path.equals("/done")) {
                // Receiver finished writing ALL files to disk -> it pings here. This is the REAL
                // end of the transfer, so fire "Sent" now (not when we merely flushed the last byte
                // to the socket — that beats the receiver by the socket-buffer + disk-write time).
                writeStatus(out, 200, "OK");
                fireComplete("receiver /complete ack");
            } else {
                writeStatus(out, 404, "Not Found");
            }
        } catch (Exception e) {
            Log.w(TAG, "handle error: " + e);
        }
    }

    private void serveManifest(OutputStream out) throws IOException {
        JSONArray arr = new JSONArray();
        try {
            for (ShareRecord rec : manifest) {
                JSONObject o = new JSONObject();
                o.put("recordid", rec.recordId);
                o.put("metadataid", rec.itemId);
                o.put("metadatatype", rec.contentType);
                o.put("name", rec.name);
                o.put("size", rec.size);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        byte[] body = arr.toString().getBytes(StandardCharsets.UTF_8);
        writeHeader(out, 200, "OK", "application/json", body.length, -1);
        out.write(body);
        out.flush();
        Log.i(TAG, "served manifest: " + manifest.size() + " item(s)");
    }

    /** SHAREit /download: stream the item's bytes from `position`. */
    private void serveDownload(OutputStream out, Map<String, String> p) throws IOException {
        String recordId = p.get("recordid");
        String itemId = p.get("metadataid");
        long position = parseLong(p.get("position"), 0);
        ShareRecord rec = records.get(key(recordId, itemId));
        if (rec == null || rec.localUri == null) {
            Log.w(TAG, "download 404: no record for key " + key(recordId, itemId)
                    + " (have " + records.size() + " record(s))");
            writeStatus(out, 404, "Not Found");
            return;
        }
        Log.i(TAG, "download start: " + rec.name + " size=" + rec.size + " position=" + position);

        InputStream src = openItem(rec);
        if (src == null) { Log.w(TAG, "download 500: cannot open " + rec.localUri); writeStatus(out, 500, "Cannot open"); return; }
        try {
            long remaining = rec.size - position;
            if (remaining < 0) remaining = 0;
            // skip to the requested resume position
            long toSkip = position;
            while (toSkip > 0) {
                long n = src.skip(toSkip);
                if (n <= 0) break;
                toSkip -= n;
            }
            writeHeader(out, 200, "OK", "application/octet-stream", remaining, position);
            byte[] buf = new byte[64 * 1024];
            int n;
            long sent = 0;
            while ((n = src.read(buf)) != -1) {
                out.write(buf, 0, n);
                sent += n;
            }
            out.flush();
            Log.i(TAG, "download done: " + rec.name + " sent " + sent + " bytes");
            // Mark this item fully served. Serving the last byte only means we FLUSHED it to the
            // socket — the receiver still has to drain the socket buffer + write to disk, so firing
            // "Sent" here beats the receiver's "received". Instead, wait for the receiver's /complete
            // ack; only if it never arrives do we fire after COMPLETE_FALLBACK_MS so we never hang.
            if (position + sent >= rec.size) {
                servedKeys.add(key(recordId, itemId));
                if (servedKeys.size() >= records.size() && fallbackScheduled.compareAndSet(false, true)) {
                    Log.i(TAG, "all " + records.size() + " item(s) served -> awaiting receiver /complete ack"
                            + " (fallback " + COMPLETE_FALLBACK_MS + "ms)");
                    Thread t = new Thread(() -> {
                        try { Thread.sleep(COMPLETE_FALLBACK_MS); } catch (InterruptedException ignored) {}
                        fireComplete("no /complete ack within fallback");
                    }, "ShareHttp-complete-fallback");
                    t.setDaemon(true);
                    t.start();
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "download aborted: " + rec.name + " err=" + e);
            throw e;
        } finally {
            try { src.close(); } catch (IOException ignored) {}
        }
    }

    /** Fire the "host transfer complete" notification once (drives the sender's "Sent" state). */
    private void fireComplete(String reason) {
        if (allServedFired.compareAndSet(false, true)) {
            Log.i(TAG, "host transfer complete (" + reason + ")");
            Runnable r = onAllServed;
            if (r != null) r.run();
        }
    }

    private InputStream openItem(ShareRecord rec) {
        try {
            String uri = rec.localUri;
            if (uri.startsWith("content://")) {
                return appCtx.getContentResolver().openInputStream(Uri.parse(uri));
            }
            return new java.io.FileInputStream(uri.startsWith("file://") ? Uri.parse(uri).getPath() : uri);
        } catch (Exception e) {
            Log.w(TAG, "openItem failed: " + e);
            return null;
        }
    }

    // ---- tiny HTTP helpers ----
    private static void writeStatus(OutputStream out, int code, String msg) throws IOException {
        writeHeader(out, code, msg, "text/plain", 0, -1);
        out.flush();
    }

    private static void writeHeader(OutputStream out, int code, String msg, String contentType,
                                    long contentLength, long position) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        if (contentLength >= 0) sb.append("Content-Length: ").append(contentLength).append("\r\n");
        if (position >= 0) sb.append("X-Position: ").append(position).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                      URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return m;
    }

    private static long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s); } catch (Exception e) { return def; }
    }

    private static String key(String recordId, String itemId) {
        return recordId + "|" + itemId;
    }
}
