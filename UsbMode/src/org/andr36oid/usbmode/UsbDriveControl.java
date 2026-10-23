package org.andr36oid.usbmode;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemProperties;
import android.os.storage.DiskInfo;
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

    static void start(Context context, UsbDrive.Offer offer, boolean ro, boolean cdrom) {
        final boolean image = offer.kind == UsbDrive.Kind.IMAGE;
        SystemProperties.set(PREFIX + "target", image ? "file" : offer.node);
        SystemProperties.set(PREFIX + "image", image ? offer.node : "");
        SystemProperties.set(PREFIX + "stamp", image ? offer.stamp : "");
        SystemProperties.set(PREFIX + "cdrom", image && cdrom ? "1" : "0");
        SystemProperties.set(PREFIX + "cid", offer.cid);
        SystemProperties.set(PREFIX + "ro", ro || offer.forcedRo || image && cdrom ? "1" : "0");
        SystemProperties.set(PREFIX + "owner", Integer.toString(Process.myPid()));
        SystemProperties.set(PREFIX + "request", "start");
        context.startForegroundService(new Intent(context, UsbModeService.class));
    }

    static void scanImages() { SystemProperties.set(PREFIX + "scan", "start"); }

    static UsbDrive.Images images() {
        return UsbDrive.images(new File("/data/misc/andr36oid-usb-drive/images"));
    }

    /**
     * The whole block device behind a disk, e.g. mmcblk1. sysPath is only there once vold
     * has sent the disk's metadata, so fall back to the major,minor in its id (disk:179,64).
     */
    private static String diskName(DiskInfo disk) {
        String path = disk.sysPath;
        if (path == null && disk.id != null && disk.id.startsWith("disk:")) {
            path = "/sys/dev/block/" + disk.id.substring(5).replace(',', ':');
        }
        if (path == null) return null;
        try {
            return new File(path).getCanonicalFile().getName();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static List<UsbDrive.Offer> scan(Context context) {
        final List<UsbDrive.Volume> volumes = new ArrayList<>();
        final StorageManager sm = context.getSystemService(StorageManager.class);
        if (sm != null) for (VolumeInfo v : sm.getVolumes()) {
            if (v.getDisk() == null) continue;
            final String disk = diskName(v.getDisk());
            if (disk == null) continue;
            volumes.add(new UsbDrive.Volume(v.getId(), disk, v.fsLabel, v.fsType,
                    v.getType() == VolumeInfo.TYPE_PRIVATE));
        }
        String suffix = SystemProperties.get("ro.boot.fstab_suffix", "rk30board");
        if (suffix.isEmpty()) suffix = "rk30board";
        return new UsbDrive(new File("/"), volumes).scan("/vendor/etc/fstab." + suffix,
                SystemProperties.get("sys.andr36oid.easyroms.dev", ""));
    }
}
