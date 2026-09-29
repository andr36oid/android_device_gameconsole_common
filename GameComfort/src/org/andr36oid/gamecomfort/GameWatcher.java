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
 * Watches the app in front. Continue playing: remembers the last game, and looks up which
 * game it ran when it leaves the front (the emulator has written its files by then) and a
 * little while after it came to the front. Automatic game mode: on while a game is in front,
 * off again when something else comes to the front. Only acts when the app in front changes,
 * so switching game mode by hand in the middle of a game sticks until the next app switch.
 */
final class GameWatcher {

    private static final String TAG = "GameComfort";
    /** Task changes come in bursts while an app starts, look once it has settled. */
    private static final long SETTLE_MS = 300;
    /** RetroArch writes its history once the game has loaded; big games take a while */
    private static final long LOOK_UP_AFTER_START_MS = 20000;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Games mGames;
    private String mForeground;
    private boolean mRunning;

    private final Runnable mCheck = this::check;
    private final Runnable mLookUp = this::lookUp;
    private boolean mGameInFront;

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
        mHandler.removeCallbacks(mLookUp);
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
        // Our own screens (Continue, Game mode) come and go on top of the game; they don't
        // count as leaving it
        if (pkg == null || pkg.equals(mForeground) || pkg.equals(mContext.getPackageName())) {
            return;
        }
        mForeground = pkg;
        final boolean game = mGames.isGame(pkg);
        Continue.onForeground(mContext, pkg, game);
        mHandler.removeCallbacks(mLookUp);
        if (game) {
            mHandler.postDelayed(mLookUp, LOOK_UP_AFTER_START_MS);
        } else if (mGameInFront) {
            mHandler.post(mLookUp);
        }
        mGameInFront = game;

        if (!GameMode.isAutomatic(mContext)) {
            return;
        }
        if (game) {
            if (!GameMode.isActive(mContext)) {
                GameMode.setActive(mContext, true, true);
            }
        } else if (GameMode.isOnByAutomatic(mContext)) {
            GameMode.setActive(mContext, false, true);
        }
    }

    private void lookUp() {
        Continue.refreshSoon(mContext);
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
