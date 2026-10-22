package app.flux;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import app.flux.protocol.ReceiveSink;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

/**
 * Saves received files into Downloads/FLUX using scoped storage (no storage permission needed).
 * Files are created "pending" (invisible to other apps) and only published by commit(), which the protocol calls
 * after the file's size and SHA-256 were verified. abort() deletes the unverified data.
 */
final class MediaSink implements ReceiveSink {
    private static final Uri DOWNLOADS = Uri.parse("content://media/external/downloads"); // == MediaStore.Downloads.EXTERNAL_CONTENT_URI (API 29)
    private final ContentResolver cr;
    private final Map<String, Uri> open = new HashMap<>();
    private final Map<String, String> saved = new java.util.LinkedHashMap<>();

    /** relative path -> content URI of every file that was verified and committed. */
    Map<String, String> saved() { return saved; }

    MediaSink(ContentResolver cr) { this.cr = cr; }

    @Override public OutputStream open(String relPath, long size) throws IOException {
        int slash = relPath.lastIndexOf('/');
        String dir = slash >= 0 ? "/" + relPath.substring(0, slash) : "";
        String name = relPath.substring(slash + 1);
        String mime = URLConnection.guessContentTypeFromName(name);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
        v.put("relative_path", "Download/FLUX" + dir);   // MediaStore.MediaColumns.RELATIVE_PATH
        v.put("is_pending", 1);                           // MediaStore.MediaColumns.IS_PENDING
        Uri uri = cr.insert(DOWNLOADS, v);
        if (uri == null) throw new IOException("couldn't create " + name + " in Downloads/FLUX");
        OutputStream out = cr.openOutputStream(uri);
        if (out == null) { cr.delete(uri, null, null); throw new IOException("couldn't open " + name + " for writing"); }
        open.put(relPath, uri);
        return out;
    }

    @Override public void commit(String relPath) throws IOException {
        Uri uri = open.remove(relPath);
        if (uri == null) throw new IOException("nothing to commit for " + relPath);
        ContentValues v = new ContentValues(); v.put("is_pending", 0);
        if (cr.update(uri, v, null, null) < 1) throw new IOException("couldn't publish " + relPath);
        saved.put(relPath, uri.toString());
    }

    @Override public void abort(String relPath) {
        Uri uri = open.remove(relPath);
        if (uri != null) { try { cr.delete(uri, null, null); } catch (RuntimeException ignored) { } }
    }
}
