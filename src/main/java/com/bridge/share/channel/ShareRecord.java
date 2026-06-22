package com.bridge.share.channel;

/**
 * Reconstruction of SHAREit com.ushareit.nft.channel.ShareRecord, reduced to the
 * fields that the SHAREit /download URL contract actually keys on (see
 * com.ushareit.nft.channel.transmit.DownloadTask):
 *   recordId    -> SHAREit "recordid" / "msgid"
 *   itemId      -> SHAREit "metadataid" (URL-encoded)
 *   contentType -> SHAREit "metadatatype" (photo/video/music/app/file/...)
 *   fileType    -> SHAREit "filetype" (raw | thumbnail)
 * Plus the local descriptors needed to actually serve/store the bytes.
 */
public final class ShareRecord {

    public enum Status { WAITING, PROCESSING, COMPLETED, ERROR }

    /** SHAREit ContentType tokens (com.ushareit.tools.core.lang.ContentType). */
    public static final String CT_PHOTO = "photo";
    public static final String CT_VIDEO = "video";
    public static final String CT_MUSIC = "music";
    public static final String CT_APP   = "app";
    public static final String CT_FILE  = "file";

    public String recordId;       // SHAREit recordid/msgid
    public String itemId;         // SHAREit metadataid
    public String contentType = CT_FILE;
    public String name;           // display file name
    public long   size;          // total bytes
    public String localUri;       // content:// or file path the host serves from
    public String savePath;       // where the joiner writes the received bytes

    public volatile Status status = Status.WAITING;
    public volatile long completed = 0; // bytes transferred so far (resume position)

    public ShareRecord() {}

    public ShareRecord(String recordId, String itemId, String contentType,
                       String name, long size) {
        this.recordId = recordId;
        this.itemId = itemId;
        this.contentType = contentType;
        this.name = name;
        this.size = size;
    }

    @Override
    public String toString() {
        return "ShareRecord[record=" + recordId + ", item=" + itemId + ", type=" + contentType
                + ", name=" + name + ", size=" + size + ", done=" + completed + ", st=" + status + "]";
    }
}
