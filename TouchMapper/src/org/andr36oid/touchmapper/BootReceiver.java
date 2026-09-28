package org.andr36oid.touchmapper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the watcher after a restart; it stops again at once if no profile is on. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // A leftover from before the restart must not grab the pad.
            Profiles.setActive(null);
            WatcherService.refresh(context);
        }
    }
}
