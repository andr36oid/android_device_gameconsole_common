package org.andr36oid.cpuoverclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Applies the saved profile after boot and puts its tile into Quick Settings the first time. */
public class ProfileReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        final PerformanceProfiles profiles = new PerformanceProfiles(context);
        profiles.apply();
        profiles.addTileOnce();
    }
}
