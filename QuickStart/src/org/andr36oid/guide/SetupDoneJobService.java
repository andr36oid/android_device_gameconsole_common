package org.andr36oid.guide;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Runs when the setup-complete flag changes. The first-start launcher is home by then and opens
 * the tutorial by itself, so this only starts the watchdog (see FirstStart.onSetupDone). Keeps
 * waiting for the wizard if setup isn't done yet.
 */
public class SetupDoneJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!FirstBootReceiver.isSetupDone(this)) {
            // A content trigger job fires once, watch again
            FirstBootReceiver.waitForSetup(this);
            return false;
        }
        FirstStart.onSetupDone(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
