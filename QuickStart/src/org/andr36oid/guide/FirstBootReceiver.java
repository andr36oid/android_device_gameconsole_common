package org.andr36oid.guide;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * The tutorial's first start, at boot. The tutorial doesn't open from here: on the first start
 * the first-start launcher is the home app and opens it (see FirstStart). This only keeps the
 * safety net going: it waits for the wizard, starts the watchdog while the launcher is home,
 * and removes a launcher that was left over. Consoles set up before this build don't get the
 * tutorial by itself. It stays in Settings > Quick start guide (X).
 */
public class FirstBootReceiver extends BroadcastReceiver {

    private static final String PREFS = "guide";
    private static final String PREF_SHOWN = "shown";
    // Job IDs are per UID, and every android.uid.system app shares one (the other andr36oid apps, LineageParts), so each app keeps to its own range
    static final int JOB_SETUP_DONE = 3601;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (isSetupDone(context)) {
            FirstStart.onSetupDone(context);
        } else if (!wasShown(context)) {
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
     * Whether the tutorial is done: finished, skipped, or the guide was opened. The beta apps
     * chooser waits for this flag.
     */
    static boolean wasShown(Context context) {
        return prefs(context).getBoolean(PREF_SHOWN, false);
    }

    static void markShown(Context context) {
        if (!wasShown(context)) {
            prefs(context).edit().putBoolean(PREF_SHOWN, true).apply();
        }
        context.getSystemService(JobScheduler.class).cancel(JOB_SETUP_DONE);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
