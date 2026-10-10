package org.andr36oid.storagecare;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;

import java.io.File;

/** Free space of the internal storage and of EASYROMS, as the system user can see it. */
final class Volumes {

    /** EASYROMS: partition 7 of the boot card. */
    static final String EASYROMS_ID = "public:179,7";

    private Volumes() {
    }

    /** {free, total} of the internal storage (userdata). */
    static long[] internal() {
        final File data = Environment.getDataDirectory();
        return new long[] {data.getUsableSpace(), data.getTotalSpace()};
    }

    static VolumeInfo easyroms(Context c) {
        return c.getSystemService(StorageManager.class).findVolumeById(EASYROMS_ID);
    }

    /** {free, total} of EASYROMS, null if it isn't mounted. */
    static long[] easyroms(VolumeInfo v) {
        if (v == null || !v.isMountedReadable()) return null;
        for (File f : new File[] {v.getPath(), v.getInternalPath()}) {
            if (f == null) continue;
            final long total = f.getTotalSpace();
            if (total > 0) return new long[] {f.getUsableSpace(), total};
        }
        return null;
    }
}
