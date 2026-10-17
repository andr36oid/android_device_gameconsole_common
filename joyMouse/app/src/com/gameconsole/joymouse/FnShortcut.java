package com.gameconsole.joymouse;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Whether FN + X switches mouse mode (PhoneWindowManager's FN_HOTKEY_JOYSTICK_MOUSE): FN
 * shortcuts are on, the console has a real FN, and neither FN nor X is remapped.
 */
final class FnShortcut {

    // Scan codes of FN and X on the built-in pad
    private static final int SCAN_FN = 172;
    private static final int SCAN_X = 307;

    private FnShortcut() {}

    static boolean available(Context context) {
        if (!SystemProperties.getBoolean("persist.sys.fn_hotkeys", true)) return false;
        if (fnMissing(context)) return false;
        // Settings > Controls > Button mapping: "scanCode:KEYCODE" pairs
        final String remap = Settings.Global.getString(context.getContentResolver(),
                "hardware_button_remap");
        if (!TextUtils.isEmpty(remap)) {
            for (String entry : remap.split(",")) {
                final String scan = entry.split(":")[0].trim();
                if (scan.equals(String.valueOf(SCAN_FN)) || scan.equals(String.valueOf(SCAN_X))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * FN is in Settings.Global andr36oid_missing_buttons, or while that isn't set, in the
     * device tree hint (consoles like the R36H use a stand-in button instead).
     */
    private static boolean fnMissing(Context context) {
        String missing = Settings.Global.getString(context.getContentResolver(),
                "andr36oid_missing_buttons");
        if (missing == null) {
            try {
                missing = new String(Files.readAllBytes(Paths.get(
                        "/proc/device-tree/odroidgo3-joypad/andr36oid,missing-buttons")))
                        .replace('\0', ',');
            } catch (IOException | SecurityException e) {
                return false;
            }
        }
        for (String name : missing.split(",")) {
            if (name.trim().equalsIgnoreCase("fn")) return true;
        }
        return false;
    }
}
