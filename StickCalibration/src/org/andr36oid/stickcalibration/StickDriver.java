package org.andr36oid.stickcalibration;

import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * The odroidgo3-joypad driver's stick files. Reading is direct; the calibration is stored in
 * persistent properties, and init writes them to the driver at boot and whenever they change
 * (init.rk30board.rc). Apps can't write the driver files themselves.
 */
final class StickDriver {

    private static final String TAG = "StickCalibration";

    static final String PROP_CAL = "persist.andr36oid.stick_cal";
    static final String PROP_DEADZONE = "persist.andr36oid.stick_deadzone";
    static final String OFF = "off";

    private static final String[] DIRS = {
            "/sys/devices/platform/odroidgo3-joypad",
            "/sys/devices/platform/odroidgo3_joypad",
    };
    private static final String DRIVER_DIR = "/sys/bus/platform/drivers/odroidgo3_joypad";

    private final File mDir;

    private StickDriver(File dir) {
        mDir = dir;
    }

    /** The driver, or null when this kernel has no stick calibration. */
    static StickDriver find() {
        for (String path : DIRS) {
            final File dir = new File(path);
            if (new File(dir, "stick_raw").exists()) {
                return new StickDriver(dir);
            }
        }
        final File[] bound = new File(DRIVER_DIR).listFiles();
        if (bound != null) {
            for (File dir : bound) {
                if (new File(dir, "stick_raw").exists()) {
                    return new StickDriver(dir);
                }
            }
        }
        return null;
    }

    /** The last raw reading of the four axes, null if unreadable. */
    int[] readRaw() {
        final String line = readLine("stick_raw");
        if (line == null) {
            return null;
        }
        final String[] parts = line.trim().split("\\s+");
        if (parts.length != Calibration.AXES) {
            return null;
        }
        final int[] raw = new int[Calibration.AXES];
        try {
            for (int i = 0; i < raw.length; i++) {
                raw[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return raw;
    }

    /** What the driver uses right now, null when it runs on the factory settings. */
    Calibration readCalibration() {
        return Calibration.parse(readLine("stick_cal"));
    }

    int readDeadzone() {
        final String line = readLine("stick_deadzone");
        try {
            return line != null ? Integer.parseInt(line.trim()) : Calibration.DEFAULT_DEADZONE;
        } catch (NumberFormatException e) {
            return Calibration.DEFAULT_DEADZONE;
        }
    }

    /** The stored dead zone (what the driver gets at boot). */
    static int storedDeadzone() {
        final int dz = SystemProperties.getInt(PROP_DEADZONE, Calibration.DEFAULT_DEADZONE);
        return Math.max(0, Math.min(Calibration.MAX_DEADZONE, dz));
    }

    static void store(Calibration cal, int deadzone) {
        // Dead zone first, so the new calibration starts out with it
        storeDeadzone(deadzone);
        set(PROP_CAL, cal != null ? cal.format() : OFF);
    }

    static void storeDeadzone(int deadzone) {
        set(PROP_DEADZONE, Integer.toString(
                Math.max(0, Math.min(Calibration.MAX_DEADZONE, deadzone))));
    }

    private static void set(String key, String value) {
        try {
            SystemProperties.set(key, value);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot store " + key, e);
        }
    }

    private String readLine(String name) {
        try (BufferedReader in = new BufferedReader(new FileReader(new File(mDir, name)))) {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
