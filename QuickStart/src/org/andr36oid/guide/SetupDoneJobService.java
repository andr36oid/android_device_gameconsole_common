package org.andr36oid.guide;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.SparseArray;

/**
 * Runs when the setup-complete flag changes, or right after boot: waits for the home screen to
 * settle, then opens the tutorial. Keeps waiting for the wizard if setup isn't done yet.
 */
public class SetupDoneJobService extends JobService {

    // One per running job: the setup trigger and the boot job may overlap
    private final SparseArray<HomeWaiter> mWaiters = new SparseArray<>();

    @Override
    public boolean onStartJob(JobParameters params) {
        if (FirstBootReceiver.wasShown(this)) {
            return false;
        }
        if (!FirstBootReceiver.isSetupDone(this)) {
            // A content trigger job fires once, watch again
            FirstBootReceiver.waitForSetup(this);
            return false;
        }
        final int id = params.getJobId();
        final HomeWaiter old = mWaiters.get(id);
        if (old != null) {
            old.cancel();
        }
        final HomeWaiter waiter = new HomeWaiter(this, timedOut -> {
            mWaiters.remove(id);
            if (!FirstBootReceiver.wasShown(this)) {
                FirstBootReceiver.show(this);
            }
            jobFinished(params, false);
        });
        mWaiters.put(id, waiter);
        waiter.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        final HomeWaiter waiter = mWaiters.get(params.getJobId());
        if (waiter != null) {
            waiter.cancel();
            mWaiters.remove(params.getJobId());
        }
        // Stopped by the system before home settled: try again
        return true;
    }
}
