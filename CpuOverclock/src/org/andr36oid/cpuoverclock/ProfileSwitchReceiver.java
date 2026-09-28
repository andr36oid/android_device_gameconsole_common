package org.andr36oid.cpuoverclock;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Steps to the next profile and says which one it is. FN + R1 sends this. */
public class ProfileSwitchReceiver extends BroadcastReceiver {

    private static Toast sToast;

    @Override
    public void onReceive(Context context, Intent intent) {
        final PerformanceProfiles profiles = new PerformanceProfiles(context);
        final int next = profiles.next();
        final String text = profiles.set(next)
                ? context.getString(R.string.profile_switched, profiles.getName(next))
                : context.getString(R.string.profile_failed);
        // Replace the last message when stepping through the profiles quickly
        if (sToast != null) {
            sToast.cancel();
        }
        sToast = Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT);
        sToast.show();
        TileService.requestListeningState(context,
                new ComponentName(context, ProfileTileService.class));
    }
}
