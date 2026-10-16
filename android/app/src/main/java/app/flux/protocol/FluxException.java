package app.flux.protocol;

public class FluxException extends Exception {
    public enum Kind { REJECTED, CANCELLED, CANCELLED_BY_RECEIVER, REMOTE, NETWORK, SECURITY, PROTOCOL, SOURCE, PAIRING, INTEGRITY, STORAGE }
    public final Kind kind;
    public FluxException(Kind kind, String message) { super(message); this.kind = kind; }
    public FluxException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
}
