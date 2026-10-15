package org.andr36oid.batterydetails;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * Starts the sampler on the first boot, notes the level right after every boot, and picks
 * a capacity measurement back up.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SampleJob.schedule(context);
            History.record(context, Gauge.read());
            // A capacity measurement goes on, or ends if the console ran empty
            Measure.onBoot(context, context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
            final int state = Measure.state(context);
            if (state == Measure.WAITING || state == Measure.RUNNING) {
                MeasureService.start(context);
            }
        }
    }
}
