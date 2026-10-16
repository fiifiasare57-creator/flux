package app.flux.protocol;

import java.util.*;

/** Minimal JSON (objects, arrays, strings, numbers, booleans, null) so the protocol has no dependencies. */
public final class Json {
    private Json() {}

    public static String write(Object o) { StringBuilder b = new StringBuilder(); w(b, o); return b.toString(); }

    @SuppressWarnings("unchecked")
    private static void w(StringBuilder b, Object o) {
        if (o == null) b.append("null");
        else if (o instanceof String) str(b, (String) o);
        else if (o instanceof Number || o instanceof Boolean) b.append(o);
        else if (o instanceof Map) {
            b.append('{'); boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) b.append(','); first = false; str(b, e.getKey()); b.append(':'); w(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof List) {
            b.append('['); boolean first = true;
            for (Object x : (List<Object>) o) { if (!first) b.append(','); first = false; w(b, x); }
            b.append(']');
        } else throw new IllegalArgumentException("unsupported json type " + o.getClass());
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }

    public static Object parse(String s) {
        Parser p = new Parser(s); Object v = p.value(); p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("trailing data");
        return v;
    }

    private static final class Parser {
        final String s; int i = 0; int depth = 0;
        Parser(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { if (i >= s.length()) throw new IllegalArgumentException("unexpected end"); return s.charAt(i); }
        Object value() {
            ws(); char c = peek();
            if (++depth > 32) throw new IllegalArgumentException("too deep");
            try {
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == '"') return string();
                if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
                if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
                if (s.startsWith("null", i)) { i += 4; return null; }
                return number();
            } finally { depth--; }
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>(); i++; ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws(); String k = string(); ws();
                if (peek() != ':') throw new IllegalArgumentException("expected ':'"); i++;
                m.put(k, value()); ws();
                char c = peek(); i++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected ',' or '}'");
            }
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>(); i++; ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value()); ws();
                char c = peek(); i++;
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected ',' or ']'");
            }
        }
        String string() {
            if (peek() != '"') throw new IllegalArgumentException("expected string"); i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = peek(); i++;
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = peek(); i++;
                switch (e) {
                    case 'n': b.append('\n'); break; case 'r': b.append('\r'); break; case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }
        Number number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("bad json at " + i);
            String t = s.substring(st, i);
            if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) return Double.parseDouble(t);
            return Long.parseLong(t);
        }
    }
}
