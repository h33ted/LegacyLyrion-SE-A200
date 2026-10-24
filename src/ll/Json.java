package ll;

import java.util.Hashtable;
import java.util.Vector;

/** Minimal JSON reader/writer for CLDC 1.1 (no java.util.regex, no org.json). Numbers stay Strings. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json j = new Json(text);
        return j.value();
    }

    private void ws() {
        while (i < s.length() && s.charAt(i) <= ' ') i++;
    }

    private Object value() {
        ws();
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '"') return str();
        if (c == 't') { i += 4; return "true"; }
        if (c == 'f') { i += 5; return "false"; }
        if (c == 'n') { i += 4; return null; }
        return num();
    }

    private Hashtable obj() {
        Hashtable h = new Hashtable();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return h; }
        while (i < s.length()) {
            ws();
            String k = str();
            ws();
            i++; // ':'
            Object v = value();
            if (v != null) h.put(k, v);
            ws();
            char c = s.charAt(i++);
            if (c == '}') break;
        }
        return h;
    }

    private Vector arr() {
        Vector v = new Vector();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return v; }
        while (i < s.length()) {
            Object o = value();
            v.addElement(o == null ? "" : o);
            ws();
            char c = s.charAt(i++);
            if (c == ']') break;
        }
        return v;
    }

    private String str() {
        StringBuffer b = new StringBuffer();
        i++; // opening quote
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private String num() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return s.substring(st, i);
    }

    // ------------------------------------------------------------------ helpers

    static String str(Object o, String k) {
        if (!(o instanceof Hashtable)) return null;
        Object v = ((Hashtable) o).get(k);
        if (v == null) return null;
        String sv = v.toString();
        return sv.length() == 0 ? null : sv;
    }

    static Hashtable obj(Object o, String k) {
        if (!(o instanceof Hashtable)) return null;
        Object v = ((Hashtable) o).get(k);
        return v instanceof Hashtable ? (Hashtable) v : null;
    }

    static Vector arr(Object o, String k) {
        if (!(o instanceof Hashtable)) return null;
        Object v = ((Hashtable) o).get(k);
        return v instanceof Vector ? (Vector) v : null;
    }

    static int toInt(String v, int def) {
        if (v == null) return def;
        try {
            int dot = v.indexOf('.');
            return Integer.parseInt(dot >= 0 ? v.substring(0, dot) : v);
        } catch (Exception e) {
            return def;
        }
    }

    /** JSON string literal; non-ASCII escaped so the request is plain ASCII. */
    static String quote(String v) {
        StringBuffer b = new StringBuffer(v.length() + 8);
        b.append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c == '"' || c == '\\') { b.append('\\').append(c); }
            else if (c < 0x20 || c > 0x7e) {
                String h = Integer.toHexString(c);
                b.append("\\u");
                for (int p = h.length(); p < 4; p++) b.append('0');
                b.append(h);
            } else b.append(c);
        }
        return b.append('"').toString();
    }
}
