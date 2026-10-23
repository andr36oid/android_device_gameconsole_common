package org.andr36oid.usbmode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class VirtualDriveTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("virtual drive check " + checks);
    }

    public static void main(String[] args) throws Exception {
        for (String name : new String[] {"Drive", "A", "16GB drive", "one-two_3.4"}) check(VirtualDrive.name(name));
        for (String name : new String[] {"", "../x", "/x", "x/../y", "a..b", "x;id", "x\n", "x\\y", " x", "x ", "x."}) check(!VirtualDrive.name(name));
        check(VirtualDrive.name("a".repeat(87)));
        check(!VirtualDrive.name("a".repeat(88)));
        for (String size : new String[] {"1", "4", "8", "16", "32", "64", "1024"}) check(VirtualDrive.size(size) == Long.parseLong(size) * VirtualDrive.GB);
        for (String size : new String[] {"", "0", "01", "-1", "1.5", "1025", "9999999999999999", "1;id"}) check(VirtualDrive.size(size) == 0);
        for (String fs : VirtualDrive.FILESYSTEMS) {
            check(VirtualDrive.label("USB DRIVE", fs));
            check(VirtualDrive.label("", fs));
            check(!VirtualDrive.label("x/y", fs));
        }
        check(!VirtualDrive.label("a".repeat(12), "fat32"));
        check(!VirtualDrive.label("a".repeat(16), "exfat"));
        check(!VirtualDrive.label("a".repeat(33), "ntfs"));
        check(!VirtualDrive.label("a".repeat(17), "ext4"));
        check(!VirtualDrive.label("", "bad"));
        check(VirtualDrive.maximum(17 * VirtualDrive.GB, "exfat") == 16 * VirtualDrive.GB);
        check(VirtualDrive.maximum(17 * VirtualDrive.GB, "vfat") == 3 * VirtualDrive.GB);
        check(VirtualDrive.maximum(VirtualDrive.GB - 1, "exfat") == 0);
        check(VirtualDrive.maximum(2 * VirtualDrive.GB - 1, "exfat") == 0);
        check(VirtualDrive.maximum(Long.MAX_VALUE, "exfat") == 1024 * VirtualDrive.GB);
        File root = new File(args[0], "virtual-inventory"); root.mkdirs();
        File inventory = new File(root, "images");
        String cid = "0123456789abcdef0123456789abcdef";
        Files.write(inventory.toPath(), ("ready\t" + cid + "\t0\nfile\t1:2:17179869184\tDrive.img\texfat\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "space").toPath(), "18253611008\texfat\t0\n".getBytes(StandardCharsets.UTF_8));
        UsbDrive.Images images = UsbDrive.images(inventory);
        check(images.mounted && images.writable && images.cid.equals(cid));
        check(images.free == 18253611008L && images.filesystem.equals("exfat"));
        check(images.offers.size() == 1 && images.offers.get(0).type.equals("exfat"));
        check(!images.offers.get(0).defaultCdrom() && !images.offers.get(0).forcedRo);
        System.out.println(checks + " virtual drive checks passed");
    }
}
