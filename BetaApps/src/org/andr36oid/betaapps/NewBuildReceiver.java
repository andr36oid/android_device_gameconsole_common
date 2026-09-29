package org.andr36oid.betaapps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The first start of a new build (Android sends PRE_BOOT_COMPLETED when ro.build.date
 * changed, before BOOT_COMPLETED). A new build can bring new beta apps, so the boot receiver
 * goes back on and looks again at this boot.
 */
public class NewBuildReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_PRE_BOOT_COMPLETED.equals(intent.getAction())) {
            BootStart.set(context, true);
        }
    }
}
