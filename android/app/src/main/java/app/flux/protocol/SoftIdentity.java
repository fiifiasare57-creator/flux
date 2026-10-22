package app.flux.protocol;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * This phone's FLUX identity: an ECDSA P-256 key pair and a self-signed certificate created by the app itself
 * (stored in the app's private storage). Using a plain software key keeps TLS client authentication on the most
 * widely tested path on every Android version and device, unlike hardware keystore keys. The certificate is
 * assembled here as DER so no extra library is needed; its SHA-256 is the device's FLUX fingerprint.
 */
public final class SoftIdentity {
    private static final char[] PW = "flux".toCharArray();
    private SoftIdentity() {}

    public static KeyManager[] load(File dir) throws Exception {
        Trace.step("identity: load");
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
        File kf = new File(dir, "identity.key"), cf = new File(dir, "identity.crt");
        PrivateKey priv; Certificate cert;
        if (kf.exists() && cf.exists()) {
            priv = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(kf.toPath())));
            cert = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Files.readAllBytes(cf.toPath())));
        } else {
            Trace.step("identity: generate");
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            byte[] der = selfSigned(kp);
            cert = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
            write(kf, kp.getPrivate().getEncoded());   // key first: a cert without a key would be useless
            write(cf, der);
            priv = kp.getPrivate();
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("flux", priv, PW, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PW);
        return kmf.getKeyManagers();
    }

    public static String fingerprint(File dir) throws Exception {
        return PinnedTls.fingerprint(Files.readAllBytes(new File(dir, "identity.crt").toPath()));
    }

    private static void write(File f, byte[] data) throws java.io.IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data); o.getFD().sync(); }
        if (!tmp.renameTo(f)) throw new java.io.IOException("cannot write " + f);
    }

    // ---------------------------------------------------------------- minimal DER

    private static byte[] cat(byte[]... p) { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] b : p) o.write(b, 0, b.length); return o.toByteArray(); }
    private static byte[] tlv(int tag, byte[]... parts) {
        byte[] c = cat(parts); ByteArrayOutputStream o = new ByteArrayOutputStream(); o.write(tag);
        if (c.length < 128) o.write(c.length); else if (c.length < 256) { o.write(0x81); o.write(c.length); } else { o.write(0x82); o.write(c.length >> 8); o.write(c.length & 0xFF); }
        o.write(c, 0, c.length); return o.toByteArray();
    }
    private static byte[] utc(Date d) { SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'"); f.setTimeZone(TimeZone.getTimeZone("UTC")); return tlv(0x17, f.format(d).getBytes()); }

    private static byte[] selfSigned(KeyPair kp) throws Exception {
        byte[] ecdsaSha256 = tlv(0x30, new byte[]{0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02});
        byte[] cn = tlv(0x30, tlv(0x31, tlv(0x30, new byte[]{0x06, 0x03, 0x55, 0x04, 0x03}, tlv(0x0C, "FLUX phone".getBytes()))));
        byte[] serial = new byte[8]; new SecureRandom().nextBytes(serial); serial[0] &= 0x3F; serial[0] |= 0x01;   // positive, non-zero
        long now = System.currentTimeMillis();
        byte[] tbs = tlv(0x30,
                new byte[]{(byte) 0xA0, 0x03, 0x02, 0x01, 0x02},          // version: v3
                tlv(0x02, serial), ecdsaSha256, cn,
                tlv(0x30, utc(new Date(now - 86_400_000L)), utc(new Date(now + 20L * 365 * 86_400_000L))),
                cn, kp.getPublic().getEncoded());                          // SubjectPublicKeyInfo, already DER
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate()); sig.update(tbs);
        return tlv(0x30, tbs, ecdsaSha256, tlv(0x03, new byte[]{0x00}, sig.sign()));
    }
}
