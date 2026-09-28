package org.andr36oid.savebackup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files the engine leaves for the app in its folder, and their parsers (plain Java, so
 * the host test reads them the same way the app does):
 * <pre>
 * last   key=value: time name files bytes where checked result
 * index  backup TAB name created zipBytes files bytes where(,) ok|broken
 *        game   TAB key label files bytes newestMtime folders(,)     (after its backup)
 * plan   zip    TAB name where
 *        item   TAB state id key label backupMtime consoleMtime backupSize consoleSize
 * result key=value: restored kept same failed unavailable
 * </pre>
 */
final class Index {

    private Index() {
    }

    /** The last backup run. */
    static final class Last {
        /** When the newest backup was made, 0 if there is none. */
        long time;
        String name = "";
        int files;
        long bytes;
        String where = "";
        /** When the saves were last found backed up (a backup, or nothing changed). */
        long checked;
        /** backup, unchanged, nothing (no saves found), or an error code. */
        String result = "";

        String text() {
            return "time=" + time + "\nname=" + name + "\nfiles=" + files + "\nbytes=" + bytes
                    + "\nwhere=" + where + "\nchecked=" + checked + "\nresult=" + result + "\n";
        }

        static Last parse(String text) {
            final Last l = new Last();
            if (text == null) return l;
            final Map<String, String> kv = keyValues(text);
            l.time = num(kv.get("time"));
            l.name = str(kv.get("name"));
            l.files = (int) num(kv.get("files"));
            l.bytes = num(kv.get("bytes"));
            l.where = str(kv.get("where"));
            l.checked = num(kv.get("checked"));
            l.result = str(kv.get("result"));
            return l;
        }
    }

    static final class Backup {
        String name;
        long created;
        long zipBytes;
        int files;
        long bytes;
        List<String> where = new ArrayList<>();
        boolean ok;
        final List<Game> games = new ArrayList<>();
    }

    static final class Game {
        String key;
        String label;
        int files;
        long bytes;
        long newest;
        List<String> folders = new ArrayList<>();
    }

    static final class PlanItem {
        String state;
        String id;
        String key;
        String label;
        long backupMtime;
        long consoleMtime;
        long backupSize;
        long consoleSize;
    }

    static final class Plan {
        String zip = "";
        String where = "";
        final List<PlanItem> items = new ArrayList<>();

        int count(String state) {
            int n = 0;
            for (PlanItem i : items) {
                if (i.state.equals(state)) n++;
            }
            return n;
        }
    }

    /** Games of a manifest, in the order of their labels. */
    static List<Game> games(Manifest m) {
        final Map<String, Game> map = new LinkedHashMap<>();
        for (Manifest.Entry e : m.entries) {
            Game g = map.get(e.key);
            if (g == null) {
                g = new Game();
                g.key = e.key;
                g.label = e.label;
                map.put(e.key, g);
            } else if (e.key.startsWith("psp:") && g.label.equals(e.key.substring(4))) {
                // a PSP state has only the ID; the save folder has the title
                g.label = e.label;
            }
            g.files++;
            g.bytes += e.size;
            g.newest = Math.max(g.newest, e.mtime);
            if (!e.folder.isEmpty() && !g.folders.contains(e.folder)) g.folders.add(e.folder);
        }
        final List<Game> list = new ArrayList<>(map.values());
        list.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    static String backupLine(String name, Manifest m, long zipBytes, List<String> where) {
        return "backup\t" + name + "\t" + m.created + "\t" + zipBytes + "\t"
                + m.entries.size() + "\t" + m.bytes() + "\t" + String.join(",", where)
                + "\tok\n";
    }

    static String brokenLine(String name, long zipBytes, List<String> where) {
        return "backup\t" + name + "\t0\t" + zipBytes + "\t0\t0\t" + String.join(",", where)
                + "\tbroken\n";
    }

    static String gameLine(Game g) {
        return "game\t" + g.key + "\t" + g.label + "\t" + g.files + "\t" + g.bytes + "\t"
                + g.newest + "\t" + String.join(",", g.folders) + "\n";
    }

    static List<Backup> parseIndex(String text) {
        final List<Backup> list = new ArrayList<>();
        if (text == null) return list;
        Backup cur = null;
        for (String line : lines(text)) {
            final String[] f = line.split("\t", -1);
            try {
                if (f[0].equals("backup") && f.length >= 8) {
                    cur = new Backup();
                    cur.name = f[1];
                    cur.created = Long.parseLong(f[2]);
                    cur.zipBytes = Long.parseLong(f[3]);
                    cur.files = Integer.parseInt(f[4]);
                    cur.bytes = Long.parseLong(f[5]);
                    cur.where = split(f[6]);
                    cur.ok = f[7].equals("ok");
                    list.add(cur);
                } else if (f[0].equals("game") && f.length >= 7 && cur != null) {
                    final Game g = new Game();
                    g.key = f[1];
                    g.label = f[2];
                    g.files = Integer.parseInt(f[3]);
                    g.bytes = Long.parseLong(f[4]);
                    g.newest = Long.parseLong(f[5]);
                    g.folders = split(f[6]);
                    cur.games.add(g);
                }
            } catch (NumberFormatException ignored) {
                // skip a damaged line
            }
        }
        return list;
    }

    static String planLine(Restorer.Item it) {
        final Manifest.Entry e = it.entry;
        return "item\t" + it.state + "\t" + e.id + "\t" + e.key + "\t" + e.label + "\t"
                + e.mtime + "\t" + it.curMtime + "\t" + e.size + "\t" + it.curSize + "\n";
    }

    static Plan parsePlan(String text) {
        final Plan p = new Plan();
        if (text == null) return p;
        for (String line : lines(text)) {
            final String[] f = line.split("\t", -1);
            try {
                if (f[0].equals("zip") && f.length >= 3) {
                    p.zip = f[1];
                    p.where = f[2];
                } else if (f[0].equals("item") && f.length >= 9) {
                    final PlanItem i = new PlanItem();
                    i.state = f[1];
                    i.id = f[2];
                    i.key = f[3];
                    i.label = f[4];
                    i.backupMtime = Long.parseLong(f[5]);
                    i.consoleMtime = Long.parseLong(f[6]);
                    i.backupSize = Long.parseLong(f[7]);
                    i.consoleSize = Long.parseLong(f[8]);
                    p.items.add(i);
                }
            } catch (NumberFormatException ignored) {
                // skip a damaged line
            }
        }
        return p;
    }

    static Map<String, String> keyValues(String text) {
        final Map<String, String> kv = new LinkedHashMap<>();
        if (text == null) return kv;
        for (String line : lines(text)) {
            final int eq = line.indexOf('=');
            if (eq > 0) kv.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return kv;
    }

    static long num(String s) {
        try {
            return s != null ? Long.parseLong(s.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(String s) {
        return s != null ? s : "";
    }

    private static List<String> split(String s) {
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    private static List<String> lines(String text) {
        final List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) out.add(line);
            }
        } catch (IOException ignored) {
            // a StringReader doesn't throw
        }
        return out;
    }
}
