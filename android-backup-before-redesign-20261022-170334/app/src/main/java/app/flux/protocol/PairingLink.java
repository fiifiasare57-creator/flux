package app.flux.protocol;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;

/** Parses flux://pair?v=1&a=ip:port&fp=<sha256 hex>&t=<one-time token>&n=<name> (the QR content). */
public final class PairingLink {
    public final List<String> addrs = new ArrayList<>();
    public String fingerprint, token, hostName = "";

    public static PairingLink parse(String uri) throws FluxException {
        PairingLink l = new PairingLink();
        String u = uri == null ? "" : uri.trim();
        if (!u.startsWith("flux://pair?")) throw bad("this is not a FLUX pairing code");
        for (String kv : u.substring("flux://pair?".length()).split("&")) {
            int eq = kv.indexOf('='); if (eq < 0) continue;
            String k = kv.substring(0, eq), v = dec(kv.substring(eq + 1));
            switch (k) {
                case "a": if (l.addrs.size() < 16) l.addrs.add(v); break;
                case "fp": l.fingerprint = v.toLowerCase(Locale.ROOT); break;
                case "t": l.token = v; break;
                case "n": l.hostName = v.length() > 64 ? v.substring(0, 64) : v; break;
                default: break;
            }
        }
        if (l.fingerprint == null || !l.fingerprint.matches("[0-9a-f]{64}")) throw bad("bad device fingerprint");
        if (l.addrs.isEmpty()) throw bad("no address in code");
        if (l.token == null || l.token.length() > 128) throw bad("bad token");
        return l;
    }

    private static FluxException bad(String m) { return new FluxException(FluxException.Kind.PAIRING, m); }
    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { return s; }
    }
}
