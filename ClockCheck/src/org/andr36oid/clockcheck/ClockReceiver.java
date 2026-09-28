package org.andr36oid.clockcheck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.Date;

/**
 * The console has no clock battery of its own: the RK817 PMIC keeps the time only while the
 * main battery has charge. Once it runs completely empty, the clock starts over, and Android
 * then moves it forward to the build date of the system (AlarmManagerService does that for any
 * time before it). So after such a reset the date is weeks or months in the past, which breaks
 * TLS when a network adapter is plugged in, and mixes up save file dates.
 *
 * At boot this spots two signs of a reset and posts one notification with a shortcut to the
 * date settings. Setting the time (by hand or from network time) removes it again.
 */
public class ClockReceiver extends BroadcastReceiver {
    private static final String TAG = "ClockCheck";

    private static final String CHANNEL = "clock_reset";
    private static final int NOTIFICATION_ID = 1;

    private static final String PREFS = "clock";
    // The latest time the clock was known to be right, in wall clock milliseconds
    private static final String PREF_LAST_GOOD = "last_good";

    // A boot this close to the system build date was moved there by Android
    private static final long BUILD_DATE_SLACK_MS = 5 * 60 * 1000L;
    // A clock this far behind the last good time went backwards
    private static final long BACKWARDS_SLACK_MS = 10 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            checkAfterBoot(context);
        } else if (Intent.ACTION_TIME_CHANGED.equals(action)) {
            // Someone set the clock: the user, or network time. Trust it from here on.
            context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
            rememberGoodTime(context);
        }
    }

    private static void checkAfterBoot(Context context) {
        final long now = System.currentTimeMillis();
        final long bootWallTime = now - SystemClock.elapsedRealtime();
        // The same time AlarmManagerService moves an earlier clock forward to
        final long buildTime = Math.max(
                1000L * SystemProperties.getLong("ro.build.date.utc", -1L),
                Math.max(Environment.getRootDirectory().lastModified(), Build.TIME));
        final long lastGood = prefs(context).getLong(PREF_LAST_GOOD, 0);

        final boolean movedToBuildDate = buildTime > 0
                && bootWallTime < buildTime + BUILD_DATE_SLACK_MS;
        final boolean wentBackwards = lastGood > 0 && now < lastGood - BACKWARDS_SLACK_MS;

        if (movedToBuildDate || wentBackwards) {
            Log.i(TAG, "Clock looks reset: now " + new Date(now) + ", system built "
                    + new Date(buildTime) + ", last good " + new Date(lastGood));
            notifyReset(context, now);
        } else {
            context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
            rememberGoodTime(context);
        }
    }

    private static void notifyReset(Context context, long now) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        final NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.channel_description));
        channel.setSound(null, null);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);

        final boolean autoTime = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AUTO_TIME, 1) != 0;
        final String date = DateFormat.getMediumDateFormat(context).format(new Date(now));
        final String text = context.getString(R.string.reset_text, date) + " "
                + context.getString(autoTime ? R.string.reset_text_auto
                        : R.string.reset_text_manual);

        final PendingIntent settings = PendingIntent.getActivity(context, 0,
                new Intent(Settings.ACTION_DATE_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_clock_check)
                .setContentTitle(context.getString(R.string.reset_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(settings)
                .addAction(new Notification.Action.Builder(null,
                        context.getString(R.string.action_set), settings).build())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        nm.notify(NOTIFICATION_ID, notification);
    }

    private static void rememberGoodTime(Context context) {
        prefs(context).edit().putLong(PREF_LAST_GOOD, System.currentTimeMillis()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
