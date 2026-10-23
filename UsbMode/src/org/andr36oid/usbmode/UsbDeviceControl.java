package org.andr36oid.usbmode;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;

import java.util.Collections;

final class UsbDeviceControl {
    static final String PREFIX = "sys.andr36oid.usbdrive.";
    static int functions() { return SystemProperties.getInt(PREFIX + "active_functions", 0); }
    static String screen() { return SystemProperties.get(PREFIX + "screen", "on"); }
    static boolean keyboardScreen() { return "keyboard".equals(SystemProperties.get(PREFIX + "routing")); }
    static boolean debugging(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 0;
    }
    static void dark() { SystemProperties.set(PREFIX + "screen_request", "dark"); }
    static void clearDarkRequest() { SystemProperties.set(PREFIX + "screen_request", "on"); }
    static UsbFunctions policies() {
        return new UsbFunctions();
    }
    static void start(Context context, int functions, UsbDrive.Offer storage, boolean ro, boolean cdrom) {
        if (request(context, functions, storage, ro, cdrom, () -> true))
            context.startActivity(new Intent(context, ControllerSessionActivity.class));
    }
    static boolean request(Context context, int functions, UsbDrive.Offer storage, boolean ro, boolean cdrom,
            java.util.function.BooleanSupplier current) {
        if (UsbDriveControl.busy() || !current.getAsBoolean()) return false;
        final UsbFunctions policy = policies();
        policy.query(Collections.singleton(functions));
        if (!"ok".equals(policy.reason(functions))) return false;
        if ((functions & UsbFunctions.ADB) != 0 && !debugging(context)) return false;
        final android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        SystemProperties.set(PREFIX + "webusb", prefs.getBoolean("usb_webusb", true) ? "1" : "0");
        SystemProperties.set(PREFIX + "macro", (functions & (UsbFunctions.KEYBOARD | UsbFunctions.MEDIA)) == (UsbFunctions.KEYBOARD | UsbFunctions.MEDIA)
                && prefs.getBoolean("usb_macro",false) ? "1" : "0");
        SystemProperties.set(PREFIX + "jiggle", (functions & UsbFunctions.MOUSE) != 0 && prefs.getBoolean("usb_jiggle",false)
                ? prefs.getString("usb_jiggle_interval","60") : "0");
        SystemProperties.set(PREFIX + "functions", Integer.toString(functions));
        String name = "";
        if (storage != null) {
            name = storage.kind == UsbDrive.Kind.IMAGE ? storage.node : storage.kind == UsbDrive.Kind.EMMC
                    ? context.getString(R.string.usb_drive_emmc) : storage.kind == UsbDrive.Kind.EASYROMS
                    ? context.getString(R.string.usb_drive_easyroms, storage.slot)
                    : context.getString(R.string.usb_drive_slot, storage.slot);
        }
        SystemProperties.set(PREFIX + "name", name);
        if (!current.getAsBoolean()) return false;
        if ((functions & UsbFunctions.STORAGE) != 0) {
            if (storage == null || !storage.enabled()) return false;
            UsbDriveControl.start(context, storage, ro, cdrom);
        } else {
            SystemProperties.set(PREFIX + "target", "none");
            SystemProperties.set(PREFIX + "ro", "1");
            SystemProperties.set(PREFIX + "owner", Integer.toString(Process.myPid()));
            SystemProperties.set(PREFIX + "request", "start");
            context.startForegroundService(new Intent(context, UsbModeService.class));
        }
        if (!current.getAsBoolean()) { UsbDriveControl.stop(); return false; }
        return true;
    }
}
