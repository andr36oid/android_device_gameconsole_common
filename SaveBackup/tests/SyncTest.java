package org.andr36oid.savebackup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host test of Sync saves:
 * <ul>
 * <li>the small parts (JSON, config.xml, the Syncthing app's settings file);</li>
 * <li>the root helper's sync_prefs and sync_key, through the real helper script;</li>
 * <li>with a Syncthing binary for the PC (SYNCTHING=…/syncthing, the same core version as
 *     the app), two real Syncthings as two consoles: SyncSetup sets up both, they pair,
 *     and only the saves go across, never ROMs, BIOS files or backups.</li>
 * </ul>
 *   tests/run-tests.sh
 */
public class SyncTest {

    static int failures;
    static String helper, classes, java;
    static File root;
    static final List<Process> procs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        helper = args[0];
        classes = args[1];
        java = args[2];
        final String syncthing = args.length > 3 ? args[3] : "";
        root = Files.createTempDirectory("synctest").toFile();
        try {
            units();
            for (String sh : new String[] {"dash", "bash", "busybox"}) {
                if (which(sh)) helperTests(sh);
            }
            if (!syncthing.isEmpty()) {
                twoConsoles(syncthing);
            } else {
                System.out.println("skip  two real Syncthings (set SYNCTHING=path/to/syncthing)");
            }
        } finally {
            for (Process p : procs) p.destroy();
            for (Process p : procs) p.waitFor();
            EngineTest.delete(root);
        }
        System.out.println(failures == 0 ? "ALL PASSED (sync)" : failures + " FAILED (sync)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    // ---- small parts -----------------------------------------------------------------

    static final String CONFIG = "<configuration version=\"51\">\n"
            + "  <folder id=\"x\"></folder>\n"
            + "  <gui enabled=\"true\" tls=\"true\" debugging=\"false\">\n"
            + "    <address>127.0.0.1:8384</address>\n"
            + "    <user>syncthing</user>\n"
            + "    <apikey>k3yK3yk3y</apikey>\n"
            + "    <theme>default</theme>\n"
            + "  </gui>\n</configuration>\n";

    static void units() {
        final Object o = Json.parse("{\"a\":[1,2.5,\"x\\\"y\\u00e9\",true,null],\"b\":{}}");
        check(Json.write(o).equals("{\"a\":[1,2.5,\"x\\\"yé\",true,null],\"b\":{}}"),
                "JSON round trip");
        check(Json.num(Json.list(Json.obj(o).get("a")).get(0)) == 1, "JSON numbers");
        boolean threw = false;
        try {
            Json.parse("{\"a\":1,}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "JSON: broken input is refused");

        Map<String, String> g = SyncSetup.gui(CONFIG);
        check("https://127.0.0.1:8384".equals(g.get("url")) && "k3yK3yk3y".equals(g.get("apikey")),
                "config.xml: REST address and key");
        g = SyncSetup.gui(CONFIG.replace("tls=\"true\"", "tls=\"false\"")
                .replace("127.0.0.1:8384", "0.0.0.0:8385"));
        check("http://127.0.0.1:8385".equals(g.get("url")), "config.xml: no TLS, any address");
        check(SyncSetup.gui(CONFIG.replace("127.0.0.1", "192.168.1.5")).isEmpty(),
                "config.xml: a LAN address isn't used");
        check(SyncSetup.gui("<configuration><gui><address>127.0.0.1:1</address>"
                + "</gui></configuration>").isEmpty(), "config.xml: no key yet");
        check(SyncApi.fromKeyFile("url=https://10.0.0.1:8384\napikey=x\n") == null,
                "key file with a LAN address is refused");
        check(SyncApi.fromKeyFile("url=https://127.0.0.1:8384\napikey=x\n") != null,
                "key file for 127.0.0.1");

        String p = SyncSetup.mergePrefs(null, true);
        check(p.contains("<boolean name=\"always_run_in_background\" value=\"true\" />")
                && p.contains("<string name=\"power_source\">ac_power</string>")
                && p.contains("<boolean name=\"run_on_mobile_data\" value=\"false\" />")
                && p.contains("<boolean name=\"broadcast_service_control\" value=\"true\" />")
                && p.trim().endsWith("</map>"), "app settings: new file");
        final String old = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                + "    <string name=\"power_source\">battery_power</string>\n"
                + "    <boolean name=\"run_on_mobile_data\" value=\"true\" />\n"
                + "    <string name=\"app_theme\">-1</string>\n"
                + "    <int name=\"btnStateForceStartStop\" value=\"2\" />\n</map>\n";
        p = SyncSetup.mergePrefs(old, true);
        check(count(p, "power_source") == 1 && p.contains(">ac_power<")
                && count(p, "run_on_mobile_data") == 1 && !p.contains("value=\"2\"")
                && p.contains("<string name=\"app_theme\">-1</string>"),
                "app settings: ours replaced, the user's own kept");
        p = SyncSetup.mergePrefs(p, false);
        check(p.contains("<boolean name=\"always_run_in_background\" value=\"false\" />")
                && count(p, "always_run_in_background") == 1 && p.contains(">ac_power<"),
                "app settings: off only stops the start at boot");
        check(SyncSetup.mergePrefs("<?xml version='1.0' ?>\n<map />\n", true)
                .contains("always_run_in_background"), "app settings: empty <map />");

        check(SyncSetup.validId("MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD"),
                "device ID accepted");
        check(!SyncSetup.validId("MFZWI3D-BONSGYC") && !SyncSetup.validId("../../x"),
                "bad device IDs refused");
        final List<String> ign = SyncSetup.ignores(SyncSetup.FOLDER_EASYROMS);
        check(ign.get(ign.size() - 1).equals("*") && ign.indexOf("/backups") < ign.indexOf(
                "!(?i)*.srm"), "ignores: rooted skips first, * last");
    }

    static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    // ---- the root helper -------------------------------------------------------------

    static void helperTests(String shell) throws Exception {
        final File base = new File(root, "helper-" + shell);
        final File dir = new File(base, "misc"), st = new File(base, "st"),
                media = new File(base, "media");
        dir.mkdirs();
        media.mkdirs();
        String s = helper(shell, dir, media, st, "action=sync_prefs\non=1\n");
        check(s.equals("error st_missing"), shell + ": sync_prefs without the app -> st_missing");
        st.mkdirs();
        s = helper(shell, dir, media, st, "action=sync_prefs\non=1\n");
        final File prefs = new File(st, "shared_prefs/" + SyncSetup.PKG + "_preferences.xml");
        final String p = EngineTest.read(prefs);
        check(s.equals("done sync_prefs") && p != null && p.contains("ac_power"),
                shell + ": sync_prefs on writes the app's settings");
        s = helper(shell, dir, media, st, "action=sync_key\nwait=1\n");
        check(s.equals("error st_no_config"), shell + ": sync_key before the first start");
        new File(st, "files").mkdirs();
        Engine.write(new File(st, "files/config.xml"), CONFIG);
        s = helper(shell, dir, media, st, "action=sync_key\nwait=5\n");
        final SyncApi api = SyncApi.fromKeyFile(EngineTest.read(new File(dir, "syncthing")));
        check(s.equals("done sync_key") && api != null, shell + ": sync_key copies the key");
        s = helper(shell, dir, media, st, "action=sync_prefs\non=0\n");
        check(s.equals("done sync_prefs") && EngineTest.read(prefs).contains(
                "\"always_run_in_background\" value=\"false\""), shell + ": sync_prefs off");
    }

    static String helper(String shell, File dir, File media, File st, String request)
            throws Exception {
        Engine.write(new File(dir, "request"), request);
        new File(dir, "status").delete();
        final ProcessBuilder pb = shell.equals("busybox")
                ? new ProcessBuilder("busybox", "sh", helper) : new ProcessBuilder(shell, helper);
        pb.environment().put("SAVEBACKUP_RUN", java + " -cp " + classes);
        pb.environment().put("SAVEBACKUP_DIR", dir.getPath());
        pb.environment().put("SAVEBACKUP_MEDIA", media.getPath());
        pb.environment().put("SAVEBACKUP_RA_DATA", new File(dir, "ra").getPath());
        pb.environment().put("SAVEBACKUP_PPSSPP_DATA", new File(dir, "pp").getPath());
        pb.environment().put("SAVEBACKUP_ST_DATA", st.getPath());
        pb.environment().put("SAVEBACKUP_EASYROMS", "");
        pb.environment().put("SAVEBACKUP_USB", "");
        pb.redirectErrorStream(true);
        final Process pr = pb.start();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        pr.getInputStream().transferTo(out);
        pr.waitFor();
        final String s = EngineTest.read(new File(dir, "status"));
        return s == null ? "" : s.trim();
    }

    // ---- two real Syncthings ---------------------------------------------------------

    static final class Console {
        final String name;
        final File home, easyroms, internal;
        int guiPort, dataPort;
        SyncApi api;
        String id;

        Console(String name) {
            this.name = name;
            home = new File(root, name + "/home");
            easyroms = new File(root, name + "/EASYROMS");
            internal = new File(root, name + "/internal");
        }

        Map<String, String> paths() {
            final Map<String, String> m = new LinkedHashMap<>();
            m.put(SyncSetup.FOLDER_EASYROMS, easyroms.getPath());
            m.put(SyncSetup.FOLDER_INTERNAL, internal.getPath());
            return m;
        }
    }

    static void twoConsoles(String syncthing) throws Exception {
        final Console a = new Console("consoleA"), b = new Console("consoleB");
        // console A: saves next to ROMs, a PSP save, BIOS, backups; RetroArch and PPSSPP
        // folders on the internal storage, and saves where they must not be picked up
        put(a.easyroms, "gba/Zelda.gba", "ROM-A");
        put(a.easyroms, "gba/Zelda.srm", "zelda-save-A");
        put(a.easyroms, "gba/Zelda.state1", "zelda-state");
        put(a.easyroms, "gba/Zelda.state1.png", "png");
        put(a.easyroms, "gba/Zelda.state.auto", "auto");
        put(a.easyroms, "psx/FF7.MCD", "memcard");
        put(a.easyroms, "psp/PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN", "psp-save");
        put(a.easyroms, "psp/PSP/SAVEDATA/ULUS10041DATA00/PARAM.SFO", "sfo");
        put(a.easyroms, "psp/Game.iso", "ISO");
        put(a.easyroms, "bios/scph1001.bin", "BIOS");
        put(a.easyroms, "backups/saves/saves-20260928-120000-1.zip", "zip");
        put(a.easyroms, "backups/saves/old.srm", "not this");
        put(a.internal, "RetroArch/saves/Pokemon.srm", "pokemon");
        put(a.internal, "RetroArch/states/Pokemon.state", "pokemon-state");
        put(a.internal, "RetroArch/system/bios.bin", "not this");
        put(a.internal, "PSP/PPSSPP_STATE/ULUS10041_1.00_0.ppst", "ppst");
        put(a.internal, "Android/data/x/files/foo.srm", "not this");
        put(a.internal, "Download/foo.srm", "not this");
        // console B: its own ROM with the same name, and a save A doesn't have
        put(b.easyroms, "gba/Zelda.gba", "ROM-B");
        put(b.easyroms, "snes/Mario.srm", "mario-B");
        b.internal.mkdirs();

        for (Console c : new Console[] {a, b}) start(syncthing, c);
        for (Console c : new Console[] {a, b}) {
            check(waitFor(() -> c.api.ping(), 60), c.name + ": Syncthing answers over HTTPS");
            c.id = SyncSetup.myId(c.api);
            // test network: loopback only, no discovery servers or relays
            final Map<String, Object> opt = Json.obj(c.api.get("/rest/config/options"));
            opt.put("listenAddresses", new ArrayList<Object>(Arrays.asList(
                    "tcp://127.0.0.1:" + c.dataPort)));
            opt.put("globalAnnounceEnabled", Boolean.FALSE);
            opt.put("localAnnounceEnabled", Boolean.FALSE);
            opt.put("relaysEnabled", Boolean.FALSE);
            opt.put("natEnabled", Boolean.FALSE);
            opt.put("urAccepted", -1L);
            opt.put("crashReportingEnabled", Boolean.FALSE);
            c.api.put("/rest/config/options", opt);
            final Map<String, String> labels = new LinkedHashMap<>();
            labels.put(SyncSetup.FOLDER_EASYROMS, "Game saves on EASYROMS");
            SyncSetup.setUp(c.api, c.paths(), labels, c.name);
        }
        final SyncSetup.State sa = SyncSetup.state(a.api);
        check(sa.folders.keySet().equals(new java.util.LinkedHashSet<>(SyncSetup.FOLDERS))
                && sa.devices.isEmpty(), "A: both save folders set up, no devices yet");
        final Map<String, Object> me = Json.obj(a.api.get("/rest/config/devices/"
                + SyncApi.enc(a.id)));
        check("consoleA".equals(Json.str(me.get("name"))), "A: the console's name is set");
        check(new File(a.easyroms, ".stignore").isFile()
                && EngineTest.read(new File(a.easyroms, ".stignore")).contains("!(?i)*.srm"),
                "A: .stignore written on EASYROMS");
        SyncSetup.setUp(a.api, a.paths(), new LinkedHashMap<>(), null);
        check(SyncSetup.state(a.api).folders.size() == 2, "setUp again changes nothing");

        // A adds B (as if B was found on the Wi-Fi); B then sees A asking
        SyncSetup.addDevice(a.api, b.id, "");
        address(a, b);
        check(waitFor(() -> SyncSetup.state(b.api).pending.containsKey(a.id), 60),
                "B: A shows up under Add a device (" + SyncSetup.state(b.api).pending + ")");
        check("consoleA".equals(SyncSetup.state(b.api).pending.get(a.id)),
                "B: with A's name");
        SyncSetup.addDevice(b.api, a.id, SyncSetup.state(b.api).pending.get(a.id));
        address(b, a);
        check(SyncSetup.state(b.api).pending.isEmpty(), "B: A is no longer pending");

        check(waitFor(() -> new File(b.easyroms, "gba/Zelda.srm").isFile()
                && new File(a.easyroms, "snes/Mario.srm").isFile()
                && new File(b.internal, "PSP/PPSSPP_STATE/ULUS10041_1.00_0.ppst").isFile(), 120),
                "saves go both ways");
        waitFor(() -> idle(a) && idle(b), 60);
        check(same(b.easyroms, "gba/Zelda.srm", "zelda-save-A")
                && same(b.easyroms, "gba/Zelda.state1", "zelda-state")
                && same(b.easyroms, "gba/Zelda.state1.png", "png")
                && same(b.easyroms, "gba/Zelda.state.auto", "auto")
                && same(b.easyroms, "psx/FF7.MCD", "memcard")
                && same(b.easyroms, "psp/PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN", "psp-save")
                && same(b.easyroms, "psp/PSP/SAVEDATA/ULUS10041DATA00/PARAM.SFO", "sfo")
                && same(b.internal, "RetroArch/saves/Pokemon.srm", "pokemon")
                && same(b.internal, "RetroArch/states/Pokemon.state", "pokemon-state"),
                "B has every save of A (next to ROMs, PSP folder, RetroArch folders)");
        check(same(b.easyroms, "gba/Zelda.gba", "ROM-B") && !exists(b.easyroms, "psp/Game.iso")
                && !exists(b.easyroms, "bios") && !exists(b.easyroms, "backups")
                && !exists(b.internal, "RetroArch/system") && !exists(b.internal, "Android")
                && !exists(b.internal, "Download"),
                "no ROMs, BIOS, backups, Android/ or Download/ went across");
        check(SyncSetup.state(a.api).connected.contains(b.id), "A: B is connected");

        // a save changed on B comes back to A
        put(b.easyroms, "gba/Zelda.srm", "zelda-save-B-newer");
        b.api.post("/rest/db/scan?folder=" + SyncSetup.FOLDER_EASYROMS, new LinkedHashMap<>());
        check(waitFor(() -> same(a.easyroms, "gba/Zelda.srm", "zelda-save-B-newer"), 60),
                "a newer save on B replaces A's");
        check(waitFor(() -> new File(a.easyroms, ".stversions/gba").isDirectory(), 20),
                "A keeps the replaced save in .stversions");

        // Sync saves off on A: folders paused, nothing else of the user's is running
        check(!SyncSetup.otherFoldersActive(a.api), "A: no other folders");
        SyncSetup.pause(a.api);
        check(SyncSetup.state(a.api).paused, "A: off pauses the save folders");
        SyncSetup.setUp(a.api, a.paths(), new LinkedHashMap<>(), null);
        check(!SyncSetup.state(a.api).paused && SyncSetup.state(a.api).devices.containsKey(b.id),
                "A: on again, B is still there");

        SyncSetup.removeDevice(a.api, b.id);
        check(SyncSetup.state(a.api).devices.isEmpty(), "A: stop syncing with B");
        boolean gone = false;
        try {
            a.api.get("/rest/config/devices/" + SyncApi.enc(b.id));
        } catch (SyncApi.HttpException e) {
            gone = e.code == 404;
        }
        check(gone, "A: B is removed as it shares nothing else");
    }

    static void start(String syncthing, Console c) throws Exception {
        c.home.mkdirs();
        c.guiPort = freePort();
        c.dataPort = freePort();
        final String key = "test-key-" + c.name;
        final ProcessBuilder pb = new ProcessBuilder(syncthing, "serve",
                "--home=" + c.home.getPath(), "--no-browser", "--no-restart", "--no-upgrade",
                "--gui-address=https://127.0.0.1:" + c.guiPort, "--gui-apikey=" + key);
        pb.environment().put("STNODEFAULTFOLDER", "1");
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(root, c.name + "/syncthing.log"));
        procs.add(pb.start());
        c.api = new SyncApi("https://127.0.0.1:" + c.guiPort, key);
    }

    /** Tells {@code from} where {@code to} listens (on the console this is discovery). */
    static void address(Console from, Console to) throws IOException {
        final String path = "/rest/config/devices/" + SyncApi.enc(to.id);
        final Map<String, Object> d = Json.obj(from.api.get(path));
        d.put("addresses", new ArrayList<Object>(Arrays.asList("tcp://127.0.0.1:" + to.dataPort)));
        from.api.put(path, d);
    }

    static boolean idle(Console c) throws IOException {
        final SyncSetup.State s = SyncSetup.state(c.api);
        for (String st : s.folders.values()) if (!st.equals("idle")) return false;
        return s.needFiles == 0;
    }

    interface Cond {
        boolean ok() throws Exception;
    }

    static boolean waitFor(Cond c, int seconds) throws InterruptedException {
        final long until = System.currentTimeMillis() + seconds * 1000L;
        while (true) {
            try {
                if (c.ok()) return true;
            } catch (Exception e) {
                // not yet
            }
            if (System.currentTimeMillis() > until) return false;
            Thread.sleep(500);
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static void put(File base, String rel, String text) throws IOException {
        final File f = new File(base, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static boolean same(File base, String rel, String text) {
        return text.equals(EngineTest.read(new File(base, rel)));
    }

    static boolean exists(File base, String rel) {
        return new File(base, rel).exists();
    }

    static boolean which(String cmd) {
        for (String d : System.getenv("PATH").split(":")) {
            if (new File(d, cmd).canExecute()) return true;
        }
        return false;
    }
}
