package com.bridge.share.channel;

import android.net.Network;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.SocketFactory;

/**
 * Reconstruction of SHAREit's CLIENT download
 * (com.ushareit.nft.channel.transmit.DownloadTask): pull the manifest, then GET
 * each item from the host's HTTP server using SHAREit's exact /download URL
 * contract, with byte-position resume.
 *
 *   manifest : GET http://<ip>:<port>/msg/collection
 *   item     : GET http://<ip>:<port>/download?recordid=..&metadatatype=..
 *                   &metadataid=<urlenc>&filetype=raw&msgid=..&position=<completed>
 */
public final class DownloadClient {

    private static final String TAG = "DownloadClient";

    public interface Listener {
        void onManifest(List<ShareRecord> records);
        void onProgress(ShareRecord record, long completed, long total);
        void onRecordDone(ShareRecord record);
        void onAllDone();
        void onError(String message);
    }

    private final String host;
    private final int port;
    private final File saveDir;
    private final Listener listener;
    /** Aware/Hotspot: open all connections through this granted Network; null = default route. */
    private final Network network;
    /** Aware: network.getSocketFactory() (currently informational; egress is via Network). */
    private final SocketFactory socketFactory;
    /** true for Aware (IPv6 literal host must be bracketed in the URL). */
    private final boolean isIpv6;
    private volatile boolean cancelled;

    public DownloadClient(String host, int port, File saveDir, Listener listener) {
        this(host, port, saveDir, listener, null, null, false);
    }

    public DownloadClient(String host, int port, File saveDir, Listener listener,
                          Network network, SocketFactory socketFactory, boolean isIpv6) {
        this.host = host;
        this.port = port;
        this.saveDir = saveDir;
        this.listener = listener;
        this.network = network;
        this.socketFactory = socketFactory;
        this.isIpv6 = isIpv6;
    }

    public void cancel() { cancelled = true; }

    /** Run the full pull (call on a background thread). */
    public void run() {
        try {
            List<ShareRecord> records = fetchManifestWithRetry();
            if (listener != null) listener.onManifest(records);
            if (!saveDir.exists()) saveDir.mkdirs();
            for (ShareRecord rec : records) {
                if (cancelled) return;
                download(rec);
                if (listener != null) listener.onRecordDone(rec);
            }
            // Tell the sender we've finished writing EVERYTHING to disk, so its "Sent" UI fires in
            // step with our "received" instead of when it merely flushed the last byte to the socket.
            sendCompleteAck();
            if (listener != null) listener.onAllDone();
        } catch (Exception e) {
            Log.e(TAG, "download run failed", e);
            if (listener != null) listener.onError(String.valueOf(e));
        }
    }

    /** Best-effort ping to the sender's /complete endpoint once all files are on disk. */
    private void sendCompleteAck() {
        if (cancelled) return;
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://" + hostForUrl() + ":" + port + "/complete");
            c = openHttp(url);
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode(); // forces the request to be sent
            Log.i(TAG, "complete ack -> HTTP " + code);
        } catch (Exception e) {
            // Non-fatal: the sender has a fallback timer and still finalizes "Sent".
            Log.w(TAG, "complete ack failed (sender will fall back): " + e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private List<ShareRecord> fetchManifest() throws Exception {
        URL url = new URL("http://" + hostForUrl() + ":" + port + "/msg/collection");
        HttpURLConnection c = openHttp(url);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            List<ShareRecord> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ShareRecord rec = new ShareRecord(
                        o.optString("recordid"), o.optString("metadataid"),
                        o.optString("metadatatype", ShareRecord.CT_FILE),
                        o.optString("name"), o.optLong("size"));
                rec.savePath = new File(saveDir, safeName(rec.name)).getAbsolutePath();
                out.add(rec);
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    /**
     * Manifest is the FIRST contact with the group owner. On a fresh Wi-Fi-Direct join the
     * client's DHCP/route to 192.168.49.1 often isn't up for a few hundred ms after the group
     * forms, so the very first connect can fail fast with ConnectException ("no route"/refused).
     * VERIFIED on real phones: that single failure used to abort the whole transfer (works only
     * "oftentimes"). Retry the connect a handful of times with a short delay so the route has time
     * to come up. Only IOExceptions (connect/read) are retried — JSON/parse errors propagate.
     */
    private List<ShareRecord> fetchManifestWithRetry() throws Exception {
        final int attempts = 6;        // ~2.5s total of retry window (5 × 500ms gaps)
        final long delayMs = 500L;
        java.io.IOException last = null;
        for (int i = 0; i < attempts; i++) {
            if (cancelled) throw new java.io.IOException("cancelled");
            try {
                if (i > 0) Log.i(TAG, "fetchManifest retry " + i + "/" + (attempts - 1));
                return fetchManifest();
            } catch (java.io.IOException e) {
                last = e;
                Log.w(TAG, "fetchManifest attempt " + (i + 1) + "/" + attempts
                        + " failed (" + e + ")" + (i < attempts - 1 ? " — retrying in " + delayMs + "ms" : ""));
                if (i < attempts - 1) {
                    try { Thread.sleep(delayMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                }
            }
        }
        throw last; // exhausted: surface the last connect failure to onError
    }

    /** SHAREit DownloadTask URL build + position resume. */
    private void download(ShareRecord rec) throws Exception {
        File dst = new File(rec.savePath);
        long position = dst.exists() ? dst.length() : 0;
        rec.completed = position;
        String spec = "http://" + hostForUrl() + ":" + port + "/download"
                + "?recordid=" + enc(rec.recordId)
                + "&metadatatype=" + enc(rec.contentType)
                + "&metadataid=" + enc(rec.itemId)
                + "&filetype=raw"
                + "&msgid=" + enc(rec.recordId)
                + "&position=" + position;
        URL url = new URL(spec);
        HttpURLConnection c = openHttp(url);
        c.setConnectTimeout(15000);
        c.setReadTimeout(300000);
        rec.status = ShareRecord.Status.PROCESSING;
        try (InputStream in = c.getInputStream();
             OutputStream os = new FileOutputStream(dst, position > 0)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long done = position;
            long lastReport = 0;
            while ((n = in.read(buf)) != -1) {
                if (cancelled) { rec.status = ShareRecord.Status.ERROR; return; }
                os.write(buf, 0, n);
                done += n;
                rec.completed = done;
                if (listener != null && (done - lastReport > 256 * 1024 || done == rec.size)) {
                    listener.onProgress(rec, done, rec.size);
                    lastReport = done;
                }
            }
            os.flush();
            rec.status = ShareRecord.Status.COMPLETED;
        } finally {
            c.disconnect();
        }
    }

    /** Bracket an IPv6 literal host for use in an http:// URL (Aware data-path). */
    private String hostForUrl() {
        if (isIpv6 && host != null && !host.startsWith("[")) return "[" + host + "]";
        return host;
    }

    /**
     * Open an HTTP connection, routed through the granted Network when supplied.
     * Network.openConnection(url) returns a URLConnection already bound to that
     * Network; otherwise fall back to the default-route HttpURLConnection.
     */
    private HttpURLConnection openHttp(URL url) throws Exception {
        URLConnection c = (network != null) ? network.openConnection(url) : url.openConnection();
        return (HttpURLConnection) c;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static String safeName(String name) {
        if (name == null || name.isEmpty()) return "file_" + System.currentTimeMillis();
        return name.replaceAll("[/\\\\:*?\"<>|]", "_");
    }
}
