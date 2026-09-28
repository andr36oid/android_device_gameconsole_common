package org.andr36oid.savebackup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Compares a backup with the saves on the console and puts files back. A save that is
 * newer on the console than in the backup is only replaced when the user said so.
 */
final class Restorer {

    /** Not on the console: restored. */
    static final String MISSING = "missing";
    /** Different and older on the console: replaced. */
    static final String OLDER = "older";
    /** Different and newer on the console: kept, unless the user says replace. */
    static final String NEWER = "newer";
    /** Already the same: nothing to do. */
    static final String SAME = "same";
    /** Its place isn't there (EASYROMS not mounted) or the path is unsafe. */
    static final String UNAVAILABLE = "unavailable";

    static final class Item {
        Manifest.Entry entry;
        String state;
        File target;
        long curMtime;
        long curSize;
    }

    static final class Result {
        int restored;
        int kept;
        int same;
        int failed;
        int unavailable;
        /** Files and folders made, for the helper to give the right owner. */
        final List<File> touched = new ArrayList<>();
    }

    private Restorer() {
    }

    /** What restoring would do, for one game ({@code key}) or everything (null). */
    static List<Item> plan(Roots roots, Manifest m, String key) {
        final List<Item> items = new ArrayList<>();
        for (Manifest.Entry e : m.entries) {
            if (key != null && !key.equals(e.key)) continue;
            final Item it = new Item();
            it.entry = e;
            it.target = roots.resolve(e.id);
            if (it.target == null || it.target.isDirectory()) {
                it.state = UNAVAILABLE;
            } else if (!it.target.exists()) {
                it.state = MISSING;
            } else {
                it.curMtime = it.target.lastModified();
                it.curSize = it.target.length();
                String sum;
                try {
                    sum = it.curSize == e.size ? Archive.sha1Of(it.target) : "";
                } catch (IOException ex) {
                    sum = "";
                }
                if (sum.equals(e.sha1)) {
                    it.state = SAME;
                } else if (it.curMtime > e.mtime) {
                    it.state = NEWER;
                } else {
                    it.state = OLDER;
                }
            }
            items.add(it);
        }
        return items;
    }

    static boolean overwrites(List<Item> plan, boolean replaceNewer) {
        for (Item it : plan) {
            if (it.state.equals(OLDER) || (replaceNewer && it.state.equals(NEWER))) return true;
        }
        return false;
    }

    /** Puts the files back: via a temp file, checked against the SHA-1, with the old time. */
    static Result apply(File zipFile, List<Item> plan, boolean replaceNewer) throws IOException {
        final Result res = new Result();
        try (ZipFile z = new ZipFile(zipFile)) {
            for (Item it : plan) {
                switch (it.state) {
                    case SAME:
                        res.same++;
                        continue;
                    case UNAVAILABLE:
                        res.unavailable++;
                        continue;
                    case NEWER:
                        if (!replaceNewer) {
                            res.kept++;
                            continue;
                        }
                        break;
                    default:
                        break;
                }
                if (restore(z, it, res.touched)) {
                    res.restored++;
                } else {
                    res.failed++;
                }
            }
        }
        return res;
    }

    private static boolean restore(ZipFile z, Item it, List<File> touched) {
        final File dir = it.target.getParentFile();
        final List<File> made = new ArrayList<>();
        if (dir == null || !Archive.mkdirs(dir, made)) return false;
        touched.addAll(made);
        final File tmp = new File(dir, "." + it.target.getName() + Collector.TMP_SUFFIX);
        final MessageDigest sha = Archive.sha1();
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            Archive.extract(z, it.entry.id, out, sha);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (!Archive.hex(sha.digest()).equals(it.entry.sha1) || !tmp.renameTo(it.target)) {
            tmp.delete();
            return false;
        }
        it.target.setLastModified(it.entry.mtime);
        touched.add(it.target);
        return true;
    }
}
