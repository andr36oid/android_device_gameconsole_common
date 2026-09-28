package org.andr36oid.savebackup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Runs the automatic backups (see Schedule). While a backup runs, a quiet notification
 * says so; it only shows when the run takes longer than a moment, so a check that finds
 * nothing changed stays invisible.
 */
public class BackupJobService extends JobService {

    private static final String CHANNEL = "backup";
    private static final int NOTIFICATION_ID = 1;
    private static final long NOTIFY_AFTER_MS = 2000;
    /** Don't back up after a game again within this time of the last check. */
    private static final long SESSION_MIN_GAP_MS = 10 * 60 * 1000L;
    /** A daily run counts as due a bit early, since job timing drifts. */
    private static final long DAILY_DUE_MS = Schedule.DAY_MS - 4 * 60 * 60 * 1000L;

    /** Emulators we ship or that are common; any app marked as a game counts too. */
    private static final Set<String> EMULATORS = new HashSet<>(Arrays.asList(
            "com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32",
            "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"));

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Helper.Runner mRunner;
    private JobParameters mParams;
    private String mLastProgress = "running start";
    private boolean mNotified;
    private final Runnable mNotify = () -> notifyProgress(mLastProgress);

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!Prefs.auto(this)) return false;
        final String reason = params.getJobId() == Schedule.JOB_DAILY ? dailyReason()
                : sessionReason();
        if (reason == null || Helper.running()) {
            if (params.getJobId() == Schedule.JOB_DAILY) {
                Schedule.daily(this, reason == null ? 0 : Schedule.RETRY_MS);
            }
            return false;
        }
        mParams = params;
        mRunner = new Helper.Runner(mHandler, new Helper.Runner.Listener() {
            @Override
            public void onProgress(String status) {
                mLastProgress = status;
                if (mNotified) notifyProgress(status);
            }

            @Override
            public void onDone(String status) {
                finish(status);
            }
        });
        if (!mRunner.start(Helper.backupRequest(this, reason, false))) {
            mParams = null;
            if (params.getJobId() == Schedule.JOB_DAILY) Schedule.daily(this, Schedule.RETRY_MS);
            return false;
        }
        mHandler.postDelayed(mNotify, NOTIFY_AFTER_MS);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // the helper goes on by itself; only stop watching it
        if (mRunner != null) mRunner.stop();
        clearNotification();
        mParams = null;
        return false;
    }

    private void finish(String status) {
        clearNotification();
        SummaryProvider.changed(this);
        final JobParameters p = mParams;
        mParams = null;
        if (p == null) return;
        if (p.getJobId() == Schedule.JOB_DAILY) {
            Schedule.daily(this, status.startsWith("done") ? 0 : Schedule.RETRY_MS);
        }
        jobFinished(p, false);
    }

    private String dailyReason() {
        final long checked = Helper.last().checked;
        return System.currentTimeMillis() - checked >= DAILY_DUE_MS ? "daily" : null;
    }

    /**
     * "session" when an emulator went to the background since the last look and something
     * else came to the front after it (the game was closed, not just the screen turned off).
     */
    private String sessionReason() {
        final long now = System.currentTimeMillis();
        final long seen = Prefs.get(this).getLong(Prefs.SESSION_SEEN, now - 2 * 60 * 60 * 1000L);
        Prefs.get(this).edit().putLong(Prefs.SESSION_SEEN, now).apply();
        if (now - Helper.last().checked < SESSION_MIN_GAP_MS) return null;

        final UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        final UsageEvents events = usm.queryEvents(Math.max(seen, now - Schedule.DAY_MS), now);
        if (events == null) return null;
        final Map<String, Boolean> isGame = new HashMap<>();
        long emuLeft = 0, emuFront = 0, otherFront = 0;
        final UsageEvents.Event e = new UsageEvents.Event();
        while (events.getNextEvent(e)) {
            final int type = e.getEventType();
            final boolean resumed = type == UsageEvents.Event.ACTIVITY_RESUMED;
            final boolean left = type == UsageEvents.Event.ACTIVITY_PAUSED
                    || type == UsageEvents.Event.ACTIVITY_STOPPED;
            if (!resumed && !left) continue;
            final String pkg = e.getPackageName();
            Boolean game = isGame.get(pkg);
            if (game == null) {
                game = isGame(pkg);
                isGame.put(pkg, game);
            }
            if (game) {
                if (resumed) emuFront = e.getTimeStamp();
                else emuLeft = e.getTimeStamp();
            } else if (resumed) {
                otherFront = e.getTimeStamp();
            }
        }
        return emuLeft > emuFront && otherFront >= emuLeft ? "session" : null;
    }

    private boolean isGame(String pkg) {
        if (EMULATORS.contains(pkg)) return true;
        try {
            final ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return ai.category == ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void notifyProgress(String status) {
        mNotified = true;
        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        final Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_save_backup)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(Texts.progress(this, status))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        final String[] f = status.split(" ");
        if (status.startsWith("running backup") && f.length >= 4) {
            b.setProgress((int) Index.num(f[3]), (int) Index.num(f[2]), false);
        } else {
            b.setProgress(0, 0, true);
        }
        nm.notify(NOTIFICATION_ID, b.build());
    }

    private void clearNotification() {
        mHandler.removeCallbacks(mNotify);
        if (mNotified) getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        mNotified = false;
    }
}
