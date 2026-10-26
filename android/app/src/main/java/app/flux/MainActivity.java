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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
 * FLUX for Android: ten screens, one Activity. Everything shown is real state.
 * Typography and spacing come ONLY from {@link Ui} (one type scale, one spacing scale).
 * Safety rules: nothing may throw out of a background thread (it would close the app); the last step reached is recorded.
 */
public class MainActivity extends Activity {
    private static final int REQ_PICK = 11, REQ_CAM = 12;
    private static final long POLL_BASE_MS = 6_000, POLL_MAX_MS = 60_000, BANNER_MS = 8_000;
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
    private String bannerTitle, bannerHint; private int bannerKind; private boolean working; private long bannerUntil;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Xfer xfer;
    private long lastUi;

    /** A failure explained in plain language: what happened, what to do, and (small) the technical detail. */
    private static final class Problem {
        final String title, hint, detail;
        Problem(String title, String hint, String detail) { this.title = title; this.hint = hint; this.detail = detail; }
    }

    private static final class Xfer {
        boolean sending, finished, ok, cancelled; long started = SystemClock.elapsedRealtime(), total, done, winT, winB; double speed;
        int filesDone, listedDone = -1; String current = ""; Problem problem; long secs;
        final List<String> names = new ArrayList<>(); final List<Long> sizes = new ArrayList<>();
        ProgressRing ring; TextView pct, bytes, name, state, remain; LinearLayout list;
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
    private TextView tx(CharSequence s, Ui.Type t, int color) { return Ui.text(this, s, t, color); }

    private static LinearLayout.LayoutParams lpOf(View v) {
        ViewGroup.LayoutParams g = v.getLayoutParams();
        return g instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) g : new LinearLayout.LayoutParams(-1, -2);
    }
    /** Add a child with a top margin taken from the spacing scale. */
    private <V extends View> V add(LinearLayout parent, V v, int topDp) { LinearLayout.LayoutParams lp = lpOf(v); lp.topMargin = dp(topDp); v.setLayoutParams(lp); parent.addView(v); return v; }

    private List<String> addrList() {
        List<String> out = new ArrayList<>();
        for (String a : prefs.getString("pc_addrs", "").split(",")) { a = a.trim(); if (a.matches(".+:\\d{1,5}") && !out.contains(a)) out.add(a); }
        return out;
    }
    private void promote(String addr) { List<String> l = addrList(); l.remove(addr); l.add(0, addr); prefs.edit().putString("pc_addrs", String.join(",", l)).apply(); }
    private boolean paired() { return prefs.getString("pc_fp", null) != null && !addrList().isEmpty(); }
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

    private void fatal(Throwable x) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter(); x.printStackTrace(new java.io.PrintWriter(sw));
            final String trace = sw.toString();
            prefs.edit().putString("last_crash", "(caught - the app kept running)\n" + trace).commit();
            runOnUiThread(() -> { try {
                busy = false; working = false;
                if (!isFinishing()) problemDialog("Something went wrong, but FLUX stayed open", trace);
                if (screen == Screen.TRANSFER || screen == Screen.SPLASH) screen = Screen.MAIN;
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

    // ---- formatting
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
    private static boolean isPhoto(String n) { return ext(n).matches("jpe?g|png|gif|webp|heic|bmp"); }
    private static boolean isVideo(String n) { return ext(n).matches("mp4|mov|mkv|avi|webm|m4v|3gp"); }
    private static boolean isMedia(String n) { return isPhoto(n) || isVideo(n); }
    private static String iconFor(String n) { return isPhoto(n) ? "ic_image" : isVideo(n) ? "ic_video" : "ic_file"; }
    private static String kindLabel(String n) { return isPhoto(n) ? "Photo" : isVideo(n) ? "Video" : ext(n).matches("pdf|docx?|txt|md|pptx?|xlsx?|csv") ? "Document" : "File"; }

    private CharSequence status(String word, int color, String rest) {
        SpannableStringBuilder s = new SpannableStringBuilder(word);
        s.setSpan(new ForegroundColorSpan(color), 0, word.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, word.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (rest != null && !rest.isEmpty()) s.append(" · ").append(rest);
        return s;
    }

    private void thumb(ImageView iv, String uri, String name) {
        iv.setImageResource(getResources().getIdentifier(iconFor(name), "drawable", getPackageName())); iv.setColorFilter(Ui.FAINT); iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
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

    private View scroll(View inner) { ScrollView sv = new ScrollView(this); sv.setFillViewport(true); sv.setVerticalScrollBarEnabled(false); sv.addView(inner); return sv; }
    private LinearLayout page() { LinearLayout l = Ui.vbox(this); int g = Ui.gutter(this); l.setPadding(g, dp(Ui.S8), g, dp(Ui.S24)); return l; }

    /** Row used by Files, History and Settings: leading mark, a two-line title, one line of metadata, optional trailing view. */
    private View listRow(View lead, CharSequence title, CharSequence meta, View trail, View.OnClickListener on) {
        LinearLayout r = Ui.hbox(this); r.setMinimumHeight(dp(Ui.ROW_MIN)); r.setPadding(0, dp(Ui.S12), 0, dp(Ui.S12));
        if (lead != null) { LinearLayout.LayoutParams lp = lpOf(lead); lp.setMarginEnd(dp(Ui.S16)); lead.setLayoutParams(lp); r.addView(lead); }
        LinearLayout col = Ui.vbox(this);
        col.addView(Ui.lines(tx(title, Ui.SUBHEAD, Ui.INK), 2));
        if (meta != null) add(col, Ui.lines(tx(meta, Ui.CAPTION, Ui.MUTED), 2), 2);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (trail != null) { LinearLayout.LayoutParams lp = lpOf(trail); lp.setMarginStart(dp(Ui.S12)); trail.setLayoutParams(lp); r.addView(trail); }
        if (on != null) { r.setOnClickListener(on); r.setBackground(Ui.ripple(this, false)); }
        return r;
    }

    private View screenHeader(String title, boolean brand, boolean gear) {
        LinearLayout bar = Ui.hbox(this); bar.setMinimumHeight(dp(Ui.TOUCH));
        TextView t = tx(title, brand ? Ui.WORDMARK : Ui.TITLE, Ui.INK); t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(t);
        if (gear) bar.addView(Ui.iconButton(this, "ic_settings", "Settings", Ui.INK), 1);
        if (gear) bar.getChildAt(1).setOnClickListener(v -> { tab = Tab.MORE; render(); });
        return bar;
    }

    private View topBar(String title, boolean back) {
        FrameLayout f = new FrameLayout(this); f.setMinimumHeight(dp(Ui.TOUCH + Ui.S8)); f.setPadding(dp(Ui.S8), dp(Ui.S4), dp(Ui.S8), dp(Ui.S4));
        TextView t = Ui.center(tx(title, Ui.SUBHEAD, Ui.INK)); t.setGravity(Gravity.CENTER); t.setPadding(dp(Ui.TOUCH + Ui.S8), 0, dp(Ui.TOUCH + Ui.S8), 0);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2); tp.gravity = Gravity.CENTER_VERTICAL; f.addView(t, tp);
        if (back) { ImageView b = Ui.iconButton(this, "ic_back", "Back", Ui.INK); b.setOnClickListener(v -> show(Screen.MAIN)); FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(Ui.TOUCH), dp(Ui.TOUCH)); bp.gravity = Gravity.CENTER_VERTICAL; f.addView(b, bp); }
        return f;
    }

    private View emptyState(String icon, String title, String body, String actionLabel, View.OnClickListener action) {
        LinearLayout c = Ui.vbox(this); c.setGravity(Gravity.CENTER_HORIZONTAL); c.setPadding(dp(Ui.S16), dp(Ui.S32), dp(Ui.S16), dp(Ui.S16));
        add(c, Ui.circle(this, icon, Ui.FAINT, Ui.SOFT, 0, 64), 0);
        add(c, Ui.center(tx(title, Ui.SECTION, Ui.INK)), Ui.S16);
        add(c, Ui.center(tx(body, Ui.BODY_SM, Ui.MUTED)), Ui.S8);
        if (actionLabel != null) { Button b = Ui.button(this, actionLabel, 1); b.setOnClickListener(action); add(c, b, Ui.S24); }
        return c;
    }

    private View banner() {
        if (bannerTitle == null && !working) return null;
        if (!working && SystemClock.elapsedRealtime() > bannerUntil) return null;
        int bg = bannerKind == 1 ? Ui.GREEN_BG : bannerKind == 2 ? Ui.RED_BG : Ui.SOFT, fg = bannerKind == 1 ? Ui.GREEN : bannerKind == 2 ? Ui.RED : Ui.INK;
        LinearLayout b = Ui.hbox(this); b.setGravity(Gravity.TOP); b.setPadding(dp(Ui.S16), dp(Ui.S12), dp(Ui.S16), dp(Ui.S12)); b.setBackground(Ui.rounded(this, bg, Ui.R_SM, 0));
        View lead = working ? Ui.spinner(this, 20, fg) : Ui.icon(this, bannerKind == 1 ? "ic_check" : "ic_info", 20, fg);
        LinearLayout.LayoutParams lp = lpOf(lead); lp.setMarginEnd(dp(Ui.S12)); lp.topMargin = dp(2); lead.setLayoutParams(lp); b.addView(lead);
        LinearLayout col = Ui.vbox(this);
        col.addView(tx(bannerTitle == null ? "" : bannerTitle, Ui.BODY_SM, fg));
        if (bannerHint != null) add(col, tx(bannerHint, Ui.CAPTION, fg), Ui.S4);
        b.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!working) { b.setOnClickListener(v -> { bannerTitle = null; refreshHome(); }); b.setContentDescription("Dismiss message"); }
        return b;
    }

    // ---------------------------------------------------------------- 1. splash

    private View buildSplash() {
        LinearLayout l = Ui.vbox(this); l.setGravity(Gravity.CENTER_HORIZONTAL); int g = Ui.gutter(this); l.setPadding(g, dp(Ui.S32), g, dp(Ui.S32));
        l.addView(Ui.flex(this));
        ImageView logo = new ImageView(this); logo.setImageResource(getResources().getIdentifier("flux_logo", "drawable", getPackageName())); logo.setAdjustViewBounds(true); logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        l.addView(logo, new LinearLayout.LayoutParams(dp(88), -2));
        add(l, Ui.center(tx("FLUX", Ui.DISPLAY, Ui.INK)), Ui.S24).setLetterSpacing(0.28f);
        add(l, Ui.center(tx("Your files. Your devices.\nSeamlessly.", Ui.BODY, Ui.MUTED)), Ui.S12);
        l.addView(Ui.flex(this));
        l.addView(Ui.spinner(this, 24, Ui.FAINT));
        add(l, Ui.center(tx("Getting things ready…", Ui.CAPTION, Ui.MUTED)), Ui.S12);
        return l;
    }

    // ---------------------------------------------------------------- 2. onboarding

    private View buildOnboard() {
        LinearLayout l = page(); l.setPadding(Ui.gutter(this), dp(Ui.S32), Ui.gutter(this), dp(Ui.S24));
        add(l, Ui.center(tx("Welcome to FLUX", Ui.TITLE, Ui.INK)), 0);
        add(l, Ui.center(tx("Send files between your phone and your computer, straight over your own Wi-Fi.", Ui.BODY, Ui.MUTED)), Ui.S8);
        add(l, new Illustration(this), Ui.S24).setLayoutParams(lpWithHeight(dp(140), Ui.S24));
        String[][] f = {{"ic_bolt", "Fast transfers", "Files go directly between your devices, with nothing uploaded."}, {"ic_shield", "Secure connection", "Everything is encrypted, and only devices you pair can connect."}, {"ic_phone", "Works both ways", "Send from your phone to your PC, or receive from your PC."}};
        for (String[] r : f) {
            LinearLayout row = Ui.hbox(this); row.setGravity(Gravity.TOP); row.setPadding(0, dp(Ui.S12), 0, dp(Ui.S12));
            View mark = Ui.circle(this, r[0], Ui.INK, Ui.SOFT, 0, 40); LinearLayout.LayoutParams mp = lpOf(mark); mp.setMarginEnd(dp(Ui.S16)); mark.setLayoutParams(mp); row.addView(mark);
            LinearLayout col = Ui.vbox(this); col.addView(tx(r[1], Ui.SUBHEAD, Ui.INK)); add(col, tx(r[2], Ui.BODY_SM, Ui.MUTED), Ui.S4);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f)); l.addView(row);
        }
        l.addView(Ui.flex(this));
        Button go = Ui.button(this, "Get started", 0); go.setOnClickListener(v -> { prefs.edit().putBoolean("onboarded", true).apply(); show(Screen.MAIN); }); add(l, go, Ui.S24);
        return scroll(l);
    }

    private LinearLayout.LayoutParams lpWithHeight(int heightPx, int topDp) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, heightPx); p.topMargin = dp(topDp); return p; }

    // ---------------------------------------------------------------- main shell + bottom navigation

    private View buildMain() {
        LinearLayout col = Ui.vbox(this);
        View page = tab == Tab.HOME ? homePage() : tab == Tab.FILES ? filesPage() : tab == Tab.HISTORY ? historyPage() : morePage();
        col.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));
        col.addView(navBar(), new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    private View navBar() {
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setMinimumHeight(dp(56));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setStroke(Math.max(1, dp(1)), Ui.LINE); bar.setBackground(bg);
        Object[][] items = {{Tab.HOME, "ic_home", "Home"}, {Tab.FILES, "ic_folder", "Files"}, {Tab.HISTORY, "ic_history", "History"}, {Tab.MORE, "ic_more", "More"}};
        for (Object[] it : items) {
            final Tab tb = (Tab) it[0]; boolean on = tab == tb; int c = on ? Ui.INK : Ui.MUTED;
            LinearLayout cell = Ui.vbox(this); cell.setGravity(Gravity.CENTER); cell.setPadding(dp(Ui.S4), dp(Ui.S8), dp(Ui.S4), dp(Ui.S8)); cell.setBackground(Ui.ripple(this, false));
            ImageView ic = Ui.icon(this, (String) it[1], 24, c); cell.addView(ic);
            TextView lb = Ui.center(tx((String) it[2], Ui.NAV, c)); lb.setGravity(Gravity.CENTER); lb.setSingleLine(true); lb.setEllipsize(TextUtils.TruncateAt.END);
            if (on) lb.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 700, false));
            add(cell, lb, Ui.S4);
            cell.setOnClickListener(v -> { tab = tb; render(); }); cell.setContentDescription((String) it[2]); cell.setSelected(on);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f); bar.addView(cell, p);
        }
        return bar;
    }

    // ---------------------------------------------------------------- 3 + 5. home

    private View homePage() {
        LinearLayout l = page();
        l.addView(screenHeader("FLUX", true, true));
        View bn = banner(); if (bn != null) add(l, bn, Ui.S12);
        if (!paired()) {
            LinearLayout card = Ui.vbox(this); card.setGravity(Gravity.CENTER_HORIZONTAL); card.setPadding(dp(Ui.S16), dp(Ui.S24), dp(Ui.S16), dp(Ui.S24)); card.setBackground(Ui.rounded(this, Color.WHITE, Ui.R_LG, Ui.LINE));
            add(card, new Illustration(this), 0).setLayoutParams(lpWithHeight(dp(104), 0));
            add(card, Ui.center(tx("Ready to connect", Ui.HEADLINE, Ui.INK)), Ui.S16);
            add(card, Ui.center(tx("Scan the QR code on your computer to pair your devices.", Ui.BODY_SM, Ui.MUTED)), Ui.S8);
            add(l, card, Ui.S16);
            Button scan = Ui.button(this, "Scan QR code", 0); scan.setOnClickListener(v -> startScan()); add(l, scan, Ui.S24);
            LinearLayout or = Ui.hbox(this);
            View a = Ui.divider(this); or.addView(a, new LinearLayout.LayoutParams(0, lpOf(a).height, 1f));
            TextView o = tx("or", Ui.CAPTION, Ui.MUTED); o.setLayoutParams(new LinearLayout.LayoutParams(-2, -2)); o.setPadding(dp(Ui.S12), 0, dp(Ui.S12), 0); or.addView(o);
            View b2 = Ui.divider(this); or.addView(b2, new LinearLayout.LayoutParams(0, lpOf(b2).height, 1f));
            add(l, or, Ui.S12);
            Button man = Ui.button(this, "Enter code manually", 1); man.setOnClickListener(v -> manualDialog()); add(l, man, Ui.S12);
            add(l, Ui.center(tx("On your computer, open FLUX and choose Devices, then Pair a device.", Ui.CAPTION, Ui.MUTED)), Ui.S24);
        } else {
            boolean live = pcReachable && netUp;
            LinearLayout card = Ui.vbox(this); card.setGravity(Gravity.CENTER_HORIZONTAL); card.setPadding(dp(Ui.S16), dp(Ui.S24), dp(Ui.S16), dp(Ui.S24)); card.setBackground(Ui.rounded(this, Color.WHITE, Ui.R_LG, Ui.LINE));
            add(card, Ui.circle(this, live ? "ic_check" : "ic_info", live ? Ui.GREEN : Ui.MUTED, Color.WHITE, live ? Ui.GREEN : 0xFFCBD1DA, 48), 0);
            add(card, Ui.center(tx(live ? "Connected" : netUp ? "Not connected" : "No network", Ui.HEADLINE, Ui.INK)), Ui.S16);
            add(card, Ui.center(tx(live ? "Your phone and PC are connected." : netUp ? "Open FLUX on your computer and make sure both devices are on the same Wi-Fi." : "Connect to Wi-Fi to send or receive files.", Ui.BODY_SM, Ui.MUTED)), Ui.S8);
            LinearLayout chip = Ui.hbox(this); chip.setPadding(dp(Ui.S12), dp(Ui.S8), dp(Ui.S16), dp(Ui.S8)); chip.setBackground(Ui.rounded(this, Ui.SOFT, Ui.R_SM, Ui.LINE));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(20), dp(20)); ip.setMarginEnd(dp(Ui.S8)); ImageView lap = Ui.icon(this, "ic_laptop", 20, Ui.INK); lap.setLayoutParams(ip); chip.addView(lap);
            TextView cn = tx(pcName(), Ui.BODY_SM, Ui.INK); cn.setLayoutParams(new LinearLayout.LayoutParams(-2, -2)); cn.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)); lines(cn, 1); chip.addView(cn);
            chip.setContentDescription("Paired with " + pcName());
            add(card, chip, Ui.S16);
            add(l, card, Ui.S16);

            PendingItem p = pending;
            if (p != null && live) {
                LinearLayout pc = Ui.vbox(this); pc.setPadding(dp(Ui.S16), dp(Ui.S16), dp(Ui.S16), dp(Ui.S16)); pc.setBackground(Ui.rounded(this, Color.WHITE, Ui.R_MD, Ui.ACCENT));
                pc.addView(tx("Waiting on your PC", Ui.CAPTION, Ui.ACCENT));
                add(pc, tx((p.fileCount == 1 ? "1 file" : p.fileCount + " files") + " · " + mb(p.totalBytes), Ui.SECTION, Ui.INK), Ui.S4);
                StringBuilder d = new StringBuilder(); for (int i = 0; i < Math.min(3, p.names.size()); i++) d.append(i == 0 ? "" : ", ").append(p.names.get(i)); if (p.fileCount > 3) d.append(" and ").append(p.fileCount - 3).append(" more");
                add(pc, Ui.lines(tx(d.toString(), Ui.BODY_SM, Ui.MUTED), 2), Ui.S4);
                Button rb = Ui.button(this, "Receive files", 0); rb.setOnClickListener(v -> startReceive()); add(pc, rb, Ui.S16);
                add(l, pc, Ui.S16);
            }
            add(l, tx("Quick actions", Ui.SECTION, Ui.INK), Ui.S24);
            add(l, actionRow("ic_send", "Send files", "Photos, videos and documents", v -> { sendTab = 0; show(Screen.SEND); }), Ui.S12);
            add(l, actionRow("ic_download", "Receive files", p != null ? p.fileCount + (p.fileCount == 1 ? " file is waiting on your PC" : " files are waiting on your PC") : "Check your PC for files to receive", v -> { if (pending != null) startReceive(); else checkNow(); }), Ui.S12);
        }
        return scroll(l);
    }

    private View actionRow(String icon, String title, String sub, View.OnClickListener on) {
        LinearLayout r = Ui.hbox(this); r.setMinimumHeight(dp(Ui.ROW_MIN)); r.setPadding(dp(Ui.S16), dp(Ui.S12), dp(Ui.S12), dp(Ui.S12)); r.setBackground(Ui.rounded(this, Color.WHITE, Ui.R_MD, Ui.LINE));
        View mark = Ui.circle(this, icon, Ui.INK, Ui.SOFT, 0, 40); LinearLayout.LayoutParams mp = lpOf(mark); mp.setMarginEnd(dp(Ui.S16)); mark.setLayoutParams(mp); r.addView(mark);
        LinearLayout c = Ui.vbox(this); c.addView(tx(title, Ui.SUBHEAD, Ui.INK)); add(c, Ui.lines(tx(sub, Ui.CAPTION, Ui.MUTED), 2), 2);
        r.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
        View chev = Ui.icon(this, "ic_chevron", 24, Ui.FAINT); LinearLayout.LayoutParams cp = lpOf(chev); cp.setMarginStart(dp(Ui.S8)); chev.setLayoutParams(cp); r.addView(chev);
        r.setOnClickListener(on); Ui.press(r); return r;
    }

    private TextView lines(TextView v, int n) { return Ui.lines(v, n); }

    // ---------------------------------------------------------------- 9. files

    private View filesPage() {
        LinearLayout l = page(); l.addView(screenHeader("Files", false, false));
        LinearLayout seg = Ui.hbox(this); seg.setBackground(Ui.rounded(this, Ui.SOFT, Ui.R_SM, 0)); seg.setPadding(dp(Ui.S4), dp(Ui.S4), dp(Ui.S4), dp(Ui.S4));
        String[] names = {"Received", "Sent"};
        for (int i = 0; i < 2; i++) {
            final boolean isSent = i == 1; boolean on = sentTab == isSent;
            TextView b = Ui.center(tx(names[i], Ui.TAB, on ? Color.WHITE : Ui.MUTED)); b.setGravity(Gravity.CENTER); b.setMinHeight(dp(40)); b.setPadding(0, dp(Ui.S8), 0, dp(Ui.S8));
            b.setBackground(on ? Ui.rounded(this, Ui.INK, Ui.R_SM - 2, 0) : Ui.ripple(this, false)); b.setSelected(on);
            b.setOnClickListener(v -> { sentTab = isSent; render(); }); b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f)); seg.addView(b);
        }
        add(l, seg, Ui.S12);
        int shown = 0; LinearLayout list = Ui.vbox(this);
        for (LocalHistory.Entry e : LocalHistory.all(this)) {
            if (e.sent != sentTab || !e.ok) continue;
            for (LocalHistory.FileRec f : e.files) {
                if (shown++ > 150) break;
                if (list.getChildCount() > 0) list.addView(Ui.divider(this));
                ImageView th = new ImageView(this); th.setBackground(Ui.rounded(this, Ui.SOFT, Ui.R_SM, 0)); th.setClipToOutline(true); th.setLayoutParams(new LinearLayout.LayoutParams(dp(Ui.TOUCH), dp(Ui.TOUCH))); thumb(th, f.uri, f.name);
                ImageView chev = Ui.icon(this, "ic_chevron", 24, Ui.FAINT);
                list.addView(listRow(th, f.name, kindLabel(f.name) + " · " + mb(f.size) + " · " + when(e.time), (f.uri != null && !sentTab) ? chev : null, (f.uri != null && !sentTab) ? v -> openFile(f) : null));
            }
        }
        if (shown == 0) {
            add(l, sentTab ? emptyState("ic_folder", "Nothing sent yet", "Files you send to your PC are listed here.", "Send files", v -> { sendTab = 0; show(Screen.SEND); })
                    : emptyState("ic_folder", "No received files yet", "Files from your PC are saved in Downloads/FLUX and listed here.", paired() ? "Check my PC for files" : "Pair with your PC", v -> { if (paired()) checkNow(); else { tab = Tab.HOME; render(); } }), Ui.S16);
        } else add(l, list, Ui.S8);
        return scroll(l);
    }

    private void openFile(LocalHistory.FileRec f) {
        try {
            if (ext(f.name).matches("exe|msi|bat|cmd|com|scr|ps1|vbs|js|jar|apk|lnk|dll")) { Toast.makeText(this, "For safety FLUX won't open this type of file.", Toast.LENGTH_LONG).show(); return; }
            String mime = java.net.URLConnection.guessContentTypeFromName(f.name);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(f.uri), mime == null ? "*/*" : mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open with"));
        } catch (Throwable x) { Toast.makeText(this, "No app can open this file.", Toast.LENGTH_SHORT).show(); }
    }

    // ---------------------------------------------------------------- history

    private View historyPage() {
        LinearLayout l = page(); l.addView(screenHeader("History", false, false));
        List<LocalHistory.Entry> all = LocalHistory.all(this);
        LinearLayout list = Ui.vbox(this);
        for (LocalHistory.Entry e : all) {
            if (list.getChildCount() > 0) list.addView(Ui.divider(this));
            String what = e.count == 1 && !e.files.isEmpty() ? e.files.get(0).name : e.count + " files";
            String rest = e.ok ? mb(e.bytes) + " · " + secsText(e.secs) + " · " + when(e.time) : (e.error == null ? when(e.time) : e.error);
            View mark = Ui.circle(this, e.ok ? "ic_check" : "ic_close", Color.WHITE, e.ok ? Ui.INK : Ui.RED, 0, 40);
            list.addView(listRow(mark, (e.sent ? "Sent " : "Received ") + what, e.ok ? status("Completed", Ui.GREEN, rest) : status("Not completed", Ui.RED, rest), null, null));
        }
        if (all.isEmpty()) add(l, emptyState("ic_history", "No transfers yet", "Every transfer from this phone is listed here, with its real result.", paired() ? "Send files" : null, v -> { sendTab = 0; show(Screen.SEND); }), Ui.S16);
        else {
            add(l, list, Ui.S8);
            Button clr = Ui.button(this, "Clear history", 2);
            clr.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Clear history?").setMessage("This removes the list only. Files you already saved stay where they are.").setPositiveButton("Clear", (d, w) -> { LocalHistory.clear(this); render(); }).setNegativeButton("Cancel", null).show());
            add(l, clr, Ui.S16);
        }
        return scroll(l);
    }

    // ---------------------------------------------------------------- 10. settings

    private String networkLabel() {
        try {
            if (cm == null) cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork(); NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) return "No network";
            return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Connected to Wi-Fi" : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile data. Your PC must be on the same Wi-Fi as this phone." : "Connected";
        } catch (Throwable x) { return "Unknown"; }
    }

    private String version() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable x) { return "0.3"; } }

    private View settingRow(String icon, String title, String value, View.OnClickListener on) {
        View lead = Ui.icon(this, icon, 24, Ui.INK);
        return listRow(lead, title, value, on != null ? Ui.icon(this, "ic_chevron", 24, Ui.FAINT) : null, on);
    }

    private View morePage() {
        LinearLayout l = page(); l.addView(screenHeader("Settings", false, false));
        LinearLayout rows = Ui.vbox(this);
        rows.addView(settingRow("ic_phone", "Device name", deviceName(), v -> nameDialog())); rows.addView(Ui.divider(this));
        List<String> addrs = addrList();
        rows.addView(settingRow("ic_wifi", "Network", networkLabel() + (paired() && !addrs.isEmpty() ? "\nPC address: " + addrs.get(0) : ""), null)); rows.addView(Ui.divider(this));
        rows.addView(settingRow("ic_lock", "Security", "End-to-end encryption", v -> new AlertDialog.Builder(this).setTitle("Security")
                .setMessage("Everything between this phone and your PC is encrypted (TLS 1.3). Each device has its own cryptographic identity, and your phone only talks to a PC it has paired with, checked on every connection. Files are verified (SHA-256) before they are saved.\n\nThis phone's fingerprint:\n" + ownFp).setPositiveButton("OK", null).show())); rows.addView(Ui.divider(this));
        rows.addView(settingRow("ic_info", "About FLUX", "Version " + version(), v -> new AlertDialog.Builder(this).setTitle("About FLUX").setMessage("FLUX v" + version() + "\nFiles go straight between your devices over your own network. Nothing is uploaded to a server.").setPositiveButton("OK", null).show()));
        add(l, rows, Ui.S8);
        if (paired()) {
            Button forget = Ui.button(this, "Forget this PC", 1); forget.setTextColor(Ui.RED);
            forget.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Forget " + pcName() + "?").setMessage("You will need to pair again to send or receive files with it.")
                    .setPositiveButton("Forget", (d, w) -> { prefs.edit().remove("pc_fp").remove("pc_addrs").remove("pc_name").apply(); pending = null; pcReachable = false; tab = Tab.HOME; render(); }).setNegativeButton("Cancel", null).show());
            add(l, forget, Ui.S24);
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
        l.addView(topBar("Send files", true));
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL); String[] tn = {"Photos & videos", "Documents", "Others"};
        for (int i = 0; i < 3; i++) {
            final int idx = i; boolean on = sendTab == i;
            LinearLayout cell = Ui.vbox(this); cell.setGravity(Gravity.CENTER); cell.setMinimumHeight(dp(Ui.TOUCH)); cell.setBackground(Ui.ripple(this, false));
            TextView lb = Ui.center(tx(tn[i], Ui.TAB, on ? Ui.INK : Ui.MUTED)); lb.setGravity(Gravity.CENTER); lb.setPadding(dp(Ui.S4), dp(Ui.S12), dp(Ui.S4), dp(Ui.S12)); lb.setSingleLine(true); lb.setEllipsize(TextUtils.TruncateAt.END); cell.addView(lb);
            View u = new View(this); u.setBackgroundColor(on ? Ui.INK : Color.TRANSPARENT); cell.addView(u, new LinearLayout.LayoutParams(-1, dp(2)));
            cell.setOnClickListener(v -> { sendTab = idx; render(); }); cell.setSelected(on); tabs.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        l.addView(tabs); l.addView(Ui.divider(this));
        if (!paired()) { LinearLayout m = page(); add(m, emptyState("ic_laptop", "Pair with your PC first", "Go back and scan the QR code on your computer.", "Back to Home", v -> show(Screen.MAIN)), Ui.S24); l.addView(m); return l; }

        int g = Ui.gutter(this), gap = dp(Ui.S8);
        int cell = (getResources().getDisplayMetrics().widthPixels - 2 * g - 2 * gap) / 3;
        GridLayout grid = new GridLayout(this); grid.setColumnCount(3);
        LinearLayout add = Ui.vbox(this); add.setGravity(Gravity.CENTER); add.setPadding(dp(Ui.S4), dp(Ui.S4), dp(Ui.S4), dp(Ui.S4));
        GradientDrawable dash = new GradientDrawable(); dash.setCornerRadius(dp(Ui.R_MD)); dash.setColor(Ui.SOFT); dash.setStroke(dp(2), 0xFFC7CCD4, dp(Ui.S8), dp(Ui.S4)); add.setBackground(dash);
        add.addView(Ui.icon(this, "ic_add", 24, Ui.INK));
        add(add, Ui.center(tx("Add files", Ui.CAPTION, Ui.INK)), Ui.S4);
        add.setOnClickListener(v -> pickFiles()); add.setContentDescription("Add files"); addCell(grid, add, cell, gap);
        for (UriSource u : new ArrayList<>(selected)) {
            FrameLayout f = new FrameLayout(this); f.setBackground(Ui.rounded(this, Ui.SOFT, Ui.R_MD, 0));
            ImageView iv = new ImageView(this); iv.setClipToOutline(true); iv.setBackground(Ui.rounded(this, Ui.SOFT, Ui.R_MD, 0)); thumb(iv, u.uri().toString(), u.relPath()); f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            TextView nm = tx(u.relPath(), Ui.CAPTION, Ui.INK); Ui.lines(nm, 1); nm.setBackgroundColor(0xE6FFFFFF); nm.setPadding(dp(Ui.S8), dp(Ui.S4), dp(Ui.S8), dp(Ui.S4));
            FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2); np.gravity = Gravity.BOTTOM; f.addView(nm, np);
            LinearLayout xb = new LinearLayout(this); xb.setBackground(Ui.rounded(this, Ui.INK, Ui.R_SM, 0)); xb.setGravity(Gravity.CENTER); xb.setClickable(true); xb.setContentDescription("Remove " + u.relPath());
            ImageView xi = Ui.icon(this, "ic_close", 16, Color.WHITE); xb.addView(xi); xb.setOnClickListener(v -> { selected.remove(u); render(); });
            FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(dp(32), dp(32)); xp.gravity = Gravity.TOP | Gravity.END; xp.setMargins(0, dp(Ui.S4), dp(Ui.S4), 0); f.addView(xb, xp);
            addCell(grid, f, cell, gap);
        }
        LinearLayout gp = Ui.vbox(this); gp.setPadding(g, dp(Ui.S16), g, dp(Ui.S16)); gp.addView(grid);
        l.addView(scroll(gp), new LinearLayout.LayoutParams(-1, 0, 1f));

        long total = 0; for (UriSource u : selected) total += u.size();
        LinearLayout bar = Ui.hbox(this); bar.setPadding(dp(Ui.S16), dp(Ui.S12), dp(Ui.S12), dp(Ui.S12)); bar.setBackground(Ui.rounded(this, Ui.INK, Ui.R_MD, 0));
        LinearLayout tcol = Ui.vbox(this);
        tcol.addView(tx(selected.isEmpty() ? "No files selected" : selected.size() + (selected.size() == 1 ? " file selected" : " files selected"), Ui.SUBHEAD, Color.WHITE));
        if (!selected.isEmpty()) add(tcol, tx(mb(total) + " in total", Ui.CAPTION, Ui.ON_INK_MUTED), 2);
        bar.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));
        Button send = Ui.button(this, "Send", 1); send.setEnabled(!selected.isEmpty()); send.setAlpha(selected.isEmpty() ? .5f : 1f); send.setOnClickListener(v -> startSend());
        send.setLayoutParams(new LinearLayout.LayoutParams(-2, -2)); send.setMinWidth(dp(96)); bar.addView(send);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.setMargins(g, dp(Ui.S4), g, dp(Ui.S16)); l.addView(bar, bp);
        return l;
    }

    private void addCell(GridLayout g, View v, int size, int gap) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(); p.width = size; p.height = size; p.setMargins(0, 0, gap / 2, gap / 2); g.addView(v, p);
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
        if (!paired()) { setBanner("Pair with your PC first", "Then share these files again.", 2); screen = Screen.MAIN; render(); return; }
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
        Xfer x = xfer; LinearLayout l = Ui.vbox(this); l.addView(topBar(x.sending ? "Sending" : "Receiving", false));
        LinearLayout body = page(); body.setGravity(Gravity.CENTER_HORIZONTAL); body.setPadding(Ui.gutter(this), dp(Ui.S16), Ui.gutter(this), dp(Ui.S16));
        FrameLayout ringBox = new FrameLayout(this); x.ring = new ProgressRing(this); ringBox.addView(x.ring, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout mid = Ui.vbox(this); mid.setGravity(Gravity.CENTER); mid.setPadding(dp(Ui.S16), 0, dp(Ui.S16), 0);
        x.pct = Ui.center(Ui.num(this, "0%", Ui.STAT, Ui.INK)); x.bytes = Ui.center(Ui.num(this, "", Ui.CAPTION, Ui.MUTED)); mid.addView(x.pct); add(mid, x.bytes, Ui.S4);
        ringBox.addView(mid, new FrameLayout.LayoutParams(-1, -1));
        int ring = Math.min(dp(200), getResources().getDisplayMetrics().widthPixels - 2 * Ui.gutter(this));
        body.addView(ringBox, new LinearLayout.LayoutParams(ring, ring));
        x.name = add(body, Ui.center(Ui.lines(tx(x.current, Ui.SUBHEAD, Ui.INK), 2)), Ui.S24);
        x.state = add(body, Ui.center(tx("", Ui.BODY_SM, Ui.MUTED)), Ui.S4);
        x.remain = add(body, Ui.center(tx("", Ui.CAPTION, Ui.MUTED)), Ui.S4);
        x.list = Ui.vbox(this); add(body, x.list, Ui.S24);
        l.addView(scroll(body), new LinearLayout.LayoutParams(-1, 0, 1f));
        Button c = Ui.button(this, "Cancel transfer", 0); c.setOnClickListener(v -> { cancel.set(true); c.setText("Cancelling…"); c.setEnabled(false); });
        LinearLayout.LayoutParams cp = lpOf(c); int g = Ui.gutter(this); cp.setMargins(g, dp(Ui.S8), g, dp(Ui.S16)); c.setLayoutParams(cp); l.addView(c);
        x.listedDone = -1; updateTransferUi();
        return l;
    }

    private void updateTransferUi() {
        Xfer x = xfer;
        if (x == null || screen != Screen.TRANSFER || x.ring == null) return;
        float p = x.total > 0 ? (float) x.done / x.total : 0f;
        x.ring.setProgress(p); x.pct.setText((int) Math.floor(p * 100) + "%"); x.bytes.setText(mb(x.done) + " of " + mb(x.total));
        x.name.setText(x.current.isEmpty() ? (x.names.isEmpty() ? "" : x.names.get(0)) : x.current);
        int n = x.names.size();
        x.state.setText((x.sending ? "Sending to " : "Receiving from ") + pcName() + (n > 1 ? " · file " + Math.min(x.filesDone + 1, n) + " of " + n : ""));
        long now = SystemClock.elapsedRealtime();
        if (x.winT == 0) { x.winT = now; x.winB = x.done; }
        else if (now - x.winT >= 1500) { x.speed = (x.done - x.winB) / ((now - x.winT) / 1000.0); x.winT = now; x.winB = x.done; }
        String rem = "";
        if (x.speed > 1024 && x.total > x.done) { long s = Math.max(1, Math.round((x.total - x.done) / x.speed)); rem = s < 60 ? "About " + s + (s == 1 ? " second" : " seconds") + " left · " + mb(Math.round(x.speed)) + "/s" : "About " + Math.round(s / 60.0) + " min left · " + mb(Math.round(x.speed)) + "/s"; }
        x.remain.setText(rem);
        if (x.listedDone != x.filesDone) {
            x.listedDone = x.filesDone; x.list.removeAllViews();
            for (int i = 0; i < Math.min(n, 30); i++) {
                View lead = Ui.icon(this, iconFor(x.names.get(i)), 24, Ui.MUTED);
                View trail = i < x.filesDone ? Ui.circle(this, "ic_check", Color.WHITE, Ui.INK, 0, 24) : i == x.filesDone ? Ui.spinner(this, 24, Ui.INK) : Ui.icon(this, "ic_clock", 24, Ui.FAINT);
                if (i > 0) x.list.addView(Ui.divider(this));
                x.list.addView(listRow(lead, x.names.get(i), i < x.sizes.size() ? mb(x.sizes.get(i)) + (i < x.filesDone ? " · Done" : i == x.filesDone ? " · In progress" : " · Waiting") : null, trail, null));
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
        final String fp = prefs.getString("pc_fp", null); final List<String> addrs = addrList();
        Xfer x = new Xfer(); x.sending = true; for (UriSource u : files) { x.names.add(u.relPath()); x.sizes.add(u.size()); x.total += u.size(); }
        xfer = x; cancel.set(false); busy = true; show(Screen.TRANSFER);
        bg(() -> {
            Problem pr = null; boolean cancelled = false, ok = false;
            try {
                FluxException last = null;
                for (String addr : addrs) {
                    try { client.send(addr, fp, new ArrayList<SourceFile>(files), listener(), cancel); last = null; promote(addr); break; }
                    catch (FluxException e) { last = e; if (e.kind != FluxException.Kind.NETWORK) break; }
                }
                if (last != null) { cancelled = last.kind == FluxException.Kind.CANCELLED; throw last; }
                ok = true;
            } catch (FluxException e) { pr = problem(e); }
            final boolean fOk = ok, fCan = cancelled; final Problem fPr = pr;
            ui(() -> finishXfer(fOk, fCan, fPr, null));
        });
    }

    private void startReceive() {
        PendingItem p = pending;
        if (client == null || busy || !paired() || p == null) return;
        final String fp = prefs.getString("pc_fp", null); final List<String> addrs = addrList();
        Xfer x = new Xfer(); x.sending = false; x.names.addAll(p.names); x.sizes.addAll(p.sizes); x.total = p.totalBytes;
        xfer = x; cancel.set(false); busy = true; show(Screen.TRANSFER);
        final MediaSink sink = new MediaSink(getContentResolver());
        bg(() -> {
            Problem pr = null; boolean cancelled = false, ok = false;
            try {
                FluxException last = null;
                for (String addr : addrs) {
                    try { client.receive(addr, fp, sink, listener(), cancel); last = null; promote(addr); break; }
                    catch (FluxException e) { last = e; if (e.kind != FluxException.Kind.NETWORK) break; }
                }
                if (last != null) { cancelled = last.kind == FluxException.Kind.CANCELLED; throw last; }
                ok = true;
            } catch (FluxException e) { pr = problem(e); }
            final boolean fOk = ok, fCan = cancelled; final Problem fPr = pr;
            ui(() -> finishXfer(fOk, fCan, fPr, sink.saved()));
        });
    }

    private void finishXfer(boolean ok, boolean cancelled, Problem problem, Map<String, String> savedUris) {
        Xfer x = xfer; busy = false; if (x == null) { show(Screen.MAIN); return; }
        x.finished = true; x.ok = ok; x.cancelled = cancelled; x.problem = problem; x.secs = Math.max(1, (SystemClock.elapsedRealtime() - x.started) / 1000);
        LocalHistory.Entry e = new LocalHistory.Entry(); e.time = System.currentTimeMillis(); e.sent = x.sending; e.ok = ok; e.secs = x.secs; e.error = ok ? null : (problem == null ? null : problem.title);
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
        Xfer x = xfer; LinearLayout l = page(); l.setGravity(Gravity.CENTER_HORIZONTAL); l.setPadding(Ui.gutter(this), dp(Ui.S32 + Ui.S16), Ui.gutter(this), dp(Ui.S16));
        add(l, Ui.circle(this, x.ok ? "ic_check" : "ic_close", Color.WHITE, x.ok ? Ui.INK : Ui.RED, 0, 72), 0);
        String title = x.ok ? (x.sending ? "Transfer complete" : "Files received") : x.cancelled ? "Transfer cancelled" : (x.problem == null ? "Transfer failed" : x.problem.title);
        add(l, Ui.center(tx(title, Ui.TITLE, Ui.INK)), Ui.S24);
        String msg = x.ok ? (x.sending ? "Your files were sent to " + pcName() + "." : "Every file was verified and saved to Downloads/FLUX.") : (x.problem == null ? "" : x.problem.hint);
        if (msg != null && !msg.isEmpty()) add(l, Ui.center(tx(msg, Ui.BODY, Ui.MUTED)), Ui.S8);
        if (!x.ok && x.problem != null && x.problem.detail != null && !x.problem.detail.trim().isEmpty()) add(l, Ui.center(tx("Details: " + x.problem.detail.trim(), Ui.CAPTION, Ui.MUTED)), Ui.S12);
        LinearLayout card = Ui.vbox(this); card.setPadding(dp(Ui.S16), dp(Ui.S4), dp(Ui.S16), dp(Ui.S4)); card.setBackground(Ui.rounded(this, Ui.SOFT, Ui.R_MD, 0));
        int n = x.ok ? x.names.size() : x.filesDone;
        String[][] rows = {{"ic_file", x.sending ? "Files sent" : "Files received", String.valueOf(n)}, {"ic_download", "Total size", mb(x.ok ? x.total : x.done)}, {"ic_clock", "Time", secsText(x.secs)}};
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) card.addView(Ui.divider(this));
            LinearLayout row = Ui.hbox(this); row.setMinimumHeight(dp(Ui.TOUCH)); row.setPadding(0, dp(Ui.S12), 0, dp(Ui.S12));
            View ic = Ui.icon(this, rows[i][0], 24, Ui.INK); LinearLayout.LayoutParams ip = lpOf(ic); ip.setMarginEnd(dp(Ui.S16)); ic.setLayoutParams(ip); row.addView(ic);
            TextView k = tx(rows[i][1], Ui.BODY_SM, Ui.MUTED); k.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f)); row.addView(k);
            TextView v = Ui.num(this, rows[i][2], Ui.SUBHEAD, Ui.INK); v.setLayoutParams(new LinearLayout.LayoutParams(-2, -2)); v.setGravity(Gravity.END); row.addView(v);
            card.addView(row);
        }
        add(l, card, Ui.S24);
        l.addView(Ui.flex(this));
        if (x.ok) { Button vf = Ui.button(this, "View files", 0); vf.setOnClickListener(v -> { sentTab = x.sending; selected.clear(); tab = Tab.FILES; show(Screen.MAIN); }); add(l, vf, Ui.S24); }
        else { Button again = Ui.button(this, "Try again", 0); again.setOnClickListener(v -> { if (x.sending) startSend(); else startReceive(); }); add(l, again, Ui.S24); }
        Button done = Ui.button(this, "Done", 1); done.setOnClickListener(v -> { selected.clear(); tab = Tab.HOME; show(Screen.MAIN); }); add(l, done, Ui.S12);
        return scroll(l);
    }

    /** Plain-language explanation of a failure: what happened, what to do, and (small) the technical detail. */
    private Problem problem(FluxException e) {
        String d = e.getMessage();
        switch (e.kind) {
            case REJECTED: return new Problem("Your PC declined the transfer", "Start it again, and accept it on your PC when asked.", d == null || d.isEmpty() ? null : d);
            case CANCELLED: return new Problem("Transfer cancelled", "Nothing was kept on your PC.", null);
            case CANCELLED_BY_RECEIVER: return new Problem("Your PC cancelled the transfer", "You can start it again from here.", null);
            case SECURITY: return new Problem("Couldn't make a secure connection", "Pair with your PC again to refresh the connection.", d);
            case NETWORK: return new Problem("Couldn't reach your PC", "Open FLUX on your computer and make sure both devices are on the same Wi-Fi.", d == null ? null : d.replaceFirst("^can't reach ", ""));
            case PAIRING: return new Problem("Couldn't pair", "Codes work once and expire after two minutes. Create a new one on your PC and try again.", d);
            case INTEGRITY: return new Problem("A file didn't pass verification", "Nothing was saved. The files are still on your PC, so try again.", d);
            case STORAGE: return new Problem("Couldn't save the file", "Check that your phone has free space, then try again.", d);
            default: return new Problem("Transfer failed", "Something unexpected happened. Try again.", d);
        }
    }

    // ================================================================== pairing

    private void startScan() {
        if (busy || client == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM); return; }
        try {
            new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("Point your camera at the QR code on your computer screen.\nMake sure both devices are on the same Wi-Fi network.").setBeepEnabled(false).setOrientationLocked(false).initiateScan();
        } catch (Throwable t) { setBanner("Couldn't open the camera", "Use Enter code manually instead.", 2); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_CAM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startScan();
        else setBanner("Camera permission is needed to scan", "Allow it in Settings, or use Enter code manually.", 2);
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

    private final Runnable clearBanner = () -> { if (!working && SystemClock.elapsedRealtime() >= bannerUntil) { bannerTitle = null; refreshHome(); } };

    /** A short message on Home. It clears itself after a few seconds so it can never contradict the state shown below it. */
    private void setBanner(String title, String hint, int kind) {
        bannerTitle = title; bannerHint = hint; bannerKind = kind; working = false; bannerUntil = SystemClock.elapsedRealtime() + BANNER_MS; tab = Tab.HOME;
        handler.removeCallbacks(clearBanner); handler.postDelayed(clearBanner, BANNER_MS + 200);
        if (screen == Screen.MAIN) render();
    }

    private void setWorking(String title) { bannerTitle = title; bannerHint = null; bannerKind = 0; working = true; tab = Tab.HOME; show(Screen.MAIN); }

    private void handleCode(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.toLowerCase(Locale.ROOT).startsWith("flux://")) pairFromLink(v);
        else if (FluxClient.normalizeCode(v) != null) pairByCode(v);
        else setBanner("That isn't a FLUX pairing code", "Type the 9 characters shown on your PC, or scan its QR code.", 2);
    }

    private void manualDialog() {
        final EditText in = new EditText(this); in.setHint("K7M-2QX-9R4"); in.setSingleLine(true); in.setTextSize(20); in.setGravity(Gravity.CENTER);
        in.setFilters(new InputFilter[]{new InputFilter.LengthFilter(600)});   // no AllCaps: a pasted link must stay intact
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this).setTitle("Enter code").setMessage("Type the 9-character code shown on your PC under Devices, then Pair a device. You can also paste the full pairing link.").setView(in)
                .setPositiveButton("Pair", (d, w) -> handleCode(in.getText().toString()))
                .setNeutralButton("Paste link", (d, w) -> { ClipboardManager c = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); CharSequence t = (c != null && c.hasPrimaryClip() && c.getPrimaryClip().getItemCount() > 0) ? c.getPrimaryClip().getItemAt(0).coerceToText(this) : null; handleCode(t == null ? "" : t.toString()); })
                .setNegativeButton("Cancel", null).show();
    }

    private void pairFromLink(String link) {
        if (client == null || busy) return;
        busy = true; setWorking("Pairing with your PC…");
        bg(() -> {
            try {
                PairingLink pl = PairingLink.parse(link); PairResult r = client.pair(pl);
                List<String> order = new ArrayList<>(pl.addrs); order.remove(r.addr); order.add(0, r.addr);
                prefs.edit().putString("pc_name", r.name.isEmpty() ? "Your PC" : r.name).putString("pc_fp", r.fingerprint).putString("pc_addrs", String.join(",", order)).apply();
                pending = null; pcReachable = true; ui(() -> { busy = false; setBanner("Paired with " + pcName(), "You can send files now.", 1); schedule(500); });
            } catch (FluxException e) { final Problem pr = problem(e); ui(() -> { busy = false; setBanner(pr.title, pr.hint, 2); }); }
        });
    }

    private void pairByCode(String code) {
        if (client == null || busy) return;
        busy = true; setWorking("Looking for your PC on this Wi-Fi…");
        bg(() -> {
            try {
                Trace.step("paircode: scanning wifi");
                List<String> found = LanScan.find(this);
                if (found.isEmpty()) throw new FluxException(FluxException.Kind.NETWORK, "no FLUX device answered on this Wi-Fi");
                PairResult r = client.pairWithCode(code, found);
                prefs.edit().putString("pc_name", r.name).putString("pc_fp", r.fingerprint).putString("pc_addrs", r.addr).apply();
                pending = null; pcReachable = true; ui(() -> { busy = false; setBanner("Paired with " + pcName(), "You can send files now.", 1); schedule(500); });
            } catch (FluxException e) { final Problem pr = problem(e); ui(() -> { busy = false; setBanner(pr.title, pr.hint, 2); }); }
        });
    }

    // ================================================================== looking for files from the PC (only while on screen)

    private void schedule(long ms) { handler.removeCallbacks(pollTask); handler.postDelayed(pollTask, ms); }

    private void registerNet() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { netUp = true; pollMs = POLL_BASE_MS; ui(() -> { schedule(400); refreshHome(); }); }
            @Override public void onLost(Network n) { netUp = false; pcReachable = false; pending = null; ui(MainActivity.this::refreshHome); }
        };
        try { cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCb); } catch (RuntimeException e) { netCb = null; }
    }
    private void unregisterNet() { try { if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb); } catch (RuntimeException ignored) { } netCb = null; }

    private void poll() { look(false); }
    private void checkNow() { setWorking("Checking your PC…"); look(true); }

    private void look(final boolean manual) {
        if (!resumed) return;
        final String fp = prefs.getString("pc_fp", null); final List<String> addrs = addrList();
        if (client == null || busy || fp == null || addrs.isEmpty() || !netUp) { if (manual) { working = false; setBanner("No network", "Connect to Wi-Fi and try again.", 2); } else schedule(pollMs); return; }
        bg(() -> {
            PendingItem p = null; boolean reach = false;
            for (String a : addrs) {
                try { p = client.peek(a, fp); reach = true; break; }
                catch (FluxException e) { if (e.kind != FluxException.Kind.NETWORK) { reach = e.kind == FluxException.Kind.REMOTE; break; } }
            }
            final PendingItem found = reach ? p : null; final boolean ok = reach;
            pollMs = ok ? POLL_BASE_MS : Math.min(POLL_MAX_MS, pollMs * 2);
            ui(() -> {
                pending = found; pcReachable = ok;
                String sig = ok + "|" + (found == null ? "-" : found.transferId);
                if (manual) { if (!ok) setBanner("Couldn't reach your PC", "Open FLUX on your computer and make sure both devices are on the same Wi-Fi.", 2); else if (found == null) setBanner("Nothing is waiting on your PC", null, 0); else { working = false; bannerTitle = null; render(); } }
                else if (!sig.equals(lastSig)) refreshHome();
                lastSig = sig;
                if (!busy && resumed) schedule(pollMs);
            });
        });
    }
}
