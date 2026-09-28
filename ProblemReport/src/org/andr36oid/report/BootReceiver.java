package org.andr36oid.report;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.DropBoxManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * After a start, looks for signs that the last one ended in a crash: a kernel crash record
 * kept by pstore (oops or panic, the kernel then restarts after 7 s), or Android's own
 * restart records in the dropbox (system_server crashed or hung). If there is a new one,
 * a notification offers to save a report while the logs are still there.
 * BOOT_COMPLETED also comes after a system_server restart, so both cases are caught.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "ProblemReport";
    static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL = "crash";
    private static final String PREFS = "boot";
    private static final String KEY_RECORD = "pstore_record";
    private static final String KEY_DROPBOX_TIME = "dropbox_time";
    private static final String[] RESTART_TAGS = {
        "system_server_crash", "system_server_watchdog", "system_server_native_crash",
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final SharedPreferences.Editor edit = prefs.edit();
        boolean crashed = false;

        final File record = Reporter.crashRecord();
        if (record != null) {
            final String fp = Reporter.fingerprint(record);
            if (fp != null && !fp.equals(prefs.getString(KEY_RECORD, null))) {
                Log.i(TAG, "kernel crash record from the last start: " + record);
                edit.putString(KEY_RECORD, fp);
                crashed = true;
            }
        }

        // The first time, only look at this kernel start: older entries are history.
        final long now = System.currentTimeMillis();
        final long since = prefs.getLong(KEY_DROPBOX_TIME,
                now - SystemClock.elapsedRealtime());
        final DropBoxManager dropbox = context.getSystemService(DropBoxManager.class);
        if (dropbox != null) {
            for (String tag : RESTART_TAGS) {
                final DropBoxManager.Entry e = dropbox.getNextEntry(tag, since);
                if (e != null) {
                    Log.i(TAG, "Android restarted after " + tag + " at " + e.getTimeMillis());
                    e.close();
                    crashed = true;
                }
            }
        }
        edit.putLong(KEY_DROPBOX_TIME, now);
        edit.apply();

        if (crashed) notifyCrash(context);
    }

    private static void notifyCrash(Context context) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT));

        final PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, ReportActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final String text = context.getString(R.string.crash_text);
        final Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_problem_report)
                .setContentTitle(context.getString(R.string.crash_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,
                        context.getString(R.string.crash_action), open).build())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
        nm.notify(NOTIFICATION_ID, n);
    }
}
