package org.andr36oid.touchmapper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

/**
 * FN + L1 (PhoneWindowManager): opens the editor on top of the app in front. Only the system
 * may send it (DEVICE_POWER, see the manifest).
 */
public class EditReceiver extends BroadcastReceiver {

    static final String ACTION_EDIT = "org.andr36oid.touchmapper.action.EDIT";

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
        context.startActivity(Profiles.editIntent(context, pkg));
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
