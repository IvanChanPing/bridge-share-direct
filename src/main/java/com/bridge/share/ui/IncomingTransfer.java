package com.bridge.share.ui;

/** Describes an incoming transfer the receiver is being asked to accept. */
public final class IncomingTransfer {
    public final String peerName;
    public final int fileCount;
    public final long totalBytes;
    public final String firstFileName;

    public IncomingTransfer(String peerName, int fileCount, long totalBytes, String firstFileName) {
        this.peerName = peerName;
        this.fileCount = fileCount;
        this.totalBytes = totalBytes;
        this.firstFileName = firstFileName;
    }

    /** "1 file from Pixel" / "3 files from Galaxy". */
    public String summary() {
        String n = fileCount == 1 ? "1 file" : fileCount + " files";
        return n + " from " + (peerName == null ? "a device" : peerName);
    }
}
