package org.andr36oid.wifitransfer;

/** Just enough JSON writing for the web page's API. */
final class Json {

    private Json() {
    }

    static String quote(String s) {
        if (s == null) return "null";
        final StringBuilder sb = new StringBuilder(s.length() + 2);
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
                    // Also escape < > & and the JS line separators, the output is safe anywhere
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028
                            || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
