package org.andr36oid.cpuoverclock;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.quicksettings.TileService;

/**
 * Lets other system apps pick a profile for a while, quietly. Game mode sends SET_PROFILE when
 * it starts and RESTORE_PROFILE when it ends. The profile from before comes back only if
 * nobody changed the profile in the meantime.
 */
public class ProfileCommandReceiver extends BroadcastReceiver {

    static final String ACTION_SET = "org.andr36oid.cpuoverclock.action.SET_PROFILE";
    static final String ACTION_RESTORE = "org.andr36oid.cpuoverclock.action.RESTORE_PROFILE";
    // 0 battery saver, 1 balanced, 2 performance
    static final String EXTRA_PROFILE = "profile";

    private static final String PREFS = "profile_command";
    private static final String PREF_BEFORE = "before";
    private static final String PREF_SET = "set";

    @Override
    public void onReceive(Context context, Intent intent) {
        final PerformanceProfiles profiles = new PerformanceProfiles(context);
        final SharedPreferences prefs = context.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE);
        if (ACTION_SET.equals(intent.getAction())) {
            final int profile = intent.getIntExtra(EXTRA_PROFILE, -1);
            if (profile < 0 || profile >= PerformanceProfiles.COUNT) {
                return;
            }
            // Keep the first "before" when SET comes twice without a RESTORE
            if (!prefs.contains(PREF_BEFORE)) {
                prefs.edit().putInt(PREF_BEFORE, profiles.get()).commit();
            }
            prefs.edit().putInt(PREF_SET, profile).commit();
            if (profiles.get() != profile) {
                profiles.set(profile);
            }
        } else if (ACTION_RESTORE.equals(intent.getAction())) {
            if (!prefs.contains(PREF_BEFORE)) {
                return;
            }
            final int before = prefs.getInt(PREF_BEFORE, PerformanceProfiles.BALANCED);
            final int set = prefs.getInt(PREF_SET, -1);
            prefs.edit().clear().commit();
            if (profiles.get() == set && set != before) {
                profiles.set(before);
            }
        } else {
            return;
        }
        TileService.requestListeningState(context,
                new ComponentName(context, ProfileTileService.class));
    }
}
