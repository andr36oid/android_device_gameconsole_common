package org.andr36oid.cardcheck;

import android.content.Context;
import android.os.Environment;
import android.os.SystemProperties;
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

/**
 * A mounted SD card or USB drive partition (a public volume), as vold sees it; or the boot
 * card's userdata when it has no EASYROMS.
 */
final class Card {

    /** Init selects EASYROMS on the boot/data disk; older boots used fixed numbers. */
    private static String easyromsId() {
        final String id = SystemProperties.get("sys.andr36oid.easyroms.id", "");
        return id.isEmpty() && !SystemProperties.getBoolean("sys.andr36oid.easyroms.ready", false)
                ? "public:179,7" : id;
    }
    /**
     * The boot card without EASYROMS (the user chose not to make it): the tests write to a
     * folder on userdata instead. The helper knows this exact folder; its name is the uuid.
     */
    static final String USERDATA = "userdata";
    private static final File USERDATA_DIR =
            new File("/data/misc/andr36oid-cardcheck/" + USERDATA);
    /** SD cards and eMMC */
    private static final String MMC_MAJOR = "179";

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
    /** The tests run on userdata, there is no EASYROMS. */
    final boolean userdata;

    private Card(VolumeInfo vol, DiskInfo d, String name) {
        uuid = vol.getFsUuid();
        this.name = name;
        internalPath = vol.getInternalPath().getAbsolutePath();
        // "disk:179,0" -> "179:0"
        disk = d.id.substring(d.id.indexOf(':') + 1).replace(',', ':');
        sd = d.isSd();
        path = vol.getPath();
        // vold never sends the size and sysfs path of the boot card: EASYROMS is set up
        // as one partition of it (voldmanaged=sdcard1:7, a DiskPartition), which skips
        // reading the disk's metadata. So look the disk up in sysfs ourselves.
        final File dir = diskDir(disk);
        sysPath = dir != null ? dir.getPath() : d.sysPath;
        final long bytes = sectors(dir) * 512;
        diskBytes = bytes > 0 ? bytes : d.size;
        userdata = false;
    }

    private Card(String name, String disk, File dir) {
        uuid = USERDATA;
        this.name = name;
        internalPath = USERDATA_DIR.getPath();
        this.disk = disk;
        diskBytes = sectors(dir) * 512;
        sd = disk.startsWith(MMC_MAJOR + ":");
        path = Environment.getDataDirectory();
        sysPath = dir.getPath();
        userdata = true;
    }

    /**
     * The boot card as userdata, or null if it has EASYROMS or userdata can't be found in
     * sysfs.
     */
    private static Card userdataCard(Context context) {
        final File[] blocks = new File("/sys/class/block").listFiles();
        if (blocks == null) return null;
        for (File block : blocks) {
            final String uevent = read(new File(block, "uevent"));
            if (uevent == null || !uevent.contains("PARTNAME=userdata")) continue;
            try {
                // a partition sits in its disk's folder in sysfs
                final File dir = block.getCanonicalFile().getParentFile();
                if (dir == null) return null;
                final String disk = read(new File(dir, "dev"));
                if (disk == null) return null;
                // EASYROMS (p7) is there, just not mounted yet (vold may still be
                // checking it): wait for it rather than test userdata
                if (new File(dir, dir.getName() + "p7").exists()) return null;
                return new Card(context.getString(R.string.userdata_name), disk, dir);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /** The disk's folder in sysfs (/sys/devices/.../block/mmcblk0), null if not found. */
    static File diskDir(String majMin) {
        try {
            final File dir = new File("/sys/dev/block/" + majMin).getCanonicalFile();
            return dir.isDirectory() ? dir : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The disk's size in 512 byte sectors, 0 if not known. */
    private static long sectors(File dir) {
        if (dir == null) return 0;
        try {
            return Long.parseLong(read(new File(dir, "size")));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A short sysfs file, trimmed; null if it can't be read. */
    static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            final byte[] buf = new byte[256];
            final int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Mounted cards and drives, EASYROMS first. With no EASYROMS the boot card comes first
     * as userdata.
     */
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
            if (easyromsId().equals(vol.getId())) {
                cards.add(0, card);
            } else {
                cards.add(card);
            }
        }
        final Card boot = userdataCard(context);
        if (boot == null) return cards;
        for (Card card : cards) {
            if (card.disk.equals(boot.disk)) return cards;
        }
        cards.add(0, boot);
        return cards;
    }

    long freeBytes() {
        return path != null ? path.getUsableSpace() : 0;
    }

    /** The card's own ID fields (SD cards only), null if not readable. */
    String attr(String name) {
        if (sysPath == null) return null;
        return read(new File(sysPath, "device/" + name));
    }

    /** The card's ID registers (SD cards only), null if they can't be read. */
    CardId id() {
        if (!sd) return null;
        final CardId id = CardId.parse(attr("cid"), attr("csd"), attr("scr"), attr("ssr"));
        return id.hasId() ? id : null;
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
