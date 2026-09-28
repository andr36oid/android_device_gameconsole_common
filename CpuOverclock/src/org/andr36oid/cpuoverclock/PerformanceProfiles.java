package org.andr36oid.cpuoverclock;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Battery saver, Balanced and Performance: the lowest and highest speed the CPU and GPU may
 * run at. Balanced is the kernel's default. The profile is kept across restarts and applied
 * again after boot. It stays within the standard speeds, the overclock goes on top of it.
 */
final class PerformanceProfiles {

    private static final String TAG = "PerformanceProfiles";

    static final int BATTERY_SAVER = 0;
    static final int BALANCED = 1;
    static final int PERFORMANCE = 2;
    static final int COUNT = 3;

    // Battery saver caps the CPU here, Performance keeps it at least here.
    private static final int CPU_MIDDLE_KHZ = 1008000;

    private static final String POLICY = "/sys/devices/system/cpu/cpufreq/policy0/";
    private static final String CPU_MIN = POLICY + "scaling_min_freq";
    private static final String CPU_MAX = POLICY + "scaling_max_freq";
    private static final String GPU = "/sys/class/devfreq/ff400000.gpu/";
    private static final String GPU_MIN = GPU + "min_freq";
    private static final String GPU_MAX = GPU + "max_freq";

    private static final String PREFS = "profiles";
    private static final String PREF_PROFILE = "profile";
    private static final String PREF_TILE_ADDED = "tile_added";

    private final Context mContext;

    PerformanceProfiles(Context context) {
        mContext = context;
    }

    int get() {
        final int profile = prefs().getInt(PREF_PROFILE, BALANCED);
        return profile >= 0 && profile < COUNT ? profile : BALANCED;
    }

    /** Remembers the profile and applies it. Battery saver turns the overclock off. */
    boolean set(int profile) {
        prefs().edit().putInt(PREF_PROFILE, profile).commit();
        final Overclock overclock = new Overclock(mContext);
        if (profile == BATTERY_SAVER && overclock.isOn()) {
            // Also applies the profile once the overclock is off.
            return overclock.setOn(false);
        }
        return apply();
    }

    int next() {
        return (get() + 1) % COUNT;
    }

    /** Writes the current profile's limits to the kernel. */
    boolean apply() {
        final int profile = get();
        boolean ok = true;

        final int[] cpu = readFrequencies(POLICY + "scaling_available_frequencies");
        if (cpu.length > 0) {
            final int lowest = cpu[0];
            final int highest = cpu[cpu.length - 1];
            final int middle = atMost(cpu, CPU_MIDDLE_KHZ);
            final boolean overclocked = new Overclock(mContext).isOn();
            try {
                // Lowest first, the kernel refuses a minimum above the maximum.
                write(CPU_MIN, lowest);
                if (!overclocked) {
                    // The overclock owns the maximum while it is on.
                    write(CPU_MAX, profile == BATTERY_SAVER ? middle : highest);
                }
                write(CPU_MIN, profile == PERFORMANCE ? middle : lowest);
            } catch (IOException e) {
                Log.e(TAG, "Couldn't set the CPU speeds", e);
                ok = false;
            }
        }

        final int[] gpu = readFrequencies(GPU + "available_frequencies");
        if (gpu.length > 0) {
            final int lowest = gpu[0];
            final int highest = gpu[gpu.length - 1];
            try {
                // 0 means the lowest for min_freq; the kernel refuses a max below the min.
                write(GPU_MIN, 0);
                write(GPU_MAX, profile == BATTERY_SAVER ? lowest : highest);
                write(GPU_MIN, profile == PERFORMANCE ? highest : lowest);
            } catch (IOException e) {
                Log.e(TAG, "Couldn't set the GPU speeds", e);
                ok = false;
            }
        }
        return ok;
    }

    /** The highest CPU speed Battery saver allows, in kHz, 0 if unknown. */
    int getSaverCpuSpeed() {
        return atMost(readFrequencies(POLICY + "scaling_available_frequencies"), CPU_MIDDLE_KHZ);
    }

    /** The lowest CPU speed Performance allows, in kHz, 0 if unknown. */
    int getPerformanceCpuSpeed() {
        return getSaverCpuSpeed();
    }

    /** The lowest GPU speed, in MHz, 0 if unknown. */
    int getGpuLowestMhz() {
        final int[] gpu = readFrequencies(GPU + "available_frequencies");
        return gpu.length > 0 ? gpu[0] / 1000000 : 0;
    }

    /** The highest GPU speed, in MHz, 0 if unknown. */
    int getGpuHighestMhz() {
        final int[] gpu = readFrequencies(GPU + "available_frequencies");
        return gpu.length > 0 ? gpu[gpu.length - 1] / 1000000 : 0;
    }

    String getName(int profile) {
        return mContext.getResources().getStringArray(R.array.profile_names)[profile];
    }

    /** Puts the profile tile into Quick Settings once, the user may remove it afterwards. */
    void addTileOnce() {
        if (prefs().getBoolean(PREF_TILE_ADDED, false)) {
            return;
        }
        final ContentResolver resolver = mContext.getContentResolver();
        final ComponentName tile = new ComponentName(mContext, ProfileTileService.class);
        final String spec = "custom(" + tile.flattenToShortString() + ")";
        String tiles = Settings.Secure.getString(resolver, Settings.Secure.QS_TILES);
        if (TextUtils.isEmpty(tiles)) {
            // Unset means the default tiles, which SystemUI expands "default" to.
            tiles = "default";
        }
        if (!Arrays.asList(tiles.split(",")).contains(spec)) {
            Settings.Secure.putString(resolver, Settings.Secure.QS_TILES, tiles + "," + spec);
        }
        prefs().edit().putBoolean(PREF_TILE_ADDED, true).apply();
    }

    /** The highest of the speeds at or below a limit, the lowest speed if all are above. */
    private static int atMost(int[] sorted, int limit) {
        if (sorted.length == 0) {
            return 0;
        }
        int result = sorted[0];
        for (int speed : sorted) {
            if (speed <= limit) {
                result = speed;
            }
        }
        return result;
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int[] readFrequencies(String path) {
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

    private static void write(String path, int value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
    }
}
