package org.andr36oid.savebackup;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/**
 * The two automatic jobs:
 * <ul>
 * <li>daily: while charging, once a day (24 hours after the last backup or check). If the
 *     console isn't charging then, it runs as soon as it's plugged in.</li>
 * <li>session: every 30 minutes, looks whether an emulator was closed since the last look
 *     and backs up if so. Looking costs nothing but a query of the app usage events.</li>
 * </ul>
 * Both skip the backup when nothing changed (the engine checks sizes and times).
 */
final class Schedule {

    static final int JOB_DAILY = 1;
    static final int JOB_SESSION = 2;
    static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final long SESSION_PERIOD_MS = 30 * 60 * 1000L;
    /** When a daily run failed or couldn't start, try again after this. */
    static final long RETRY_MS = 30 * 60 * 1000L;

    private Schedule() {
    }

    /** Schedules or cancels the jobs to match the switch. */
    static void update(Context c) {
        final JobScheduler js = c.getSystemService(JobScheduler.class);
        if (!Prefs.auto(c)) {
            js.cancel(JOB_DAILY);
            js.cancel(JOB_SESSION);
            return;
        }
        daily(c, 0);
        if (js.getPendingJob(JOB_SESSION) == null) {
            js.schedule(new JobInfo.Builder(JOB_SESSION, component(c))
                    .setPeriodic(SESSION_PERIOD_MS)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build());
        }
    }

    /**
     * The next daily run: 24 hours after the last check, whenever charging, but not sooner
     * than {@code atLeast} (a retry after a failed or busy run).
     */
    static void daily(Context c, long atLeast) {
        if (!Prefs.auto(c)) return;
        final long last = Helper.last().checked;
        long wait = last > 0
                ? Math.max(0, Math.min(DAY_MS, last + DAY_MS - System.currentTimeMillis())) : 0;
        wait = Math.max(wait, atLeast);
        c.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(JOB_DAILY, component(c))
                        .setRequiresCharging(true)
                        .setMinimumLatency(wait)
                        .setPersisted(true)
                        .build());
    }

    private static ComponentName component(Context c) {
        return new ComponentName(c, BackupJobService.class);
    }
}
