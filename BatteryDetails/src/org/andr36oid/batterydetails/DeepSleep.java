package org.andr36oid.batterydetails;

import android.os.SystemProperties;
import android.util.Log;

import java.io.File;

/**
 * Whether the console sleeps deeply with the screen off. Deep sleep powers most of the chip
 * down through the firmware and uses the least battery; with it off the kernel only idles
 * (s2idle), which drains the battery faster but skips the firmware's power-down and wake-up,
 * for consoles that sometimes don't wake up again. The choice is kept in a persistent
 * property and init writes it to /sys/power/mem_sleep at boot and whenever it changes
 * (init.rk30board.rc).
 */
final class DeepSleep {

    private static final String TAG = "BatteryDetails";

    static final String PROPERTY = "persist.andr36oid.deep_sleep";

    private static final String KNOB = "/sys/power/mem_sleep";

    private DeepSleep() {}

    static boolean isSupported() {
        return new File(KNOB).exists();
    }

    /** True unless deep sleep was turned off. */
    static boolean chosen() {
        return SystemProperties.getBoolean(PROPERTY, true);
    }

    static void choose(boolean on) {
        try {
            SystemProperties.set(PROPERTY, on ? "1" : "0");
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot store the deep sleep setting", e);
        }
    }
}
