package org.andr36oid.gamecomfort;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/**
 * Automatic game mode: on while a game is in front, off again when something else comes to
 * the front. Only acts when the app in front changes, so switching game mode by hand in the
 * middle of a game sticks until the next app switch.
 */
final class GameWatcher {

    private static final String TAG = "GameComfort";
    /** Task changes come in bursts while an app starts, look once it has settled. */
    private static final long SETTLE_MS = 300;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Games mGames;
    private String mForeground;
    private boolean mRunning;

    private final Runnable mCheck = this::check;

    private final TaskStackListener mListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            // On a binder thread
            mHandler.removeCallbacks(mCheck);
            mHandler.postDelayed(mCheck, SETTLE_MS);
        }
    };

    GameWatcher(Context context) {
        mContext = context;
        mGames = new Games(context);
    }

    void start() {
        if (mRunning) {
            return;
        }
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mListener);
            mRunning = true;
            mForeground = null;
            mHandler.post(mCheck);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't watch the apps in front", e);
        }
    }

    void stop() {
        if (!mRunning) {
            return;
        }
        mRunning = false;
        mHandler.removeCallbacks(mCheck);
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(mListener);
        } catch (RemoteException e) {
            // system_server is gone, nothing to unregister from
        }
    }

    private void check() {
        if (!mRunning) {
            return;
        }
        final String pkg = foregroundPackage();
        if (pkg == null || pkg.equals(mForeground)) {
            return;
        }
        mForeground = pkg;
        if (mGames.isGame(pkg)) {
            if (!GameMode.isActive(mContext)) {
                GameMode.setActive(mContext, true, true);
            }
        } else if (GameMode.isOnByAutomatic(mContext)) {
            GameMode.setActive(mContext, false, true);
        }
    }

    private String foregroundPackage() {
        // The system user sees every task, not just its own.
        final List<ActivityManager.RunningTaskInfo> tasks =
                mContext.getSystemService(ActivityManager.class).getRunningTasks(1);
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        final ComponentName top = tasks.get(0).topActivity;
        return top != null ? top.getPackageName() : null;
    }
}
