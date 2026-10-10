package org.andr36oid.cpuoverclock;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Steps to the next profile and says which one it is. FN + R1 sends this. With an app in front
 * that has its own profile, the step only lasts until that app is left.
 */
public class ProfileSwitchReceiver extends BroadcastReceiver {

    private static Toast sToast;

    @Override
    public void onReceive(Context context, Intent intent) {
        final PerformanceProfiles profiles = PerformanceProfiles.inEffect(context);
        final int next = profiles.next();
        String text = profiles.set(next)
                ? profiles.describeForToast(next)
                : context.getString(R.string.profile_failed);
        final CharSequence app = AppProfiles.getFrontLabel(context);
        if (app != null) {
            // The app in front has its own profile, the pick lasts until it is left
            text += "\n" + context.getString(R.string.app_profile_until_left, app);
        }
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
