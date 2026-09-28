package org.andr36oid.savebackup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Host test of Save backup: fake saves in a temp dir (internal storage, EASYROMS, a USB
 * drive, RetroArch's and PPSSPP's folders), the real helper script run with the given shell
 * and the engine run with java in place of app_process.
 *
 *   tests/run-tests.sh
 */
public class EngineTest {

    static int failures;
    static File root, media, easyroms, usb, dir, raData, ppsspp;
    static String helper, shell, classes, java;
    static final long DAY = 86400000L;
    static final long T0 = 1790000000000L; // 2026-09-21
    static long clock = T0;

    public static void main(String[] args) throws Exception {
        helper = args[0];
        shell = args[1];
        classes = args[2];
        java = args[3];
        try {
            units();
            scenario();
        } finally {
            if (root != null) delete(root);
        }
        System.out.println(failures == 0 ? "ALL PASSED (" + shell + ")"
                : failures + " FAILED (" + shell + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    // ---- small parts -------------------------------------------------------------------

    static void units() throws IOException {
        check(Games.baseName("Zelda (USA).srm").equals("Zelda (USA)"), "base name of .srm");
        check(Games.baseName("Zelda (USA).state.auto").equals("Zelda (USA)"),
                "base name of .state.auto");
        check(Games.baseName("Zelda.v1.1.state12").equals("Zelda.v1.1"), "base name of .state12");
        check(Games.baseName("Zelda.state3.png").equals("Zelda"), "base name of a thumbnail");
        check(Games.isSaveName("a.SRM") && Games.isSaveName("a.state") && Games.isSaveName("a.state9")
                && Games.isSaveName("a.state.auto") && Games.isSaveName("x.mcd"),
                "save names are found by ending, any case");
        check(!Games.isSaveName("a.gba") && !Games.isSaveName("a.png") && !Games.isSaveName(".srm")
                && !Games.isSaveName("statement.pdf"), "ROMs and pictures aren't saves");
        check("Test Game: Portable".equals(Games.sfoTitle(sfo("Test Game: Portable"))),
                "PSP title from PARAM.SFO");
        check(Games.sfoTitle(new byte[] {1, 2, 3}) == null, "a broken PARAM.SFO gives no title");
        check(Games.pspId("ULUS10041DATA00").equals("ULUS10041"), "PSP ID from the folder name");

        check(!Roots.safeRelative("a/../b") && !Roots.safeRelative("/etc/passwd")
                && !Roots.safeRelative("a//b") && !Roots.safeRelative("..")
                && !Roots.safeRelative("a\nb") && Roots.safeRelative("gba/Zelda.srm"),
                "unsafe relative paths are refused");
        final File t = Files.createTempDirectory("sbu").toFile();
        try {
            final File m = new File(t, "media"), e = new File(t, "ABCD-1234");
            m.mkdirs();
            e.mkdirs();
            final Roots r = new Roots(m, e, null, new File(t, "ra"), new File(t, "pp"));
            check(r.resolve("internal/../x") == null && r.resolve("easyroms/a/../../x") == null
                    && r.resolve("other/x") == null && r.resolve("internal") == null,
                    "resolve refuses .., unknown areas and bare areas");
            check(new File(e, "gba/Z.srm").equals(r.resolve("easyroms/gba/Z.srm")),
                    "resolve maps easyroms/ to the card");
            check(new File(m, "PSP").equals(r.lower(
                    "content://com.android.externalstorage.documents/tree/primary%3APSP")),
                    "PPSSPP's content:// folder on the internal storage");
            check(new File(e, "psp").equals(r.lower(
                    "content://com.android.externalstorage.documents/tree/ABCD-1234%3Apsp")),
                    "PPSSPP's content:// folder on EASYROMS");
            check(new File(e, "saves").equals(r.lower("/storage/ABCD-1234/saves"))
                    && new File(m, "RetroArch/saves").equals(r.lower("/sdcard/RetroArch/saves/"))
                    && r.lower("/storage/9999-0000/x") == null,
                    "retroarch.cfg paths as root sees them");
            // a link out of the area is refused
            final File outside = new File(t, "outside");
            outside.mkdirs();
            Files.createSymbolicLink(new File(m, "link").toPath(), outside.toPath());
            check(r.resolve("internal/link/x.srm") == null, "resolve refuses links out of the area");

            // pruning: newest of each of the last keep/2 days, then the newest
            final File pd = new File(t, "prune");
            pd.mkdirs();
            final String[] names = {
                "saves-20260920-100000-1.zip", "saves-20260921-100000-2.zip",
                "saves-20260922-100000-3.zip", "saves-20260922-110000-4.zip",
                "saves-20260923-090000-5.zip", "saves-20260923-100000-6.zip",
                "saves-20260923-110000-7.zip", "saves-20260923-120000-8.zip",
                "saves-20260923-130000-9.zip", "saves-20260923-140000-10.zip",
                "saves-19700101-000000-11.zip",   // clock was wrong: still the newest
                "notes.zip",
            };
            for (String n : names) new File(pd, n).createNewFile();
            Archive.prune(pd, 6, "saves-20260920-100000-1.zip");
            final List<String> left = new ArrayList<>(Arrays.asList(pd.list()));
            left.sort(null);
            check(left.equals(Arrays.asList("notes.zip", "saves-19700101-000000-11.zip",
                    "saves-20260920-100000-1.zip", "saves-20260922-110000-4.zip",
                    "saves-20260923-110000-7.zip", "saves-20260923-120000-8.zip", "saves-20260923-130000-9.zip",
                    "saves-20260923-140000-10.zip")),
                    "prune keeps 6 (days first, by number not date), the protected one and"
                    + " other files: " + left);
        } finally {
            delete(t);
        }
    }

    // ---- the whole thing, through the helper -------------------------------------------

    static void scenario() throws Exception {
        root = Files.createTempDirectory("savebackup").toFile();
        media = new File(root, "media");
        easyroms = new File(root, "1234-ABCD");
        usb = new File(root, "5678-EFAB");
        dir = new File(root, "misc");
        raData = new File(root, "ra");
        ppsspp = new File(root, "ppsspp");
        for (File f : new File[] {media, easyroms, usb, dir, raData, ppsspp}) f.mkdirs();

        // internal storage: RetroArch's default folders, PPSSPP's memory stick
        put(media, "RetroArch/saves/Pokemon Emerald.srm", "pokemon-v1", -50);
        put(media, "RetroArch/states/Pokemon Emerald.state1", "pokemon-state", -50);
        put(media, "RetroArch/states/Pokemon Emerald.state1.png", "png", -50);
        put(media, "PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN", "psp-data", -40);
        putBytes(media, "PSP/SAVEDATA/ULUS10041DATA00/PARAM.SFO", sfo("Test Game"), -40);
        put(media, "PSP/PPSSPP_STATE/ULUS10041_1.00_0.ppst", "psp-state", -40);
        put(media, "Download/manual.pdf", "not a save", -40);
        put(media, "Download/old-game.sav", "a save somewhere else", -40);
        put(media, "Android/data/other.app/files/x.sav", "another app", -40);
        put(media, "Android/data/org.ppsspp.ppsspp/files/PSP/SAVEDATA/NPJH50001X/DATA.BIN",
                "psp2", -40);
        // retroarch.cfg moves the save states to a folder on EASYROMS
        put(media, "Android/data/com.retroarch/files/retroarch.cfg",
                "savefile_directory = \"default\"\nsavestate_directory = \""
                        + "/storage/1234-ABCD/ra-states\"\n", -40);
        put(easyroms, "ra-states/Metroid.custom", "metroid-any-name", -40);

        // EASYROMS: saves next to the ROMs, and things that must stay out
        put(easyroms, "gba/Zelda.gba", "rom", -60);
        put(easyroms, "gba/Zelda.srm", "zelda-v1", -30);
        put(easyroms, "psx/FF7.srm", "ff7-v1", -30);
        put(easyroms, "psx/FF7.state.auto", "ff7-state", -30);
        put(easyroms, "bios/scph5501.bin", "bios", -60);
        put(easyroms, ".hidden/secret.srm", "hidden", -60);
        put(easyroms, "backups/other.srm", "inside backups", -60);

        // 1. first backup
        String st = run("action=backup\nreason=manual\nkeep=10");
        check(st.startsWith("done backup "), "first backup: " + st);
        List<File> in = Archive.list(new File(media, "Backups/Saves"));
        List<File> er = Archive.list(new File(easyroms, "backups/saves"));
        check(in.size() == 1 && er.size() == 1 && in.get(0).getName().equals(er.get(0).getName()),
                "first backup: one zip on the internal storage and one on EASYROMS");
        check(Archive.list(new File(usb, "Backups/Saves")).size() == 1,
                "first backup: also on the plugged in USB drive");
        final String first = in.get(0).getName();
        Manifest m = Archive.readManifest(in.get(0));
        final List<String> ids = new ArrayList<>();
        for (Manifest.Entry e : m.entries) ids.add(e.id);
        final List<String> want = Arrays.asList(
                "easyroms/gba/Zelda.srm", "easyroms/psx/FF7.srm", "easyroms/psx/FF7.state.auto",
                "easyroms/ra-states/Metroid.custom",
                "internal/Android/data/org.ppsspp.ppsspp/files/PSP/SAVEDATA/NPJH50001X/DATA.BIN",
                "internal/Download/old-game.sav",
                "internal/PSP/PPSSPP_STATE/ULUS10041_1.00_0.ppst",
                "internal/PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN",
                "internal/PSP/SAVEDATA/ULUS10041DATA00/PARAM.SFO",
                "internal/RetroArch/saves/Pokemon Emerald.srm",
                "internal/RetroArch/states/Pokemon Emerald.state1",
                "internal/RetroArch/states/Pokemon Emerald.state1.png");
        check(ids.equals(want), "first backup: exactly the saves (no ROM, BIOS, PDF, other apps,"
                + " hidden or backup folders): " + ids);
        final Index.Last last = Index.Last.parse(read(new File(dir, "last")));
        check(last.name.equals(first) && last.files == 12 && last.result.equals("backup")
                && last.where.equals("internal,easyroms,usb"),
                "first backup: last = " + last.text().replace('\n', ' '));

        // the index: per game, with the PSP title from PARAM.SFO
        st = run("action=list");
        List<Index.Backup> idx = Index.parseIndex(read(new File(dir, "index")));
        check(st.equals("done list") && idx.size() == 1 && idx.get(0).ok
                && idx.get(0).files == 12, "list: one backup, 12 files");
        final Index.Game psp = game(idx.get(0), "psp:ULUS10041");
        check(psp != null && psp.label.equals("Test Game") && psp.files == 3,
                "list: the PSP save and state are one game with its title");
        final Index.Game pk = game(idx.get(0), "game:pokemon emerald");
        check(pk != null && pk.files == 3 && pk.folders.contains("saves")
                && pk.folders.contains("states"), "list: Pokemon's save, state and thumbnail");

        // 2. nothing changed: skipped
        clock += 3600000;
        st = run("action=backup\nreason=daily");
        check(st.startsWith("done unchanged"), "unchanged: skipped: " + st);
        check(Archive.list(new File(media, "Backups/Saves")).size() == 1,
                "unchanged: no new zip");
        check(Index.Last.parse(read(new File(dir, "last"))).checked == clock,
                "unchanged: the check time moves on");

        // 3. a copy got lost: an unchanged run puts it back
        new File(easyroms, "backups/saves/" + first).delete();
        st = run("action=backup\nreason=daily");
        check(st.startsWith("done unchanged") && new File(easyroms, "backups/saves/" + first).isFile(),
                "unchanged: the lost EASYROMS copy is made again");

        // 4. a change is found
        clock += 3600000;
        put(easyroms, "gba/Zelda.srm", "zelda-v2", -1);
        st = run("action=backup\nreason=session");
        in = Archive.list(new File(media, "Backups/Saves"));
        check(st.startsWith("done backup") && in.size() == 2, "changed: a second backup: " + st);
        m = Archive.readManifest(in.get(0));
        check(entry(m, "easyroms/gba/Zelda.srm").sha1.equals(sha1("zelda-v2")),
                "changed: the new backup has the new save");
        final String second = in.get(0).getName();

        // 5. prune to N: many changes over days, keep 4
        for (int i = 0; i < 8; i++) {
            clock += DAY / 2;
            put(easyroms, "psx/FF7.srm", "ff7-v" + (i + 2), -1);
            run("action=backup\nreason=daily\nkeep=4");
        }
        in = Archive.list(new File(media, "Backups/Saves"));
        er = Archive.list(new File(easyroms, "backups/saves"));
        check(in.size() == 4 && er.size() == 4, "prune: 4 kept on each partition ("
                + in.size() + ", " + er.size() + ")");
        check(Archive.list(new File(usb, "Backups/Saves")).size() == 4, "prune: and on USB");
        check(!new File(media, "Backups/Saves/" + first).exists(), "prune: the oldest is gone");

        // put everything back to a known state
        put(easyroms, "psx/FF7.srm", "ff7-v1", -30);
        run("action=backup\nreason=manual\nkeep=10");
        final String baseline = Archive.list(new File(media, "Backups/Saves")).get(0).getName();

        // 6. restore one game: a newer save on the console is not replaced without a yes
        put(easyroms, "psx/FF7.srm", "ff7-newer", +1);
        st = run("action=plan\nzip=" + baseline + "\ngame=game:ff7");
        Index.Plan plan = Index.parsePlan(read(new File(dir, "plan")));
        check(st.equals("done plan") && plan.items.size() == 2 && plan.count(Restorer.NEWER) == 1
                && plan.count(Restorer.SAME) == 1, "plan: FF7 save newer on the console, state"
                + " the same");
        final int before = Archive.list(new File(media, "Backups/Saves")).size();
        st = run("action=restore\nzip=" + baseline + "\ngame=game:ff7\nnewer=0");
        check(st.equals("done restore 0 1 0") && read(new File(easyroms, "psx/FF7.srm"))
                .equals("ff7-newer"), "restore without yes: the newer save is kept: " + st);
        check(Archive.list(new File(media, "Backups/Saves")).size() == before,
                "restore without yes: nothing replaced, so no safety backup");

        // the user says yes: the newer one is backed up first, then replaced
        st = run("action=restore\nzip=" + baseline + "\ngame=game:ff7\nnewer=1");
        check(st.equals("done restore 1 0 0") && read(new File(easyroms, "psx/FF7.srm"))
                .equals("ff7-v1"), "restore with yes: replaced: " + st);
        in = Archive.list(new File(media, "Backups/Saves"));
        check(in.size() == before + 1 && entry(Archive.readManifest(in.get(0)),
                "easyroms/psx/FF7.srm").sha1.equals(sha1("ff7-newer")),
                "restore with yes: the replaced save is in a new backup first");
        check(new File(easyroms, "psx/FF7.srm").lastModified() == clock - 30 * 60000L,
                "restore: the file gets its time from the backup");
        check(read(new File(easyroms, "gba/Zelda.srm")).equals("zelda-v2"),
                "restore one game: other games untouched");

        // 7. a missing and an older file are restored
        new File(media, "PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN").delete();
        put(media, "PSP/PPSSPP_STATE/ULUS10041_1.00_0.ppst", "psp-state-broken", 0);
        new File(media, "PSP/PPSSPP_STATE/ULUS10041_1.00_0.ppst").setLastModified(T0 - DAY);
        run("action=plan\nzip=" + baseline + "\ngame=psp:ULUS10041");
        plan = Index.parsePlan(read(new File(dir, "plan")));
        check(plan.count(Restorer.MISSING) == 1 && plan.count(Restorer.OLDER) == 1
                && plan.count(Restorer.SAME) == 1, "plan: PSP save missing, state older");
        st = run("action=restore\nzip=" + baseline + "\ngame=psp:ULUS10041\nnewer=0");
        check(st.equals("done restore 2 0 0")
                && read(new File(media, "PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN")).equals("psp-data")
                && read(new File(media, "PSP/PPSSPP_STATE/ULUS10041_1.00_0.ppst"))
                        .equals("psp-state"), "restore: missing and older files back: " + st);
        check(!new File(dir, "touched").exists(), "helper: the owner list is handled and removed");

        // 8. EASYROMS not mounted
        final String keepEasy = easyroms.getPath();
        easyroms = null;
        put(media, "RetroArch/saves/Pokemon Emerald.srm", "pokemon-v2", -1);
        st = run("action=backup\nreason=daily");
        check(st.startsWith("done backup"), "no EASYROMS: still backs up the internal saves");
        final Index.Last l2 = Index.Last.parse(read(new File(dir, "last")));
        check(l2.where.equals("internal,usb"), "no EASYROMS: internal and USB only: " + l2.where);
        run("action=plan\nzip=" + baseline);
        plan = Index.parsePlan(read(new File(dir, "plan")));
        check(plan.count(Restorer.UNAVAILABLE) == 4, "no EASYROMS: its saves can't be restored"
                + " now (" + plan.count(Restorer.UNAVAILABLE) + ")");
        easyroms = new File(keepEasy);

        // 9. USB: back up now to USB, and without a drive
        st = run("action=backup\nreason=usb\nusb=1");
        check(st.startsWith("done") && new File(usb, "Backups/Saves/"
                + Index.Last.parse(read(new File(dir, "last"))).name).isFile(),
                "USB: the newest backup is on the drive: " + st);
        final File keepUsb = usb;
        usb = null;
        st = run("action=backup\nreason=usb\nusb=1");
        check(st.equals("error no_usb"), "USB: no drive, says so: " + st);
        usb = keepUsb;

        // 10. a backup whose manifest points outside: nothing is written there
        final File evil = new File(media, "Backups/Saves/saves-20260101-000000-999.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(evil))) {
            final String entry = "internal/../../escaped.srm";
            z.putNextEntry(new ZipEntry(entry));
            z.write("evil".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("manifest.txt"));
            z.write(("# andr36oid save backup 1\ncreated\t1\nfile\t" + entry + "\t4\t1\t"
                    + sha1("evil") + "\tgame:x\tx\tx\n").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        st = run("action=restore\nzip=" + evil.getName() + "\nnewer=1");
        check(!new File(root, "escaped.srm").exists() && !new File(root.getParentFile(),
                "escaped.srm").exists(), "restore: a path out of the storage is refused: " + st);
        evil.delete();

        // 11. a damaged backup is listed as such; its other copy is used
        final String dmg = Archive.list(new File(media, "Backups/Saves")).get(0).getName();
        Files.write(new File(media, "Backups/Saves/" + dmg).toPath(), new byte[] {1, 2, 3});
        run("action=list");
        idx = Index.parseIndex(read(new File(dir, "index")));
        check(idx.get(0).name.equals(dmg) && idx.get(0).ok,
                "damaged: the internal copy is broken, the EASYROMS copy is read");
        st = run("action=restore\nzip=nonsense.zip");
        check(st.equals("error not_found"), "restore: an unknown backup: " + st);
        st = run("action=dance");
        check(st.equals("error bad_request"), "a bad request is refused");

        // 12. no saves at all
        final File empty = new File(root, "empty");
        empty.mkdirs();
        final File keepMedia = media;
        media = empty;
        easyroms = null;
        st = run("action=backup\nreason=daily");
        check(st.equals("done nothing"), "no saves: nothing to back up: " + st);

        // 13. nowhere to write: the failure is kept for the summary, the last backup too
        media = new File(root, "gone");
        usb = null;
        st = run("action=backup\nreason=daily");
        final Index.Last failed = Index.Last.parse(read(new File(dir, "last")));
        check(st.equals("error no_storage") && failed.result.equals("error no_storage")
                && failed.time > 0, "no storage: the failure is remembered: " + st);
        media = keepMedia;
        easyroms = new File(keepEasy);
    }

    // ---- helpers -----------------------------------------------------------------------

    static String run(String request) throws Exception {
        Engine.write(new File(dir, "request"), request + "\nnow=" + clock + "\n");
        new File(dir, "status").delete();
        // "busybox" runs its ash, the closest thing to the console's mksh on a PC
        final ProcessBuilder pb = shell.equals("busybox")
                ? new ProcessBuilder("busybox", "sh", helper) : new ProcessBuilder(shell, helper);
        pb.environment().put("SAVEBACKUP_RUN", java + " -cp " + classes);
        pb.environment().put("SAVEBACKUP_DIR", dir.getPath());
        pb.environment().put("SAVEBACKUP_MEDIA", media.getPath());
        pb.environment().put("SAVEBACKUP_RA_DATA", raData.getPath());
        pb.environment().put("SAVEBACKUP_PPSSPP_DATA", ppsspp.getPath());
        pb.environment().put("SAVEBACKUP_EASYROMS", easyroms != null ? easyroms.getPath() : "");
        pb.environment().put("SAVEBACKUP_USB", usb != null ? usb.getPath() : "");
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        p.waitFor();
        final String s = read(new File(dir, "status"));
        if (s == null || !(s.startsWith("done") || s.startsWith("error"))) {
            System.out.println(out.toString(StandardCharsets.UTF_8));
        }
        return s == null ? "" : s.trim();
    }

    /** A file with content and a time relative to the test clock, in minutes. */
    static void put(File base, String rel, String content, int minutes) throws IOException {
        putBytes(base, rel, content.getBytes(StandardCharsets.UTF_8), minutes);
    }

    static void putBytes(File base, String rel, byte[] content, int minutes) throws IOException {
        final File f = new File(base, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content);
        f.setLastModified(clock + minutes * 60000L);
    }

    static String read(File f) {
        return Engine.read(f);
    }

    static Index.Game game(Index.Backup b, String key) {
        for (Index.Game g : b.games) {
            if (g.key.equals(key)) return g;
        }
        return null;
    }

    static Manifest.Entry entry(Manifest m, String id) {
        for (Manifest.Entry e : m.entries) {
            if (e.id.equals(id)) return e;
        }
        throw new AssertionError("not in the backup: " + id);
    }

    static String sha1(String s) {
        return Archive.hex(Archive.sha1().digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    /** A PARAM.SFO with a CATEGORY and a TITLE. */
    static byte[] sfo(String title) {
        final byte[] t = title.getBytes(StandardCharsets.UTF_8);
        final String keys = "CATEGORY\0TITLE\0\0";
        final int keyStart = 20 + 2 * 16;
        final int dataStart = keyStart + keys.length();
        final int titleMax = 128;
        final byte[] b = new byte[dataStart + 4 + titleMax];
        b[1] = 'P';
        b[2] = 'S';
        b[3] = 'F';
        le(b, 4, 0x101);
        le(b, 8, keyStart);
        le(b, 12, dataStart);
        le(b, 16, 2);
        // CATEGORY = "MS" at data 0 (4 bytes)
        b[20] = 0;
        b[22] = 4;
        b[23] = 2;
        le(b, 24, 3);
        le(b, 28, 4);
        le(b, 32, 0);
        // TITLE at data 4
        b[36] = 9;
        b[38] = 4;
        b[39] = 2;
        le(b, 40, t.length + 1);
        le(b, 44, titleMax);
        le(b, 48, 4);
        System.arraycopy(keys.getBytes(StandardCharsets.US_ASCII), 0, b, keyStart, keys.length());
        b[dataStart] = 'M';
        b[dataStart + 1] = 'S';
        System.arraycopy(t, 0, b, dataStart + 4, t.length);
        return b;
    }

    static void le(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    static void delete(File f) {
        final File[] children = f.listFiles();
        if (children != null && !Files.isSymbolicLink(f.toPath())) {
            for (File c : children) delete(c);
        }
        f.delete();
    }
}
