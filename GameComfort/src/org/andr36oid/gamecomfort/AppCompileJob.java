package org.andr36oid.gamecomfort;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.IPackageManager;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Compiles the code apps were actually seen using, so they start faster.
 *
 * Android has a job for this, but it waits for "charging and idle with the screen off for
 * a long time". A handheld goes to sleep before that, so the job never runs and every app
 * installed at first boot stays interpreted. This asks for the same work while the console
 * sits on the charger doing nothing. Measured on an R36 Ultra: Settings 1.66 to 1.38 s,
 * VLC 2.35 to about 1.2 s, 81 apps in three minutes, 48 MB of storage.
 *
 * Kernel Tweaks "Speed up apps while charging" (persist.andr36oid.app_compile_off=1) turns it off.
 */
public final class AppCompileJob extends JobService {

    private static final String TAG = "AppCompileJob";
    private static final int JOB_ID = 3601;
    private static final long CHECK_EVERY_MS = 60 * 60 * 1000L;
    private static final long REPEAT_AFTER_MS = 20 * 60 * 60 * 1000L;
    // The compiler keeps all cores busy. A game or a video keeps the load above this.
    private static final float BUSY_LOAD = 0.6f;
    private static final String PREFS = "app_compile";
    private static final String LAST = "last";

    private volatile Thread mWorker;

    static boolean enabled() {
        return !"1".equals(SystemProperties.get("persist.andr36oid.app_compile_off", "0"));
    }

    static void schedule(Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!enabled()) {
            scheduler.cancel(JOB_ID);
            return;
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID,
                new ComponentName(context, AppCompileJob.class))
                .setRequiresCharging(true)
                .setPeriodic(CHECK_EVERY_MS)
                .build());
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        final long last = prefs.getLong(LAST, 0);
        // A clock set backwards must not postpone the next run forever.
        if (!enabled() || (last <= now && now - last < REPEAT_AFTER_MS) || busy()) return false;
        mWorker = new Thread(() -> {
            boolean done = false;
            try {
                final IPackageManager pm = IPackageManager.Stub.asInterface(
                        ServiceManager.getService("package"));
                done = pm != null && pm.runBackgroundDexoptJob(null);
            } catch (Exception e) {
                Log.w(TAG, "compile failed", e);
            }
            if (done) prefs.edit().putLong(LAST, System.currentTimeMillis()).apply();
            Log.i(TAG, "compile " + (done ? "finished" : "did not finish"));
            jobFinished(params, false);
        }, TAG);
        mWorker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Unplugged mid-run: Android's own pass stops by itself on low battery or heat,
        // and an unfinished pass leaves no stamp, so the next idle hour tries again.
        return false;
    }

    private static boolean busy() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/loadavg"))) {
            final String line = reader.readLine();
            return line == null || Float.parseFloat(line.split(" ")[0]) >= BUSY_LOAD;
        } catch (IOException | NumberFormatException e) {
            return true;
        }
    }
}
