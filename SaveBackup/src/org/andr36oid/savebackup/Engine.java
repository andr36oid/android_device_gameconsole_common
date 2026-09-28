package org.andr36oid.savebackup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The part of Save backup that touches the files. It runs as root, started by the
 * andr36oid-savebackup init service (helper/andr36oid-savebackup.sh) through app_process
 * with this APK as the class path, because the app (system user) can't read EASYROMS,
 * the internal storage or the emulators' folders. Plain Java, so it runs on a PC too.
 *
 * <pre>
 * app_process / org.andr36oid.savebackup.Engine --dir DIR --media /data/media/0
 *     --ra-data … --ppsspp-data … [--easyroms /mnt/media_rw/UUID] [--usb /mnt/media_rw/UUID]…
 * </pre>
 * The app writes DIR/request (key=value):
 * <pre>
 * action=backup  reason=daily|session|manual|usb  keep=10  usb=1 (copy even if unchanged)
 * action=list                                  -> DIR/index
 * action=plan    zip=NAME [game=KEY]           -> DIR/plan
 * action=restore zip=NAME [game=KEY] newer=0|1 -> DIR/result
 * </pre>
 * DIR/status is one line: "running …", "done …" or "error CODE". DIR/last describes the
 * last backup (see Index). DIR/touched lists what was made on the internal storage, for
 * the helper to give it the right owner.
 */
public final class Engine {

    /** Room to leave on a partition, so games can still save. */
    static final long MARGIN = 32L << 20;
    static final int DEFAULT_KEEP = 10;

    private final File mDir;
    private final Roots mRoots;
    private final Map<String, String> mRequest;
    private final List<File> mTouched = new ArrayList<>();
    private long mNow;
    private long mLastProgress;

    Engine(File dir, Roots roots, Map<String, String> request) {
        mDir = dir;
        mRoots = roots;
        mRequest = request;
        final long now = Index.num(request.get("now"));
        mNow = now > 0 ? now : System.currentTimeMillis();
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        File dir = null, media = null, easyroms = null, raData = null, ppsspp = null;
        final List<File> usb = new ArrayList<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            final File f = new File(args[i + 1]);
            switch (args[i]) {
                case "--dir": dir = f; break;
                case "--media": media = f; break;
                case "--easyroms": easyroms = args[i + 1].isEmpty() ? null : f; break;
                case "--usb": if (!args[i + 1].isEmpty()) usb.add(f); break;
                case "--ra-data": raData = f; break;
                case "--ppsspp-data": ppsspp = f; break;
                default: break;
            }
        }
        if (dir == null || media == null) {
            System.err.println("usage: Engine --dir DIR --media DIR [--easyroms DIR] [--usb DIR]"
                    + " [--ra-data DIR] [--ppsspp-data DIR]");
            return 2;
        }
        if (raData == null) raData = new File("/data/user/0/com.retroarch");
        if (ppsspp == null) ppsspp = new File("/data/user/0/org.ppsspp.ppsspp");
        final Roots roots = new Roots(media, easyroms, usb, raData, ppsspp);
        final Map<String, String> req = Index.keyValues(read(new File(dir, "request")));
        final Engine e = new Engine(dir, roots, req);
        String outcome;
        try {
            outcome = e.dispatch();
        } catch (Throwable t) {
            t.printStackTrace();
            outcome = "error crashed";
        }
        if (outcome.startsWith("error") && "backup".equals(e.req("action"))
                && !"usb".equals(e.req("reason"))) {
            // keep the last good backup's details, but say the last try failed
            final Index.Last last = Index.Last.parse(read(new File(dir, "last")));
            last.result = outcome;
            try {
                e.writeLast(last);
            } catch (IOException ex) {
                System.err.println("can't write last: " + ex);
            }
        }
        e.writeTouched();
        e.status(outcome);
        return outcome.startsWith("done") ? 0 : 1;
    }

    String dispatch() throws IOException {
        final String action = req("action");
        switch (action) {
            case "backup":
                return backup(req("reason"), keep(), "1".equals(req("usb")), null);
            case "list":
                return list();
            case "plan":
                return plan();
            case "restore":
                return restore();
            default:
                return "error bad_request";
        }
    }

    private String req(String key) {
        final String v = mRequest.get(key);
        return v != null ? v.trim() : "";
    }

    private int keep() {
        final long k = Index.num(req("keep"));
        return k >= 1 && k <= 100 ? (int) k : DEFAULT_KEEP;
    }

    // ---- backup ------------------------------------------------------------------------

    /**
     * Backs the saves up unless nothing changed since the last backup, to the internal
     * storage and EASYROMS, and to USB drives that are plugged in.
     *
     * @param usbAlways copy the newest backup to the USB drives even if nothing changed
     * @param protect   a backup that pruning must keep (the one being restored)
     */
    String backup(String reason, int keep, boolean usbAlways, String protect)
            throws IOException {
        status("running scan");
        final List<Roots.Place> places = mRoots.places(true);
        if (places.isEmpty()) return "error no_storage";
        if (usbAlways && mRoots.usb.isEmpty()) return "error no_usb";
        for (Roots.Place p : places) Archive.cleanTemp(p.dir);

        final List<SaveFile> saves = new Collector(mRoots).collect();
        final Index.Last last = Index.Last.parse(read(new File(mDir, "last")));
        if (saves.isEmpty()) {
            last.checked = mNow;
            last.result = "nothing";
            writeLast(last);
            return "done nothing";
        }

        final String fp = Manifest.fingerprint(saves);
        final File newest = find(last.name, places);
        if (newest != null && fp.equals(read(new File(mDir, "last.fp")))) {
            // nothing changed: make sure every place has the newest backup, though
            status("running copy");
            for (Roots.Place p : places) {
                if (p.where.equals(Roots.USB) && !usbAlways && !reason.equals("usb")) {
                    // an unchanged automatic run doesn't write to USB drives
                    continue;
                }
                copyTo(newest, p);
                prune(p, keep, protect);
            }
            last.checked = mNow;
            last.result = "unchanged";
            last.where = whereOf(last.name, places);
            writeLast(last);
            return "done unchanged " + last.name;
        }

        long total = 0;
        for (SaveFile s : saves) total += s.size;
        final List<File> dirs = new ArrayList<>();
        for (Roots.Place p : places) dirs.add(p.dir);
        final String name = Archive.name(mNow, Archive.maxSeq(dirs) + 1);

        File written = null;
        Manifest m = null;
        boolean room = false;
        for (Roots.Place p : places) {
            if (free(p.dir) < total + MARGIN) continue;
            room = true;
            if (!mkdirs(p.dir)) continue;
            final File out = new File(p.dir, name);
            try {
                m = Archive.write(saves, out, mNow, reason.isEmpty() ? "manual" : reason,
                        (done, all) -> progress("running backup " + done + " " + all));
                written = out;
                touch(out);
                break;
            } catch (IOException e) {
                System.err.println("backup to " + p.dir + " failed: " + e);
            }
        }
        if (written == null) return room ? "error write_failed" : "error no_space";

        status("running copy");
        for (Roots.Place p : places) {
            if (!new File(p.dir, name).equals(written)) copyTo(written, p);
            prune(p, keep, protect);
        }
        last.time = mNow;
        last.name = name;
        last.files = m.entries.size();
        last.bytes = m.bytes();
        last.where = whereOf(name, places);
        last.checked = mNow;
        last.result = "backup";
        writeLast(last);
        write(new File(mDir, "last.fp"), fp);
        return "done backup " + name + " " + m.entries.size();
    }

    private void copyTo(File zip, Roots.Place p) {
        final File out = new File(p.dir, zip.getName());
        if (out.exists() || free(p.dir) < zip.length() + MARGIN || !mkdirs(p.dir)) return;
        try {
            Archive.copy(zip, p.dir);
            touch(out);
        } catch (IOException e) {
            System.err.println("copy to " + p.dir + " failed: " + e);
        }
    }

    private static void prune(Roots.Place p, int keep, String protect) {
        Archive.prune(p.dir, keep, protect);
    }

    private static File find(String name, List<Roots.Place> places) {
        if (name == null || name.isEmpty()) return null;
        for (Roots.Place p : places) {
            final File f = new File(p.dir, name);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static String whereOf(String name, List<Roots.Place> places) {
        final List<String> w = new ArrayList<>();
        for (Roots.Place p : places) {
            if (new File(p.dir, name).isFile() && !w.contains(p.where)) w.add(p.where);
        }
        return String.join(",", w);
    }

    /** Free space for a folder that may not exist yet. */
    private static long free(File dir) {
        File d = dir;
        while (d != null && !d.exists()) d = d.getParentFile();
        return d != null ? d.getUsableSpace() : 0;
    }

    // ---- list, plan, restore -----------------------------------------------------------

    /** Every backup in every place, newest first, with its games. */
    String list() throws IOException {
        status("running list");
        final Map<String, List<File>> copies = new LinkedHashMap<>();
        final Map<String, List<String>> where = new LinkedHashMap<>();
        final List<File> all = new ArrayList<>();
        for (Roots.Place p : mRoots.places(true)) {
            for (File f : Archive.list(p.dir)) {
                all.add(f);
                copies.computeIfAbsent(f.getName(), k -> new ArrayList<>()).add(f);
                final List<String> w = where.computeIfAbsent(f.getName(), k -> new ArrayList<>());
                if (!w.contains(p.where)) w.add(p.where);
            }
        }
        all.sort((a, b) -> Integer.compare(Archive.seq(b.getName()), Archive.seq(a.getName())));
        final StringBuilder out = new StringBuilder();
        final List<String> done = new ArrayList<>();
        for (File f : all) {
            final String name = f.getName();
            if (done.contains(name)) continue;
            done.add(name);
            Manifest m = null;
            for (File c : copies.get(name)) {
                try {
                    m = Archive.readManifest(c);
                    break;
                } catch (IOException e) {
                    // try the other copy
                }
            }
            if (m == null) {
                out.append(Index.brokenLine(name, f.length(), where.get(name)));
                continue;
            }
            out.append(Index.backupLine(name, m, f.length(), where.get(name)));
            for (Index.Game g : Index.games(m)) out.append(Index.gameLine(g));
        }
        write(new File(mDir, "index"), out.toString());
        return "done list";
    }

    /** The first copy of a backup that can be read, with its manifest. */
    private File locate(String name, Manifest[] manifest) {
        if (Archive.seq(name) < 0) return null;
        for (Roots.Place p : mRoots.places(true)) {
            final File f = new File(p.dir, name);
            if (!f.isFile()) continue;
            try {
                manifest[0] = Archive.readManifest(f);
                return f;
            } catch (IOException e) {
                // try the next copy
            }
        }
        return null;
    }

    private String whereIs(File zip) {
        for (Roots.Place p : mRoots.places(true)) {
            if (zip.getParentFile().equals(p.dir)) return p.where;
        }
        return "";
    }

    String plan() throws IOException {
        status("running plan");
        final Manifest[] m = new Manifest[1];
        final File zip = locate(req("zip"), m);
        if (zip == null) return "error not_found";
        final String game = req("game");
        final StringBuilder out = new StringBuilder();
        out.append("zip\t").append(zip.getName()).append('\t').append(whereIs(zip)).append('\n');
        for (Restorer.Item it : Restorer.plan(mRoots, m[0], game.isEmpty() ? null : game)) {
            out.append(Index.planLine(it));
        }
        write(new File(mDir, "plan"), out.toString());
        return "done plan";
    }

    /**
     * Restores a backup (or one game of it). What would be replaced is backed up first; a
     * save that's newer on the console is only replaced with newer=1.
     */
    String restore() throws IOException {
        final Manifest[] m = new Manifest[1];
        final String name = req("zip");
        final File zip = locate(name, m);
        if (zip == null) return "error not_found";
        final String game = req("game");
        final boolean newer = "1".equals(req("newer"));
        List<Restorer.Item> plan = Restorer.plan(mRoots, m[0], game.isEmpty() ? null : game);
        if (Restorer.overwrites(plan, newer)) {
            final String r = backup("before-restore", keep(), false, zip.getName());
            if (r.startsWith("error")) return "error safety_failed";
            // the backup may have fixed up places; look at the files once more
            plan = Restorer.plan(mRoots, m[0], game.isEmpty() ? null : game);
        }
        status("running restore");
        final Restorer.Result res = Restorer.apply(zip, plan, newer);
        for (File f : res.touched) touch(f);
        write(new File(mDir, "result"), "restored=" + res.restored + "\nkept=" + res.kept
                + "\nsame=" + res.same + "\nfailed=" + res.failed + "\nunavailable="
                + res.unavailable + "\n");
        return "done restore " + res.restored + " " + res.kept + " " + res.failed;
    }

    // ---- files -------------------------------------------------------------------------

    private boolean mkdirs(File dir) {
        final List<File> made = new ArrayList<>();
        final boolean ok = Archive.mkdirs(dir, made);
        for (File f : made) touch(f);
        return ok;
    }

    /** Remembers a file or folder we made on the internal storage (the helper chowns it). */
    private void touch(File f) {
        if (mRoots.idOf(f) != null && mRoots.idOf(f).startsWith(Roots.INTERNAL + "/")) {
            mTouched.add(f);
        }
    }

    private void writeTouched() {
        final StringBuilder b = new StringBuilder();
        for (File f : mTouched) b.append(f.getPath()).append('\n');
        try {
            write(new File(mDir, "touched"), b.toString());
        } catch (IOException e) {
            System.err.println("can't write touched: " + e);
        }
    }

    private void writeLast(Index.Last last) throws IOException {
        write(new File(mDir, "last"), last.text());
    }

    /** Progress lines, at most two a second. */
    private void progress(String line) {
        final long t = System.nanoTime() / 1000000;
        if (t - mLastProgress < 500) return;
        mLastProgress = t;
        status(line);
    }

    void status(String line) {
        try {
            write(new File(mDir, "status"), line + "\n");
        } catch (IOException e) {
            System.err.println("can't write status: " + e);
        }
    }

    /** Writes via a temp file, so the app never reads half a file. */
    static void write(File f, String text) throws IOException {
        final File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("can't write " + f);
        }
    }

    static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }
}
