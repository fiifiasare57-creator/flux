package app.flux;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import javax.security.auth.x500.X500Principal;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;

/** This phone's FLUX identity: an EC P-256 key pair created inside the Android Keystore (the private key never leaves it). */
final class AndroidIdentity {
    private static final String ALIAS = "flux_identity_v2", OLD_ALIAS = "flux_identity";
    private AndroidIdentity() {}

    static KeyManager[] keyManagers() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(OLD_ALIAS)) ks.deleteEntry(OLD_ALIAS); // v1 key could not sign TLS handshakes
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256) // TLS stacks sign a pre-hashed value (NONEwithECDSA)
                    .setCertificateSubject(new X500Principal("CN=FLUX phone"))
                    .build());
            g.generateKeyPair();
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, null);
        return kmf.getKeyManagers();
    }
}
