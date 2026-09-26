package org.andr36oid.cpuoverclock;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
 * The overclock is the kernel's cpufreq boost: the OPPs above the standard speed are boost
 * OPPs, which the kernel keeps off until boost is turned on, so the console always starts
 * at standard speed. With boost on, scaling_max_freq picks the speed.
 */
final class Overclock {

    private static final String TAG = "CpuOverclock";

    private static final String CPUFREQ = "/sys/devices/system/cpu/cpufreq/";
    private static final String BOOST = CPUFREQ + "boost";
    private static final String POLICY = CPUFREQ + "policy0/";

    private static final String PREFS = "overclock";
    private static final String PREF_SPEED = "speed_khz";

    private final Context mContext;

    Overclock(Context context) {
        mContext = context;
    }

    /** The overclock speeds in kHz, lowest first, none if the console has no boost OPPs. */
    int[] getSpeeds() {
        final String text = read(POLICY + "scaling_boost_frequencies");
        if (TextUtils.isEmpty(text)) {
            return new int[0];
        }
        final int[] speeds = Arrays.stream(text.trim().split("\\s+"))
                .mapToInt(Integer::parseInt).toArray();
        Arrays.sort(speeds);
        return speeds;
    }

    boolean isSupported() {
        return read(BOOST) != null && getSpeeds().length > 0;
    }

    /** The top speed without the overclock, in kHz. */
    int getStandardSpeed() {
        final String text = read(POLICY + "scaling_available_frequencies");
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        return Arrays.stream(text.trim().split("\\s+")).mapToInt(Integer::parseInt).max()
                .orElse(0);
    }

    boolean isOn() {
        return "1".equals(read(BOOST));
    }

    /** What the CPU may run at right now, in kHz. */
    int getMaxSpeed() {
        final String text = read(POLICY + "scaling_max_freq");
        return TextUtils.isEmpty(text) ? 0 : Integer.parseInt(text);
    }

    /** The speed the overclock uses, kept while it is off. The lowest one by default. */
    int getChosenSpeed() {
        final int[] speeds = getSpeeds();
        if (speeds.length == 0) {
            return 0;
        }
        final int chosen = prefs().getInt(PREF_SPEED, speeds[0]);
        return Arrays.stream(speeds).anyMatch(speed -> speed == chosen) ? chosen : speeds[0];
    }

    /** Remembers the speed and switches to it right away if the overclock is on. */
    boolean setChosenSpeed(int khz) {
        prefs().edit().putInt(PREF_SPEED, khz).apply();
        return !isOn() || setOn(true);
    }

    boolean setOn(boolean on) {
        try {
            if (on) {
                // Boost on first, before that the kernel refuses anything above standard.
                write(BOOST, "1");
                write(POLICY + "scaling_max_freq", String.valueOf(getChosenSpeed()));
                showTile();
            } else {
                // Also puts scaling_max_freq back to the standard top speed.
                write(BOOST, "0");
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Couldn't switch the overclock " + (on ? "on" : "off"), e);
            return false;
        }
    }

    String formatSpeed(int khz) {
        return mContext.getString(R.string.speed_ghz, khz / 1000000f);
    }

    /** The first time the overclock is used, its tile appears in Quick Settings. */
    private void showTile() {
        final PackageManager pm = mContext.getPackageManager();
        final ComponentName tile = new ComponentName(mContext, OverclockTileService.class);
        if (pm.getComponentEnabledSetting(tile)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return;
        }
        pm.setComponentEnabledSetting(tile, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        final ContentResolver resolver = mContext.getContentResolver();
        final String spec = "custom(" + tile.flattenToShortString() + ")";
        String tiles = Settings.Secure.getString(resolver, Settings.Secure.QS_TILES);
        if (TextUtils.isEmpty(tiles)) {
            // Unset means the default tiles, which SystemUI expands "default" to.
            tiles = "default";
        }
        if (!Arrays.asList(tiles.split(",")).contains(spec)) {
            Settings.Secure.putString(resolver, Settings.Secure.QS_TILES, tiles + "," + spec);
        }
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(String path, String value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
