package org.andr36oid.gamecomfort;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the background service after a restart. The first time, also puts the game mode and
 * Continue tiles into Quick Settings.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Read before the service watches the home screen come up, which counts as
            // leaving the game
            final boolean gameOpen = Continue.wasGameOpen(context);
            Comfort.update(context, new Intent(context, ComfortService.class)
                    .putExtra(ComfortService.EXTRA_AFTER_BOOT, true)
                    .putExtra(ComfortService.EXTRA_GAME_WAS_OPEN, gameOpen));
            GameMode.addTileOnce(context);
            Continue.addTileOnce(context);
        }
    }
}
