package app.flux.protocol;

public interface TransferListener {
    void onStatus(String text);
    /** Real byte counts only: bytesDone counts bytes the receiver has received (and verified per finished file). */
    void onProgress(long bytesDone, long bytesTotal, String currentFile, int filesDone, int filesTotal);
}
