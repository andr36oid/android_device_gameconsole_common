package org.andr36oid.playtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Alarms don't survive a restart, so the break checks are set up again, and parental
 * controls start counting again.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Breaks.schedule(context, Breaks.getMinutes(context));
            // the time left was saved: a restart doesn't give any back
            if (Parental.isOn(context)) {
                context.startForegroundService(new Intent(context, ParentalService.class));
            }
        }
    }
}
