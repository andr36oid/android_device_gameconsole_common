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
 *
 * The maximum CPU speed (underclock) is a cap on top of the profile: the lower of the two
 * wins, and Performance's raised minimum comes down to the cap. It is kept across restarts
 * too, since it can only make the CPU slower. The overclock puts the cap on hold while it
 * is on, and picking a cap turns the overclock off.
 */
final class PerformanceProfiles {

    private static final String TAG = "PerformanceProfiles";

    static final int BATTERY_SAVER = 0;
    static final int BALANCED = 1;
    static final int PERFORMANCE = 2;
    static final int COUNT = 3;

    // Battery saver caps the CPU here, Performance keeps it at least here.
    private static final int CPU_MIDDLE_KHZ = 1008000;
    // The lowest maximum CPU speed offered. Below this even 8-bit emulators start to slow.
    private static final int CPU_CAP_FLOOR_KHZ = 600000;

    private static final String POLICY = "/sys/devices/system/cpu/cpufreq/policy0/";
    private static final String CPU_MIN = POLICY + "scaling_min_freq";
    private static final String CPU_MAX = POLICY + "scaling_max_freq";
    private static final String GPU = "/sys/class/devfreq/ff400000.gpu/";
    private static final String GPU_MIN = GPU + "min_freq";
    private static final String GPU_MAX = GPU + "max_freq";

    private static final String PREFS = "profiles";
    private static final String PREF_PROFILE = "profile";
    private static final String PREF_TILE_ADDED = "tile_added";
    private static final String PREF_CPU_CAP = "cpu_cap_khz";

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

    /** The chosen maximum CPU speed in kHz, 0 for none (stock). */
    int getCpuCap() {
        return Math.max(0, prefs().getInt(PREF_CPU_CAP, 0));
    }

    /** Remembers the maximum CPU speed (0 for none) and applies it. Turns the overclock off. */
    boolean setCpuCap(int khz) {
        prefs().edit().putInt(PREF_CPU_CAP, Math.max(0, khz)).commit();
        final Overclock overclock = new Overclock(mContext);
        if (khz > 0 && overclock.isOn()) {
            // Also applies the profile and the cap once the overclock is off.
            return overclock.setOn(false);
        }
        return apply();
    }

    /** The speeds offered as a maximum, in kHz, highest first: the standard ones below the top. */
    int[] getCpuCapChoices() {
        final int[] cpu = readFrequencies(POLICY + "scaling_available_frequencies");
        if (cpu.length == 0) {
            return cpu;
        }
        final int highest = cpu[cpu.length - 1];
        final int[] choices = Arrays.stream(cpu)
                .filter(speed -> speed >= CPU_CAP_FLOOR_KHZ && speed < highest).toArray();
        for (int i = 0, j = choices.length - 1; i < j; i++, j--) {
            final int speed = choices[i];
            choices[i] = choices[j];
            choices[j] = speed;
        }
        return choices;
    }

    /**
     * The speed the cap holds the CPU to right now, in kHz, or 0 if it doesn't matter: no cap,
     * the overclock is on, or the profile already stays lower.
     */
    int getActiveCpuCap() {
        final int cap = getCpuCap();
        final int[] cpu = readFrequencies(POLICY + "scaling_available_frequencies");
        if (cap == 0 || cpu.length == 0 || new Overclock(mContext).isOn()) {
            return 0;
        }
        final int capped = atMost(cpu, cap);
        return capped < profileCpuMax(cpu, get()) ? capped : 0;
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
            final int middle = atMost(cpu, CPU_MIDDLE_KHZ);
            final boolean overclocked = new Overclock(mContext).isOn();
            int max = profileCpuMax(cpu, profile);
            final int cap = getCpuCap();
            if (cap > 0 && !overclocked) {
                // The lower of the profile and the cap wins.
                max = Math.min(max, atMost(cpu, cap));
            }
            try {
                // Lowest first, the kernel refuses a minimum above the maximum.
                write(CPU_MIN, lowest);
                if (!overclocked) {
                    // The overclock owns the maximum while it is on.
                    write(CPU_MAX, max);
                }
                // The minimum never goes above the cap.
                write(CPU_MIN, profile == PERFORMANCE ? Math.min(middle, max) : lowest);
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

    /** The highest CPU speed a profile allows without the overclock and the cap, in kHz. */
    private static int profileCpuMax(int[] cpu, int profile) {
        return profile == BATTERY_SAVER ? atMost(cpu, CPU_MIDDLE_KHZ) : cpu[cpu.length - 1];
    }

    /** The highest CPU speed Battery saver allows, in kHz, 0 if unknown. */
    int getSaverCpuSpeed() {
        return atMost(readFrequencies(POLICY + "scaling_available_frequencies"), CPU_MIDDLE_KHZ);
    }

    /** The lowest CPU speed Performance allows, in kHz, 0 if unknown. Never above the cap. */
    int getPerformanceCpuSpeed() {
        final int cap = getActiveCpuCap();
        return cap > 0 ? Math.min(cap, getSaverCpuSpeed()) : getSaverCpuSpeed();
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

    /** A CPU speed in kHz as "816 MHz". */
    String formatMhz(int khz) {
        return mContext.getString(R.string.speed_mhz, khz / 1000);
    }

    /** The highest CPU speed the profile allows right now, cap and overclock left out, in kHz. */
    int getProfileCpuMax() {
        final int[] cpu = readFrequencies(POLICY + "scaling_available_frequencies");
        return cpu.length > 0 ? profileCpuMax(cpu, get()) : 0;
    }

    /** "Balanced", or "Balanced, CPU up to 816 MHz" style text when the cap holds the CPU lower. */
    String describeForToast(int profile) {
        final int cap = getActiveCpuCap();
        return cap > 0
                ? mContext.getString(R.string.profile_switched_capped, getName(profile),
                        formatMhz(cap))
                : mContext.getString(R.string.profile_switched, getName(profile));
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
