package org.andr36oid.buttonmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that a button layout keeps the console usable. Everything needed to get around the
 * system, and back into this screen to fix things, has to stay on at least one button that
 * this console really has.
 */
final class LayoutValidator {
    static final int UP = 0;
    static final int DOWN = 1;
    static final int LEFT = 2;
    static final int RIGHT = 3;
    static final int SELECT = 4;
    static final int BACK = 5;
    static final int HOME = 6;
    static final int POWER = 7;
    static final int FUNCTION_COUNT = 8;

    // The actions that provide each function. The key character maps of the built-in devices
    // (Vendor_484b_Product_1100.kcm and Generic.kcm) let A fall back to DPAD_CENTER, and B and
    // Escape fall back to BACK, so they count too.
    private static final String[][] PROVIDERS = {
            { "DPAD_UP" },
            { "DPAD_DOWN" },
            { "DPAD_LEFT" },
            { "DPAD_RIGHT" },
            { "BUTTON_A", "DPAD_CENTER", "ENTER" },
            { "BUTTON_B", "BACK", "ESCAPE" },
            { "HOME" },
            { "POWER" },
    };

    private LayoutValidator() {
    }

    /**
     * Returns the functions (UP to POWER) the layout loses, empty when it is safe to use.
     * Functions the stock layout does not provide on this console are not required.
     *
     * @param actions what each button does, indexed like {@code defaults}
     * @param defaults what each button does with the stock layout
     * @param present whether each button exists on this console
     */
    static List<Integer> findMissing(String[] actions, String[] defaults, boolean[] present) {
        final List<Integer> missing = new ArrayList<>();
        for (int function = 0; function < FUNCTION_COUNT; function++) {
            if (isProvided(function, defaults, present) && !isProvided(function, actions, present)) {
                missing.add(function);
            }
        }
        return missing;
    }

    /** Returns whether an action on its own provides one of the functions. */
    static boolean provides(String action, int function) {
        for (String provider : PROVIDERS[function]) {
            if (provider.equals(action)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProvided(int function, String[] actions, boolean[] present) {
        for (int i = 0; i < actions.length; i++) {
            if (present[i] && provides(actions[i], function)) {
                return true;
            }
        }
        return false;
    }
}
