package org.andr36oid.storagecare;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs the space and card check (Checks) off the main thread. */
public class CheckJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                Checks.run(this);
            } finally {
                jobFinished(params, false);
            }
        }, "StorageCheck").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
