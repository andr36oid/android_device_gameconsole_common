package org.andr36oid.gamecomfort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Host test of the low battery warning levels (plain Java, no android.*). */
public final class LowBatteryTest {

    private static int sFailed;
    private static int sRun;

    private static void check(String name, Object want, Object got) {
        sRun++;
        final boolean ok = want instanceof int[]
                ? Arrays.equals((int[]) want, (int[]) got) : want.equals(got);
        if (!ok) {
            sFailed++;
            System.out.println("FAIL " + name + ": want " + str(want) + ", got " + str(got));
        }
    }

    private static String str(Object o) {
        return o instanceof int[] ? Arrays.toString((int[]) o) : String.valueOf(o);
    }

    /** Runs a discharge: levels on battery (negative = plugged in at -level). */
    private static List<Integer> run(String setting, int... levels) {
        final int[] thresholds = LowBattery.withLast(LowBattery.parse(setting));
        int warned = LowBattery.NONE;
        final List<Integer> shown = new ArrayList<>();
        for (int l : levels) {
            final boolean plugged = l < 0;
            final int percent = plugged ? -l : l;
            final int warn = LowBattery.warnAbout(thresholds, warned, percent, plugged);
            warned = LowBattery.nextWarned(thresholds, warned, percent, plugged);
            if (warn != LowBattery.NONE) {
                // the threshold warned about, negative for the last (long) warning
                shown.add(LowBattery.isLast(percent) ? -warn : warn);
            }
        }
        return shown;
    }

    public static void main(String[] args) {
        // withLast
        check("off stays off", new int[0], LowBattery.withLast(LowBattery.parse("off")));
        check("empty stays off", new int[0], LowBattery.withLast(LowBattery.parse("")));
        check("default gets 3", new int[] {15, 5, 3},
                LowBattery.withLast(LowBattery.parse("15,5")));
        check("5 only gets 3", new int[] {5, 3}, LowBattery.withLast(LowBattery.parse("5")));
        check("3 not twice", new int[] {10, 3}, LowBattery.withLast(LowBattery.parse("10,3")));

        // isLast
        check("3 is last", true, LowBattery.isLast(3));
        check("0 is last", true, LowBattery.isLast(0));
        check("4 is not", false, LowBattery.isLast(4));
        check("unknown is not", false, LowBattery.isLast(-1));

        // Discharges; a negative entry in the result is the last (long) warning
        check("full discharge", Arrays.asList(15, 5, -3),
                run("15,5", 20, 16, 15, 14, 6, 5, 4, 3, 2, 1, 0));
        check("default discharge", Arrays.asList(30, 20, 15, 5, -3),
                run("30,20,15,5", 40, 31, 30, 25, 20, 16, 15, 6, 5, 3, 1));
        check("5 only", Arrays.asList(5, -3), run("5", 20, 15, 5, 4, 3, 2));
        check("off never warns", Arrays.asList(), run("off", 20, 15, 5, 3, 1, 0));
        check("jump past 5 gives only the last", Arrays.asList(15, -3),
                run("15,5", 16, 15, 7, 2, 1));
        check("start at 2", Arrays.asList(-3), run("15,5", 2, 1, 0));
        check("start at 4 warns once for 5", Arrays.asList(5, -3), run("15,5", 4, 3));
        check("start at 6 warns once for 15", Arrays.asList(15, 5, -3), run("15,5", 6, 5, 3));
        final int[] d = LowBattery.withLast(LowBattery.parse("15,5"));
        check("unknown level: no warning", LowBattery.NONE, LowBattery.warnAbout(d, 5, -1, false));
        check("unknown level: state kept", 5, LowBattery.nextWarned(d, 5, -1, false));
        check("charger at 3 then unplugged again at 3: no repeat", Arrays.asList(15, 5, -3),
                run("15,5", 15, 5, 3, -3, 3, 2));
        check("charged above 3 then down again: last again", Arrays.asList(15, 5, -3, -3),
                run("15,5", 15, 5, 3, -4, -5, 4, 3));
        check("charged above 15: all again", Arrays.asList(15, 5, -3, 15, 5, -3),
                run("15,5", 15, 5, 3, -20, 15, 5, 3));
        // Old installs: the warned state from before the update was 5, then 3 comes
        int[] t = LowBattery.withLast(LowBattery.parse("15,5"));
        check("update mid-discharge", 3, LowBattery.warnAbout(t, 5, 3, false));

        System.out.println((sRun - sFailed) + "/" + sRun + " passed");
        if (sFailed > 0) {
            System.exit(1);
        }
    }
}
