package org.andr36oid.playtime;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Parental controls: a daily play time allowance behind a PIN. The console has no clock
 * battery, so the wall clock can't be trusted (it starts over on every power-on). Time is
 * only ever measured with SystemClock.elapsedRealtime: ParentalService adds up how long
 * the screen is on, and the allowance comes back after a long enough rest (screen off or
 * asleep, measured the same way) or when a parent enters the PIN. Power-off time can't
 * be measured, so it doesn't count as rest: a restart never gives time back.
 */
final class Parental {

    /** Settings.Global, read by Settings to keep Force stop and Disable greyed out */
    static final String GLOBAL_ON = "andr36oid_parental_controls";

    static final int PIN_LENGTH = 4;
    static final int[] LIMIT_MINUTES = {15, 30, 45, 60, 90, 120, 180, 240};
    /** hours of rest before the time comes back; 0: only with the PIN */
    static final int[] REFILL_HOURS = {6, 8, 10, 12, 16, 0};
    /** minutes the time up page waits for the PIN before the console shuts down; 0: never */
    static final int[] SHUTDOWN_MINUTES = {1, 2, 5, 0};
    /** what the PIN can add on the time is up page and the settings page, in minutes */
    static final int[] EXTRA_MINUTES = {15, 30, 60};

    private static final String PREFS = "parental";
    private static final String KEY_ON = "on";
    private static final String KEY_PIN = "pin";
    private static final String KEY_LIMIT = "limit_minutes";
    private static final String KEY_REFILL = "refill_hours";
    private static final String KEY_USED = "used_ms";
    private static final String KEY_EXTRA = "extra_ms";
    private static final String KEY_WARNED = "warned";
    private static final String KEY_SHUTDOWN = "shutdown_minutes";
    private static final String KEY_FAILS = "fails";

    private static final int MAX_FAILS = 5;
    private static final long FAIL_WAIT_MS = 60 * 1000L;
    /** wrong PINs: elapsedRealtime until which no PIN is taken (a restart ends it, fine) */
    private static long sWaitUntil;

    private Parental() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isOn(Context context) {
        return prefs(context).getBoolean(KEY_ON, false);
    }

    /** Turns the limits on or off; on needs a PIN set first. */
    static void setOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_ON, on).commit();
        Settings.Global.putInt(context.getContentResolver(), GLOBAL_ON, on ? 1 : 0);
        BootStart.set(context, on || Breaks.getMinutes(context) > 0);
        final Intent service = new Intent(context, ParentalService.class);
        if (on) {
            context.startForegroundService(service);
        } else {
            context.stopService(service);
        }
    }

    // ---- PIN

    static boolean hasPin(Context context) {
        return prefs(context).contains(KEY_PIN);
    }

    static void setPin(Context context, String pin) {
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        final String s = hex(salt);
        prefs(context).edit().putString(KEY_PIN, s + ":" + hash(s, pin)).putInt(KEY_FAILS, 0)
                .commit();
    }

    /** Seconds before another PIN is taken after too many wrong ones, 0 if it may be tried. */
    static int waitSeconds() {
        final long left = sWaitUntil - SystemClock.elapsedRealtime();
        return left > 0 ? (int) ((left + 999) / 1000) : 0;
    }

    /** Checks a PIN; after MAX_FAILS wrong ones in a row, none is taken for a minute. */
    static boolean checkPin(Context context, String pin) {
        if (waitSeconds() > 0) return false;
        final SharedPreferences p = prefs(context);
        final String stored = p.getString(KEY_PIN, "");
        final int colon = stored.indexOf(':');
        if (colon > 0 && MessageDigest.isEqual(
                stored.substring(colon + 1).getBytes(StandardCharsets.US_ASCII),
                hash(stored.substring(0, colon), pin).getBytes(StandardCharsets.US_ASCII))) {
            p.edit().putInt(KEY_FAILS, 0).apply();
            return true;
        }
        final int fails = p.getInt(KEY_FAILS, 0) + 1;
        if (fails >= MAX_FAILS) {
            sWaitUntil = SystemClock.elapsedRealtime() + FAIL_WAIT_MS;
            p.edit().putInt(KEY_FAILS, 0).apply();
        } else {
            p.edit().putInt(KEY_FAILS, fails).apply();
        }
        return false;
    }

    private static String hash(String salt, String pin) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.US_ASCII));
            md.update(pin.getBytes(StandardCharsets.US_ASCII));
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        final StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---- settings

    static int limitMinutes(Context context) {
        return prefs(context).getInt(KEY_LIMIT, 60);
    }

    static void setLimitMinutes(Context context, int minutes) {
        prefs(context).edit().putInt(KEY_LIMIT, minutes).commit();
        refresh(context);
    }

    static int refillHours(Context context) {
        return prefs(context).getInt(KEY_REFILL, 10);
    }

    static void setRefillHours(Context context, int hours) {
        prefs(context).edit().putInt(KEY_REFILL, hours).commit();
    }

    static int shutdownMinutes(Context context) {
        return prefs(context).getInt(KEY_SHUTDOWN, 2);
    }

    static void setShutdownMinutes(Context context, int minutes) {
        prefs(context).edit().putInt(KEY_SHUTDOWN, minutes).commit();
    }

    // ---- the allowance

    static long usedMs(Context context) {
        return prefs(context).getLong(KEY_USED, 0);
    }

    static void setUsedMs(Context context, long ms) {
        prefs(context).edit().putLong(KEY_USED, ms).apply();
    }

    static long allowanceMs(Context context) {
        return limitMinutes(context) * 60_000L + prefs(context).getLong(KEY_EXTRA, 0);
    }

    static long leftMs(Context context) {
        return Math.max(0, allowanceMs(context) - usedMs(context));
    }

    /** More time on top of what is left now. */
    static void addMinutes(Context context, int minutes) {
        final SharedPreferences p = prefs(context);
        // used time beyond the allowance (counted while the time up page was on) is forgiven
        final long over = Math.max(0, usedMs(context) - allowanceMs(context));
        p.edit().putLong(KEY_EXTRA, p.getLong(KEY_EXTRA, 0) + over + minutes * 60_000L)
                .putInt(KEY_WARNED, 0).commit();
        refresh(context);
    }

    /** The whole allowance again, as after a rest. */
    static void refill(Context context) {
        prefs(context).edit().putLong(KEY_USED, 0).putLong(KEY_EXTRA, 0).putInt(KEY_WARNED, 0)
                .commit();
        refresh(context);
    }

    /** Which warnings (bits, see ParentalService) were shown for this allowance. */
    static int warned(Context context) {
        return prefs(context).getInt(KEY_WARNED, 0);
    }

    static void setWarned(Context context, int bits) {
        prefs(context).edit().putInt(KEY_WARNED, bits).apply();
    }

    /** Tells the running service that the allowance or the settings changed. */
    static void refresh(Context context) {
        if (isOn(context)) {
            context.startForegroundService(new Intent(context, ParentalService.class)
                    .setAction(ParentalService.ACTION_REFRESH));
        }
    }
}
