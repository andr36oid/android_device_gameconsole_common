package org.andr36oid.betaapps;

import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Opens the Beta apps screen once the console is ready for it, so it never pops up over the
 * setup wizard, the quick start guide or a game:
 * <ol>
 * <li>the setup wizard is done (a content trigger on user_setup_complete, like the guide),</li>
 * <li>the quick start guide, if this build has it, was shown (it opens by itself about 1.5 s
 * after setup),</li>
 * <li>and the home screen is in front on two checks in a row, so the guide was closed.</li>
 * </ol>
 * Checks every few seconds; gives up for this boot after about ten minutes (e.g. the tester
 * went straight into a game) and asks again on the next start.
 */
public class WhenReadyJobService extends JobService {

    private static final int JOB_SETUP_DONE = 1;
    private static final int JOB_CHECK = 2;

    private static final long FIRST_CHECK_MS = 3000;
    private static final long CHECK_EVERY_MS = 3000;
    /** About two minutes: long enough for the guide to open, even on a slow first boot */
    private static final int MAX_GUIDE_CHECKS = 40;
    /** About ten minutes */
    private static final int MAX_CHECKS = 200;

    private static final String EXTRA_CHECKS = "checks";
    private static final String EXTRA_HOME_SEEN = "home_seen";

    private static final String GUIDE_PACKAGE = "org.andr36oid.guide";
    private static final String SETTINGS_PACKAGE = "com.android.settings";

    static void start(Context context) {
        if (isSetupDone(context)) {
            scheduleCheck(context, FIRST_CHECK_MS, 0, false);
        } else {
            waitForSetup(context);
        }
    }

    /** Called when the screen was opened, by itself or by hand. */
    static void cancel(Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(JOB_SETUP_DONE);
        scheduler.cancel(JOB_CHECK);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!BetaList.hasUnasked(this)) {
            return false;
        }
        if (!isSetupDone(this)) {
            // A content trigger job fires once, watch again
            waitForSetup(this);
            return false;
        }
        final PersistableBundle extras = params.getExtras();
        final int checks = extras.getInt(EXTRA_CHECKS, 0);
        final boolean homeWasSeen = extras.getBoolean(EXTRA_HOME_SEEN, false);

        final boolean guidePending = checks < MAX_GUIDE_CHECKS && isGuidePending();
        final boolean homeInFront = !guidePending && isHomeInFront();
        if (homeInFront && homeWasSeen) {
            show();
        } else if (checks < MAX_CHECKS) {
            scheduleCheck(this, CHECK_EVERY_MS, checks + 1, homeInFront);
        } else {
            Log.i(BetaList.TAG, "The home screen didn't come up, asking on the next start");
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    private void show() {
        try {
            // The system uid may start activities from the background
            startActivity(new Intent(this, BetaAppsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(BetaList.TAG, "Couldn't open the beta apps screen", e);
        }
    }

    static boolean isSetupDone(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
    }

    private static void waitForSetup(Context context) {
        final JobInfo job = new JobInfo.Builder(JOB_SETUP_DONE,
                new ComponentName(context, WhenReadyJobService.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), 0))
                .setTriggerContentUpdateDelay(1500)
                .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    private static void scheduleCheck(Context context, long delay, int checks,
            boolean homeSeen) {
        final PersistableBundle extras = new PersistableBundle();
        extras.putInt(EXTRA_CHECKS, checks);
        extras.putBoolean(EXTRA_HOME_SEEN, homeSeen);
        final JobInfo job = new JobInfo.Builder(JOB_CHECK,
                new ComponentName(context, WhenReadyJobService.class))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + 2000)
                .setExtras(extras)
                .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    /**
     * Whether the quick start guide is in this build and hasn't opened yet. It keeps a
     * "shown" flag in its preferences; both apps run as the system user, so the file is
     * readable. The guide sets the flag right before it opens.
     */
    private boolean isGuidePending() {
        try {
            getPackageManager().getPackageInfo(GUIDE_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        try {
            final File prefs = new File(createPackageContext(GUIDE_PACKAGE, 0).getDataDir(),
                    "shared_prefs/guide.xml");
            final String content = new String(Files.readAllBytes(prefs.toPath()),
                    StandardCharsets.UTF_8);
            return !content.contains("name=\"shown\" value=\"true\"");
        } catch (IOException | PackageManager.NameNotFoundException | RuntimeException e) {
            // Not written yet: the guide didn't open
            return true;
        }
    }

    /** Whether a home app (the launcher, Daijishou) is the app in front. */
    @SuppressWarnings("deprecation")
    private boolean isHomeInFront() {
        // The system uid sees every task here, not just its own
        final List<ActivityManager.RunningTaskInfo> tasks =
                getSystemService(ActivityManager.class).getRunningTasks(1);
        if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) {
            return false;
        }
        final String front = tasks.get(0).topActivity.getPackageName();
        if (SETTINGS_PACKAGE.equals(front)) {
            // Its FallbackHome is a home activity too, but only during boot
            return false;
        }
        final List<ResolveInfo> homes = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        for (ResolveInfo home : homes) {
            if (front.equals(home.activityInfo.packageName)) {
                return true;
            }
        }
        return false;
    }
}
