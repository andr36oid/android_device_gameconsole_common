package org.andr36oid.storagecare;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Host test of Free up space: the rules on their own, then a fake internal storage and a
 * fake EASYROMS, scanned and cleaned through the real helper script (run with the given
 * shell) with java in place of app_process.
 *
 *   tests/run-tests.sh
 */
public class EngineTest {

    static int failures;
    static File root, media, easyroms, dir;
    static String helper, shell, classes, java;
    static final long T0 = 1790000000000L; // 2026-09-21
    static final long MIN = 60000L;

    public static void main(String[] args) throws Exception {
        helper = args[0];
        shell = args[1];
        classes = args[2];
        java = args[3];
        try {
            rules();
            space();
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

    // ---- rules -------------------------------------------------------------------------

    static void rules() {
        for (String n : new String[] {"Zelda.srm", "Zelda.SAV", "Pokemon.rtc", "x.eep", "x.mcd",
                "x.mcr", "x.dsv", "x.fla", "x.sra", "x.mpk"}) {
            check(Rules.isBatterySave(n), "battery save: " + n);
            check(Rules.state(n, true) == null, "a battery save is never a state: " + n);
        }
        check(!Rules.isBatterySave(".srm"), "a bare .srm is no name");
        check(Rules.state("Zelda.state", false) != null && Rules.state("Zelda.STATE12", false) != null,
                "RetroArch states, any slot, any case");
        check(Rules.state("Zelda.state3.png", false).picture, "a state picture");
        check(Rules.state("Zelda.state.auto", false) == null, ".state.auto is never picked");
        check(Rules.state("Zelda.gba", false) == null && Rules.state("statement.pdf", false) == null,
                "ROMs and documents aren't states");
        check(Rules.state("ULUS10041_1.00_0.ppst", true) != null
                && Rules.state("ULUS10041_1.00_undo.ppst", true) != null,
                "PPSSPP states in PPSSPP_STATE");
        check(Rules.state("IMG_0001.jpg", false) == null && Rules.state("ULUS10041_1.00_0.ppst", false) == null,
                "PPSSPP names count only in PPSSPP_STATE (photos stay)");
        check("ULUS10041_1.00".equals(Rules.state("ULUS10041_1.00_0.jpg", true).game),
                "PPSSPP game from the state name");

        final List<Rules.Entry> dir = Arrays.asList(
                e("Zelda.state", 1), e("Zelda.state1", 5), e("Zelda.state1.png", 5),
                e("Zelda.state2", 3), e("Zelda.state2.png", 3), e("Zelda.state3", 4),
                e("Zelda.state.auto", 0), e("Zelda.srm", 0), e("Zelda.gba", 0),
                e("Mario.state", 2), e("Mario.state5.png", 1));
        final Set<String> old = Rules.oldStates(dir, 2, false);
        check(old.equals(set("Zelda.state", "Zelda.state2", "Zelda.state2.png", "Mario.state5.png")),
                "keep 2: the two oldest Zelda slots with their picture, and a picture without a state "
                        + old);
        check(Rules.oldStates(dir, 0, false).equals(Rules.oldStates(dir, 1, false)),
                "keep is at least 1");
        check(Rules.oldStates(dir, 10, false).equals(set("Mario.state5.png")),
                "keep 10: only the lost picture");
        check(!Rules.oldStates(dir, 1, false).contains("Mario.state"),
                "a game's only state is kept");
        final List<Rules.Entry> tie = Arrays.asList(e("A.state", 7), e("A.state1", 7), e("A.state2", 7));
        check(Rules.oldStates(tie, 1, false).equals(set("A.state1", "A.state2")),
                "same time: the order is fixed (by slot)");

        final List<String> zips = Arrays.asList("saves-20260101-000000-9.zip",
                "saves-19700101-000100-10.zip", "saves-20260102-000000-8.zip", "other.zip",
                "saves-20260101-000000-7.zip.tmp");
        check(Rules.oldBackups(zips, 2).equals(set("saves-20260102-000000-8.zip")),
                "backups: the newest by counter, not by date; others untouched");
        check(Rules.oldBackups(zips, 0).size() == 2, "backups: keep at least 1");

        check(Rules.isApk("a.apk") && Rules.isApk("b.XAPK") && !Rules.isApk("apk") && !Rules.isApk("a.apk.txt"),
                "APK names");
        check(Rules.romCandidate("Tetris.gb", 300000) && !Rules.romCandidate("Tetris.gb", 1000)
                && !Rules.romCandidate("Tetris.srm", 300000) && !Rules.romCandidate("a.state1", 300000)
                && !Rules.romCandidate("box.png", 300000), "ROM candidates");
    }

    static Rules.Entry e(String name, long minutes) {
        return new Rules.Entry(name, T0 + minutes * MIN);
    }

    @SafeVarargs
    static <T> Set<T> set(T... a) {
        return new HashSet<>(Arrays.asList(a));
    }

    // ---- space warnings --------------------------------------------------------------

    static void space() {
        final String in = Space.INTERNAL, er = Space.EASYROMS;
        check(Space.level(in, 5 * Space.GB) == Space.OK && Space.level(in, 900 * Space.MB) == Space.LOW
                && Space.level(in, 200 * Space.MB) == Space.FULL, "internal levels");
        check(Space.level(er, 900 * Space.MB) == Space.OK && Space.level(er, 400 * Space.MB) == Space.LOW
                && Space.level(er, 100 * Space.MB) == Space.FULL, "EASYROMS levels");
        int w = Space.OK;
        int n = Space.next(in, 900 * Space.MB, w);
        check(Space.show(n) && Space.warned(n) == Space.LOW, "low: warn once");
        w = Space.warned(n);
        n = Space.next(in, 950 * Space.MB, w);
        check(!Space.show(n) && Space.warned(n) == Space.LOW, "still low: no second warning");
        n = Space.next(in, 1100 * Space.MB, w);
        check(!Space.show(n) && Space.warned(n) == Space.LOW,
                "just above the limit: stays armed off (margin)");
        n = Space.next(in, 250 * Space.MB, w);
        check(Space.show(n) && Space.warned(n) == Space.FULL, "full: warns again");
        w = Space.warned(n);
        n = Space.next(in, 700 * Space.MB, w);
        check(!Space.show(n) && Space.warned(n) == Space.LOW, "freed up to low: back to low, quiet");
        w = Space.warned(n);
        n = Space.next(in, 2 * Space.GB, w);
        check(!Space.show(n) && Space.warned(n) == Space.OK, "plenty again: cleared");
        n = Space.next(in, 900 * Space.MB, Space.warned(n));
        check(Space.show(n), "and the next time it runs low it warns again");
        n = Space.next(er, 50 * Space.MB, Space.OK);
        check(Space.show(n) && Space.warned(n) == Space.FULL, "straight to full");
    }

    // ---- the whole thing on a fake card -----------------------------------------------

    static void scenario() throws Exception {
        root = Files.createTempDirectory("storagecare").toFile().getCanonicalFile();
        media = new File(root, "media");
        easyroms = new File(root, "ABCD-1234");
        dir = new File(root, "misc");
        final File raData = new File(root, "ra");
        dir.mkdirs();
        raData.mkdirs();

        // internal storage
        put(media, "RetroArch/states/Zelda.state", 10, 1);
        put(media, "RetroArch/states/Zelda.state1", 10, 5);
        put(media, "RetroArch/states/Zelda.state1.png", 10, 5);
        put(media, "RetroArch/states/Zelda.state2", 10, 3);
        put(media, "RetroArch/states/Zelda.state3", 10, 4);
        put(media, "RetroArch/states/Zelda.state.auto", 10, 0);
        put(media, "RetroArch/saves/Zelda.srm", 10, 0);
        put(media, "RetroArch/states/Mario.state5.png", 10, 1);
        put(media, "RetroArch/thumbnails/Nintendo - Game Boy/Named_Boxarts/Tetris.png", 1000, 1);
        put(media, "RetroArch/thumbnails/Sega - Mega Drive/Named_Snaps/Sonic.png", 2000, 1);
        put(media, "RetroArch/thumbnails/Odd/keep.sav", 10, 1);
        put(media, "RetroArch/screenshots/shot1.png", 500, 1);
        put(media, "RetroArch/screenshots/notes.txt", 5, 1);
        put(media, "Pictures/Screenshots/Screenshot_1.png", 300, 1);
        put(media, "Pictures/Camera/IMG_0001.jpg", 300, 1);
        put(media, "Download/app.apk", 4000, 1);
        put(media, "Download/sub/b.xapk", 3000, 1);
        put(media, "Download/manual.pdf", 100, 1);
        put(media, "PSP/PPSSPP_STATE/ULUS10041_1.00_0.ppst", 50, 1);
        put(media, "PSP/PPSSPP_STATE/ULUS10041_1.00_0.jpg", 5, 1);
        put(media, "PSP/PPSSPP_STATE/ULUS10041_1.00_1.ppst", 50, 2);
        put(media, "PSP/PPSSPP_STATE/ULUS10041_1.00_2.ppst", 50, 3);
        put(media, "PSP/PPSSPP_STATE/ULUS10041_1.00_undo.ppst", 50, 4);
        put(media, "PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN", 50, 1);
        for (int i = 1; i <= 5; i++) put(media, "Backups/Saves/saves-20260920-12000" + i + "-" + i + ".zip", 100, i);
        put(media, "Backups/Saves/notes.zip", 100, 1);
        // a link out of the storage must never be followed
        Files.createSymbolicLink(new File(media, "RetroArch/thumbnails/link").toPath(),
                new File(root, "outside").toPath());
        put(root, "outside/precious.txt", 10, 1);

        // EASYROMS
        final byte[] tetris = bytes(300 * 1024, 1);
        put(easyroms, "gba/Zelda.gba", bytes(400 * 1024, 2), 1);
        put(easyroms, "gba/Zelda.srm", 10, 1);
        put(easyroms, "gba/Zelda.state", 10, 1);
        put(easyroms, "gba/Zelda.state1", 10, 2);
        put(easyroms, "gba/Zelda.state2", 10, 3);
        put(easyroms, "gba/Zelda.state3", 10, 4);
        put(easyroms, "gb/Tetris.gb", tetris, 1);
        put(easyroms, "gbc/Tetris.gb", tetris, 1);
        final byte[] other = tetris.clone();
        other[150 * 1024] ^= 1; // same size, same start and end, one byte different
        put(easyroms, "gbc/Tetris (hack).gb", other, 1);
        final byte[] bios = bytes(512 * 1024, 3);
        put(easyroms, "bios/scph1001.bin", bios, 1);
        put(easyroms, "psx/scph1001.bin", bios, 1);
        for (int i = 1; i <= 4; i++) put(easyroms, "backups/saves/saves-20260920-12000" + i + "-" + i + ".zip", 100, i);
        put(easyroms, "Game.apk", 100, 1);
        put(easyroms, "screenshots/gba-001.png", 100, 1);

        final File mounts = new File(root, "mounts");
        write(mounts, "/dev/block/vold/public:179,7 " + easyroms.getPath()
                + " exfat ro,dirsync,nosuid,nodev,noexec,noatime,uid=0,gid=1023 0 0\n");
        final File dmesg = new File(root, "dmesg");
        write(dmesg, "[  12.1] exFAT-fs (mmcblk0p7): error, invalid access to FAT (entry 0x0000abcd)\n"
                + "[  12.1] exFAT-fs (mmcblk0p7): Filesystem has been set read-only\n"
                + "[  13.0] exFAT-fs (sda1): error, something on a USB stick\n");
        final File vold = new File(root, "vold");
        write(vold, "09-28 10:00:01 I vold    : Check OK\n");

        final String env = "STORAGECARE_DIR=" + dir + " STORAGECARE_MEDIA=" + media
                + " STORAGECARE_RA_DATA=" + raData + " STORAGECARE_EASYROMS=" + easyroms
                + " STORAGECARE_MOUNTS=" + mounts + " STORAGECARE_DMESG=" + dmesg
                + " STORAGECARE_VOLD=" + vold;

        // scan
        write(new File(dir, "request"), "action=scan\nkeep_states=2\nkeep_backups=2\ndups=1\n");
        run(env);
        check(read(new File(dir, "status")).startsWith("done scan"), "scan finishes: "
                + read(new File(dir, "status")).trim());
        final Scan s = Scan.parse(read(new File(dir, "scan")));
        final Set<String> states = rel(s, Scan.STATES);
        check(states.equals(set("media/RetroArch/states/Zelda.state",
                "media/RetroArch/states/Zelda.state2", "media/RetroArch/states/Mario.state5.png",
                "media/PSP/PPSSPP_STATE/ULUS10041_1.00_0.ppst", "media/PSP/PPSSPP_STATE/ULUS10041_1.00_0.jpg",
                "media/PSP/PPSSPP_STATE/ULUS10041_1.00_1.ppst",
                "ABCD-1234/gba/Zelda.state", "ABCD-1234/gba/Zelda.state1")),
                "old states on both partitions, PPSSPP too " + states);
        for (Scan.Item i : s.items) {
            check(!Rules.isBatterySave(new File(i.path).getName()), "no battery save listed: " + i.path);
        }
        check(rel(s, Scan.BACKUPS).equals(set(
                "media/Backups/Saves/saves-20260920-120001-1.zip",
                "media/Backups/Saves/saves-20260920-120002-2.zip",
                "media/Backups/Saves/saves-20260920-120003-3.zip",
                "ABCD-1234/backups/saves/saves-20260920-120001-1.zip",
                "ABCD-1234/backups/saves/saves-20260920-120002-2.zip")),
                "backups beyond the newest 2 per place " + rel(s, Scan.BACKUPS));
        check(rel(s, Scan.THUMBS).equals(set("media/RetroArch/thumbnails/Nintendo - Game Boy",
                "media/RetroArch/thumbnails/Sega - Mega Drive")),
                "box art per system; not the folder with a save in it, not the link " + rel(s, Scan.THUMBS));
        check(bytesOf(s, Scan.THUMBS) == 3000, "box art sizes add up");
        check(rel(s, Scan.SHOTS).equals(set("media/RetroArch/screenshots/shot1.png",
                "media/Pictures/Screenshots/Screenshot_1.png", "ABCD-1234/screenshots/gba-001.png")),
                "screenshots: pictures only, camera photos stay " + rel(s, Scan.SHOTS));
        check(rel(s, Scan.APKS).equals(set("media/Download/app.apk", "media/Download/sub/b.xapk",
                "ABCD-1234/Game.apk")), "APKs " + rel(s, Scan.APKS));
        final List<Scan.Item> dups = s.group(Scan.DUPS);
        check(dups.size() == 1 && dups.get(0).path.endsWith("/gbc/Tetris.gb")
                && dups.get(0).keep.endsWith("/gb/Tetris.gb"),
                "one duplicate: gbc/Tetris.gb, keeping gb/Tetris.gb (hack and BIOS aren't)");
        check(s.folders.size() >= 5 && s.folders.get(0).name.equals("bios") == false
                && s.folders.get(0).bytes >= s.folders.get(1).bytes, "folders biggest first");
        boolean noBackupsFolder = true;
        for (Scan.Folder f : s.folders) if (f.name.equals("backups")) noBackupsFolder = false;
        check(noBackupsFolder, "the backups folder isn't a ROM system");
        check(s.free.containsKey(Space.INTERNAL) && s.free.containsKey(Space.EASYROMS),
                "free space of both");
        check("1".equals(s.health.get("easyroms_ro")) && "1".equals(s.health.get("fs_errors"))
                && "exfat".equals(s.health.get("easyroms_fs")) && "ok".equals(s.health.get("fsck"))
                && "mounted".equals(s.health.get("easyroms_state")),
                "health: read-only exFAT, one error on EASYROMS (not the USB one) " + s.health);
        check(Health.READ_ONLY.equals(Health.problem(s.health)), "health: read-only is the problem");
        check(s.display(media.getPath() + "/Download/app.apk").equals("Internal storage/Download/app.apk")
                && s.display(easyroms.getPath() + "/gb/x").equals("EASYROMS/gb/x"), "display names");
        check(Scan.parse(s.format()).format().equals(s.format()), "scan file round trip");

        // delete: everything that was offered, plus lines that must be refused
        final StringBuilder del = new StringBuilder();
        for (Scan.Item i : s.items) {
            del.append(i.group).append('\t').append(i.path);
            if (i.keep != null) del.append('\t').append(i.keep);
            del.append('\n');
        }
        final String m = media.getPath(), er = easyroms.getPath();
        final String[] forged = {
            "states\t" + m + "/RetroArch/saves/Zelda.srm",
            "states\t" + m + "/RetroArch/states/Zelda.state1",
            "states\t" + m + "/RetroArch/states/Zelda.state.auto",
            "states\t" + m + "/Pictures/Camera/IMG_0001.jpg",
            "backups\t" + m + "/Backups/Saves/saves-20260920-120005-5.zip",
            "backups\t" + m + "/Backups/Saves/notes.zip",
            "thumbs\t" + m + "/RetroArch/thumbnails",
            "thumbs\t" + m + "/RetroArch/thumbnails/Odd",
            "thumbs\t" + m + "/RetroArch/thumbnails/link",
            "thumbs\t" + m + "/RetroArch/thumbnails/../saves",
            "shots\t" + m + "/RetroArch/screenshots/notes.txt",
            "apks\t" + m + "/Download/manual.pdf",
            "apks\t" + root + "/outside/precious.txt",
            "dups\t" + er + "/gbc/Tetris (hack).gb\t" + er + "/gb/Tetris.gb",
            "dups\t" + er + "/psx/scph1001.bin\t" + er + "/psx/scph1001.bin",
            "nonsense\t" + m + "/Download/app.apk",
            "states",
        };
        for (String f : forged) del.append(f).append('\n');
        write(new File(dir, "delete"), del.toString());
        write(new File(dir, "request"), "action=delete\nkeep_states=2\nkeep_backups=2\n");
        run(env);
        final String st = read(new File(dir, "status")).trim();
        check(st.startsWith("done delete " + s.items.size() + " "), "all offered items removed: " + st);
        check(st.endsWith(" 0 " + forged.length), "every forged line refused: " + st);
        for (String keep : new String[] {"media/RetroArch/saves/Zelda.srm",
                "media/RetroArch/states/Zelda.state1", "media/RetroArch/states/Zelda.state1.png",
                "media/RetroArch/states/Zelda.state3", "media/RetroArch/states/Zelda.state.auto",
                "media/Pictures/Camera/IMG_0001.jpg", "media/Backups/Saves/notes.zip",
                "media/Backups/Saves/saves-20260920-120005-5.zip",
                "media/Backups/Saves/saves-20260920-120004-4.zip",
                "media/RetroArch/thumbnails/Odd/keep.sav", "media/RetroArch/screenshots/notes.txt",
                "media/Download/manual.pdf", "outside/precious.txt",
                "media/PSP/PPSSPP_STATE/ULUS10041_1.00_2.ppst",
                "media/PSP/PPSSPP_STATE/ULUS10041_1.00_undo.ppst",
                "media/PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN",
                "ABCD-1234/gba/Zelda.srm", "ABCD-1234/gba/Zelda.gba", "ABCD-1234/gba/Zelda.state2",
                "ABCD-1234/gba/Zelda.state3", "ABCD-1234/gb/Tetris.gb",
                "ABCD-1234/gbc/Tetris (hack).gb", "ABCD-1234/psx/scph1001.bin",
                "ABCD-1234/bios/scph1001.bin"}) {
            check(new File(root, keep).exists(), "still there: " + keep);
        }
        for (Scan.Item i : s.items) {
            check(!new File(i.path).exists(), "gone: " + i.path.substring(root.getPath().length() + 1));
        }

        // both copies of a duplicate asked for: neither goes
        put(easyroms, "nes/Tetris.gb", tetris, 1);
        write(new File(dir, "delete"), "dups\t" + er + "/nes/Tetris.gb\t" + er + "/gb/Tetris.gb\n"
                + "dups\t" + er + "/gb/Tetris.gb\t" + er + "/nes/Tetris.gb\n");
        run(env);
        check(read(new File(dir, "status")).trim().equals("done delete 0 0 0 2")
                && new File(easyroms, "nes/Tetris.gb").exists() && new File(easyroms, "gb/Tetris.gb").exists(),
                "both copies of a duplicate asked for: both kept");
        new File(easyroms, "nes/Tetris.gb").delete();
        new File(easyroms, "nes").delete();

        // a second scan with nothing left to offer but the new "oldest" ones
        write(new File(dir, "request"), "action=scan\nkeep_states=2\nkeep_backups=2\n");
        run(env);
        final Scan again = Scan.parse(read(new File(dir, "scan")));
        check(again.group(Scan.STATES).isEmpty() && again.group(Scan.BACKUPS).isEmpty(),
                "after cleaning, nothing old is left at the same settings");

        // EASYROMS gone (unmountable): the scan still runs on the internal storage
        write(new File(dir, "request"), "action=health\n");
        run("STORAGECARE_EASYROMS_STATE=unmountable " + env.replace(
                "STORAGECARE_EASYROMS=" + easyroms, "STORAGECARE_EASYROMS="));
        final String h = read(new File(dir, "health"));
        check(h.contains("easyroms_state=unmountable") && h.contains("easyroms_ro=0"),
                "health of an unmountable EASYROMS: " + h.replace('\n', ' '));
        check(Health.UNMOUNTABLE.equals(Health.problem(Engine.keyValues(h))), "unmountable is the problem");
        write(vold, "I vold    : Check failed (code 4)\n");
        write(new File(dir, "request"), "action=health\n");
        run(env);
        check(read(new File(dir, "health")).contains("fsck=failed"), "vold's failed check is seen");
        write(vold, "W vold    : Check OK, errors fixed\n");
        run(env);
        check(read(new File(dir, "health")).contains("fsck=fixed"), "a fixing check is seen");
        final java.util.Map<String, String> hm = Engine.keyValues(read(new File(dir, "health")));
        hm.put("easyroms_ro", "0");
        check(Health.ERRORS.equals(Health.problem(hm)), "kernel errors while writable");
        hm.put("fs_errors", "0");
        check(Health.FIXED.equals(Health.problem(hm)), "fixed by the check before mounting");
        hm.put("fsck", "ok");
        check(Health.problem(hm) == null, "all well");
        hm.put("easyroms_state", "removed");
        hm.put("easyroms_ro", "1");
        check(Health.problem(hm) == null, "no card, no card problem");
        write(new File(dir, "request"), "action=bogus\n");
        run(env);
        check(read(new File(dir, "status")).startsWith("error request"), "unknown action: error");
    }

    static Set<String> rel(Scan s, String group) {
        final Set<String> out = new HashSet<>();
        for (Scan.Item i : s.group(group)) out.add(i.path.substring(root.getPath().length() + 1));
        return out;
    }

    static long bytesOf(Scan s, String group) {
        long n = 0;
        for (Scan.Item i : s.group(group)) n += i.bytes;
        return n;
    }

    static void run(String env) throws IOException, InterruptedException {
        final List<String> cmd = new ArrayList<>(Arrays.asList("env"));
        for (String kv : env.split(" (?=[A-Z_]+=)")) cmd.add(kv);
        cmd.add("STORAGECARE_RUN=" + java + " -cp " + classes);
        cmd.addAll(Arrays.asList(shell.split(" ")));
        cmd.add(helper);
        final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        if (System.getenv("VERBOSE") != null) System.out.print(out);
    }

    static byte[] bytes(int n, int seed) {
        final byte[] b = new byte[n];
        new java.util.Random(seed).nextBytes(b);
        return b;
    }

    static void put(File base, String rel, int size, long minutes) throws IOException {
        put(base, rel, new byte[size], minutes);
    }

    static void put(File base, String rel, byte[] data, long minutes) throws IOException {
        final File f = new File(base, rel);
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        f.setLastModified(T0 + minutes * MIN);
    }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    static String read(File f) throws IOException {
        return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    static void delete(File f) {
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            final File[] c = f.listFiles();
            if (c != null) for (File x : c) delete(x);
        }
        f.delete();
    }
}
