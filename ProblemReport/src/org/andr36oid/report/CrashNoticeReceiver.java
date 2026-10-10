package org.andr36oid.report;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The last start ended in a crash: a kernel crash record kept by pstore, or a system_server
 * crash or hang in the dropbox. andr36oid-report-check.sh (root, after every start) looks for
 * those and sends this only when it found a new one, so the app doesn't start at every boot.
 * A notification offers to save a report while the logs are still there.
 */
public class CrashNoticeReceiver extends BroadcastReceiver {

    static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL = "crash";

    @Override
    public void onReceive(Context context, Intent intent) {
        notifyCrash(context);
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
