package org.andr36oid.gamecomfort.resume;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Finds the last game of RetroArch and of PPSSPP from their own files, and checks whether it
 * can still be started. Plain Java, so the whole search is tested on a PC with sample files.
 */
public final class Finder {

    private Finder() {
    }

    /** RetroArch's last game. */
    public static final class RaGame {
        public final RetroArch.Entry entry;
        /** When RetroArch last wrote its history, in seconds; it does when a game starts. */
        public final long time;
        public final Map<String, String> config;
        /** The config RetroArch uses, as an app path; its default when there is none yet. */
        public final String configPath;

        RaGame(RetroArch.Entry entry, long time, Map<String, String> config,
                String configPath) {
            this.entry = entry;
            this.time = time;
            this.config = config;
            this.configPath = configPath;
        }
    }

    /** PPSSPP's last game. */
    public static final class PpGame {
        /** As PPSSPP wrote it: a path or a content:// link. */
        public final String path;
        /** When PPSSPP last wrote its ini (it does on exit and on settings changes). */
        public final long time;

        PpGame(String path, long time) {
            this.path = path;
            this.time = time;
        }
    }

    /** A save state newer than the game's own save (see {@link #stateHint}). */
    public static final class StateHint {
        /** 0 for "Game.state", n for "Game.staten", -1 for "Game.state.auto". */
        public final int slot;
        public final long stateTime;
        /** 0 if there is no in-game save file. */
        public final long saveTime;

        StateHint(int slot, long stateTime, long saveTime) {
            this.slot = slot;
            this.stateTime = stateTime;
            this.saveTime = saveTime;
        }
    }

    /** RetroArch's newest game that has a core and a file, or null. */
    public static RaGame retroArch(FileAccess files) {
        final List<String> configs = RetroArch.configCandidates();
        final Map<String, FileAccess.Found> readConfigs = files.read(configs);
        Map<String, String> config = null;
        String configPath = RetroArch.FILES + "/retroarch.cfg";
        for (String c : configs) {
            final FileAccess.Found f = readConfigs.get(c);
            if (f != null) {
                config = RetroArch.parseConfig(f.text);
                configPath = Paths.toApp(c);
                break;
            }
        }
        final List<String> histories = RetroArch.historyCandidates(config);
        final List<FileAccess.Found> found = new ArrayList<>(files.read(histories).values());
        // Newest history first: RetroArch may have used another folder before
        Collections.sort(found, (a, b) -> Long.compare(b.mtime, a.mtime));
        for (FileAccess.Found f : found) {
            final RetroArch.Entry entry = RetroArch.newest(RetroArch.parseHistory(f.text));
            if (entry != null) {
                return new RaGame(entry, f.mtime, config, configPath);
            }
        }
        return null;
    }

    /** PPSSPP's newest recent game, or null. */
    public static PpGame ppsspp(FileAccess files) {
        final String memstick = Ppsspp.memstickFile();
        final FileAccess.Found txt = files.read(Collections.singletonList(memstick)).get(memstick);
        final List<String> inis = Ppsspp.iniCandidates(txt != null ? txt.text : null);
        final List<FileAccess.Found> found = new ArrayList<>(files.read(inis).values());
        Collections.sort(found, (a, b) -> Long.compare(b.mtime, a.mtime));
        for (FileAccess.Found f : found) {
            final List<String> recent = Ppsspp.parseRecent(f.text);
            if (!recent.isEmpty()) {
                return new PpGame(recent.get(0), f.mtime);
            }
        }
        return null;
    }

    /** Whether a file is there: TRUE, FALSE, or null when the helper can't look there. */
    public static Boolean exists(FileAccess files, String appPath) {
        final String root = Paths.toRoot(appPath);
        if (root == null) {
            return null;
        }
        return files.stat(Collections.singletonList(root)).containsKey(root);
    }

    /**
     * The newest save state of the game if it is newer than the game's own save (.srm), else
     * null. Loading an older state would take the in-game save back too, and RetroArch would
     * then write that older save over the newer .srm a few seconds later; so only a newer
     * state is worth pointing out. Also null when RetroArch loads the auto state by itself
     * (savestate_auto_load) and that is the newest.
     */
    public static StateHint stateHint(FileAccess files, RaGame game) {
        final List<String> names = RetroArch.saveNames(game.entry.path);
        final List<String> prefixes = new ArrayList<>();
        for (String n : names) {
            prefixes.add(n + ".");
        }
        final List<FileAccess.Found> found =
                files.list(RetroArch.saveFolders(game.config, game.entry.path), prefixes);
        long saveTime = 0;
        long stateTime = 0;
        int slot = Integer.MIN_VALUE;
        for (FileAccess.Found f : found) {
            final String name = Paths.name(f.path);
            for (String n : names) {
                if (!name.startsWith(n + ".")) {
                    continue;
                }
                final String end = name.substring(n.length() + 1);
                if (end.equals("srm")) {
                    saveTime = Math.max(saveTime, f.mtime);
                    continue;
                }
                final int s = stateSlot(end);
                if (s != Integer.MIN_VALUE && f.mtime > stateTime) {
                    stateTime = f.mtime;
                    slot = s;
                }
            }
        }
        if (slot == Integer.MIN_VALUE || stateTime <= saveTime) {
            return null;
        }
        if (slot == -1 && game.config != null
                && "true".equals(game.config.get("savestate_auto_load"))) {
            return null;
        }
        return new StateHint(slot, stateTime, saveTime);
    }

    /** "state" is 0, "state7" is 7, "state.auto" is -1, anything else MIN_VALUE. */
    static int stateSlot(String end) {
        if (end.equals("state")) {
            return 0;
        }
        if (end.equals("state.auto")) {
            return -1;
        }
        if (end.startsWith("state") && end.length() > 5 && end.length() <= 8) {
            try {
                final int n = Integer.parseInt(end.substring(5));
                return n >= 0 ? n : Integer.MIN_VALUE;
            } catch (NumberFormatException e) {
                return Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }
}
