package org.andr36oid.perfoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Puts the overlay back after a restart if it was on. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && Hud.isEnabled(context)) {
            Hud.apply(context, true);
        }
    }
}
