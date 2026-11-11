package org.andr36oid.subsystemsetup;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Deferred mount preparation; root work stays in the existing broker service. */
public final class PrestageJob extends JobService {
    private static final int ID=0x3636;
    static void schedule(Context context) {
        JobScheduler jobs=context.getSystemService(JobScheduler.class);
        if(jobs==null)return;
        if(!"1".equals(SubsystemBackend.get(SubsystemBackend.PRESTAGE))) {jobs.cancel(ID);return;}
        jobs.schedule(new JobInfo.Builder(ID,new ComponentName(context,PrestageJob.class))
                .setRequiresDeviceIdle(true).setOverrideDeadline(90000).build());
    }
    @Override public boolean onStartJob(JobParameters p) {
        if("1".equals(SubsystemBackend.get(SubsystemBackend.PRESTAGE)) &&
           "1".equals(SubsystemBackend.get("sys.boot_completed")) &&
           !"1".equals(SubsystemBackend.get("sys.andr36oid.subsystem.prestaged"))) SubsystemBackend.requestPrestage(true);
        return false;
    }
    @Override public boolean onStopJob(JobParameters p) {return false;}
}
