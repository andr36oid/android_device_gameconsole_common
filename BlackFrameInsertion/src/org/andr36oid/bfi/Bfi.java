package org.andr36oid.bfi;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Shared settings: the frame pattern and when the Settings entry shows. The black frames
 * themselves come from the display controller (kernel rockchip_drm_vop.c, vop_bfi_frame):
 * it scans out black in the pattern's black slots, switched at every frame start, so it
 * keeps step with the panel. An app drawing a black overlay can't: it misses refreshes
 * and the black frames land at random, which only flickers.
 */
final class Bfi {

    /** below this the black frames flicker too much to be any use (BFI does the same) */
    static final float MIN_HZ = 99.5f;
    static final int MAX_FRAMES = 12;

    private static final String PREFS = "bfi";
    private static final String KEY_NORMAL = "normal";
    private static final String KEY_BLACK = "black";
    private static final String KEY_WARNED = "warned";

    private static final String TAG = "Bfi";
    /** "normal:black", 0 is off; the kernel turns it off on every start */
    private static final String PARAM = "/sys/module/rockchipdrm/parameters/bfi";

    static int sNormal = 1;
    static int sBlack = 1;

    private Bfi() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void load(Context context) {
        final SharedPreferences p = prefs(context);
        sNormal = clamp(p.getInt(KEY_NORMAL, 1));
        sBlack = clamp(p.getInt(KEY_BLACK, 1));
    }

    static void setPattern(Context context, int normal, int black) {
        sNormal = clamp(normal);
        sBlack = clamp(black);
        prefs(context).edit().putInt(KEY_NORMAL, sNormal).putInt(KEY_BLACK, sBlack).apply();
        // a new pattern applies at once
        if (running()) set(true);
    }

    /** Whether the display controller is inserting black frames now. */
    static boolean running() {
        try {
            final String v = new String(Files.readAllBytes(Paths.get(PARAM)),
                    StandardCharsets.US_ASCII).trim();
            return !v.isEmpty() && !v.startsWith("0");
        } catch (IOException e) {
            return false;
        }
    }

    /** Turns it on with the saved pattern, or off. */
    static boolean set(boolean on) {
        final String v = on ? sNormal + ":" + sBlack : "0";
        try (FileOutputStream out = new FileOutputStream(PARAM)) {
            out.write(v.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Can't write " + v + " to " + PARAM, e);
            return false;
        }
    }

    static boolean warned(Context context) {
        return prefs(context).getBoolean(KEY_WARNED, false);
    }

    static void setWarned(Context context) {
        prefs(context).edit().putBoolean(KEY_WARNED, true).apply();
    }

    private static int clamp(int n) {
        return Math.max(1, Math.min(MAX_FRAMES, n));
    }

    /** Settings > Display > Peak refresh rate */
    static float peakRate(Context context) {
        return Settings.System.getFloat(context.getContentResolver(),
                Settings.System.PEAK_REFRESH_RATE, 60f);
    }

    /** The Settings entry shows only while Peak refresh rate is 100 Hz or more. */
    static void syncEntry(Context context) {
        final ComponentName entry = new ComponentName(context, BfiActivity.class);
        final int state = peakRate(context) >= MIN_HZ
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        final PackageManager pm = context.getPackageManager();
        if (pm.getComponentEnabledSetting(entry) != state) {
            pm.setComponentEnabledSetting(entry, state, PackageManager.DONT_KILL_APP);
        }
    }
}
