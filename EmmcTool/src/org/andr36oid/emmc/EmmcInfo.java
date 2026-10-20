package org.andr36oid.emmc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the helper found on the built-in eMMC ($DIR/info, see andr36oid-emmc.sh): the chip's
 * sysfs fields, its partitions and boot areas. No Android classes, so the host test can
 * use it.
 */
final class EmmcInfo {

    /** A partition or boot area. */
    static final class Part {
        /** Block name, such as mmcblk2p3 or mmcblk2boot0. */
        final String block;
        final long sectors;
        /** Partition name from the partition table, file system type and label; "" if none. */
        final String name, type, label;
        /** Partition or boot area number. */
        final int number;

        Part(String block, long sectors, String name, String type, String label, int number) {
            this.block = block;
            this.sectors = sectors;
            this.name = name;
            this.type = type;
            this.label = label;
            this.number = number;
        }

        long bytes() {
            return sectors * 512;
        }
    }

    /** The helper found the eMMC. */
    final boolean present;
    final String disk;
    final long sectors;
    final List<Part> parts;
    final List<Part> boots;
    private final Map<String, String> mFields;

    private EmmcInfo(Map<String, String> fields, List<Part> parts, List<Part> boots) {
        mFields = fields;
        present = "ok".equals(fields.get("state")) && fields.get("disk") != null;
        disk = fields.get("disk");
        sectors = number(fields.get("sectors"));
        this.parts = Collections.unmodifiableList(parts);
        this.boots = Collections.unmodifiableList(boots);
    }

    static EmmcInfo parse(String text) {
        final Map<String, String> fields = new HashMap<>();
        final List<Part> parts = new ArrayList<>();
        final List<Part> boots = new ArrayList<>();
        for (String line : text.split("\n")) {
            final int eq = line.indexOf('=');
            if (eq <= 0) continue;
            final String key = line.substring(0, eq);
            final String value = line.substring(eq + 1).trim();
            if ("part".equals(key)) {
                // block|start|sectors|name|type|label
                final String[] f = value.split("\\|", -1);
                if (f.length < 6) continue;
                parts.add(new Part(f[0], number(f[2]), f[3], f[4], f[5], suffix(f[0], 'p')));
            } else if ("boot".equals(key)) {
                // block|sectors
                final String[] f = value.split("\\|", -1);
                if (f.length < 2) continue;
                boots.add(new Part(f[0], number(f[1]), "", "", "", suffix(f[0], 't')));
            } else {
                fields.put(key, value);
            }
        }
        return new EmmcInfo(fields, parts, boots);
    }

    String field(String key) {
        final String v = mFields.get(key);
        return v == null || v.isEmpty() ? null : v;
    }

    long bytes() {
        return sectors * 512;
    }

    /** The whole eMMC and its boot areas, what "whole eMMC" copies. */
    long wholeBytes() {
        long b = bytes();
        for (Part p : boots) b += p.bytes();
        return b;
    }

    /** The maker of the common eMMC chips, by the manufacturer ID in sysfs; null if unknown. */
    static String maker(String manfid) {
        if (manfid == null) return null;
        final int id;
        try {
            id = Integer.decode(manfid);
        } catch (NumberFormatException e) {
            return null;
        }
        switch (id) {
            case 0x02:
            case 0x45: return "SanDisk";
            case 0x11: return "Kioxia (Toshiba)";
            case 0x13:
            case 0xfe: return "Micron";
            case 0x15: return "Samsung";
            case 0x70: return "Kingston";
            case 0x88: return "Foresee";
            case 0x90: return "SK hynix";
            case 0x9b: return "YMTC";
            default: return null;
        }
    }

    /** The eMMC standard version from the EXT_CSD revision in sysfs ("0x8"); null if unknown. */
    static String version(String rev) {
        if (rev == null) return null;
        final int r;
        try {
            r = Integer.decode(rev);
        } catch (NumberFormatException e) {
            return null;
        }
        switch (r) {
            case 0: return "4.0";
            case 1: return "4.1";
            case 2: return "4.2";
            case 3: return "4.3";
            case 5: return "4.41";
            case 6: return "4.5";
            case 7: return "5.0";
            case 8: return "5.1";
            default: return null;
        }
    }

    /**
     * How worn the chip says it is: the higher of its two life time estimates ("0x01 0x02"
     * in sysfs), 1 for 0-10% of its rated life used, ... 10 for 90-100%, 11 past it. 0 if
     * the chip doesn't say.
     */
    static int lifeUsed(String lifeTime) {
        if (lifeTime == null) return 0;
        int max = 0;
        for (String v : lifeTime.trim().split("\\s+")) {
            try {
                final int n = Integer.decode(v);
                if (n >= 1 && n <= 11) max = Math.max(max, n);
            } catch (NumberFormatException e) {
                // not a number
            }
        }
        return max;
    }

    /** The spare blocks: 1 normal, 2 warning (80% used), 3 urgent (90%); 0 if not said. */
    static int preEol(String preEolInfo) {
        if (preEolInfo == null) return 0;
        try {
            final int n = Integer.decode(preEolInfo.trim());
            return n >= 1 && n <= 3 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The number after the last c in a block name: 3 for mmcblk2p3 (p), 0 for ...boot0 (t). */
    private static int suffix(String block, char c) {
        final int i = block.lastIndexOf(c);
        if (i < 0) return 0;
        return (int) number(block.substring(i + 1));
    }

    private static long number(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
