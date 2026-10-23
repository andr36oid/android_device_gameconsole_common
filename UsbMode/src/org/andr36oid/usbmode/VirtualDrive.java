package org.andr36oid.usbmode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        return label.length() <= labelLimit(fs) && label.matches("[a-zA-Z0-9_ -]*")
                && (!"fat32".equals(fs) || !label.isEmpty() && !label.startsWith(" "));
    }

    static long size(String size) {
        if (!size.matches("[1-9][0-9]{0,3}")) return 0;
        long gb = Long.parseLong(size);
        return gb <= 1024 ? gb * GB : 0;
    }

    static long maximum(long free, String fs) {
        if (free <= GB) return 0;
        long max = Math.min(1024 * GB, free - GB);
        if ("vfat".equals(fs) || "msdos".equals(fs)) max = Math.min(max, 4294967295L);
        return max / GB * GB;
    }
    private static int labelLimit(String fs) {
        return "fat32".equals(fs) ? 11 : "exfat".equals(fs) ? 15
                : "ntfs".equals(fs) ? 32 : "ext4".equals(fs) ? 16 : -1;
    }

    static String defaultLabel(String name, String fs) {
        String label = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "_");
        if (label.isEmpty()) label = "IMAGE";
        return label.substring(0, Math.min(label.length(), Math.max(0, labelLimit(fs))));
    }

    static String nextName(List<String> names) {
        Set<String> used = new HashSet<>();
        for (String name : names) {
            int dot = name.lastIndexOf('.');
            used.add((dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT));
        }
        for (int n = 1; ; n++) if (!used.contains("image" + n)) return "image" + n;
    }

    static String clampSize(String value, long free, String fs) {
        long max = maximum(free, fs) / GB;
        if (max == 0) return "";
        if (!value.matches("[1-9][0-9]*")) return value;
        try { return Long.parseLong(value) > max ? Long.toString(max) : value; }
        catch (NumberFormatException e) { return Long.toString(max); }
    }

    static List<String> presets(long free, String fs) {
        List<String> sizes = new ArrayList<>();
        long max = maximum(free, fs) / GB;
        for (int gb : new int[] {1,2,4,8,16,32,64,128,256,512,1024})
            if (gb <= max) sizes.add(Integer.toString(gb));
        return sizes;
    }

    static boolean presetSelected(String value, String preset) { return value.equals(preset); }

}
