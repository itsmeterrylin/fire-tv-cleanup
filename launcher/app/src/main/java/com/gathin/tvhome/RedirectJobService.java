package com.gathin.tvhome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fire OS 6 does not deliver BOOT_COMPLETED to sideloaded apps, so the launcher cannot be restarted
 * by a boot receiver. A persisted JobScheduler job is rescheduled by the system itself after a boot,
 * which is how the Home redirect comes back on its own.
 */
public class RedirectJobService extends JobService {

    private static final String TAG = "HomeRedirect";
    private static final int JOB_ID = 4310;

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, RedirectJobService.class))
                .setPersisted(true)
                .setPeriodic(15 * 60_000L)
                .build();
        js.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "job fired");
        if (HomeRedirectService.isEnabled(this)) {
            startService(new Intent(this, HomeRedirectService.class));
        }
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
