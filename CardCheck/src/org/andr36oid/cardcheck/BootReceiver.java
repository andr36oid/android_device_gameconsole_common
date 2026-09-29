package org.andr36oid.cardcheck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Android drops notifications at a restart. A paused or power-cut size check still fills
 * the card, so its reminder is posted again at start-up.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        final long mb = Checker.pausedAnywhereMb();
        if (mb >= 0) FinishedReceiver.showPaused(context, mb);
    }
}
