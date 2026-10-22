package app.flux;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import app.flux.protocol.*;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FLUX for Android. One Activity, ten screens (splash, onboarding, home, connected, send, transferring, complete,
 * files, history, settings). Everything shown is real state: no fake devices, no invented progress.
 * Safety rules: no background thread may throw out of its Runnable (it would close the app), and the last step reached
 * is recorded so a low-level crash can be located.
 */
public class MainActivity extends Activity {
    private static final int REQ_PICK = 11, REQ_CAM = 12;
    private static final long POLL_BASE_MS = 6_000, POLL_MAX_MS = 60_000;
    private enum Screen { SPLASH, ONBOARD, MAIN, SEND, TRANSFER, RESULT }
    private enum Tab { HOME, FILES, HISTORY, MORE }

    private SharedPreferences prefs;
    private FluxClient client;
    private String ownFp = "", initError;
    private FrameLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = this::poll;
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final android.util.LruCache<String, Bitmap> thumbCache = new android.util.LruCache<>(60);

    private Screen screen = Screen.SPLASH;
    private Tab tab = Tab.HOME;
    private boolean sentTab;
    private int sendTab;
    private final List<UriSource> selected = new ArrayList<>();

    private volatile PendingItem pending;
    private volatile boolean pcReachable, netUp = true, resumed, busy;
    private long pollMs = POLL_BASE_MS;
    private String lastSig = "";
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private String banner; private int bannerKind; private boolean working;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Xfer xfer;
    private long lastUi;

    /** The transfer currently shown on the Transferring / Complete screens. */
    private static final class Xfer {
        boolean sending, finished, ok, cancelled; long started = SystemClock.elapsedRealtime(), total, done, winT, winB; double speed;
        int filesDone, listedDone = -1; String current = "", error; long secs;
        final List<String> names = new ArrayList<>(); final List<Long> sizes = new ArrayList<>();
        ProgressRing ring; TextView pct, bytes, name, remain; LinearLayout list;
    }

    // ================================================================== lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("flux", MODE_PRIVATE);
        final boolean died = prefs.getBoolean("running", false);
        Trace.sink = step -> prefs.edit().putString("last_step", step).commit();   // synchronous: survives a native crash
        installCrashReporter();
        getWindow().setStatusBarColor(Color.WHITE); getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = new FrameLayout(this); root.setBackgroundColor(Color.WHITE); setContentView(root);
        buildClient();
        render();
        handler.postDelayed(() -> { if (screen == Screen.SPLASH) show(prefs.getBoolean("onboarded", false) ? Screen.MAIN : Screen.ONBOARD); }, 1100);
        reportLastProblem(died);
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleShare(i); }
    @Override protected void onStart() { super.onStart(); prefs.edit().putBoolean("running", true).commit(); }
    @Override protected void onStop() { super.onStop(); prefs.edit().putBoolean("running", false).commit(); }
    @Override protected void onResume() {
        super.onResume();
        try { resumed = true; Trace.step("app: resumed"); registerNet(); schedule(400); } catch (Throwable x) { fatal(x); }
    }
    @Override protected void onPause() {
        super.onPause();
        try { resumed = false; handler.removeCallbacks(pollTask); unregisterNet(); } catch (Throwable x) { fatal(x); }
    }
    @Override protected void onDestroy() { super.onDestroy(); thumbs.shutdownNow(); }

    @Override public void onBackPressed() {
        if (screen == Screen.SEND) show(Screen.MAIN);
        else if (screen == Screen.RESULT) { selected.clear(); tab = Tab.HOME; show(Screen.MAIN); }
        else if (screen == Screen.TRANSFER) Toast.makeText(this, "Use Cancel to stop this transfer.", Toast.LENGTH_SHORT).show();
        else if (screen == Screen.MAIN && tab != Tab.HOME) { tab = Tab.HOME; render(); }
        else super.onBackPressed();
    }

    // ================================================================== helpers

    private int dp(int v) { return Ui.dp(this, v); }
    private boolean paired() { return prefs.getString("pc_fp", null) != null && prefs.getString("pc_addrs", null) != null; }
    private String deviceName() { return prefs.getString("device_name", Build.MODEL); }
    private String pcName() { return prefs.getString("pc_name", "your PC"); }

    private void buildClient() {
        try {
            File dir = new File(getFilesDir(), "identity");
            javax.net.ssl.KeyManager[] km = SoftIdentity.load(dir);
            ownFp = SoftIdentity.fingerprint(dir);
            client = new FluxClient(km, ownFp, deviceName(), "phone"); initError = null;
        } catch (Throwable t) { client = null; initError = String.valueOf(t); }
    }

    private void bg(Runnable r) {
        Thread t = new Thread(() -> { try { r.run(); } catch (Throwable x) { fatal(x); } }, "flux-bg");
        t.setDaemon(true); t.start();
    }
    private void ui(Runnable r) { runOnUiThread(() -> { try { r.run(); } catch (Throwable x) { fatal(x); } }); }

    private void installCrashReporter() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try { java.io.StringWriter sw = new java.io.StringWriter(); e.printStackTrace(new java.io.PrintWriter(sw)); prefs.edit().putString("last_crash", sw.toString()).commit(); } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** An unexpected error is shown (and kept for support) instead of closing the app. */
    private void fatal(Throwable x) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter(); x.printStackTrace(new java.io.PrintWriter(sw));
            final String trace = sw.toString();
            prefs.edit().putString("last_crash", "(caught - the app kept running)\n" + trace).commit();
            runOnUiThread(() -> { try {
                busy = false; working = false;
                if (!isFinishing()) problemDialog("Something went wrong, but FLUX stayed open", trace);
                if (screen == Screen.TRANSFER || screen == Screen.SPLASH) { screen = Screen.MAIN; }
                render();
            } catch (Throwable ignored) { } });
        } catch (Throwable ignored) { }
    }

    private void problemDialog(String title, String trace) {
        final String shown = trace.length() > 1800 ? trace.substring(0, 1800) : trace;
        new AlertDialog.Builder(this).setTitle(title).setMessage(shown)
                .setPositiveButton("Copy details", (d, w) -> { ClipboardManager c = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); if (c != null) c.setPrimaryClip(ClipData.newPlainText("FLUX", trace)); })
                .setNegativeButton("Close", null).show();
    }

    private void reportLastProblem(boolean died) {
        String crash = prefs.getString("last_crash", null);
        if (crash != null) { prefs.edit().remove("last_crash").commit(); problemDialog("FLUX had a problem last time", crash); }
        else if (died) { prefs.edit().putBoolean("running", false).commit(); problemDialog("FLUX stopped unexpectedly last time", "No error was captured, which points to a low-level crash.\nLast step reached: " + prefs.getString("last_step", "(none)")); }
        if (initError != null) problemDialog("This phone's secure identity couldn't be created", initError);
    }

    private static String mb(long b) { return b >= 1L << 30 ? String.format(Locale.US, "%.2f GB", b / 1073741824.0) : b >= 1 << 20 ? String.format(Locale.US, "%.1f MB", b / 1048576.0) : b >= 1024 ? String.format(Locale.US, "%.0f KB", b / 1024.0) : b + " B"; }
    private static String secsText(long s) { return s < 60 ? s + (s == 1 ? " second" : " seconds") : (s / 60) + " min " + (s % 60) + " s"; }
    private String when(long t) {
        long age = System.currentTimeMillis() - t;
        if (age < 60_000) return "Just now";
        DateFormat tf = DateFormat.getTimeInstance(DateFormat.SHORT);
        if (age < 24L * 3600_000 && new Date(t).getDate() == new Date().getDate()) return "Today, " + tf.format(new Date(t));
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(t)) + ", " + tf.format(new Date(t));
    }
    private static String ext(String n) { int i = n.lastIndexOf('.'); return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT); }
    private static boolean isMedia(String n) { String e = ext(n); return e.matches("jpe?g|png|gif|webp|heic|bmp|mp4|mov|mkv|avi|webm|m4v|3gp"); }
    private static String iconFor(String n) { String e = ext(n); return e.matches("jpe?g|png|gif|webp|heic|bmp") ? "ic_image" : e.matches("mp4|mov|mkv|avi|webm|m4v|3gp") ? "ic_video" : "ic_file"; }

    private void thumb(ImageView iv, String uri, String name) {
        iv.setImageResource(getResources().getIdentifier(iconFor(name), "drawable", getPackageName())); iv.setColorFilter(Ui.MUTED); iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (uri == null || !isMedia(name)) return;
        iv.setTag(uri);
        Bitmap hit = thumbCache.get(uri);
        if (hit != null) { iv.clearColorFilter(); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setImageBitmap(hit); return; }
        thumbs.execute(() -> {
            try {
                Bitmap bm = getContentResolver().loadThumbnail(Uri.parse(uri), new Size(180, 180), null);
                thumbCache.put(uri, bm);
                runOnUiThread(() -> { if (uri.equals(iv.getTag())) { iv.clearColorFilter(); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setImageBitmap(bm); } });
            } catch (Throwable ignored) { }
        });
    }

    // ================================================================== rendering

    private void show(Screen s) { screen = s; render(); }

    private void render() {
        try {
            root.removeAllViews();
            View v;
            switch (screen) {
                case SPLASH: v = buildSplash(); break;
                case ONBOARD: v = buildOnboard(); break;
                case SEND: v = buildSend(); break;
                case TRANSFER: v = buildTransfer(); break;
                case RESULT: v = buildResult(); break;
                default: v = buildMain();
            }
            root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable x) { fatal(x); }
    }

    private void refreshHome() { if (screen == Screen.MAIN && tab == Tab.HOME) render(); }

    private TextView t(String s, float sp, int color, boolean bold) { TextView v = Ui.text(this, sp, color, bold); v.setText(s); return v; }
    private LinearLayout.LayoutParams lp(int w, int h, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.topMargin = dp(top); return p; }
    private View scroll(View inner) { ScrollView sv = new ScrollView(this); sv.setFillViewport(true); sv.setVerticalScrollBarEnabled(false); sv.addView(inner); return sv; }
    private LinearLayout page() { LinearLayout l = Ui.vbox(this); l.setPadding(dp(20), dp(14), dp(20), dp(20)); return l; }

    private View circleIcon(String icon, int iconTint, int fill, int ring, int sizeDp) {
        LinearLayout c = new LinearLayout(this); c.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(fill); if (ring != 0) g.setStroke(dp(2), ring); c.setBackground(g);
        c.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        ImageView iv = Ui.icon(this, icon, sizeDp / 2, iconTint); c.addView(iv); return c;
    }

    private View topBar(String title, boolean back) {
        FrameLayout f = new FrameLayout(this); f.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView tt = t(title, 17, Ui.INK, true); tt.setGravity(Gravity.CENTER); f.addView(tt, new FrameLayout.LayoutParams(-1, dp(40)));
        if (back) { ImageView b = Ui.icon(this, "ic_back", 24, Ui.INK); b.setPadding(dp(8), dp(8), dp(8), dp(8)); b.setLayoutParams(new FrameLayout.LayoutParams(dp(40), dp(40))); b.setOnClickListener(v -> show(Screen.MAIN)); b.setContentDescription("Back"); f.addView(b); }
        return f;
    }

    // ---------------------------------------------------------------- 1. splash

    private View buildSplash() {
        LinearLayout l = Ui.vbox(this); l.setGravity(Gravity.CENTER_HORIZONTAL); l.setPadding(dp(30), dp(30), dp(30), dp(40));
        l.addView(Ui.flex(this));
        ImageView logo = new ImageView(this); logo.setImageResource(getResources().getIdentifier("flux_logo", "drawable", getPackageName())); logo.setAdjustViewBounds(true);
        l.addView(logo, new LinearLayout.LayoutParams(dp(96), -2));
        TextView name = t("FLUX", 34, Ui.INK, true); name.setLetterSpacing(.28f); name.setPadding(0, dp(18), 0, dp(10)); l.addView(name);
        TextView tag = t("Your files. Your devices.\nSeamlessly.", 15, Ui.INK, false); tag.setGravity(Gravity.CENTER); l.addView(tag);
        l.addView(Ui.flex(this));
        ProgressBar pb = new ProgressBar(this); l.addView(pb, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView sub = t("Getting things ready…", 12, Ui.MUTED, false); sub.setPadding(0, dp(10), 0, 0); l.addView(sub);
        return l;
    }

    // ---------------------------------------------------------------- 2. onboarding

    private View buildOnboard() {
        LinearLayout l = page(); l.setPadding(dp(24), dp(30), dp(24), dp(20));
        TextView h = t("Welcome to FLUX", 25, Ui.INK, true); h.setGravity(Gravity.CENTER); l.addView(h);
        TextView s1 = t("Quick. Secure. Simple.", 15, Ui.INK, false); s1.setGravity(Gravity.CENTER); s1.setPadding(0, dp(6), 0, dp(4)); l.addView(s1);
        TextView s2 = t("Transfer files between your phone and computer with ease.", 13, Ui.MUTED, false); s2.setGravity(Gravity.CENTER); l.addView(s2);
        l.addView(new Illustration(this), lp(-1, dp(150), 22));
        String[][] f = {{"ic_bolt", "Fast Transfers", "Straight over your own Wi-Fi"}, {"ic_shield", "Secure Connection", "Encrypted, and only devices you pair"}, {"ic_phone", "Cross-Device", "Phone to PC and back"}};
        for (String[] r : f) {
            LinearLayout row = Ui.hbox(this); row.setPadding(0, dp(12), 0, dp(12));
            row.addView(Ui.icon(this, r[0], 24, Ui.INK));
            LinearLayout col = Ui.vbox(this); col.setPadding(dp(16), 0, 0, 0); col.addView(t(r[1], 15, Ui.INK, true)); col.addView(t(r[2], 12.5f, Ui.MUTED, false)); row.addView(col);
            l.addView(row);
        }
        l.addView(Ui.flex(this));
        Button go = Ui.button(this, "Get Started", 0); go.setOnClickListener(v -> { prefs.edit().putBoolean("onboarded", true).apply(); show(Screen.MAIN); }); l.addView(go);
        return scroll(l);
    }

    // ---------------------------------------------------------------- main shell + bottom navigation

    private View buildMain() {
        LinearLayout col = Ui.vbox(this);
        View page = tab == Tab.HOME ? homePage() : tab == Tab.FILES ? filesPage() : tab == Tab.HISTORY ? historyPage() : morePage();
        col.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));
        col.addView(navBar(), new LinearLayout.LayoutParams(-1, dp(64)));
        return col;
    }

    private View navBar() {
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setBackgroundColor(Color.WHITE);
        GradientDrawable top = new GradientDrawable(); top.setColor(Color.WHITE); top.setStroke(dp(1), Ui.LINE); bar.setBackground(top);
        Object[][] items = {{Tab.HOME, "ic_home", "Home"}, {Tab.FILES, "ic_folder", "Files"}, {Tab.HISTORY, "ic_history", "History"}, {Tab.MORE, "ic_more", "More"}};
        for (Object[] it : items) {
            Tab tb = (Tab) it[0]; boolean on = tab == tb; int col = on ? Ui.INK : 0xFF9CA3AF;
            LinearLayout cell = Ui.vbox(this); cell.setGravity(Gravity.CENTER);
            cell.addView(Ui.icon(this, (String) it[1], 24, col)); TextView lb = t((String) it[2], 11, col, on); lb.setPadding(0, dp(2), 0, 0); cell.addView(lb);
            cell.setOnClickListener(v -> { tab = tb; render(); }); cell.setContentDescription((String) it[2]);
            bar.addView(cell, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        return bar;
    }

    private View headerRow(String title, boolean gear) {
        LinearLayout r = Ui.hbox(this);
        TextView tt = t(title, title.equals("FLUX") ? 24 : 22, Ui.INK, true); if (title.equals("FLUX")) tt.setLetterSpacing(.12f);
        r.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
        if (gear) { ImageView g = Ui.icon(this, "ic_settings", 24, Ui.INK); g.setPadding(dp(6), dp(6), dp(6), dp(6)); g.setOnClickListener(v -> { tab = Tab.MORE; render(); }); g.setContentDescription("Settings"); r.addView(g, new LinearLayout.LayoutParams(dp(40), dp(40))); }
        return r;
    }

    private View bannerView() {
        if (banner == null && !working) return null;
        int bg = bannerKind == 1 ? 0xFFE8F7EE : bannerKind == 2 ? 0xFFFDECEC : Ui.SOFT, fg = bannerKind == 1 ? Ui.GREEN : bannerKind == 2 ? Ui.RED : Ui.INK;
        LinearLayout b = Ui.hbox(this); b.setPadding(dp(14), dp(12), dp(14), dp(12)); b.setBackground(Ui.rounded(this, bg, 14, 0));
        if (working) { ProgressBar pb = new ProgressBar(this); b.addView(pb, new LinearLayout.LayoutParams(dp(20), dp(20))); }
        TextView m = t(banner == null ? "" : banner, 13.5f, fg, false); m.setPadding(working ? dp(10) : 0, 0, 0, 0); b.addView(m, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!working) b.setOnClickListener(v -> { banner = null; refreshHome(); });
        b.setLayoutParams(lp(-1, -2, 14));
        return b;
    }

    // ---------------------------------------------------------------- 3 + 5. home (ready to connect / connected)

    private View homePage() {
        LinearLayout l = page();
        l.addView(headerRow("FLUX", true));
        View bn = bannerView(); if (bn != null) l.addView(bn);
        if (!paired()) {
            LinearLayout card = Ui.vbox(this); card.setGravity(Gravity.CENTER_HORIZONTAL); card.setPadding(dp(18), dp(22), dp(18), dp(22)); card.setBackground(Ui.rounded(this, Color.WHITE, 20, Ui.LINE));
            card.addView(new Illustration(this), new LinearLayout.LayoutParams(-1, dp(110)));
            TextView h = t("Ready to connect", 18, Ui.INK, true); h.setPadding(0, dp(12), 0, dp(4)); card.addView(h);
            TextView s = t("Scan the QR code on your computer to pair your devices.", 13.5f, Ui.MUTED, false); s.setGravity(Gravity.CENTER); card.addView(s);
            l.addView(card, lp(-1, -2, 16));
            Button scan = Ui.button(this, "Scan QR Code", 0); scan.setOnClickListener(v -> startScan());
            LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) scan.getLayoutParams(); sp.topMargin = dp(16);
            l.addView(scan);
            LinearLayout or = Ui.hbox(this); or.setPadding(0, dp(8), 0, dp(2));
            View a = new View(this); a.setBackgroundColor(Ui.LINE); or.addView(a, new LinearLayout.LayoutParams(0, dp(1), 1f));
            TextView o = t("OR", 12, Ui.MUTED, true); o.setPadding(dp(12), 0, dp(12), 0); or.addView(o);
            View b2 = new View(this); b2.setBackgroundColor(Ui.LINE); or.addView(b2, new LinearLayout.LayoutParams(0, dp(1), 1f));
            l.addView(or);
            Button man = Ui.button(this, "Enter Manually", 1); man.setOnClickListener(v -> manualDialog()); l.addView(man);
            TextView hint = t("Open FLUX on your computer, then use Devices → Pair a device. You can scan its QR code or type the 9-character code.", 12, Ui.MUTED, false); hint.setPadding(dp(4), dp(16), dp(4), 0); l.addView(hint);
        } else {
            boolean live = pcReachable && netUp;
            LinearLayout card = Ui.vbox(this); card.setGravity(Gravity.CENTER_HORIZONTAL); card.setPadding(dp(18), dp(22), dp(18), dp(20)); card.setBackground(Ui.rounded(this, Color.WHITE, 20, Ui.LINE));
            card.addView(circleIcon("ic_check", live ? Ui.GREEN : 0xFF9CA3AF, Color.WHITE, live ? Ui.GREEN : 0xFFD1D5DB, 54));
            TextView h = t(live ? "Connected" : "Paired", 19, Ui.INK, true); h.setPadding(0, dp(10), 0, dp(2)); card.addView(h);
            TextView s = t(live ? "You're now paired with your PC" : "Your PC isn't reachable right now. Open FLUX on it, on the same Wi-Fi.", 13.5f, Ui.MUTED, false); s.setGravity(Gravity.CENTER); card.addView(s);
            LinearLayout chip = Ui.hbox(this); chip.setPadding(dp(14), dp(9), dp(14), dp(9)); chip.setBackground(Ui.rounded(this, Ui.SOFT, 12, Ui.LINE));
            chip.addView(Ui.icon(this, "ic_laptop", 18, Ui.INK)); TextView cn = t(pcName(), 13.5f, Ui.INK, true); cn.setPadding(dp(8), 0, 0, 0); chip.addView(cn);
            card.addView(chip, lp(-2, -2, 14));
            l.addView(card, lp(-1, -2, 16));
            PendingItem p = pending;
            if (p != null && live) {
                LinearLayout pc = Ui.vbox(this); pc.setPadding(dp(16), dp(14), dp(16), dp(14)); pc.setBackground(Ui.rounded(this, Color.WHITE, 16, Ui.ACCENT));
                pc.addView(t("WAITING FROM YOUR PC", 11, Ui.ACCENT, true));
                TextView pt = t(p.fileCount == 1 ? "1 file is waiting for you" : p.fileCount + " files are waiting for you", 16, Ui.INK, true); pt.setPadding(0, dp(4), 0, 0); pc.addView(pt);
                StringBuilder d = new StringBuilder(mb(p.totalBytes)); for (int i = 0; i < Math.min(3, p.names.size()); i++) d.append(i == 0 ? " · " : ", ").append(p.names.get(i)); if (p.fileCount > 3) d.append(" …");
                pc.addView(t(d.toString(), 12.5f, Ui.MUTED, false));
                Button rb = Ui.button(this, "Receive files", 0); rb.setOnClickListener(v -> startReceive()); pc.addView(rb);
                l.addView(pc, lp(-1, -2, 14));
            }
            TextView qa = t("Quick Actions", 15, Ui.INK, true); qa.setPadding(0, dp(20), 0, dp(8)); l.addView(qa);
            l.addView(actionRow("ic_send", "Send Files", "Photos, videos, documents", v -> { sendTab = 0; show(Screen.SEND); }));
            l.addView(actionRow("ic_download", "Receive Files", p != null ? (p.fileCount + " waiting on your PC") : "Accept files from your PC", v -> { if (pending != null) startReceive(); else checkNow(); }));
        }
        return scroll(l);
    }

    private View actionRow(String icon, String title, String sub, View.OnClickListener on) {
        LinearLayout r = Ui.hbox(this); r.setPadding(dp(14), dp(14), dp(14), dp(14)); r.setBackground(Ui.rounded(this, Color.WHITE, 16, Ui.LINE));
        r.addView(circleIcon(icon, Ui.INK, Ui.SOFT, 0, 40));
        LinearLayout c = Ui.vbox(this); c.setPadding(dp(14), 0, 0, 0); c.addView(t(title, 15, Ui.INK, true)); c.addView(t(sub, 12.5f, Ui.MUTED, false));
        r.addView(c, new LinearLayout.LayoutParams(0, -2, 1f)); r.addView(Ui.icon(this, "ic_chevron", 22, 0xFF9CA3AF));
        r.setOnClickListener(on); Ui.press(r); r.setLayoutParams(lp(-1, -2, 10)); return r;
    }

    // ---------------------------------------------------------------- 9. files

    private View filesPage() {
        LinearLayout l = page(); l.addView(headerRow("Files", false));
        LinearLayout seg = Ui.hbox(this); seg.setBackground(Ui.rounded(this, Ui.SOFT, 12, 0)); seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        String[] names = {"Received", "Sent"};
        for (int i = 0; i < 2; i++) {
            final boolean isSent = i == 1; boolean on = sentTab == isSent;
            TextView b = t(names[i], 14, on ? Color.WHITE : Ui.MUTED, true); b.setGravity(Gravity.CENTER); b.setPadding(0, dp(10), 0, dp(10));
            if (on) b.setBackground(Ui.rounded(this, Ui.INK, 10, 0));
            b.setOnClickListener(v -> { sentTab = isSent; render(); });
            seg.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        l.addView(seg, lp(-1, -2, 12));
        int shown = 0;
        for (LocalHistory.Entry e : LocalHistory.all(this)) {
            if (e.sent != sentTab || !e.ok) continue;
            for (LocalHistory.FileRec f : e.files) {
                if (shown++ > 150) break;
                LinearLayout row = Ui.hbox(this); row.setPadding(0, dp(10), 0, dp(10));
                ImageView th = new ImageView(this); th.setBackground(Ui.rounded(this, Ui.SOFT, 10, 0)); th.setClipToOutline(true); thumb(th, f.uri, f.name);
                row.addView(th, new LinearLayout.LayoutParams(dp(48), dp(48)));
                LinearLayout c = Ui.vbox(this); c.setPadding(dp(14), 0, dp(8), 0); TextView nm = t(f.name, 14.5f, Ui.INK, true); nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END); c.addView(nm);
                c.addView(t(mb(f.size) + " · " + when(e.time), 12, Ui.MUTED, false));
                row.addView(c, new LinearLayout.LayoutParams(0, -2, 1f)); row.addView(Ui.icon(this, "ic_chevron", 22, 0xFF9CA3AF));
                if (f.uri != null && !sentTab) row.setOnClickListener(v -> openFile(f));
                l.addView(row);
            }
        }
        if (shown == 0) l.addView(emptyState("ic_folder", sentTab ? "Nothing sent yet" : "No received files yet", sentTab ? "Files you send to your PC will be listed here." : "Files from your PC are saved in Downloads/FLUX and listed here."));
        return scroll(l);
    }

    private void openFile(LocalHistory.FileRec f) {
        try {
            String e = ext(f.name);
            if (e.matches("exe|msi|bat|cmd|com|scr|ps1|vbs|js|jar|apk|lnk|dll")) { Toast.makeText(this, "For safety FLUX won't open this type of file.", Toast.LENGTH_LONG).show(); return; }
            String mime = java.net.URLConnection.guessContentTypeFromName(f.name);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(f.uri), mime == null ? "*/*" : mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open with"));
        } catch (Throwable x) { Toast.makeText(this, "No app can open this file.", Toast.LENGTH_SHORT).show(); }
    }

    private View emptyState(String icon, String title, String body) {
        LinearLayout c = Ui.vbox(this); c.setGravity(Gravity.CENTER_HORIZONTAL); c.setPadding(dp(20), dp(48), dp(20), dp(20));
        c.addView(circleIcon(icon, 0xFF9CA3AF, Ui.SOFT, 0, 64));
        TextView h = t(title, 16, Ui.INK, true); h.setPadding(0, dp(14), 0, dp(4)); c.addView(h);
        TextView b = t(body, 13, Ui.MUTED, false); b.setGravity(Gravity.CENTER); c.addView(b);
        return c;
    }

    // ---------------------------------------------------------------- history

    private View historyPage() {
        LinearLayout l = page(); l.addView(headerRow("History", false));
        List<LocalHistory.Entry> all = LocalHistory.all(this);
        for (LocalHistory.Entry e : all) {
            LinearLayout row = Ui.hbox(this); row.setPadding(0, dp(12), 0, dp(12));
            row.addView(circleIcon(e.ok ? "ic_check" : "ic_close", Color.WHITE, e.ok ? Ui.INK : Ui.RED, 0, 38));
            LinearLayout c = Ui.vbox(this); c.setPadding(dp(14), 0, 0, 0);
            String what = e.count == 1 && !e.files.isEmpty() ? e.files.get(0).name : e.count + " files";
            c.addView(t((e.sent ? "Sent " : "Received ") + what, 14.5f, Ui.INK, true));
            c.addView(t((e.ok ? mb(e.bytes) + " · " + secsText(e.secs) : (e.error == null ? "Not completed" : e.error)) + " · " + when(e.time), 12, e.ok ? Ui.MUTED : Ui.RED, false));
            row.addView(c, new LinearLayout.LayoutParams(0, -2, 1f)); l.addView(row);
        }
        if (all.isEmpty()) l.addView(emptyState("ic_history", "No transfers yet", "Every transfer from this phone is listed here with its real outcome."));
        else { Button clr = Ui.button(this, "Clear history", 2); clr.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Clear history?").setMessage("This removes the list only. Saved files stay where they are.").setPositiveButton("Clear", (d, w) -> { LocalHistory.clear(this); render(); }).setNegativeButton("Cancel", null).show()); l.addView(clr); }
        return scroll(l);
    }

    // ---------------------------------------------------------------- 10. settings / more

    private String networkLabel() {
        try {
            if (cm == null) cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork(); NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) return "No network";
            return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi connected" : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile data (your PC needs the same Wi-Fi)" : "Connected";
        } catch (Throwable x) { return "Unknown"; }
    }

    private String version() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable x) { return "0.3"; } }

    private View settingRow(String icon, String title, String value, View.OnClickListener on) {
        LinearLayout r = Ui.hbox(this); r.setPadding(0, dp(14), 0, dp(14));
        r.addView(Ui.icon(this, icon, 24, Ui.INK));
        LinearLayout c = Ui.vbox(this); c.setPadding(dp(16), 0, dp(8), 0); c.addView(t(title, 15, Ui.INK, true)); TextView v = t(value, 12.5f, Ui.MUTED, false); c.addView(v);
        r.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
        if (on != null) { r.addView(Ui.icon(this, "ic_chevron", 22, 0xFF9CA3AF)); r.setOnClickListener(on); }
        return r;
    }

    private View morePage() {
        LinearLayout l = page(); l.addView(headerRow("Settings", false));
        l.addView(settingRow("ic_phone", "Device Name", deviceName(), v -> nameDialog()));
        l.addView(settingRow("ic_wifi", "Network", networkLabel() + (paired() ? "\nPC address: " + prefs.getString("pc_addrs", "").split(",")[0] : ""), null));
        l.addView(settingRow("ic_lock", "Security", "End-to-end encryption", v -> new AlertDialog.Builder(this).setTitle("Security")
                .setMessage("Everything between this phone and your PC is encrypted (TLS 1.3). Each device has its own cryptographic identity, and your phone only talks to a PC it has paired with, checked on every connection. Files are verified (SHA-256) before they are saved.\n\nThis phone's fingerprint:\n" + ownFp).setPositiveButton("OK", null).show()));
        l.addView(settingRow("ic_info", "About", "FLUX v" + version(), v -> new AlertDialog.Builder(this).setTitle("About FLUX").setMessage("FLUX v" + version() + "\nFiles go straight between your devices over your own network. Nothing is uploaded to a server.").setPositiveButton("OK", null).show()));
        if (paired()) {
            Button forget = Ui.button(this, "Forget this PC", 1); forget.setTextColor(Ui.RED);
            forget.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Forget " + pcName() + "?").setMessage("You will need to pair again to send or receive files with it.")
                    .setPositiveButton("Forget", (d, w) -> { prefs.edit().remove("pc_fp").remove("pc_addrs").remove("pc_name").apply(); pending = null; pcReachable = false; tab = Tab.HOME; render(); }).setNegativeButton("Cancel", null).show());
            l.addView(forget);
        }
        return scroll(l);
    }

    private void nameDialog() {
        final EditText in = new EditText(this); in.setText(deviceName()); in.setSingleLine(true); in.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        new AlertDialog.Builder(this).setTitle("Device name").setMessage("How this phone appears on your PC.").setView(in)
                .setPositiveButton("Save", (d, w) -> { String n = in.getText().toString().trim(); if (!n.isEmpty()) { prefs.edit().putString("device_name", n).apply(); buildClient(); render(); } }).setNegativeButton("Cancel", null).show();
    }

    // ---------------------------------------------------------------- 6. send files

    private View buildSend() {
        LinearLayout l = Ui.vbox(this);
        l.addView(topBar("Send Files", true));
        LinearLayout tabs = Ui.hbox(this); String[] tn = {"Photos & Videos", "Documents", "Others"};
        for (int i = 0; i < 3; i++) {
            final int idx = i; boolean on = sendTab == i;
            LinearLayout cell = Ui.vbox(this); cell.setGravity(Gravity.CENTER); TextView lb = t(tn[i], 13.5f, on ? Ui.INK : Ui.MUTED, on); lb.setPadding(0, dp(12), 0, dp(10)); cell.addView(lb);
            View u = new View(this); u.setBackgroundColor(on ? Ui.INK : Color.TRANSPARENT); cell.addView(u, new LinearLayout.LayoutParams(-1, dp(2)));
            cell.setOnClickListener(v -> { sendTab = idx; render(); }); tabs.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        l.addView(tabs);
        View sep = new View(this); sep.setBackgroundColor(Ui.LINE); l.addView(sep, new LinearLayout.LayoutParams(-1, dp(1)));
        if (!paired()) { LinearLayout m = page(); m.addView(emptyState("ic_laptop", "Pair with your PC first", "Go back and scan the QR code on your computer.")); l.addView(m); return l; }

        int cell = (getResources().getDisplayMetrics().widthPixels - dp(40) - dp(12)) / 3;
        GridLayout g = new GridLayout(this); g.setColumnCount(3);
        LinearLayout add = Ui.vbox(this); add.setGravity(Gravity.CENTER);
        GradientDrawable dash = new GradientDrawable(); dash.setCornerRadius(dp(14)); dash.setColor(Ui.SOFT); dash.setStroke(dp(2), 0xFFC7CCD4, dp(6), dp(4)); add.setBackground(dash);
        add.addView(Ui.icon(this, "ic_add", 28, Ui.INK)); TextView at = t(sendTab == 0 ? "Add photos & videos" : sendTab == 1 ? "Add documents" : "Add files", 11.5f, Ui.INK, true); at.setGravity(Gravity.CENTER); at.setPadding(dp(4), dp(4), dp(4), 0); add.addView(at);
        add.setOnClickListener(v -> pickFiles()); addCell(g, add, cell);
        for (UriSource u : new ArrayList<>(selected)) {
            FrameLayout f = new FrameLayout(this); f.setBackground(Ui.rounded(this, Ui.SOFT, 14, 0));
            ImageView iv = new ImageView(this); iv.setClipToOutline(true); iv.setBackground(Ui.rounded(this, Ui.SOFT, 14, 0)); thumb(iv, u.uri().toString(), u.relPath()); f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            TextView nm = t(u.relPath(), 10.5f, Ui.INK, true); nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END); nm.setBackgroundColor(0xCCFFFFFF); nm.setPadding(dp(5), dp(2), dp(5), dp(2));
            FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2); np.gravity = Gravity.BOTTOM; f.addView(nm, np);
            LinearLayout x = new LinearLayout(this); x.setGravity(Gravity.CENTER); x.setBackground(Ui.rounded(this, Ui.INK, 11, 0)); x.addView(Ui.icon(this, "ic_close", 14, Color.WHITE));
            FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(dp(22), dp(22)); xp.gravity = Gravity.TOP | Gravity.END; xp.setMargins(0, dp(5), dp(5), 0);
            x.setOnClickListener(v -> { selected.remove(u); render(); }); x.setContentDescription("Remove " + u.relPath()); f.addView(x, xp);
            addCell(g, f, cell);
        }
        LinearLayout gp = Ui.vbox(this); gp.setPadding(dp(14), dp(14), dp(14), dp(14)); gp.addView(g);
        l.addView(scroll(gp), new LinearLayout.LayoutParams(-1, 0, 1f));

        long total = 0; for (UriSource u : selected) total += u.size();
        LinearLayout bar = Ui.hbox(this); bar.setPadding(dp(18), dp(12), dp(12), dp(12)); bar.setBackground(Ui.rounded(this, Ui.INK, 18, 0));
        LinearLayout tx = Ui.vbox(this);
        tx.addView(t(selected.isEmpty() ? "No files selected" : selected.size() + (selected.size() == 1 ? " file selected" : " files selected"), 14.5f, Color.WHITE, true));
        if (!selected.isEmpty()) tx.addView(t("(" + mb(total) + ")", 12, 0xFFB6BCC8, false));
        bar.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        Button send = new Button(this); send.setText("Send"); send.setAllCaps(false); send.setTextSize(15); send.setTypeface(Typeface.DEFAULT_BOLD); send.setStateListAnimator(null); send.setTextColor(Ui.INK);
        send.setBackground(Ui.rounded(this, Color.WHITE, 12, 0)); send.setEnabled(!selected.isEmpty()); send.setAlpha(selected.isEmpty() ? .45f : 1f); send.setOnClickListener(v -> startSend());
        bar.addView(send, new LinearLayout.LayoutParams(dp(96), dp(44)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.setMargins(dp(14), dp(4), dp(14), dp(14)); l.addView(bar, bp);
        return l;
    }

    private void addCell(GridLayout g, View v, int size) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(); p.width = size; p.height = size; p.setMargins(dp(2), dp(2), dp(2), dp(2)); g.addView(v, p);
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); i.setType("*/*");
        if (sendTab == 0) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        else if (sendTab == 1) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "text/*"});
        startActivityForResult(i, REQ_PICK);
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private void handleShare(Intent i) {
        if (i == null) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(i.getAction())) { Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) uris.add(u); }
        else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) { ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l != null) uris.addAll(l); }
        if (uris.isEmpty()) return;
        i.setAction(Intent.ACTION_MAIN);
        if (!paired()) { banner = "Pair with your PC first, then share again."; bannerKind = 2; tab = Tab.HOME; screen = Screen.MAIN; render(); return; }
        addSelected(uris); sendTab = 2; screen = Screen.SEND; render();
    }

    private void addSelected(List<Uri> uris) {
        bg(() -> {
            for (Uri u : uris) {
                try {
                    final UriSource src = UriSource.of(this, u);
                    ui(() -> { boolean dup = false; for (UriSource s : selected) if (s.uri().equals(u)) dup = true; if (!dup) selected.add(src); if (screen == Screen.SEND) render(); });
                } catch (Throwable x) { ui(() -> Toast.makeText(this, "Couldn't read one of the selected files.", Toast.LENGTH_SHORT).show()); }
            }
        });
    }

    // ---------------------------------------------------------------- 7. transferring

    private View buildTransfer() {
        Xfer x = xfer; LinearLayout l = Ui.vbox(this); l.addView(topBar(x.sending ? "Transferring…" : "Receiving…", false));
        LinearLayout body = page(); body.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout ringBox = new FrameLayout(this); x.ring = new ProgressRing(this); ringBox.addView(x.ring, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout mid = Ui.vbox(this); mid.setGravity(Gravity.CENTER); x.pct = t("0%", 30, Ui.INK, true); x.bytes = t("", 12, Ui.MUTED, false); mid.addView(x.pct); mid.addView(x.bytes);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -1); ringBox.addView(mid, mp);
        body.addView(ringBox, new LinearLayout.LayoutParams(dp(190), dp(190)));
        x.name = t(x.current, 15, Ui.INK, true); x.name.setPadding(0, dp(16), 0, dp(2)); x.name.setSingleLine(true); x.name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); body.addView(x.name);
        x.remain = t("", 12.5f, Ui.MUTED, false); body.addView(x.remain);
        x.list = Ui.vbox(this); x.list.setPadding(0, dp(14), 0, 0); body.addView(x.list, new LinearLayout.LayoutParams(-1, -2));
        l.addView(scroll(body), new LinearLayout.LayoutParams(-1, 0, 1f));
        Button c = Ui.button(this, "Cancel", 0); c.setOnClickListener(v -> { cancel.set(true); c.setText("Cancelling…"); c.setEnabled(false); });
        LinearLayout.LayoutParams cp = (LinearLayout.LayoutParams) c.getLayoutParams(); cp.setMargins(dp(20), dp(6), dp(20), dp(20)); l.addView(c);
        x.listedDone = -1; updateTransferUi();
        return l;
    }

    private void updateTransferUi() {
        Xfer x = xfer;
        if (x == null || screen != Screen.TRANSFER || x.ring == null) return;
        float p = x.total > 0 ? (float) x.done / x.total : 0f;
        x.ring.setProgress(p); x.pct.setText((int) Math.floor(p * 100) + "%"); x.bytes.setText(mb(x.done) + " / " + mb(x.total));
        x.name.setText(x.current);
        long now = SystemClock.elapsedRealtime();
        if (x.winT == 0) { x.winT = now; x.winB = x.done; }
        else if (now - x.winT >= 1500) { x.speed = (x.done - x.winB) / ((now - x.winT) / 1000.0); x.winT = now; x.winB = x.done; }
        String rem = "";
        if (x.speed > 1024 && x.total > x.done) { long s = Math.max(1, Math.round((x.total - x.done) / x.speed)); rem = s < 60 ? "~" + s + " seconds remaining" : "~" + Math.round(s / 60.0) + " min remaining"; }
        x.remain.setText(rem);
        if (x.listedDone != x.filesDone) {
            x.listedDone = x.filesDone; x.list.removeAllViews();
            for (int i = 0; i < Math.min(x.names.size(), 30); i++) {
                LinearLayout row = Ui.hbox(this); row.setPadding(0, dp(8), 0, dp(8));
                row.addView(Ui.icon(this, iconFor(x.names.get(i)), 22, Ui.MUTED));
                LinearLayout c = Ui.vbox(this); c.setPadding(dp(12), 0, dp(8), 0); TextView nm = t(x.names.get(i), 13.5f, Ui.INK, false); nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END); c.addView(nm);
                if (i < x.sizes.size()) c.addView(t(mb(x.sizes.get(i)), 11.5f, Ui.MUTED, false));
                row.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
                if (i < x.filesDone) row.addView(circleIcon("ic_check", Color.WHITE, Ui.INK, 0, 22));
                else if (i == x.filesDone) row.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(20), dp(20)));
                else row.addView(Ui.icon(this, "ic_clock", 20, 0xFFB6BCC8));
                x.list.addView(row);
            }
        }
    }

    private TransferListener listener() {
        return new TransferListener() {
            public void onStatus(String s) { }
            public void onProgress(long done, long total, String file, int fd, int ft) {
                Xfer x = xfer; if (x == null) return;
                x.done = done; x.total = Math.max(x.total, total); x.current = file == null ? "" : file; x.filesDone = Math.min(fd, ft);
                long now = SystemClock.elapsedRealtime();
                if (now - lastUi < 120 && done < total) return;
                lastUi = now; ui(MainActivity.this::updateTransferUi);
            }
        };
    }

    // ---------------------------------------------------------------- send / receive

    private void startSend() {
        if (client == null || busy || selected.isEmpty() || !paired()) return;
        final List<UriSource> files = new ArrayList<>(selected);
        final String fp = prefs.getString("pc_fp", null), addrs = prefs.getString("pc_addrs", null);
        Xfer x = new Xfer(); x.sending = true; for (UriSource u : files) { x.names.add(u.relPath()); x.sizes.add(u.size()); x.total += u.size(); }
        xfer = x; cancel.set(false); busy = true; show(Screen.TRANSFER);
        bg(() -> {
            String err = null; boolean cancelled = false, ok = false;
            try {
                FluxException last = null;
                for (String addr : addrs.split(",")) {
                    try { client.send(addr, fp, new ArrayList<SourceFile>(files), listener(), cancel); last = null; prefs.edit().putString("pc_addrs", addr + "," + addrs.replace(addr, "")).apply(); break; }
                    catch (FluxException e) { last = e; if (!(e.kind == FluxException.Kind.NETWORK && String.valueOf(e.getMessage()).startsWith("can't reach"))) break; }
                }
                if (last != null) { cancelled = last.kind == FluxException.Kind.CANCELLED; throw last; }
                ok = true;
            } catch (FluxException e) { err = friendly(e); }
            final boolean fOk = ok, fCan = cancelled; final String fErr = err;
            ui(() -> finishXfer(fOk, fCan, fErr, null));
        });
    }

    private void startReceive() {
        PendingItem p = pending;
        if (client == null || busy || !paired() || p == null) return;
        final String fp = prefs.getString("pc_fp", null), addrs = prefs.getString("pc_addrs", null);
        Xfer x = new Xfer(); x.sending = false; x.names.addAll(p.names); x.sizes.addAll(p.sizes); x.total = p.totalBytes;
        xfer = x; cancel.set(false); busy = true; show(Screen.TRANSFER);
        final MediaSink sink = new MediaSink(getContentResolver());
        bg(() -> {
            String err = null; boolean cancelled = false, ok = false;
            try {
                FluxException last = null;
                for (String addr : addrs.split(",")) {
                    try { client.receive(addr, fp, sink, listener(), cancel); last = null; break; }
                    catch (FluxException e) { last = e; if (!(e.kind == FluxException.Kind.NETWORK && String.valueOf(e.getMessage()).startsWith("can't reach"))) break; }
                }
                if (last != null) { cancelled = last.kind == FluxException.Kind.CANCELLED; throw last; }
                ok = true;
            } catch (FluxException e) { err = friendly(e) + (e.kind == FluxException.Kind.NETWORK || e.kind == FluxException.Kind.INTEGRITY ? "\nThe files are still queued on your PC, so you can try again." : ""); }
            final boolean fOk = ok, fCan = cancelled; final String fErr = err;
            ui(() -> finishXfer(fOk, fCan, fErr, sink.saved()));
        });
    }

    /** Record the outcome (real values) and show the result screen. */
    private void finishXfer(boolean ok, boolean cancelled, String error, Map<String, String> savedUris) {
        Xfer x = xfer; busy = false; if (x == null) { show(Screen.MAIN); return; }
        x.finished = true; x.ok = ok; x.cancelled = cancelled; x.error = error; x.secs = Math.max(1, (SystemClock.elapsedRealtime() - x.started) / 1000);
        LocalHistory.Entry e = new LocalHistory.Entry(); e.time = System.currentTimeMillis(); e.sent = x.sending; e.ok = ok; e.secs = x.secs; e.error = ok ? null : error;
        e.count = x.names.size(); e.bytes = x.total;
        for (int i = 0; i < x.names.size(); i++) {
            LocalHistory.FileRec r = new LocalHistory.FileRec(); r.name = x.names.get(i); r.size = i < x.sizes.size() ? x.sizes.get(i) : 0;
            if (x.sending) { if (i < selected.size()) r.uri = selected.get(i).uri().toString(); } else if (savedUris != null) r.uri = savedUris.get(x.names.get(i));
            e.files.add(r);
        }
        if (ok || x.filesDone > 0) LocalHistory.add(this, e);
        if (ok && !x.sending) pending = null;
        show(Screen.RESULT); schedule(800);
    }

    // ---------------------------------------------------------------- 8. result

    private View buildResult() {
        Xfer x = xfer; LinearLayout l = page(); l.setGravity(Gravity.CENTER_HORIZONTAL); l.setPadding(dp(24), dp(48), dp(24), dp(20));
        l.addView(circleIcon(x.ok ? "ic_check" : "ic_close", Color.WHITE, x.ok ? Ui.INK : Ui.RED, 0, 72));
        TextView h = t(x.ok ? (x.sending ? "Transfer Complete" : "Files Received") : x.cancelled ? "Transfer Cancelled" : "Transfer Failed", 22, Ui.INK, true); h.setPadding(0, dp(16), 0, dp(6)); l.addView(h);
        TextView sub = t(x.ok ? (x.sending ? "Your files have been sent successfully." : "Every file was verified and saved to Downloads/FLUX.") : (x.error == null ? "" : x.error), 13.5f, x.ok ? Ui.MUTED : Ui.RED, false); sub.setGravity(Gravity.CENTER); l.addView(sub);
        LinearLayout card = Ui.vbox(this); card.setPadding(dp(18), dp(8), dp(18), dp(8)); card.setBackground(Ui.rounded(this, Ui.SOFT, 18, 0));
        int n = x.ok ? x.names.size() : x.filesDone;
        String[][] rows = {{"ic_file", x.sending ? "Files sent" : "Files received", String.valueOf(n)}, {"ic_download", "Total size", mb(x.ok ? x.total : x.done)}, {"ic_clock", "Time", secsText(x.secs)}};
        for (String[] r : rows) { LinearLayout row = Ui.hbox(this); row.setPadding(0, dp(12), 0, dp(12)); row.addView(Ui.icon(this, r[0], 22, Ui.INK)); TextView k = t(r[1], 14, Ui.INK, false); k.setPadding(dp(14), 0, 0, 0); row.addView(k, new LinearLayout.LayoutParams(0, -2, 1f)); row.addView(t(r[2], 14, Ui.INK, true)); card.addView(row); }
        l.addView(card, lp(-1, -2, 22));
        l.addView(Ui.flex(this));
        if (x.ok) {
            Button vf = Ui.button(this, "View Files", 0); vf.setOnClickListener(v -> { sentTab = x.sending; selected.clear(); tab = Tab.FILES; show(Screen.MAIN); }); l.addView(vf);
        } else {
            Button again = Ui.button(this, "Try again", 0); again.setOnClickListener(v -> { if (x.sending) startSend(); else startReceive(); }); l.addView(again);
        }
        Button done = Ui.button(this, "Done", 1); done.setOnClickListener(v -> { selected.clear(); tab = Tab.HOME; show(Screen.MAIN); }); l.addView(done);
        return scroll(l);
    }

    private String friendly(FluxException e) {
        switch (e.kind) {
            case REJECTED: return "Your PC declined the transfer" + (e.getMessage().isEmpty() ? "." : ": " + e.getMessage());
            case CANCELLED: return "Cancelled. Nothing was kept on the PC.";
            case CANCELLED_BY_RECEIVER: return "Your PC cancelled the transfer.";
            case SECURITY: return "Couldn't set up a secure connection to your PC.\n" + e.getMessage();
            case NETWORK: return "Can't reach your PC. Is FLUX open on it, and are both on the same Wi-Fi?\n(" + e.getMessage() + ")";
            case PAIRING: return "Pairing failed: " + e.getMessage() + ". Codes work once and expire quickly. Make a new one on your PC and try again.";
            case INTEGRITY: return "Verification failed: " + e.getMessage();
            case STORAGE: return "Couldn't save the file: " + e.getMessage();
            default: return "Transfer failed: " + e.getMessage();
        }
    }

    // ================================================================== pairing

    private void startScan() {
        if (busy || client == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM); return; }
        try {
            new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("Point your camera at the QR code on your computer screen.\nMake sure both devices are on the same Wi-Fi network.").setBeepEnabled(false).setOrientationLocked(false).initiateScan();
        } catch (Throwable t) { setBanner("Couldn't open the camera scanner. Use Enter Manually instead.", 2); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_CAM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startScan();
        else setBanner("Camera permission is needed to scan. Allow it in Settings, or use Enter Manually.", 2);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        try {
            IntentResult qr = IntentIntegrator.parseActivityResult(req, res, data);
            if (qr != null) { if (qr.getContents() != null) handleCode(qr.getContents()); return; }
            if (req == REQ_PICK && res == RESULT_OK && data != null) {
                List<Uri> uris = new ArrayList<>(); ClipData cd = data.getClipData();
                if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri()); else if (data.getData() != null) uris.add(data.getData());
                addSelected(uris);
            }
        } catch (Throwable x) { fatal(x); }
    }

    private void setBanner(String msg, int kind) { banner = msg; bannerKind = kind; working = false; tab = Tab.HOME; if (screen == Screen.MAIN) render(); }

    /** A scanned/typed value: a full flux:// link, or the 9-character code. */
    private void handleCode(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.toLowerCase(Locale.ROOT).startsWith("flux://")) pairFromLink(v);
        else if (FluxClient.normalizeCode(v) != null) pairByCode(v);
        else setBanner("That isn't a FLUX pairing code. Type the 9 characters shown on your PC, or scan its QR code.", 2);
    }

    private void manualDialog() {
        final EditText in = new EditText(this); in.setHint("K7M-2QX-9R4"); in.setSingleLine(true); in.setTextSize(20); in.setGravity(Gravity.CENTER);
        in.setFilters(new InputFilter[]{new InputFilter.LengthFilter(600)});   // no AllCaps: a pasted link must stay intact
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this).setTitle("Enter manually").setMessage("Type the 9-character code shown on your PC (Devices → Pair a device). You can also paste the full pairing link.").setView(in)
                .setPositiveButton("Find my PC and pair", (d, w) -> handleCode(in.getText().toString()))
                .setNeutralButton("Paste link", (d, w) -> { ClipboardManager c = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); CharSequence t = (c != null && c.hasPrimaryClip() && c.getPrimaryClip().getItemCount() > 0) ? c.getPrimaryClip().getItemAt(0).coerceToText(this) : null; handleCode(t == null ? "" : t.toString()); })
                .setNegativeButton("Cancel", null).show();
    }

    private void pairFromLink(String link) {
        if (client == null || busy) return;
        busy = true; banner = "Pairing…"; working = true; bannerKind = 0; tab = Tab.HOME; show(Screen.MAIN);
        bg(() -> {
            try {
                PairingLink pl = PairingLink.parse(link); PairResult r = client.pair(pl);
                List<String> order = new ArrayList<>(pl.addrs); order.remove(r.addr); order.add(0, r.addr);
                prefs.edit().putString("pc_name", r.name.isEmpty() ? "Your PC" : r.name).putString("pc_fp", r.fingerprint).putString("pc_addrs", String.join(",", order)).apply();
                pending = null; pcReachable = true; ui(() -> { busy = false; setBanner("Paired with " + pcName() + ". You can send files now.", 1); schedule(500); });
            } catch (FluxException e) { final String m = friendly(e); ui(() -> { busy = false; setBanner(m, 2); }); }
        });
    }

    private void pairByCode(String code) {
        if (client == null || busy) return;
        busy = true; banner = "Looking for your PC on this Wi-Fi…"; working = true; bannerKind = 0; tab = Tab.HOME; show(Screen.MAIN);
        bg(() -> {
            try {
                Trace.step("paircode: scanning wifi");
                List<String> found = LanScan.find(this);
                if (found.isEmpty()) throw new FluxException(FluxException.Kind.NETWORK, "Couldn't find FLUX running on this Wi-Fi. Is FLUX open on your PC, and are both on the same network? (Guest Wi-Fi often blocks this.)");
                PairResult r = client.pairWithCode(code, found);
                prefs.edit().putString("pc_name", r.name).putString("pc_fp", r.fingerprint).putString("pc_addrs", r.addr).apply();
                pending = null; pcReachable = true; ui(() -> { busy = false; setBanner("Paired with " + pcName() + ". You can send files now.", 1); schedule(500); });
            } catch (FluxException e) { final String m = friendly(e); ui(() -> { busy = false; setBanner(m, 2); }); }
        });
    }

    // ================================================================== looking for files from the PC (only while on screen)

    private void schedule(long ms) { handler.removeCallbacks(pollTask); handler.postDelayed(pollTask, ms); }

    private void registerNet() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { netUp = true; pollMs = POLL_BASE_MS; ui(() -> { schedule(400); refreshHome(); }); }
            @Override public void onLost(Network n) { netUp = false; pcReachable = false; ui(MainActivity.this::refreshHome); }
        };
        try { cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCb); } catch (RuntimeException e) { netCb = null; }
    }
    private void unregisterNet() { try { if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb); } catch (RuntimeException ignored) { } netCb = null; }

    private void poll() { look(false); }
    private void checkNow() { look(true); setBanner("Checking your PC…", 0); }

    /** One short authenticated look at the PC's queue ("peek": nothing is consumed). */
    private void look(final boolean manual) {
        if (!resumed) return;
        final String fp = prefs.getString("pc_fp", null), addrs = prefs.getString("pc_addrs", null);
        if (client == null || busy || fp == null || addrs == null || !netUp) { if (!manual) schedule(pollMs); return; }
        bg(() -> {
            PendingItem p = null; boolean reach = false;
            for (String a : addrs.split(",")) {
                try { p = client.peek(a, fp); reach = true; break; }
                catch (FluxException e) { if (e.kind != FluxException.Kind.NETWORK) { reach = e.kind == FluxException.Kind.REMOTE; break; } }
            }
            final PendingItem found = p; final boolean ok = reach;
            pollMs = ok ? POLL_BASE_MS : Math.min(POLL_MAX_MS, pollMs * 2);
            ui(() -> {
                pending = found; pcReachable = ok;
                String sig = ok + "|" + (found == null ? "-" : found.transferId);
                if (manual) setBanner(found == null ? (ok ? "Nothing is waiting on your PC." : "Can't reach your PC right now.") : "Files are waiting on your PC.", found != null ? 1 : ok ? 0 : 2);
                else if (!sig.equals(lastSig)) refreshHome();
                lastSig = sig;
                if (!busy && resumed) schedule(pollMs);
            });
        });
    }
}
