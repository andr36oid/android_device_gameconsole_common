package org.andr36oid.cpuoverclock;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.service.quicksettings.TileService;

import org.andr36oid.cpuoverclock.AppProfileRules.Front;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-app profiles: an app can have its own profile, used while it is in front. The chosen
 * profile comes back when it is left. Off until switched on in Settings > Performance >
 * Per-app profiles. The watcher (AppProfileService) runs only while it is on and at least
 * one app has a profile of its own, and only then starts at boot.
 */
final class AppProfiles {

    private static final String PREFS = "app_profiles";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_APP = "app:";
    // What is in front right now, written by the watcher
    private static final String STATE = "app_profiles_front";
    private static final String STATE_PKG = "pkg";
    private static final String STATE_PROFILE = "profile";
    private static final String STATE_PICKED = "picked";

    private AppProfiles() {
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ENABLED, false);
    }

    static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(PREF_ENABLED, on).commit();
        update(context);
    }

    /** The user's choice for an app, FOLLOW for Default, or the shipped default if none. */
    static int getChoice(Context context, String pkg) {
        return AppProfileRules.profileFor(pkg, chosen(context), defaults(context), null);
    }

    static void setChoice(Context context, String pkg, int profile) {
        prefs(context).edit().putInt(PREF_APP + pkg, profile).commit();
        update(context);
    }

    /** The profile an app asks for, FOLLOW for none. */
    static int profileFor(Context context, String pkg) {
        return AppProfileRules.profileFor(pkg, chosen(context), defaults(context),
                context.getPackageName());
    }

    /** Whether the watcher has something to do. */
    static boolean isNeeded(Context context) {
        return isEnabled(context) && AppProfileRules.anySet(chosen(context), defaults(context));
    }

    /** Starts or stops the watcher and its start at boot to match the settings. */
    static void update(Context context) {
        final boolean needed = isNeeded(context);
        final PackageManager pm = context.getPackageManager();
        final ComponentName receiver = new ComponentName(context, AppProfileBootReceiver.class);
        final int state = needed ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (pm.getComponentEnabledSetting(receiver) != state) {
            pm.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP);
        }
        // The service looks again and stops itself if it isn't needed
        context.startService(new Intent(context, AppProfileService.class));
    }

    /** The profile in effect, given the chosen one. */
    static int effective(Context context, int chosen) {
        return AppProfileRules.effective(isEnabled(context), getFront(context), chosen);
    }

    static Front getFront(Context context) {
        final SharedPreferences state = state(context);
        final String pkg = state.getString(STATE_PKG, null);
        if (pkg == null) {
            return Front.NONE;
        }
        return new Front(pkg, state.getInt(STATE_PROFILE, AppProfileRules.FOLLOW),
                state.getInt(STATE_PICKED, AppProfileRules.FOLLOW));
    }

    static void setFront(Context context, Front front) {
        final SharedPreferences.Editor edit = state(context).edit().clear();
        if (front.pkg != null) {
            edit.putString(STATE_PKG, front.pkg).putInt(STATE_PROFILE, front.profile)
                    .putInt(STATE_PICKED, front.picked);
        }
        edit.commit();
    }

    /**
     * A pick with the tile or FN + R1. Returns true if it only lasts while the app in front
     * is in front (it has a profile of its own), false if the chosen profile should change.
     */
    static boolean pick(Context context, int profile) {
        if (!isEnabled(context)) {
            return false;
        }
        final Front picked = AppProfileRules.onPick(getFront(context), profile);
        if (picked == null) {
            return false;
        }
        setFront(context, picked);
        return true;
    }

    /** The name of the app whose profile is in effect, null if the chosen one is. */
    static CharSequence getFrontLabel(Context context) {
        final Front front = getFront(context);
        if (!isEnabled(context) || !front.hasOwn()) {
            return null;
        }
        try {
            final PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(front.pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return front.pkg;
        }
    }

    /** Asks the Quick Settings tile to show the profile in effect. */
    static void updateTile(Context context) {
        TileService.requestListeningState(context,
                new ComponentName(context, ProfileTileService.class));
    }

    /** How many installed apps have a profile of their own. */
    static int countSet(Context context) {
        int count = 0;
        for (String pkg : launchableApps(context).keySet()) {
            if (AppProfileRules.valid(profileFor(context, pkg))) {
                count++;
            }
        }
        return count;
    }

    /** Apps that can be opened (launcher, TV launcher or home screen), by package, sorted by name. */
    static Map<String, ResolveInfo> launchableApps(Context context) {
        final PackageManager pm = context.getPackageManager();
        final List<ResolveInfo> found = new ArrayList<>();
        for (String category : new String[] {Intent.CATEGORY_LAUNCHER,
                Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_HOME}) {
            found.addAll(pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(category), 0));
        }
        final Map<String, ResolveInfo> byPackage = new HashMap<>();
        final Map<String, String> labels = new HashMap<>();
        for (ResolveInfo info : found) {
            final String pkg = info.activityInfo.packageName;
            // Not this app, and not the fallback home screen that only shows while booting
            if (pkg.equals(context.getPackageName()) || byPackage.containsKey(pkg)
                    || info.activityInfo.name.endsWith("FallbackHome")) {
                continue;
            }
            byPackage.put(pkg, info);
            labels.put(pkg, info.loadLabel(pm).toString());
        }
        final List<String> pkgs = new ArrayList<>(byPackage.keySet());
        pkgs.sort((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        final Map<String, ResolveInfo> sorted = new LinkedHashMap<>();
        for (String pkg : pkgs) {
            sorted.put(pkg, byPackage.get(pkg));
        }
        return sorted;
    }

    private static Map<String, Integer> chosen(Context context) {
        final Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs(context).getAll().entrySet()) {
            if (e.getKey().startsWith(PREF_APP) && e.getValue() instanceof Integer) {
                result.put(e.getKey().substring(PREF_APP.length()), (Integer) e.getValue());
            }
        }
        return result;
    }

    private static Map<String, Integer> defaults(Context context) {
        return AppProfileRules.parseDefaults(
                context.getResources().getStringArray(R.array.app_profile_defaults));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }
}
