package org.andr36oid.gamecomfort;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** Small boot checks sharing the already-running GameComfort process. */
final class BootChecks {

    private static final String TAG = "BootChecks";
    private static final String STATUS_LED =
            "sys/devices/platform/andr36oid-status-led/colors";
    private static final String RGB_LED = "sys/class/leds/red:status";
    private static final String CARD_CHECK = "data/misc/andr36oid-cardcheck";

    private BootChecks() {}

    static void run(Context context) {
        run(context, new File("/"));
    }

    // A private filesystem root lets host checks exercise moving a card between boards.
    static void run(Context context, File root) {
        AppCompileJob.schedule(context);
        BatteryBootCheck.start(context);
        BootEligibility.run(context, root);
        // Recheck at every boot: users move the card between consoles and change DTBs.
        // StorageCare normally owns its preference-aware receiver gate. A separate
        // process must be able to restore its opt-out while that receiver is off.
        if ("0".equals(SystemProperties.get("persist.andr36oid.ram.boot_storagecare", "1")))
            managed(context, "storagecare", ".BootReceiver");
        if (managed(context, "statuslight", ".BootReceiver"))
            updateEntry(context, "org.andr36oid.statuslight", ".StatusLightActivity",
                    hasLine(new File(root, STATUS_LED)));
        if (managed(context, "rgblights", ".BootReceiver"))
            updateEntry(context, "org.andr36oid.rgblights", ".RgbLightsActivity",
                    new File(root, RGB_LED).exists());
        if (managed(context, "bfi", ".BootReceiver"))
            updateEntry(context, "org.andr36oid.bfi", ".BfiActivity",
                    Settings.System.getFloat(context.getContentResolver(),
                            Settings.System.PEAK_REFRESH_RATE, 60f) >= 99.5f);

        if (managed(context, "syncsettings", ".BootStartReceiver"))
            updateEntry(context, "org.andr36oid.syncsettings", ".SyncSettingsActivity",
                    "1".equals(SystemProperties.get("persist.andr36oid.subsystem.booted", "")));

        if (managed(context, "cardcheck", ".BootStartReceiver")
                && hasCardProgress(new File(root, CARD_CHECK))) {
            // The helper writes progress before filling the card, including on a power cut.
            // Only start the notification app when there may actually be a reminder.
            context.sendBroadcast(new Intent("org.andr36oid.cardcheck.RESTORE_PAUSED")
                    .setComponent(new ComponentName("org.andr36oid.cardcheck",
                            "org.andr36oid.cardcheck.BootReceiver")));
        }
    }

    // Per helper opt-out. Keep the established Java path available; never kill
    // any app sharing the system UID while updating component state.
    private static boolean managed(Context c, String name, String receiver) {
        final boolean managed = !"0".equals(SystemProperties.get(
                "persist.andr36oid.ram.boot_" + name, "1"));
        final String pkg = "org.andr36oid." + name;
        final ComponentName component = new ComponentName(pkg, pkg + receiver);
        final PackageManager pm = c.getPackageManager();
        try {
            pm.getReceiverInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS);
            final int state = managed ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            if (pm.getComponentEnabledSetting(component) != state) {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP);
                if (!managed) c.sendBroadcast(new Intent("android.intent.action.BOOT_COMPLETED")
                        .setComponent(component));
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Optional helper absent: do not create an override for a future install.
        } catch (RuntimeException e) {
            Log.w(TAG, "Can't select boot path for " + pkg, e);
        }
        return managed;
    }

    static void updateEntry(Context context, String pkg, String activity, boolean show) {
        final ComponentName entry = new ComponentName(pkg, pkg + activity);
        final PackageManager pm = context.getPackageManager();
        try {
            // The app is optional. Do not create overrides for components absent from a build.
            pm.getActivityInfo(entry, PackageManager.MATCH_DISABLED_COMPONENTS);
            final int state = show ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            if (pm.getComponentEnabledSetting(entry) != state) {
                pm.setComponentEnabledSetting(entry, state, PackageManager.DONT_KILL_APP);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Not installed in this build.
        } catch (RuntimeException e) {
            // A missing helper or package-manager failure must not stop Continue playing.
            Log.w(TAG, "Can't update " + entry.flattenToShortString(), e);
        }
    }

    private static boolean hasLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine() != null;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean hasCardProgress(File dir) {
        final File[] files = dir.listFiles((parent, name) -> name.startsWith("progress-"));
        if (files == null) return false;
        for (File file : files) {
            if (file.isFile() && file.length() > 0) return true;
        }
        return false;
    }
}
