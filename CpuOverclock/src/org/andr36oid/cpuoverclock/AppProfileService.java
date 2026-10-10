package org.andr36oid.cpuoverclock;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Service;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import org.andr36oid.cpuoverclock.AppProfileRules.Front;

import java.util.List;

/**
 * Switches to an app's own profile when it comes to the front and back to the chosen one
 * when it is left. Runs only while per-app profiles are on and some app has one. It does
 * no polling: the system calls it when the apps in front change, like Touch controls and
 * automatic game mode do.
 */
public class AppProfileService extends Service {

    private static final String TAG = "AppProfiles";
    /** Task changes come in bursts while an app starts, look once it has settled. */
    private static final long SETTLE_MS = 250;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCheck = this::check;
    private boolean mListening;

    private final TaskStackListener mListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            // On a binder thread
            mHandler.removeCallbacks(mCheck);
            mHandler.postDelayed(mCheck, SETTLE_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AppProfiles.isNeeded(this)) {
            stopListening();
            if (!Front.NONE.equals(AppProfiles.getFront(this))) {
                // Switched off: the chosen profile comes back at once
                AppProfiles.setFront(this, Front.NONE);
                new PerformanceProfiles(this).apply();
                AppProfiles.updateTile(this);
            }
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startListening();
        // Settings changed: look again even if the same app is in front
        mHandler.removeCallbacks(mCheck);
        mHandler.post(mCheck);
        // Brought back if the system has to kill it for memory during a game
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopListening();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startListening() {
        if (mListening) {
            return;
        }
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mListener);
            mListening = true;
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't watch the apps in front", e);
        }
    }

    private void stopListening() {
        mHandler.removeCallbacks(mCheck);
        if (!mListening) {
            return;
        }
        mListening = false;
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(mListener);
        } catch (RemoteException e) {
            // system_server is gone, nothing to unregister from
        }
    }

    private void check() {
        if (!mListening) {
            return;
        }
        final String pkg = foregroundPackage();
        final Front old = AppProfiles.getFront(this);
        show(AppProfileRules.onFront(old, pkg, AppProfiles.profileFor(this, pkg)));
    }

    /** Remembers what is in front and applies the profile if that changes it. */
    private void show(Front front) {
        final Front old = AppProfiles.getFront(this);
        if (front.equals(old)) {
            return;
        }
        final PerformanceProfiles profiles = new PerformanceProfiles(this);
        final int chosen = profiles.get();
        final boolean enabled = AppProfiles.isEnabled(this);
        final int before = AppProfileRules.effective(enabled, old, chosen);
        AppProfiles.setFront(this, front);
        if (AppProfileRules.effective(enabled, front, chosen) != before) {
            profiles.apply();
            AppProfiles.updateTile(this);
        }
    }

    /** The app in front. The system user sees every task, not just its own. */
    private String foregroundPackage() {
        final List<ActivityManager.RunningTaskInfo> tasks =
                getSystemService(ActivityManager.class).getRunningTasks(1);
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        final ComponentName top = tasks.get(0).topActivity;
        return top != null ? top.getPackageName() : null;
    }
}
