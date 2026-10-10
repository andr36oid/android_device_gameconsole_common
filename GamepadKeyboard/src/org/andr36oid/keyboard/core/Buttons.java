package org.andr36oid.keyboard.core;

/**
 * The button map. Key codes are Android's KeyEvent values (copied, so this class has no
 * Android dependency; ButtonsTest checks them against the documented numbers).
 */
public final class Buttons {
    // android.view.KeyEvent key codes
    public static final int KEYCODE_DPAD_UP = 19;
    public static final int KEYCODE_DPAD_DOWN = 20;
    public static final int KEYCODE_DPAD_LEFT = 21;
    public static final int KEYCODE_DPAD_RIGHT = 22;
    public static final int KEYCODE_DPAD_CENTER = 23;
    public static final int KEYCODE_BUTTON_A = 96;
    public static final int KEYCODE_BUTTON_B = 97;
    public static final int KEYCODE_BUTTON_X = 99;
    public static final int KEYCODE_BUTTON_Y = 100;
    public static final int KEYCODE_BUTTON_L1 = 102;
    public static final int KEYCODE_BUTTON_R1 = 103;
    public static final int KEYCODE_BUTTON_L2 = 104;
    public static final int KEYCODE_BUTTON_R2 = 105;
    public static final int KEYCODE_BUTTON_THUMBL = 106;
    public static final int KEYCODE_BUTTON_THUMBR = 107;
    public static final int KEYCODE_BUTTON_START = 108;
    public static final int KEYCODE_BUTTON_SELECT = 109;

    public static final int NONE = 0;
    public static final int NAV_UP = 1;
    public static final int NAV_DOWN = 2;
    public static final int NAV_LEFT = 3;
    public static final int NAV_RIGHT = 4;
    public static final int PRESS = 5;        // A: type the highlighted key
    public static final int BACKSPACE = 6;    // B
    public static final int SPACE = 7;        // X
    public static final int SHIFT = 8;        // Y
    public static final int CURSOR_LEFT = 9;  // L1
    public static final int CURSOR_RIGHT = 10; // R1
    public static final int HIDE = 11;        // L2
    public static final int BIG_TEXT = 12;    // R2: text line above the keyboard on/off
    public static final int SWITCH_IME = 13;  // L3
    public static final int STICK_MODE = 14;  // R3
    public static final int ENTER = 15;       // Start
    public static final int PAGE = 16;        // Select

    private Buttons() {}

    public static int action(int keyCode) {
        switch (keyCode) {
            case KEYCODE_DPAD_UP: return NAV_UP;
            case KEYCODE_DPAD_DOWN: return NAV_DOWN;
            case KEYCODE_DPAD_LEFT: return NAV_LEFT;
            case KEYCODE_DPAD_RIGHT: return NAV_RIGHT;
            case KEYCODE_DPAD_CENTER:
            case KEYCODE_BUTTON_A: return PRESS;
            case KEYCODE_BUTTON_B: return BACKSPACE;
            case KEYCODE_BUTTON_X: return SPACE;
            case KEYCODE_BUTTON_Y: return SHIFT;
            case KEYCODE_BUTTON_L1: return CURSOR_LEFT;
            case KEYCODE_BUTTON_R1: return CURSOR_RIGHT;
            case KEYCODE_BUTTON_L2: return HIDE;
            case KEYCODE_BUTTON_R2: return BIG_TEXT;
            case KEYCODE_BUTTON_THUMBL: return SWITCH_IME;
            case KEYCODE_BUTTON_THUMBR: return STICK_MODE;
            case KEYCODE_BUTTON_START: return ENTER;
            case KEYCODE_BUTTON_SELECT: return PAGE;
            default: return NONE;
        }
    }

    /** Stick clicks act on release, and not at all when both were down (mouse mode combo). */
    public static boolean actsOnRelease(int action) {
        return action == SWITCH_IME || action == STICK_MODE;
    }
}
