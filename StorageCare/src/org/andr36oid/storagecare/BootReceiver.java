package org.andr36oid.storagecare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Schedules the space check after a start or an update, and runs it when a card changes. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Checks.schedule(context);
        } else if (Intent.ACTION_MEDIA_MOUNTED.equals(action)
                || Intent.ACTION_MEDIA_UNMOUNTABLE.equals(action)) {
            Checks.soon(context);
        }
    }
}
