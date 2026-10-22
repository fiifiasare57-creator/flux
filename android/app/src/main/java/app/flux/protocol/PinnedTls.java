package app.flux.protocol;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * TLS 1.3 with mutual authentication. The server certificate must match the pinned SHA-256 fingerprint
 * (from the QR code or the saved pairing); our own certificate comes from the supplied KeyManagers.
 */
public final class PinnedTls {
    private PinnedTls() {}

    public static String fingerprint(byte[] certDer) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(certDer);
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final class Pin extends X509ExtendedTrustManager {
        final String expected;   // null = accept any certificate (short-code pairing authenticates via the code instead)
        Pin(String expected) { this.expected = expected == null ? null : expected.toLowerCase(); }
        void check(X509Certificate[] chain) throws CertificateException {
            if (expected == null) return;
            try {
                if (chain == null || chain.length == 0 || !fingerprint(chain[0].getEncoded()).equals(expected))
                    throw new CertificateException("device identity does not match the pinned fingerprint");
            } catch (CertificateException e) { throw e; } catch (Exception e) { throw new CertificateException(e); }
        }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { check(c); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { check(c); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { check(c); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("client mode only"); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { throw new CertificateException("client mode only"); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { throw new CertificateException("client mode only"); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    /** Server certificate fingerprint of an established connection. */
    public static String serverFingerprint(SSLSocket s) throws Exception {
        return fingerprint(((X509Certificate) s.getSession().getPeerCertificates()[0]).getEncoded());
    }

    public static SSLSocket connect(KeyManager[] keys, String hostPort, String expectedFp, int timeoutMs) throws FluxException {
        int c = hostPort.lastIndexOf(':');
        if (c <= 0) throw new FluxException(FluxException.Kind.NETWORK, "bad address " + hostPort);
        String host = hostPort.substring(0, c);
        int port;
        try { port = Integer.parseInt(hostPort.substring(c + 1)); } catch (NumberFormatException e) { throw new FluxException(FluxException.Kind.NETWORK, "bad port"); }
        SSLSocket s = null;
        Trace.step("tls: connecting to " + hostPort);
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(keys, new TrustManager[]{new Pin(expectedFp)}, new SecureRandom());
            s = (SSLSocket) ctx.getSocketFactory().createSocket();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            Trace.step("tls: tcp connected, starting handshake");
            s.setSoTimeout(30_000);
            s.setEnabledProtocols(new String[]{"TLSv1.3"});
            s.startHandshake();
            Trace.step("tls: handshake complete");
            return s;
        } catch (SSLException e) {
            close(s);
            throw new FluxException(FluxException.Kind.SECURITY, "secure connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            close(s);
            throw new FluxException(FluxException.Kind.NETWORK, "can't reach " + hostPort + ": " + e.getMessage(), e);
        }
    }

    private static void close(Socket s) { try { if (s != null) s.close(); } catch (Exception ignored) { } }
}
