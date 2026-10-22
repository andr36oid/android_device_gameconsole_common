package org.andr36oid.gamecomfort;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;

/** The settings, and starting the background service only while something needs it. */
final class Comfort {

    static final String KEY_BATTERY_WARNING = "battery_warning";
    static final String DEFAULT_BATTERY_WARNING = "30,20,15,5";
    /** Above this a warning only says to charge soon; from here on to save the game */
    private static final int SAVE_NOW_PERCENT = 15;

    /** Lowest threshold warned about in this discharge, see LowBattery. */
    private static final String KEY_WARNED = "battery_warned";

    private Comfort() {
    }

    static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    static int[] getThresholds(Context context) {
        return LowBattery.withLast(LowBattery.parse(prefs(context).getString(
                KEY_BATTERY_WARNING, DEFAULT_BATTERY_WARNING)));
    }

    static boolean isBatteryWarningOn(Context context) {
        return getThresholds(context).length > 0;
    }

    static int getWarned(Context context) {
        return prefs(context).getInt(KEY_WARNED, LowBattery.NONE);
    }

    static void setWarned(Context context, int warned) {
        prefs(context).edit().putInt(KEY_WARNED, warned).apply();
    }

    /**
     * The banner text for this level: charge soon while there is plenty left, save the game
     * from 15 % on, and the last warning says that the console turns off.
     */
    static String warningText(Context context, int percent) {
        return context.getString(LowBattery.isLast(percent) ? R.string.banner_last_text
                : percent > SAVE_NOW_PERCENT ? R.string.banner_charge_text
                : R.string.banner_text, percent);
    }

    static boolean isServiceNeeded(Context context) {
        // Continue playing always watches which game is in front
        return true;
    }

    /** Starts or stops the service to match the settings. */
    static void update(Context context) {
        update(context, new Intent(context, ComfortService.class));
    }

    static void update(Context context, Intent service) {
        if (isServiceNeeded(context)) {
            // The system user may start services from the background.
            context.startService(service);
        } else {
            context.stopService(service);
        }
        // At boot the service has to start again, and the tile goes in once
        BootStart.set(context, isServiceNeeded(context)
                || !prefs(context).getBoolean(GameMode.KEY_TILE_ADDED, false));
    }

    /** One short buzz, if the console has a vibration motor at all (the R36S has none). */
    static void vibrate(Context context) {
        final Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(200,
                    VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
