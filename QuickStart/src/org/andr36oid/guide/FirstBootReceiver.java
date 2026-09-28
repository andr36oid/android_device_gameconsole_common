package org.andr36oid.guide;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * Opens the tutorial on the first start after the setup wizard, until it was finished or
 * skipped. On the very first boot the wizard is still on screen when the boot completes, so it
 * waits for the wizard to finish. Consoles updated from a build without the tutorial see it once
 * and can skip it on the first page.
 */
public class FirstBootReceiver extends BroadcastReceiver {

    private static final String TAG = "QuickStartGuide";
    private static final String PREFS = "guide";
    private static final String PREF_SHOWN = "shown";
    static final int JOB_SETUP_DONE = 1;
    static final int JOB_SHOW = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || wasShown(context)) {
            return;
        }
        if (isSetupDone(context)) {
            scheduleShow(context);
        } else {
            waitForSetup(context);
        }
    }

    static boolean isSetupDone(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
    }

    /** Runs SetupDoneJobService as soon as the wizard marks the setup complete. */
    static void waitForSetup(Context context) {
        final JobInfo job = new JobInfo.Builder(JOB_SETUP_DONE,
                new ComponentName(context, SetupDoneJobService.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), 0))
                .setTriggerContentUpdateDelay(1500)
                .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    /**
     * Runs SetupDoneJobService right away. It waits for the home screen to settle (HomeWaiter)
     * before it opens anything, since the home app still starts up for a few seconds after
     * boot or after the wizard and would cover what opened first.
     */
    static void scheduleShow(Context context) {
        final JobInfo job = new JobInfo.Builder(JOB_SHOW,
                new ComponentName(context, SetupDoneJobService.class))
                .setOverrideDeadline(0)
                .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    /**
     * Opens the hands-on tutorial. It sets the "shown" flag itself when it's finished, skipped
     * or left, so it comes back on the next start if the console restarted in the middle. The
     * beta apps chooser waits for that flag. The guide itself no longer opens by itself: the
     * tutorial's last page offers it.
     */
    static void show(Context context) {
        if (TutorialActivity.isOpen()) {
            return;
        }
        try {
            context.startActivity(new Intent(context, TutorialActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't open the tutorial, opening the guide", e);
            markShown(context);
            try {
                context.startActivity(new Intent(context, GuideActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e2) {
                Log.w(TAG, "Couldn't open the guide", e2);
            }
        }
    }

    static boolean wasShown(Context context) {
        return prefs(context).getBoolean(PREF_SHOWN, false);
    }

    static void markShown(Context context) {
        if (!wasShown(context)) {
            prefs(context).edit().putBoolean(PREF_SHOWN, true).apply();
        }
        final JobScheduler jobs = context.getSystemService(JobScheduler.class);
        jobs.cancel(JOB_SETUP_DONE);
        jobs.cancel(JOB_SHOW);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
