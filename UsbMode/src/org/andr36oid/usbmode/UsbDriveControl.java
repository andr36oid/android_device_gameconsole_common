package org.andr36oid.usbmode;

import android.content.Context;
import android.os.Process;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Requests are boot properties. The root helper alone writes sysfs and configfs. */
final class UsbDriveControl {
    private static final String PREFIX = "sys.andr36oid.usbdrive.";

    static String state() { return SystemProperties.get(PREFIX + "state", ""); }

    static void stop() { SystemProperties.set(PREFIX + "request", "stop"); }

    static boolean busy() {
        final String state = state();
        return "preparing".equals(state) || "sharing".equals(state) || "stopping".equals(state)
                || "start".equals(SystemProperties.get(PREFIX + "request"));
    }

    static void start(UsbDrive.Offer offer, boolean ro) {
        SystemProperties.set(PREFIX + "target", offer.node);
        SystemProperties.set(PREFIX + "cid", offer.cid);
        SystemProperties.set(PREFIX + "ro", ro || offer.forcedRo ? "1" : "0");
        SystemProperties.set(PREFIX + "owner", Integer.toString(Process.myPid()));
        SystemProperties.set(PREFIX + "request", "start");
    }

    static List<UsbDrive.Offer> scan(Context context) {
        final List<UsbDrive.Volume> volumes = new ArrayList<>();
        final StorageManager sm = context.getSystemService(StorageManager.class);
        if (sm != null) for (VolumeInfo v : sm.getVolumes()) {
            if (v.getDisk() == null) continue;
            String disk = "";
            try {
                disk = new File(v.getDisk().sysPath).getCanonicalFile().getName();
                // DiskInfo.sysPath names the whole block device in sysfs.
            } catch (java.io.IOException e) {
                continue;
            }
            volumes.add(new UsbDrive.Volume(v.getId(), disk, v.fsLabel, v.fsType,
                    v.getType() == VolumeInfo.TYPE_PRIVATE));
        }
        String suffix = SystemProperties.get("ro.boot.fstab_suffix", "rk30board");
        if (suffix.isEmpty()) suffix = "rk30board";
        return new UsbDrive(new File("/"), volumes).scan("/vendor/etc/fstab." + suffix,
                SystemProperties.get("sys.andr36oid.easyroms.dev", ""));
    }
}
