package org.andr36oid.cardcheck;

import android.content.Context;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A mounted SD card or USB drive partition (a public volume), as vold sees it. */
final class Card {

    /** EASYROMS: partition 7 of the boot card. */
    private static final String EASYROMS_ID = "public:179,7";

    final String uuid;
    final String name;
    /** Where vold mounted it, the lower file system under the FUSE view. */
    final String internalPath;
    /** The whole disk, "major:minor". */
    final String disk;
    final long diskBytes;
    final boolean sd;
    final File path;
    final String sysPath;

    private Card(VolumeInfo vol, DiskInfo d, String name) {
        uuid = vol.getFsUuid();
        this.name = name;
        internalPath = vol.getInternalPath().getAbsolutePath();
        // "disk:179,0" -> "179:0"
        disk = d.id.substring(d.id.indexOf(':') + 1).replace(',', ':');
        diskBytes = d.size;
        sd = d.isSd();
        path = vol.getPath();
        sysPath = d.sysPath;
    }

    /** Mounted cards and drives, EASYROMS first. */
    static List<Card> list(Context context) {
        final StorageManager sm = context.getSystemService(StorageManager.class);
        final List<Card> cards = new ArrayList<>();
        for (VolumeInfo vol : sm.getVolumes()) {
            final DiskInfo d = vol.getDisk();
            if (vol.getType() != VolumeInfo.TYPE_PUBLIC || !vol.isMountedWritable()
                    || d == null || vol.getFsUuid() == null || vol.getInternalPath() == null
                    || !vol.getInternalPath().getAbsolutePath().startsWith("/mnt/media_rw/")) {
                continue;
            }
            String name = sm.getBestVolumeDescription(vol);
            if (name == null) name = vol.getFsUuid();
            final Card card = new Card(vol, d, name);
            if (EASYROMS_ID.equals(vol.getId())) {
                cards.add(0, card);
            } else {
                cards.add(card);
            }
        }
        return cards;
    }

    long freeBytes() {
        return path != null ? path.getUsableSpace() : 0;
    }

    /** The card's own ID fields (SD cards only), null if not readable. */
    String attr(String name) {
        if (sysPath == null) return null;
        try (InputStream in = new FileInputStream(new File(sysPath, "device/" + name))) {
            final byte[] buf = new byte[128];
            final int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** SD association manufacturer IDs of the common brands. */
    static String maker(String manfid) {
        if (manfid == null) return null;
        final int id;
        try {
            id = Integer.decode(manfid);
        } catch (NumberFormatException e) {
            return null;
        }
        switch (id) {
            case 0x01: return "Panasonic";
            case 0x02: return "Toshiba/Kioxia";
            case 0x03: return "SanDisk";
            case 0x09: return "ATP";
            case 0x1b: return "Samsung";
            case 0x1d: return "ADATA";
            case 0x27: return "Phison";
            case 0x28: return "Lexar";
            case 0x31: return "Silicon Power";
            case 0x41: return "Kingston";
            case 0x74: return "Transcend";
            case 0x76: return "Patriot";
            case 0x82: return "Sony";
            case 0x9c: return "Angelbird";
            case 0xad: return "Longsys";
            default: return null;
        }
    }
}
