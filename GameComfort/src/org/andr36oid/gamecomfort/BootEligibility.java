package org.andr36oid.gamecomfort;

import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.os.SystemProperties;
import android.util.Log;
import java.io.File;
import java.nio.file.Files;

/** Avoids no-op boot receivers without moving or disabling their real work. */
final class BootEligibility {
    static void run(Context context, File root) {
        try {
            Context cpu = context.createPackageContext("org.andr36oid.cpuoverclock", 0);
            SharedPreferences p = cpu.getSharedPreferences("profiles", Context.MODE_PRIVATE);
            boolean needed = p.getInt("profile", 1) != 1 || p.getInt("cpu_cap_khz", 0) > 0
                    || !p.getBoolean("tile_added", false)
                    || cpu.getSharedPreferences("profile_command", Context.MODE_PRIVATE)
                            .getBoolean("hold_memory", false);
            gate(context, "cpuoverclock", ".ProfileReceiver", needed);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) { log(e); }
        try {
            Context save = context.createPackageContext("org.andr36oid.savebackup", 0);
            boolean needed = save.getSharedPreferences("org.andr36oid.savebackup_preferences",
                    Context.MODE_PRIVATE).getBoolean("auto", true);
            gate(context, "savebackup", ".BootReceiver", needed);
            if (enabled("savebackup") && !needed) {
                JobScheduler jobs = context.getSystemService(JobScheduler.class);
                jobs.cancel(3621); jobs.cancel(3622);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) { log(e); }
        try {
            Context storage = context.createPackageContext("org.andr36oid.storagecare", 0);
            boolean needed = storage.getSharedPreferences("org.andr36oid.storagecare_preferences",
                    Context.MODE_PRIVATE).getBoolean("warn", true);
            gate(context, "storagecare", ".BootReceiver", needed);
            if (enabled("storagecare") && !needed) {
                JobScheduler jobs = context.getSystemService(JobScheduler.class);
                jobs.cancel(3741); jobs.cancel(3742);
                storage.getSystemService(android.app.NotificationManager.class).cancelAll();
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) { log(e); }
        try {
            File marker = new File(root, "proc/device-tree/dwmmc@ff390000/andr36oid,builtin-emmc");
            File parameter = new File(root, "sys/module/dw_mmc_rockchip/parameters/builtin_emmc");
            // Unknown/read-error is fail-open. Only suppress a proven absent/off controller.
            boolean needed = marker.exists();
            if (needed && parameter.isFile()) {
                String v = new String(Files.readAllBytes(parameter.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
                needed = !v.isEmpty() && !v.equals("0") && !v.equalsIgnoreCase("n") && !v.equals("off");
            }
            if (enabled("emmc") && !needed) {
                BootChecks.updateEntry(context, "org.andr36oid.emmc", ".EmmcActivity", false);
                BootChecks.updateEntry(context, "org.andr36oid.emmc", ".InstallEntry", false);
            }
            gate(context, "emmc", ".BootReceiver", needed);
        } catch (java.io.IOException | RuntimeException e) { log(e); }
        try {
            UsbManager usb = context.getSystemService(UsbManager.class);
            gate(context, "usbmode", ".UsbLinkService$BootReceiver",
                    usb == null || !usb.getDeviceList().isEmpty());
        } catch (RuntimeException e) { log(e); }
    }
    private static boolean enabled(String name) {
        return "1".equals(SystemProperties.get("persist.andr36oid.ram.boot3_" + name, "0"));
    }
    static void gate(Context c, String name, String receiver, boolean needed) {
        final ComponentName component = new ComponentName("org.andr36oid." + name,
                "org.andr36oid." + name + receiver);
        final PackageManager pm = c.getPackageManager();
        final SharedPreferences journal = c.getSharedPreferences("ram3_boot_receivers", Context.MODE_PRIVATE);
        final String key = component.flattenToString();
        try {
            pm.getReceiverInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS);
            int state = pm.getComponentEnabledSetting(component);
            if (enabled(name) && !needed) {
                if (!journal.contains(key)) {
                    // Do not take over a receiver already disabled by its own app/user.
                    if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return;
                    if (!journal.edit().putInt(key, state).commit()) return;
                }
                if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                }
            } else if (journal.contains(key)) {
                int previous = journal.getInt(key, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(component, previous, PackageManager.DONT_KILL_APP);
                    if (previous != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        c.sendBroadcast(new Intent(Intent.ACTION_BOOT_COMPLETED).setComponent(component));
                    }
                }
                journal.edit().remove(key).commit();
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) { log(e); }
    }
    private static void log(Exception e) { Log.w("BootChecks", "Boot eligibility unavailable; retaining app path", e); }
}
