package org.andr36oid.guide;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs when the setup-complete flag changes: opens the guide, or keeps waiting. */
public class SetupDoneJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!FirstBootReceiver.wasShown(this)) {
            if (FirstBootReceiver.isSetupDone(this)) {
                FirstBootReceiver.show(this);
            } else {
                // A content trigger job fires once, watch again
                FirstBootReceiver.waitForSetup(this);
            }
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
