package org.andr36oid.usbmode;

/** The stored choice never starts a session; every session needs a live app lease. */
final class ControllerMode {
    static final String[] MODES = {"xinput", "gamepad", "keyboard"};

    static int index(String mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return i;
        return 0;
    }

    static boolean active(String target, boolean busy) {
        if (!busy) return false;
        for (String mode : MODES) if (mode.equals(target)) return true;
        return false;
    }

    static boolean finished(String state, String request) {
        return !"start".equals(request) && ("idle".equals(state) || "error".equals(state));
    }
}
