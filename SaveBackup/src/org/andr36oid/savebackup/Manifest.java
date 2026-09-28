package org.andr36oid.savebackup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * manifest.txt inside every backup: when it was made and, per file, where it goes back to,
 * its size, time and SHA-1, and which game it belongs to. TAB separated:
 * <pre>
 * # andr36oid save backup 1
 * created  1759068153000
 * reason   daily
 * file     easyroms/gba/Zelda.srm  8192  1759060000000  3f78…  game:zelda  Zelda  gba
 * </pre>
 */
final class Manifest {

    static final String NAME = "manifest.txt";
    private static final String HEADER = "# andr36oid save backup 1";

    long created;
    String reason = "";
    final List<Entry> entries = new ArrayList<>();

    static final class Entry {
        String id;
        long size;
        long mtime;
        String sha1;
        String key;
        String label;
        String folder;
    }

    Entry add(SaveFile s, String sha1) {
        final Entry e = new Entry();
        e.id = s.id;
        e.size = s.size;
        e.mtime = s.mtime;
        e.sha1 = sha1;
        e.key = s.key;
        e.label = s.label;
        e.folder = s.folder != null ? s.folder : "";
        entries.add(e);
        return e;
    }

    long bytes() {
        long n = 0;
        for (Entry e : entries) n += e.size;
        return n;
    }

    String text() {
        final StringBuilder b = new StringBuilder();
        b.append(HEADER).append('\n');
        b.append("created\t").append(created).append('\n');
        b.append("reason\t").append(reason).append('\n');
        for (Entry e : entries) {
            b.append("file\t").append(e.id).append('\t').append(e.size).append('\t')
                    .append(e.mtime).append('\t').append(e.sha1).append('\t').append(e.key)
                    .append('\t').append(clean(e.label)).append('\t').append(clean(e.folder))
                    .append('\n');
        }
        return b.toString();
    }

    static Manifest read(Reader in) throws IOException {
        final Manifest m = new Manifest();
        final BufferedReader r = new BufferedReader(in);
        String line = r.readLine();
        if (line == null || !line.startsWith("# andr36oid save backup")) {
            throw new IOException("not a save backup manifest");
        }
        while ((line = r.readLine()) != null) {
            final String[] f = line.split("\t", -1);
            try {
                if (f[0].equals("created") && f.length >= 2) {
                    m.created = Long.parseLong(f[1]);
                } else if (f[0].equals("reason") && f.length >= 2) {
                    m.reason = f[1];
                } else if (f[0].equals("file") && f.length >= 7) {
                    final Entry e = new Entry();
                    e.id = f[1];
                    e.size = Long.parseLong(f[2]);
                    e.mtime = Long.parseLong(f[3]);
                    e.sha1 = f[4];
                    e.key = f[5];
                    e.label = f[6];
                    e.folder = f.length >= 8 ? f[7] : "";
                    m.entries.add(e);
                }
            } catch (NumberFormatException ignored) {
                // a damaged line: leave it out
            }
        }
        return m;
    }

    /** What decides "nothing changed": every save's id, size and time. */
    static String fingerprint(List<SaveFile> saves) {
        final StringBuilder b = new StringBuilder();
        for (SaveFile s : saves) {
            b.append(s.id).append('\t').append(s.size).append('\t').append(s.mtime).append('\n');
        }
        return b.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }
}
