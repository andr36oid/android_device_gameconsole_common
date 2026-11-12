package org.andr36oid.storagecare;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** No boot or card broadcasts are needed while storage warnings are off. */
final class BootStart {

    private BootStart() {}

    static void set(Context context, boolean needed) {
        final PackageManager pm = context.getPackageManager();
        final ComponentName receiver = new ComponentName(context, BootReceiver.class);
        final boolean gate = !"0".equals(android.os.SystemProperties.get(
                "persist.andr36oid.ram.boot_storagecare", "1"));
        final int state = (!gate || needed) ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (pm.getComponentEnabledSetting(receiver) != state) {
            pm.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP);
        }
    }
}
