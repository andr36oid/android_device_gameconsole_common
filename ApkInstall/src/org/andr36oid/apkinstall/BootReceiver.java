package org.andr36oid.apkinstall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** At every start: look for APKs on EASYROMS (CheckService). */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startService(new Intent(context, CheckService.class));
        }
    }
}
