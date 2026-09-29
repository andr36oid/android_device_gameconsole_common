package org.andr36oid.gamecomfort.resume;

import java.util.LinkedHashMap;
import java.util.Map;

/** The game "Continue" starts: a RetroArch or PPSSPP game file, or an Android game app. */
public final class Target {

    public static final String RETROARCH = "retroarch";
    public static final String PPSSPP = "ppsspp";
    public static final String APP = "app";

    public final String kind;
    public final String pkg;
    /** What the user sees: the game's name, or the app's name for an app. */
    public final String label;
    /** The game file as the emulator names it, "" for an app. */
    public final String game;
    /** RetroArch only: the core file and its name. */
    public final String core;
    public final String coreName;
    /** RetroArch only: the config it uses (CONFIGFILE). */
    public final String config;

    public Target(String kind, String pkg, String label, String game, String core,
            String coreName, String config) {
        this.kind = kind;
        this.pkg = pkg;
        this.label = label != null ? label : "";
        this.game = game != null ? game : "";
        this.core = core != null ? core : "";
        this.coreName = coreName != null ? coreName : "";
        this.config = config != null ? config : "";
    }

    /**
     * What to continue. {@code lastPkg} is the game app that was last in front (the app keeps
     * track of it); when it is RetroArch or PPSSPP, their own files say which game. With no
     * app known yet, the emulator that saved its last game most recently wins. Null for
     * nothing to continue.
     *
     * @param appLabel the name of {@code lastPkg} as installed, or null if it is gone
     */
    public static Target choose(String lastPkg, String appLabel, Finder.RaGame ra,
            Finder.PpGame pp) {
        if (lastPkg == null) {
            if (ra != null && (pp == null || ra.time >= pp.time)) {
                return fromRetroArch(ra);
            }
            return pp != null ? fromPpsspp(pp) : null;
        }
        if (appLabel == null) {
            // Uninstalled since
            return null;
        }
        if (lastPkg.equals(RetroArch.PACKAGE) && ra != null) {
            return fromRetroArch(ra);
        }
        if (lastPkg.equals(Ppsspp.PACKAGE) && pp != null) {
            return fromPpsspp(pp);
        }
        return new Target(APP, lastPkg, appLabel, null, null, null, null);
    }

    static Target fromRetroArch(Finder.RaGame ra) {
        final RetroArch.Entry e = ra.entry;
        final String label = !RetroArch.isEmpty(e.label) && !e.label.equals(e.path)
                ? e.label : Paths.withoutExtension(Paths.name(e.path));
        return new Target(RETROARCH, RetroArch.PACKAGE, displayName(label), e.path, e.corePath,
                e.coreName, ra.configPath);
    }

    static Target fromPpsspp(Finder.PpGame pp) {
        return new Target(PPSSPP, Ppsspp.PACKAGE,
                displayName(Paths.withoutExtension(Paths.name(pp.path))), pp.path, null, null, null);
    }

    /**
     * A game name without the No-Intro/Redump tags: "Pokemon - Emerald Version (USA, Europe)
     * [!]" is "Pokemon - Emerald Version". Kept as it is if nothing would be left.
     */
    public static String displayName(String name) {
        final StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        final String s = sb.toString().replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? name.trim() : s;
    }

    /** One line per field, for SharedPreferences. */
    public String serialize() {
        final StringBuilder sb = new StringBuilder();
        line(sb, "kind", kind);
        line(sb, "pkg", pkg);
        line(sb, "label", label);
        line(sb, "game", game);
        line(sb, "core", core);
        line(sb, "coreName", coreName);
        line(sb, "config", config);
        return sb.toString();
    }

    /** The target {@link #serialize} wrote, or null. */
    public static Target parse(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        final Map<String, String> m = new LinkedHashMap<>();
        for (String line : s.split("\n")) {
            final int eq = line.indexOf('=');
            if (eq > 0) {
                m.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        final String kind = m.get("kind");
        final String pkg = m.get("pkg");
        if (kind == null || pkg == null || pkg.isEmpty()) {
            return null;
        }
        if (!kind.equals(RETROARCH) && !kind.equals(PPSSPP) && !kind.equals(APP)) {
            return null;
        }
        return new Target(kind, pkg, m.get("label"), m.get("game"), m.get("core"),
                m.get("coreName"), m.get("config"));
    }

    private static void line(StringBuilder sb, String key, String value) {
        // File names can't hold a newline on Android's file systems; drop one just in case
        sb.append(key).append('=').append(value.replace('\n', ' ')).append('\n');
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Target && serialize().equals(((Target) o).serialize());
    }

    @Override
    public int hashCode() {
        return serialize().hashCode();
    }

    @Override
    public String toString() {
        return serialize().replace('\n', ' ');
    }
}
