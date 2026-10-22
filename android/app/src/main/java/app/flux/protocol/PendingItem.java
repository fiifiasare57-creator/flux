package app.flux.protocol;

import java.util.List;

/** Files the PC has queued for this phone (from a non-consuming peek). */
public final class PendingItem {
    public final String transferId;
    public final List<String> names;
    public final List<Long> sizes;
    public final long totalBytes;
    public final int fileCount;
    public PendingItem(String transferId, List<String> names, List<Long> sizes, long totalBytes, int fileCount) {
        this.transferId = transferId; this.names = names; this.sizes = sizes; this.totalBytes = totalBytes; this.fileCount = fileCount;
    }
}
