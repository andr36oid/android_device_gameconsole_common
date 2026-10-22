package org.andr36oid.storagecare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which files count as what, and which of them are "old". Plain Java (no android.*), so
 * the root engine and the host test use the same rules.
 *
 * The one hard rule: battery saves (.srm, .sav and friends) are never picked, whatever
 * folder they are in. Only save STATES are thinned out, and the newest few of each game
 * are always kept, as is RetroArch's .state.auto (the "resume" point).
 */
final class Rules {

    private Rules() {
    }

    /** In-game saves. Never removed by anything in this app. */
    private static final String[] BATTERY_SAVES = {
        ".srm", ".sav", ".rtc", ".eep", ".sra", ".fla", ".mpk", ".mcd", ".mcr", ".dsv",
        ".ldci", ".sgm", ".nv", ".bsv"
    };

    // Disc files may be tracks or named by a cue sheet or multi-disc playlist. Removing
    // a matching file from another folder can break that game's references.
    private static final String[] DISC_FILES = {
        ".bin", ".img", ".sub", ".cue", ".gdi", ".ccd", ".mdf", ".mds", ".m3u",
        ".m3u8", ".iso", ".chd", ".cso", ".pbp"
    };

    /** RetroArch: Game.state, Game.state3, Game.state12, and Game.state3.png next to it. */
    private static final Pattern RA_STATE =
            Pattern.compile("(.+)\\.state(\\d*)(\\.png)?", Pattern.CASE_INSENSITIVE);
    /** PPSSPP: ULUS10041_1.00_0.ppst, ULUS10041_1.00_undo.ppst, and the .jpg next to it. */
    private static final Pattern PPSSPP_STATE =
            Pattern.compile("(.+)_(\\d+|undo)\\.(ppst|jpg)", Pattern.CASE_INSENSITIVE);
    /** Save backup's zips: saves-20260928-140233-17.zip (see SaveBackup's Archive). */
    static final Pattern BACKUP = Pattern.compile("saves-(\\d{8})-(\\d{6})-(\\d+)\\.zip");

    static boolean isBatterySave(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        for (String e : BATTERY_SAVES) {
            if (n.endsWith(e) && n.length() > e.length()) return true;
        }
        return false;
    }

    static boolean isApk(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        return n.length() > 4 && (n.endsWith(".apk") || n.endsWith(".apks")
                || n.endsWith(".xapk") || n.endsWith(".apkm"));
    }

    static boolean isPicture(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        return n.length() > 4 && (n.endsWith(".png") || n.endsWith(".jpg")
                || n.endsWith(".jpeg") || n.endsWith(".webp") || n.endsWith(".bmp"));
    }

    /**
     * A save state or its picture: game + slot. Null for anything else, and for .state.auto.
     * PPSSPP names (GAMEID_1.00_0.ppst) only count in a PPSSPP_STATE folder, so a photo
     * called IMG_0001.jpg is never taken for a state picture.
     */
    static State state(String name, boolean ppssppFolder) {
        if (isBatterySave(name)) return null;
        Matcher m = RA_STATE.matcher(name);
        if (m.matches() && !m.group(1).isEmpty()) {
            return new State(m.group(1), "ra" + m.group(2), m.group(3) != null);
        }
        if (!ppssppFolder) return null;
        m = PPSSPP_STATE.matcher(name);
        if (m.matches() && !m.group(1).isEmpty()) {
            return new State(m.group(1), "pp" + m.group(2).toLowerCase(Locale.ROOT),
                    m.group(3).equalsIgnoreCase("jpg"));
        }
        return null;
    }

    /** What state() found in a file name. */
    static final class State {
        final String game;
        final String slot;
        final boolean picture;

        State(String game, String slot, boolean picture) {
            this.game = game;
            this.slot = slot;
            this.picture = picture;
        }
    }

    /** A file as the rules see it: its name and its time. */
    static final class Entry {
        final String name;
        final long mtime;

        Entry(String name, long mtime) {
            this.name = name;
            this.mtime = mtime;
        }
    }

    /**
     * The files of one folder that are old save states: for each game, all but the newest
     * {@code keep} slots (by the state file's time), with their pictures. A picture whose
     * state is gone is old too. {@code keep} is at least 1.
     */
    static boolean isPpssppStateFolder(String folderName) {
        return "PPSSPP_STATE".equalsIgnoreCase(folderName);
    }

    static Set<String> oldStates(List<Entry> files, int keep, boolean ppssppFolder) {
        keep = Math.max(1, keep);
        // game -> slot -> time of the state file
        final Map<String, Map<String, Long>> games = new HashMap<>();
        for (Entry e : files) {
            final State s = state(e.name, ppssppFolder);
            if (s == null || s.picture) continue;
            games.computeIfAbsent(s.game, k -> new HashMap<>()).put(s.slot, e.mtime);
        }
        final Set<String> oldSlots = new HashSet<>();
        for (Map.Entry<String, Map<String, Long>> g : games.entrySet()) {
            final List<Map.Entry<String, Long>> slots = new ArrayList<>(g.getValue().entrySet());
            Collections.sort(slots, (a, b) -> {
                final int c = Long.compare(b.getValue(), a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            });
            for (int i = keep; i < slots.size(); i++) {
                oldSlots.add(g.getKey() + "\n" + slots.get(i).getKey());
            }
        }
        final Set<String> old = new HashSet<>();
        for (Entry e : files) {
            final State s = state(e.name, ppssppFolder);
            if (s == null) continue;
            final String key = s.game + "\n" + s.slot;
            if (oldSlots.contains(key)) {
                old.add(e.name);
            } else if (s.picture && !hasSlot(games, s)) {
                old.add(e.name);
            }
        }
        return old;
    }

    private static boolean hasSlot(Map<String, Map<String, Long>> games, State s) {
        final Map<String, Long> slots = games.get(s.game);
        return slots != null && slots.containsKey(s.slot);
    }

    /**
     * Save backup zips beyond the newest {@code keep} of one folder, by their counter (not
     * the date, which is wrong after the clock reset). Other files are never picked.
     */
    static Set<String> oldBackups(List<String> names, int keep) {
        keep = Math.max(1, keep);
        final List<String> zips = new ArrayList<>();
        for (String n : names) {
            if (BACKUP.matcher(n).matches()) zips.add(n);
        }
        Collections.sort(zips, (a, b) -> Long.compare(seq(b), seq(a)));
        final Set<String> old = new HashSet<>();
        for (int i = keep; i < zips.size(); i++) old.add(zips.get(i));
        return old;
    }

    static long seq(String backupName) {
        final Matcher m = BACKUP.matcher(backupName);
        if (!m.matches()) return -1;
        try {
            return Long.parseLong(m.group(3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Could this file be a ROM for the duplicate finder? Saves, states and pictures aren't. */
    static boolean romCandidate(String name, long size) {
        if (size < Dupes.MIN_BYTES || name.startsWith(".")) return false;
        if (isBatterySave(name) || state(name, true) != null || isPicture(name) || isApk(name)) {
            return false;
        }
        final String n = name.toLowerCase(Locale.ROOT);
        for (String e : DISC_FILES) {
            if (n.endsWith(e)) return false;
        }
        return !n.endsWith(".state.auto") && !n.endsWith(".txt") && !n.endsWith(".xml")
                && !n.endsWith(".pdf") && !n.endsWith(".zip.tmp");
    }
}
