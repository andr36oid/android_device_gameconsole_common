package org.andr36oid.touchmapper;

import android.view.KeyEvent;

/**
 * Buttons and sticks, by the names the profile files and the joyMouse daemon use
 * (joyMouse/src/Buttons.cpp). Buttons are recognised by their scan code, the physical button,
 * so a button swapped in Button mapping still means the same button here and in the daemon.
 */
final class Inputs {

    private Inputs() {
    }

    /** Buttons a control can use, in the order menus list them. FN is never one of them. */
    static final String[] BUTTONS = {
            "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3", "SELECT", "START",
            "UP", "DOWN", "LEFT", "RIGHT",
    };

    /** Buttons that can be the shift button. */
    static final String[] SHIFT_BUTTONS = {"L1", "R1", "L2", "R2", "L3", "R3", "SELECT"};

    /** Linux key codes of the handhelds' pads, as the key layout and the daemon know them. */
    static String buttonForScanCode(int scanCode) {
        switch (scanCode) {
            case 304: return "A";       // BTN_A
            case 305: return "B";
            case 307: return "X";
            case 308: return "Y";
            case 310: return "L1";      // BTN_TL
            case 311: return "R1";
            case 312: return "L2";
            case 313: return "R2";
            case 314: return "SELECT";
            case 315: return "START";
            case 317: return "L3";      // BTN_THUMBL
            case 318: return "R3";
            case 158: return "L3";      // KEY_BACK: the odroidgo-style driver's L3
            case 125: return "R3";      // KEY_LEFTMETA: its R3
            case 103: case 0x220: return "UP";
            case 108: case 0x221: return "DOWN";
            case 105: case 0x222: return "LEFT";
            case 106: case 0x223: return "RIGHT";
            default: return null;
        }
    }

    /** For pads without useful scan codes. */
    static String buttonForKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            default: return null;
        }
    }

    /** The physical button of a key event, or null for FN, volume, power and the like. */
    static String button(KeyEvent event) {
        final String byScan = buttonForScanCode(event.getScanCode());
        if (byScan != null) {
            return byScan;
        }
        return event.getScanCode() == 0 ? buttonForKeyCode(event.getKeyCode()) : null;
    }

    static boolean isDpad(String button) {
        return "UP".equals(button) || "DOWN".equals(button) || "LEFT".equals(button)
                || "RIGHT".equals(button);
    }

    /** Short label for markers and hints. */
    static String label(String input) {
        if (input == null) {
            return "?";
        }
        switch (input) {
            case "SELECT": return "Sel";
            case "START": return "Start";
            case "UP": return "↑";
            case "DOWN": return "↓";
            case "LEFT": return "←";
            case "RIGHT": return "→";
            case Profile.STICK_LEFT: return "L";
            case Profile.STICK_RIGHT: return "R";
            case Profile.STICK_DPAD: return "✚";
            default: return input;
        }
    }

    /** Label for menus and messages. */
    static String longLabel(String input) {
        if (input == null) {
            return "?";
        }
        switch (input) {
            case "SELECT": return "Select";
            case "START": return "Start";
            case "UP": return "D-pad up";
            case "DOWN": return "D-pad down";
            case "LEFT": return "D-pad left";
            case "RIGHT": return "D-pad right";
            case Profile.STICK_LEFT: return "Left stick";
            case Profile.STICK_RIGHT: return "Right stick";
            case Profile.STICK_DPAD: return "D-pad";
            default: return input;
        }
    }
}
