package org.andr36oid.batterydetails;

import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.text.NumberFormat;

/**
 * How fast the battery charges. The choice is kept in a persistent property and init writes
 * it to the RK817 charger driver at boot and whenever it changes (init.rk30board.rc). 0 is
 * the console's own default from its device tree. Above the default the kernel also lets
 * the charger input draw more, up to 3 A, so the faster speed can take effect.
 */
final class ChargeSpeed {

    private static final String TAG = "BatteryDetails";

    static final String PROPERTY = "persist.andr36oid.charge_current";
    /** Choices offered in mA, 0 = the default. The PMIC's steps from 500 mA to 3.5 A. */
    static final int[] CHOICES = {0, 500, 1000, 1500, 2000, 2500, 3000, 3500};
    /** Most the kernel lets the charger input draw, see rk817_charge_apply_speed() */
    private static final int MAX_INPUT_MA = 3000;
    /** Typical battery voltage while charging, for the watts shown */
    private static final float BATTERY_VOLTS = 3.85f;
    private static final float CHARGER_VOLTS = 5f;

    private static final String DIR = "/sys/class/power_supply/ac/device/";
    private static final String KNOB = DIR + "charge_current_ma";
    private static final String DEFAULT = DIR + "charge_current_default_ma";

    private ChargeSpeed() {}

    static boolean isSupported() {
        return new File(KNOB).exists() && defaultMa() > 0;
    }

    /** The console's default charging current in mA, 0 if unknown. */
    static int defaultMa() {
        final int ma = Gauge.readInt(DEFAULT, 1);
        return ma == Gauge.UNKNOWN ? 0 : ma;
    }

    /** The stored speed in mA, 0 for the default. */
    static int chosen() {
        final int ma = SystemProperties.getInt(PROPERTY, 0);
        return valid(ma) ? ma : 0;
    }

    static void choose(int ma) {
        try {
            SystemProperties.set(PROPERTY, Integer.toString(valid(ma) ? ma : 0));
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot store the charging speed", e);
        }
    }

    /** About how many watts go into the battery at this speed, formatted. */
    static String batteryWatts(int ma) {
        return format(ma * BATTERY_VOLTS / 1000f);
    }

    /** What the charger has to give for this speed, in amps, formatted. */
    static String chargerAmps(int ma) {
        return format(Math.min(ma, MAX_INPUT_MA) / 1000f);
    }

    /** What the charger has to give for this speed, in watts at 5 V, formatted. */
    static String chargerWatts(int ma) {
        return format(Math.min(ma, MAX_INPUT_MA) * CHARGER_VOLTS / 1000f);
    }

    private static String format(float value) {
        final NumberFormat format = NumberFormat.getInstance();
        format.setMaximumFractionDigits(1);
        return format.format(value);
    }

    private static boolean valid(int ma) {
        return ma >= 500 && ma <= 3500;
    }
}
