package org.andr36oid.savebackup;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the save files:
 * <ul>
 * <li>everything in the emulators' own save folders: RetroArch's saves and states folders
 *     (from retroarch.cfg, else its defaults) and PPSSPP's PSP/SAVEDATA and
 *     PSP/PPSSPP_STATE (from memstick_dir.txt, else its defaults);</li>
 * <li>anything that looks like a save (.srm, .sav, .state1, …) elsewhere on the internal
 *     storage and on EASYROMS, which covers saves kept next to the ROMs.</li>
 * </ul>
 * Backup folders, hidden folders and Android/ (other than the emulators' folders in it)
 * are skipped.
 *
 * <p>The walk of the internal storage and EASYROMS remembers, per folder, its time and the
 * save files and folders in it (DIR/walk.cache). A quick walk (the after-game backup) lists
 * a folder again only when its time changed, so a card with 20000 ROMs costs one stat per
 * folder instead of two per file. Adding, removing or renaming a file changes the folder's
 * time; a save written in place is still seen, because every remembered save is looked at
 * again. A PC may not change the folder time, so a full walk (every other backup, and any
 * backup when the last full walk is over a day old) lists everything like before.
 */
final class Collector {

    /** Bigger files are left out; PSP save states are 20-40 MB. */
    static final long MAX_BYTES = 128L << 20;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILES = 20000;
    static final String TMP_SUFFIX = ".savebackup-tmp";
    /** A quick walk trusts the folder list for this long after the last full walk. */
    static final long CACHE_MAX_AGE = 24L * 3600 * 1000;
    /** Folders changed this close to a walk are listed again next time (FAT keeps 2 s). */
    static final long CACHE_SLACK = 5000;
    private static final String CACHE_VERSION = "savebackup-walk 1";

    private final Roots mRoots;
    private final Games mGames = new Games();
    private final Map<String, SaveFile> mFound = new TreeMap<>();
    /** Folder lists from the last walk (key: flag + path), used by a quick walk. */
    private final Map<String, String> mOld = new HashMap<>();
    /** Folder lists of this walk, written to the cache file. */
    private final Map<String, String> mNew = new TreeMap<>();
    private File mCache;
    private boolean mQuick;
    private long mStart;
    private long mFullWalk;
    /** Folders of the internal storage and EASYROMS listed from the disk in this walk. */
    int listed;

    Collector(Roots roots) {
        mRoots = roots;
    }

    /**
     * Remember folder lists in this file. With quick set, trust the lists of folders whose
     * time didn't change, if the last full walk is less than a day old.
     */
    Collector cache(File file, boolean quick) {
        mCache = file;
        mQuick = quick;
        return this;
    }

    List<SaveFile> collect() {
        mStart = System.currentTimeMillis();
        mFullWalk = mStart;
        if (mCache != null && mQuick) readCache();
        for (File dir : knownFolders()) {
            walk(dir, 0, true, false);
        }
        walk(mRoots.media, 0, false, true);
        if (mRoots.easyroms != null) walk(mRoots.easyroms, 0, false, true);
        if (mCache != null) writeCache();
        final List<SaveFile> list = new ArrayList<>(mFound.values());
        for (SaveFile s : list) mGames.classify(s);
        return list;
    }

    /** The emulators' own save folders that exist, on the internal storage or EASYROMS. */
    List<File> knownFolders() {
        final Set<File> dirs = new LinkedHashSet<>();
        final File media = mRoots.media;
        final File raFiles = new File(media, "Android/data/com.retroarch/files");

        // RetroArch: what retroarch.cfg says, and its defaults
        for (File cfg : new File[] {new File(raFiles, "retroarch.cfg"),
                new File(mRoots.raData, "retroarch.cfg")}) {
            for (String key : new String[] {"savefile_directory", "savestate_directory"}) {
                final String v = cfgValue(cfg, key);
                if (v == null || v.isEmpty() || v.equals("default") || v.startsWith(":")) {
                    continue;
                }
                final File d = mRoots.lower(v);
                if (d != null) dirs.add(d);
            }
        }
        dirs.add(new File(media, "RetroArch/saves"));
        dirs.add(new File(media, "RetroArch/states"));
        dirs.add(new File(raFiles, "saves"));
        dirs.add(new File(raFiles, "states"));

        // PPSSPP: the memory stick folder holds PSP/SAVEDATA and PSP/PPSSPP_STATE
        final List<File> sticks = new ArrayList<>();
        final String chosen = firstLine(new File(mRoots.ppssppData, "files/memstick_dir.txt"));
        final File c = chosen != null ? mRoots.lower(chosen) : null;
        if (c != null) sticks.add(c);
        sticks.add(media);
        sticks.add(new File(media, "Android/data/org.ppsspp.ppsspp/files"));
        for (File stick : sticks) {
            dirs.add(new File(stick, "PSP/SAVEDATA"));
            dirs.add(new File(stick, "PSP/PPSSPP_STATE"));
        }

        final List<File> found = new ArrayList<>();
        for (File d : dirs) {
            if (d.isDirectory() && mRoots.idOf(new File(d, "x")) != null) found.add(d);
        }
        return found;
    }

    /**
     * @param all   take every file (an emulator's save folder), not just save names
     * @param top   the top of the internal storage or EASYROMS, where some folders are skipped
     */
    private void walk(File dir, int depth, boolean all, boolean top) {
        if (depth > MAX_DEPTH || mFound.size() >= MAX_FILES) return;
        // only the walks of the internal storage and EASYROMS use the cache
        final String key = top ? (all ? "A" : "S") + dir.getPath() : null;
        final List<String> entries = key != null ? cached(key, dir) : null;
        if (entries != null) {
            for (String e : entries) {
                final File f = new File(dir, e.substring(1));
                if (e.charAt(0) == 'd') {
                    walk(f, depth + 1, all || psp(dir, f.getName()), top);
                } else if (f.isFile()) {
                    add(f);
                }
            }
            return;
        }
        final long mtime = key != null ? dir.lastModified() : 0;
        final File[] children = dir.listFiles();
        if (children == null) return;
        if (key != null) listed++;
        final StringBuilder list = new StringBuilder();
        for (File f : children) {
            final String name = f.getName();
            if (name.startsWith(".") || name.indexOf('\n') >= 0 || name.indexOf('\t') >= 0) {
                continue;
            }
            if (f.isDirectory()) {
                if (top && depth == 0 && skipTop(name)) continue;
                if (skipAnywhere(name)) continue;
                list.append("\td").append(name);
                walk(f, depth + 1, all || psp(dir, name), top);
            } else if (f.isFile()) {
                if (name.endsWith(TMP_SUFFIX)) continue;
                if (!all && !Games.isSaveName(name)) continue;
                list.append("\tf").append(name);
                add(f);
            }
        }
        // a folder changed just now may change again within the file system's time step
        if (key != null && mtime > 0 && mtime < mStart - CACHE_SLACK) {
            mNew.put(key, mtime + list.toString());
        }
    }

    /** PSP/SAVEDATA and PSP/PPSSPP_STATE anywhere: take the whole folder. */
    private static boolean psp(File dir, String name) {
        return dir.getName().equalsIgnoreCase("PSP")
                && (name.equalsIgnoreCase("SAVEDATA") || name.equalsIgnoreCase("PPSSPP_STATE"));
    }

    /** The remembered list of a folder whose time didn't change, or null to list it. */
    private List<String> cached(String key, File dir) {
        final String line = mOld.get(key);
        if (line == null) return null;
        final String[] parts = line.split("\t", -1);
        final long mtime = dir.lastModified();
        if (mtime <= 0 || !parts[0].equals(Long.toString(mtime))) return null;
        final List<String> entries = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].length() > 1) entries.add(parts[i]);
        }
        mNew.put(key, line);
        return entries;
    }

    /**
     * Cache file: a header "savebackup-walk 1 FULLWALKTIME", then one line per folder:
     * key TAB time {TAB d|f name}. Names with a tab or a line break are never walked.
     */
    private void readCache() {
        final String text = Engine.read(mCache);
        if (text == null) return;
        final String[] lines = text.split("\n");
        if (!lines[0].startsWith(CACHE_VERSION + " ")) return;
        final long full = Index.num(lines[0].substring(CACHE_VERSION.length() + 1));
        // too old, or the clock went back: walk everything
        if (full <= 0 || mStart < full || mStart - full > CACHE_MAX_AGE) return;
        mFullWalk = full;
        for (int i = 1; i < lines.length; i++) {
            final int tab = lines[i].indexOf('\t');
            if (tab > 0) mOld.put(lines[i].substring(0, tab), lines[i].substring(tab + 1));
        }
    }

    private void writeCache() {
        final StringBuilder out = new StringBuilder(CACHE_VERSION).append(' ')
                .append(mFullWalk).append('\n');
        for (Map.Entry<String, String> e : mNew.entrySet()) {
            out.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        final File tmp = new File(mCache.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(mCache)) tmp.delete();
    }

    private static boolean skipTop(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        // Android/ is other apps' data (the emulators' folders in it are known folders),
        // and our own backups must not end up inside the next backup
        return n.equals("android") || n.equals("backups");
    }

    private static boolean skipAnywhere(String name) {
        return name.equals("System Volume Information") || name.equals("LOST.DIR")
                || name.equals("$RECYCLE.BIN") || name.equals("lost+found");
    }

    private void add(File f) {
        final String id = mRoots.idOf(f);
        if (id == null || mFound.containsKey(id)) return;
        final long size = f.length();
        if (size > MAX_BYTES) return;
        mFound.put(id, new SaveFile(id, f, size, f.lastModified()));
    }

    /** key = "value" from a RetroArch config file, the last one wins like in RetroArch. */
    static String cfgValue(File cfg, String key) {
        if (!cfg.isFile()) return null;
        String value = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(cfg), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith(key)) continue;
                final String rest = line.substring(key.length()).trim();
                if (!rest.startsWith("=")) continue;
                String v = rest.substring(1).trim();
                if (v.startsWith("\"")) {
                    final int end = v.indexOf('"', 1);
                    v = end > 0 ? v.substring(1, end) : v.substring(1);
                }
                value = v;
            }
        } catch (IOException e) {
            return null;
        }
        return value;
    }

    private static String firstLine(File f) {
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            final String line = r.readLine();
            return line != null && !line.trim().isEmpty() ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
