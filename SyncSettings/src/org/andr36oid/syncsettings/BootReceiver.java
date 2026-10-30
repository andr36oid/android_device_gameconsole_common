package org.andr36oid.syncsettings;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemProperties;

/** Shows Sync only after dArkOSen has run EmulationStation. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !"org.andr36oid.syncsettings.UPDATE_SETTINGS".equals(intent.getAction())) return;
        final ComponentName entry = new ComponentName(context, SyncSettingsActivity.class);
        final int state = "1".equals(SystemProperties.get("persist.andr36oid.subsystem.booted", ""))
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        final PackageManager pm = context.getPackageManager();
        if (pm.getComponentEnabledSetting(entry) != state) {
            pm.setComponentEnabledSetting(entry, state, PackageManager.DONT_KILL_APP);
        }
    }
}
