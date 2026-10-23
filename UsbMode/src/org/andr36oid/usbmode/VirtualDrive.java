package org.andr36oid.usbmode;

/** Shared form limits. Root repeats every check against the current mount. */
final class VirtualDrive {
    static final long GB = 1073741824L;
    static final String[] FILESYSTEMS = {"fat32", "exfat", "ntfs", "ext4"};

    static boolean name(String name) {
        return name.length() <= 87 && !name.contains("..")
                && name.matches("[a-zA-Z0-9][a-zA-Z0-9._ -]*")
                && !name.endsWith(" ") && !name.endsWith(".");
    }

    static boolean label(String label, String fs) {
        int limit = "fat32".equals(fs) ? 11 : "exfat".equals(fs) ? 15
                : "ntfs".equals(fs) ? 32 : "ext4".equals(fs) ? 16 : -1;
        return label.length() <= limit && label.matches("[a-zA-Z0-9_ -]*");
    }

    static long size(String size) {
        if (!size.matches("[1-9][0-9]{0,3}")) return 0;
        long gb = Long.parseLong(size);
        return gb <= 1024 ? gb * GB : 0;
    }

    static long maximum(long free, String fs) {
        long max = Math.min(1024 * GB, Math.max(0, free - GB));
        if ("vfat".equals(fs) || "msdos".equals(fs)) max = Math.min(max, 4294967295L);
        return max / GB * GB;
    }
}
