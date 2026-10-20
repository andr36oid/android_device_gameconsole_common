package org.andr36oid.emmc;

import android.content.Context;
import android.os.Environment;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Where a backup can go: internal storage, or a mounted SD card or USB drive. */
final class Target {

    /** Android runs from internal storage and needs room left there. */
    private static final int INTERNAL_KEEP_FREE_MB = 1024;
    private static final int KEEP_FREE_MB = 16;
    /** FAT32 takes files up to 4 GB less a byte: pieces of 4095 MB. */
    private static final int FAT32_PIECE_MB = 4095;

    final String id;
    final String name;
    /** The folder the helper writes in, as root. */
    final String path;
    /** Where we read the free space. */
    private final File mSpace;
    /** 0, or the piece size for FAT32. */
    final int splitMb;
    final int keepFreeMb;

    private Target(String id, String name, String path, File space, int splitMb,
            int keepFreeMb) {
        this.id = id;
        this.name = name;
        this.path = path;
        mSpace = space;
        this.splitMb = splitMb;
        this.keepFreeMb = keepFreeMb;
    }

    /** What can be used for a backup, in bytes. */
    long freeBytes() {
        return Math.max(0, mSpace.getUsableSpace() - keepFreeMb * 1024L * 1024L);
    }

    /** Internal storage first, then the mounted cards and drives. */
    static List<Target> list(Context context) {
        final List<Target> list = new ArrayList<>();
        list.add(new Target("internal", context.getString(R.string.internal_storage),
                "/data/media/" + UserHandle.myUserId(), Environment.getDataDirectory(), 0,
                INTERNAL_KEEP_FREE_MB));
        final StorageManager sm = context.getSystemService(StorageManager.class);
        for (VolumeInfo vol : sm.getVolumes()) {
            if (vol.getType() != VolumeInfo.TYPE_PUBLIC || !vol.isMountedWritable()
                    || vol.getFsUuid() == null || vol.getInternalPath() == null
                    || vol.getPath() == null) {
                continue;
            }
            String name = sm.getBestVolumeDescription(vol);
            if (name == null) name = vol.getFsUuid();
            list.add(new Target(vol.getFsUuid(), name,
                    vol.getInternalPath().getAbsolutePath(), vol.getPath(),
                    "vfat".equals(vol.fsType) ? FAT32_PIECE_MB : 0, KEEP_FREE_MB));
        }
        return list;
    }
}
