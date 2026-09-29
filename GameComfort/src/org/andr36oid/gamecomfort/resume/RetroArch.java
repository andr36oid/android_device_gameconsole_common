package org.andr36oid.gamecomfort.resume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What RetroArch 1.22 leaves on disk about the last game: its content history
 * (content_history.lpl, newest game first) and its config (retroarch.cfg), and where both are.
 */
public final class RetroArch {

    public static final String PACKAGE = "com.retroarch";
    /** The activity that runs a game; Daijishou starts this one with the game in extras. */
    public static final String GAME_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture";
    /** RetroArch's private folder (DATADIR): cores, and the history when nothing else works */
    public static final String DATA = "/data/user/0/com.retroarch";
    /** Its folder on the internal storage, where its config lives */
    public static final String FILES = Paths.STORAGE + "/Android/data/com.retroarch/files";

    static final String HISTORY = "content_history.lpl";

    private RetroArch() {
    }

    /** One line of the history: the game file and the core that ran it. */
    public static final class Entry {
        public final String path;
        public final String label;
        public final String corePath;
        public final String coreName;

        Entry(String path, String label, String corePath, String coreName) {
            this.path = path;
            this.label = label;
            this.corePath = corePath;
            this.coreName = coreName;
        }

        /** Has what a restart needs: a game file and a real core (not "DETECT"). */
        public boolean isLaunchable() {
            return !isEmpty(path) && !isEmpty(corePath) && corePath.endsWith(".so");
        }

        @Override
        public String toString() {
            return path + " | " + label + " | " + corePath + " | " + coreName;
        }
    }

    /**
     * The history's entries, newest first, in either format RetroArch writes: JSON (1.7.6 and
     * later, the default) or the old six lines per game. Empty for a compressed history
     * (playlist_compression, off by default) or anything unreadable.
     */
    public static List<Entry> parseHistory(String text) {
        final List<Entry> entries = new ArrayList<>();
        if (text == null) {
            return entries;
        }
        final String trimmed = text.trim();
        if (trimmed.startsWith("#RZIP")) {
            return entries;
        }
        if (trimmed.startsWith("{")) {
            final Object root;
            try {
                root = Json.parse(trimmed);
            } catch (IllegalArgumentException e) {
                return entries;
            }
            if (!(root instanceof Map)) {
                return entries;
            }
            final Object items = ((Map<?, ?>) root).get("items");
            if (!(items instanceof List)) {
                return entries;
            }
            for (Object item : (List<?>) items) {
                if (!(item instanceof Map)) {
                    continue;
                }
                final Map<?, ?> m = (Map<?, ?>) item;
                final String path = str(m.get("path"));
                if (isEmpty(path)) {
                    continue;
                }
                entries.add(new Entry(path, str(m.get("label")), str(m.get("core_path")),
                        str(m.get("core_name"))));
            }
            return entries;
        }
        // Old format: path, label, core path, core name, crc32, db name; then settings lines
        final String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i + 5 < lines.length; i += 6) {
            if (lines[i].contains(" = \"")) {
                break;
            }
            if (!lines[i].isEmpty()) {
                entries.add(new Entry(lines[i], lines[i + 1], lines[i + 2], lines[i + 3]));
            }
        }
        return entries;
    }

    /** The first entry a restart can use, or null. */
    public static Entry newest(List<Entry> entries) {
        for (Entry e : entries) {
            if (e.isLaunchable()) {
                return e;
            }
        }
        return null;
    }

    /** retroarch.cfg as key/value pairs ({@code key = "value"} lines). */
    public static Map<String, String> parseConfig(String text) {
        final Map<String, String> map = new LinkedHashMap<>();
        if (text == null) {
            return map;
        }
        for (String line : text.split("\r?\n")) {
            final String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            final int eq = l.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String key = l.substring(0, eq).trim();
            String value = l.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            map.put(key, value);
        }
        return map;
    }

    /** Where RetroArch's config may be, as root paths, the one it uses first. */
    public static List<String> configCandidates() {
        final List<String> list = new ArrayList<>();
        list.add(Paths.toRoot(FILES + "/retroarch.cfg"));
        list.add(DATA + "/retroarch.cfg");
        return list;
    }

    /**
     * Where the history may be, as root paths. RetroArch keeps it in
     * {@code <playlist_directory>/builtin/}, unless content_history_directory says otherwise.
     * On Android the playlist folder is Internal storage/RetroArch/playlists when RetroArch may
     * write there (the F-Droid build may), else in its Android/data or private folder.
     */
    public static List<String> historyCandidates(Map<String, String> config) {
        final List<String> list = new ArrayList<>();
        if (config != null) {
            final String dir = dir(config.get("content_history_directory"));
            if (dir != null) {
                add(list, Paths.toRoot(dir + "/" + HISTORY));
            }
            final String playlists = dir(config.get("playlist_directory"));
            if (playlists != null) {
                add(list, Paths.toRoot(playlists + "/builtin/" + HISTORY));
            }
        }
        add(list, Paths.toRoot(Paths.STORAGE + "/RetroArch/playlists/builtin/" + HISTORY));
        add(list, Paths.toRoot(FILES + "/playlists/builtin/" + HISTORY));
        add(list, DATA + "/playlists/builtin/" + HISTORY);
        // Before 1.9 the history sat right in the app folder
        add(list, DATA + "/" + HISTORY);
        return list;
    }

    /**
     * Folders where the game's save states and in-game saves may be, as root paths: next to
     * the game, or in the save/state folder from the config (RetroArch may sort them into a
     * subfolder per core or per game folder, the helper also looks one level down), or its
     * defaults.
     */
    public static List<String> saveFolders(Map<String, String> config, String gamePath) {
        final List<String> list = new ArrayList<>();
        final String game = Paths.toRoot(stripArchive(gamePath));
        final String gameDir = game != null ? Paths.parent(game) : null;
        if (gameDir != null) {
            add(list, gameDir);
        }
        for (String key : new String[] {"savestate_directory", "savefile_directory"}) {
            final String dir = config != null ? dir(config.get(key)) : null;
            if (dir != null) {
                add(list, Paths.toRoot(dir));
            }
        }
        add(list, Paths.toRoot(Paths.STORAGE + "/RetroArch/states"));
        add(list, Paths.toRoot(Paths.STORAGE + "/RetroArch/saves"));
        add(list, Paths.toRoot(FILES + "/states"));
        add(list, Paths.toRoot(FILES + "/saves"));
        return list;
    }

    /**
     * The names RetroArch may give the game's saves, without ending: the game file's name,
     * and for a game in an archive ("Game.zip#Game.gba") also the archive's name.
     */
    public static List<String> saveNames(String gamePath) {
        final List<String> names = new ArrayList<>();
        final int hash = gamePath.indexOf('#');
        if (hash > 0) {
            add(names, Paths.withoutExtension(Paths.name(gamePath.substring(hash + 1))));
            add(names, Paths.withoutExtension(Paths.name(gamePath.substring(0, hash))));
        } else {
            add(names, Paths.withoutExtension(Paths.name(gamePath)));
        }
        return names;
    }

    /** The game file itself, without the entry inside an archive. */
    public static String stripArchive(String gamePath) {
        final int hash = gamePath.indexOf('#');
        return hash > 0 ? gamePath.substring(0, hash) : gamePath;
    }

    /** A config folder value, or null for unset ("", "default") or relative ones. */
    private static String dir(String value) {
        if (value == null) {
            return null;
        }
        final String v = value.trim();
        if (v.isEmpty() || v.equals("default") || !v.startsWith("/")) {
            return null;
        }
        return v;
    }

    private static void add(List<String> list, String s) {
        if (s != null && !s.isEmpty() && !list.contains(s)) {
            list.add(s);
        }
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
