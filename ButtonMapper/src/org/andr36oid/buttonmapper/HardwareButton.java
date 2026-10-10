package org.andr36oid.buttonmapper;

import android.graphics.RectF;

import org.andr36oid.consoleart.R36s.Control;

/**
 * A built-in button: the Linux scan code it reports (see the odroidgo3-joypad and gpio-keys
 * nodes in the kernel device tree), what it does with the stock key layout
 * (Vendor_484b_Product_1100.kl, Generic.kl) and which control of the drawing it is.
 */
final class HardwareButton {
    static final HardwareButton[] ALL = {
            new HardwareButton(103, "DPAD_UP", R.string.button_dpad_up, Control.DPAD_UP),
            new HardwareButton(108, "DPAD_DOWN", R.string.button_dpad_down, Control.DPAD_DOWN),
            new HardwareButton(105, "DPAD_LEFT", R.string.button_dpad_left, Control.DPAD_LEFT),
            new HardwareButton(106, "DPAD_RIGHT", R.string.button_dpad_right,
                    Control.DPAD_RIGHT),
            new HardwareButton(307, "BUTTON_X", R.string.button_x, Control.X),
            new HardwareButton(308, "BUTTON_Y", R.string.button_y, Control.Y),
            new HardwareButton(304, "BUTTON_A", R.string.button_a, Control.A),
            new HardwareButton(305, "BUTTON_B", R.string.button_b, Control.B),
            new HardwareButton(312, "BUTTON_L2", R.string.button_l2, Control.L2),
            new HardwareButton(310, "BUTTON_L1", R.string.button_l1, Control.L1),
            // R1 sits at the outer edge, R2 inside, next to the volume buttons
            new HardwareButton(311, "BUTTON_R1", R.string.button_r1, Control.R1),
            new HardwareButton(313, "BUTTON_R2", R.string.button_r2, Control.R2),
            new HardwareButton(158, "BUTTON_THUMBL", R.string.button_l3, Control.L3),
            new HardwareButton(125, "BUTTON_THUMBR", R.string.button_r3, Control.R3),
            new HardwareButton(314, "BUTTON_SELECT", R.string.button_select, Control.SELECT),
            new HardwareButton(172, "HOME", R.string.button_function, Control.FN),
            new HardwareButton(315, "BUTTON_START", R.string.button_start, Control.START),
            new HardwareButton(114, "VOLUME_DOWN", R.string.button_volume_down,
                    Control.VOLUME_DOWN),
            new HardwareButton(115, "VOLUME_UP", R.string.button_volume_up, Control.VOLUME_UP),
            // The only button that wakes the console from sleep, so it can't be changed. The
            // framework ignores remaps of it as well.
            new HardwareButton(116, "POWER", R.string.button_power, Control.POWER, true),
    };

    static final int KEY_VOLUMEDOWN = 114;
    static final int KEY_VOLUMEUP = 115;

    final int scanCode;
    final String defaultAction;
    final int nameRes;
    final Control control;
    // Position on the drawing, in R36s units.
    final RectF bounds;
    final boolean locked;

    private HardwareButton(int scanCode, String defaultAction, int nameRes, Control control) {
        this(scanCode, defaultAction, nameRes, control, false);
    }

    private HardwareButton(int scanCode, String defaultAction, int nameRes, Control control,
            boolean locked) {
        this.scanCode = scanCode;
        this.defaultAction = defaultAction;
        this.nameRes = nameRes;
        this.control = control;
        this.bounds = new RectF(control.left, control.top, control.right, control.bottom);
        this.locked = locked;
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
