package org.andr36oid.guide;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Waits until the home screen has settled: a home app's activity is on top and the top activity
 * hasn't changed for SETTLE_MS. Daijishou starts in steps (BootstrapActivity, then a splash,
 * then MainActivity, each one bringing its task to the front), so opening anything before that
 * gets it covered. While the top is still the activity HOME resolves to (Daijishou's
 * BootstrapActivity, up to about 6 s on a first start) it waits ENTRY_SETTLE_MS instead, since a
 * launcher's entry activity may be a trampoline. Gives up after TIMEOUT_MS and reports anyway,
 * so nothing waits forever.
 */
final class HomeWaiter {

    interface Callback {
        void onHomeSettled(boolean timedOut);
    }

    private static final String TAG = "QuickStartGuide";
    static final long SETTLE_MS = 3000;
    static final long ENTRY_SETTLE_MS = 8000;
    static final long TIMEOUT_MS = 60000;
    private static final long POLL_MS = 500;

    private final Context mContext;
    private final Callback mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mStart;
    private long mLastChange;
    private String mLastTop;
    private boolean mRunning;

    HomeWaiter(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;
    }

    void start() {
        cancel();
        mRunning = true;
        mStart = SystemClock.uptimeMillis();
        mLastChange = mStart;
        mLastTop = null;
        mHandler.post(mPoll);
    }

    void cancel() {
        mRunning = false;
        mHandler.removeCallbacks(mPoll);
    }

    boolean isRunning() {
        return mRunning;
    }

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) {
                return;
            }
            final long now = SystemClock.uptimeMillis();
            final ComponentName top = topActivity();
            final String topName = top != null ? top.flattenToShortString() : null;
            if (!Objects.equals(topName, mLastTop)) {
                mLastTop = topName;
                mLastChange = now;
            }
            final boolean home = top != null && homePackages().contains(top.getPackageName());
            final long settle = top != null && top.equals(homeEntry())
                    ? ENTRY_SETTLE_MS : SETTLE_MS;
            if (home && now - mLastChange >= settle) {
                finish(false);
            } else if (now - mStart >= TIMEOUT_MS) {
                Log.i(TAG, "Home screen didn't settle, top is " + topName);
                finish(true);
            } else {
                mHandler.postDelayed(this, POLL_MS);
            }
        }
    };

    private void finish(boolean timedOut) {
        mRunning = false;
        mCallback.onHomeSettled(timedOut);
    }

    /** The top activity of the front task. The system uid sees every task. */
    private ComponentName topActivity() {
        try {
            final List<ActivityManager.RunningTaskInfo> tasks =
                    mContext.getSystemService(ActivityManager.class).getRunningTasks(1);
            return tasks.isEmpty() ? null : tasks.get(0).topActivity;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The activity HOME resolves to, or null if there's no default. */
    private ComponentName homeEntry() {
        final ResolveInfo info = mContext.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return info == null || info.activityInfo == null ? null
                : new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
    }

    /** Packages with a home activity, without Settings' FallbackHome and the setup wizard. */
    private Set<String> homePackages() {
        final Set<String> packages = new HashSet<>();
        final List<ResolveInfo> homes = mContext.getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo info : homes) {
            final String name = info.activityInfo.name;
            final String pkg = info.activityInfo.packageName;
            if (name.endsWith(".FallbackHome") || pkg.equals("org.lineageos.setupwizard")) {
                continue;
            }
            packages.add(pkg);
        }
        return packages;
    }
}
