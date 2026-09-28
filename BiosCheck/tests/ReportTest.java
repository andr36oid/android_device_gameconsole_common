package org.andr36oid.bioscheck;

import org.andr36oid.bioscheck.BiosTable.Bios;
import org.andr36oid.bioscheck.Report.Copy;
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
 * Host test of the scanning and matching: fake BIOS files in a temp dir, a table with
 * their MD5s, the real helper script (run with sh) doing the scan and the copies.
 *
 *   tests/run-tests.sh
 */
public class ReportTest {

    static int failures;
    static File root, easyroms, bios, media, system, raData, dir;
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
        pb.environment().put("BIOSCHECK_MEDIA", media.getPath());
        pb.environment().put("BIOSCHECK_RA_DATA", raData.getPath());
        pb.environment().put("BIOSCHECK_EASYROMS", easyroms.getPath());
        final int rc = pb.start().waitFor();
        check(rc == 0 && read(new File(dir, "status")).trim().equals("done"),
                "helper " + action + " finished");
    }

    static Report report(BiosTable t) throws Exception {
        try (FileReader r = new FileReader(new File(dir, "scan"), StandardCharsets.UTF_8)) {
            return Report.build(t, Scan.parse(r));
        }
    }

    static FileState state(Report r, String name) {
        for (SystemState s : r.systems) {
            for (FileState f : s.files) {
                if (f.bios.name.equals(name)) return f;
            }
        }
        return null;
    }

    static void scenario() throws Exception {
        root = Files.createTempDirectory("bioscheck").toFile();
        easyroms = new File(root, "easyroms");
        bios = new File(easyroms, "BIOS"); // any case
        media = new File(root, "media");
        system = new File(media, "RetroArch/system");
        raData = new File(root, "ra");
        dir = new File(root, "state");
        dir.mkdirs();

        // cores: no Genesis Plus GX, but FBNeo from the online updater
        for (String c : new String[] { "pcsx_rearmed", "fceumm", "mgba", "gambatte",
                "mednafen_pce_fast", "gearsystem", "handy", "fbneo" }) {
            put(new File(raData, "cores"), c + "_libretro_android.so", "x");
        }

        final String us = "psx us", jp = "psx jp", other = "psx 1001", fds = "fds",
                pce = "pce3", lynx = "lynx", gb = "gb", sms = "sms";
        final String table = String.join("\n",
                "# test table",
                "system|psx|PlayStation|pcsx_rearmed|shipped|note",
                "file|psx|scph5501.bin|oneof|" + md5(us) + "|US|",
                "file|psx|scph5500.bin|oneof|" + md5(jp) + "|JP|",
                "file|psx|scph1001.bin|oneof|" + md5(other) + "|US old|",
                "system|fds|FDS|fceumm|shipped|note",
                "file|fds|disksys.rom|required|" + md5(fds) + "|FDS|",
                "system|gba|GBA|mgba|shipped|note",
                "file|gba|gba_bios.bin|optional|" + md5("gba") + "|GBA|",
                "system|pcecd|PCE CD|mednafen_pce_fast|shipped|note",
                "file|pcecd|syscard3.pce|required|" + md5(pce) + "|card 3|",
                "system|lynx|Lynx|handy|shipped|note",
                "file|lynx|lynxboot.img|optional|" + md5(lynx) + "|boot|",
                "system|gb|GB|gambatte mgba|shipped|note",
                "file|gb|gb_bios.bin|optional|" + md5(gb) + "|GB|",
                "system|sms|SMS|gearsystem genesis_plus_gx|shipped|note",
                "file|sms|bios.sms|optional|" + md5(sms) + "|SMS|gearsystem",
                "file|sms|bios_U.sms|optional|" + md5(sms) + "|SMS US|genesis_plus_gx",
                "system|md|MD|genesis_plus_gx|shipped|note",
                "file|md|bios_MD.bin|optional|" + md5("md") + "|MD|",
                "system|neogeo|Neo Geo|fbneo|installed|note",
                "file|neogeo|neogeo.zip|required|*|set|",
                "");
        final BiosTable t = BiosTable.parse(new StringReader(table));

        put(bios, "SCPH5501.BIN", us);            // good, name in another case
        put(bios, "psx japan.bin", jp);           // good content, wrong name
        put(bios, "scph1001.bin", "bad dump");    // right name, unknown content
        put(bios, "gba_bios.bin", fds);           // right name of another BIOS: it's disksys
        put(bios, "syscard3.pce", pce);           // good, but a different one is in place
        put(bios, "gb_bios.bin", gb);
        put(bios, "copy of gb.bin", gb);          // duplicate
        put(bios, "bios_U.sms", sms);             // same content bios.sms needs
        put(bios, "neogeo.zip", "any zip");       // name-only
        put(bios, "game.zip", "a game");          // unknown
        put(bios, "._SCPH5501.BIN", "mac junk");  // ignored
        put(bios, "readme.txt", "text");          // ignored
        put(system, "syscard3.pce", "other card");
        put(system, "lynxboot.img", lynx);
        final String conflictBefore = read(new File(system, "syscard3.pce"));

        helper("scan", null);
        Report r = report(t);

        check(r.systemDir.equals(system.getPath()), "system dir is RetroArch's default");
        check(r.system("md") == null, "MD hidden: Genesis Plus GX isn't installed");
        check(r.system("neogeo") != null, "Neo Geo shown: FBNeo is installed");
        check(state(r, "bios_U.sms") == null, "bios_U.sms hidden (Genesis Plus GX only)");

        check(state(r, "scph5501.bin").state == Report.READY, "scph5501.bin ready (any case)");
        check(state(r, "scph5500.bin").state == Report.READY
                && state(r, "scph5500.bin").source.name.equals("psx japan.bin"),
                "scph5500.bin ready from the misnamed file");
        final FileState s1001 = state(r, "scph1001.bin");
        check(s1001.state == Report.WRONG && s1001.wrongIs.isEmpty() && s1001.wrong.inBiosFolder,
                "scph1001.bin wrong, unknown content");
        check(r.system("psx").level == Report.LEVEL_READY, "PlayStation: ready to copy");

        check(state(r, "disksys.rom").state == Report.READY, "disksys.rom ready from gba_bios.bin");
        final FileState gba = state(r, "gba_bios.bin");
        check(gba.state == Report.WRONG && gba.wrongIs.size() == 1
                && gba.wrongIs.get(0).name.equals("disksys.rom"),
                "gba_bios.bin wrong: identified as disksys.rom");

        final FileState card = state(r, "syscard3.pce");
        check(card.state == Report.WRONG && card.source != null && !card.wrong.inBiosFolder,
                "syscard3.pce: different file in place, good one in the bios folder");
        check(r.conflicts.size() == 1 && r.conflicts.get(0).bios.name.equals("syscard3.pce"),
                "one conflict: syscard3.pce");
        check(r.system("pcecd").level == Report.LEVEL_WRONG, "PCE CD: wrong");

        check(state(r, "lynxboot.img").state == Report.OK, "lynxboot.img OK in place");
        check(r.system("lynx").level == Report.LEVEL_OK, "Lynx: OK");
        check(state(r, "bios.sms").state == Report.READY, "bios.sms ready from bios_U.sms");
        check(state(r, "neogeo.zip").state == Report.READY, "neogeo.zip ready by name");

        final List<String> renames = new ArrayList<>();
        for (Rename rn : r.renames) renames.add(rn.file.name + ">" + rn.to.name);
        Collections.sort(renames);
        check(renames.equals(Arrays.asList("gba_bios.bin>disksys.rom",
                "psx japan.bin>scph5500.bin")), "renames: " + renames);
        check(r.duplicates.size() == 1 && r.duplicates.get(0).file.name.equals("copy of gb.bin"),
                "duplicate: copy of gb.bin");
        check(r.unknown.size() == 1 && r.unknown.get(0).name.equals("game.zip"),
                "unknown: game.zip only (junk ignored)");

        final List<String> dests = new ArrayList<>();
        for (Copy c : r.copies) dests.add(c.to.substring(c.to.lastIndexOf('/') + 1));
        Collections.sort(dests);
        check(dests.equals(Arrays.asList("bios.sms", "disksys.rom", "gb_bios.bin", "neogeo.zip",
                "scph5500.bin", "scph5501.bin")), "copies: " + dests);

        // a copy slipped in over the different file must not overwrite it either
        final List<Copy> sneaky = new ArrayList<>(r.copies);
        sneaky.addAll(r.conflicts);
        helper("apply", Report.ops(sneaky, Collections.emptyList(), Collections.emptyList()));
        check(read(new File(system, "scph5501.bin")).equals(us), "copied scph5501.bin");
        check(read(new File(system, "scph5500.bin")).equals(jp), "copied scph5500.bin");
        check(read(new File(system, "bios.sms")).equals(sms), "copied bios.sms");
        check(read(new File(system, "syscard3.pce")).equals(conflictBefore),
                "different syscard3.pce kept");
        final String result = read(new File(dir, "result"));
        check(result.contains("kept " + system.getPath() + "/syscard3.pce"), "result says kept");
        check(new File(bios, "SCPH5501.BIN").exists(), "bios folder files stay");

        r = report(t);
        check(r.copies.isEmpty(), "after the copy nothing is left to copy");
        check(r.system("psx").level == Report.LEVEL_WRONG, "PlayStation: wrong (scph1001.bin)");
        check(state(r, "scph5501.bin").state == Report.OK, "scph5501.bin now OK");

        // copying again: same content, nothing happens
        helper("apply", Report.ops(sneaky, Collections.emptyList(), Collections.emptyList()));
        check(read(new File(dir, "result")).contains("same " + system.getPath() + "/scph5501.bin"),
                "second copy: same");

        // the user said replace
        helper("apply", Report.ops(Collections.emptyList(), r.conflicts, Collections.emptyList()));
        check(read(new File(system, "syscard3.pce")).equals(pce), "replaced syscard3.pce");

        // renames
        helper("apply", Report.ops(Collections.emptyList(), Collections.emptyList(), r.renames));
        check(new File(bios, "scph5500.bin").exists() && !new File(bios, "psx japan.bin").exists(),
                "renamed psx japan.bin to scph5500.bin");
        check(new File(bios, "disksys.rom").exists(), "renamed gba_bios.bin to disksys.rom");
        r = report(t);
        check(r.renames.isEmpty(), "no renames left");
        check(state(r, "gba_bios.bin").state == Report.MISSING, "gba_bios.bin now just missing");
        check(r.system("pcecd").level == Report.LEVEL_OK, "PCE CD: OK");

        // a rename never overwrites
        put(bios, "x.bin", jp);
        final Rename rn = new Rename();
        rn.file = new Scan.Found();
        rn.file.path = new File(bios, "x.bin").getPath();
        rn.toPath = new File(bios, "scph5500.bin").getPath();
        helper("apply", Report.ops(Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(rn)));
        check(read(new File(dir, "result")).startsWith("exists "), "rename onto a file refused");

        // paths outside the allowed places are refused
        final File outside = new File(root, "outside");
        helper("apply", "copy\t" + new File(bios, "scph5500.bin") + "\t" + outside + "\t-\n"
                + "copy\t" + new File(bios, "scph5500.bin") + "\t" + system + "/../../x\t-\n");
        check(!outside.exists() && !new File(media, "x").exists()
                && read(new File(dir, "result")).split("failed").length == 3,
                "copies outside EASYROMS and the storage refused");

        // RetroArch set to its own system folder
        final File custom = new File(media, "Emu/system");
        put(new File(media, "Android/data/com.retroarch/files"), "retroarch.cfg",
                "savefile_directory = \"default\"\nsystem_directory = \"/storage/emulated/0/Emu/system\"\n");
        helper("scan", null);
        r = report(t);
        check(r.systemDir.equals(custom.getPath()), "system dir from retroarch.cfg");
        check(state(r, "scph5501.bin").state == Report.READY
                && state(r, "scph5501.bin").source.inBiosFolder,
                "custom dir: copy again, from the bios folder");

        // no EASYROMS
        final File gone = new File(root, "gone");
        final File saved = easyroms;
        easyroms = gone;
        helper("scan", null);
        easyroms = saved;
        try (FileReader fr = new FileReader(new File(dir, "scan"), StandardCharsets.UTF_8)) {
            final Scan s = Scan.parse(fr);
            check(s.biosDir == null && s.easyroms == null, "no EASYROMS: no bios folder");
        }
    }
}
