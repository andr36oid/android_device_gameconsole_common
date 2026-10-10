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
 *
 * SET_PROFILE may also ask to hold the memory clock at its top speed until RESTORE_PROFILE,
 * whatever the profile. The profile may then be -1 to leave the profile as it is.
 */
public class ProfileCommandReceiver extends BroadcastReceiver {

    static final String ACTION_SET = "org.andr36oid.cpuoverclock.action.SET_PROFILE";
    static final String ACTION_RESTORE = "org.andr36oid.cpuoverclock.action.RESTORE_PROFILE";
    // 0 battery saver, 1 balanced, 2 performance, -1 don't change
    static final String EXTRA_PROFILE = "profile";
    static final String EXTRA_HOLD_MEMORY = "hold_memory";

    static final String PREFS = "profile_command";
    private static final String PREF_BEFORE = "before";
    private static final String PREF_SET = "set";
    static final String PREF_HOLD_MEMORY = "hold_memory";

    /** Whether a SET_PROFILE asked to hold the memory clock and no RESTORE_PROFILE came yet. */
    static boolean isMemoryHeld(Context context) {
        return prefs(context).getBoolean(PREF_HOLD_MEMORY, false);
    }

    /** Drops the memory hold after a restart, nobody is playing yet. */
    static void clearMemoryHold(Context context) {
        prefs(context).edit().remove(PREF_HOLD_MEMORY).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final PerformanceProfiles profiles = new PerformanceProfiles(context);
        final SharedPreferences prefs = prefs(context);
        if (ACTION_SET.equals(intent.getAction())) {
            final int profile = intent.getIntExtra(EXTRA_PROFILE, -1);
            final boolean hold = intent.getBooleanExtra(EXTRA_HOLD_MEMORY, false);
            final boolean validProfile = profile >= 0 && profile < PerformanceProfiles.COUNT;
            if (!validProfile && !hold) {
                return;
            }
            if (hold) {
                prefs.edit().putBoolean(PREF_HOLD_MEMORY, true).commit();
            }
            if (validProfile) {
                // Keep the first "before" when SET comes twice without a RESTORE
                if (!prefs.contains(PREF_BEFORE)) {
                    prefs.edit().putInt(PREF_BEFORE, profiles.get()).commit();
                }
                prefs.edit().putInt(PREF_SET, profile).commit();
            }
            if (validProfile && profiles.get() != profile) {
                profiles.set(profile);
            } else {
                // Same profile, but the memory hold may have changed.
                profiles.apply();
            }
        } else if (ACTION_RESTORE.equals(intent.getAction())) {
            final boolean held = prefs.getBoolean(PREF_HOLD_MEMORY, false);
            if (!prefs.contains(PREF_BEFORE)) {
                if (held) {
                    prefs.edit().clear().commit();
                    profiles.apply();
                }
                return;
            }
            final int before = prefs.getInt(PREF_BEFORE, PerformanceProfiles.BALANCED);
            final int set = prefs.getInt(PREF_SET, -1);
            prefs.edit().clear().commit();
            if (profiles.get() == set && set != before) {
                profiles.set(before);
            } else if (held) {
                profiles.apply();
            }
        } else {
            return;
        }
        TileService.requestListeningState(context,
                new ComponentName(context, ProfileTileService.class));
    }
}
