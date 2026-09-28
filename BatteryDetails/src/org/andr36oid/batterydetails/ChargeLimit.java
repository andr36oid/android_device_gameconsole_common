package org.andr36oid.batterydetails;

import android.os.SystemProperties;
import android.util.Log;

import java.io.File;

/**
 * Stop charging at a set level, for consoles that live on the charger. The choice is kept in
 * a persistent property and init writes it to the RK817 driver at boot and whenever it
 * changes (init.rk30board.rc). The kernel switches the charger off at the limit and back on
 * HYSTERESIS points below it; the console keeps running from the charger meanwhile.
 */
final class ChargeLimit {

    private static final String TAG = "BatteryDetails";

    static final String PROPERTY = "persist.andr36oid.charge_limit";
    /** Must match CHRG_LIMIT_HYST in rk817_battery.c */
    static final int HYSTERESIS = 5;
    /** Choices offered, 0 = off */
    static final int[] CHOICES = {0, 60, 70, 80, 90};

    private static final String KNOB = "device/charge_limit_percent";
    private static final String ACTIVE = "device/charge_limit_active";

    private ChargeLimit() {}

    static boolean isSupported() {
        final String dir = Gauge.findBattery();
        return dir != null && new File(dir + KNOB).exists();
    }

    /** The stored limit, 0 when off. */
    static int chosen() {
        final int percent = SystemProperties.getInt(PROPERTY, 0);
        return percent >= 60 && percent < 100 ? percent : 0;
    }

    static void choose(int percent) {
        try {
            SystemProperties.set(PROPERTY, Integer.toString(percent >= 60 && percent < 100
                    ? percent : 0));
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot store the charge limit", e);
        }
    }

    /** True while the kernel has switched charging off because of the limit. */
    static boolean isHolding() {
        final String dir = Gauge.findBattery();
        return dir != null && Gauge.readInt(dir + ACTIVE, 1) == 1;
    }
}
