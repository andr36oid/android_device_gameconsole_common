package org.andr36oid.gamecomfort;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.Log;

/** One existing helper monitors native sampler ownership; Java remains the fallback. */
final class BatteryBootCheck {
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Boolean nativeOwner;
    private static boolean started;
    private static int grace = 20;
    static void start(Context context) {
        if (started) return;
        started = true;
        final Context app = context.getApplicationContext();
        if ("1".equals(SystemProperties.get("persist.andr36oid.ram.boot_batterydetails", "0"))) {
            try {
                Context target = app.createPackageContext("org.andr36oid.batterydetails", 0);
                // App UID/Context creates the correct owner and SELinux label;
                // the root sampler never creates an app-private history/lock file.
                java.io.File dir = target.getFilesDir();
                try (java.io.FileOutputStream lock = new java.io.FileOutputStream(
                        new java.io.File(dir, "history.lock"), true);
                        java.io.FileOutputStream history = new java.io.FileOutputStream(
                        new java.io.File(dir, "history.csv"), true)) { }
            } catch (PackageManager.NameNotFoundException | java.io.IOException | RuntimeException e) {
                Log.w("BootChecks", "Cannot initialize native battery history; keeping Java", e);
            }
        }
        HANDLER.post(new Runnable() {
            @Override public void run() {
                final boolean requested = "1".equals(SystemProperties.get(
                        "persist.andr36oid.ram.boot_batterydetails", "0"));
                final boolean ready = "1".equals(SystemProperties.get(
                        "sys.andr36oid.ram.battery_sampler", "0"));
                if (requested && !ready && nativeOwner == null && grace-- > 0) {
                    HANDLER.postDelayed(this, 1000);
                    return;
                }
                final boolean wanted = requested && ready;
                if (nativeOwner == null || nativeOwner != wanted) {
                    boolean effective = wanted;
                    if (wanted) {
                        try {
                            // Same system UID and platform signer. Use this app's context
                            // for resources, data directory and JobScheduler component names.
                            Context target = app.createPackageContext("org.andr36oid.batterydetails",
                                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                            target.getClassLoader().loadClass("org.andr36oid.batterydetails.NativeBoot")
                                    .getMethod("prepare", Context.class).invoke(null, target);
                        } catch (ReflectiveOperationException | PackageManager.NameNotFoundException
                                | RuntimeException e) {
                            Log.w("BootChecks", "Native battery boot unavailable; keeping Java", e);
                            effective = false;
                        }
                    }
                    select(app, effective);
                    nativeOwner = effective;
                }
                // Changes to this boot policy take effect on reboot. While on,
                // keep a cheap fallback check in the already-resident helper.
                if (requested) HANDLER.postDelayed(this, 60_000);
            }
        });
    }
    private static void select(Context context, boolean nativeSampler) {
        final ComponentName receiver = new ComponentName("org.andr36oid.batterydetails",
                "org.andr36oid.batterydetails.BootReceiver");
        final PackageManager pm = context.getPackageManager();
        try {
            pm.getReceiverInfo(receiver, PackageManager.MATCH_DISABLED_COMPONENTS);
            final int before = pm.getComponentEnabledSetting(receiver);
            final android.content.SharedPreferences journal = context.getSharedPreferences(
                    "ram3_battery_owner", Context.MODE_PRIVATE);
            if (nativeSampler) {
                if (before != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    if (!journal.contains("previous")
                            && !journal.edit().putInt("previous", before).commit()) return;
                    pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                }
            } else if (journal.contains("previous")) {
                if (before == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(receiver, journal.getInt("previous", 0),
                            PackageManager.DONT_KILL_APP);
                    context.sendBroadcast(new Intent(Intent.ACTION_BOOT_COMPLETED).setComponent(receiver));
                }
                journal.edit().remove("previous").commit();
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w("BootChecks", "Cannot select battery boot path", e);
        }
    }
}
