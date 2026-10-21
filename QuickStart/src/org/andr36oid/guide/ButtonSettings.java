package org.andr36oid.guide;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which buttons this console has, and what stands in for a missing Fn button. Both live in
 * Settings.Global; the window manager, the button mapper, joyMouse and the guide read them too.
 * A copy of this class is in Controllers (org.andr36oid.controllers.ButtonSettings): keep them
 * alike.
 */
final class ButtonSettings {

    private static final String TAG = "ButtonSettings";

    /**
     * Comma list from {fn,l3,r3,l2,r2,vol}. Unset: not checked yet, the device tree hint
     * counts. "": checked, nothing missing.
     */
    static final String MISSING = "andr36oid_missing_buttons";
    /** Unset or "": the real Fn button. "vol_both", or "scan:<scan code>" of the stand-in. */
    static final String FN_STANDIN = "andr36oid_fn_standin";
    /** Vol+ and Vol- together, before the combos: reads as START_VOL_DOWN now */
    static final String VOL_BOTH = "vol_both";
    static final String SCAN_PREFIX = "scan:";
    /** "combo:<a>+<b>": hold one of the two buttons and press the other to open Fn */
    static final String COMBO_PREFIX = "combo:";
    static final String START_VOL_DOWN = "combo:315+114";
    static final String START_VOL_UP = "combo:315+115";
    static final String L2_R2 = "combo:312+313";
    static final int SCAN_POWER = 116;
    /** Known boards: andr36oid,missing-buttons = "fn", "l3"; in the joypad node */
    private static final String DT_HINT =
            "/proc/device-tree/odroidgo3-joypad/andr36oid,missing-buttons";

    /** The buttons the check can find missing, in the order they're listed */
    static final String[] OPTIONAL = {"fn", "l2", "r2", "l3", "r3", "vol"};

    static final int SCAN_FN = 172;
    static final int SCAN_EXTRA_FIRST = 704;  // BTN_TRIGGER_HAPPY1
    static final int SCAN_EXTRA_LAST = 719;   // BTN_TRIGGER_HAPPY16

    private ButtonSettings() {
    }

    /** The saved list, or null if the buttons were never checked. */
    static Set<String> saved(Context context) {
        final String value = Settings.Global.getString(context.getContentResolver(), MISSING);
        return value == null ? null : parse(value);
    }

    /** What the device tree says for known boards; empty if it says nothing. */
    static Set<String> hint() {
        try {
            final byte[] data = Files.readAllBytes(Paths.get(DT_HINT));
            return parse(new String(data, StandardCharsets.UTF_8).replace('\0', ','));
        } catch (IOException | SecurityException e) {
            return new LinkedHashSet<>();
        }
    }

    /** The saved list, or the device tree hint while nothing was saved. */
    static Set<String> missing(Context context) {
        final Set<String> saved = saved(context);
        return saved != null ? saved : hint();
    }

    static boolean hasFn(Context context) {
        return !missing(context).contains("fn");
    }

    static void saveMissing(Context context, Set<String> missing) {
        final List<String> ordered = new ArrayList<>();
        for (String name : OPTIONAL) {
            if (missing.contains(name)) ordered.add(name);
        }
        Settings.Global.putString(context.getContentResolver(), MISSING,
                String.join(",", ordered));
    }

    /** Adds one button to the missing list, keeping the others (and the hint's, if unsaved) */
    static void addMissing(Context context, String name) {
        final Set<String> missing = missing(context);
        missing.add(name);
        saveMissing(context, missing);
    }

    /** Takes one button off the missing list: the console has it after all */
    static void removeMissing(Context context, String name) {
        final Set<String> missing = missing(context);
        if (missing.remove(name)) saveMissing(context, missing);
    }

    private static Set<String> parse(String value) {
        final Set<String> names = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            final String name = part.trim().toLowerCase();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    /**
     * "", "combo:<a>+<b>" or "scan:<code>"; with a missing Fn and nothing chosen, and for the
     * old VOL_BOTH, START_VOL_DOWN.
     */
    static String fnStandin(Context context) {
        final String value = Settings.Global.getString(context.getContentResolver(),
                FN_STANDIN);
        if (value == null || value.isEmpty()) {
            return hasFn(context) ? "" : START_VOL_DOWN;
        }
        return VOL_BOTH.equals(value) ? START_VOL_DOWN : value;
    }

    static void setFnStandin(Context context, String value) {
        Log.i(TAG, "Fn stand-in: " + value);
        Settings.Global.putString(context.getContentResolver(), FN_STANDIN, value);
    }

    /** The two scan codes of a "combo:<a>+<b>" stand-in, or null. */
    static int[] comboScanCodes(String standin) {
        if (VOL_BOTH.equals(standin)) standin = START_VOL_DOWN;
        if (standin == null || !standin.startsWith(COMBO_PREFIX)) return null;
        final String[] parts = standin.substring(COMBO_PREFIX.length()).split("\\+");
        if (parts.length != 2) return null;
        try {
            final int a = Integer.parseInt(parts[0].trim());
            final int b = Integer.parseInt(parts[1].trim());
            return a > 0 && b > 0 && a != b ? new int[] {a, b} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String combo(int a, int b) {
        return COMBO_PREFIX + a + "+" + b;
    }

    /**
     * Why two buttons can't be the Fn combo, as a string with the two names as %1$s and %2$s,
     * or 0 when they can. The system already uses some pairs.
     */
    static int comboRefusal(int a, int b) {
        if (a == SCAN_POWER || b == SCAN_POWER) return R.string.fn_combo_refused_power;
        if (a == SCAN_FN || b == SCAN_FN) return R.string.fn_combo_refused_fn;
        if (pair(a, b, 114, 115)) return R.string.fn_combo_refused_reset;
        // joyMouse's mouse mode switch, Select + Start or L3 + R3 depending on the console
        if (pair(a, b, 314, 315) || pair(a, b, 317, 318)) return R.string.fn_combo_refused_mouse;
        return 0;
    }

    private static boolean pair(int a, int b, int x, int y) {
        return (a == x && b == y) || (a == y && b == x);
    }

    /** The scan code of a "scan:<code>" stand-in, or -1. */
    static int standinScanCode(String standin) {
        if (standin == null || !standin.startsWith(SCAN_PREFIX)) return -1;
        try {
            return Integer.parseInt(standin.substring(SCAN_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- Names

    /** A built-in button: its scan code, the name for missing lists (or null) and its label */
    static final class Button {
        final int scanCode;
        final String optional;
        final int label;

        Button(int scanCode, String optional, int label) {
            this.scanCode = scanCode;
            this.optional = optional;
            this.label = label;
        }
    }

    /** The built-in buttons apps can see, in the order of the check page (see HardwareButton
     *  in ButtonMapper for the scan codes). Power is left out: it never reaches apps. */
    static final Button[] BUILT_IN = {
            new Button(304, null, R.string.button_a),
            new Button(305, null, R.string.button_b),
            new Button(307, null, R.string.button_x),
            new Button(308, null, R.string.button_y),
            new Button(310, null, R.string.button_l1),
            new Button(311, null, R.string.button_r1),
            new Button(312, "l2", R.string.button_l2),
            new Button(313, "r2", R.string.button_r2),
            new Button(158, "l3", R.string.button_l3),
            new Button(125, "r3", R.string.button_r3),
            new Button(314, null, R.string.button_select),
            new Button(315, null, R.string.button_start),
            new Button(103, null, R.string.button_up),
            new Button(108, null, R.string.button_down),
            new Button(105, null, R.string.button_left),
            new Button(106, null, R.string.button_right),
            new Button(SCAN_FN, "fn", R.string.button_fn),
            new Button(115, "vol", R.string.button_vol_up),
            new Button(114, "vol", R.string.button_vol_down),
    };

    static Button builtIn(int scanCode) {
        for (Button button : BUILT_IN) {
            if (button.scanCode == scanCode) return button;
        }
        return null;
    }

    static boolean isExtra(int scanCode) {
        return scanCode >= SCAN_EXTRA_FIRST && scanCode <= SCAN_EXTRA_LAST;
    }

    /** "L3", "Extra button 2", "Button 123" */
    static String buttonName(Context context, int scanCode) {
        final Button button = builtIn(scanCode);
        if (button != null) return context.getString(button.label);
        if (isExtra(scanCode)) {
            return context.getString(R.string.button_extra, scanCode - SCAN_EXTRA_FIRST + 1);
        }
        return context.getString(R.string.button_other, scanCode);
    }

    /** "All buttons", or "No Fn, no L3/R3" */
    static String describeMissing(Context context, Set<String> missing) {
        final List<String> parts = listMissing(context, missing);
        if (parts.isEmpty()) return context.getString(R.string.buttons_all);
        final StringBuilder text = new StringBuilder(
                context.getString(R.string.buttons_missing_first, parts.get(0)));
        for (int i = 1; i < parts.size(); i++) {
            text.append(context.getString(R.string.buttons_missing_more, parts.get(i)));
        }
        return text.toString();
    }

    /** "Fn", "L3/R3", "volume buttons" */
    static List<String> listMissing(Context context, Set<String> missing) {
        final List<String> parts = new ArrayList<>();
        if (missing.contains("fn")) parts.add(context.getString(R.string.button_fn));
        addPair(context, parts, missing, "l2", "r2", R.string.button_l2, R.string.button_r2);
        addPair(context, parts, missing, "l3", "r3", R.string.button_l3, R.string.button_r3);
        if (missing.contains("vol")) parts.add(context.getString(R.string.buttons_missing_volume));
        return parts;
    }

    private static void addPair(Context context, List<String> parts, Set<String> missing,
            String left, String right, int leftLabel, int rightLabel) {
        if (missing.contains(left) && missing.contains(right)) {
            parts.add(context.getString(leftLabel) + "/" + context.getString(rightLabel));
        } else if (missing.contains(left)) {
            parts.add(context.getString(leftLabel));
        } else if (missing.contains(right)) {
            parts.add(context.getString(rightLabel));
        }
    }

    /** The Controls summary: the saved state, or the hint while nothing was checked */
    static String summary(Context context) {
        final Set<String> saved = saved(context);
        if (saved != null) return describeMissing(context, saved);
        final Set<String> hint = hint();
        return hint.isEmpty() ? context.getString(R.string.buttons_not_checked)
                : context.getString(R.string.buttons_hint, describeMissing(context, hint));
    }

    /** "The Fn button", "Start + Vol−", "L3 works as Fn" */
    static String describeStandin(Context context, String standin) {
        final int[] combo = comboScanCodes(standin);
        if (combo != null) {
            return context.getString(R.string.fn_standin_combo, buttonName(context, combo[0]),
                    buttonName(context, combo[1]));
        }
        final int scanCode = standinScanCode(standin);
        if (scanCode >= 0) {
            return context.getString(R.string.fn_standin_button, buttonName(context, scanCode));
        }
        return context.getString(R.string.fn_standin_fn);
    }
}
