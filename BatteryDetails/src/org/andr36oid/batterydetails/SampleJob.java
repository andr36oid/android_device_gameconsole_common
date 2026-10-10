package org.andr36oid.batterydetails;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Adds a point to the graph about every 15 minutes while the console is awake. It never
 * wakes the console: a sleeping console just leaves a gap the graph bridges. That is why
 * this is a one-shot job with only a minimum delay, scheduled again after each run. A
 * periodic job gets a deadline, and JobScheduler wakes the console from sleep for every
 * deadline (ELAPSED_REALTIME_WAKEUP in TimeController).
 */
public class SampleJob extends JobService {

    // Job IDs are per UID, and every android.uid.system app shares one (the other andr36oid apps, LineageParts), so each app keeps to its own range
    private static final int JOB_ID = 3631;
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    /** Makes sure a sample is pending; replaces the old periodic job after an update. */
    static void schedule(Context context) {
        final JobInfo pending = context.getSystemService(JobScheduler.class)
                .getPendingJob(JOB_ID);
        if (pending == null || pending.isPeriodic()) {
            scheduleNext(context);
        }
    }

    private static void scheduleNext(Context context) {
        context.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(JOB_ID, new ComponentName(context, SampleJob.class))
                        .setMinimumLatency(PERIOD_MS)
                        .setPersisted(true)
                        .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // A few sysfs reads and one appended line, fine on the main thread.
        History.record(this, Gauge.read());
        // Scheduling the same ID replaces this job, so do it once this run has returned.
        final Context app = getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> scheduleNext(app));
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
