package org.andr36oid.touchmapper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * FN + L1 (PhoneWindowManager): opens the editor on top of the app in front. Only the system
 * may send it (DEVICE_POWER, see the manifest).
 */
public class EditReceiver extends BroadcastReceiver {

    static final String ACTION_EDIT = "org.andr36oid.touchmapper.action.EDIT";

    // A bouncing L1 or FN sends FN + L1 twice. The second one comes before the editor is in
    // front, so the check below still sees the game, and starting the editor again (it
    // clears its task) can close the one just opened. Kauan: "opens and closes almost
    // instantly".
    static final long REFIRE_MS = 1000;
    private static long sLastOpen = -REFIRE_MS;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_EDIT.equals(intent.getAction())) {
            return;
        }
        final String pkg = WatcherService.foregroundPackage(context);
        if (context.getPackageName().equals(pkg)) {
            // Already editing
            return;
        }
        if (pkg == null || !canHaveControls(context, pkg)) {
            Toast.makeText(context, R.string.edit_needs_app, Toast.LENGTH_LONG).show();
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (isRefire(now, sLastOpen)) {
            return;
        }
        sLastOpen = now;
        context.startActivity(Profiles.editIntent(context, pkg));
    }

    static boolean isRefire(long now, long lastOpen) {
        return now - lastOpen < REFIRE_MS;
    }

    /** Games and other apps, but not the home screen, Settings or this app itself. */
    static boolean canHaveControls(Context context, String pkg) {
        if (pkg.equals(context.getPackageName()) || "com.android.settings".equals(pkg)
                || "com.android.systemui".equals(pkg) || "android".equals(pkg)) {
            return false;
        }
        final Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (android.content.pm.ResolveInfo ri : context.getPackageManager()
                .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (pkg.equals(ri.activityInfo.packageName)) {
                return false;
            }
        }
        return true;
    }
}
