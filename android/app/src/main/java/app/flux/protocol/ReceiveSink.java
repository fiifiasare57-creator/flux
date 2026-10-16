package app.flux.protocol;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Where received files go. Data is written via open(); commit() is called ONLY after the file's size and SHA-256
 * have been verified, so an unverified or partial file is never presented as received. abort() discards it.
 */
public interface ReceiveSink {
    OutputStream open(String relPath, long size) throws IOException;
    void commit(String relPath) throws IOException;
    void abort(String relPath);
}
