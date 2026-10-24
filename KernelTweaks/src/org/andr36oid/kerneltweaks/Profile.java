package org.andr36oid.kerneltweaks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The shareable text format. Values always come from Tweaks, never from shell code. */
final class Profile {

    static final int MAX_BYTES = 65536;
    static final String[] IDENTITY = {"model", "compatible", "profile", "version", "panel"};
    private static final String PREFIX = "persist.andr36oid.kt.";

    final Map<String, String> identity = new LinkedHashMap<>();
    final Map<Tweak, String> values = new LinkedHashMap<>();
    final List<String> unknown = new ArrayList<>();
    final List<String> invalid = new ArrayList<>();
    String matchModel = "";
    String matchCompatible = "";

    static Profile parse(String text, List<Tweak> tweaks) throws IOException {
        if (text.length() > MAX_BYTES) throw new IOException("profile too large");
        final Profile out = new Profile();
        final Map<String, Tweak> known = new LinkedHashMap<>();
        for (Tweak t : tweaks) if (!t.once) known.put(t.key, t);
        final Set<String> seen = new HashSet<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            final int eq = line.indexOf('=');
            if (eq <= 0 || line.indexOf('\0') >= 0) throw new IOException("bad profile line");
            final String key = line.substring(0, eq).trim();
            final String value = line.substring(eq + 1).trim();
            if (!key.matches("[A-Za-z0-9_.-]+") || !seen.add(key)) {
                throw new IOException("bad or duplicate key");
            }
            if ("format".equals(key)) {
                if (!"andr36oid-ktprofile-1".equals(value)) {
                    throw new IOException("unknown profile format");
                }
            } else if (Arrays.asList(IDENTITY).contains(key)) {
                out.identity.put(key, value);
            } else if ("match.model".equals(key)) {
                out.matchModel = value;
            } else if ("match.compatible".equals(key)) {
                out.matchCompatible = value;
            } else {
                final Tweak t = key.startsWith(PREFIX) ? known.get(key.substring(PREFIX.length()))
                        : null;
                if (t == null) {
                    out.unknown.add(key);
                } else if (allowed(t, value)) {
                    out.values.put(t, normalize(t, value));
                } else {
                    out.invalid.add(key);
                }
            }
        }
        if (!seen.contains("format")) throw new IOException("missing profile format");
        return out;
    }

    private static boolean allowed(Tweak t, String value) {
        if (t.kind == Tweak.AXES) return value.matches("[01](\\s+[01]){3}");
        if (t.kind == Tweak.STICKS) return Tweaks.sticks(value) != null;
        return Arrays.asList(t.values).contains(value);
    }

    /** the canonical spelling of an allowed value, so profiles store it the same way */
    private static String normalize(Tweak t, String value) {
        if (t.kind == Tweak.AXES) return Tweaks.axesValue(Tweaks.axes(value));
        if (t.kind == Tweak.STICKS) {
            final int[] v = Tweaks.sticks(value);
            return Tweaks.sticksValue(v[0], v[1]);
        }
        return value;
    }

    /** Built-ins must name a model or a complete compatible token, or both. */
    boolean recommended(Map<String, String> console) {
        if (matchModel.isEmpty() && matchCompatible.isEmpty()) return false;
        if (!matchModel.isEmpty() && !matchModel.equals(console.get("model"))) return false;
        return matchCompatible.isEmpty() || Arrays.asList(
                console.getOrDefault("compatible", "").split("\\s+")).contains(matchCompatible);
    }

    List<String> differences(Map<String, String> console) {
        final List<String> out = new ArrayList<>();
        for (String key : IDENTITY) {
            final String before = console.getOrDefault(key, "");
            final String after = identity.getOrDefault(key, "");
            if (!before.equals(after)) out.add(key);
        }
        if ((!matchModel.isEmpty() || !matchCompatible.isEmpty()) && !recommended(console)) {
            out.add("match");
        }
        return out;
    }
}
