package app.flux;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** This phone's own transfer log (names, sizes, outcome, saved file location). No file contents are ever stored. */
final class LocalHistory {
    static final int MAX = 200;
    private LocalHistory() {}

    static final class FileRec { String name; long size; String uri; }
    static final class Entry {
        long time; boolean sent; boolean ok; int count; long bytes; long secs; String error; List<FileRec> files = new ArrayList<>();
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("flux_history", Context.MODE_PRIVATE); }

    static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("items", "[]"));
            for (int i = a.length() - 1; i >= 0; i--) {   // newest first
                JSONObject o = a.getJSONObject(i); Entry e = new Entry();
                e.time = o.optLong("t"); e.sent = o.optBoolean("sent"); e.ok = o.optBoolean("ok"); e.count = o.optInt("n"); e.bytes = o.optLong("b"); e.secs = o.optLong("s");
                e.error = o.isNull("err") ? null : o.optString("err", null);
                JSONArray f = o.optJSONArray("f");
                if (f != null) for (int k = 0; k < f.length(); k++) {
                    JSONObject fo = f.getJSONObject(k); FileRec r = new FileRec();
                    r.name = fo.optString("n"); r.size = fo.optLong("z"); r.uri = fo.isNull("u") ? null : fo.optString("u", null); e.files.add(r);
                }
                out.add(e);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static synchronized void add(Context c, Entry e) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("items", "[]"));
            JSONObject o = new JSONObject();
            o.put("t", e.time); o.put("sent", e.sent); o.put("ok", e.ok); o.put("n", e.count); o.put("b", e.bytes); o.put("s", e.secs); o.put("err", e.error == null ? JSONObject.NULL : e.error);
            JSONArray f = new JSONArray();
            for (int i = 0; i < Math.min(50, e.files.size()); i++) {
                FileRec r = e.files.get(i); JSONObject fo = new JSONObject();
                fo.put("n", r.name); fo.put("z", r.size); fo.put("u", r.uri == null ? JSONObject.NULL : r.uri); f.put(fo);
            }
            o.put("f", f); a.put(o);
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, a.length() - MAX); i < a.length(); i++) trimmed.put(a.get(i));
            sp(c).edit().putString("items", trimmed.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void clear(Context c) { sp(c).edit().remove("items").apply(); }
}
