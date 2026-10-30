package org.andr36oid.subsystemsetup;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/** Shows the boot Settings entry after the broker finds a Linux image. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !"org.andr36oid.subsystemsetup.UPDATE_SETTINGS".equals(intent.getAction())) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // A pending launch only makes sense within the boot that started it. The
            // setup activity persists launch_pending across process death so the boot
            // console survives a full handoff, but it must not survive a REBOOT: a
            // session that ended uncleanly (e.g. an external stop after a game) otherwise
            // leaves the setup screen stuck on "Waiting for the subsystem service" with
            // no live session. A completed boot means any carried-over pending is stale.
            final SharedPreferences prefs =
                    context.getSharedPreferences("subsystem_setup", Context.MODE_PRIVATE);
            if (prefs.getBoolean("launch_pending", false)) {
                prefs.edit().remove("launch_pending").remove("launch_elapsed")
                        .remove("launch_saw_running").apply();
            }
        }
        SubsystemSetupActivity.updateLauncher(context);
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
