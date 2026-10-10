package org.andr36oid.storagecare;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the same ROM in two places. Cheap on purpose: only files of equal size are
 * compared, first by their first and last 64 KB, and only files that still match are read
 * in full. Most ROMs differ in size, so most are never read at all.
 */
final class Dupes {

    private Dupes() {
    }

    /** Smaller files (saves, text, tiny homebrew) aren't worth it. */
    static final long MIN_BYTES = 256 * 1024;
    private static final int EDGE = 64 * 1024;

    /**
     * Groups of files with the same content, each sorted so that the copy to keep comes
     * first: the one in the shallowest folder, then by path.
     */
    static List<List<File>> find(List<File> files) {
        final Map<Long, List<File>> bySize = new HashMap<>();
        for (File f : files) {
            final long n = f.length();
            if (n >= MIN_BYTES) bySize.computeIfAbsent(n, k -> new ArrayList<>()).add(f);
        }
        final List<List<File>> groups = new ArrayList<>();
        for (List<File> same : bySize.values()) {
            if (same.size() < 2) continue;
            for (List<File> byEdges : split(same, false)) {
                if (byEdges.size() < 2) continue;
                for (List<File> byAll : split(byEdges, true)) {
                    if (byAll.size() < 2) continue;
                    Collections.sort(byAll, Dupes::keepOrder);
                    groups.add(byAll);
                }
            }
        }
        Collections.sort(groups, (a, b) -> a.get(0).getPath().compareTo(b.get(0).getPath()));
        return groups;
    }

    static int keepOrder(File a, File b) {
        final int da = depth(a.getPath()), db = depth(b.getPath());
        if (da != db) return Integer.compare(da, db);
        return a.getPath().compareTo(b.getPath());
    }

    private static int depth(String p) {
        int n = 0;
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) == '/') n++;
        }
        return n;
    }

    private static List<List<File>> split(List<File> files, boolean whole) {
        final Map<String, List<File>> by = new HashMap<>();
        for (File f : files) {
            final String h = whole ? hash(f) : edges(f);
            if (h == null) continue;
            by.computeIfAbsent(h, k -> new ArrayList<>()).add(f);
        }
        return new ArrayList<>(by.values());
    }

    /** SHA-1 of the whole file, null if it can't be read. */
    static String hash(File f) {
        try (InputStream in = new FileInputStream(f)) {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            final byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String edges(File f) {
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            final byte[] buf = new byte[EDGE];
            final long len = in.length();
            md.update(buf, 0, readFully(in, buf));
            if (len > EDGE) {
                in.seek(Math.max(EDGE, len - EDGE));
                md.update(buf, 0, readFully(in, buf));
            }
            return hex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static int readFully(RandomAccessFile in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            final int n = in.read(buf, got, buf.length - got);
            if (n <= 0) break;
            got += n;
        }
        return got;
    }

    private static String hex(byte[] b) {
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
