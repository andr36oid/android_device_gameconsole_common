package org.andr36oid.betaapps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * On every start, if the beta list has apps the tester wasn't asked about, waits until the
 * setup wizard is done, the quick start guide was shown and closed and the home screen is up,
 * then opens the Beta apps screen (see {@link WhenReadyJobService}).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && BetaList.hasUnasked(context)) {
            WhenReadyJobService.start(context);
        }
    }
}
