package org.andr36oid.playtime;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.util.List;

/**
 * Counts the screen-on time while parental controls are on, with elapsedRealtime only (see
 * Parental). Runs in the foreground, its notification shows the time left. When the time
 * is up, TimeUpActivity covers the screen until a parent enters the PIN, and comes back
 * whenever something else is opened over it. Allowed apps (Parental.allowedApps) don't
 * count and stay usable when the time is up; blocked apps are sent back to the home screen.
 */
public class ParentalService extends Service {

    static final String ACTION_REFRESH = "org.andr36oid.playtime.PARENTAL_REFRESH";
    private static final String TAG = "PlayTimeParental";

    private static final String CHANNEL = "parental";
    private static final String CHANNEL_WARN = "parental_warning";
    private static final int NOTIFICATION_ID = 2;
    private static final int WARNING_ID = 3;

    /** how often the time is added up and saved while the screen is on */
    private static final long TICK_MS = 15 * 1000L;
    /** while the time is up: how often to check the time up page is still in front */
    private static final long GUARD_MS = 1000L;
    /** warnings before the time is up, in minutes, and their bits in Parental.warned */
    private static final int[] WARN_MINUTES = {10, 2};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** elapsedRealtime of the last count while the screen is on, -1 while it is off */
    private long mLastCount = -1;
    /** elapsedRealtime when the screen went off, -1 if it didn't in this run */
    private long mOffAt = -1;
    private long mShownMinute = -1;
    /** elapsedRealtime when the time ran out, -1 while there is time left */
    private long mUpSince = -1;
    /** elapsedRealtime the console shuts down at, for TimeUpActivity; 0: it won't */
    static volatile long sShutdownAt;
    private boolean mStarted;
    /** the app in front, and whether it is an allowed one (its time isn't counted) */
    private String mTop = "";
    private boolean mTopAllowed;

    private final TaskStackListener mTasks = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(ParentalService.this::topChanged);
        }
    };

    private final BroadcastReceiver mScreen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenOff();
            } else {
                screenOn();
            }
        }
    };

    private final Runnable mTick = this::tick;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Parental.isOn(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!mStarted) {
            mStarted = true;
            final NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.parental_title), NotificationManager.IMPORTANCE_LOW));
            // pops up over the game, like the break reminder
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_WARN,
                    getString(R.string.parental_warning_channel),
                    NotificationManager.IMPORTANCE_HIGH));
            startForeground(NOTIFICATION_ID, notification());
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(mScreen, filter);
            try {
                ActivityTaskManager.getService().registerTaskStackListener(mTasks);
            } catch (RemoteException e) {
                Log.w(TAG, "no task changes", e);
            }
            topChanged();
            if (getSystemService(PowerManager.class).isInteractive()) {
                screenOn();
            } else {
                mOffAt = SystemClock.elapsedRealtime();
            }
        } else {
            // settings, the app lists or the allowance changed
            topChanged();
            if (mLastCount >= 0) {
                mShownMinute = -1;
                tick();
            }
        }
        // started again if it is killed
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        if (mStarted) {
            count();
            unregisterReceiver(mScreen);
            try {
                ActivityTaskManager.getService().unregisterTaskStackListener(mTasks);
            } catch (RemoteException e) {
                // gone with the system
            }
        }
        super.onDestroy();
    }

    private void screenOn() {
        final long now = SystemClock.elapsedRealtime();
        if (mOffAt >= 0) {
            final long refill = Parental.refillHours(this) * 3600_000L;
            if (refill > 0 && now - mOffAt >= refill) {
                Parental.refill(this);
            }
            mOffAt = -1;
        }
        mLastCount = now;
        mShownMinute = -1;
        // the shutdown wait starts again: asleep, nobody could enter the PIN
        mUpSince = -1;
        tick();
    }

    private void screenOff() {
        Parental.setUnlocked(this, false);
        count();
        mLastCount = -1;
        mOffAt = SystemClock.elapsedRealtime();
        mHandler.removeCallbacks(mTick);
    }

    /** Adds the screen-on time since the last count, unless an allowed app was in front. */
    private void count() {
        if (mLastCount < 0) return;
        final long now = SystemClock.elapsedRealtime();
        if (!mTopAllowed) {
            Parental.setUsedMs(this, Parental.usedMs(this) + (now - mLastCount));
        }
        mLastCount = now;
    }

    /** Another app came to the front: send it home if blocked, count from here on. */
    private void topChanged() {
        String top = "";
        final List<ActivityManager.RunningTaskInfo> tasks =
                getSystemService(ActivityManager.class).getRunningTasks(1);
        if (!tasks.isEmpty() && tasks.get(0).topActivity != null) {
            top = tasks.get(0).topActivity.getPackageName();
        }
        if (Parental.blockedApps(this).contains(top)) {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, getString(R.string.parental_blocked, label(top)),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!Parental.keepsUnlock(top)) {
            Parental.setUnlocked(this, false);
        }
        final boolean allowed = Parental.allowedApps(this).contains(top);
        if (top.equals(mTop) && allowed == mTopAllowed) return;
        count();
        mTop = top;
        mTopAllowed = allowed;
        if (mLastCount >= 0) tick();
    }

    private CharSequence label(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Exception e) {
            return pkg;
        }
    }

    private void tick() {
        mHandler.removeCallbacks(mTick);
        if (mLastCount < 0) return;
        count();
        final long left = Parental.leftMs(this);
        warn(left);
        final long minute = (left + 59_999) / 60_000;
        if (minute != mShownMinute) {
            mShownMinute = minute;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
        if (left <= 0 && mTopAllowed) {
            // an allowed app may go on; the shutdown wait starts again when it is left
            mUpSince = -1;
            sShutdownAt = 0;
            mHandler.postDelayed(mTick, GUARD_MS);
        } else if (left <= 0) {
            final long now = SystemClock.elapsedRealtime();
            if (mUpSince < 0) mUpSince = now;
            final int wait = Parental.shutdownMinutes(this);
            sShutdownAt = wait > 0 ? mUpSince + wait * 60_000L : 0;
            if (sShutdownAt > 0 && now >= sShutdownAt) {
                // the used time is saved: after the restart the time is still up
                getSystemService(PowerManager.class).shutdown(false, "parental controls", false);
                return;
            }
            if (!TimeUpActivity.sShown) {
                startActivity(new Intent(this, TimeUpActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            }
            mHandler.postDelayed(mTick, GUARD_MS);
        } else {
            mUpSince = -1;
            sShutdownAt = 0;
            mHandler.postDelayed(mTick, Math.min(TICK_MS, left));
        }
    }

    private void warn(long left) {
        int warned = Parental.warned(this);
        // the shortest first, so a cut to under 2 minutes doesn't show the 10 minute one too
        for (int i = WARN_MINUTES.length - 1; i >= 0; i--) {
            final int bit = 1 << i;
            if (left > 0 && left <= WARN_MINUTES[i] * 60_000L && (warned & bit) == 0) {
                // the earlier, longer warnings count as shown too
                warned |= (bit << 1) - 1;
                Parental.setWarned(this, warned);
                final String text = getString(R.string.parental_warning,
                        Usage.format(this, Math.max(60_000L, left)));
                getSystemService(NotificationManager.class).notify(WARNING_ID,
                        new Notification.Builder(this, CHANNEL_WARN)
                                .setSmallIcon(R.drawable.ic_play_time)
                                .setContentTitle(text)
                                .setTimeoutAfter(30 * 1000L)
                                .setAutoCancel(true)
                                .setCategory(Notification.CATEGORY_REMINDER)
                                .build());
                break;
            }
        }
    }

    private Notification notification() {
        final long left = Parental.leftMs(this);
        final String text = left > 0
                ? getString(R.string.parental_left, Usage.format(this, Math.max(60_000L, left)))
                : getString(R.string.parental_up_title);
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, PlayTimeActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_play_time)
                .setContentTitle(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
