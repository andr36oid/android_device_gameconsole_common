package org.andr36oid.batterydetails;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.provider.Settings;

import java.io.File;

/**
 * Measures the battery's real capacity: the charge that comes out of it from full until
 * the console turns off. The kernel counts it from the coulomb counter (drained_uah, since
 * boot), so sleep is counted too. Restarts on the way are fine: each boot adds its part.
 * MeasureService watches the charger and saves the count while it runs.
 */
final class Measure {

    static final int IDLE = 0;
    /** Waiting for a full charge and the charger to come off */
    static final int WAITING = 1;
    static final int RUNNING = 2;
    /** A result is there to be used */
    static final int DONE = 3;
    static final int FAILED = 4;

    static final int FAIL_PLUGGED = 1;
    static final int FAIL_CHARGED = 2;
    static final int FAIL_SHORT = 3;

    private static final String KNOB = "device/drained_uah";
    private static final String PREFS = "measure";
    private static final String STATE = "state";
    private static final String FULL_SEEN = "full_seen";
    private static final String BOOT = "boot";
    /** drained_uah when this boot's part started */
    private static final String BASE = "base_uah";
    /** What the boots before this one added up to */
    private static final String EARLIER = "earlier_uah";
    /** This boot's part so far, with the level and voltage at that point */
    private static final String LAST = "last_uah";
    private static final String LAST_PERCENT = "last_percent";
    private static final String LAST_MV = "last_mv";
    private static final String RESULT = "result_mah";
    private static final String FAIL = "fail";

    /** Below this or this voltage the console was about to turn off: the measurement ends */
    private static final int EMPTY_PERCENT = 3;
    private static final int EMPTY_MV = 3550;
    /** Less than this is no real battery, something went wrong */
    private static final int MIN_RESULT_MAH = 500;

    static final String CHANNEL = "measure";
    static final int NOTIFICATION_RUNNING = 1;
    private static final int NOTIFICATION_RESULT = 2;

    private Measure() {}

    static boolean isSupported() {
        final String dir = Gauge.findBattery();
        return dir != null && new File(dir + KNOB).exists();
    }

    /** Charge out of the battery since boot in uAh, -1 if unknown. */
    static long drainedUah() {
        final String dir = Gauge.findBattery();
        final int mah = dir == null ? Gauge.UNKNOWN : Gauge.readInt(dir + KNOB, 1);
        return mah == Gauge.UNKNOWN ? -1 : mah;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int state(Context context) {
        return prefs(context).getInt(STATE, IDLE);
    }

    static boolean fullSeen(Context context) {
        return prefs(context).getBoolean(FULL_SEEN, false);
    }

    static int resultMah(Context context) {
        return prefs(context).getInt(RESULT, 0);
    }

    static int failure(Context context) {
        return prefs(context).getInt(FAIL, 0);
    }

    /** All of it so far in mAh, while running. */
    static int soFarMah(Context context) {
        final SharedPreferences p = prefs(context);
        return (int) ((p.getLong(EARLIER, 0) + p.getLong(LAST, 0)) / 1000);
    }

    static void start(Context context) {
        prefs(context).edit().clear().putInt(STATE, WAITING).apply();
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_RESULT);
        MeasureService.start(context);
    }

    static void stop(Context context) {
        prefs(context).edit().clear().apply();
        context.stopService(new Intent(context, MeasureService.class));
    }

    /** What the battery broadcast says, while waiting or running. False when it's over. */
    static boolean update(Context context, Intent battery) {
        final SharedPreferences p = prefs(context);
        final boolean plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        final int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        final int mv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        switch (p.getInt(STATE, IDLE)) {
            case WAITING:
                if (level >= 100 || status == BatteryManager.BATTERY_STATUS_FULL) {
                    if (!p.getBoolean(FULL_SEEN, false)) {
                        p.edit().putBoolean(FULL_SEEN, true).apply();
                    }
                }
                if (!plugged && p.getBoolean(FULL_SEEN, false)) {
                    final long drained = drainedUah();
                    if (drained < 0) {
                        return false;
                    }
                    p.edit().putInt(STATE, RUNNING).putInt(BOOT, bootCount(context))
                            .putLong(BASE, drained).putLong(EARLIER, 0).putLong(LAST, 0)
                            .putInt(LAST_PERCENT, level).putInt(LAST_MV, mv).apply();
                }
                return true;
            case RUNNING:
                save(context, level, mv);
                if (plugged) {
                    if (level <= EMPTY_PERCENT) {
                        finish(context);
                    } else {
                        fail(context, FAIL_PLUGGED);
                    }
                    return false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Notes this boot's part, so a sudden power-off loses next to nothing. */
    static void save(Context context, int level, int mv) {
        final SharedPreferences p = prefs(context);
        final long drained = drainedUah();
        if (p.getInt(STATE, IDLE) != RUNNING || drained < 0) {
            return;
        }
        p.edit().putLong(LAST, Math.max(0, drained - p.getLong(BASE, 0)))
                .putInt(LAST_PERCENT, level).putInt(LAST_MV, mv).commit();
    }

    /**
     * After a restart: if the console had run empty, that's the result. If it was charged
     * in between, the measurement is off. Otherwise this boot adds its part.
     */
    static void onBoot(Context context, Intent battery) {
        final SharedPreferences p = prefs(context);
        if (p.getInt(STATE, IDLE) != RUNNING || p.getInt(BOOT, -1) == bootCount(context)) {
            return;
        }
        final int lastPercent = p.getInt(LAST_PERCENT, 100);
        final int lastMv = p.getInt(LAST_MV, 0);
        final boolean plugged = battery != null
                && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        final int level = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        if (lastPercent <= EMPTY_PERCENT || (lastMv > 0 && lastMv < EMPTY_MV)) {
            finish(context);
        } else if (plugged || level > lastPercent + 3) {
            fail(context, FAIL_CHARGED);
        } else {
            // The kernel counts from boot, so this boot's part starts at 0
            p.edit().putLong(EARLIER, p.getLong(EARLIER, 0) + p.getLong(LAST, 0))
                    .putLong(LAST, 0).putLong(BASE, 0).putInt(BOOT, bootCount(context))
                    .apply();
        }
    }

    private static void finish(Context context) {
        final int mah = soFarMah(context);
        if (mah < MIN_RESULT_MAH) {
            fail(context, FAIL_SHORT);
            return;
        }
        prefs(context).edit().clear().putInt(STATE, DONE).putInt(RESULT, mah).apply();
        notifyResult(context, context.getString(R.string.measure_done_title),
                context.getString(R.string.measure_done_text, mah));
    }

    private static void fail(Context context, int why) {
        prefs(context).edit().clear().putInt(STATE, FAILED).putInt(FAIL, why).apply();
        notifyResult(context, context.getString(R.string.measure_failed_title),
                failureText(context, why));
    }

    static String failureText(Context context, int why) {
        return context.getString(why == FAIL_PLUGGED ? R.string.measure_fail_plugged
                : why == FAIL_CHARGED ? R.string.measure_fail_charged
                : R.string.measure_fail_short);
    }

    /** The result is used or thrown away. */
    static void clear(Context context) {
        prefs(context).edit().clear().apply();
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_RESULT);
    }

    static void createChannel(Context context) {
        context.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, context.getString(R.string.measure_channel),
                        NotificationManager.IMPORTANCE_LOW));
    }

    static PendingIntent openPage(Context context) {
        return PendingIntent.getActivity(context, 0,
                new Intent(context, DetailsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void notifyResult(Context context, String title, String text) {
        createChannel(context);
        context.getSystemService(NotificationManager.class).notify(NOTIFICATION_RESULT,
                new Notification.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_battery_details)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setContentIntent(openPage(context))
                        .setAutoCancel(true)
                        .build());
    }

    private static int bootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.BOOT_COUNT, 0);
    }
}
