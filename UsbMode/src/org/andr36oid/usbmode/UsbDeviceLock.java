package org.andr36oid.usbmode;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.preference.PreferenceManager;

import java.util.HashSet;
import java.util.Set;

/** Full task locking, with a journal for process death while the display is dark. */
final class UsbDeviceLock {
    static boolean lock(Activity activity) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        final Set<String> previous = new HashSet<>();
        final DevicePolicyManager policy = activity.getSystemService(DevicePolicyManager.class);
        for (PackageInfo info : activity.getPackageManager().getInstalledPackages(0)) {
            if (policy.isLockTaskPermitted(info.packageName)) previous.add(info.packageName);
        }
        if (!prefs.edit().putStringSet("usb_lock_packages", previous)
                .putBoolean("usb_lock_restore", true).commit()) return false;
        final Set<String> permitted = new HashSet<>(previous);
        permitted.add(activity.getPackageName());
        try {
            ActivityTaskManager.getService().updateLockTaskPackages(UserHandle.myUserId(),
                    permitted.toArray(new String[0]));
            activity.startLockTask();
            return true;
        } catch (RemoteException | SecurityException e) { }
        restore(activity);
        return false;
    }
    static boolean locked(Context context) {
        // Real lock task (allowlisted) or the screen-pinning fallback both hold this session in
        // front with touch locked, so either is safe to dim behind. Requiring strictly LOCKED
        // left the screen-off handshake waiting forever whenever the task came up PINNED.
        final int mode = context.getSystemService(ActivityManager.class).getLockTaskModeState();
        return mode == ActivityManager.LOCK_TASK_MODE_LOCKED
                || mode == ActivityManager.LOCK_TASK_MODE_PINNED;
    }
    static void restore(Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("usb_lock_restore", false)) return;
        final Set<String> previous = prefs.getStringSet("usb_lock_packages", new HashSet<>());
        try {
            ActivityTaskManager.getService().updateLockTaskPackages(UserHandle.myUserId(),
                    previous.toArray(new String[0]));
            prefs.edit().remove("usb_lock_restore").remove("usb_lock_packages").commit();
        } catch (RemoteException | SecurityException e) { }
    }
}
