package app.flux.protocol;

import java.io.*;
import java.util.Map;

/** FLUX wire framing: kind:u8 | len:u32 big-endian | payload. Lengths are validated before allocating. */
final class Frames {
    static final int KIND_CONTROL = 1, KIND_DATA = 2, MAX_CONTROL = 1 << 20, MAX_DATA = 1 << 20, CHUNK = 256 * 1024;

    static void writeMsg(DataOutputStream out, Map<String, Object> msg) throws IOException {
        byte[] body = Json.write(msg).getBytes("UTF-8");
        if (body.length > MAX_CONTROL) throw new IOException("control message too large");
        out.writeByte(KIND_CONTROL); out.writeInt(body.length); out.write(body); out.flush();
    }

    static void writeData(DataOutputStream out, byte[] buf, int len) throws IOException {
        out.writeByte(KIND_DATA); out.writeInt(len); out.write(buf, 0, len); // flushed by the stream's own buffering policy
    }

    /** One frame from the peer: either a control message or raw file bytes. */
    static final class Frame {
        final Map<String, Object> msg; final byte[] data;
        Frame(Map<String, Object> m, byte[] d) { msg = m; data = d; }
    }

    @SuppressWarnings("unchecked")
    static Frame readFrame(DataInputStream in) throws IOException, FluxException {
        int kind = in.readUnsignedByte();
        long len = in.readInt() & 0xFFFFFFFFL;
        if (kind == KIND_DATA) {
            if (len > MAX_DATA) throw new FluxException(FluxException.Kind.PROTOCOL, "data frame too large");
            byte[] b = new byte[(int) len]; in.readFully(b); return new Frame(null, b);
        }
        if (kind != KIND_CONTROL) throw new FluxException(FluxException.Kind.PROTOCOL, "unknown frame kind " + kind);
        if (len > MAX_CONTROL) throw new FluxException(FluxException.Kind.PROTOCOL, "control frame too large");
        byte[] b = new byte[(int) len]; in.readFully(b);
        try { return new Frame((Map<String, Object>) Json.parse(new String(b, "UTF-8")), null); }
        catch (RuntimeException e) { throw new FluxException(FluxException.Kind.PROTOCOL, "bad message from peer", e); }
    }

    /** Returns a Map for control frames; data frames from the peer are a protocol violation on the sender side. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readMsg(DataInputStream in) throws IOException, FluxException {
        int kind = in.readUnsignedByte();
        long len = in.readInt() & 0xFFFFFFFFL;
        if (kind != KIND_CONTROL) throw new FluxException(FluxException.Kind.PROTOCOL, "unexpected frame kind " + kind);
        if (len > MAX_CONTROL) throw new FluxException(FluxException.Kind.PROTOCOL, "control frame too large");
        byte[] b = new byte[(int) len]; in.readFully(b);
        try { return (Map<String, Object>) Json.parse(new String(b, "UTF-8")); }
        catch (RuntimeException e) { throw new FluxException(FluxException.Kind.PROTOCOL, "bad message from peer", e); }
    }
}
