package org.andr36oid.storagecare;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The root half of Settings > Storage > Free up space. Plain Java (no android.*): the
 * andr36oid-storagecare init service runs it as root with app_process from this APK, and
 * the host test runs it with java on a fake card.
 *
 *   Engine --dir D --media /data/media/0 [--easyroms /mnt/media_rw/UUID] [--ra-data DIR]
 *
 * D/request holds "action=scan" or "action=delete", plus keep_states=N, keep_backups=N and
 * dups=0|1. A scan writes D/scan (see Scan). A delete reads D/delete, one
 * "GROUP<tab>PATH[<tab>KEEP]" per line, checks every line again against the same rules
 * the scan used (nothing is taken on trust from the file), then removes what passed and
 * writes D/result. Progress and the end go to D/status: "running scan 1200",
 * "running delete 3 40", "done …" or "error CODE".
 */
public final class Engine {

    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILES = 300000;
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "System Volume Information", "LOST.DIR", "$RECYCLE.BIN", "lost+found"));

    private final File mDir;
    private final File mMedia;
    private final File mEasyroms;
    private final File mRaData;
    private final Map<String, String> mRequest;
    private int mSeen;
    private long mLastStatus;

    Engine(File dir, File media, File easyroms, File raData, Map<String, String> request) {
        mDir = dir;
        mMedia = canonical(media);
        mEasyroms = easyroms != null && easyroms.isDirectory() ? canonical(easyroms) : null;
        mRaData = raData;
        mRequest = request;
    }

    public static void main(String[] args) {
        File dir = null, media = null, easyroms = null, raData = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            final File f = new File(args[i + 1]);
            switch (args[i]) {
                case "--dir": dir = f; break;
                case "--media": media = f; break;
                case "--easyroms": easyroms = f; break;
                case "--ra-data": raData = f; break;
                default: break;
            }
        }
        if (dir == null || media == null) {
            System.err.println("usage: Engine --dir D --media M [--easyroms E] [--ra-data R]");
            System.exit(2);
        }
        final Engine e = new Engine(dir, media, easyroms, raData,
                keyValues(read(new File(dir, "request"))));
        try {
            final String action = e.mRequest.get("action");
            if ("scan".equals(action)) {
                e.scan();
            } else if ("delete".equals(action)) {
                e.delete();
            } else {
                e.status("error request");
            }
        } catch (IOException | RuntimeException ex) {
            ex.printStackTrace();
            e.status("error " + ex.getClass().getSimpleName());
        }
    }

    // ---- scan ------------------------------------------------------------------------

    void scan() throws IOException {
        status("running scan 0");
        final Scan s = new Scan();
        s.roots.put(Space.INTERNAL, mMedia.getPath());
        s.free.put(Space.INTERNAL, new long[] {mMedia.getUsableSpace(), mMedia.getTotalSpace()});
        if (mEasyroms != null) {
            s.roots.put(Space.EASYROMS, mEasyroms.getPath());
            s.free.put(Space.EASYROMS,
                    new long[] {mEasyroms.getUsableSpace(), mEasyroms.getTotalSpace()});
        }
        final Set<String> seen = new HashSet<>();
        final int keepStates = number("keep_states", 3);

        // Save states, next to the ROMs and in the emulators' folders
        final List<File> romFiles = new ArrayList<>();
        final Map<String, long[]> folders = new LinkedHashMap<>();
        walk(mMedia, 0, new HashSet<>(Arrays.asList("Android", "Backups")), s, seen, keepStates,
                null, null);
        for (File f : new File[] {new File(mMedia, "Android/data/com.retroarch/files"),
                new File(mMedia, "Android/data/org.ppsspp.ppsspp/files")}) {
            walk(f, 1, new HashSet<>(), s, seen, keepStates, null, null);
        }
        if (mEasyroms != null) {
            walk(mEasyroms, 0, new HashSet<>(Arrays.asList("backups")), s, seen, keepStates,
                    romFiles, folders);
        }

        // Save backup zips
        final int keepBackups = number("keep_backups", 3);
        for (File d : backupDirs()) {
            final String[] names = d.list();
            if (names == null) continue;
            for (String n : Rules.oldBackups(Arrays.asList(names), keepBackups)) {
                addFile(s, seen, Scan.BACKUPS, new File(d, n), null);
            }
        }

        // RetroArch's cache and box art: one item per entry in the folder
        for (File root : raRoots("cache_directory", "cache", null)) {
            addChildren(s, seen, Scan.RACACHE, root);
        }
        for (File root : raRoots("thumbnails_directory", "thumbnails", "thumbnails")) {
            addChildren(s, seen, Scan.THUMBS, root);
        }

        // Screenshots: pictures only
        for (File root : shotRoots()) addPictures(s, seen, root, 0);

        // Downloaded app installers
        addApks(s, seen, new File(mMedia, "Download"), 0, 3);
        if (mEasyroms != null) addApks(s, seen, mEasyroms, 0, 0);

        // The same ROM twice
        if ("1".equals(mRequest.get("dups"))) {
            status("running dups 0");
            for (List<File> g : Dupes.find(romFiles)) {
                final File keep = g.get(0);
                for (int i = 1; i < g.size(); i++) {
                    addFile(s, seen, Scan.DUPS, g.get(i), keep.getPath());
                }
            }
        }

        // Biggest folders on EASYROMS
        final List<Map.Entry<String, long[]>> list = new ArrayList<>(folders.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        for (Map.Entry<String, long[]> e : list) {
            if (e.getValue()[0] > 0) {
                s.folders.add(new Scan.Folder(e.getKey(), e.getValue()[0], e.getValue()[1]));
            }
        }

        // What the helper script found out about the card (mount state, kernel messages)
        s.health.putAll(keyValues(read(new File(mDir, "health"))));

        write(new File(mDir, "scan"), s.format());
        status("done scan " + s.items.size());
    }

    /**
     * Walks a tree: finds old save states folder by folder and, for EASYROMS, collects the
     * ROM candidates and the size of each top-level folder.
     */
    private void walk(File dir, int depth, Set<String> skipTop, Scan s, Set<String> seen,
            int keepStates, List<File> romFiles, Map<String, long[]> folders)
            throws IOException {
        walk(dir, depth, skipTop, s, seen, keepStates, romFiles, folders, null);
    }

    private void walk(File dir, int depth, Set<String> skipTop, Scan s, Set<String> seen,
            int keepStates, List<File> romFiles, Map<String, long[]> folders, long[] top)
            throws IOException {
        if (depth > MAX_DEPTH || mSeen > MAX_FILES || !dir.isDirectory() || isLink(dir)) return;
        final File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        final List<Rules.Entry> entries = new ArrayList<>();
        for (File c : children) {
            final String name = c.getName();
            if (isLink(c)) continue;
            if (c.isDirectory()) {
                if (name.startsWith(".") || SKIP.contains(name)) continue;
                if (depth == 0 && skipTop.contains(name)) continue;
                long[] t = top;
                if (folders != null && depth == 0) {
                    t = new long[2];
                    folders.put(name, t);
                }
                walk(c, depth + 1, skipTop, s, seen, keepStates, romFiles, folders, t);
                continue;
            }
            if (++mSeen % 500 == 0) progress("running scan " + mSeen);
            entries.add(new Rules.Entry(name, c.lastModified()));
            if (top != null) {
                top[0] += c.length();
                top[1]++;
            }
            if (romFiles != null && top != null && !"bios".equalsIgnoreCase(topName(c))
                    && Rules.romCandidate(name, c.length())) {
                romFiles.add(c);
            }
        }
        for (String n : Rules.oldStates(entries, keepStates,
                Rules.isPpssppStateFolder(dir.getName()))) {
            addFile(s, seen, Scan.STATES, new File(dir, n), null);
        }
    }

    /** The top-level folder of a file on EASYROMS. */
    private String topName(File f) {
        final String rel = f.getPath().substring(mEasyroms.getPath().length() + 1);
        final int slash = rel.indexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    private void addFile(Scan s, Set<String> seen, String group, File f, String keep) {
        final String p = f.getPath();
        if (!f.isFile() || !Scan.writable(p) || (keep != null && !Scan.writable(keep))
                || Rules.isBatterySave(f.getName()) || !seen.add(p)) {
            return;
        }
        s.items.add(new Scan.Item(group, f.length(), f.lastModified(), p, keep));
    }

    private void addChildren(Scan s, Set<String> seen, String group, File root) {
        final File[] children = root.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File c : children) {
            final String p = c.getPath();
            if (isLink(c) || !Scan.writable(p) || seen.contains(p)) continue;
            if (c.isDirectory()) {
                if (hasBatterySave(c, 0)) continue;
                seen.add(p);
                s.items.add(new Scan.Item(group, size(c, 0), c.lastModified(), p, null));
            } else {
                addFile(s, seen, group, c, null);
            }
        }
    }

    private void addPictures(Scan s, Set<String> seen, File dir, int depth) {
        if (depth > 3 || isLink(dir)) return;
        final File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File c : children) {
            if (c.isDirectory()) {
                if (!c.getName().startsWith(".")) addPictures(s, seen, c, depth + 1);
            } else if (Rules.isPicture(c.getName()) && !isLink(c)) {
                addFile(s, seen, Scan.SHOTS, c, null);
            }
        }
    }

    private void addApks(Scan s, Set<String> seen, File dir, int depth, int maxDepth) {
        if (depth > maxDepth || isLink(dir)) return;
        final File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File c : children) {
            if (c.isDirectory()) {
                if (!c.getName().startsWith(".")) addApks(s, seen, c, depth + 1, maxDepth);
            } else if (Rules.isApk(c.getName()) && !isLink(c)) {
                addFile(s, seen, Scan.APKS, c, null);
            }
        }
    }

    // ---- where things are ------------------------------------------------------------

    List<File> backupDirs() {
        final List<File> l = new ArrayList<>();
        l.add(new File(mMedia, "Backups/Saves"));
        if (mEasyroms != null) l.add(new File(mEasyroms, "backups/saves"));
        return l;
    }

    /**
     * RetroArch's folder for a cfg key, plus its default places. A folder only counts if
     * its own name says what it is ("thumbnails", "cache"), so a cfg that points the key
     * at the whole card can't make us offer the whole card.
     */
    List<File> raRoots(String key, String mustContain, String defaultName) {
        final List<File> l = new ArrayList<>();
        for (File cfg : raConfigs()) addRoot(l, lower(cfgValue(cfg, key)), mustContain);
        if (defaultName != null) {
            addRoot(l, new File(mMedia, "RetroArch/" + defaultName), mustContain);
            addRoot(l, new File(mMedia, "Android/data/com.retroarch/files/" + defaultName),
                    mustContain);
        }
        return l;
    }

    List<File> shotRoots() {
        final List<File> l = new ArrayList<>();
        for (File cfg : raConfigs()) {
            addRoot(l, lower(cfgValue(cfg, "screenshot_directory")), "screenshot");
        }
        addRoot(l, new File(mMedia, "Pictures/Screenshots"), "screenshot");
        addRoot(l, new File(mMedia, "DCIM/Screenshots"), "screenshot");
        addRoot(l, new File(mMedia, "RetroArch/screenshots"), "screenshot");
        addRoot(l, new File(mMedia, "Android/data/com.retroarch/files/screenshots"),
                "screenshot");
        addRoot(l, new File(mMedia, "PSP/SCREENSHOT"), "screenshot");
        if (mEasyroms != null) {
            addRoot(l, new File(mEasyroms, "screenshots"), "screenshot");
            addRoot(l, new File(mEasyroms, "PSP/SCREENSHOT"), "screenshot");
        }
        return l;
    }

    private void addRoot(List<File> l, File f, String mustContain) {
        if (f == null || !f.isDirectory() || isLink(f)) return;
        f = canonical(f);
        if (!f.getName().toLowerCase(Locale.ROOT).contains(mustContain)) return;
        if (!inArea(f) || l.contains(f)) return;
        l.add(f);
    }

    private File[] raConfigs() {
        return new File[] {new File(mMedia, "Android/data/com.retroarch/files/retroarch.cfg"),
                mRaData != null ? new File(mRaData, "retroarch.cfg") : new File("/nonexistent")};
    }

    /** A path as RetroArch names it, as root sees it; null if it's not on our storage. */
    File lower(String p) {
        if (p == null) return null;
        p = p.trim();
        if (p.isEmpty() || p.equals("default")) return null;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        for (String prefix : new String[] {"/storage/emulated/0", "/storage/self/primary",
                "/sdcard", "/mnt/sdcard", "/data/media/0", mMedia.getPath()}) {
            if (p.equals(prefix)) return mMedia;
            if (p.startsWith(prefix + "/")) return new File(mMedia, p.substring(prefix.length() + 1));
        }
        if (mEasyroms != null) {
            final String uuid = mEasyroms.getName();
            for (String prefix : new String[] {"/storage/" + uuid, "/mnt/media_rw/" + uuid,
                    mEasyroms.getPath()}) {
                if (p.equals(prefix)) return mEasyroms;
                if (p.startsWith(prefix + "/")) {
                    return new File(mEasyroms, p.substring(prefix.length() + 1));
                }
            }
        }
        return null;
    }

    static String cfgValue(File cfg, String key) {
        final String text = read(cfg);
        if (text == null) return null;
        String value = null;
        for (String line : text.split("\n")) {
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
        return value;
    }

    // ---- delete ----------------------------------------------------------------------

    void delete() throws IOException {
        final String text = read(new File(mDir, "delete"));
        final List<String[]> lines = new ArrayList<>();
        if (text != null) {
            for (String l : text.split("\n")) {
                if (!l.isEmpty()) lines.add(l.split("\t", -1));
            }
        }
        status("running delete 0 " + lines.size());
        final Set<String> asked = new HashSet<>();
        for (String[] l : lines) {
            if (l.length >= 2) asked.add(l[1]);
        }
        // Check everything first, against the files as they are now, then remove.
        final List<File> ok = new ArrayList<>();
        int refused = 0;
        final Checker checker = new Checker(asked);
        for (String[] l : lines) {
            final File f = l.length >= 2 ? checker.check(l[0], l[1], l.length >= 3 ? l[2] : null)
                    : null;
            if (f != null) {
                ok.add(f);
            } else {
                refused++;
                System.err.println("refused: " + String.join(" ", l));
            }
        }
        int deleted = 0, failed = 0, done = 0;
        long bytes = 0;
        for (File f : ok) {
            final long n = f.isDirectory() ? size(f, 0) : f.length();
            if (remove(f, 0)) {
                deleted++;
                bytes += n;
            } else {
                failed++;
            }
            progress("running delete " + (++done) + " " + ok.size());
        }
        final String result = "deleted=" + deleted + "\nbytes=" + bytes + "\nfailed=" + failed
                + "\nrefused=" + refused + "\n";
        write(new File(mDir, "result"), result);
        status("done delete " + deleted + " " + bytes + " " + failed + " " + refused);
    }

    /** Checks one line of a delete request against the rules. */
    final class Checker {
        private final Set<String> mAsked;
        private final Map<String, Set<String>> mOldStates = new HashMap<>();
        private final Map<String, Set<String>> mOldBackups = new HashMap<>();
        private List<File> mThumbs, mCache, mShots;

        Checker(Set<String> asked) {
            mAsked = asked;
        }

        /** The file to remove, or null if this line isn't allowed. */
        File check(String group, String path, String keep) {
            if (path == null || !path.startsWith("/")) return null;
            final File f = new File(path);
            if (!f.exists() || isLink(f) || !inArea(f) || !canonical(f).getPath().equals(path)) {
                return null;
            }
            if (f.equals(mMedia) || f.equals(mEasyroms)) return null;
            final String name = f.getName();
            final boolean file = f.isFile();
            if (file && Rules.isBatterySave(name)) return null;
            switch (group) {
                case Scan.STATES:
                    return file && oldStates(f.getParentFile()).contains(name) ? f : null;
                case Scan.BACKUPS:
                    return file && backupDirs().contains(f.getParentFile())
                            && oldBackups(f.getParentFile()).contains(name) ? f : null;
                case Scan.RACACHE:
                    if (mCache == null) mCache = raRoots("cache_directory", "cache", null);
                    return childOf(mCache, f) && !hasBatterySave(f, 0) ? f : null;
                case Scan.THUMBS:
                    if (mThumbs == null) {
                        mThumbs = raRoots("thumbnails_directory", "thumbnails", "thumbnails");
                    }
                    return childOf(mThumbs, f) && !hasBatterySave(f, 0) ? f : null;
                case Scan.SHOTS:
                    if (mShots == null) mShots = shotRoots();
                    return file && Rules.isPicture(name) && below(mShots, f) ? f : null;
                case Scan.APKS:
                    return file && Rules.isApk(name)
                            && (below(Arrays.asList(new File(mMedia, "Download")), f)
                                    || (mEasyroms != null
                                            && mEasyroms.equals(f.getParentFile()))) ? f : null;
                case Scan.DUPS:
                    return file && sameCopyStays(f, keep) ? f : null;
                default:
                    return null;
            }
        }

        private Set<String> oldStates(File dir) {
            return mOldStates.computeIfAbsent(dir.getPath(), k -> {
                final List<Rules.Entry> entries = new ArrayList<>();
                final File[] children = dir.listFiles();
                if (children != null) {
                    for (File c : children) {
                        if (c.isFile()) entries.add(new Rules.Entry(c.getName(), c.lastModified()));
                    }
                }
                return Rules.oldStates(entries, number("keep_states", 3),
                        Rules.isPpssppStateFolder(dir.getName()));
            });
        }

        private Set<String> oldBackups(File dir) {
            return mOldBackups.computeIfAbsent(dir.getPath(), k -> {
                final String[] names = dir.list();
                return Rules.oldBackups(names != null ? Arrays.asList(names)
                        : new ArrayList<>(), number("keep_backups", 3));
            });
        }

        /** A duplicate goes only if the other copy is there, stays, and is really the same. */
        private boolean sameCopyStays(File f, String keep) {
            if (mEasyroms == null || keep == null || mAsked.contains(keep)) return false;
            final File k = new File(keep);
            if (!k.isFile() || isLink(k) || !inside(mEasyroms, k) || !inside(mEasyroms, f)
                    || k.equals(f) || !canonical(k).getPath().equals(keep)
                    || k.length() != f.length()) {
                return false;
            }
            final String a = Dupes.hash(f), b = Dupes.hash(k);
            return a != null && a.equals(b);
        }
    }

    // ---- small helpers ---------------------------------------------------------------

    private boolean inArea(File f) {
        return inside(mMedia, f) || (mEasyroms != null && inside(mEasyroms, f));
    }

    static boolean inside(File root, File f) {
        final String r = root.getPath();
        return f.getPath().startsWith(r.endsWith("/") ? r : r + "/");
    }

    /** f is directly in one of the roots. */
    private static boolean childOf(List<File> roots, File f) {
        return roots.contains(f.getParentFile());
    }

    /** f is somewhere below one of the roots. */
    private static boolean below(List<File> roots, File f) {
        for (File r : roots) {
            if (inside(canonical(r), f)) return true;
        }
        return false;
    }

    static boolean isLink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (RuntimeException e) {
            return true;
        }
    }

    static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }

    private static boolean hasBatterySave(File f, int depth) {
        if (!f.isDirectory()) return Rules.isBatterySave(f.getName());
        if (depth > MAX_DEPTH) return true;
        final File[] children = f.listFiles();
        if (children == null) return false;
        for (File c : children) {
            if (!isLink(c) && hasBatterySave(c, depth + 1)) return true;
        }
        return false;
    }

    private static long size(File f, int depth) {
        if (isLink(f)) return 0;
        if (!f.isDirectory()) return f.length();
        if (depth > MAX_DEPTH) return 0;
        long n = 0;
        final File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) n += size(c, depth + 1);
        }
        return n;
    }

    /** Removes a file or a folder tree (never following links out of it). */
    private static boolean remove(File f, int depth) {
        if (isLink(f)) return f.delete();
        if (f.isDirectory()) {
            if (depth > MAX_DEPTH) return false;
            final File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) remove(c, depth + 1);
            }
        }
        return f.delete();
    }

    private int number(String key, int def) {
        try {
            return Integer.parseInt(mRequest.getOrDefault(key, "" + def).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void progress(String s) {
        final long now = System.currentTimeMillis();
        if (now - mLastStatus < 300) return;
        mLastStatus = now;
        try {
            status(s);
        } catch (RuntimeException e) {
            // progress is only a nicety
        }
    }

    void status(String s) {
        try {
            write(new File(mDir, "status"), s + "\n");
        } catch (IOException e) {
            System.err.println("status: " + e);
        }
    }

    static Map<String, String> keyValues(String text) {
        final Map<String, String> m = new LinkedHashMap<>();
        if (text == null) return m;
        for (String line : text.split("\n")) {
            final int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return m;
    }

    static String read(File f) {
        if (f == null || !f.isFile()) return null;
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            final char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } catch (IOException e) {
            return null;
        }
        return sb.toString();
    }

    static void write(File f, String text) throws IOException {
        final File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("rename " + f);
        }
    }
}
