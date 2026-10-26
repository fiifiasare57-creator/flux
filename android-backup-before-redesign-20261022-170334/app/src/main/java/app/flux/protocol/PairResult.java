package app.flux.protocol;

public final class PairResult {
    public final String name, fingerprint, addr;
    public PairResult(String name, String fingerprint, String addr) { this.name = name; this.fingerprint = fingerprint; this.addr = addr; }
}
