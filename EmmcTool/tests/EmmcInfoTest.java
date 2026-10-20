package org.andr36oid.emmc;

/** Host test of EmmcInfo: run by tests/run-tests.sh. */
public final class EmmcInfoTest {

    private static int sChecks, sFails;

    private static void ok(String what, Object got, Object want) {
        sChecks++;
        if (got == null ? want != null : !got.equals(want)) {
            System.out.println("FAIL " + what + ": got '" + got + "', want '" + want + "'");
            sFails++;
        }
    }

    public static void main(String[] args) {
        final EmmcInfo info = EmmcInfo.parse("state=ok\n"
                + "disk=mmcblk2\n"
                + "sectors=15269888\n"
                + "name=8GTF4R\n"
                + "manfid=0x000015\n"
                + "rev=0x8\n"
                + "life_time=0x01 0x02\n"
                + "pre_eol_info=0x01\n"
                + "serial=\n"
                + "part=mmcblk2p1|16384|8192|uboot||\n"
                + "part=mmcblk2p7|409600|14860288|rootfs|ext4|STORAGE\n"
                + "part=broken\n"
                + "boot=mmcblk2boot0|8192\n"
                + "boot=mmcblk2boot1|8192\n");
        ok("present", info.present, true);
        ok("disk", info.disk, "mmcblk2");
        ok("bytes", info.bytes(), 15269888L * 512);
        ok("whole", info.wholeBytes(), (15269888L + 16384) * 512);
        ok("name", info.field("name"), "8GTF4R");
        ok("empty field", info.field("serial"), null);
        ok("parts", info.parts.size(), 2);
        ok("p7 number", info.parts.get(1).number, 7);
        ok("p7 name", info.parts.get(1).name, "rootfs");
        ok("p7 type", info.parts.get(1).type, "ext4");
        ok("p7 label", info.parts.get(1).label, "STORAGE");
        ok("p1 type", info.parts.get(0).type, "");
        ok("boots", info.boots.size(), 2);
        ok("boot1 number", info.boots.get(1).number, 1);
        ok("boot0 bytes", info.boots.get(0).bytes(), 8192L * 512);

        ok("none", EmmcInfo.parse("state=none\n").present, false);
        ok("empty", EmmcInfo.parse("").present, false);

        ok("maker samsung", EmmcInfo.maker("0x000015"), "Samsung");
        ok("maker hynix", EmmcInfo.maker("0x000090"), "SK hynix");
        ok("maker unknown", EmmcInfo.maker("0x0000aa"), null);
        ok("maker junk", EmmcInfo.maker("x"), null);
        ok("version 5.1", EmmcInfo.version("0x8"), "5.1");
        ok("version 4.41", EmmcInfo.version("0x5"), "4.41");
        ok("version unknown", EmmcInfo.version("0x4"), null);
        ok("life", EmmcInfo.lifeUsed("0x01 0x02"), 2);
        ok("life past", EmmcInfo.lifeUsed("0x0b 0x01"), 11);
        ok("life not said", EmmcInfo.lifeUsed("0x00 0x00"), 0);
        ok("life missing", EmmcInfo.lifeUsed(null), 0);
        ok("eol normal", EmmcInfo.preEol("0x01"), 1);
        ok("eol urgent", EmmcInfo.preEol("0x03"), 3);
        ok("eol not said", EmmcInfo.preEol("0x00"), 0);

        System.out.println("EmmcInfoTest: " + sChecks + " checks, " + sFails + " failed");
        if (sFails > 0) System.exit(1);
    }
}
