package app.flux.protocol;

/** Breadcrumbs: the app records the last step reached, so even a native crash (which leaves no Java error) can be located. */
public final class Trace {
    public interface Sink { void step(String s); }
    public static volatile Sink sink;
    private Trace() {}
    public static void step(String s) { Sink k = sink; if (k != null) { try { k.step(s); } catch (RuntimeException ignored) { } } }
}
