import app.flux.protocol.*;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.io.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Desktop-JVM end-to-end test of the Android protocol client against the real Rust `flux receive`. */
public class E2E {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "PASS  " : "FAIL  ") + name); if (ok) pass++; else fail++; }

    static KeyManager[] keys(String p12) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(p12)) { ks.load(in, "changeit".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        return kmf.getKeyManagers();
    }

    static SourceFile file(Path p, String rel) {
        return new SourceFile() {
            public String relPath() { return rel; }
            public long size() { try { return Files.size(p); } catch (IOException e) { throw new RuntimeException(e); } }
            public long mtimeSeconds() { return 0; }
            public InputStream open() throws IOException { return Files.newInputStream(p); }
        };
    }

    static String sha(Path p) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) d.update(b, 0, n); }
        StringBuilder sb = new StringBuilder(); for (byte x : d.digest()) sb.append(String.format("%02x", x)); return sb.toString();
    }

    static Path random(Path dir, String name, int size) throws Exception {
        byte[] b = new byte[size]; new Random(size).nextBytes(b); Path p = dir.resolve(name); Files.createDirectories(p.getParent()); Files.write(p, b); return p;
    }

    static final TransferListener QUIET = new TransferListener() { public void onStatus(String t) {} public void onProgress(long a, long b, String f, int x, int y) {} };

    static FluxException.Kind kindOf(Runnable2 r) { try { r.run(); return null; } catch (FluxException e) { return e.kind; } catch (Exception e) { return FluxException.Kind.PROTOCOL; } }
    interface Runnable2 { void run() throws Exception; }

    public static void main(String[] a) throws Exception {
        String link = a[0], recvDir = a[1], p12a = a[2], p12b = a[3], pcOutFile = a[4];
        Path work = Files.createTempDirectory("fluxsrc");
        FluxClient phone = new FluxClient(keys(p12a), "Test Phone", "phone");
        PairingLink pl = PairingLink.parse(link);

        // impostor: same address, wrong pinned fingerprint
        PairingLink forged = PairingLink.parse(link.replace(pl.fingerprint, "0".repeat(64)));
        check("pairing refuses a device whose identity doesn't match the QR fingerprint", kindOf(() -> phone.pair(forged)) == FluxException.Kind.SECURITY);

        PairResult pr = phone.pair(pl);
        check("pairing with the QR link succeeds and returns the PC's name", pr.fingerprint.equals(pl.fingerprint) && !pr.name.isEmpty());
        check("replaying the same QR link fails (single use)", kindOf(() -> new FluxClient(keys(p12b), "Other", "phone").pair(pl)) == FluxException.Kind.PAIRING);

        Path big = random(work, "video.mp4", 25 * 1024 * 1024 + 7), small = random(work, "notes.txt", 123), nested = random(work, "x/cells.jpg", 400_000);
        phone.send(pr.addr, pr.fingerprint, List.of(file(big, "video.mp4"), file(small, "notes.txt"), file(nested, "Biology/cells.jpg")), QUIET, new AtomicBoolean());
        Path R = Paths.get(recvDir);
        check("25 MB video arrives with identical SHA-256", sha(big).equals(sha(R.resolve("video.mp4"))));
        check("small file arrives intact", sha(small).equals(sha(R.resolve("notes.txt"))));
        check("folder structure is preserved", sha(nested).equals(sha(R.resolve("Biology/cells.jpg"))));

        phone.send(pr.addr, pr.fingerprint, List.of(file(small, "notes.txt")), QUIET, new AtomicBoolean());
        check("same name again becomes 'notes (1).txt', original untouched", Files.exists(R.resolve("notes (1).txt")) && sha(small).equals(sha(R.resolve("notes.txt"))));

        // cancel mid-transfer
        Path huge = random(work, "huge.bin", 150 * 1024 * 1024);
        AtomicBoolean cancel = new AtomicBoolean();
        FluxException.Kind k = kindOf(() -> phone.send(pr.addr, pr.fingerprint, List.of(file(huge, "huge.bin")), new TransferListener() {
            public void onStatus(String t) {}
            public void onProgress(long d, long t, String f, int x, int y) { if (d > 5_000_000) cancel.set(true); }
        }, cancel));
        Thread.sleep(500);
        check("cancel stops the transfer", k == FluxException.Kind.CANCELLED);
        check("cancel leaves no file or partial on the PC", !Files.exists(R.resolve("huge.bin")) && !Files.exists(R.resolve(".flux-partial")));

        // hostile name
        FluxException.Kind hk = kindOf(() -> phone.send(pr.addr, pr.fingerprint, List.of(file(small, "../../escape.txt")), QUIET, new AtomicBoolean()));
        check("a '../' filename is refused by the PC", hk == FluxException.Kind.REMOTE);
        check("nothing was written outside the receive folder", !Files.exists(R.getParent().resolve("escape.txt")) && !Files.exists(R.getParent().getParent().resolve("escape.txt")));

        // unpaired phone: the PC must ask the user (stdin is closed in this test, so the prompt is declined)
        FluxClient stranger = new FluxClient(keys(p12b), "Stranger", "phone");
        FluxException.Kind sk = kindOf(() -> stranger.send(pr.addr, pr.fingerprint, List.of(file(small, "sneaky.txt")), QUIET, new AtomicBoolean()));
        check("an unpaired phone is not auto-accepted (PC asks, request declined)", sk == FluxException.Kind.REJECTED && !Files.exists(R.resolve("sneaky.txt")));

        // ---------------- PC -> phone: the PC queued pcOutFile for every paired device (--dev-outbox)
        Thread.sleep(800);
        PendingItem pend = phone.peek(pr.addr, pr.fingerprint);
        check("peek sees the file the PC queued (name and size)", pend != null && pend.fileCount == 1 && pend.names.get(0).equals(Paths.get(pcOutFile).getFileName().toString()) && pend.totalBytes == Files.size(Paths.get(pcOutFile)));
        PendingItem again = phone.peek(pr.addr, pr.fingerprint);
        check("peeking again still shows it (peek never consumes)", again != null && again.transferId.equals(pend.transferId));
        check("an unpaired phone sees nothing and is refused", kindOf(() -> stranger.peek(pr.addr, pr.fingerprint)) == FluxException.Kind.REMOTE);
        Path inbox = Files.createTempDirectory("phoneinbox");
        final java.util.Set<String> committed = new java.util.HashSet<>(), aborted = new java.util.HashSet<>();
        ReceiveSink sink = new ReceiveSink() {
            public OutputStream open(String rel, long size) throws IOException { return Files.newOutputStream(inbox.resolve(rel.replace('/', '_') + ".part")); }
            public void commit(String rel) throws IOException { Files.move(inbox.resolve(rel.replace('/', '_') + ".part"), inbox.resolve(rel.replace('/', '_'))); committed.add(rel); }
            public void abort(String rel) { try { Files.deleteIfExists(inbox.resolve(rel.replace('/', '_') + ".part")); } catch (IOException ignored) { } aborted.add(rel); }
        };
        final String[] last = {""};
        phone.receive(pr.addr, pr.fingerprint, sink, new TransferListener() { public void onStatus(String t) { last[0] = t; } public void onProgress(long d, long t, String f, int x, int y) {} }, new AtomicBoolean());
        String outName = Paths.get(pcOutFile).getFileName().toString();
        check("PC -> phone file arrives with identical SHA-256", Files.exists(inbox.resolve(outName)) && sha(Paths.get(pcOutFile)).equals(sha(inbox.resolve(outName))));
        check("the file was committed only after verification (no leftover .part)", committed.contains(outName) && aborted.isEmpty() && !Files.exists(inbox.resolve(outName + ".part")));
        check("success is reported only after the PC confirmed completion", last[0].startsWith("Done"));
        check("after delivery the queue is empty (peek returns nothing)", phone.peek(pr.addr, pr.fingerprint) == null);
        check("path sanitiser rejects traversal and keeps normal folders", FluxClient.safePath("../x") == null && FluxClient.safePath("/etc/passwd") == null && "Docs/a b.pdf".equals(FluxClient.safePath("Docs/a b.pdf")) && "a_b".equals(FluxClient.safePath("a:b")));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
