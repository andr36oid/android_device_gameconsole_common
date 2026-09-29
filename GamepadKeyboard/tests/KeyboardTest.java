import java.util.List;
import org.andr36oid.keyboard.core.Accents;
import org.andr36oid.keyboard.core.Buttons;
import org.andr36oid.keyboard.core.GridNav;
import org.andr36oid.keyboard.core.Key;
import org.andr36oid.keyboard.core.KeyboardLogic;
import org.andr36oid.keyboard.core.Layouts;
import org.andr36oid.keyboard.core.Page;
import org.andr36oid.keyboard.core.Radial;
import org.andr36oid.keyboard.core.StickRepeat;

/** Host tests for the keyboard logic: plain Java, run by run-tests.sh. */
public class KeyboardTest {
    private static int passed;
    private static int failed;

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void eq(Object want, Object got, String what) {
        check(want == null ? got == null : want.equals(got), what + ": want <" + want + "> got <" + got + ">");
    }

    public static void main(String[] args) {
        layouts();
        navigation();
        pageSwitch();
        typing();
        shift();
        accents();
        radial();
        stickRepeat();
        thumbClicks();
        buttons();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void layouts() {
        for (String fam : new String[] {Layouts.QWERTY, Layouts.QWERTZ, Layouts.AZERTY}) {
            Page p = Layouts.letters(fam);
            eq(5, p.rowCount(), fam + " rows");
            for (int r = 0; r < p.rowCount(); r++) {
                eq(10f, p.rowWidth(r), fam + " row " + r + " width");
            }
            // all 26 letters present exactly once
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < p.rowCount(); r++) {
                for (Key k : p.row(r)) if (k.isLetter()) sb.append(k.text);
            }
            char[] cs = sb.toString().toCharArray();
            java.util.Arrays.sort(cs);
            eq("abcdefghijklmnopqrstuvwxyz", new String(cs), fam + " letters");
            check(p.find(Key.ENTER) != null && p.find(Key.SHIFT) != null
                    && p.find(Key.DELETE) != null && p.find(Key.SPACE) != null
                    && p.find(Key.SWITCH_IME) != null && p.find(Key.PAGE) != null,
                    fam + " special keys");
        }
        Page s = Layouts.symbols();
        for (int r = 0; r < s.rowCount(); r++) eq(10f, s.rowWidth(r), "symbols row " + r);
        eq("z", Layouts.letters(Layouts.QWERTZ).key(1, 5).text, "qwertz z");
        eq("a", Layouts.letters(Layouts.AZERTY).key(1, 0).text, "azerty a");

        eq(Layouts.QWERTZ, Layouts.familyFor("de", "DE"), "de");
        eq(Layouts.QWERTZ, Layouts.familyFor("de", "AT"), "de-AT");
        eq(Layouts.QWERTZ, Layouts.familyFor("cs", ""), "cs");
        eq(Layouts.AZERTY, Layouts.familyFor("fr", "FR"), "fr");
        eq(Layouts.AZERTY, Layouts.familyFor("fr", "BE"), "fr-BE");
        eq(Layouts.QWERTY, Layouts.familyFor("fr", "CA"), "fr-CA");
        eq(Layouts.QWERTZ, Layouts.familyFor("fr", "CH"), "fr-CH");
        eq(Layouts.AZERTY, Layouts.familyFor("nl", "BE"), "nl-BE");
        eq(Layouts.QWERTY, Layouts.familyFor("nl", "NL"), "nl");
        eq(Layouts.QWERTY, Layouts.familyFor("en", "US"), "en");
        eq(Layouts.QWERTY, Layouts.familyFor("ru", "RU"), "ru falls back to latin");
        eq(Layouts.QWERTY, Layouts.familyFor("ja", ""), "ja");
        eq(Layouts.QWERTY, Layouts.familyFor(null, null), "null");
        eq(Layouts.AZERTY, Layouts.resolve("azerty", "en", "US"), "forced azerty");
        eq(Layouts.QWERTZ, Layouts.resolve("auto", "de", "DE"), "auto");
        eq(Layouts.QWERTY, Layouts.resolve("bogus", "en", ""), "bad pref");
    }

    static void navigation() {
        Page p = Layouts.letters(Layouts.QWERTY);
        GridNav n = new GridNav(p);
        eq(2, n.row(), "home row");
        eq("g", n.current().text, "home key");
        n.move(GridNav.UP);
        eq("t", n.current().text, "up from g"); // g centre 4.5 -> row1 col 4 't'
        n.move(GridNav.UP);
        eq("5", n.current().text, "up to digits");
        n.move(GridNav.UP);
        eq(Key.SPACE, n.current().code, "up wraps to bottom row (space)");
        n.move(GridNav.DOWN);
        eq("5", n.current().text, "down wraps to top, same column");
        // left/right wrap
        n = new GridNav(p);
        for (int i = 0; i < 4; i++) n.move(GridNav.LEFT);
        eq("a", n.current().text, "left to a");
        n.move(GridNav.LEFT);
        eq("-", n.current().text, "left wraps to end of row");
        n.move(GridNav.RIGHT);
        eq("a", n.current().text, "right wraps to start");
        // column memory through the wide space key
        n = new GridNav(p);
        n.move(GridNav.RIGHT); // h, centre 5.5
        n.move(GridNav.DOWN);  // row3: shift(0-1.5) z x c v b(5.5-6.5) -> centre 5.5 -> 'b'
        eq("b", n.current().text, "down from h");
        n.move(GridNav.DOWN);  // bottom: page(0-1.5) kbd(1.5-2.5) ,(2.5-3.5) space(3.5-6.5)
        eq(Key.SPACE, n.current().code, "down to space");
        n.move(GridNav.UP);
        n.move(GridNav.UP);
        eq("h", n.current().text, "back up to h (anchor kept)");
        // shift key row: moving right from shift
        n = new GridNav(p);
        check(n.focus(Key.SHIFT), "focus shift");
        n.move(GridNav.RIGHT);
        eq("z", n.current().text, "right of shift");
        n.move(GridNav.UP);
        eq("d", n.current().text, "up from z (centre 2.0 is in d)");
    }

    static void pageSwitch() {
        KeyboardLogic l = new KeyboardLogic(Layouts.QWERTY, "en", false);
        l.down(Buttons.NAV_UP, 0);
        eq("t", l.nav().current().text, "t");
        l.down(Buttons.PAGE, 0);
        check(l.onSymbols(), "on symbols");
        eq("&", l.nav().current().text, "same spot on symbols page");
        l.down(Buttons.PAGE, 3); // held Select repeats: ignored
        check(l.onSymbols(), "held select ignored");
        l.down(Buttons.PAGE, 0);
        eq("t", l.nav().current().text, "back to letters");
    }

    static String texts(List<KeyboardLogic.Effect> es) {
        StringBuilder sb = new StringBuilder();
        for (KeyboardLogic.Effect e : es) sb.append(e.toString()).append(' ');
        return sb.toString().trim();
    }

    static void typing() {
        KeyboardLogic l = new KeyboardLogic(Layouts.QWERTY, "en", false);
        l.down(Buttons.NAV_RIGHT, 0); // h (no accents): types on down
        eq("TEXT(h)", texts(l.down(Buttons.PRESS, 0)), "type h");
        eq("TEXT(h)", texts(l.down(Buttons.PRESS, 1)), "held h repeats");
        eq("", texts(l.up(Buttons.PRESS, 600)), "h release");
        l.down(Buttons.NAV_LEFT, 0); // g: has accents -> typed on release
        eq("", texts(l.down(Buttons.PRESS, 0)), "g down waits");
        eq("TEXT(g)", texts(l.up(Buttons.PRESS, 80)), "g on release");
        eq("TEXT( )", texts(l.down(Buttons.SPACE, 0)), "X space");
        eq("BACKSPACE!", texts(l.down(Buttons.BACKSPACE, 0)), "B fresh");
        eq("BACKSPACE", texts(l.down(Buttons.BACKSPACE, 4)), "B held");
        eq("ENTER", texts(l.down(Buttons.ENTER, 0)), "Start");
        eq("", texts(l.down(Buttons.ENTER, 2)), "Start held: once");
        eq("CURSOR_LEFT", texts(l.down(Buttons.CURSOR_LEFT, 0)), "L1");
        eq("CURSOR_RIGHT", texts(l.down(Buttons.CURSOR_RIGHT, 5)), "R1 repeats");
        eq("HIDE", texts(l.down(Buttons.HIDE, 0)), "L2");
        eq("BIG_TEXT", texts(l.down(Buttons.BIG_TEXT, 0)), "R2");
        // grid special keys
        l.nav().focus(Key.ENTER);
        eq("ENTER", texts(l.down(Buttons.PRESS, 0)), "enter key");
        l.nav().focus(Key.DELETE);
        eq("BACKSPACE", texts(l.down(Buttons.PRESS, 0)), "delete key never closes");
        eq("BACKSPACE", texts(l.down(Buttons.PRESS, 1)), "delete key repeats");
        l.nav().focus(Key.SWITCH_IME);
        eq("SWITCH_IME", texts(l.down(Buttons.PRESS, 0)), "keyboard key");
        l.nav().focus(Key.PAGE);
        l.down(Buttons.PRESS, 0);
        check(l.onSymbols(), "page key");
        l.reset();
        check(!l.onSymbols(), "reset to letters");
        eq("g", l.nav().current().text, "reset to home");
    }

    static void shift() {
        KeyboardLogic l = new KeyboardLogic(Layouts.QWERTY, "en", false);
        l.down(Buttons.SHIFT, 0);
        eq(KeyboardLogic.SHIFT_ONCE, l.shift(), "Y once");
        l.down(Buttons.PRESS, 0);
        eq("TEXT(G)", texts(l.up(Buttons.PRESS, 50)), "capital G");
        eq(KeyboardLogic.SHIFT_OFF, l.shift(), "shift used up");
        l.down(Buttons.SHIFT, 0);
        l.down(Buttons.SHIFT, 0);
        eq(KeyboardLogic.SHIFT_LOCK, l.shift(), "caps lock");
        l.down(Buttons.PRESS, 0);
        l.up(Buttons.PRESS, 50);
        eq(KeyboardLogic.SHIFT_LOCK, l.shift(), "lock stays");
        l.autoCaps(false);
        eq(KeyboardLogic.SHIFT_LOCK, l.shift(), "auto caps leaves lock");
        l.down(Buttons.SHIFT, 0);
        eq(KeyboardLogic.SHIFT_OFF, l.shift(), "third Y off");
        l.autoCaps(true);
        eq(KeyboardLogic.SHIFT_ONCE, l.shift(), "auto caps");
        l.autoCaps(false);
        eq(KeyboardLogic.SHIFT_OFF, l.shift(), "auto caps withdrawn");
        l.down(Buttons.SHIFT, 0);
        l.autoCaps(false);
        eq(KeyboardLogic.SHIFT_ONCE, l.shift(), "manual shift survives auto caps");
        l.autoCaps(true);
        l.down(Buttons.NAV_UP, 0);
        l.down(Buttons.NAV_UP, 0); // digit 5
        eq("TEXT(5)", texts(l.down(Buttons.PRESS, 0)), "digit not shifted");
        eq(KeyboardLogic.SHIFT_ONCE, l.shift(), "digit keeps shift");
    }

    static void accents() {
        List<String> v = Accents.variants("e", "fr", false);
        eq("e", v.get(0), "base first");
        eq("é", v.get(1), "french é first");
        eq("è", v.get(2), "then è");
        check(v.contains("ě") && v.contains("ë"), "rest included");
        List<String> d = Accents.variants("s", "de", true);
        eq("S", d.get(0), "shifted base");
        eq("ß", d.get(1), "ß stays ß when shifted");
        check(d.contains("Š"), "shifted š");
        check(!Accents.has("h"), "h has none");
        eq(1, Accents.variants("h", "en", false).size(), "no variants list");

        KeyboardLogic l = new KeyboardLogic(Layouts.QWERTY, "de", false);
        l.down(Buttons.NAV_UP, 0); // t
        l.down(Buttons.NAV_RIGHT, 0); // y
        l.down(Buttons.NAV_RIGHT, 0); // u
        eq("u", l.nav().current().text, "on u");
        l.down(Buttons.PRESS, 0);
        l.down(Buttons.PRESS, 1); // held -> strip opens
        check(l.variants() != null, "strip open");
        eq("ü", l.variants().get(l.variantIndex()), "german ü preselected");
        eq("", texts(l.up(Buttons.PRESS, 700)), "release types nothing");
        l.down(Buttons.NAV_RIGHT, 0);
        eq("ù", l.variants().get(l.variantIndex()), "next variant");
        l.down(Buttons.NAV_LEFT, 0);
        l.down(Buttons.NAV_LEFT, 0);
        eq("u", l.variants().get(l.variantIndex()), "back to base");
        eq("TEXT(u)", texts(l.down(Buttons.PRESS, 0)), "type from strip");
        check(l.variants() == null, "strip closed");
        eq("", texts(l.up(Buttons.PRESS, 50)), "its release is swallowed");
        // B cancels without deleting
        l.down(Buttons.PRESS, 0);
        l.down(Buttons.PRESS, 1);
        l.up(Buttons.PRESS, 600);
        eq("", texts(l.down(Buttons.BACKSPACE, 0)), "B closes strip");
        check(l.variants() == null, "closed by B");
        // no key repeat at all: a long hold still opens the strip on release
        l.down(Buttons.PRESS, 0);
        eq("", texts(l.up(Buttons.PRESS, 900)), "long hold, no repeats");
        check(l.variants() != null, "opened on release");
        // X while open: closes and types a space
        eq("TEXT( )", texts(l.down(Buttons.SPACE, 0)), "X falls through");
        check(l.variants() == null, "closed by X");
    }

    static void radial() {
        eq(0, Radial.sector(0f, -1f, 8), "up");
        eq(2, Radial.sector(1f, 0f, 8), "right");
        eq(4, Radial.sector(0f, 1f, 8), "down");
        eq(6, Radial.sector(-1f, 0f, 8), "left");
        eq(1, Radial.sector(0.7f, -0.7f, 8), "up-right");
        eq(7, Radial.sector(-0.7f, -0.7f, 8), "up-left");
        eq(0, Radial.sector(-0.2f, -1f, 8), "near up");
        eq(1, Radial.sector(1f, 0f, 4), "4: right");
        eq(3, Radial.sector(-1f, 0.1f, 4), "4: left");
        // every letter reachable once
        StringBuilder sb = new StringBuilder();
        for (String g : Radial.LETTERS) sb.append(g);
        String all = sb.toString();
        for (char c = 'a'; c <= 'z'; c++) eq(1, all.length() - all.replace(String.valueOf(c), "").length(), "radial has " + c);
        for (String g : Radial.SYMBOLS) eq(4, g.length(), "symbol group size");

        KeyboardLogic l = new KeyboardLogic(Layouts.QWERTY, "en", true);
        eq(KeyboardLogic.MODE_STICKS, l.mode(), "starts in stick mode");
        l.leftStick(1f, 0f, 0); // group 2: ijkl
        eq(2, l.radial().group(), "group ijkl");
        l.leftStick(0f, 0f, 10);
        eq(2, l.radial().group(), "group sticks after release");
        eq("", texts(l.rightStick(0f, 1f, 0)), "point down (k)");
        eq(2, l.radial().slot(), "slot down");
        eq("", texts(l.rightStick(0.4f, 0.1f, 0)), "between thresholds: nothing");
        eq("TEXT(k)", texts(l.rightStick(0.05f, 0f, 0)), "release types k");
        eq("", texts(l.rightStick(0f, 0f, 0)), "nothing more");
        // change mind before release
        l.rightStick(0f, -1f, 0); // i
        l.rightStick(-1f, 0f, 0); // l
        eq("TEXT(l)", texts(l.rightStick(0f, 0f, 0)), "last pointed wins");
        // A while pointing types right away; release then types nothing
        l.rightStick(1f, 0f, 0); // j
        eq("TEXT(j)", texts(l.down(Buttons.PRESS, 0)), "A types pointed");
        eq("TEXT(j)", texts(l.down(Buttons.PRESS, 0)), "A again types again");
        eq("", texts(l.rightStick(0f, 0f, 0)), "release after A: nothing");
        eq("", texts(l.down(Buttons.PRESS, 0)), "A with stick centred: nothing");
        // shift in stick mode
        l.down(Buttons.SHIFT, 0);
        l.rightStick(0f, -1f, 0);
        eq("TEXT(I)", texts(l.rightStick(0f, 0f, 0)), "shifted I");
        eq(KeyboardLogic.SHIFT_OFF, l.shift(), "shift used");
        // d-pad rotates the group
        l.down(Buttons.NAV_RIGHT, 0);
        eq(3, l.radial().group(), "dpad right rotates");
        l.down(Buttons.NAV_UP, 0);
        eq(1, l.radial().group(), "dpad up rotates back 2");
        // symbols
        l.down(Buttons.PAGE, 0);
        l.leftStick(0f, -1f, 20);
        l.rightStick(0f, -1f, 0);
        eq("TEXT(1)", texts(l.rightStick(0f, 0f, 0)), "symbols group 0 slot 0");
        // right stick from grid switches to stick mode
        KeyboardLogic g = new KeyboardLogic(Layouts.QWERTY, "en", false);
        eq("", texts(g.rightStick(0.2f, 0f, 0)), "small push: nothing");
        eq(KeyboardLogic.MODE_GRID, g.mode(), "still grid");
        g.rightStick(0f, -1f, 1000);
        eq(KeyboardLogic.MODE_GRID, g.mode(), "a short push: still grid");
        g.rightStick(0f, 0f, 1100);
        g.rightStick(0f, -1f, 2000);
        eq(KeyboardLogic.MODE_GRID, g.mode(), "released in between: starts again");
        eq(2000 + KeyboardLogic.RIGHT_SWITCH_MS, g.nextTick(), "timer for the switch");
        eq(true, g.tick(2000 + KeyboardLogic.RIGHT_SWITCH_MS), "held without new events");
        eq(KeyboardLogic.MODE_STICKS, g.mode(), "held: stick mode");
        eq("TEXT(a)", texts(g.rightStick(0f, 0f, 2400)), "and it typed group 0 slot 0");
        KeyboardLogic h = new KeyboardLogic(Layouts.QWERTY, "en", false);
        h.rightStick(0f, -1f, 0);
        h.rightStick(0f, -0.9f, KeyboardLogic.RIGHT_SWITCH_MS);
        eq(KeyboardLogic.MODE_STICKS, h.mode(), "held with motion events: stick mode");
    }

    static void stickRepeat() {
        StickRepeat s = new StickRepeat();
        eq(-1, s.update(0.3f, 0f, 0), "dead zone");
        eq(GridNav.RIGHT, s.update(0.9f, 0.2f, 0), "push right");
        eq(-1, s.update(0.95f, 0.1f, 10), "still right: no new step");
        eq(-1, s.tick(100), "before first delay");
        eq(GridNav.RIGHT, s.tick(StickRepeat.FIRST_DELAY_MS), "first repeat");
        eq(-1, s.tick(StickRepeat.FIRST_DELAY_MS + 50), "between repeats");
        eq(GridNav.RIGHT, s.tick(StickRepeat.FIRST_DELAY_MS + StickRepeat.REPEAT_MS), "second repeat");
        eq(-1, s.update(0.5f, 0f, 600), "in hysteresis band: held");
        eq(GridNav.RIGHT, s.direction(), "still held");
        eq(-1, s.update(0.1f, 0f, 700), "let go");
        eq(-1, s.tick(10000), "no repeat after let go");
        eq(GridNav.UP, s.update(0.2f, -0.8f, 800), "up");
        eq(GridNav.LEFT, s.update(-0.9f, -0.3f, 820), "swing to left: new step");

        KeyboardLogic l = new KeyboardLogic(Layouts.QWERTY, "en", false);
        check(l.leftStick(-1f, 0f, 0), "stick moved highlight");
        eq("f", l.nav().current().text, "g -> f");
        eq((long) StickRepeat.FIRST_DELAY_MS, l.nextTick(), "timer due");
        check(l.tick(StickRepeat.FIRST_DELAY_MS), "repeat step");
        eq("d", l.nav().current().text, "f -> d");
        l.leftStick(0f, 0f, 500);
        eq(-1L, l.nextTick(), "timer off");
    }

    static void thumbClicks() {
        KeyboardLogic l = new KeyboardLogic(Layouts.QWERTY, "en", false);
        eq("", texts(l.down(Buttons.STICK_MODE, 0)), "R3 down");
        l.up(Buttons.STICK_MODE, 100);
        eq(KeyboardLogic.MODE_STICKS, l.mode(), "R3 click toggles");
        l.down(Buttons.STICK_MODE, 0);
        l.up(Buttons.STICK_MODE, 100);
        eq(KeyboardLogic.MODE_GRID, l.mode(), "and back");
        l.down(Buttons.SWITCH_IME, 0);
        eq("SWITCH_IME", texts(l.up(Buttons.SWITCH_IME, 100)), "L3 click");
        // L3 + R3 together (mouse mode combo): nothing
        l.down(Buttons.SWITCH_IME, 0);
        l.down(Buttons.STICK_MODE, 0);
        eq("", texts(l.up(Buttons.SWITCH_IME, 1000)), "combo L3 up");
        eq("", texts(l.up(Buttons.STICK_MODE, 1000)), "combo R3 up");
        eq(KeyboardLogic.MODE_GRID, l.mode(), "combo did not toggle");
        // held alone: nothing
        l.down(Buttons.STICK_MODE, 0);
        l.down(Buttons.STICK_MODE, 5);
        l.up(Buttons.STICK_MODE, 1200);
        eq(KeyboardLogic.MODE_GRID, l.mode(), "held R3 ignored");
        // next clean click works again
        l.down(Buttons.STICK_MODE, 0);
        l.up(Buttons.STICK_MODE, 100);
        eq(KeyboardLogic.MODE_STICKS, l.mode(), "clean click after combo");
    }

    static void buttons() {
        eq(Buttons.PRESS, Buttons.action(96), "A");
        eq(Buttons.PRESS, Buttons.action(23), "dpad centre");
        eq(Buttons.BACKSPACE, Buttons.action(97), "B");
        eq(Buttons.SPACE, Buttons.action(99), "X");
        eq(Buttons.SHIFT, Buttons.action(100), "Y");
        eq(Buttons.CURSOR_LEFT, Buttons.action(102), "L1");
        eq(Buttons.CURSOR_RIGHT, Buttons.action(103), "R1");
        eq(Buttons.HIDE, Buttons.action(104), "L2");
        eq(Buttons.BIG_TEXT, Buttons.action(105), "R2");
        eq(Buttons.SWITCH_IME, Buttons.action(106), "L3");
        eq(Buttons.STICK_MODE, Buttons.action(107), "R3");
        eq(Buttons.ENTER, Buttons.action(108), "Start");
        eq(Buttons.PAGE, Buttons.action(109), "Select");
        eq(Buttons.NAV_UP, Buttons.action(19), "up");
        eq(Buttons.NONE, Buttons.action(3), "HOME passes");
        eq(Buttons.NONE, Buttons.action(4), "BACK passes");
        eq(Buttons.NONE, Buttons.action(110), "MODE passes");
        eq(Buttons.NONE, Buttons.action(24), "volume passes");
        eq(Buttons.NONE, Buttons.action(66), "keyboard enter passes");
        eq(Buttons.NONE, Buttons.action(29), "keyboard letters pass");
    }
}
