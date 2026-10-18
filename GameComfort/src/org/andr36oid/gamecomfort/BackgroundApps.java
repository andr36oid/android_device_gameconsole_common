package org.andr36oid.gamecomfort;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;
import android.widget.Toast;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;

import java.util.ArrayList;
import java.util.List;

/**
 * Closes the background apps when a game opens, if the user asked for it (Settings >
 * Performance). Same rules as Close background apps in the power menu (SystemUI
 * GlobalActionsDialog): everything but the apps on screen, home, recents and the system is
 * removed from recents and its process killed, unless it still runs a foreground service.
 */
final class BackgroundApps {

    private static final String TAG = "GameComfort";
    /** Written by Settings > Performance (CpuOverclock) */
    private static final String SETTING = "andr36oid_close_apps_on_game";
    /** A game that restarts itself or opens a second window must not clean twice. */
    private static final long MIN_GAP_MS = 10000;

    private static long sLastMs = -MIN_GAP_MS;

    private BackgroundApps() {
    }

    static boolean isOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SETTING, 0) != 0;
    }

    /** Closes the apps off the main thread, then shows the toast. At most once per gap. */
    static void closeForGame(Context context, String game) {
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastMs < MIN_GAP_MS) {
            return;
        }
        sLastMs = now;
        Continue.BACKGROUND.execute(() -> {
            final int closed = close(context, game);
            // nothing closed: no toast on every game start
            if (closed == 0) return;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                final Resources res = context.getResources();
                Toast.makeText(context,
                        res.getQuantityString(R.plurals.close_apps_done, closed, closed),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** Blocking binder calls, keep off the main thread. Returns the number of apps closed. */
    private static int close(Context context, String game) {
        final IActivityTaskManager atm = ActivityTaskManager.getService();
        final int userId = ActivityManager.getCurrentUser();
        final List<TaskInfo> tasks = new ArrayList<>();
        final ArraySet<Integer> onScreen = new ArraySet<>();
        try {
            for (ActivityManager.StackInfo stack : atm.getAllStackInfos()) {
                if (stack.visible && stack.taskIds != null && stack.taskIds.length > 0) {
                    onScreen.add(stack.taskIds[stack.taskIds.length - 1]);
                }
            }
            // Running tasks as well, for apps that keep themselves out of recents
            tasks.addAll(atm.getFilteredTasks(Integer.MAX_VALUE,
                    false /* filterOnlyVisibleRecents */));
            tasks.addAll(atm.getRecentTasks(Integer.MAX_VALUE,
                    ActivityManager.RECENT_IGNORE_UNAVAILABLE, userId).getList());
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get tasks", e);
            return 0;
        }

        final ArraySet<Integer> userIds = new ArraySet<>();
        for (int id : context.getSystemService(UserManager.class).getEnabledProfileIds(userId)) {
            userIds.add(id);
        }

        // Apps on screen (the game) are left alone entirely, and so are the system and us
        final ArraySet<String> keep = new ArraySet<>();
        keep.add("android");
        keep.add("com.android.systemui");
        keep.add(context.getPackageName());
        keep.add(game);
        for (TaskInfo task : tasks) {
            if (onScreen.contains(task.taskId)) {
                addPackage(keep, component(task));
                addPackage(keep, task.topActivity);
            }
        }

        final ArraySet<Integer> seen = new ArraySet<>();
        final ArraySet<String> closed = new ArraySet<>();
        for (TaskInfo task : tasks) {
            if (!seen.add(task.taskId) || !userIds.contains(task.userId) || isHomeOrRecents(task)) {
                continue;
            }
            final ComponentName component = component(task);
            if (component == null || keep.contains(component.getPackageName())) {
                continue;
            }
            try {
                if (atm.removeTask(task.taskId)) {
                    closed.add(component.getPackageName());
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to remove task " + task.taskId, e);
            }
        }
        return closed.size();
    }

    private static ComponentName component(TaskInfo task) {
        if (task.baseActivity != null) {
            return task.baseActivity;
        }
        if (task.realActivity != null) {
            return task.realActivity;
        }
        return task.baseIntent != null ? task.baseIntent.getComponent() : null;
    }

    private static boolean isHomeOrRecents(TaskInfo task) {
        final int type = task.configuration.windowConfiguration.getActivityType();
        return type == ACTIVITY_TYPE_HOME || type == ACTIVITY_TYPE_RECENTS;
    }

    private static void addPackage(ArraySet<String> packages, ComponentName component) {
        if (component != null) {
            packages.add(component.getPackageName());
        }
    }
}
