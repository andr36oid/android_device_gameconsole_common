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
 * start to say it worked. On an SD card start it also asks the installer whether this is
 * the eMMC's data card that the flasher (or the eMMC system, for going back to the stock
 * system) made start again: then the wizard offers to update the eMMC and make the card
 * a data card again, or to put the stock eMMC back (every start until one is done).
 */
public class BootReceiver extends BroadcastReceiver {
    /** How long to wait for the eMMC system's test start check (it starts with us). */
    private static final long CONFIRM_WAIT_MS = 9000;
    /** How long the data card check may take (it mounts BOOT on both). */
    private static final long CHECK_WAIT_MS = 15000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        final boolean on = EmmcFragment.consoleHasEmmc() && EmmcFragment.switchedOn();
        enable(context, EmmcActivity.class.getName(), on);
        // on the eMMC system with a data card too: the way back to the stock system
        enable(context, installEntry(), on && (Installer.fromSD()
                || (Installer.fromEmmc() && !Installer.datacard().isEmpty())));
        if (on && Installer.fromSD() && pending()) {
            open(context, false);
        } else if (on && Installer.fromSD() && !Installer.running()) {
            final PendingResult result = goAsync();
            new Thread(() -> {
                if (Installer.start("datacard-check")) {
                    final long until = SystemClock.elapsedRealtime() + CHECK_WAIT_MS;
                    JSONObject r;
                    while (((r = Installer.result()) == null || !"datacard-check".equals(r.optString("cmd")))
                            && SystemClock.elapsedRealtime() < until) {
                        SystemClock.sleep(300);
                    }
                    final JSONObject c = r == null ? null : r.optJSONObject("result");
                    if (c != null && c.optBoolean("offer")) open(context, true);
                }
                result.finish();
            }).start();
        } else if (on && Installer.fromEmmc()) {
            final PendingResult result = goAsync();
            new Thread(() -> {
                final long until = SystemClock.elapsedRealtime() + CONFIRM_WAIT_MS;
                JSONObject c;
                while ((c = Installer.confirmResult()) == null
                        && SystemClock.elapsedRealtime() < until) {
                    SystemClock.sleep(300);
                }
                if (c != null && "confirmed".equals(c.optString("result"))) open(context, false);
                result.finish();
            }).start();
        }
    }

    private static String installEntry() {
        return InstallActivity.class.getPackage().getName() + ".InstallEntry";
    }

    /**
     * A step waits for its check after a restart, or one was stopped, or a new data card
     * waits to be finished after its copy.
     */
    private static boolean pending() {
        final JSONObject st = Installer.state();
        final JSONObject d = st.optJSONObject("datacard");
        return "blanked".equals(st.optString("step1")) || "written".equals(st.optString("step2"))
                || !st.optString("running").isEmpty() || !st.optString("failed").isEmpty()
                || (d != null && "prepared".equals(d.optString("stage")));
    }

    private static void open(Context context, boolean datacardReturn) {
        context.startActivity(new Intent(context, InstallActivity.class)
                .putExtra(InstallActivity.EXTRA_DATACARD_RETURN, datacardReturn)
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
