package org.andr36oid.batterydetails;

import android.os.SystemProperties;
import android.util.Log;

import java.io.File;

/**
 * The battery capacity the fuel gauge counts with. The choice is kept in a persistent
 * property, and init writes it to the RK817 driver at boot and whenever it changes
 * (init.rk30board.rc). Kernels without the knob don't get the setting at all.
 */
final class Capacity {

    private static final String TAG = "BatteryDetails";

    static final String PROPERTY = "persist.andr36oid.battery_capacity";
    static final int MIN_MAH = 1000;
    static final int MAX_MAH = 10000;
    static final int STEP_MAH = 100;
    /** Only used if the kernel doesn't say what its default is */
    private static final int FALLBACK_DEFAULT_MAH = 3200;

    private static final String KNOB = "device/design_capacity_mah";
    private static final String DEFAULT_KNOB = "device/design_capacity_default_mah";

    private Capacity() {}

    /** True when the kernel lets the capacity be changed. */
    static boolean isSupported() {
        final String dir = Gauge.findBattery();
        return dir != null && new File(dir + KNOB).exists();
    }

    /** The device's own capacity, from its device tree. */
    static int defaultMah() {
        final String dir = Gauge.findBattery();
        final int mah = dir == null ? Gauge.UNKNOWN : Gauge.readInt(dir + DEFAULT_KNOB, 1);
        return mah >= MIN_MAH && mah <= MAX_MAH ? mah : FALLBACK_DEFAULT_MAH;
    }

    /** What the user picked, or the default. */
    static int chosenMah() {
        final int mah = SystemProperties.getInt(PROPERTY, 0);
        return mah >= MIN_MAH && mah <= MAX_MAH ? mah : defaultMah();
    }

    /** Stores the choice; init hands it to the kernel. The default is stored as 0. */
    static void choose(int mah) {
        final int value = mah == defaultMah() ? 0 : clamp(mah);
        try {
            SystemProperties.set(PROPERTY, Integer.toString(value));
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot store the battery capacity", e);
        }
    }

    static int clamp(int mah) {
        final int stepped = Math.round(mah / (float) STEP_MAH) * STEP_MAH;
        return Math.max(MIN_MAH, Math.min(MAX_MAH, stepped));
    }
}
