package org.andr36oid.storagecare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.storage.VolumeInfo;
import android.provider.Settings;
import android.text.format.Formatter;

/**
 * The background check: free space of the internal storage and of EASYROMS, and the
 * card's health. Runs every 6 hours, shortly after a start, and when a card is mounted or
 * can't be mounted. Tells the user with one notification per problem, which opens
 * Free up space.
 */
final class Checks {

    // Job IDs are per UID and all android.uid.system apps share one; this app keeps to 3741-3749
    static final int JOB_PERIODIC = 3741;
    static final int JOB_SOON = 3742;
    private static final long PERIOD_MS = 6 * 60 * 60 * 1000L;

    private static final String CHANNEL = "storage_warnings";
    private static final int ID_INTERNAL = 1;
    private static final int ID_EASYROMS = 2;
    private static final int ID_HEALTH = 3;

    private Checks() {
    }

    static void schedule(Context c) {
        final JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(new JobInfo.Builder(JOB_PERIODIC, component(c))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build());
        }
        soon(c);
    }

    /** A check in about a minute (after a start, or a card coming or going). */
    static void soon(Context c) {
        c.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(JOB_SOON, component(c))
                        .setMinimumLatency(60 * 1000L)
                        .setOverrideDeadline(5 * 60 * 1000L)
                        .build());
    }

    private static ComponentName component(Context c) {
        return new ComponentName(c, CheckJobService.class);
    }

    /** Runs the check. Blocks for a few seconds (the helper), so not on the main thread. */
    static void run(Context c) {
        final NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!Prefs.warn(c)) {
            nm.cancelAll();
            return;
        }
        space(c, nm, Space.INTERNAL, Volumes.internal(), ID_INTERNAL);
        final VolumeInfo v = Volumes.easyroms(c);
        final long[] er = Volumes.easyroms(v);
        if (er != null) {
            space(c, nm, Space.EASYROMS, er, ID_EASYROMS);
        } else {
            nm.cancel(ID_EASYROMS);
        }

        // Card health needs root (the lower mount, the kernel log): ask the helper
        if (v != null && (v.getState() == VolumeInfo.STATE_MOUNTED
                || v.getState() == VolumeInfo.STATE_MOUNTED_READ_ONLY
                || v.getState() == VolumeInfo.STATE_UNMOUNTABLE)) {
            if (Helper.runAndWait(Helper.HEALTH_REQUEST, 20000)) {
                health(c, nm, Health.problem(Helper.health()));
            }
        } else {
            nm.cancel(ID_HEALTH);
        }
    }

    private static void space(Context c, NotificationManager nm, String volume, long[] free,
            int id) {
        final int next = Space.next(volume, free[0], Prefs.warned(c, volume));
        Prefs.setWarned(c, volume, Space.warned(next));
        if (Space.warned(next) == Space.OK) {
            nm.cancel(id);
            return;
        }
        if (!Space.show(next)) return;
        final boolean full = Space.warned(next) == Space.FULL;
        final boolean internal = Space.INTERNAL.equals(volume);
        final String left = Formatter.formatShortFileSize(c, free[0]);
        final int title = internal
                ? (full ? R.string.warn_internal_full : R.string.warn_internal_low)
                : (full ? R.string.warn_easyroms_full : R.string.warn_easyroms_low);
        final int text = internal
                ? (full ? R.string.warn_internal_full_text : R.string.warn_internal_low_text)
                : (full ? R.string.warn_easyroms_full_text : R.string.warn_easyroms_low_text);
        notify(c, nm, id, c.getString(title), c.getString(text, left),
                NotificationManager.IMPORTANCE_DEFAULT);
    }

    private static void health(Context c, NotificationManager nm, String problem) {
        if (problem == null) {
            nm.cancel(ID_HEALTH);
            return;
        }
        final int boot = Settings.Global.getInt(c.getContentResolver(),
                Settings.Global.BOOT_COUNT, 0);
        if (Prefs.healthTold(c, problem) == boot) return;
        Prefs.setHealthTold(c, problem, boot);
        notify(c, nm, ID_HEALTH, Texts.healthTitle(c, problem), Texts.healthText(c, problem),
                NotificationManager.IMPORTANCE_DEFAULT);
    }

    private static void notify(Context c, NotificationManager nm, int id, String title,
            String text, int importance) {
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                c.getString(R.string.channel_warnings), importance));
        final PendingIntent open = PendingIntent.getActivity(c, id,
                new Intent(c, FreeSpaceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(id, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_free_space)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }

    /** After files were removed: clears warnings that no longer hold. */
    static void recheckSpace(Context c) {
        final NotificationManager nm = c.getSystemService(NotificationManager.class);
        space(c, nm, Space.INTERNAL, Volumes.internal(), ID_INTERNAL);
        final long[] er = Volumes.easyroms(Volumes.easyroms(c));
        if (er != null) space(c, nm, Space.EASYROMS, er, ID_EASYROMS);
    }
}
