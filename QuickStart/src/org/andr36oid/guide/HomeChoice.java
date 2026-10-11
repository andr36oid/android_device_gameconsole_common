package org.andr36oid.guide;

import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The two home screens a build can ship, Daijishou and our launcher, and the user's choice.
 *
 * <p>The setup wizard's "Choose your home screen" page writes the choice down
 * ({@link #SETTING_HOME}, {@link #SETTING_REMOVE_OTHER}). It is applied here when the first
 * start is over: by {@link FirstStart#finish} after the tutorial, or by {@link #applyOnce}
 * when there was no first-start launcher. Settings > Apps &amp; notifications > Home screen
 * ({@link HomeScreenActivity}) changes it later.
 *
 * <p>Two rules keep the console from ever showing Android's "which home app?" question or
 * having no home app:
 * <ul>
 * <li>A home activity is only switched on after the preferred home entry already lists it
 * ({@link #enableHome}). Android drops the "always use" entry when a new home app shows up
 * that it doesn't list, and would ask until the role controller catches up.
 * <li>The other home screen is only removed when the chosen one holds the home role and its
 * home activity is on ({@link #removeOther}). The chosen one is never removed.
 * </ul>
 *
 * <p>"Remove" is Android's uninstall for this user of a system app (the same as
 * {@code pm uninstall --user 0}): the app's data goes and it disappears from the app list, the
 * APK stays on /system, and {@link #restore} (the same as {@code pm install-existing}) brings
 * it back, empty.
 */
final class HomeChoice {

    private static final String TAG = "HomeChoice";

    static final String DAIJISHOU = "com.magneticchen.daijishou";
    static final ComponentName DAIJISHOU_HOME = new ComponentName(DAIJISHOU,
            "com.magneticchen.daijishou.activities.BootstrapActivity");

    /**
     * Our launcher's home activity (package/class), set by the build that ships it
     * (apps/Andr36oidLauncher in this repo), empty otherwise. The one place that knows its id.
     */
    private static final String PROP_LAUNCHER_HOME = "ro.andr36oid.launcher_home";

    /** Written by the setup wizard: the chosen home app's package. */
    static final String SETTING_HOME = "andr36oid_home";
    /** Written by the setup wizard: 1 = remove the other home screen after the first start. */
    static final String SETTING_REMOVE_OTHER = "andr36oid_home_remove_other";

    private static final String PREFS = "guide";
    private static final String PREF_APPLIED = "home_choice_applied";

    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String SETUP_WIZARD_PACKAGE = "org.lineageos.setupwizard";

    private HomeChoice() {
    }

    /** Our launcher's home activity, or null if this build doesn't ship it. */
    static ComponentName launcherHome() {
        return ComponentName.unflattenFromString(SystemProperties.get(PROP_LAUNCHER_HOME, ""));
    }

    /** Whether this build ships two home screens to choose from. */
    static boolean hasChoice() {
        return launcherHome() != null;
    }

    /**
     * Whether the home screen is (or, during the setup, will be) our launcher rather than
     * Daijishou, for the guide's texts.
     */
    static boolean isSimpleUi(Context context) {
        return hasChoice() && !DAIJISHOU.equals(chosen(context));
    }

    /**
     * Whether Daijishou is there to open from the app list: installed, and not about to be
     * removed because the user asked for that in the setup wizard.
     */
    static boolean keepsDaijishou(Context context) {
        return isInstalled(context, DAIJISHOU) && Settings.Global.getInt(
                context.getContentResolver(), SETTING_REMOVE_OTHER, 0) == 0;
    }

    /** Daijishou's and, if shipped, our launcher's home activity. */
    static List<ComponentName> ourHomes() {
        final List<ComponentName> homes = new ArrayList<>();
        homes.add(DAIJISHOU_HOME);
        final ComponentName launcher = launcherHome();
        if (launcher != null) {
            homes.add(launcher);
        }
        return homes;
    }

    static ComponentName homeOf(String packageName) {
        for (ComponentName home : ourHomes()) {
            if (home.getPackageName().equals(packageName)) {
                return home;
            }
        }
        return null;
    }

    /** The other one of our two home screens, null if there is only one. */
    static String otherOf(String packageName) {
        for (ComponentName home : ourHomes()) {
            if (!home.getPackageName().equals(packageName)) {
                return home.getPackageName();
            }
        }
        return null;
    }

    /** Installed for this user. A removed one is still on /system, but not installed. */
    static boolean isInstalled(Context context, String packageName) {
        try {
            final ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** On the system partition, installed for this user or removed. */
    static boolean isShipped(Context context, String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The package holding the home role now, or null. */
    static String currentHome(Context context) {
        try {
            final List<String> holders = context.getSystemService(RoleManager.class)
                    .getRoleHoldersAsUser(RoleManager.ROLE_HOME, Process.myUserHandle());
            return holders.isEmpty() ? null : holders.get(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't read the home role", e);
            return null;
        }
    }

    /**
     * The home app to use when the first start is over: the one chosen in the wizard if it's
     * still there; without a choice (the page wasn't shown, or the console was set up before
     * this build) the home app the user has now, else Daijishou, else our launcher.
     */
    static String chosen(Context context) {
        final String choice = Settings.Global.getString(context.getContentResolver(),
                SETTING_HOME);
        if (!TextUtils.isEmpty(choice) && homeOf(choice) != null
                && isInstalled(context, choice)) {
            return choice;
        }
        final String now = currentHome(context);
        if (now != null && !FirstStart.LAUNCHER_PACKAGE.equals(now)
                && !SETUP_WIZARD_PACKAGE.equals(now) && !SETTINGS_PACKAGE.equals(now)
                && isInstalled(context, now)) {
            return now;
        }
        for (ComponentName home : ourHomes()) {
            if (isInstalled(context, home.getPackageName())) {
                return home.getPackageName();
            }
        }
        return DAIJISHOU;
    }

    /**
     * Switches on the home activities of our home screens that are installed, the chosen one
     * first. Before an extra one comes on, the preferred home entry is written with it in the
     * list, so Android doesn't ask which home app to use (see the class comment).
     */
    static void showHomes(Context context, String chosen) {
        final ComponentName chosenHome = homeOf(chosen);
        if (chosenHome != null) {
            enableHome(context, chosenHome, null);
        }
        for (ComponentName home : ourHomes()) {
            if (!home.equals(chosenHome) && isInstalled(context, home.getPackageName())) {
                enableHome(context, home, chosenHome);
            }
        }
    }

    /**
     * Switches a home activity on if it's off. With {@code preferred}, first writes the
     * preferred home entry for the list that will be there afterwards, so the new home
     * activity doesn't make Android ask.
     */
    static void enableHome(Context context, ComponentName home, ComponentName preferred) {
        final PackageManager pm = context.getPackageManager();
        try {
            if (pm.getComponentEnabledSetting(home)
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                return;
            }
            if (preferred != null) {
                preferAhead(context, preferred, home);
            }
            pm.setComponentEnabledSetting(home, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't switch on " + home.flattenToShortString(), e);
        }
    }

    /**
     * Writes the "always use" home entry for {@code preferred}, listing today's home
     * activities plus {@code extra}, which is about to come (switched on or brought back).
     * What the role controller writes itself (PreferredActivity.configure), one step early.
     */
    static void preferAhead(Context context, ComponentName preferred, ComponentName extra) {
        final PackageManager pm = context.getPackageManager();
        final Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        final List<ResolveInfo> infos = pm.queryIntentActivities(intent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                        | PackageManager.MATCH_DEFAULT_ONLY);
        final List<ComponentName> set = new ArrayList<>();
        for (ResolveInfo info : infos) {
            set.add(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
        }
        if (!set.contains(extra)) {
            set.add(extra);
        }
        if (!set.contains(preferred)) {
            set.add(preferred);
        }
        final IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            pm.replacePreferredActivity(filter, IntentFilter.MATCH_CATEGORY_EMPTY,
                    set.toArray(new ComponentName[0]), preferred);
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't set the preferred home app", e);
        }
    }

    /** Gives the home role to a package; {@code then} gets whether that worked. */
    static void makeHome(Context context, String packageName, Consumer<Boolean> then) {
        final Context app = context.getApplicationContext();
        try {
            Log.i(TAG, "Giving the home role to " + packageName);
            context.getSystemService(RoleManager.class).addRoleHolderAsUser(
                    RoleManager.ROLE_HOME, packageName, 0, Process.myUserHandle(),
                    context.getMainExecutor(), ok -> {
                        if (ok && homeOf(packageName) != null) {
                            // gameconsole-emu's repair gives the role to this setting at the
                            // next boot: keep it on what the user picked last, not the wizard
                            Settings.Global.putString(app.getContentResolver(), SETTING_HOME,
                                    packageName);
                        }
                        then.accept(ok);
                    });
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't give the home role to " + packageName, e);
            then.accept(false);
        }
    }

    /**
     * Removes {@code packageName} for this user, only if it isn't the home app and the home
     * app is the other one of ours, installed with its home activity on. Returns whether it
     * was removed.
     */
    static boolean remove(Context context, String packageName) {
        final String home = currentHome(context);
        final ComponentName homeActivity = home != null ? homeOf(home) : null;
        if (homeOf(packageName) == null || packageName.equals(home) || homeActivity == null
                || !isInstalled(context, home)
                || context.getPackageManager().getComponentEnabledSetting(homeActivity)
                        == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            Log.w(TAG, "Not removing " + packageName + ", the home app is " + home);
            return false;
        }
        if (!isInstalled(context, packageName)) {
            return true;
        }
        try {
            // pm uninstall --user 0: DELETE_SYSTEM_APP for this user only
            context.getPackageManager().setSystemAppState(packageName,
                    PackageManager.SYSTEM_APP_STATE_UNINSTALLED);
            Log.i(TAG, "Removed " + packageName + " (it stays on /system)");
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't remove " + packageName, e);
            return false;
        }
    }

    /**
     * Brings a removed home screen back (pm install-existing). The home app stays what it is:
     * the preferred home entry lists the returning app before it arrives, and the home role
     * is given to the home app again afterwards, which rewrites the entry for good.
     */
    static boolean restore(Context context, String packageName) {
        final ComponentName back = homeOf(packageName);
        if (back == null || !isShipped(context, packageName)) {
            return false;
        }
        if (isInstalled(context, packageName)) {
            return true;
        }
        final String home = currentHome(context);
        final ComponentName homeActivity = home != null ? homeOf(home) : null;
        if (homeActivity != null) {
            preferAhead(context, homeActivity, back);
        }
        try {
            context.getPackageManager().setSystemAppState(packageName,
                    PackageManager.SYSTEM_APP_STATE_INSTALLED);
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't bring back " + packageName, e);
            return false;
        }
        if (!isInstalled(context, packageName)) {
            Log.w(TAG, "Bringing back " + packageName + " didn't work");
            return false;
        }
        Log.i(TAG, "Brought back " + packageName);
        if (home != null) {
            makeHome(context, home, ok -> { });
        }
        return true;
    }

    /**
     * After the home role went to {@code chosen}: removes the other home screen if the user
     * asked for it in the wizard, once.
     */
    static void removeOtherIfAsked(Context context, String chosen) {
        if (Settings.Global.getInt(context.getContentResolver(), SETTING_REMOVE_OTHER, 0)
                == 0) {
            return;
        }
        final String other = otherOf(chosen);
        if (other == null || !chosen.equals(currentHome(context))) {
            Log.w(TAG, "Not removing the other home screen: " + chosen + " isn't home");
            return;
        }
        if (remove(context, other)) {
            // Once: from now on only Settings > Home screen removes or brings back
            Settings.Global.putInt(context.getContentResolver(), SETTING_REMOVE_OTHER, 0);
        }
    }

    /**
     * Without a first-start launcher (it couldn't be installed, or the console was set up
     * before this build): applies the choice once. On an older console this only gives the
     * role to the home app it has now again, which lists our launcher in the "always use"
     * entry after an update brought it.
     */
    static void applyOnce(Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_APPLIED, false)) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String home = chosen(app);
        showHomes(app, home);
        makeHome(app, home, ok -> {
            if (ok) {
                removeOtherIfAsked(app, home);
                prefs.edit().putBoolean(PREF_APPLIED, true).apply();
            }
        });
    }

    /** Called after FirstStart gave the role to the chosen home: nothing more to apply. */
    static void markApplied(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_APPLIED, true).apply();
    }
}
