package org.andr36oid.cpuoverclock;

import static org.andr36oid.cpuoverclock.AppProfileRules.BALANCED;
import static org.andr36oid.cpuoverclock.AppProfileRules.FOLLOW;
import static org.andr36oid.cpuoverclock.AppProfileRules.PERFORMANCE;
import static org.andr36oid.cpuoverclock.AppProfileRules.SAVER;

import org.andr36oid.cpuoverclock.AppProfileRules.Front;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host test of the per-app profile rules, and of how they play with game mode, whose
 * SET_PROFILE / RESTORE_PROFILE (ProfileCommandReceiver) work on the chosen profile.
 *
 *   tests/run-tests.sh
 */
public class AppProfileRulesTest {

    static final String OWN = "org.andr36oid.cpuoverclock";
    static final String PSP = "org.ppsspp.ppsspp";
    static final String RA = "com.retroarch";
    static final String HOME = "com.magneticchen.daijishou";
    static final String VLC = "org.videolan.vlc";
    static final String FILES = "com.android.documentsui";
    static final String EDITOR = "org.andr36oid.touchmapper";

    static int failures;
    static int checks;

    public static void main(String[] args) throws Exception {
        shippedDefaults(new File(args[0]));
        parsing();
        lookup();
        frontAndPick();
        effective();
        withGameMode();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }

    static void check(String what, Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }

    // The real list: every line must parse, no package twice
    static void shippedDefaults(File xml) throws Exception {
        final String text = new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8);
        final Matcher m = Pattern.compile("<item>([^<]*)</item>").matcher(text);
        final List<String> items = new ArrayList<>();
        while (m.find()) {
            items.add(m.group(1));
        }
        final Map<String, Integer> parsed =
                AppProfileRules.parseDefaults(items.toArray(new String[0]));
        check("shipped lines", true, items.size() > 0);
        check("every shipped line parses, no package twice", items.size(), parsed.size());
        check("PPSSPP ships Performance", PERFORMANCE, parsed.get(PSP));
        check("RetroArch is not listed", null, parsed.get(RA));
        check("home screen ships Balanced", BALANCED, parsed.get(HOME));
        check("VLC ships Battery saver", SAVER, parsed.get(VLC));
    }

    static void parsing() {
        final Map<String, Integer> d = AppProfileRules.parseDefaults(new String[] {
                "a=performance", " b = saver ", "c=balanced", "d=turbo", "=saver", "e", ""});
        check("parse a", PERFORMANCE, d.get("a"));
        check("parse b trimmed", SAVER, d.get("b"));
        check("parse c", BALANCED, d.get("c"));
        check("unknown name skipped", false, d.containsKey("d"));
        check("bad lines skipped", 3, d.size());
    }

    static void lookup() {
        final Map<String, Integer> defaults = new HashMap<>();
        defaults.put(PSP, PERFORMANCE);
        defaults.put(HOME, BALANCED);
        final Map<String, Integer> chosen = new HashMap<>();
        check("shipped default used", PERFORMANCE,
                AppProfileRules.profileFor(PSP, chosen, defaults, OWN));
        check("unlisted app follows", FOLLOW,
                AppProfileRules.profileFor(RA, chosen, defaults, OWN));
        check("null follows", FOLLOW, AppProfileRules.profileFor(null, chosen, defaults, OWN));
        chosen.put(PSP, SAVER);
        chosen.put(HOME, FOLLOW);
        chosen.put(RA, BALANCED);
        check("user choice beats shipped", SAVER,
                AppProfileRules.profileFor(PSP, chosen, defaults, OWN));
        check("user Default beats shipped", FOLLOW,
                AppProfileRules.profileFor(HOME, chosen, defaults, OWN));
        check("user choice for unlisted app", BALANCED,
                AppProfileRules.profileFor(RA, chosen, defaults, OWN));
        chosen.put(OWN, PERFORMANCE);
        check("this app never has its own", FOLLOW,
                AppProfileRules.profileFor(OWN, chosen, defaults, OWN));
        chosen.put(VLC, 7);
        check("stored junk follows", FOLLOW,
                AppProfileRules.profileFor(VLC, chosen, defaults, OWN));

        final Map<String, Integer> none = new HashMap<>();
        check("nothing set", false, AppProfileRules.anySet(none, none));
        check("shipped counts", true, AppProfileRules.anySet(none, defaults));
        final Map<String, Integer> allDefault = new HashMap<>();
        allDefault.put(PSP, FOLLOW);
        allDefault.put(HOME, FOLLOW);
        check("all shipped set back to Default", false,
                AppProfileRules.anySet(allDefault, defaults));
        final Map<String, Integer> one = new HashMap<>();
        one.put(RA, SAVER);
        check("one user choice", true, AppProfileRules.anySet(one, none));
    }

    static void frontAndPick() {
        Front f = AppProfileRules.onFront(Front.NONE, PSP, PERFORMANCE);
        check("PSP in front", new Front(PSP, PERFORMANCE, FOLLOW), f);
        f = AppProfileRules.onPick(f, BALANCED);
        check("pick in PSP lasts in PSP", new Front(PSP, PERFORMANCE, BALANCED), f);
        check("same app again keeps the pick", f, AppProfileRules.onFront(f, PSP, PERFORMANCE));
        check("touch controls editor keeps the pick", f,
                AppProfileRules.onFront(f, EDITOR, FOLLOW));
        check("recent apps keep the pick", f,
                AppProfileRules.onFront(f, "com.android.systemui", FOLLOW));
        check("unknown front keeps state", f, AppProfileRules.onFront(f, null, FOLLOW));
        check("list changed PSP to Default drops the pick", new Front(PSP, FOLLOW, FOLLOW),
                AppProfileRules.onFront(f, PSP, FOLLOW));
        final Front home = AppProfileRules.onFront(f, HOME, BALANCED);
        check("leaving drops the pick", new Front(HOME, BALANCED, FOLLOW), home);
        check("back in PSP starts fresh", new Front(PSP, PERFORMANCE, FOLLOW),
                AppProfileRules.onFront(home, PSP, PERFORMANCE));
        final Front files = AppProfileRules.onFront(home, FILES, FOLLOW);
        check("pick in an app without its own changes the chosen one", null,
                AppProfileRules.onPick(files, SAVER));
        check("pick with nothing in front", null, AppProfileRules.onPick(Front.NONE, SAVER));
        check("bad pick", null, AppProfileRules.onPick(home, 9));
    }

    static void effective() {
        final Front psp = new Front(PSP, PERFORMANCE, FOLLOW);
        check("off: chosen", SAVER, AppProfileRules.effective(false, psp, SAVER));
        check("app's own", PERFORMANCE, AppProfileRules.effective(true, psp, SAVER));
        check("pick wins", BALANCED, AppProfileRules.effective(true,
                new Front(PSP, PERFORMANCE, BALANCED), SAVER));
        check("app without own: chosen", SAVER,
                AppProfileRules.effective(true, new Front(FILES, FOLLOW, FOLLOW), SAVER));
        check("nothing in front: chosen", BALANCED,
                AppProfileRules.effective(true, Front.NONE, BALANCED));
        check("own app (Settings screen): chosen", PERFORMANCE,
                AppProfileRules.effective(true, new Front(OWN, FOLLOW, FOLLOW), PERFORMANCE));
    }

    /**
     * The device as a small model: the chosen profile, game mode's commands (same logic as
     * ProfileCommandReceiver, on the chosen profile), the watcher, the tile. Game mode and the
     * watcher see the same app switch but run in two apps, so either may come first. Every
     * order must end with the same profile in the kernel and the chosen one unharmed.
     */
    static final class Console {
        final boolean enabled = true;
        final Map<String, Integer> defaults = new HashMap<>();
        final Map<String, Integer> chosenApps = new HashMap<>();
        int chosen;
        Front front = Front.NONE;
        Integer gmBefore;
        int gmSet = FOLLOW;

        Console(int chosen) {
            this.chosen = chosen;
            defaults.put(PSP, PERFORMANCE);
            defaults.put(HOME, BALANCED);
        }

        int kernel() {
            return AppProfileRules.effective(enabled, front, chosen);
        }

        void watcherSees(String pkg) {
            front = AppProfileRules.onFront(front, pkg,
                    AppProfileRules.profileFor(pkg, chosenApps, defaults, OWN));
        }

        void gameModeSet(int profile) {
            if (gmBefore == null) {
                gmBefore = chosen;
            }
            gmSet = profile;
            chosen = profile;
        }

        void gameModeRestore() {
            if (gmBefore == null) {
                return;
            }
            if (chosen == gmSet && gmSet != gmBefore) {
                chosen = gmBefore;
            }
            gmBefore = null;
        }

        void tile(int profile) {
            final Front picked = AppProfileRules.onPick(front, profile);
            if (picked != null) {
                front = picked;
            } else {
                chosen = profile;
            }
        }
    }

    static void withGameMode() {
        // Game mode with "Performance profile while playing: Battery saver" (odd, but it
        // shows who wins), per-app PSP Performance, chosen Balanced
        for (int order = 0; order < 4; order++) {
            final Console c = new Console(BALANCED);
            c.watcherSees(HOME);
            if ((order & 1) == 0) {
                c.watcherSees(PSP);
                c.gameModeSet(SAVER);
            } else {
                c.gameModeSet(SAVER);
                c.watcherSees(PSP);
            }
            check("order " + order + ": app's own beats game mode's", PERFORMANCE, c.kernel());
            if ((order & 2) == 0) {
                c.watcherSees(HOME);
                c.gameModeRestore();
            } else {
                c.gameModeRestore();
                c.watcherSees(HOME);
            }
            check("order " + order + ": chosen back after", BALANCED, c.chosen);
            check("order " + order + ": home screen's own after", BALANCED, c.kernel());
        }

        // Game mode's profile still counts in a game without its own
        final Console c = new Console(SAVER);
        c.watcherSees(RA);
        c.gameModeSet(PERFORMANCE);
        check("RetroArch follows game mode's profile", PERFORMANCE, c.kernel());
        c.gameModeRestore();
        c.watcherSees(FILES);
        check("chosen Battery saver back", SAVER, c.kernel());

        // Tile in PSP: lasts until PSP is left, the chosen profile never moves
        final Console t = new Console(SAVER);
        t.watcherSees(PSP);
        check("PSP Performance over chosen Battery saver", PERFORMANCE, t.kernel());
        t.tile(BALANCED);
        check("tile pick in PSP", BALANCED, t.kernel());
        check("chosen untouched by the pick", SAVER, t.chosen);
        t.watcherSees(EDITOR);
        t.watcherSees(PSP);
        check("pick kept over the touch editor", BALANCED, t.kernel());
        t.watcherSees(FILES);
        check("chosen back after PSP", SAVER, t.kernel());
        t.tile(PERFORMANCE);
        check("tile in an app without its own changes the chosen", PERFORMANCE, t.chosen);
        t.watcherSees(PSP);
        t.watcherSees(VLC);
        check("VLC has no own profile in this model", PERFORMANCE, t.kernel());

        // Game mode switched off by hand in PSP after it picked a profile
        final Console g = new Console(BALANCED);
        g.watcherSees(PSP);
        g.gameModeSet(SAVER);
        g.gameModeRestore();
        check("game mode off in PSP: PSP's own", PERFORMANCE, g.kernel());
        check("game mode off in PSP: chosen back", BALANCED, g.chosen);
    }
}
