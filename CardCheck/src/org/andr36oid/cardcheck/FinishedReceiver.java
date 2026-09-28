package org.andr36oid.cardcheck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Map;

/** The check service is done. A real size check takes hours, so it gets a notification. */
public class FinishedReceiver extends BroadcastReceiver {

    static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL = "done";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Map<String, String> s = Checker.status();
        if (!Checker.FULL.equals(s.get("action"))) return;
        final String text = Texts.fullResult(context, s);
        if (text == null) return;

        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT));
        final PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, CardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
