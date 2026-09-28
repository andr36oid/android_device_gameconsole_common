package org.andr36oid.batterydetails;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the sampler on the first boot, and notes the level right after every boot. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SampleJob.schedule(context);
            History.record(context, Gauge.read());
        }
    }
}
