package org.andr36oid.cpuoverclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.andr36oid.cpuoverclock.AppProfileRules.Front;

/**
 * Starts the per-app profile watcher after a restart. Off in the manifest and switched on
 * only while per-app profiles are on and some app has one (AppProfiles.update), so the app
 * doesn't start at every boot for nothing.
 */
public class AppProfileBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        // What was in front before the restart isn't any more. The profile receiver may have
        // run first with the old app's profile, so write the chosen one again.
        AppProfiles.setFront(context, Front.NONE);
        new PerformanceProfiles(context).apply();
        AppProfiles.update(context);
    }
}
