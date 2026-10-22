package app.flux.protocol;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static app.flux.protocol.FluxException.Kind;

/** Phone/PC-side FLUX client (v1 wire protocol): pairing and sending. No resume yet: files restart from byte 0. */
public final class FluxClient {
    private static final int APPROVAL_WAIT_MS = 135_000;
    private final KeyManager[] keys;
    private final String deviceName, deviceType;
    private String ownFingerprint = "";

    public FluxClient(KeyManager[] keys, String deviceName, String deviceType) {
        this.keys = keys; this.deviceName = clean(deviceName); this.deviceType = deviceType;
    }

    /** Needed for short-code pairing: the proofs are bound to this device's own certificate fingerprint. */
    public FluxClient(KeyManager[] keys, String ownFingerprint, String deviceName, String deviceType) {
        this(keys, deviceName, deviceType); this.ownFingerprint = ownFingerprint;
    }

    private static String clean(String n) {
        StringBuilder b = new StringBuilder();
        for (char c : (n == null ? "" : n).toCharArray()) if (!Character.isISOControl(c) && b.length() < 64) b.append(c);
        String s = b.toString().trim();
        return s.isEmpty() ? "Phone" : s;
    }

    private Map<String, Object> hello(String purpose, String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "hello"); m.put("proto", 1L); m.put("device_name", deviceName); m.put("device_type", deviceType);
        m.put("purpose", purpose); m.put("token", token);
        return m;
    }

    private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof String ? (String) v : ""; }

    /** Pair with a PC using the link from its QR code. The PC's fingerprint in the link is pinned. */
    public PairResult pair(PairingLink link) throws FluxException {
        Trace.step("pair: start");
        FluxException best = null; StringBuilder tried = new StringBuilder();
        for (String addr : link.addrs) {
            try (SSLSocket s = PinnedTls.connect(keys, addr, link.fingerprint, 6_000)) {
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                Trace.step("pair: sending hello");
                Frames.writeMsg(out, hello("pair", link.token));
                Map<String, Object> r = Frames.readMsg(in);
                Trace.step("pair: got reply");
                String t = str(r, "t");
                if (t.equals("pair_ok")) {
                    String name = str(r, "device_name");
                    return new PairResult(name.isEmpty() ? link.hostName : name, link.fingerprint, addr);
                }
                if (t.equals("error")) throw new FluxException(Kind.PAIRING, str(r, "message"));
                throw new FluxException(Kind.PROTOCOL, "unexpected reply to pairing");
            } catch (FluxException e) {
                if (e.kind == Kind.PAIRING) throw e; // the PC answered and refused: no point trying other addresses
                tried.append("\n- ").append(addr).append(": ").append(e.getMessage());
                if (best == null || (best.kind == Kind.NETWORK && e.kind != Kind.NETWORK)) best = e;
            } catch (IOException e) {
                tried.append("\n- ").append(addr).append(": connection lost while pairing: ").append(e.getMessage());
                if (best == null) best = new FluxException(Kind.NETWORK, "connection lost while pairing", e);
            }
        }
        if (best == null) throw new FluxException(Kind.PAIRING, "the pairing code has no usable address");
        throw new FluxException(best.kind, best.getMessage() + "\n\nTried:" + tried, best);
    }


    // ------------------------------------------------------------------ pairing with the short typed code

    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** "k7m-2qx-9r4" -> "K7M2QX9R4"; null if not a valid 9-character code. */
    public static String normalizeCode(String raw) {
        if (raw == null) return null;
        StringBuilder b = new StringBuilder();
        for (char c : raw.toCharArray()) if (Character.isLetterOrDigit(c)) b.append(Character.toUpperCase(c));
        String s = b.toString();
        if (s.length() != 9) return null;
        for (char c : s.toCharArray()) if (CODE_ALPHABET.indexOf(c) < 0) return null;
        return s;
    }

    private static byte[] mac(String code, String role, String pcFp, String clientFp) throws Exception {
        javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
        m.init(new javax.crypto.spec.SecretKeySpec(code.getBytes("UTF-8"), "HmacSHA256"));
        return m.doFinal(("flux-pair-v1|" + role + "|" + pcFp + "|" + clientFp).getBytes("UTF-8"));
    }

    /**
     * Pair using the 9-character code shown on the PC. The PC is not pinned beforehand (the code carries no identity), so
     * it is authenticated by its proof of the code, which is bound to its certificate and ours: an impostor cannot make it.
     * Tries each candidate address (devices found on the Wi-Fi); non-FLUX devices are skipped.
     */
    public PairResult pairWithCode(String rawCode, List<String> candidates) throws FluxException {
        String code = normalizeCode(rawCode);
        if (code == null) throw new FluxException(Kind.PAIRING, "enter the 9-character code shown on your PC");
        if (ownFingerprint.isEmpty()) throw new FluxException(Kind.PAIRING, "this phone's identity is not ready");
        Trace.step("paircode: start");
        FluxException last = new FluxException(Kind.PAIRING, "no device on your Wi-Fi accepted this code");
        for (String addr : candidates) {
            try (SSLSocket s = PinnedTls.connect(keys, addr, null, 4_000)) {
                s.setSoTimeout(8_000);
                String pcFp = PinnedTls.serverFingerprint(s);
                DataOutputStream out = dout(s); DataInputStream in = din(s);
                Frames.writeMsg(out, hello("pair_code", null));
                Map<String, Object> pr = new LinkedHashMap<>(); pr.put("t", "pair_code"); pr.put("proof", hex(mac(code, "client", pcFp, ownFingerprint)));
                Frames.writeMsg(out, pr);
                Map<String, Object> r = Frames.readMsg(in);
                String t = str(r, "t");
                if (t.equals("error")) throw new FluxException(Kind.PAIRING, str(r, "message"));
                if (!t.equals("pair_ok")) throw new FluxException(Kind.PROTOCOL, "unexpected reply to pairing");
                byte[] given = new byte[0];
                try { String hx = str(r, "proof"); given = new byte[hx.length() / 2]; for (int i = 0; i < given.length; i++) given[i] = (byte) Integer.parseInt(hx.substring(2 * i, 2 * i + 2), 16); } catch (RuntimeException ignored) { }
                if (!java.security.MessageDigest.isEqual(given, mac(code, "server", pcFp, ownFingerprint)))
                    throw new FluxException(Kind.PAIRING, "that device could not prove it knows the code, so it was not trusted");
                String name = str(r, "device_name");
                Trace.step("paircode: success");
                return new PairResult(name.isEmpty() ? "Your PC" : name, pcFp, addr);
            } catch (FluxException e) {
                last = e;
            } catch (Exception e) {
                last = new FluxException(Kind.NETWORK, "can't talk to " + addr + ": " + e.getMessage(), e);
            }
        }
        throw last;
    }

    private static String newId() {
        byte[] b = new byte[16]; new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString();
    }

    /** Send files to a paired PC. Blocks; run on a background thread. Set `cancel` to abort (receiver discards partial data). */
    public void send(String addr, String fingerprint, List<SourceFile> files, TransferListener ui, AtomicBoolean cancel) throws FluxException {
        if (files.isEmpty()) throw new FluxException(Kind.SOURCE, "nothing to send");
        long total = 0; for (SourceFile f : files) total += f.size();
        ui.onStatus("Connecting…");
        try (SSLSocket s = PinnedTls.connect(keys, addr, fingerprint, 8_000)) {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            Frames.writeMsg(out, hello("transfer", null));
            List<Object> metas = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", (long) i); m.put("path", files.get(i).relPath()); m.put("size", files.get(i).size()); m.put("mtime", files.get(i).mtimeSeconds());
                metas.add(m);
            }
            Map<String, Object> offer = new LinkedHashMap<>();
            offer.put("t", "offer"); offer.put("transfer_id", newId()); offer.put("files", metas);
            Frames.writeMsg(out, offer);
            ui.onStatus("Waiting for the PC to accept…");
            s.setSoTimeout(APPROVAL_WAIT_MS);
            Map<String, Object> reply = Frames.readMsg(in);
            s.setSoTimeout(30_000);
            switch (str(reply, "t")) {
                case "accept": break;
                case "reject": throw new FluxException(Kind.REJECTED, str(reply, "reason"));
                case "error": throw new FluxException(Kind.REMOTE, str(reply, "message"));
                default: throw new FluxException(Kind.PROTOCOL, "unexpected reply to offer");
            }
            ui.onStatus("Sending…");
            long done = 0; byte[] buf = new byte[Frames.CHUNK];
            for (int i = 0; i < files.size(); i++) {
                SourceFile f = files.get(i);
                Map<String, Object> st = new LinkedHashMap<>();
                st.put("t", "file_start"); st.put("index", (long) i); st.put("offset", 0L);
                Frames.writeMsg(out, st);
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long sent = 0;
                try (InputStream src = f.open()) {
                    while (sent < f.size()) {
                        if (cancel.get()) { cancelNow(out); throw new FluxException(Kind.CANCELLED, "cancelled"); }
                        int n = src.read(buf, 0, (int) Math.min(buf.length, f.size() - sent));
                        if (n < 0) { cancelNow(out); throw new FluxException(Kind.SOURCE, f.relPath() + " became shorter while sending"); }
                        sha.update(buf, 0, n);
                        try { Frames.writeData(out, buf, n); }
                        catch (IOException e) { throw explain(in, e); }
                        sent += n; done += n;
                        ui.onProgress(done, total, f.relPath(), i, files.size());
                    }
                }
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("t", "file_end"); end.put("index", (long) i); end.put("size", f.size()); end.put("sha256", hex(sha.digest()));
                try { Frames.writeMsg(out, end); } catch (IOException e) { throw explain(in, e); }
                Map<String, Object> r = Frames.readMsg(in);
                String t = str(r, "t");
                if (t.equals("cancel")) throw new FluxException(Kind.CANCELLED_BY_RECEIVER, "cancelled by the PC");
                if (t.equals("error")) throw new FluxException(Kind.REMOTE, str(r, "message"));
                if (!t.equals("file_result") || !Boolean.TRUE.equals(r.get("ok"))) throw new FluxException(Kind.REMOTE, "the PC rejected " + f.relPath() + ": " + str(r, "error"));
                ui.onProgress(done, total, f.relPath(), i + 1, files.size());
            }
            Map<String, Object> fin = Frames.readMsg(in);
            if (str(fin, "t").equals("cancel")) throw new FluxException(Kind.CANCELLED_BY_RECEIVER, "cancelled by the PC");
            if (!str(fin, "t").equals("complete")) throw new FluxException(Kind.REMOTE, str(fin, "message"));
            ui.onStatus("Done: the PC verified every file.");
        } catch (FluxException e) { throw e; }
        catch (IOException e) { throw new FluxException(Kind.NETWORK, "connection lost: " + e.getMessage(), e); }
        catch (Exception e) { throw new FluxException(Kind.PROTOCOL, "unexpected error: " + e, e); }
    }


    // ------------------------------------------------------------------ PC -> phone (the phone pulls)

    private DataOutputStream dout(SSLSocket s) throws IOException { return new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024)); }
    private DataInputStream din(SSLSocket s) throws IOException { return new DataInputStream(new BufferedInputStream(s.getInputStream())); }

    /** Safe relative path from the PC: no '..', no absolute paths, no odd characters. Returns null if hostile. */
    public static String safePath(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 1024) return null;
        List<String> parts = new ArrayList<>();
        for (String p : raw.split("/", -1)) {
            String t = p.trim();
            if (t.isEmpty() || t.equals(".") || t.equals("..")) return null;
            String c = t.replaceAll("[\\\\:*?\"<>|\\p{Cntrl}]", "_");
            while (c.endsWith(".") || c.endsWith(" ")) c = c.substring(0, c.length() - 1);
            if (c.isEmpty() || c.length() > 200) return null;
            parts.add(c);
            if (parts.size() > 32) return null;
        }
        return String.join("/", parts);
    }

    /**
     * Ask the PC whether anything is queued for us. NEVER downloads or consumes anything (the PC treats the
     * Reject we send as "just looking"). Returns null when nothing is waiting. One short, authenticated request.
     */
    public PendingItem peek(String addr, String fingerprint) throws FluxException {
        try (SSLSocket s = PinnedTls.connect(keys, addr, fingerprint, 5_000)) {
            s.setSoTimeout(8_000);
            DataOutputStream out = dout(s); DataInputStream in = din(s);
            Trace.step("peek: asking the PC");
            Frames.writeMsg(out, hello("fetch", null));
            Map<String, Object> m = Frames.readMsg(in);
            String t = str(m, "t");
            Trace.step("peek: reply " + t);
            if (t.equals("complete")) return null;
            if (t.equals("error")) throw new FluxException(Kind.REMOTE, str(m, "message"));
            if (!t.equals("offer")) throw new FluxException(Kind.PROTOCOL, "unexpected reply to check-in");
            List<String> names = new ArrayList<>(); List<Long> sizes = new ArrayList<>(); long total = 0; int n = 0;
            Object files = m.get("files");
            if (files instanceof List) for (Object o : (List<?>) files) {
                if (!(o instanceof Map)) continue;
                n++; Object sz = ((Map<?, ?>) o).get("size"); long szv = sz instanceof Number ? ((Number) sz).longValue() : 0; total += szv;
                if (names.size() < 20) { sizes.add(szv); String p = safePath(String.valueOf(((Map<?, ?>) o).get("path"))); names.add(p == null ? "(unsafe name)" : p); }
            }
            Map<String, Object> rej = new LinkedHashMap<>(); rej.put("t", "reject"); rej.put("reason", "peek");
            Frames.writeMsg(out, rej);
            return new PendingItem(str(m, "transfer_id"), names, sizes, total, n);
        } catch (FluxException e) { throw e; }
        catch (IOException e) { throw new FluxException(Kind.NETWORK, "connection lost: " + e.getMessage(), e); }
    }

    /**
     * Download what the PC queued. Each file is verified (exact size + SHA-256) BEFORE it is committed to the sink
     * and BEFORE we acknowledge it; "done" is reported only after every file verified and the PC confirmed completion.
     * If anything fails the PC keeps the item queued, so this can simply be retried.
     */
    public void receive(String addr, String fingerprint, ReceiveSink sink, TransferListener ui, AtomicBoolean cancel) throws FluxException {
        String openFile = null;
        try (SSLSocket s = PinnedTls.connect(keys, addr, fingerprint, 8_000)) {
            DataOutputStream out = dout(s); DataInputStream in = din(s);
            Frames.writeMsg(out, hello("fetch", null));
            Map<String, Object> m = Frames.readMsg(in);
            String t = str(m, "t");
            if (t.equals("complete")) { ui.onStatus("Nothing is waiting on your PC."); return; }
            if (t.equals("error")) throw new FluxException(Kind.REMOTE, str(m, "message"));
            if (!t.equals("offer")) throw new FluxException(Kind.PROTOCOL, "unexpected reply to check-in");
            List<?> files = m.get("files") instanceof List ? (List<?>) m.get("files") : new ArrayList<>();
            if (files.isEmpty() || files.size() > 2000) throw new FluxException(Kind.PROTOCOL, "bad file list from the PC");
            String[] paths = new String[files.size()]; long[] sizes = new long[files.size()]; long total = 0;
            for (int i = 0; i < files.size(); i++) {
                Map<?, ?> f = (Map<?, ?>) files.get(i);
                paths[i] = safePath(String.valueOf(f.get("path")));
                Object sz = f.get("size");
                if (paths[i] == null || !(sz instanceof Number) || ((Number) sz).longValue() < 0) throw new FluxException(Kind.PROTOCOL, "the PC offered an unsafe file name");
                sizes[i] = ((Number) sz).longValue(); total += sizes[i];
            }
            Map<String, Object> acc = new LinkedHashMap<>(); acc.put("t", "accept"); acc.put("resume", new ArrayList<>());
            Frames.writeMsg(out, acc);
            ui.onStatus("Downloading…");
            long done = 0;
            for (int i = 0; i < files.size(); i++) {
                Frames.Frame st = Frames.readFrame(in);
                if (st.msg == null || !str(st.msg, "t").equals("file_start") || ((Number) st.msg.get("index")).intValue() != i)
                    { if (st.msg != null && str(st.msg, "t").equals("cancel")) throw new FluxException(Kind.CANCELLED_BY_RECEIVER, "cancelled on the PC"); throw new FluxException(Kind.PROTOCOL, "expected file start"); }
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long got = 0; openFile = paths[i];
                try (OutputStream sinkOut = sink.open(paths[i], sizes[i])) {
                    while (true) {
                        if (cancel.get()) { Map<String, Object> c = new LinkedHashMap<>(); c.put("t", "cancel"); Frames.writeMsg(out, c); throw new FluxException(Kind.CANCELLED, "cancelled"); }
                        Frames.Frame fr = Frames.readFrame(in);
                        if (fr.data != null) {
                            got += fr.data.length;
                            if (got > sizes[i]) throw new FluxException(Kind.PROTOCOL, "the PC sent more data than it declared");
                            sha.update(fr.data); sinkOut.write(fr.data); done += fr.data.length;
                            ui.onProgress(done, total, paths[i], i, files.size());
                            continue;
                        }
                        String ft = str(fr.msg, "t");
                        if (ft.equals("cancel")) throw new FluxException(Kind.CANCELLED_BY_RECEIVER, "cancelled on the PC");
                        if (!ft.equals("file_end")) throw new FluxException(Kind.PROTOCOL, "unexpected message during file");
                        boolean ok = got == sizes[i] && ((Number) fr.msg.get("size")).longValue() == got && hex(sha.digest()).equalsIgnoreCase(str(fr.msg, "sha256"));
                        if (!ok) {
                            sinkOut.close(); sink.abort(paths[i]); openFile = null;
                            Map<String, Object> bad = new LinkedHashMap<>(); bad.put("t", "file_result"); bad.put("index", (long) i); bad.put("ok", false); bad.put("error", "hash mismatch");
                            Frames.writeMsg(out, bad);
                            throw new FluxException(Kind.INTEGRITY, "verification failed for " + paths[i] + " (it will stay queued on the PC)");
                        }
                        break;
                    }
                }
                sink.commit(paths[i]); openFile = null;   // only now does the file become visible to the user
                Map<String, Object> okm = new LinkedHashMap<>(); okm.put("t", "file_result"); okm.put("index", (long) i); okm.put("ok", true); okm.put("error", null);
                Frames.writeMsg(out, okm);
                ui.onProgress(done, total, paths[i], i + 1, files.size());
            }
            Map<String, Object> fin = Frames.readMsg(in);
            if (!str(fin, "t").equals("complete")) throw new FluxException(Kind.REMOTE, "the PC did not confirm completion: " + str(fin, "message"));
            ui.onStatus("Done: every file was verified and saved.");
        } catch (FluxException e) { if (openFile != null) sink.abort(openFile); throw e; }
        catch (IOException e) { if (openFile != null) sink.abort(openFile); throw new FluxException(Kind.NETWORK, "connection lost: " + e.getMessage(), e); }
        catch (java.security.NoSuchAlgorithmException e) { throw new FluxException(Kind.PROTOCOL, "SHA-256 unavailable", e); }
    }

    private static void cancelNow(DataOutputStream out) {
        try { Map<String, Object> m = new LinkedHashMap<>(); m.put("t", "cancel"); Frames.writeMsg(out, m); } catch (IOException ignored) { }
    }

    /** A failed write usually means the PC hung up; its last message says why. */
    private static FluxException explain(DataInputStream in, IOException original) {
        try {
            Map<String, Object> m = Frames.readMsg(in);
            String t = str(m, "t");
            if (t.equals("cancel")) return new FluxException(Kind.CANCELLED_BY_RECEIVER, "cancelled by the PC");
            if (t.equals("error")) return new FluxException(Kind.REMOTE, str(m, "message"));
            if (t.equals("reject")) return new FluxException(Kind.REJECTED, str(m, "reason"));
        } catch (Exception ignored) { }
        return new FluxException(Kind.NETWORK, "connection lost: " + original.getMessage(), original);
    }

    private static String hex(byte[] d) { StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x)); return b.toString(); }
}
