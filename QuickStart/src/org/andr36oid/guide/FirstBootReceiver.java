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
 * waits for the wizard to finish. The wizard normally opens the tutorial itself as its last
 * step; this is the fallback for a wizard without that step, and for consoles updated from a
 * build without the tutorial (they see it once and can skip it on the first page).
 */
public class FirstBootReceiver extends BroadcastReceiver {

    private static final String TAG = "QuickStartGuide";
    private static final String PREFS = "guide";
    private static final String PREF_SHOWN = "shown";
    static final int JOB_SETUP_DONE = 1;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || wasShown(context)) {
            return;
        }
        if (isSetupDone(context)) {
            show(context);
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
                // Let the wizard's last page close and the home screen come up first
                .setTriggerContentUpdateDelay(1500)
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
        context.getSystemService(JobScheduler.class).cancel(JOB_SETUP_DONE);
        if (TutorialActivity.isOpen()) {
            // The setup wizard already opened it as its last step
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
        context.getSystemService(JobScheduler.class).cancel(JOB_SETUP_DONE);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
