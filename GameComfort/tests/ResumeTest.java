package org.andr36oid.gamecomfort.resume;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host test of "Continue playing": the parsers on real-format sample files, the choice of the
 * game, and the root helper script (run with dash/bash/mksh against a fake console tree).
 *   java ... ResumeTest <helper.sh> <samples dir> <shell>
 */
public class ResumeTest {

    private static int sPass;
    private static int sFail;

    public static void main(String[] args) throws Exception {
        final String helper = args[0];
        final File samples = new File(args[1]);
        final String shell = args[2];
        System.out.println("== ResumeTest with " + shell);

        json();
        paths();
        history(samples);
        config(samples);
        ppsspp(samples);
        target();
        slots();
        withHelper(helper, samples, shell);

        System.out.println(shell + ": " + sPass + " passed, " + sFail + " failed");
        System.exit(sFail == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- plain parsing

    static void json() {
        final Object o = Json.parse("{\"a\": [1, -2.5e1, true, false, null],"
                + " \"b\": \"x\\\"y\\\\z\\/\\u00e9\\n\", \"c\": {}}");
        final Map<?, ?> m = (Map<?, ?>) o;
        eq("json array", Arrays.asList(1.0, -25.0, true, false, null), m.get("a"));
        eq("json escapes", "x\"y\\z/\u00e9\n", m.get("b"));
        eq("json empty object", Collections.emptyMap(), m.get("c"));
        for (String bad : new String[] {"{", "{\"a\" 1}", "[1,]", "\"abc", "{} x", "\"\\q\""}) {
            try {
                Json.parse(bad);
                fail("json rejects " + bad);
            } catch (IllegalArgumentException e) {
                pass("json rejects " + bad);
            }
        }
    }

    static void paths() {
        eq("internal", "/data/media/0/RetroArch/x", Paths.toRoot("/storage/emulated/0/RetroArch/x"));
        eq("sdcard link", "/data/media/0/a", Paths.toRoot("/sdcard/a/"));
        eq("card", "/mnt/media_rw/1234-ABCD/gba/a b.gba",
                Paths.toRoot("/storage/1234-ABCD/gba/a b.gba"));
        eq("file url", "/mnt/media_rw/1234-ABCD/psp/x y.iso",
                Paths.toRoot("file:///storage/1234-ABCD/psp/x%20y.iso"));
        eq("retroarch data", "/data/user/0/com.retroarch/cores/a.so",
                Paths.toRoot("/data/data/com.retroarch/cores/a.so"));
        eq("dot dot refused", null, Paths.toRoot("/storage/emulated/0/../../../etc/passwd"));
        eq("other place refused", null, Paths.toRoot("/data/system/users/0.xml"));
        eq("other app refused", null, Paths.toRoot("/data/user/0/com.retroarchx/a"));
        eq("relative refused", null, Paths.toRoot("RetroArch/x"));
        eq("tree link", "/data/media/0/PSP stick",
                Paths.toRoot("content://com.android.externalstorage.documents/tree/primary%3APSP%20stick"));
        eq("document link", "/mnt/media_rw/1234-ABCD/psp/Game+1.iso",
                Paths.toRoot("content://com.android.externalstorage.documents/tree/1234-ABCD%3Apsp"
                        + "/document/1234-ABCD%3Apsp%2FGame%2B1.iso"));
        eq("other provider refused", null,
                Paths.toRoot("content://com.android.providers.downloads.documents/document/12"));
        eq("to app", "/storage/emulated/0/a", Paths.toApp("/data/media/0/a"));
        eq("to app card", "/storage/1234-ABCD/a", Paths.toApp("/mnt/media_rw/1234-ABCD/a"));
        eq("name of link", "Game+1.iso", Paths.name(
                "content://com.android.externalstorage.documents/tree/1234-ABCD%3Apsp"
                        + "/document/1234-ABCD%3Apsp%2FGame%2B1.iso"));
        eq("no extension", "Tetris (World)", Paths.withoutExtension("Tetris (World).gb"));
        eq("dot file", ".hidden", Paths.withoutExtension(".hidden"));
    }

    static void history(File samples) throws IOException {
        final List<RetroArch.Entry> e = RetroArch.parseHistory(text(new File(samples,
                "content_history.lpl")));
        eq("history entries", 3, e.size());
        eq("history newest path",
                "/storage/1234-ABCD/gba/Pok\u00e9mon - Emerald Version (USA, Europe) [!].gba",
                e.get(0).path);
        eq("history core", "/data/user/0/com.retroarch/cores/mgba_libretro_android.so",
                e.get(0).corePath);
        eq("history label with quotes", "Mario \"the\" Plumber", e.get(1).label);
        eq("history DETECT not launchable", false, e.get(2).isLaunchable());
        eq("newest", e.get(0), RetroArch.newest(e));

        final List<RetroArch.Entry> d = RetroArch.parseHistory(text(new File(samples,
                "content_history_detect_first.lpl")));
        eq("image viewer skipped", "/storage/1234-ABCD/psx/Crash Bandicoot (USA).m3u",
                RetroArch.newest(d).path);

        final List<RetroArch.Entry> old = RetroArch.parseHistory(text(new File(samples,
                "content_history_old.lpl")));
        eq("old format entries", 2, old.size());
        eq("old format label", "Chrono Trigger (USA)", old.get(0).label);
        eq("old format core", "/data/user/0/com.retroarch/cores/gambatte_libretro_android.so",
                old.get(1).corePath);

        eq("compressed", 0, RetroArch.parseHistory("#RZIPv1#....").size());
        eq("broken json", 0, RetroArch.parseHistory("{\"items\": [").size());
        eq("empty", 0, RetroArch.parseHistory("").size());
        eq("no items", 0, RetroArch.parseHistory("{\"version\": \"1.5\"}").size());
        eq("empty history", null, RetroArch.newest(RetroArch.parseHistory(
                "{\"version\": \"1.5\", \"items\": []}")));
    }

    static void config(File samples) throws IOException {
        final Map<String, String> c = RetroArch.parseConfig(text(new File(samples,
                "retroarch.cfg")));
        eq("cfg value", "true", c.get("savestates_in_content_dir"));
        eq("cfg comment skipped", false, c.containsKey("# andr36oid"));
        final List<String> h = RetroArch.historyCandidates(c);
        eq("history from cfg first",
                "/data/media/0/RetroArch/playlists/builtin/content_history.lpl", h.get(0));
        eq("history default dir ignored", false, h.contains("default/content_history.lpl"));
        eq("private history last", "/data/user/0/com.retroarch/content_history.lpl",
                h.get(h.size() - 1));
        final Map<String, String> own = new HashMap<>();
        own.put("content_history_directory", "/storage/1234-ABCD/ra");
        eq("own history folder", "/mnt/media_rw/1234-ABCD/ra/content_history.lpl",
                RetroArch.historyCandidates(own).get(0));
        final List<String> s = RetroArch.saveFolders(c,
                "/storage/1234-ABCD/nes/Mario.zip#Mario.nes");
        eq("saves next to the game first", "/mnt/media_rw/1234-ABCD/nes", s.get(0));
        eq("state folder from cfg", true, s.contains("/data/media/0/RetroArch/states"));
        eq("archive save names", Arrays.asList("Mario"),
                RetroArch.saveNames("/storage/x/Mario.zip#Mario.nes"));
        eq("archive two names", Arrays.asList("Inner", "Outer"),
                RetroArch.saveNames("/storage/x/Outer.7z#Inner.gba"));
        eq("strip archive", "/storage/x/Outer.7z", RetroArch.stripArchive("/storage/x/Outer.7z#Inner.gba"));
    }

    static void ppsspp(File samples) throws IOException {
        final List<String> r = Ppsspp.parseRecent(text(new File(samples, "ppsspp.ini")));
        eq("ppsspp recent", Arrays.asList("/storage/1234-ABCD/psp/Patapon 2 (USA).cso",
                "/storage/1234-ABCD/psp/Lumines (Europe).iso"), r);
        eq("ppsspp no recent", 0, Ppsspp.parseRecent("[General]\nFileName0 = x\n").size());
        final List<String> ini = Ppsspp.iniCandidates(text(new File(samples,
                "memstick_dir.txt")));
        eq("memstick from link", "/data/media/0/PSPstick/PSP/SYSTEM/ppsspp.ini", ini.get(0));
        eq("memstick defaults", 3, ini.size());
        eq("memstick plain", "/mnt/media_rw/1234-ABCD/PSP/SYSTEM/ppsspp.ini",
                Ppsspp.iniCandidates("/storage/1234-ABCD\n").get(0));
        eq("no memstick", 2, Ppsspp.iniCandidates(null).size());
    }

    static void target() {
        eq("tags removed", "Pok\u00e9mon - Emerald Version",
                Target.displayName("Pok\u00e9mon - Emerald Version (USA, Europe) [!]"));
        eq("nested tags", "Game", Target.displayName("Game (a (b)) [c]"));
        eq("all tags kept", "(Homebrew)", Target.displayName("(Homebrew)"));

        final RetroArch.Entry entry = new RetroArch.Entry("/storage/1234-ABCD/gba/Zelda (USA).gba",
                "", "/data/user/0/com.retroarch/cores/mgba_libretro_android.so", "mGBA");
        final Finder.RaGame ra = new Finder.RaGame(entry, 2000, null,
                "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg");
        final Finder.PpGame pp = new Finder.PpGame("/storage/1234-ABCD/psp/Patapon 2 (USA).cso",
                3000);

        Target t = Target.choose(RetroArch.PACKAGE, "RetroArch", ra, pp);
        eq("last app RetroArch", Target.RETROARCH, t.kind);
        eq("label from file name", "Zelda", t.label);
        eq("config kept", "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg",
                t.config);
        t = Target.choose(Ppsspp.PACKAGE, "PPSSPP", ra, pp);
        eq("last app PPSSPP", "Patapon 2", t.label);
        t = Target.choose("com.example.game", "Example", ra, pp);
        eq("last app a game", Target.APP, t.kind);
        eq("app label", "Example", t.label);
        eq("uninstalled", null, Target.choose("com.example.game", null, ra, pp));
        eq("RetroArch without history opens the app", Target.APP,
                Target.choose(RetroArch.PACKAGE, "RetroArch", null, pp).kind);
        eq("nothing known, newest emulator", Target.PPSSPP,
                Target.choose(null, null, ra, pp).kind);
        eq("nothing known, RetroArch", Target.RETROARCH, Target.choose(null, null, ra, null).kind);
        eq("nothing at all", null, Target.choose(null, null, null, null));

        final Target odd = new Target(Target.RETROARCH, "com.retroarch", "a=b\nc", "/x/a=b.gba",
                "/c.so", "Core (x)", "/cfg");
        final Target back = Target.parse(odd.serialize());
        eq("round trip game", "/x/a=b.gba", back.game);
        eq("round trip label", "a=b c", back.label);
        eq("parse garbage", null, Target.parse("kind=bogus\npkg=x\n"));
        eq("parse empty", null, Target.parse(""));
    }

    static void slots() {
        eq("slot 0", 0, Finder.stateSlot("state"));
        eq("slot 12", 12, Finder.stateSlot("state12"));
        eq("slot auto", -1, Finder.stateSlot("state.auto"));
        eq("thumbnail", Integer.MIN_VALUE, Finder.stateSlot("state.png"));
        eq("state1.png", Integer.MIN_VALUE, Finder.stateSlot("state1.png"));
        eq("srm", Integer.MIN_VALUE, Finder.stateSlot("srm"));
    }

    // ---------------------------------------------------------------- with the root helper

    /** FileAccess through the real helper script, on a fake console tree. */
    static final class ShellFiles implements FileAccess {
        final String helper;
        final String shell;
        final File root;
        final File dir;
        int runs;

        ShellFiles(String helper, String shell, File root) {
            this.helper = helper;
            this.shell = shell;
            this.root = root;
            this.dir = new File(root, "misc");
            dir.mkdirs();
        }

        List<String[]> run(String request) {
            runs++;
            try {
                write(new File(dir, "request"), request);
                final ProcessBuilder pb = new ProcessBuilder(shell, helper);
                pb.environment().put("GAMECOMFORT_DIR", dir.getPath());
                pb.environment().put("GAMECOMFORT_ROOT", root.getPath());
                pb.redirectErrorStream(true);
                final Process p = pb.start();
                final String output = ResumeTest.read(p.getInputStream());
                final int rc = p.waitFor();
                if (rc != 0 || !output.isEmpty()) {
                    fail("helper rc " + rc + " output: " + output);
                }
                final String status = text(new File(dir, "status")).trim();
                if (!status.equals("done")) {
                    fail("helper status " + status);
                }
                if (new File(dir, "request").exists()) {
                    fail("helper left the request");
                }
                final List<String[]> lines = new ArrayList<>();
                for (String line : text(new File(dir, "out")).split("\n")) {
                    if (!line.isEmpty()) {
                        lines.add(line.split("\t", -1));
                    }
                }
                return lines;
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Map<String, Found> read(List<String> paths) {
            final StringBuilder sb = new StringBuilder();
            for (String p : paths) {
                sb.append("read\t").append(p).append('\n');
            }
            final Map<String, Found> map = new HashMap<>();
            for (String[] l : run(sb.toString())) {
                if (l.length == 5 && l[0].equals("found")) {
                    try {
                        map.put(l[3], new Found(l[3], Long.parseLong(l[1]), Long.parseLong(l[2]),
                                text(new File(dir, "f" + l[4]))));
                    } catch (IOException e) {
                        fail("copy f" + l[4] + " missing");
                    }
                } else {
                    fail("read answer " + String.join("|", l));
                }
            }
            return map;
        }

        @Override
        public Map<String, Found> stat(List<String> paths) {
            final StringBuilder sb = new StringBuilder();
            for (String p : paths) {
                sb.append("stat\t").append(p).append('\n');
            }
            final Map<String, Found> map = new HashMap<>();
            for (String[] l : run(sb.toString())) {
                if (l.length == 4 && l[0].equals("stat")) {
                    map.put(l[3], new Found(l[3], Long.parseLong(l[1]), Long.parseLong(l[2]),
                            null));
                } else {
                    fail("stat answer " + String.join("|", l));
                }
            }
            return map;
        }

        @Override
        public List<Found> list(List<String> dirs, List<String> prefixes) {
            final StringBuilder sb = new StringBuilder();
            for (String d : dirs) {
                sb.append("dir\t").append(d).append('\n');
            }
            for (String p : prefixes) {
                sb.append("prefix\t").append(p).append('\n');
            }
            final List<Found> list = new ArrayList<>();
            for (String[] l : run(sb.toString())) {
                if (l.length == 4 && l[0].equals("file")) {
                    list.add(new Found(l[3], Long.parseLong(l[1]), Long.parseLong(l[2]), null));
                } else {
                    fail("list answer " + String.join("|", l));
                }
            }
            return list;
        }
    }

    static void withHelper(String helper, File samples, String shell) throws IOException {
        final File root = java.nio.file.Files.createTempDirectory("resume").toFile();
        try {
            helperTests(new ShellFiles(helper, shell, root), root, samples);
        } finally {
            delete(root);
        }
    }

    static void helperTests(ShellFiles files, File root, File samples) throws IOException {
        final long now = System.currentTimeMillis() / 1000;
        final String card = "/mnt/media_rw/1234-ABCD";

        // Nothing there yet
        eq("no RetroArch", null, Finder.retroArch(files));
        eq("no PPSSPP", null, Finder.ppsspp(files));

        // RetroArch: config in Android/data, the history where the cfg says, and an older
        // history in the private folder with another game
        put(root, "/data/media/0/Android/data/com.retroarch/files/retroarch.cfg",
                text(new File(samples, "retroarch.cfg")), now - 5000);
        put(root, "/data/media/0/RetroArch/playlists/builtin/content_history.lpl",
                text(new File(samples, "content_history.lpl")), now - 100);
        put(root, "/data/user/0/com.retroarch/playlists/builtin/content_history.lpl",
                text(new File(samples, "content_history_old.lpl")), now - 3000);
        Finder.RaGame ra = Finder.retroArch(files);
        eq("newest history wins", "/storage/1234-ABCD/gba/Pok\u00e9mon - Emerald Version (USA, "
                + "Europe) [!].gba", ra != null ? ra.entry.path : null);
        eq("history time", now - 100, ra.time);
        eq("config path", "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg",
                ra.configPath);
        eq("config read", "true", ra.config.get("savestates_in_content_dir"));

        // The private one becomes newer (RetroArch lost its storage permission, say)
        touch(root, "/data/user/0/com.retroarch/playlists/builtin/content_history.lpl", now);
        eq("then the other history", "Chrono Trigger (USA)",
                Finder.retroArch(files).entry.label);
        touch(root, "/data/user/0/com.retroarch/playlists/builtin/content_history.lpl",
                now - 3000);

        // The game and core are there? (card not mounted yet)
        eq("game missing", false, Finder.exists(files, ra.entry.path));
        final String game = card + "/gba/Pok\u00e9mon - Emerald Version (USA, Europe) [!].gba";
        put(root, game, "rom", now - 9000);
        eq("game there", true, Finder.exists(files, ra.entry.path));
        eq("core missing", false, Finder.exists(files, ra.entry.corePath));
        put(root, "/data/user/0/com.retroarch/cores/mgba_libretro_android.so", "elf", now);
        eq("core there", true, Finder.exists(files, ra.entry.corePath));
        eq("unknown place", null, Finder.exists(files, "/system/lib/x.so"));

        // Save states next to the game; names with [!] must not act as patterns
        final String base = card + "/gba/Pok\u00e9mon - Emerald Version (USA, Europe) [!]";
        eq("no states", null, Finder.stateHint(files, ra));
        put(root, base + ".srm", "srm", now - 600);
        put(root, base + ".state", "s0", now - 1200);
        eq("state older than save", null, Finder.stateHint(files, ra));
        put(root, base + ".state3", "s3", now - 60);
        put(root, base + ".state3.png", "png", now - 30);
        put(root, card + "/gba/Pok\u00e9mon - Emerald Version (USA, Europe) [!] 2.state5", "x",
                now);
        put(root, card + "/gba/Pokemon.state7", "x", now);
        Finder.StateHint hint = Finder.stateHint(files, ra);
        eq("newest state slot", 3, hint != null ? hint.slot : null);
        eq("save time", now - 600, hint.saveTime);
        touch(root, base + ".srm", now - 10);
        eq("save newer again", null, Finder.stateHint(files, ra));
        put(root, base + ".state.auto", "auto", now);
        eq("auto state", -1, Finder.stateHint(files, ra).slot);

        // States in a per-core folder under the state folder (sorting on)
        new File(root, base.substring(1) + ".state").delete();
        new File(root, base.substring(1) + ".state3").delete();
        new File(root, base.substring(1) + ".state.auto").delete();
        put(root, "/data/media/0/RetroArch/states/mGBA/Pok\u00e9mon - Emerald Version (USA, "
                + "Europe) [!].state2", "s2", now);
        eq("state in a core folder", 2, Finder.stateHint(files, ra).slot);

        // Refused and too big files
        put(root, "/data/system/secret.xml", "no", now);
        eq("outside refused", 0, files.read(Arrays.asList("/data/system/secret.xml",
                "/data/media/0/../../system/secret.xml")).size());
        eq("list outside refused", 0, files.list(Arrays.asList("/data/system", "/etc"),
                Arrays.asList("s")).size());
        eq("prefix with a slash refused", 0, files.list(Arrays.asList("/data/media/0"),
                Arrays.asList("../")).size());
        final StringBuilder big = new StringBuilder();
        while (big.length() <= 4194304) {
            big.append("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n");
        }
        put(root, "/data/media/0/big.lpl", big.toString(), now);
        eq("big file skipped", 0, files.read(Arrays.asList("/data/media/0/big.lpl")).size());
        eq("folder not read", 0, files.read(Arrays.asList("/data/media/0/RetroArch")).size());

        // PPSSPP: memstick chosen with the file picker, ini there
        put(root, "/data/media/0/Android/data/org.ppsspp.ppsspp/files/memstick_dir.txt",
                text(new File(samples, "memstick_dir.txt")), now - 9000);
        put(root, "/data/media/0/PSPstick/PSP/SYSTEM/ppsspp.ini",
                text(new File(samples, "ppsspp.ini")), now - 50);
        put(root, "/data/media/0/PSP/SYSTEM/ppsspp.ini",
                "[Recent]\nFileName0 = /storage/emulated/0/old.iso\n", now - 7000);
        final Finder.PpGame pp = Finder.ppsspp(files);
        eq("ppsspp game", "/storage/1234-ABCD/psp/Patapon 2 (USA).cso", pp != null ? pp.path : null);
        eq("ppsspp time", now - 50, pp.time);

        // Nothing known about the last app: the emulator that saved last (PPSSPP) wins
        eq("choose newest", Target.PPSSPP, Target.choose(null, null, ra, pp).kind);

        // Special characters in a path survive the trip
        final String odd = "/data/media/0/odd/it's \"$HOME\" `x` *.gb";
        put(root, odd, "odd", now);
        eq("odd name read", "odd", files.read(Arrays.asList(odd)).get(odd).text);
        eq("odd name listed", 1, files.list(Arrays.asList("/data/media/0/odd"),
                Arrays.asList("it's \"$HOME\" `x` *")).size());
        eq("star is plain text", 0, files.list(Arrays.asList("/data/media/0/odd"),
                Arrays.asList("*")).size());
    }

    // ---------------------------------------------------------------- helpers

    static void put(File root, String path, String text, long mtime) throws IOException {
        final File f = new File(root, path.substring(1));
        f.getParentFile().mkdirs();
        write(f, text);
        f.setLastModified(mtime * 1000);
    }

    static void touch(File root, String path, long mtime) {
        new File(root, path.substring(1)).setLastModified(mtime * 1000);
    }

    static void write(File f, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String text(File f) throws IOException {
        return read(new FileInputStream(f));
    }

    static String read(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            final StringBuilder sb = new StringBuilder();
            final char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    static void delete(File f) {
        final File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                delete(c);
            }
        }
        f.delete();
    }

    static void eq(String what, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            pass(what);
        } else {
            fail(what + ": expected <" + expected + "> got <" + actual + ">");
        }
    }

    static void pass(String what) {
        sPass++;
    }

    static void fail(String what) {
        sFail++;
        System.out.println("FAIL " + what);
    }
}
