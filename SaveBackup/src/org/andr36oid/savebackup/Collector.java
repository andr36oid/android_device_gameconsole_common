package org.andr36oid.savebackup;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 */
final class Collector {

    /** Bigger files are left out; PSP save states are 20-40 MB. */
    static final long MAX_BYTES = 128L << 20;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILES = 20000;
    static final String TMP_SUFFIX = ".savebackup-tmp";

    private final Roots mRoots;
    private final Games mGames = new Games();
    private final Map<String, SaveFile> mFound = new TreeMap<>();

    Collector(Roots roots) {
        mRoots = roots;
    }

    List<SaveFile> collect() {
        for (File dir : knownFolders()) {
            walk(dir, 0, true, false);
        }
        walk(mRoots.media, 0, false, true);
        if (mRoots.easyroms != null) walk(mRoots.easyroms, 0, false, true);
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
        final File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            final String name = f.getName();
            if (name.startsWith(".") || name.indexOf('\n') >= 0 || name.indexOf('\t') >= 0) {
                continue;
            }
            if (f.isDirectory()) {
                if (top && depth == 0 && skipTop(name)) continue;
                if (skipAnywhere(name)) continue;
                // PSP/SAVEDATA and PSP/PPSSPP_STATE anywhere: take the whole folder
                final boolean psp = dir.getName().equalsIgnoreCase("PSP")
                        && (name.equalsIgnoreCase("SAVEDATA")
                            || name.equalsIgnoreCase("PPSSPP_STATE"));
                walk(f, depth + 1, all || psp, top);
            } else if (f.isFile()) {
                if (name.endsWith(TMP_SUFFIX)) continue;
                if (!all && !Games.isSaveName(name)) continue;
                add(f);
            }
        }
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
