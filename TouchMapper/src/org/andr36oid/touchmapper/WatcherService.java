package org.andr36oid.touchmapper;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Service;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.util.List;

/**
 * Switches touch controls with the app in front: names the app to the joyMouse daemon while it
 * has an enabled profile, and shows the hints if the profile wants them. Also tells the daemon
 * the display size and rotation. Runs only while some profile is switched on.
 */
public class WatcherService extends Service {

    private static final String TAG = "TouchControls";
    private static final String ACTION_REFRESH = "org.andr36oid.touchmapper.action.REFRESH";
    private static final String ACTION_EDIT_WHEN_OPEN =
            "org.andr36oid.touchmapper.action.EDIT_WHEN_OPEN";
    /** Task changes come in bursts while an app starts, look once it has settled. */
    private static final long SETTLE_MS = 250;
    /** Opened from Settings: wait for the game to show something before the editor comes up. */
    private static final long EDIT_DELAY_MS = 2500;
    private static final long EDIT_TIMEOUT_MS = 60_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCheck = this::check;
    private HintOverlay mHints;
    private DisplayManager mDisplayManager;
    private boolean mListening;
    private String mActive;
    private String mPendingEdit;
    private long mPendingEditUntil;

    private final TaskStackListener mListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            // On a binder thread
            mHandler.removeCallbacks(mCheck);
            mHandler.postDelayed(mCheck, SETTLE_MS);
        }
    };

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        publishDisplay();
                        // The hints follow the new layout on their own; the markers are
                        // stored as shares of the screen.
                    }
                }
            };

    /** Starts the watcher if any profile is on, or lets it stop. */
    static void refresh(Context context) {
        context.startService(new Intent(context, WatcherService.class).setAction(ACTION_REFRESH));
    }

    /** Opens the editor as soon as the app is in front (from Settings). */
    static void editWhenOpen(Context context, String packageName) {
        context.startService(new Intent(context, WatcherService.class)
                .setAction(ACTION_EDIT_WHEN_OPEN)
                .putExtra(EditorActivity.EXTRA_PACKAGE, packageName));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHints = new HintOverlay(this);
        mDisplayManager = getSystemService(DisplayManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_EDIT_WHEN_OPEN.equals(intent.getAction())) {
            mPendingEdit = intent.getStringExtra(EditorActivity.EXTRA_PACKAGE);
            mPendingEditUntil = SystemClock.uptimeMillis() + EDIT_TIMEOUT_MS;
        }
        if (mPendingEdit == null && !Profiles.anyEnabled()) {
            stopListening();
            // Only if no newer start came in meanwhile, e.g. "edit when open" right after a save
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startListening();
        // Settings changed: look again even if the same app is in front.
        mActive = null;
        mHandler.removeCallbacks(mCheck);
        mHandler.post(mCheck);
        // Brought back if the system has to kill it for memory during a game.
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
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't watch the apps in front", e);
            return;
        }
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        mListening = true;
        publishDisplay();
    }

    private void stopListening() {
        mHandler.removeCallbacksAndMessages(null);
        mHints.hide();
        Profiles.setActive(null);
        mActive = null;
        if (!mListening) {
            return;
        }
        mListening = false;
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(mListener);
        } catch (RemoteException e) {
            // system_server is gone, nothing to unregister from
        }
    }

    private void publishDisplay() {
        final Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return;
        }
        final Point size = new Point();
        display.getRealSize(size);
        final int rotation = display.getRotation();
        // The virtual touchscreen works in the display's natural orientation.
        final boolean sideways = rotation == 1 || rotation == 3;
        final int w = sideways ? size.y : size.x;
        final int h = sideways ? size.x : size.y;
        Profiles.setProperty(Profiles.PROP_DISPLAY, w + "x" + h + "@" + rotation);
    }

    private void check() {
        if (!mListening) {
            return;
        }
        final String pkg = foregroundPackage();
        if (pkg == null) {
            return;
        }
        if (mPendingEdit != null) {
            if (SystemClock.uptimeMillis() > mPendingEditUntil) {
                mPendingEdit = null;
            } else if (mPendingEdit.equals(pkg)) {
                mPendingEdit = null;
                mHandler.postDelayed(() -> openEditor(pkg), EDIT_DELAY_MS);
            }
        }
        if (pkg.equals(mActive)) {
            return;
        }
        mActive = pkg;
        // The editor and Settings of this app never get touch controls; while the editor is
        // open, the pad belongs to it.
        final Profile profile = getPackageName().equals(pkg) ? null : Profiles.load(pkg);
        if (profile == null || !profile.enabled || profile.controls.isEmpty()) {
            Profiles.setActive(null);
            mHints.hide();
            return;
        }
        Profiles.setActive(pkg);
        if (profile.hints) {
            mHints.show(profile);
        } else {
            mHints.hide();
        }
    }

    private void openEditor(String pkg) {
        if (pkg.equals(foregroundPackage())) {
            startActivity(Profiles.editIntent(this, pkg));
        }
    }

    private String foregroundPackage() {
        return foregroundPackage(this);
    }

    /** The app in front. The system user sees every task, not just its own. */
    static String foregroundPackage(Context context) {
        final List<ActivityManager.RunningTaskInfo> tasks =
                context.getSystemService(ActivityManager.class).getRunningTasks(1);
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        final ComponentName top = tasks.get(0).topActivity;
        return top != null ? top.getPackageName() : null;
    }
}
