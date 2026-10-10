package org.andr36oid.savebackup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for Syncthing's REST API. Plain Java (org.json is only on
 * the device), so the sync code runs in the host test too. Objects are LinkedHashMaps,
 * arrays are ArrayLists, numbers are Longs or Doubles.
 */
final class Json {

    private final String mText;
    private int mPos;

    private Json(String text) {
        mText = text;
    }

    static Object parse(String text) {
        final Json j = new Json(text);
        j.space();
        final Object v = j.value();
        j.space();
        if (j.mPos != text.length()) throw j.error("trailing text");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }

    static boolean bool(Object o) {
        return Boolean.TRUE.equals(o);
    }

    // ---- writing ---------------------------------------------------------------------

    static String write(Object o) {
        final StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            quote(sb, (String) o);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object v : (List<?>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
        } else {
            quote(sb, o.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---- reading ---------------------------------------------------------------------

    private Object value() {
        if (mPos >= mText.length()) throw error("unexpected end");
        final char c = mText.charAt(mPos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return word("true", Boolean.TRUE);
            case 'f': return word("false", Boolean.FALSE);
            case 'n': return word("null", null);
            default: return number();
        }
    }

    private Map<String, Object> object() {
        final Map<String, Object> m = new LinkedHashMap<>();
        mPos++;
        space();
        if (peek() == '}') {
            mPos++;
            return m;
        }
        while (true) {
            space();
            if (peek() != '"') throw error("expected a key");
            final String key = string();
            space();
            expect(':');
            space();
            m.put(key, value());
            space();
            final char c = next();
            if (c == '}') return m;
            if (c != ',') throw error("expected , or }");
        }
    }

    private List<Object> array() {
        final List<Object> l = new ArrayList<>();
        mPos++;
        space();
        if (peek() == ']') {
            mPos++;
            return l;
        }
        while (true) {
            space();
            l.add(value());
            space();
            final char c = next();
            if (c == ']') return l;
            if (c != ',') throw error("expected , or ]");
        }
    }

    private String string() {
        expect('"');
        final StringBuilder sb = new StringBuilder();
        while (true) {
            final char c = next();
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            final char e = next();
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    if (mPos + 4 > mText.length()) throw error("bad \\u");
                    sb.append((char) Integer.parseInt(mText.substring(mPos, mPos + 4), 16));
                    mPos += 4;
                    break;
                default: sb.append(e); break;
            }
        }
    }

    private Object number() {
        final int start = mPos;
        while (mPos < mText.length() && "+-0123456789.eE".indexOf(mText.charAt(mPos)) >= 0) {
            mPos++;
        }
        final String s = mText.substring(start, mPos);
        if (s.isEmpty()) throw error("unexpected character");
        try {
            if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
                return Long.parseLong(s);
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private Object word(String w, Object v) {
        if (!mText.startsWith(w, mPos)) throw error("unexpected word");
        mPos += w.length();
        return v;
    }

    private void space() {
        while (mPos < mText.length() && Character.isWhitespace(mText.charAt(mPos))) mPos++;
    }

    private char peek() {
        return mPos < mText.length() ? mText.charAt(mPos) : 0;
    }

    private char next() {
        if (mPos >= mText.length()) throw error("unexpected end");
        return mText.charAt(mPos++);
    }

    private void expect(char c) {
        if (next() != c) throw error("expected " + c);
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + mPos);
    }
}
