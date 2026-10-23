package org.andr36oid.usbmode;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
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
    static boolean busy() { return ControllerMode.active(target(), UsbDriveControl.busy()); }

    static void start(Context context, String mode) {
        if (UsbDriveControl.busy()) return;
        SystemProperties.set(PREFIX + "target", ControllerMode.MODES[ControllerMode.index(mode)]);
        SystemProperties.set(PREFIX + "ro", "1");
        SystemProperties.set(PREFIX + "owner", Integer.toString(Process.myPid()));
        SystemProperties.set(PREFIX + "request", "start");
        context.startForegroundService(new Intent(context, UsbModeService.class));
        context.startActivity(new Intent(context, ControllerSessionActivity.class));
    }
}
