package org.andr36oid.cardcheck;

import java.util.ArrayList;
import java.util.List;

/**
 * What an SD card says about itself: the CID, CSD, SCR and SSR registers the kernel shows
 * in /sys/block/mmcblkN/device (cid, csd, scr, ssr, as hex). Plain Java, so the host test
 * can run it. Bit numbers are the SD Physical Layer spec's (bit 0 = last hex digit's
 * lowest bit), which is how the kernel prints them.
 */
final class CardId {

    static final long GB = 1000L * 1000 * 1000;

    /** Signs of a fake or no-name card, weakest last. */
    enum Sign {
        /** Maker ID 0, or an OEM ID that is 0 or not two letters/digits. */
        NO_MAKER,
        /** The maker ID of a big brand with a different brand's OEM ID. */
        BRAND_MISMATCH,
        /** The CSD's size isn't the size the system uses. */
        SIZE_MISMATCH,
        /** A card over 32 GB that claims an SD spec from before such cards existed. */
        SPEC_TOO_OLD,
        /** Made in month 0 or 13+, or in the future. */
        BAD_DATE,
        /** Made before cards of this size were sold. */
        TOO_OLD_FOR_SIZE,
    }

    /** -1 when not known. */
    int manfid = -1;
    int oemidRaw = -1;
    String oemid;
    String product;
    int year = -1, month = -1;

    /** 0 = SDSC, 1 = SDHC/SDXC, 2 = SDUC; -1 unknown. */
    int csdVersion = -1;
    long csdBytes = -1;

    /** 1 = 1.0x, 2 = 1.10, 3 = 2.00, 4 = 3.0x, 5 = 4.xx, 6.. = 5.xx.. ; 0 unknown. */
    int specLevel;

    /** From the SD status: 0/2/4/6/10, 0/1/3, 0/6/10/30/60/90, 0/1/2; -1 unknown. */
    int speedClass = -1, uhsGrade = -1, videoClass = -1, appClass = -1;

    /** Parses the four registers; any of them may be null or garbage. */
    static CardId parse(String cid, String csd, String scr, String ssr) {
        final CardId id = new CardId();
        cid = hex(cid, 32);
        csd = hex(csd, 32);
        scr = hex(scr, 16);
        ssr = hex(ssr, 128);
        if (cid != null) {
            id.manfid = (int) bits(cid, 127, 120);
            id.oemidRaw = (int) bits(cid, 119, 104);
            id.oemid = ascii(bits(cid, 119, 104), 2);
            id.product = ascii(bits(cid, 103, 64), 5).trim();
            id.year = 2000 + (int) bits(cid, 19, 12);
            id.month = (int) bits(cid, 11, 8);
        }
        if (csd != null) {
            id.csdVersion = (int) bits(csd, 127, 126);
            if (id.csdVersion == 0) {
                final long cSize = bits(csd, 73, 62);
                final long mult = bits(csd, 49, 47);
                final long blockLen = bits(csd, 83, 80);
                id.csdBytes = (cSize + 1) << (mult + 2 + blockLen);
            } else if (id.csdVersion == 1) {
                id.csdBytes = (bits(csd, 69, 48) + 1) * 512 * 1024;
            } else if (id.csdVersion == 2) {
                id.csdBytes = (bits(csd, 75, 48) + 1) * 512 * 1024;
            }
        }
        if (scr != null) {
            final int spec = (int) bits(scr, 59, 56);
            final boolean spec3 = bits(scr, 47, 47) == 1;
            final boolean spec4 = bits(scr, 42, 42) == 1;
            final int specX = (int) bits(scr, 41, 38);
            if (spec < 2) {
                id.specLevel = spec + 1;
            } else if (spec == 2 && !spec3) {
                id.specLevel = 3;
            } else if (spec == 2 && specX > 0) {
                id.specLevel = 5 + specX;
            } else if (spec == 2) {
                id.specLevel = spec4 ? 5 : 4;
            }
        }
        // an all-zero SD status means the kernel didn't read it
        if (ssr != null && !ssr.matches("0+")) {
            final int sc = (int) bits(ssr, 447, 440);
            id.speedClass = sc <= 4 ? new int[] {0, 2, 4, 6, 10}[sc] : -1;
            id.uhsGrade = (int) bits(ssr, 399, 396);
            id.videoClass = (int) bits(ssr, 391, 384);
            id.appClass = (int) bits(ssr, 339, 336);
        }
        return id;
    }

    /** The hex digits of a register of this many digits, null if it isn't one. */
    static String hex(String s, int digits) {
        if (s == null) return null;
        s = s.trim().toLowerCase();
        if (s.startsWith("0x")) s = s.substring(2);
        return s.length() == digits && s.matches("[0-9a-f]+") ? s : null;
    }

    /** Bits hi..lo (inclusive, hi - lo < 63) of a register written as hex, MSB first. */
    static long bits(String hex, int hi, int lo) {
        final int top = hex.length() * 4 - 1;
        long v = 0;
        for (int b = hi; b >= lo; b--) {
            final int digit = Character.digit(hex.charAt((top - b) / 4), 16);
            v = (v << 1) | ((digit >> (b % 4)) & 1);
        }
        return v;
    }

    private static String ascii(long v, int chars) {
        final StringBuilder sb = new StringBuilder();
        for (int i = chars - 1; i >= 0; i--) {
            final int c = (int) (v >> (8 * i)) & 0xff;
            sb.append(c >= 0x20 && c < 0x7f ? (char) c : '?');
        }
        return sb.toString();
    }

    boolean hasId() {
        return manfid >= 0;
    }

    /** What the card is marked with, as printed on it: "Class 10, U3, V30, A2". */
    String marks() {
        final List<String> m = new ArrayList<>();
        if (speedClass > 0) m.add("Class " + speedClass);
        if (uhsGrade > 0) m.add("U" + uhsGrade);
        if (videoClass > 0) m.add("V" + videoClass);
        if (appClass > 0) m.add("A" + appClass);
        return String.join(", ", m);
    }

    /** The least MB/s the card promises for writing, by its marks; 0 if none. */
    int promisedWriteMbps() {
        int mbps = Math.max(0, speedClass);
        if (uhsGrade == 1) mbps = Math.max(mbps, 10);
        if (uhsGrade >= 3) mbps = Math.max(mbps, 30);
        if (videoClass > 0) mbps = Math.max(mbps, videoClass);
        return mbps;
    }

    /** Small random reads and writes per second an A1 (or A2) card promises; 0 if none. */
    int promisedReadIops() {
        return appClass == 1 ? 1500 : appClass >= 2 ? 4000 : 0;
    }

    int promisedWriteIops() {
        return appClass == 1 ? 500 : appClass >= 2 ? 2000 : 0;
    }

    /**
     * Signs of a fake or no-name card. {@code diskBytes} is the size the system uses
     * (0 if not known), {@code nowYear}/{@code nowMonth} today (month 1-12).
     */
    List<Sign> signs(long diskBytes, int nowYear, int nowMonth) {
        final List<Sign> s = new ArrayList<>();
        if (hasId()) {
            if (manfid == 0 || oemidRaw == 0 || !oemid.matches("[0-9A-Za-z]{2}")) {
                s.add(Sign.NO_MAKER);
            } else if ((manfid == 0x03 && !"SD".equals(oemid))
                    || (manfid == 0x1b && !"SM".equals(oemid))
                    || (manfid == 0x02 && !"TM".equals(oemid))) {
                s.add(Sign.BRAND_MISMATCH);
            }
        }
        if (csdBytes > 0 && diskBytes > 0
                && Math.abs(csdBytes - diskBytes) > Math.max(diskBytes / 100, 1L << 20)) {
            s.add(Sign.SIZE_MISMATCH);
        }
        final long size = csdBytes > 0 ? csdBytes : diskBytes;
        if ((csdVersion == 0 && csdBytes > 4L * 1024 * 1024 * 1024)
                || (specLevel > 0 && specLevel < 4 && size > 34 * GB)) {
            s.add(Sign.SPEC_TOO_OLD);
        }
        if (hasId()) {
            if (month < 1 || month > 12 || year > nowYear
                    || (year == nowYear && month > nowMonth + 1)) {
                s.add(Sign.BAD_DATE);
            } else if (year < oldestYearFor(size)) {
                s.add(Sign.TOO_OLD_FOR_SIZE);
            }
        }
        return s;
    }

    /**
     * A year before which no real microSD card of this size was made. The first ones: 64 GB
     * 2011, 128 GB 2014, 256 GB 2016, 512 GB 2017, 1 TB 2019; the limits are a year or so
     * earlier, so only clearly wrong dates count. SDXC (over 32 GB) came in 2009/2010.
     */
    static int oldestYearFor(long bytes) {
        if (bytes > 520 * GB) return 2018;
        if (bytes > 260 * GB) return 2017;
        if (bytes > 130 * GB) return 2015;
        if (bytes > 66 * GB) return 2013;
        if (bytes > 34 * GB) return 2010;
        return 0;
    }
}
