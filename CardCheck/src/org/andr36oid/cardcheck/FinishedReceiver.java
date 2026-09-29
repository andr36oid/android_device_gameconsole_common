package org.andr36oid.cardcheck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Map;

/**
 * The check service is done. A real size check takes hours, so it gets a notification;
 * a paused one gets a lasting reminder, because its test data keeps games from saving.
 */
public class FinishedReceiver extends BroadcastReceiver {

    static final int NOTIFICATION_ID = 1;
    static final int PAUSED_ID = 2;
    private static final String CHANNEL = "done";
    private static final String PAUSED_CHANNEL = "paused";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Map<String, String> s = Checker.status();
        if (!Checker.FULL.equals(s.get("action"))) return;
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.cancel(PAUSED_ID);
        final PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, CardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if ("paused".equals(s.get("state"))) {
            final String text = context.getString(R.string.paused_text,
                    Texts.size(context, Checker.number(s, "written_mb")));
            nm.createNotificationChannel(new NotificationChannel(PAUSED_CHANNEL,
                    context.getString(R.string.paused_channel),
                    NotificationManager.IMPORTANCE_LOW));
            nm.notify(PAUSED_ID, new Notification.Builder(context, PAUSED_CHANNEL)
                    .setSmallIcon(R.drawable.ic_card_check)
                    .setContentTitle(context.getString(R.string.paused_title))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .build());
            return;
        }
        // stopped from the page: nothing to tell
        if ("stopped".equals(s.get("state"))) return;
        final String text = Texts.fullResult(context, s);
        if (text == null) return;

        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT));
        nm.notify(NOTIFICATION_ID, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_card_check)
                .setContentTitle(context.getString(R.string.done_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build());
    }
}
