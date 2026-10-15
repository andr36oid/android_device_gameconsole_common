package org.andr36oid.cpuoverclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * The GPU and memory overclock (after R36 Tuner Next by zenmode-adri): extra GPU and memory
 * speeds the kernel lists in overclock_frequencies and keeps above max_freq at every start.
 * The profiles then let the chosen one in. Like the CPU overclock, a restart turns them off:
 * the choice is kept with the boot count and counts for that boot only.
 */
final class GpuRamOverclock {

    static final String GPU = "/sys/class/devfreq/ff400000.gpu/";
    static final String DMC = "/sys/class/devfreq/dmc/";

    private static final String PREFS = "gpu_ram_overclock";
    private static final String PREF_BOOT = "boot_count";

    /** What the memory speeds really run at: the DDR firmware rounds them to its PLL. */
    private static final int[][] REAL_MHZ = { { 928, 924 }, { 1040, 1032 } };
    /** Memory speeds at the power rail's ceiling, marked experimental. */
    private static final int EXPERIMENTAL_HZ = 1000000000;

    private final Context mContext;

    GpuRamOverclock(Context context) {
        mContext = context;
    }

    /** The overclock speeds of a devfreq device in Hz, lowest first. */
    static int[] getSpeeds(String dev) {
        return read(dev + "overclock_frequencies");
    }

    static boolean isExperimental(int hz) {
        return hz >= EXPERIMENTAL_HZ;
    }

    /** The speed a memory or GPU OPP really runs at, in MHz. */
    static int realMhz(int hz) {
        final int mhz = hz / 1000000;
        for (int[] pair : REAL_MHZ) {
            if (pair[0] == mhz) {
                return pair[1];
            }
        }
        return mhz;
    }

    /** The overclock speed chosen for this boot in Hz, 0 for none. */
    int getChosen(String dev) {
        final SharedPreferences prefs = prefs();
        if (prefs.getInt(PREF_BOOT, -1) != bootCount()) {
            return 0;
        }
        final int hz = prefs.getInt(dev, 0);
        return Arrays.stream(getSpeeds(dev)).anyMatch(s -> s == hz) ? hz : 0;
    }

    void setChosen(String dev, int hz) {
        final SharedPreferences prefs = prefs();
        final SharedPreferences.Editor edit = prefs.edit();
        if (prefs.getInt(PREF_BOOT, -1) != bootCount()) {
            edit.clear().putInt(PREF_BOOT, bootCount());
        }
        edit.putInt(dev, hz).apply();
    }

    /** The highest speed without the overclock, in Hz, from the sorted available speeds. */
    static int getStockMax(String dev, int[] available) {
        final int[] oc = getSpeeds(dev);
        int max = 0;
        for (int hz : available) {
            if (Arrays.stream(oc).noneMatch(s -> s == hz)) {
                max = Math.max(max, hz);
            }
        }
        return max;
    }

    /** The available speeds the profiles may use right now: the stock ones and the chosen one. */
    int[] allowed(String dev, int[] available) {
        final int top = Math.max(getStockMax(dev, available), getChosen(dev));
        return Arrays.stream(available).filter(hz -> hz <= top).toArray();
    }

    private int bootCount() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.BOOT_COUNT, 0);
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int[] read(String path) {
        try {
            final String text = new String(Files.readAllBytes(Paths.get(path)),
                    StandardCharsets.UTF_8).trim();
            if (TextUtils.isEmpty(text)) {
                return new int[0];
            }
            final int[] speeds = Arrays.stream(text.split("\\s+"))
                    .mapToInt(Integer::parseInt).toArray();
            Arrays.sort(speeds);
            return speeds;
        } catch (IOException | NumberFormatException e) {
            return new int[0];
        }
    }
}
