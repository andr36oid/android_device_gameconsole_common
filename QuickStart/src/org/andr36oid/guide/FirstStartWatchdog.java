package org.andr36oid.guide;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The watchdog alarm and the uninstall result, both from FirstStart. Not exported. */
public class FirstStartWatchdog extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (FirstStart.ACTION_WATCHDOG.equals(intent.getAction())) {
            FirstStart.onWatchdog(context);
        } else if (FirstStart.ACTION_UNINSTALLED.equals(intent.getAction())) {
            FirstStart.onUninstalled(intent);
        }
    }
}
