package org.andr36oid.gamecomfort;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the background service after a restart, only if a feature is on. The first time,
 * also puts the game mode tile into Quick Settings.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Comfort.update(context);
            GameMode.addTileOnce(context);
        }
    }
}
