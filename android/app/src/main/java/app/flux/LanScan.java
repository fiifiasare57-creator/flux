package app.flux;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Finds computers on this Wi-Fi that are listening on FLUX's port (a plain TCP probe of the local /24). */
final class LanScan {
    static final int PORT = 47800;
    private LanScan() {}

    static List<String> find(Context ctx) {
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp == null) return found;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (!(la.getAddress() instanceof Inet4Address) || la.getAddress().isLoopbackAddress()) continue;
                byte[] a = la.getAddress().getAddress();
                final String prefix = (a[0] & 255) + "." + (a[1] & 255) + "." + (a[2] & 255) + ".";
                final int self = a[3] & 255;
                ExecutorService pool = Executors.newFixedThreadPool(48);
                for (int i = 1; i < 255; i++) {
                    if (i == self) continue;
                    final String host = prefix + i;
                    pool.execute(() -> {
                        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, PORT), 450); found.add(host + ":" + PORT); }
                        catch (Exception ignored) { }
                    });
                }
                pool.shutdown();
                pool.awaitTermination(15, TimeUnit.SECONDS);
            }
        } catch (Throwable ignored) { }
        return new ArrayList<>(found);
    }
}
