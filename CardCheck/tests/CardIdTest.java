package org.andr36oid.cardcheck;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host test of the card ID parsing (CardId), the numbers from the helper (Stats) and the
 * advice (Verdict). Registers are built field by field with the SD spec's bit numbers
 * (reg()), plus one real CSD whose size was worked out separately.
 *
 *   tests/run-tests.sh
 */
public class CardIdTest {

    static int failures, checks;

    static void eq(String what, Object got, Object want) {
        checks++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            System.out.println("FAIL " + what + ": got " + got + ", want " + want);
            failures++;
        }
    }

    /** A register of {@code bits} bits as hex; fields are {hi, lo, value} triples. */
    static String reg(int bits, long... f) {
        BigInteger v = BigInteger.ZERO;
        for (int i = 0; i < f.length; i += 3) {
            final int lo = (int) f[i + 1];
            v = v.or(BigInteger.valueOf(f[i + 2]).shiftLeft(lo));
        }
        String s = v.toString(16);
        while (s.length() < bits / 4) s = "0" + s;
        return s;
    }

    static long ascii(String s) {
        long v = 0;
        for (char c : s.toCharArray()) v = (v << 8) | c;
        return v;
    }

    /** CID: maker, OEM, name, made year/month. */
    static String cid(int mid, String oid, String pnm, int year, int month) {
        return reg(128, 127, 120, mid, 119, 104, ascii(oid), 103, 64, ascii(pnm),
                63, 56, 0x80, 55, 24, 0x12345678L, 19, 12, year - 2000, 11, 8, month, 0, 0, 1);
    }

    /** CSD version 2 (SDHC/SDXC) of this many 512 KB units. */
    static String csd2(long units) {
        return reg(128, 127, 126, 1, 103, 96, 0x32, 69, 48, units - 1, 0, 0, 1);
    }

    /** SCR: SD_SPEC, SD_SPEC3, SD_SPEC4, SD_SPECX. */
    static String scr(int spec, int spec3, int spec4, int specX) {
        return reg(64, 59, 56, spec, 51, 48, 5, 47, 47, spec3, 42, 42, spec4, 41, 38, specX);
    }

    /** SD status: speed class code, UHS grade, video class, app class. */
    static String ssr(int classCode, int uhs, int video, int app) {
        return reg(512, 511, 510, 2, 447, 440, classCode, 431, 428, 9, 399, 396, uhs,
                391, 384, video, 339, 336, app);
    }

    static final long GB = CardId.GB;
    static final long SIZE64 = 121_896L; // units of 512 KB: a real 64 GB card, 63.9 GB

    public static void main(String[] args) {
        parse();
        signs();
        stats();
        advice();
        System.out.println("CardIdTest: " + checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    static void parse() {
        // a real SDHC CSD as the kernel prints it (32 GB, 25 MHz)
        CardId id = CardId.parse(null, "400e00325b590000ee7f7f800a404000", null, null);
        eq("real csd version", id.csdVersion, 1);
        eq("real csd size", id.csdBytes, 32010928128L);
        eq("bits of the real csd: TRAN_SPEED", CardId.bits("400e00325b590000ee7f7f800a404000",
                103, 96), 0x32);

        id = CardId.parse(cid(0x03, "SD", "SN64G", 2021, 3), csd2(SIZE64),
                scr(2, 1, 1, 2), ssr(4, 3, 30, 2));
        eq("manfid", id.manfid, 3);
        eq("oemid", id.oemid, "SD");
        eq("product", id.product, "SN64G");
        eq("date", id.month + "/" + id.year, "3/2021");
        eq("csd size", id.csdBytes, SIZE64 * 512 * 1024);
        eq("spec 6.x", id.specLevel, 7);
        eq("speed class", id.speedClass, 10);
        eq("marks", id.marks(), "Class 10, U3, V30, A2");
        eq("promised write", id.promisedWriteMbps(), 30);
        eq("promised iops", id.promisedReadIops() + "/" + id.promisedWriteIops(), "4000/2000");

        id = CardId.parse(null, null, scr(2, 1, 0, 0), ssr(2, 0, 0, 0));
        eq("spec 3.0", id.specLevel, 4);
        eq("class 4 only", id.marks(), "Class 4");
        eq("class 4 promise", id.promisedWriteMbps(), 4);
        eq("spec 2.0", CardId.parse(null, null, scr(2, 0, 0, 0), null).specLevel, 3);
        eq("spec 1.10", CardId.parse(null, null, scr(1, 0, 0, 0), null).specLevel, 2);
        eq("spec 4.x", CardId.parse(null, null, scr(2, 1, 1, 0), null).specLevel, 5);

        // kernel didn't read the SD status: all zero
        id = CardId.parse(null, null, null, reg(512));
        eq("zero ssr", id.speedClass + " " + id.marks(), "-1 ");
        // garbage and 0x prefixes
        eq("short cid", CardId.parse("1234", null, null, null).manfid, -1);
        eq("0x csd", CardId.parse(null, "0x400e00325b590000ee7f7f800a404000", null, null)
                .csdBytes, 32010928128L);
        eq("bad hex", CardId.parse("zz" + cid(3, "SD", "X", 2020, 1).substring(2), null,
                null, null).manfid, -1);

        // CSD version 1: 2 GB card, C_SIZE 4095? READ_BL_LEN 10, C_SIZE_MULT 7
        id = CardId.parse(null, reg(128, 83, 80, 10, 73, 62, 3775, 49, 47, 7), null, null);
        eq("csd v1", id.csdVersion + " " + id.csdBytes, "0 " + (3776L * 512 * 1024));
    }

    static void signs() {
        final long disk64 = SIZE64 * 512 * 1024;
        CardId good = CardId.parse(cid(0x03, "SD", "SN64G", 2021, 3), csd2(SIZE64),
                scr(2, 1, 1, 2), ssr(4, 3, 30, 2));
        eq("good card", good.signs(disk64, 2026, 9), "[]");

        eq("no maker", CardId.parse(cid(0, "\0\0", "SD128", 2023, 5), csd2(SIZE64 * 2),
                null, null).signs(disk64 * 2, 2026, 9), "[NO_MAKER]");
        eq("odd oem", CardId.parse(cid(0x12, "4\u0001", "ASTC", 2023, 5), csd2(SIZE64),
                null, null).signs(disk64, 2026, 9), "[NO_MAKER]");
        eq("fake SanDisk", CardId.parse(cid(0x03, "PH", "SD64G", 2022, 1), csd2(SIZE64),
                null, null).signs(disk64, 2026, 9), "[BRAND_MISMATCH]");
        eq("fake Samsung", CardId.parse(cid(0x1b, "SD", "EVO", 2022, 1), csd2(SIZE64),
                null, null).signs(disk64, 2026, 9), "[BRAND_MISMATCH]");
        eq("real Kingston by Phison", CardId.parse(cid(0x27, "PH", "SD64G", 2022, 1),
                csd2(SIZE64), null, null).signs(disk64, 2026, 9), "[]");
        eq("size mismatch", good.signs(disk64 / 2, 2026, 9), "[SIZE_MISMATCH]");
        eq("size within 1%", good.signs(disk64 - disk64 / 200, 2026, 9), "[]");
        eq("128 GB claiming SD 2.0", CardId.parse(cid(0x9f, "TI", "SD128", 2022, 1),
                csd2(SIZE64 * 2), scr(2, 0, 0, 0), null).signs(0, 2026, 9), "[SPEC_TOO_OLD]");
        eq("month 0", CardId.parse(cid(0x03, "SD", "SN64G", 2020, 0), csd2(SIZE64),
                null, null).signs(disk64, 2026, 9), "[BAD_DATE]");
        eq("future", CardId.parse(cid(0x03, "SD", "SN64G", 2027, 1), csd2(SIZE64),
                null, null).signs(disk64, 2026, 9), "[BAD_DATE]");
        eq("next month is fine", CardId.parse(cid(0x03, "SD", "SN64G", 2026, 10),
                csd2(SIZE64), null, null).signs(disk64, 2026, 9), "[]");
        eq("1 TB from 2012", CardId.parse(cid(0x03, "SD", "SN1T", 2012, 6),
                csd2(SIZE64 * 16), null, null).signs(disk64 * 16, 2026, 9),
                "[TOO_OLD_FOR_SIZE]");
        eq("16 GB from 2012", CardId.parse(cid(0x03, "SD", "SL16G", 2012, 6),
                csd2(SIZE64 / 4), null, null).signs(disk64 / 4, 2026, 9), "[]");
        eq("usb drive", new CardId().signs(disk64, 2026, 9), "[]");
    }

    static Map<String, String> speed(String w, String r, String rr, String rw, String bus) {
        final Map<String, String> m = new HashMap<>();
        m.put("state", "done");
        m.put("size_mb", "256");
        m.put("w0", "100.000000000");
        m.put("w1", w);
        m.put("r0", "200.5");
        m.put("r1", r);
        if (rr != null) {
            m.put("rr_ops", rr);
            m.put("rr_s", "8.000");
            m.put("rw_ops", rw);
            m.put("rw_s", "8.000");
        }
        if (bus != null) m.put("bus", bus);
        return m;
    }

    static void stats() {
        final Map<String, String> s = speed("112.8", "205.62", "16000", "4000", null);
        eq("write MB/s", Math.round(Stats.speed(s, 256, "w0", "w1") * 10), 200);
        eq("read MB/s", Math.round(Stats.speed(s, 256, "r0", "r1") * 10), 500);
        eq("read iops", Stats.iops(s, "rr"), 2000.0);
        eq("write iops", Stats.iops(s, "rw"), 500.0);
        eq("no iops", Stats.iops(new HashMap<>(), "rr"), Double.NaN);
        eq("level good", Verdict.level(s), Verdict.GOOD);
        eq("level without small blocks", Verdict.level(speed("112.8", "205.62", null, null,
                null)), Verdict.GOOD);
        eq("level slow small writes", Verdict.level(speed("112.8", "205.62", "16000", "200",
                null)), Verdict.SLOW);
        eq("level ok", Verdict.level(speed("140", "212", "8000", "800", null)), Verdict.OK);
        eq("level slow seq", Verdict.level(speed("300", "260", "16000", "4000", null)),
                Verdict.SLOW);
        eq("level not done", Verdict.level(new HashMap<>()), Verdict.UNKNOWN);

        eq("bus sdr104", Verdict.Bus.of("ultra_high_speed_SDR104_SDXC"), "SDR104");
        eq("bus ddr50", Verdict.Bus.of("ultra_high_speed_DDR50_SDHC"), "DDR50");
        eq("bus high", Verdict.Bus.of("high_speed_SDXC"), "HIGH");
        eq("bus default", Verdict.Bus.of("SDHC"), "DEFAULT");
        eq("bus none", Verdict.Bus.of(""), "UNKNOWN");
        eq("bus junk", Verdict.Bus.of("x y"), "UNKNOWN");
    }

    static String advise(CardId id, Map<String, String> speed, Map<String, String> full,
            boolean paused) {
        final List<Verdict.Item> items = Verdict.advise(id, SIZE64 * 512 * 1024, speed, full,
                paused, 2026, 9);
        final StringBuilder sb = new StringBuilder();
        for (Verdict.Item i : items) sb.append(sb.length() > 0 ? " " : "").append(i);
        return sb.toString();
    }

    static void advice() {
        final Map<String, String> none = new HashMap<>();
        final CardId a2 = CardId.parse(cid(0x03, "SD", "SN64G", 2021, 3), csd2(SIZE64),
                scr(2, 1, 1, 2), ssr(4, 3, 30, 2));
        final CardId plain = CardId.parse(cid(0x9f, "TI", "SD64G", 2021, 3), csd2(SIZE64),
                scr(2, 1, 0, 0), ssr(4, 1, 0, 0));
        final CardId fake = CardId.parse(cid(0, "\0\0", "SD64G", 2021, 3), csd2(SIZE64),
                scr(2, 1, 0, 0), ssr(4, 3, 30, 1));
        final Map<String, String> fast = speed("112.8", "205.62", "20000", "6000",
                "ultra_high_speed_SDR104_SDXC");
        final Map<String, String> slow = speed("200", "218", "3000", "200", "high_speed_SDXC");

        eq("untested", advise(a2, none, none, false), "UNTESTED");
        eq("good", advise(a2, fast, none, false), "GOOD");
        eq("usb drive, good", advise(null, fast, none, false), "GOOD");
        eq("paused", advise(a2, fast, none, true), "PAUSED");
        Map<String, String> full = new HashMap<>();
        full.put("state", "done");
        full.put("result", "wraps");
        eq("fake by size check", advise(a2, fast, full, true), "PAUSED FAKE");
        full.put("result", "write_error");
        eq("write error", advise(a2, fast, full, false), "WRITE_ERROR");
        full.put("result", "pass");
        eq("signs but real", advise(fake, fast, full, false), "SIGNS_BUT_REAL[NO_MAKER]");
        eq("signs", advise(fake, fast, none, false), "SIGNS[NO_MAKER]");
        // U3 A2 card writing 2.56 MB/s, 25 small reads/s: not what it says
        eq("claims", advise(a2, slow, none, false),
                "CLAIM_WRITE(30.0,2.56) CLAIM_SMALL(2.0,0.0) BUS_SLOW(25.0,0.0) SLOW");
        eq("plain U1 card, slow", advise(plain, slow, none, false),
                "CLAIM_WRITE(10.0,2.56) BUS_SLOW(25.0,0.0) SLOW");
        eq("fake A1 U3", advise(fake, slow, none, false),
                "SIGNS[NO_MAKER] CLAIM_WRITE(30.0,2.56) CLAIM_SMALL(1.0,0.0) BUS_SLOW(25.0,0.0) SLOW");
        eq("no bus line", advise(plain, speed("112.8", "205.62", "20000", "6000", ""), none,
                false), "GOOD");
        eq("list", Arrays.asList(Verdict.Kind.values()).size(), 11);
    }
}
