package org.andr36oid.playtime;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.preference.PreferenceManager;

/**
 * Break reminders. An alarm checks every 5 minutes while the console is awake (it never
 * wakes it up) how long the screen has been on, from the screen on and off events in the
 * usage stats. So nothing has to keep running in the background.
 */
final class Breaks {

    static final String KEY_MINUTES = "break_minutes";

    private static final String KEY_SESSION = "reminded_session";
    private static final String KEY_COUNT = "reminded_count";
    private static final String CHANNEL = "breaks";
    private static final int NOTIFICATION_ID = 1;

    private static final long CHECK_EVERY_MS = 5 * 60 * 1000L;
    /** A screen off shorter than this is not a break. */
    private static final long SHORTEST_BREAK_MS = 2 * 60 * 1000L;
    /** How far back to look for the start of the session. */
    private static final long LOOK_BACK_MS = 12 * 3600 * 1000L;

    private Breaks() {
    }

    static int getMinutes(Context context) {
        try {
            return Integer.parseInt(prefs(context).getString(KEY_MINUTES, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Starts or stops the checks. 0 minutes means no reminders. */
    static void schedule(Context context, int minutes) {
        final AlarmManager am = context.getSystemService(AlarmManager.class);
        final PendingIntent check = PendingIntent.getBroadcast(context, 0,
                new Intent(context, BreakReceiver.class), PendingIntent.FLAG_IMMUTABLE);
        am.cancel(check);
        if (minutes > 0) {
            // RTC, not RTC_WAKEUP: a sleeping console stays asleep.
            am.setInexactRepeating(AlarmManager.RTC,
                    System.currentTimeMillis() + CHECK_EVERY_MS, CHECK_EVERY_MS, check);
        }
    }

    static void check(Context context) {
        final int minutes = getMinutes(context);
        if (minutes <= 0 || !context.getSystemService(PowerManager.class).isInteractive()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long start = sessionStart(context, now);
        if (start < 0) {
            return;
        }
        final long played = now - start;
        final long due = played / (minutes * 60 * 1000L);
        final SharedPreferences prefs = prefs(context);
        final long reminded = prefs.getLong(KEY_SESSION, -1) == start
                ? prefs.getLong(KEY_COUNT, 0) : 0;
        if (due <= reminded) {
            return;
        }
        prefs.edit().putLong(KEY_SESSION, start).putLong(KEY_COUNT, due).apply();
        notify(context, played);
    }

    /** When the screen came on for this session, -1 if it is off. */
    private static long sessionStart(Context context, long now) {
        final UsageStatsManager usm = context.getSystemService(UsageStatsManager.class);
        final long from = now - LOOK_BACK_MS;
        final UsageEvents events = usm.queryEvents(from, now);
        long start = from;
        long lastOff = -1;
        boolean on = true;
        if (events != null) {
            final UsageEvents.Event event = new UsageEvents.Event();
            while (events.getNextEvent(event)) {
                final long time = event.getTimeStamp();
                switch (event.getEventType()) {
                    case UsageEvents.Event.SCREEN_INTERACTIVE:
                        if (lastOff < 0) {
                            // The first screen on in the window: it was off before that.
                            if (start == from) {
                                start = time;
                            }
                        } else if (!on && time - lastOff >= SHORTEST_BREAK_MS) {
                            // Back after a real break. A shorter pause continues the session.
                            start = time;
                        }
                        on = true;
                        break;
                    case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                    case UsageEvents.Event.DEVICE_SHUTDOWN:
                        lastOff = time;
                        on = false;
                        break;
                    case UsageEvents.Event.DEVICE_STARTUP:
                        // Off since the shutdown, a restart is a break too.
                        if (lastOff < 0 || time - lastOff >= SHORTEST_BREAK_MS) {
                            start = time;
                        }
                        on = true;
                        break;
                    default:
                        break;
                }
            }
        }
        return on ? start : -1;
    }

    private static void notify(Context context, long played) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        // High importance, so it pops up on top of the game for a few seconds.
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH));
        final int battery = context.getSystemService(BatteryManager.class)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        final String text = battery > 0 && battery <= 100
                ? context.getString(R.string.break_text_battery, battery)
                : context.getString(R.string.break_text);
        final PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, PlayTimeActivity.class), PendingIntent.FLAG_IMMUTABLE);
        final Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_play_time)
                .setContentTitle(context.getString(R.string.break_title,
                        Usage.format(context, played)))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(60 * 1000L)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        nm.notify(NOTIFICATION_ID, notification);
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }
}
