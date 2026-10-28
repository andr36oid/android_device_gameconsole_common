package org.andr36oid.subsystemsetup;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Shows the boot Settings entry after the broker finds a Linux image. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !"org.andr36oid.subsystemsetup.UPDATE_SETTINGS".equals(intent.getAction())) return;
        final ComponentName entry = new ComponentName(context, SubsystemBootActivity.class);
        final String image = SubsystemBackend.get(SubsystemBackend.IMAGE);
        // The broker reports 0 after a scan finds no image.
        final int state = !image.isEmpty() && !"0".equals(image)
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        final PackageManager pm = context.getPackageManager();
        if (pm.getComponentEnabledSetting(entry) != state) {
            pm.setComponentEnabledSetting(entry, state, PackageManager.DONT_KILL_APP);
        }
    }
}
