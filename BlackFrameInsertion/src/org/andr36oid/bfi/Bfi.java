package org.andr36oid.bfi;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** Shared settings: the frame pattern and when the Settings entry shows. */
final class Bfi {

    /** below this the black frames flicker too much to be any use (BFI does the same) */
    static final float MIN_HZ = 99.5f;
    static final int MAX_FRAMES = 12;

    private static final String PREFS = "bfi";
    private static final String KEY_NORMAL = "normal";
    private static final String KEY_BLACK = "black";
    private static final String KEY_WARNED = "warned";

    /** read by the render thread every frame, so pattern changes apply at once */
    static volatile int sNormal = 1;
    static volatile int sBlack = 1;

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
