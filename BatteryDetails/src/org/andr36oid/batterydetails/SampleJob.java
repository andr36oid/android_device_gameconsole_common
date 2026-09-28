package org.andr36oid.batterydetails;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Adds a point to the graph every 15 minutes (the shortest period JobScheduler allows).
 * It never wakes the console, a sleeping console just leaves a gap the graph bridges.
 */
public class SampleJob extends JobService {

    private static final int JOB_ID = 1;
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    static void schedule(Context context) {
        final JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_ID) != null) {
            return;
        }
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, SampleJob.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // A few sysfs reads and one appended line, fine on the main thread.
        History.record(this, Gauge.read());
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
