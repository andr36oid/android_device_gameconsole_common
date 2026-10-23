package org.andr36oid.usbmode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The root inventory is the only file listing the settings app can read. */
public class UsbDriveImagesTest {
    private static int sPass;
    private static File sInventory;
    private static final String HEADER = "ready\t0123456789abcdef0123456789abcdef\t0\n";

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        sPass++;
    }

    private static UsbDrive.Images inventory(String text) throws Exception {
        Files.write(sInventory.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return UsbDrive.images(sInventory);
    }

    public static void main(String[] args) throws Exception {
        sInventory = new File(args[0], "images");
        check("missing inventory is unavailable", !UsbDrive.images(sInventory).mounted);
        check("unmounted EASYROMS is unavailable", !inventory("unavailable\n").mounted);
        check("empty mounted directory", inventory(HEADER).mounted
                && inventory(HEADER).offers.isEmpty());
        UsbDrive.Images images = inventory(HEADER
                + "file\t10:20:5368709120\tzeta.img\n"
                + "file\t10:21:1048576\tAlpine Linux.iSo\n"
                + "file\t10:22:614400\tother.IMG\n");
        check("lists ISO and IMG files", images.offers.size() == 3);
        final UsbDrive.Offer iso = images.offers.get(0);
        check("sorted by name", iso.label.equals("Alpine Linux.iSo"));
        check("ISO defaults to CD regardless of extension case", iso.defaultCdrom());
        check("IMG defaults to USB stick", !images.offers.get(1).defaultCdrom());
        check("name and size preserved", iso.node.equals(iso.label) && iso.bytes == 1048576);
        check("size over 4 GB is preserved", images.offers.get(2).bytes == 5368709120L);
        check("large images cannot use truncated CD mode", !images.offers.get(2).cdromPossible());
        check("valid CD image", iso.cdromPossible());
        check("identity contains stat token", iso.identity().endsWith(":10:21:1048576"));
        check("writable EASYROMS permits switch", !iso.forcedRo);
        images = inventory(HEADER.replace("\t0\n", "\t1\n")
                + "file\t10:21:1048576\tAlpine.iso\n");
        check("read-only EASYROMS fixes switch", images.offers.get(0).forcedRo);
        for (String name : new String[] {"../outside.iso", "/tmp/out.iso", "sub/file.iso",
                "bad..iso", ".hidden.iso", "bad;name.iso", "bad\tname.iso", "bad\nname.iso",
                "bad.iso.exe", "bad.iso ", "badé.iso", "a".repeat(88) + ".iso"}) {
            check("reject unsafe name: " + name, inventory(HEADER
                    + "file\t10:21:1048576\t" + name + "\n").offers.isEmpty());
        }
        check("91 ASCII bytes fit a property", inventory(HEADER
                + "file\t10:21:1048576\t" + "a".repeat(87) + ".iso\n").offers.size() == 1);
        for (String stamp : new String[] {"10:21:0", "10:21:-1", "10:21:99999999999999999999",
                "bad", "10:21:abc"}) {
            check("reject bad identity/size: " + stamp, inventory(HEADER
                    + "file\t" + stamp + "\tAlpine.iso\n").offers.isEmpty());
        }
        check("bad header fails closed", !inventory("ready\tbad\t0\n").mounted);
        check("bad read-only flag fails closed", !inventory(HEADER.replace("\t0\n", "\t2\n")).mounted);
        for (long size : new long[] {512, 614401, 2359296000L}) {
            iso.bytes = size;
            check("CD size refused: " + size, !iso.cdromPossible());
        }
        iso.bytes = 2359293952L;
        check("last complete CD block is supported", iso.cdromPossible());
        for (String extension : new String[] {"img","ima","vfd"}) for (long size : new long[] {737280,1474560,2949120}) {
            UsbDrive.Offer floppy = inventory(HEADER + "file\t10:40:" + size + "\tBoot." + extension + "\n").offers.get(0);
            check("floppy detected " + extension + size,floppy.floppy() && !floppy.cdromPossible());
        }
        for (String extension : new String[] {"ima","vfd"}) check("wrong floppy size", inventory(HEADER + "file\t10:40:1474561\tBoot." + extension + "\n").offers.isEmpty());
        final String old = iso.identity();
        iso.stamp = "10:23:1048576";
        check("replacement file changes identity", !iso.identity().equals(old));
        System.out.println(sPass + " disc image checks passed");
    }
}
