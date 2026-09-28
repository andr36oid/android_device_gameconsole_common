package org.andr36oid.playtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The break reminder alarm. */
public class BreakReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                Breaks.check(context);
            } finally {
                result.finish();
            }
        }, "BreakCheck").start();
    }
}
