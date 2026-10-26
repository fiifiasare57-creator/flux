package app.flux;

import android.Manifest;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import app.flux.protocol.*;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** One screen: pair with a PC by scanning its QR code, then send files to it (picker or the Share menu). */
public class MainActivity extends Activity {
    private static final int REQ_PICK = 11, REQ_CAM = 12;
    private SharedPreferences prefs;
    private FluxClient client;
    private TextView pcName, pcInfo, status, detail, avatar, chip;
    private RoundBar bar;
    private View panel, pcCard, hero, pcBox;
    private TextView pcBoxTitle, pcBoxDetail;
    private Button receiveBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = this::poll;
    private long pollMs = POLL_BASE_MS;
    private volatile boolean netUp = true, pcReachable;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private volatile PendingItem pending;
    private static final long POLL_BASE_MS = 6_000, POLL_MAX_MS = 60_000;
    private Button scanBtn, pasteBtn, sendBtn, cancelBtn;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile boolean busy;
    private long lastUi;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("flux", MODE_PRIVATE);
        installCrashReporter();
        buildUi();
        String crash = prefs.getString("last_crash", null);
        if (crash != null) {
            prefs.edit().remove("last_crash").apply();
            setStatus("FLUX closed unexpectedly last time. Please send this text to the developer:");
            detail.setTextIsSelectable(true);
            detail.setText(crash.length() > 1800 ? crash.substring(0, 1800) : crash);
        }
        try { client = new FluxClient(AndroidIdentity.keyManagers(), Build.MODEL, "phone"); }
        catch (Exception e) { setStatus("Couldn't create this phone's secure identity: " + e.getMessage()); sendBtn.setEnabled(false); scanBtn.setEnabled(false); }
        refresh();
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleShare(i); }

    // ---------------------------------------------------------------- UI

    private static final int INK = 0xFF0A0A0A, MUTED = 0xFF6B7280, LINE = 0xFFE6E8EC, SOFT = 0xFFF6F7F9, ACCENT = 0xFF2F7DF6, GREEN = 0xFF16A34A, RED = 0xFFDC2626;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radiusDp)); g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    /** Gentle press feedback so every control feels alive. */
    private void pressable(View v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) x.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) x.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
    }

    /** kind 0 = solid black, 1 = outlined, 2 = quiet text button. */
    private Button button(String text, int kind) {
        Button x = new Button(this);
        x.setText(text); x.setAllCaps(false); x.setTextSize(16); x.setTypeface(Typeface.DEFAULT_BOLD); x.setStateListAnimator(null);
        x.setTextColor(kind == 0 ? Color.WHITE : INK);
        x.setBackground(kind == 0 ? rounded(INK, 30, 0) : kind == 1 ? rounded(Color.WHITE, 30, 0xFFD5D9E0) : rounded(Color.TRANSPARENT, 30, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(kind == 2 ? 44 : 56)); lp.topMargin = dp(kind == 2 ? 4 : 12);
        x.setLayoutParams(lp);
        pressable(x);
        return x;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Slim rounded progress bar (real progress only). */
    private static final class RoundBar extends View {
        private int value; private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        RoundBar(Context c) { super(c); track.setColor(0xFFE6E8EC); fill.setColor(ACCENT); }
        void setProgress(int v) { value = Math.max(0, Math.min(1000, v)); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), r = h / 2f;
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, track);
            if (value > 0) c.drawRoundRect(new RectF(0, 0, Math.max(h, w * value / 1000f), h), r, r, fill);
        }
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.BLACK);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(Color.WHITE); sv.setFillViewport(true); sv.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); sv.addView(root);

        // ---- hero
        LinearLayout h = new LinearLayout(this); h.setOrientation(LinearLayout.VERTICAL); h.setPadding(dp(24), dp(44), dp(24), dp(76));
        GradientDrawable hb = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF000000, 0xFF161B26});
        float r = dp(34); hb.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r}); h.setBackground(hb);
        LinearLayout brandRow = new LinearLayout(this); brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this); logo.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager())); brandRow.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView brand = text(15, Color.WHITE, true); brand.setText("FLUX"); brand.setLetterSpacing(.35f); brand.setPadding(dp(12), 0, 0, 0); brandRow.addView(brand);
        h.addView(brandRow);
        TextView head = text(34, Color.WHITE, true); head.setText("Your files,\nin motion."); head.setLineSpacing(0, .95f); head.setPadding(0, dp(28), 0, dp(10)); h.addView(head);
        TextView sub = text(14, 0xFF9CA3AF, false); sub.setText("Send photos, videos and files to your PC over your own Wi-Fi. No cloud, no account."); h.addView(sub);
        hero = h; root.addView(h);

        // ---- content (overlaps the hero)
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), 0, dp(20), dp(28));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.topMargin = -dp(46); root.addView(body, bp);

        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16)); card.setBackground(rounded(Color.WHITE, 20, LINE)); card.setElevation(dp(8));
        avatar = text(18, Color.WHITE, true); avatar.setGravity(Gravity.CENTER); avatar.setBackground(rounded(INK, 24, 0)); card.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(14), 0, dp(8), 0);
        pcName = text(18, INK, true); col.addView(pcName); pcInfo = text(13, MUTED, false); col.addView(pcInfo);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        chip = text(12, MUTED, true); chip.setPadding(dp(10), dp(5), dp(10), dp(5)); card.addView(chip);
        pcCard = card; body.addView(card, new LinearLayout.LayoutParams(-1, -2));

        sendBtn = button("↑  Choose files to send", 0); body.addView(sendBtn); sendBtn.setOnClickListener(v -> pick());
        scanBtn = button("Scan QR code to pair", 1); body.addView(scanBtn); scanBtn.setOnClickListener(v -> startScan());
        pasteBtn = button("Or paste the pairing link", 2); body.addView(pasteBtn); pasteBtn.setOnClickListener(v -> pastePair());

        // ---- live status panel (appears once there is something to say)
        LinearLayout pn = new LinearLayout(this); pn.setOrientation(LinearLayout.VERTICAL); pn.setPadding(dp(18), dp(16), dp(18), dp(16));
        pn.setBackground(rounded(SOFT, 20, LINE)); pn.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.topMargin = dp(18); body.addView(pn, pp);
        status = text(16, INK, true); status.setTextIsSelectable(true); pn.addView(status);
        bar = new RoundBar(this); bar.setVisibility(View.GONE); LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(8)); bl.topMargin = dp(14); pn.addView(bar, bl);
        detail = text(13, MUTED, false); detail.setPadding(0, dp(10), 0, 0); pn.addView(detail);
        cancelBtn = button("Cancel transfer", 1); cancelBtn.setVisibility(View.GONE); pn.addView(cancelBtn);
        cancelBtn.setOnClickListener(v -> { cancel.set(true); setStatus("Cancelling…"); });
        panel = pn;

        TextView foot = text(12, MUTED, false); foot.setLineSpacing(0, 1.15f); foot.setPadding(dp(4), dp(22), dp(4), 0);
        foot.setText("Keep FLUX open while sending or receiving, and stay on the same Wi-Fi as your PC. Every file is checked before it is saved. FLUX only looks for files from your PC while it is open; background delivery is not supported yet.");
        body.addView(foot);
        setContentView(sv);

        // gentle entrance
        View[] in = {head, sub, pcCard};
        for (int i = 0; i < in.length; i++) { in[i].setAlpha(0f); in[i].setTranslationY(dp(14)); in[i].animate().alpha(1f).translationY(0).setStartDelay(80L + i * 90L).setDuration(380).start(); }
    }

    private void refresh() {
        String name = prefs.getString("pc_name", null);
        if (name == null) {
            avatar.setText("?"); avatar.setBackground(rounded(0xFFD1D5DB, 24, 0));
            pcName.setText("No PC paired yet"); pcInfo.setText("On your PC: FLUX → Devices → Pair a device");
            chip.setText("Not paired"); chip.setTextColor(MUTED); chip.setBackground(rounded(0xFFF1F3F6, 14, 0));
            sendBtn.setEnabled(false); sendBtn.setAlpha(.4f);
        } else {
            avatar.setText(name.substring(0, 1).toUpperCase()); avatar.setBackground(rounded(INK, 24, 0));
            pcName.setText(name); pcInfo.setText("Trusted · " + prefs.getString("pc_fp", "").substring(0, 8) + "…");
            if (pcReachable && netUp) { chip.setText("● Online"); chip.setTextColor(GREEN); chip.setBackground(rounded(0xFFE8F7EE, 14, 0)); }
            else { chip.setText(netUp ? "○ Paired · not reachable" : "○ No network"); chip.setTextColor(MUTED); chip.setBackground(rounded(0xFFF1F3F6, 14, 0)); }
            sendBtn.setEnabled(!busy); sendBtn.setAlpha(busy ? .4f : 1f);
        }
        scanBtn.setText(name == null ? "Scan QR code to pair" : "Pair a different PC");
    }

    private void installCrashReporter() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                prefs.edit().putString("last_crash", sw.toString()).commit();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private void startScan() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            return;
        }
        try {
            new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("On your PC: FLUX → Devices → Pair a device").setBeepEnabled(false).setOrientationLocked(false).initiateScan();
        } catch (Throwable t) {
            setStatus("Couldn't open the camera scanner (" + t + "). Use \"Or paste the pairing link\" instead.");
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_CAM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startScan();
        else setStatus("Camera permission is needed to scan. Allow it in Settings, or use \"Or paste the pairing link\".");
    }

    /** Fallback that needs no camera: on the PC tap "Copy link", send it to this phone, copy it here, tap this button. */
    private void pastePair() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence t = (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
        if (t == null || !t.toString().trim().startsWith("flux://")) { setStatus("Copy the pairing link first (PC: Pair a device → Copy link, then send it to this phone and copy it here)."); return; }
        pair(t.toString());
    }

    // ---------------------------------------------------------------- lifecycle, polling, network

    @Override protected void onResume() { super.onResume(); registerNet(); schedule(300); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(pollTask); unregisterNet(); }

    private void schedule(long ms) { handler.removeCallbacks(pollTask); handler.postDelayed(pollTask, ms); }

    private void registerNet() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { netUp = true; pollMs = POLL_BASE_MS; ui(() -> schedule(400)); }   // reconnect: look again right away
            @Override public void onLost(Network n) { netUp = false; pcReachable = false; ui(() -> { refresh(); updatePcBox(); }); }
        };
        try { cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCb); }
        catch (RuntimeException e) { netCb = null; }
    }
    private void unregisterNet() { try { if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb); } catch (RuntimeException ignored) { } netCb = null; }

    /** One short authenticated look at the PC's queue ("peek": nothing is consumed). Runs only while the app is open. */
    private void poll() {
        String fp = prefs.getString("pc_fp", null), addrs = prefs.getString("pc_addrs", null);
        if (client == null || busy || fp == null || addrs == null || !netUp) { schedule(pollMs); return; }
        new Thread(() -> {
            PendingItem p = null; boolean reach = false;
            for (String a : addrs.split(",")) {
                try { p = client.peek(a, fp); reach = true; break; }
                catch (FluxException e) { if (e.kind != FluxException.Kind.NETWORK) { reach = e.kind == FluxException.Kind.REMOTE; break; } }
            }
            final PendingItem found = p; final boolean ok = reach;
            pollMs = ok ? POLL_BASE_MS : Math.min(POLL_MAX_MS, pollMs * 2);   // back off while the PC is unreachable
            ui(() -> { pending = found; pcReachable = ok; refresh(); updatePcBox(); if (!busy) schedule(pollMs); });
        }).start();
    }

    private void updatePcBox() {
        PendingItem p = pending;
        if (p == null || busy || !netUp) { pcBox.setVisibility(View.GONE); return; }
        pcBox.setVisibility(View.VISIBLE);
        pcBoxTitle.setText(p.fileCount == 1 ? "1 file is waiting for you" : p.fileCount + " files are waiting for you");
        StringBuilder d = new StringBuilder(mb(p.totalBytes));
        for (int i = 0; i < Math.min(3, p.names.size()); i++) d.append(i == 0 ? " · " : ", ").append(p.names.get(i));
        if (p.fileCount > 3) d.append(" …");
        pcBoxDetail.setText(d);
    }

    /** Download what the PC queued. Success is reported only after every file verified and the PC confirmed. */
    private void receiveNow() {
        String fp = prefs.getString("pc_fp", null), addrs = prefs.getString("pc_addrs", null);
        if (client == null || busy || fp == null || addrs == null) return;
        cancel.set(false); setBusy(true); setStatus("Connecting…"); detail.setText("");
        new Thread(() -> {
            try {
                TransferListener l = new TransferListener() {
                    public void onStatus(String t) { setStatus(t); }
                    public void onProgress(long done, long total, String file, int fd, int ft) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastUi < 120 && done < total) return;
                        lastUi = now;
                        ui(() -> { bar.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
                            detail.setText(String.format(Locale.US, "%s of %s · file %d of %d\n%s", mb(done), mb(total), Math.min(fd + 1, ft), ft, file)); });
                    }
                };
                FluxException last = null;
                for (String addr : addrs.split(",")) {
                    try { client.receive(addr, fp, new MediaSink(getContentResolver()), l, cancel); last = null; pending = null; ui(() -> detail.setText("Saved in Downloads/FLUX")); break; }
                    catch (FluxException e) { last = e; if (!(e.kind == FluxException.Kind.NETWORK && String.valueOf(e.getMessage()).startsWith("can't reach"))) break; }
                }
                if (last != null) throw last;
            } catch (FluxException e) {
                setStatus(friendly(e) + (e.kind == FluxException.Kind.NETWORK || e.kind == FluxException.Kind.INTEGRITY ? "\nIt is still queued on your PC. Tap Receive to try again." : ""));
            } finally { setBusy(false); ui(() -> { updatePcBox(); }); schedule(1500); }
        }).start();
    }

    private void ui(Runnable r) { runOnUiThread(r); }
    private void setStatus(String s) {
        ui(() -> {
            panel.setVisibility(View.VISIBLE);
            status.setText(s);
            boolean ok = s.startsWith("Done") || s.startsWith("Paired with");
            boolean bad = !ok && (s.startsWith("Can't") || s.startsWith("Couldn't") || s.startsWith("Pairing failed") || s.startsWith("Transfer failed") || s.startsWith("Couldn't save")
                    || s.startsWith("The PC") || s.startsWith("Cancelled") || s.startsWith("FLUX closed") || s.startsWith("Camera permission") || s.startsWith("Copy the pairing"));
            status.setTextColor(ok ? GREEN : bad ? RED : INK);
            if (ok) status.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        });
    }
    private void setBusy(boolean on) {
        busy = on;
        ui(() -> {
            cancelBtn.setVisibility(on ? View.VISIBLE : View.GONE); scanBtn.setEnabled(!on); pasteBtn.setEnabled(!on); bar.setVisibility(on ? View.VISIBLE : View.GONE); if (on) bar.setProgress(0);
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            refresh();
        });
    }

    // ---------------------------------------------------------------- pairing

    @Override protected void onActivityResult(int req, int res, Intent data) {
        IntentResult qr = IntentIntegrator.parseActivityResult(req, res, data);
        if (qr != null) { if (qr.getContents() != null) pair(qr.getContents()); return; }
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            send(uris);
        }
    }

    private void pair(String code) {
        if (client == null || busy) return;
        setBusy(true); setStatus("Pairing…"); detail.setText("");
        new Thread(() -> {
            try {
                PairingLink link = PairingLink.parse(code);
                PairResult r = client.pair(link);
                List<String> order = new ArrayList<>(link.addrs); order.remove(r.addr); order.add(0, r.addr);
                prefs.edit().putString("pc_name", r.name.isEmpty() ? "Your PC" : r.name).putString("pc_fp", r.fingerprint).putString("pc_addrs", String.join(",", order)).apply();
                setStatus("Paired with " + (r.name.isEmpty() ? "your PC" : r.name) + ". You can send files now.");
            } catch (FluxException e) {
                setStatus(friendly(e));
            } finally { setBusy(false); }
        }).start();
    }

    // ---------------------------------------------------------------- sending

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private void handleShare(Intent i) {
        if (i == null || busy) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(i.getAction())) { Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) uris.add(u); }
        else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) { ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l != null) uris.addAll(l); }
        if (!uris.isEmpty()) { i.setAction(Intent.ACTION_MAIN); send(uris); }
    }

    private void send(List<Uri> uris) {
        if (client == null || uris.isEmpty() || busy) return;
        String fp = prefs.getString("pc_fp", null), addrs = prefs.getString("pc_addrs", null);
        if (fp == null || addrs == null) { setStatus("Pair with your PC first (scan its QR code)."); return; }
        cancel.set(false); setBusy(true); setStatus("Preparing…"); detail.setText("");
        new Thread(() -> {
            try {
                List<SourceFile> files = new ArrayList<>();
                for (Uri u : uris) { try { files.add(UriSource.of(this, u)); } catch (Exception e) { throw new FluxException(FluxException.Kind.SOURCE, "Couldn't read a selected file: " + e.getMessage()); } }
                TransferListener l = new TransferListener() {
                    public void onStatus(String t) { setStatus(t); }
                    public void onProgress(long done, long total, String file, int fd, int ft) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastUi < 120 && done < total) return;
                        lastUi = now;
                        ui(() -> { bar.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
                            detail.setText(String.format(Locale.US, "%s of %s · file %d of %d\n%s", mb(done), mb(total), Math.min(fd + 1, ft), ft, file)); });
                    }
                };
                FluxException last = null;
                for (String addr : addrs.split(",")) {
                    try { client.send(addr, fp, files, l, cancel); last = null; prefs.edit().putString("pc_addrs", addr + "," + addrs.replace(addr, "")).apply(); break; }
                    catch (FluxException e) { last = e; if (!(e.kind == FluxException.Kind.NETWORK && String.valueOf(e.getMessage()).startsWith("can't reach"))) break; }
                }
                if (last != null) throw last;
            } catch (FluxException e) { setStatus(friendly(e)); }
            finally { setBusy(false); }
        }).start();
    }

    private static String mb(long b) { return b >= 1 << 30 ? String.format(Locale.US, "%.2f GB", b / 1073741824.0) : String.format(Locale.US, "%.1f MB", b / 1048576.0); }

    private static String friendly(FluxException e) {
        switch (e.kind) {
            case REJECTED: return "The PC declined the transfer" + (e.getMessage().isEmpty() ? "." : ": " + e.getMessage());
            case CANCELLED: return "Cancelled. Nothing was kept on the PC.";
            case CANCELLED_BY_RECEIVER: return "The PC cancelled the transfer.";
            case SECURITY: return "Couldn't set up a secure connection to your PC.\n" + e.getMessage();
            case NETWORK: return "Can't reach your PC. Is FLUX open on it, and are both on the same Wi-Fi? If its address changed, scan the QR code again.\n(" + e.getMessage() + ")";
            case PAIRING: return "Pairing failed: " + e.getMessage() + ". Codes expire quickly and work once, so tap New code on the PC and scan again.";
            case INTEGRITY: return "Transfer failed verification: " + e.getMessage();
            case STORAGE: return "Couldn't save the file: " + e.getMessage();
            default: return "Transfer failed: " + e.getMessage();
        }
    }
}
