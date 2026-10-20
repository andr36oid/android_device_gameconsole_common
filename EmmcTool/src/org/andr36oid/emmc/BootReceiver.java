package org.andr36oid.emmc;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * Shows the Settings entries only when Kernel Tweaks switched the eMMC on for this start
 * (eMMC Installation only on an SD card start), and opens eMMC Installation by itself
 * after a restart it asked for: to check the step on the SD card, or on the eMMC's test
 * start to say it worked.
 */
public class BootReceiver extends BroadcastReceiver {
    /** How long to wait for the eMMC system's test start check (it starts with us). */
    private static final long CONFIRM_WAIT_MS = 9000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        final boolean on = EmmcFragment.consoleHasEmmc() && EmmcFragment.switchedOn();
        enable(context, EmmcActivity.class.getName(), on);
        enable(context, installEntry(), on && Installer.fromSD());
        if (on && Installer.fromSD() && pending()) {
            open(context);
        } else if (on && Installer.fromEmmc()) {
            final PendingResult result = goAsync();
            new Thread(() -> {
                final long until = SystemClock.elapsedRealtime() + CONFIRM_WAIT_MS;
                JSONObject c;
                while ((c = Installer.confirmResult()) == null
                        && SystemClock.elapsedRealtime() < until) {
                    SystemClock.sleep(300);
                }
                if (c != null && "confirmed".equals(c.optString("result"))) open(context);
                result.finish();
            }).start();
        }
    }

    private static String installEntry() {
        return InstallActivity.class.getPackage().getName() + ".InstallEntry";
    }

    /** A step waits for its check after a restart, or one was stopped. */
    private static boolean pending() {
        final JSONObject st = Installer.state();
        return "blanked".equals(st.optString("step1")) || "written".equals(st.optString("step2"))
                || !st.optString("running").isEmpty() || !st.optString("failed").isEmpty();
    }

    private static void open(Context context) {
        context.startActivity(new Intent(context, InstallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void enable(Context context, String cls, boolean on) {
        final ComponentName c = new ComponentName(context.getPackageName(), cls);
        final int state = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        final PackageManager pm = context.getPackageManager();
        if (pm.getComponentEnabledSetting(c) != state) {
            pm.setComponentEnabledSetting(c, state, PackageManager.DONT_KILL_APP);
        }
    }
}
