package org.andr36oid.wifitransfer;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The folders the web page may use: EASYROMS first, then USB drives, then internal storage. */
final class Volumes implements Root.Source {

    /** EASYROMS: partition 7 of the boot card. */
    private static final String EASYROMS_ID = "public:179,7";

    private final Context mContext;

    Volumes(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public List<Root> roots() {
        final StorageManager sm = mContext.getSystemService(StorageManager.class);
        final List<Root> roots = new ArrayList<>();
        for (VolumeInfo vol : sm.getVolumes()) {
            final File path = vol.getPath();
            if (vol.getType() != VolumeInfo.TYPE_PUBLIC || !vol.isMountedWritable()
                    || path == null || vol.getFsUuid() == null || !path.isDirectory()) {
                continue;
            }
            String name = sm.getBestVolumeDescription(vol);
            if (EASYROMS_ID.equals(vol.getId())) {
                roots.add(0, new Root("easyroms", name != null ? name : "EASYROMS", path));
            } else {
                roots.add(new Root("vol-" + vol.getFsUuid().replaceAll("[^A-Za-z0-9-]", ""),
                        name != null ? name : vol.getFsUuid(), path));
            }
        }
        final File internal = Environment.getExternalStorageDirectory();
        if (internal != null && internal.isDirectory()) {
            roots.add(new Root("internal", mContext.getString(R.string.internal_storage),
                    internal));
        }
        return roots;
    }
}
