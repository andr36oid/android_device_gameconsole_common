package org.andr36oid.usbmode;

public class ModeTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(ControllerMode.index("") == 0);
        check(ControllerMode.index("unknown") == 0);
        check(ControllerMode.index("gamepad") == 1);
        check(ControllerMode.index("keyboard") == 2);
        for (String mode : ControllerMode.MODES) {
            check(ControllerMode.active(mode, true));
            check(!ControllerMode.active(mode, false));
        }
        check(!ControllerMode.active("file", true));
        check(!ControllerMode.active("mmcblk1", true));
        check(!ControllerMode.finished("sharing", "start"));
        check(!ControllerMode.finished("idle", "start"));
        check(!ControllerMode.finished("preparing", "stop"));
        check(!ControllerMode.finished("stopping", "stop"));
        check(ControllerMode.finished("error", "stop"));
        check(ControllerMode.finished("idle", "stop"));
        System.out.println("Controller settings choices, exclusivity and session states passed");
    }
}
