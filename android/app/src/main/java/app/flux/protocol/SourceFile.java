package app.flux.protocol;

import java.io.IOException;
import java.io.InputStream;

/** A file to send. On Android this wraps a content:// URI; on a desktop JVM (tests) a plain file. */
public interface SourceFile {
    /** '/'-separated relative path shown to the receiver (the receiver sanitises it again). */
    String relPath();
    long size();
    long mtimeSeconds();
    InputStream open() throws IOException;
}
