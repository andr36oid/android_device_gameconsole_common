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
            check(VirtualDrive.label("", fs) == !fs.equals("fat32"));
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
        check(!VirtualDrive.label(" IMAGE", "fat32"));
        for (String fs : VirtualDrive.FILESYSTEMS) {
            for (String name : new String[] {"image1", "Long name.with punctuation", "", "éà---"})
                check(VirtualDrive.label(VirtualDrive.defaultLabel(name, fs), fs));
        }
        check(VirtualDrive.defaultLabel("image1", "fat32").equals("IMAGE1"));
        check(VirtualDrive.defaultLabel("a very long image name", "fat32").length() == 11);
        check(VirtualDrive.nextName(java.util.Arrays.asList("image1.img", "IMAGE2.ISO", "other.img")).equals("image3"));
        check(VirtualDrive.nextName(java.util.Arrays.asList("image2.img")).equals("image1"));
        check(VirtualDrive.presets(128 * VirtualDrive.GB, "exfat").equals(java.util.Arrays.asList("1","2","4","8","16","32","64")));
        check(VirtualDrive.presets(5 * VirtualDrive.GB, "vfat").equals(java.util.Arrays.asList("1","2")));
        check(VirtualDrive.presets(VirtualDrive.GB, "exfat").isEmpty());
        check(VirtualDrive.clampSize("1024", 128 * VirtualDrive.GB, "exfat").equals("127"));
        check(VirtualDrive.clampSize("99999999999999999999", 4 * VirtualDrive.GB, "exfat").equals("3"));
        check(VirtualDrive.clampSize("2", 128 * VirtualDrive.GB, "exfat").equals("2"));
        check(!VirtualDrive.presetSelected("2", "4"));
        check(VirtualDrive.presetSelected("4", "4"));
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
