package app.flux;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import app.flux.protocol.SourceFile;

import java.io.*;

/** A file chosen by the user (content:// URI). Streams from the provider; never loads the file into memory. */
final class UriSource implements SourceFile {
    private final ContentResolver cr;
    private Uri uri;
    private final String name;
    private long size;

    private UriSource(ContentResolver cr, Uri uri, String name, long size) { this.cr = cr; this.uri = uri; this.name = name; this.size = size; }

    static UriSource of(Context ctx, Uri uri) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String name = null; long size = -1;
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
            }
        } catch (RuntimeException ignored) { }
        if (name == null || name.isEmpty()) { String p = uri.getLastPathSegment(); name = p == null ? "file" : p.substring(p.lastIndexOf('/') + 1); }
        if (size < 0) { try (AssetFileDescriptor fd = cr.openAssetFileDescriptor(uri, "r")) { if (fd != null) size = fd.getLength(); } catch (IOException ignored) { } }
        if (size < 0) { // provider can't tell us the size: copy to cache once so the size is exact
            File tmp = File.createTempFile("flux", ".tmp", ctx.getCacheDir());
            try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("cannot open " + name);
                byte[] b = new byte[64 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            tmp.deleteOnExit();
            return new UriSource(cr, Uri.fromFile(tmp), name, tmp.length());
        }
        return new UriSource(cr, uri, name, size);
    }

    Uri uri() { return uri; }
    @Override public String relPath() { return name; }
    @Override public long size() { return size; }
    @Override public long mtimeSeconds() { return 0; }
    @Override public InputStream open() throws IOException {
        InputStream in = "file".equals(uri.getScheme()) ? new FileInputStream(uri.getPath()) : cr.openInputStream(uri);
        if (in == null) throw new IOException("cannot open " + name);
        return in;
    }
}
