package org.andr36oid.bioscheck;

import org.andr36oid.bioscheck.BiosTable.Bios;
import org.andr36oid.bioscheck.Report.FileState;
import org.andr36oid.bioscheck.Report.Rename;
import org.andr36oid.bioscheck.Report.SystemState;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Host test of the scanning and matching: fake BIOS files in a temp EASYROMS/BIOS
 * folder, a table with their MD5s, and the real helper script (run with sh) doing the
 * scan and the renames.
 *
 *   tests/run-tests.sh
 */
public class ReportTest {

    static int failures;
    static File root, easyroms, bios, raData, dir, media, part;
    static String helper, shell;

    public static void main(String[] args) throws Exception {
        helper = args[0];
        shell = args.length > 2 ? args[2] : "sh";
        realTable(new File(args[1]));
        try {
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

    /** The shipped table parses, and every entry looks right. */
    static void realTable(File f) throws IOException {
        final BiosTable t;
        try (FileReader r = new FileReader(f, StandardCharsets.UTF_8)) {
            t = BiosTable.parse(r);
        }
        check(t.systems.size() >= 10, "real table: " + t.systems.size() + " systems");
        boolean md5sOk = true, filesOk = true;
        final Set<String> names = new HashSet<>();
        for (Bios b : t.files) {
            for (String m : b.md5s) md5sOk &= m.matches("[0-9a-f]{32}");
            filesOk &= names.add(BiosTable.key(b.name)) && !b.cores.isEmpty();
        }
        check(md5sOk, "real table: every MD5 is 32 hex digits");
        check(filesOk, "real table: file names unique, every file has cores");
        check(t.byMd5("490F666E1AFB15B7362B406ED1CEA246").get(0).name.equals("scph5501.bin"),
                "real table: scph5501.bin by MD5, any case");
        check(t.byName("NEOGEO.ZIP").get(0).anyContent(), "real table: neogeo.zip is name-only");
        check(t.system("neogeo").onlyInstalled, "real table: Neo Geo only with its core");
        boolean plain = true;
        for (BiosTable.Sys s : t.systems) {
            plain &= !s.note.contains("system folder") && !s.note.contains("core");
        }
        check(plain, "real table: the notes don't talk about system folders or cores");
    }

    static void delete(File f) {
        final File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) delete(c);
        }
        f.delete();
    }

    static String md5(String s) throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8))) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static void put(File dir, String name, String content) throws IOException {
        dir.mkdirs();
        Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void helper(String action, String ops) throws Exception {
        put(dir, "request", "action=" + action + "\n");
        if (ops != null) put(dir, "ops", ops);
        final ProcessBuilder pb = new ProcessBuilder(shell, helper).inheritIO();
        pb.environment().put("BIOSCHECK_DIR", dir.getPath());
        pb.environment().put("BIOSCHECK_RA_DATA", raData.getPath());
        pb.environment().put("BIOSCHECK_EASYROMS", easyroms.getPath());
        pb.environment().put("BIOSCHECK_MEDIA", media.getPath());
        pb.environment().put("BIOSCHECK_PART", part.getPath());
        final int rc = pb.start().waitFor();
        check(rc == 0 && read(new File(dir, "status")).trim().equals("done"),
                "helper " + action + " finished");
    }

    static Scan scan() throws Exception {
        try (FileReader r = new FileReader(new File(dir, "scan"), StandardCharsets.UTF_8)) {
            return Scan.parse(r);
        }
    }

    static Report report(BiosTable t) throws Exception {
        return Report.build(t, scan());
    }

    static FileState state(Report r, String name) {
        for (SystemState s : r.systems) {
            for (FileState f : s.files) {
                if (f.bios.name.equals(name)) return f;
            }
        }
        return null;
    }

    static int level(Report r, String id) {
        return r.system(id) != null ? r.system(id).level : -1;
    }

    static void scenario() throws Exception {
        root = Files.createTempDirectory("bioscheck").toFile();
        easyroms = new File(root, "easyroms");
        bios = new File(easyroms, "BIOS"); // any case
        raData = new File(root, "ra");
        dir = new File(root, "state");
        dir.mkdirs();
        media = new File(root, "media");
        media.mkdirs();
        // EASYROMS's partition is there (a .noroms card has none)
        part = new File(root, "mmcblk0p7");
        put(root, "mmcblk0p7", "");

        // cores: no Genesis Plus GX, but FBNeo from the online updater
        for (String c : new String[] { "pcsx_rearmed", "fceumm", "mgba", "gambatte",
                "mednafen_pce_fast", "gearsystem", "handy", "fbneo", "clownmdemu" }) {
            put(new File(raData, "cores"), c + "_libretro_android.so", "x");
        }

        final String us = "psx us", jp = "psx jp", eu = "psx eu", fds = "fds", pce = "pce3",
                lynx = "lynx", gb = "gb", sms = "sms";
        final String table = String.join("\n",
                "# test table",
                "system|psx|PlayStation|pcsx_rearmed|shipped|note",
                "file|psx|scph5501.bin|oneof|" + md5(us) + "|US|",
                "file|psx|scph5500.bin|oneof|" + md5(jp) + "|JP|",
                "file|psx|scph5502.bin|oneof|" + md5(eu) + "|EU|",
                "file|psx|scph1001.bin|oneof|" + md5("psx 1001") + "|US old|",
                "system|segacd|Sega CD|clownmdemu|shipped|note",
                "file|segacd|bios_CD_U.bin|oneof|" + md5("cd u") + "|US|",
                "file|segacd|bios_CD_E.bin|oneof|" + md5("cd e") + "|EU|",
                "system|fds|FDS|fceumm|shipped|note",
                "file|fds|disksys.rom|required|" + md5(fds) + "|FDS|",
                "system|gba|GBA|mgba|shipped|note",
                "file|gba|gba_bios.bin|optional|" + md5("gba") + "|GBA|",
                "system|pcecd|PCE CD|mednafen_pce_fast|shipped|note",
                "file|pcecd|syscard3.pce|required|" + md5(pce) + "|card 3|",
                "file|pcecd|syscard2.pce|optional|" + md5("pce2") + "|card 2|",
                "system|lynx|Lynx|handy|shipped|note",
                "file|lynx|lynxboot.img|optional|" + md5(lynx) + "|boot|",
                "system|gb|GB|gambatte mgba|shipped|note",
                "file|gb|gb_bios.bin|optional|" + md5(gb) + "|GB|",
                "file|gb|gbc_bios.bin|optional|" + md5("gbc") + "|GBC|",
                "system|sms|SMS|gearsystem genesis_plus_gx|shipped|note",
                "file|sms|bios.sms|optional|" + md5(sms) + "|SMS|gearsystem",
                "file|sms|bios_U.sms|optional|" + md5(sms) + "|SMS US|genesis_plus_gx",
                "system|md|MD|genesis_plus_gx|shipped|note",
                "file|md|bios_MD.bin|optional|" + md5("md") + "|MD|",
                "system|neogeo|Neo Geo|fbneo|installed|note",
                "file|neogeo|neogeo.zip|required|*|set|",
                "");
        final BiosTable t = BiosTable.parse(new StringReader(table));

        put(bios, "SCPH5501.BIN", us);                    // right, name in another case
        put(bios, "psx japan.bin", jp);                   // right content, wrong name
        put(new File(bios, "psx"), "scph5502.bin", eu);   // in a subfolder
        put(bios, "scph1001.bin", "bad dump");            // right name, unknown content
        put(bios, "gba_bios.bin", fds);                   // name of another BIOS: it's disksys
        put(bios, "syscard3.pce", pce);
        put(bios, "syscard2.pce", "other card");          // optional, wrong version
        put(bios, "gb_bios.bin", gb);
        put(bios, "copy of gb.bin", gb);                  // a spare copy: left alone
        put(bios, "bios_U.sms", sms);                     // Genesis Plus GX isn't there
        put(bios, "neogeo.zip", "any zip");               // name-only
        put(bios, "game.zip", "a game");                  // not a BIOS: ignored
        put(bios, "._SCPH5501.BIN", "mac junk");          // ignored
        put(bios, "readme.txt", "text");                  // ignored
        put(bios, "lynxboot.img", lynx);

        helper("scan", null);
        Scan sc = scan();
        check(sc.biosDir.equals(bios.getPath()), "bios folder found in capitals");
        check(sc.files.size() == 13, "junk ignored, subfolder listed: " + sc.files.size());
        Report r = Report.build(t, sc);

        check(r.system("md") == null, "MD hidden: Genesis Plus GX isn't installed");
        check(r.system("neogeo") != null, "Neo Geo shown: FBNeo is installed");
        check(state(r, "bios_U.sms") == null, "bios_U.sms hidden (Genesis Plus GX only)");

        check(state(r, "scph5501.bin").state == Report.FOUND, "scph5501.bin found (any case)");
        check(state(r, "scph5500.bin").state == Report.WRONG_NAME
                && state(r, "scph5500.bin").file.name.equals("psx japan.bin"),
                "scph5500.bin: wrong name");
        check(state(r, "scph5502.bin").state == Report.WRONG_NAME
                && sc.inBios(state(r, "scph5502.bin").file).equals("psx/scph5502.bin"),
                "scph5502.bin: in a subfolder counts as wrong name");
        final FileState s1001 = state(r, "scph1001.bin");
        check(s1001.state == Report.WRONG_VERSION && s1001.really.isEmpty()
                && s1001.rename == null, "scph1001.bin: wrong version, unknown content");
        check(level(r, "psx") == Report.LEVEL_READY,
                "PlayStation: ready (one is enough, a wrong spare doesn't matter)");

        check(state(r, "disksys.rom").state == Report.WRONG_NAME, "disksys.rom: wrong name");
        check(level(r, "fds") == Report.LEVEL_WRONG_NAME, "FDS: wrong name");
        final FileState gba = state(r, "gba_bios.bin");
        check(gba.state == Report.WRONG_VERSION && gba.really.size() == 1
                && gba.really.get(0).name.equals("disksys.rom") && gba.rename != null,
                "gba_bios.bin: really disksys.rom, renamed by the fix");
        check(level(r, "gba") == Report.LEVEL_WRONG_VERSION, "GBA: wrong version");

        check(state(r, "syscard3.pce").state == Report.FOUND, "syscard3.pce found");
        check(state(r, "syscard2.pce").state == Report.WRONG_VERSION, "syscard2.pce wrong");
        check(level(r, "pcecd") == Report.LEVEL_WRONG_VERSION,
                "PCE CD: a wrong optional file still shows");
        check(level(r, "segacd") == Report.LEVEL_MISSING_ONE_OF, "Sega CD: missing one of");
        check(level(r, "lynx") == Report.LEVEL_READY, "Lynx: ready");
        check(level(r, "gb") == Report.LEVEL_READY, "GB: ready, the spare copy left alone");
        check(state(r, "bios.sms").state == Report.WRONG_NAME, "bios.sms: from bios_U.sms");
        check(state(r, "neogeo.zip").state == Report.FOUND, "neogeo.zip found by name");

        final List<String> renames = new ArrayList<>();
        for (Rename rn : r.renames) renames.add(sc.inBios(rn.file) + ">" + rn.to.name);
        Collections.sort(renames);
        check(renames.equals(Arrays.asList("bios_U.sms>bios.sms", "gba_bios.bin>disksys.rom",
                "psx japan.bin>scph5500.bin", "psx/scph5502.bin>scph5502.bin")),
                "renames: " + renames);

        // the fix, then the helper scans again by itself
        helper("rename", Report.ops(r.renames));
        check(read(new File(dir, "result")).split("renamed ").length == 5, "4 renamed");
        check(new File(bios, "scph5500.bin").exists() && !new File(bios, "psx japan.bin").exists(),
                "psx japan.bin is scph5500.bin now");
        check(new File(bios, "scph5502.bin").exists()
                && !new File(bios, "psx/scph5502.bin").exists(), "scph5502.bin moved up");
        r = report(t);
        check(r.renames.isEmpty(), "no renames left");
        check(level(r, "fds") == Report.LEVEL_READY, "FDS: ready");
        check(state(r, "gba_bios.bin").state == Report.MISSING, "gba_bios.bin: just missing");
        check(level(r, "gba") == Report.LEVEL_NOT_NEEDED, "GBA: optional, not needed");
        check(level(r, "sms") == Report.LEVEL_READY, "SMS: ready");

        // a missing required file
        new File(bios, "syscard3.pce").delete();
        new File(bios, "syscard2.pce").delete();
        helper("scan", null);
        r = report(t);
        check(level(r, "pcecd") == Report.LEVEL_MISSING, "PCE CD: missing");

        // the helper refuses renames onto files, out of the bios folder, into subfolders
        put(bios, "x.bin", jp);
        put(easyroms, "outside.bin", jp);
        final String b = bios.getPath();
        helper("rename", String.join("\n",
                b + "/x.bin\t" + b + "/scph5500.bin",
                b + "/x.bin\t" + easyroms + "/y.bin",
                b + "/x.bin\t" + b + "/psx/y.bin",
                b + "/x.bin\t" + b + "/../y.bin",
                easyroms + "/outside.bin\t" + b + "/z.bin",
                b + "/../outside.bin\t" + b + "/z.bin", ""));
        final String res = read(new File(dir, "result"));
        check(res.startsWith("exists ") && res.split("failed ").length == 6,
                "renames refused: " + res.replace('\n', '|'));
        check(new File(bios, "x.bin").exists() && read(new File(bios, "scph5500.bin")).equals(jp)
                && !new File(easyroms, "y.bin").exists() && !new File(bios, "z.bin").exists()
                && new File(easyroms, "outside.bin").exists(), "nothing moved");

        // a fresh card without a bios folder gets one
        delete(bios);
        helper("scan", null);
        check(new File(easyroms, "bios").isDirectory() && scan().biosDir != null,
                "bios folder made");

        // no EASYROMS
        final File saved = easyroms;
        easyroms = new File(root, "gone");
        helper("scan", null);
        final Scan gone = scan();
        check(gone.biosDir == null && gone.easyroms == null && !gone.internal
                && !gone.cores.isEmpty(), "EASYROMS not mounted: no bios folder, not internal");
        helper("rename", b + "/x.bin\t" + b + "/y.bin\n");
        check(read(new File(dir, "result")).startsWith("failed "), "no EASYROMS: rename fails");
        easyroms = saved;

        // a card made with .noroms: no partition 7, the bios folder is on the internal
        // storage
        part.delete();
        helper("scan", null);
        Scan in = scan();
        final File ibios = new File(media, "bios");
        check(in.internal && in.easyroms == null && ibios.isDirectory()
                && in.biosDir.equals(ibios.getPath()), "no EASYROMS partition: internal bios folder made");
        put(ibios, "japan.bin", jp);
        put(ibios, "SCPH5501.BIN", us);
        helper("scan", null);
        r = report(t);
        check(state(r, "scph5501.bin").state == Report.FOUND, "internal: scph5501.bin found");
        check(r.renames.size() == 1 && r.renames.get(0).toPath.equals(ibios + "/scph5500.bin"),
                "internal: japan.bin to be renamed");
        final String i = ibios.getPath();
        helper("rename", Report.ops(r.renames) + i + "/SCPH5501.BIN\t" + easyroms + "/x.bin\n");
        check(new File(ibios, "scph5500.bin").exists() && new File(ibios, "SCPH5501.BIN").exists()
                && read(new File(dir, "result")).contains("failed "),
                "internal: renamed, but nothing moves out of the folder");
        r = report(t);
        check(r.renames.isEmpty() && level(r, "psx") == Report.LEVEL_READY, "internal: PlayStation ready");

        // the card is swapped for one with EASYROMS again
        put(root, "mmcblk0p7", "");
        helper("scan", null);
        in = scan();
        check(!in.internal && in.easyroms != null && in.biosDir.startsWith(easyroms.getPath()),
                "EASYROMS back: its bios folder again");
    }
}
