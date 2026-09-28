package org.andr36oid.savebackup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The backup files: saves-20260928-140233-17.zip, with the saves under internal/ and
 * easyroms/ and a manifest.txt. The number at the end counts up with every backup, so the
 * order stays right even when the clock was wrong.
 */
final class Archive {

    static final Pattern NAME = Pattern.compile("saves-(\\d{8})-(\\d{6})-(\\d+)\\.zip");
    private static final String README = "README.txt";

    interface Progress {
        void step(int done, int total);
    }

    private Archive() {
    }

    static String name(long now, int seq) {
        return "saves-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(new Date(now)) + "-" + seq + ".zip";
    }

    static int seq(String name) {
        final Matcher m = NAME.matcher(name);
        if (!m.matches()) return -1;
        try {
            return Integer.parseInt(m.group(3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String day(String name) {
        final Matcher m = NAME.matcher(name);
        return m.matches() ? m.group(1) : "";
    }

    /** Our backups in a folder, newest first. */
    static List<File> list(File dir) {
        final List<File> list = new ArrayList<>();
        final File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return list;
        for (File f : files) {
            if (f.isFile() && seq(f.getName()) >= 0) list.add(f);
        }
        Collections.sort(list, (a, b) -> Integer.compare(seq(b.getName()), seq(a.getName())));
        return list;
    }

    static int maxSeq(Collection<File> dirs) {
        int max = 0;
        for (File d : dirs) {
            for (File f : list(d)) max = Math.max(max, seq(f.getName()));
        }
        return max;
    }

    /** Leftovers of a backup that was cut off. */
    static void cleanTemp(File dir) {
        final File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith("saves-") && f.getName().endsWith(".zip.tmp")) f.delete();
        }
    }

    /**
     * Writes the backup. A file that can't be read is left out of the manifest (and so
     * never restored); the rest is still saved.
     */
    static Manifest write(List<SaveFile> saves, File out, long now, String reason,
            Progress progress) throws IOException {
        final Manifest m = new Manifest();
        m.created = now;
        m.reason = reason;
        final File tmp = new File(out.getPath() + ".tmp");
        final byte[] buf = new byte[65536];
        try (FileOutputStream fos = new FileOutputStream(tmp);
                ZipOutputStream zip = new ZipOutputStream(fos)) {
            zip.setLevel(6);
            put(zip, README, readme().getBytes(StandardCharsets.UTF_8), now);
            int done = 0;
            for (SaveFile s : saves) {
                if (progress != null) progress.step(done++, saves.size());
                final MessageDigest sha = sha1();
                final ZipEntry ze = new ZipEntry(s.id);
                ze.setTime(s.mtime);
                zip.putNextEntry(ze);
                boolean ok = true;
                long n = 0;
                try (InputStream in = new FileInputStream(s.file)) {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        zip.write(buf, 0, r);
                        sha.update(buf, 0, r);
                        n += r;
                    }
                } catch (IOException e) {
                    ok = false;
                }
                zip.closeEntry();
                // it changed while we read it: keep what we read
                if (ok) {
                    final SaveFile read = n == s.size ? s : resized(s, n);
                    m.add(read, hex(sha.digest()));
                }
            }
            put(zip, Manifest.NAME, m.text().getBytes(StandardCharsets.UTF_8), now);
            zip.finish();
            fos.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        // read it back before it counts as a backup
        try {
            if (readManifest(tmp).entries.size() != m.entries.size()) {
                throw new IOException("backup reads back wrong");
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            throw new IOException("can't rename " + tmp);
        }
        return m;
    }

    private static SaveFile resized(SaveFile s, long n) {
        final SaveFile r = new SaveFile(s.id, s.file, n, s.mtime);
        r.key = s.key;
        r.label = s.label;
        r.folder = s.folder;
        return r;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data, long time)
            throws IOException {
        final ZipEntry e = new ZipEntry(name);
        e.setTime(time);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    private static String readme() {
        return "Save backup made by andr36oid (Settings > Save backup).\n"
                + "\n"
                + "internal/  files from the internal storage (Internal storage/…)\n"
                + "easyroms/  files from the EASYROMS partition of the SD card\n"
                + "manifest.txt  every file with its size, time and SHA-1\n"
                + "\n"
                + "Restore on the console with Settings > Save backup > Restore saves,\n"
                + "or copy the files back by hand to the same folders.\n";
    }

    /** Copies a backup to another folder (via a temp file), checking the size. */
    static void copy(File src, File dstDir) throws IOException {
        mkdirs(dstDir, null);
        final File out = new File(dstDir, src.getName());
        final File tmp = new File(dstDir, src.getName() + ".tmp");
        final byte[] buf = new byte[65536];
        try (InputStream in = new FileInputStream(src);
                FileOutputStream fos = new FileOutputStream(tmp)) {
            int r;
            while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
            fos.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (tmp.length() != src.length() || !tmp.renameTo(out)) {
            tmp.delete();
            throw new IOException("copy to " + dstDir + " failed");
        }
    }

    static Manifest readManifest(File zipFile) throws IOException {
        try (ZipFile z = new ZipFile(zipFile)) {
            final ZipEntry e = z.getEntry(Manifest.NAME);
            if (e == null) throw new IOException("no manifest in " + zipFile);
            try (InputStream in = z.getInputStream(e)) {
                return Manifest.read(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Keeps {@code keep} backups: the newest one of each of the last keep/2 days that have
     * backups (so a day of many short sessions can't push out every older day), then the
     * newest of the rest. {@code protect} is never deleted.
     *
     * @return the files deleted
     */
    static List<File> prune(File dir, int keep, String protect) {
        final List<File> all = list(dir);
        final Set<File> kept = new LinkedHashSet<>();
        final Set<String> days = new HashSet<>();
        final int dayQuota = keep / 2;
        for (File f : all) {
            if (days.size() >= dayQuota) break;
            if (days.add(day(f.getName()))) kept.add(f);
        }
        for (File f : all) {
            if (kept.size() >= keep) break;
            kept.add(f);
        }
        final List<File> deleted = new ArrayList<>();
        for (File f : all) {
            if (kept.contains(f) || f.getName().equals(protect)) continue;
            if (f.delete()) deleted.add(f);
        }
        return deleted;
    }

    /** mkdir -p, telling {@code made} about every folder it made. */
    static boolean mkdirs(File dir, List<File> made) {
        if (dir.isDirectory()) return true;
        final File parent = dir.getParentFile();
        if (parent != null && !mkdirs(parent, made)) return false;
        if (dir.mkdir()) {
            if (made != null) made.add(dir);
            return true;
        }
        return dir.isDirectory();
    }

    static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha1Of(File f) throws IOException {
        final MessageDigest sha = sha1();
        final byte[] buf = new byte[65536];
        try (InputStream in = new FileInputStream(f)) {
            int r;
            while ((r = in.read(buf)) > 0) sha.update(buf, 0, r);
        }
        return hex(sha.digest());
    }

    static String hex(byte[] b) {
        final StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    /** Streams one file out of a backup. */
    static void extract(ZipFile z, String id, OutputStream out, MessageDigest sha)
            throws IOException {
        final ZipEntry e = z.getEntry(id);
        if (e == null) throw new IOException("not in the backup: " + id);
        final byte[] buf = new byte[65536];
        try (InputStream in = z.getInputStream(e)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                sha.update(buf, 0, r);
            }
        }
    }
}
