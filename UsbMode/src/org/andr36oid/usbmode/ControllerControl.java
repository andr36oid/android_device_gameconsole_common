package org.andr36oid.usbmode;

import android.content.Context;
import android.os.SystemProperties;

final class ControllerControl {
    private static final String PREFIX = "sys.andr36oid.usbdrive.";
    static final int[] TITLES = {R.string.controller_xinput, R.string.controller_gamepad,
            R.string.controller_keyboard};

    static String target() { return SystemProperties.get(PREFIX + "target"); }
    static int brightness() { return SystemProperties.getInt(PREFIX + "brightness", 15); }
    static void setBrightness(int percent) {
        SystemProperties.set(PREFIX + "brightness", Integer.toString(Math.max(2, Math.min(100, percent))));
    }
    static String request() { return SystemProperties.get(PREFIX + "request"); }
    static boolean busy() { return UsbDriveControl.busy(); }

    static void start(Context context, String mode) {
        final int mask = "gamepad".equals(mode) ? UsbFunctions.GAMEPAD : "keyboard".equals(mode)
                ? UsbFunctions.KEYBOARD | UsbFunctions.MOUSE : UsbFunctions.XINPUT;
        UsbDeviceControl.start(context, mask, null, true, false);
    }
}
