package org.andr36oid.guide;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The first start after the setup wizard, run with the first-start launcher.
 *
 * <p>The first-start launcher (org.andr36oid.firststart, FirstStart/ in this repo) is a tiny
 * user app with a home activity and nothing else. gameconsole-emu disables Daijishou's home
 * activity, installs the launcher while the setup wizard runs and gives it the home role. So
 * when the wizard ends, the launcher is the only home app, Android goes home to it, and it
 * tells FirstStartReceiver, which opens the tutorial.
 * Daijishou doesn't run at all until the tutorial is done, so it can't cover it. And each FN
 * press sends a new home intent to the launcher, which tells the tutorial exactly.
 *
 * <p>When the tutorial is finished or skipped, {@link #finish} swaps the home activities back
 * and gives the home role to Daijishou, sets the "shown" flag, opens the home screen (or the guide) and uninstalls the
 * launcher. The watchdog makes sure the console is never stuck with the launcher as home.
 */
final class FirstStart {

    private static final String TAG = "FirstStart";

    static final String LAUNCHER_PACKAGE = "org.andr36oid.firststart";
    private static final String DAIJISHOU_PACKAGE = "com.magneticchen.daijishou";
    private static final String SETUP_WIZARD_PACKAGE = "org.lineageos.setupwizard";
    /**
     * Disabled by gameconsole-emu while the launcher is home, so the launcher is the only home
     * app with priority 0 and Android never asks which home app to use.
     */
    private static final ComponentName DAIJISHOU_HOME = new ComponentName(DAIJISHOU_PACKAGE,
            "com.magneticchen.daijishou.activities.BootstrapActivity");
    private static final ComponentName LAUNCHER_HOME = new ComponentName(LAUNCHER_PACKAGE,
            "org.andr36oid.firststart.HomeActivity");

    /**
     * The safety net: if the first-start launcher is home and the tutorial showed no sign of
     * life (opened, heartbeat, step change) for this long, Daijishou is made home again and
     * the launcher is uninstalled. The tutorial beats every HEARTBEAT_MS while it's open, even
     * when the user reads a page for a long time, so only a tutorial that isn't there counts.
     */
    static final long NO_LIFE_TIMEOUT_MS = 60 * 1000;
    static final long HEARTBEAT_MS = 15 * 1000;
    /** Tutorial crashes during the first start before it gives up and restores home. */
    static final int MAX_CRASHES = 3;
    /** How long to wait for the role controller before uninstalling the launcher anyway. */
    private static final long ROLE_TIMEOUT_MS = 10 * 1000;

    static final String ACTION_WATCHDOG = "org.andr36oid.guide.action.FIRST_START_WATCHDOG";
    static final String ACTION_UNINSTALLED = "org.andr36oid.guide.action.FIRST_START_UNINSTALLED";

    private static final String PREFS = "guide";
    private static final String PREF_CRASHES = "first_start_crashes";

    private static boolean sFinishing;

    private FirstStart() {
    }

    static boolean isLauncherInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(LAUNCHER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Whether the launcher is home now and the tutorial isn't done: the tutorial's mode. */
    static boolean isActive(Context context) {
        return FirstBootReceiver.isSetupDone(context) && !FirstBootReceiver.wasShown(context)
                && isLauncherInstalled(context);
    }

    /**
     * The setup wizard is done, or the console started with setup done. Starts the watchdog
     * while the launcher is home. Without a launcher this start has no automatic tutorial:
     * the console was set up before this build, or the launcher couldn't be installed. Then
     * the "shown" flag is set, so the beta apps chooser doesn't wait for it, and the tutorial
     * stays in Settings > Quick start guide (X).
     */
    static void onSetupDone(Context context) {
        if (isLauncherInstalled(context)) {
            if (FirstBootReceiver.wasShown(context)) {
                // Done, but the launcher wasn't removed (the console restarted in between)
                Log.i(TAG, "Tutorial was done, cleaning up the first-start launcher");
                finish(context, false);
            } else {
                armWatchdog(context);
            }
        } else if (!FirstBootReceiver.wasShown(context)) {
            Log.i(TAG, "No first-start launcher, the tutorial stays in the guide");
            FirstBootReceiver.markShown(context);
        }
    }

    // ---- The watchdog

    /** A sign of life from the tutorial: the watchdog fires NO_LIFE_TIMEOUT_MS after the last. */
    static void heartbeat(Context context) {
        armWatchdog(context);
    }

    static void armWatchdog(Context context) {
        context.getSystemService(AlarmManager.class).setExact(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + NO_LIFE_TIMEOUT_MS, watchdogIntent(context));
    }

    private static void cancelWatchdog(Context context) {
        context.getSystemService(AlarmManager.class).cancel(watchdogIntent(context));
    }

    private static PendingIntent watchdogIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_WATCHDOG).setClass(context, FirstStartWatchdog.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * The watchdog fired: no sign of life for NO_LIFE_TIMEOUT_MS. A tutorial that is still
     * open in this process is alive (the console slept, and alarms and heartbeats wait for the
     * wake-up), so that only re-arms. Otherwise the tutorial is gone and home is restored.
     */
    static void onWatchdog(Context context) {
        if (!isLauncherInstalled(context) || !FirstBootReceiver.isSetupDone(context)) {
            return;
        }
        if (FirstBootReceiver.wasShown(context)) {
            finish(context, false);
        } else if (TutorialActivity.isAlive()) {
            armWatchdog(context);
        } else {
            Log.w(TAG, "No sign of the tutorial for " + NO_LIFE_TIMEOUT_MS / 1000
                    + " s, giving the home screen back");
            finish(context, false);
        }
    }

    // ---- Crashes

    /** Counts tutorial crashes while the launcher is home, see TutorialActivity. */
    static void noteCrash(Context context) {
        final SharedPreferences prefs = prefs(context);
        // commit, not apply: the process is about to die
        prefs.edit().putInt(PREF_CRASHES, prefs.getInt(PREF_CRASHES, 0) + 1).commit();
    }

    static boolean crashedTooOften(Context context) {
        return prefs(context).getInt(PREF_CRASHES, 0) >= MAX_CRASHES;
    }

    // ---- The end

    /**
     * Ends the first start: sets the "shown" flag, gives the home role back to Daijishou,
     * opens the home screen (or the guide, if asked), then uninstalls the launcher
     * without asking (this app runs as the system uid, which holds DELETE_PACKAGES). Safe to
     * call more than once.
     */
    static void finish(Context context, boolean openGuide) {
        final Context app = context.getApplicationContext();
        FirstBootReceiver.markShown(app);
        prefs(app).edit().remove(PREF_CRASHES).commit();
        cancelWatchdog(app);
        if (sFinishing) {
            return;
        }
        sFinishing = true;
        // Skipped on the launcher's page, or the watchdog: the tutorial may still be open
        TutorialActivity.close();

        final Handler handler = new Handler(Looper.getMainLooper());
        final AtomicBoolean ran = new AtomicBoolean();
        final Consumer<Boolean> then = ok -> {
            if (ran.getAndSet(true)) {
                return;
            }
            handler.removeCallbacksAndMessages(null);
            if (!ok) {
                // Uninstalling the launcher still hands home back: the role controller then
                // picks the only home app left
                Log.w(TAG, "Couldn't give the home role back, uninstalling anyway");
            }
            open(app, openGuide);
            uninstallLauncher(app);
            sFinishing = false;
        };
        // In case the role controller never answers
        handler.postDelayed(() -> then.accept(false), ROLE_TIMEOUT_MS);
        swapHome(app);
        final RoleManager roles = app.getSystemService(RoleManager.class);
        final String home = pickHome(app);
        try {
            if (home != null) {
                Log.i(TAG, "Giving the home role to " + home);
                roles.addRoleHolderAsUser(RoleManager.ROLE_HOME, home, 0,
                        Process.myUserHandle(), app.getMainExecutor(), then);
            } else {
                // No other home app: the role controller falls back by itself
                roles.removeRoleHolderAsUser(RoleManager.ROLE_HOME, LAUNCHER_PACKAGE, 0,
                        Process.myUserHandle(), app.getMainExecutor(), then);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't change the home role", e);
            then.accept(false);
        }
    }

    /**
     * The launcher's home activity off first, then Daijishou's on again: there are never two
     * home apps with priority 0 at the same time, so Android can't ask which one to use. In
     * between there is no real home app for a moment, and a home press then shows Settings'
     * FallbackHome, which hands over as soon as Daijishou is back.
     */
    private static void swapHome(Context context) {
        final PackageManager pm = context.getPackageManager();
        try {
            if (isLauncherInstalled(context)) {
                pm.setComponentEnabledSetting(LAUNCHER_HOME,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't disable the launcher's home activity", e);
        }
        try {
            if (pm.getComponentEnabledSetting(DAIJISHOU_HOME)
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(DAIJISHOU_HOME,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0);
            }
        } catch (RuntimeException e) {
            // Not installed. gameconsole-emu also enables it again on the next start
            Log.w(TAG, "Couldn't enable Daijishou's home activity", e);
        }
    }

    /** Daijishou if it's there, else any other home app but the launcher and system ones. */
    private static String pickHome(Context context) {
        final List<ResolveInfo> homes = context.getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        String other = null;
        for (ResolveInfo info : homes) {
            final String pkg = info.activityInfo.packageName;
            if (DAIJISHOU_PACKAGE.equals(pkg)) {
                return pkg;
            }
            if (other == null && !LAUNCHER_PACKAGE.equals(pkg)
                    && !SETUP_WIZARD_PACKAGE.equals(pkg)
                    && !info.activityInfo.name.endsWith(".FallbackHome")) {
                other = pkg;
            }
        }
        return other;
    }

    /**
     * The home screen, or only the guide: Daijishou brings itself to the front a few times
     * while it starts, which would cover the guide. Closing the guide goes home, and Daijishou
     * starts then.
     */
    private static void open(Context context, boolean openGuide) {
        try {
            context.startActivity(openGuide
                    ? new Intent(context, GuideActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    : new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't open " + (openGuide ? "the guide" : "the home screen"), e);
        }
    }

    private static void uninstallLauncher(Context context) {
        if (!isLauncherInstalled(context)) {
            return;
        }
        final PendingIntent status = PendingIntent.getBroadcast(context, 1,
                new Intent(ACTION_UNINSTALLED).setClass(context, FirstStartWatchdog.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            context.getPackageManager().getPackageInstaller().uninstall(LAUNCHER_PACKAGE,
                    status.getIntentSender());
        } catch (RuntimeException e) {
            // Tried again on the next start, see onSetupDone
            Log.w(TAG, "Couldn't uninstall the first-start launcher", e);
        }
    }

    /** Logs how the uninstall went. */
    static void onUninstalled(Intent intent) {
        final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(TAG, "First-start launcher uninstalled");
        } else {
            Log.w(TAG, "Uninstalling the first-start launcher failed: " + status + " "
                    + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
