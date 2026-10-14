package org.andr36oid.bfi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Shows the Settings entry after every start if Peak refresh rate is 100 Hz or more. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Bfi.syncEntry(context);
    }
}
