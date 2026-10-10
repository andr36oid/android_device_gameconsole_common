package org.andr36oid.rgblights;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemProperties;

import java.io.File;

/**
 * The RGB LED of the R36 Ultra: three on/off GPIO LEDs (red:status, green:status,
 * blue:status in its DTB), so seven colours and off. The choice is kept in
 * {@link #PROP}; init.rk30board.rc writes the LEDs whenever it changes, also at boot.
 */
final class RgbLights {

    static final String PROP = "persist.sys.rgb_lights";
    static final String OFF = "off";
    private static final String LED = "/sys/class/leds/red:status";

    private RgbLights() {
    }

    /** Whether this console has the RGB LED. */
    static boolean isPresent() {
        return new File(LED).exists();
    }

    static String get() {
        return SystemProperties.get(PROP, OFF);
    }

    static void set(String colour) {
        SystemProperties.set(PROP, colour);
    }

    /** Shows the Settings entry only where the LED exists. */
    static void updateEntry(Context context) {
        final ComponentName entry = new ComponentName(context, RgbLightsActivity.class);
        final int state = isPresent() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        final PackageManager pm = context.getPackageManager();
        if (pm.getComponentEnabledSetting(entry) != state) {
            pm.setComponentEnabledSetting(entry, state, PackageManager.DONT_KILL_APP);
        }
    }
}
