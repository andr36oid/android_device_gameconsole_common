package org.andr36oid.buttonmapper;

import android.graphics.RectF;

/**
 * A built-in button: the Linux scan code it reports (see the odroidgo3-joypad and gpio-keys
 * nodes in the kernel device tree), what it does with the stock key layout
 * (Vendor_484b_Product_1100.kl, Generic.kl) and where ControllerView draws it.
 */
final class HardwareButton {
    static final int SHAPE_ROUND = 0;
    static final int SHAPE_PILL = 1;
    static final int SHAPE_STICK = 2;
    static final int SHAPE_DPAD = 3;

    static final int ICON_NONE = 0;
    static final int ICON_UP = 1;
    static final int ICON_DOWN = 2;
    static final int ICON_LEFT = 3;
    static final int ICON_RIGHT = 4;
    static final int ICON_POWER = 5;

    static final HardwareButton[] ALL = {
            dpad(103, "DPAD_UP", R.string.button_dpad_up, ICON_UP, 33, 37, 47, 51),
            dpad(108, "DPAD_DOWN", R.string.button_dpad_down, ICON_DOWN, 33, 65, 47, 79),
            dpad(105, "DPAD_LEFT", R.string.button_dpad_left, ICON_LEFT, 19, 51, 33, 65),
            dpad(106, "DPAD_RIGHT", R.string.button_dpad_right, ICON_RIGHT, 47, 51, 61, 65),
            round(307, "BUTTON_X", R.string.button_x, "X", 200, 44),
            round(308, "BUTTON_Y", R.string.button_y, "Y", 186, 58),
            round(304, "BUTTON_A", R.string.button_a, "A", 214, 58),
            round(305, "BUTTON_B", R.string.button_b, "B", 200, 72),
            pill(312, "BUTTON_L2", R.string.button_l2, "L2", false, 14, 3, 44, 16),
            pill(310, "BUTTON_L1", R.string.button_l1, "L1", false, 48, 3, 78, 16),
            pill(311, "BUTTON_R1", R.string.button_r1, "R1", false, 162, 3, 192, 16),
            pill(313, "BUTTON_R2", R.string.button_r2, "R2", false, 196, 3, 226, 16),
            stick(158, "BUTTON_THUMBL", R.string.button_l3, "L3", 60, 112),
            stick(125, "BUTTON_THUMBR", R.string.button_r3, "R3", 180, 112),
            pill(314, "BUTTON_SELECT", R.string.button_select, "SELECT", true, 88, 107, 108, 115),
            pill(172, "HOME", R.string.button_function, "FN", true, 110, 107, 130, 115),
            pill(315, "BUTTON_START", R.string.button_start, "START", true, 132, 107, 152, 115),
            pill(114, "VOLUME_DOWN", R.string.button_volume_down, "−", false, 87, 3, 105, 16),
            pill(115, "VOLUME_UP", R.string.button_volume_up, "+", false, 111, 3, 129, 16),
            // The only button that wakes the console from sleep, so it can't be changed. The
            // framework ignores remaps of it as well.
            new HardwareButton(116, "POWER", R.string.button_power, null, ICON_POWER, false,
                    SHAPE_PILL, new RectF(135, 3, 153, 16), true),
    };

    static final int KEY_VOLUMEDOWN = 114;
    static final int KEY_VOLUMEUP = 115;

    final int scanCode;
    final String defaultAction;
    final int nameRes;
    // Text drawn on or below the button, null when an icon is drawn instead.
    final String label;
    final int icon;
    final boolean labelBelow;
    final int shape;
    // Position on the drawing, in ControllerView units.
    final RectF bounds;
    final boolean locked;

    private HardwareButton(int scanCode, String defaultAction, int nameRes, String label,
            int icon, boolean labelBelow, int shape, RectF bounds, boolean locked) {
        this.scanCode = scanCode;
        this.defaultAction = defaultAction;
        this.nameRes = nameRes;
        this.label = label;
        this.icon = icon;
        this.labelBelow = labelBelow;
        this.shape = shape;
        this.bounds = bounds;
        this.locked = locked;
    }

    private static HardwareButton dpad(int scanCode, String defaultAction, int nameRes, int icon,
            float left, float top, float right, float bottom) {
        return new HardwareButton(scanCode, defaultAction, nameRes, null, icon, false,
                SHAPE_DPAD, new RectF(left, top, right, bottom), false);
    }

    private static HardwareButton round(int scanCode, String defaultAction, int nameRes,
            String label, float centerX, float centerY) {
        return new HardwareButton(scanCode, defaultAction, nameRes, label, ICON_NONE, false,
                SHAPE_ROUND, new RectF(centerX - 8, centerY - 8, centerX + 8, centerY + 8),
                false);
    }

    private static HardwareButton stick(int scanCode, String defaultAction, int nameRes,
            String label, float centerX, float centerY) {
        return new HardwareButton(scanCode, defaultAction, nameRes, label, ICON_NONE, false,
                SHAPE_STICK, new RectF(centerX - 14, centerY - 14, centerX + 14, centerY + 14),
                false);
    }

    private static HardwareButton pill(int scanCode, String defaultAction, int nameRes,
            String label, boolean labelBelow, float left, float top, float right, float bottom) {
        return new HardwareButton(scanCode, defaultAction, nameRes, label, ICON_NONE, labelBelow,
                SHAPE_PILL, new RectF(left, top, right, bottom), false);
    }

    /** Returns the index of the button reporting a scan code, or -1. */
    static int indexOf(int scanCode) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].scanCode == scanCode) {
                return i;
            }
        }
        return -1;
    }

    static String[] defaultActions() {
        final String[] actions = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            actions[i] = ALL[i].defaultAction;
        }
        return actions;
    }
}
