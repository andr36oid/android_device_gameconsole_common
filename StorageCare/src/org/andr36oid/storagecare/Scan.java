package org.andr36oid.storagecare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a scan found, as the engine writes it (one tab-separated record per line) and the
 * app reads it back. Plain Java.
 *
 *   root     internal|easyroms  PATH
 *   free     internal|easyroms  FREE_BYTES  TOTAL_BYTES
 *   folder   BYTES  FILES  NAME             (top-level folders of EASYROMS)
 *   item     GROUP  BYTES  MTIME  PATH  KEEP  ("-" when there's no copy to keep)
 *   health   KEY  VALUE
 */
final class Scan {

    /** Old save states beyond the newest N per game (never battery saves). */
    static final String STATES = "states";
    /** Save backup zips beyond the newest N per place. */
    static final String BACKUPS = "backups";
    /** RetroArch's downloaded box art and screen pictures. */
    static final String THUMBS = "thumbs";
    /** RetroArch's cache folder (unpacked zips and such). */
    static final String RACACHE = "racache";
    static final String SHOTS = "shots";
    static final String APKS = "apks";
    static final String DUPS = "dups";

    /** In the order the page shows them. */
    static final String[] GROUPS = {STATES, BACKUPS, RACACHE, THUMBS, SHOTS, APKS, DUPS};

    static final class Item {
        final String group;
        final long bytes;
        final long mtime;
        final String path;
        /** For a duplicate: the copy that stays. Else null. */
        final String keep;

        Item(String group, long bytes, long mtime, String path, String keep) {
            this.group = group;
            this.bytes = bytes;
            this.mtime = mtime;
            this.path = path;
            this.keep = keep;
        }
    }

    static final class Folder {
        final String name;
        final long bytes;
        final long files;

        Folder(String name, long bytes, long files) {
            this.name = name;
            this.bytes = bytes;
            this.files = files;
        }
    }

    final Map<String, String> roots = new LinkedHashMap<>();
    /** volume -> {free, total} */
    final Map<String, long[]> free = new LinkedHashMap<>();
    final List<Folder> folders = new ArrayList<>();
    final List<Item> items = new ArrayList<>();
    final Map<String, String> health = new LinkedHashMap<>();

    /** Paths go into the file as they are; a tab or line break in one would break it. */
    static boolean writable(String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') return false;
        }
        return !s.isEmpty();
    }

    String format() {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : roots.entrySet()) {
            sb.append("root\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        for (Map.Entry<String, long[]> e : free.entrySet()) {
            sb.append("free\t").append(e.getKey()).append('\t').append(e.getValue()[0])
                    .append('\t').append(e.getValue()[1]).append('\n');
        }
        for (Folder f : folders) {
            sb.append("folder\t").append(f.bytes).append('\t').append(f.files).append('\t')
                    .append(f.name).append('\n');
        }
        for (Item i : items) {
            sb.append("item\t").append(i.group).append('\t').append(i.bytes).append('\t')
                    .append(i.mtime).append('\t').append(i.path).append('\t')
                    .append(i.keep != null ? i.keep : "-").append('\n');
        }
        for (Map.Entry<String, String> e : health.entrySet()) {
            sb.append("health\t").append(e.getKey()).append('\t').append(e.getValue())
                    .append('\n');
        }
        return sb.toString();
    }

    static Scan parse(String text) {
        final Scan s = new Scan();
        if (text == null) return s;
        for (String line : text.split("\n")) {
            final String[] f = line.split("\t", -1);
            try {
                switch (f[0]) {
                    case "root":
                        if (f.length >= 3) s.roots.put(f[1], f[2]);
                        break;
                    case "free":
                        if (f.length >= 4) {
                            s.free.put(f[1], new long[] {Long.parseLong(f[2]),
                                    Long.parseLong(f[3])});
                        }
                        break;
                    case "folder":
                        if (f.length >= 4) {
                            s.folders.add(new Folder(f[3], Long.parseLong(f[1]),
                                    Long.parseLong(f[2])));
                        }
                        break;
                    case "item":
                        if (f.length >= 6) {
                            s.items.add(new Item(f[1], Long.parseLong(f[2]),
                                    Long.parseLong(f[3]), f[4], "-".equals(f[5]) ? null : f[5]));
                        }
                        break;
                    case "health":
                        if (f.length >= 3) s.health.put(f[1], f[2]);
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException e) {
                // skip a broken line
            }
        }
        return s;
    }

    List<Item> group(String group) {
        final List<Item> list = new ArrayList<>();
        for (Item i : items) {
            if (i.group.equals(group)) list.add(i);
        }
        return list;
    }

    /** "Internal storage/RetroArch/…" or "EASYROMS/gba/…" for a root path. */
    String display(String path) {
        final String in = roots.get(Space.INTERNAL);
        final String er = roots.get(Space.EASYROMS);
        if (in != null && path.startsWith(in + "/")) {
            return "Internal storage/" + path.substring(in.length() + 1);
        }
        if (er != null && path.startsWith(er + "/")) {
            return "EASYROMS/" + path.substring(er.length() + 1);
        }
        return path;
    }
}
