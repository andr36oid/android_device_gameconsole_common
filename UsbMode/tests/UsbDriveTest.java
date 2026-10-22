package org.andr36oid.usbmode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real sysfs links, mount device numbers and fstab aliases in a temporary tree. */
public class UsbDriveTest {
    private static File sRoot;
    private static int sPass;
    private static final List<UsbDrive.Volume> VOLUMES = new ArrayList<>();

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        sPass++;
    }

    private static Path path(String p) { return new File(sRoot, p).toPath(); }

    private static void write(String p, String value) throws Exception {
        if (!Files.isDirectory(path(p).getParent())) Files.createDirectories(path(p).getParent());
        Files.write(path(p), value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void link(String p, String target) throws Exception {
        if (!Files.isDirectory(path(p).getParent())) Files.createDirectories(path(p).getParent());
        Files.deleteIfExists(path(p));
        Files.createSymbolicLink(path(p), path(target));
    }

    private static void disk(String name, String controller, int minor) throws Exception {
        final String dir = "sys/devices/platform/" + controller + "/mmc_host/mmc0/block/" + name;
        Files.createDirectories(path(dir));
        link("sys/class/block/" + name, dir);
        write("sys/class/block/" + name + "/dev", "179:" + minor);
        write("sys/class/block/" + name + "/size", "33554432");
        write("sys/class/block/" + name + "/device/cid", "0123456789abcdef0123456789abcdef");
        for (int p : new int[] {2, 5, 6, 7}) {
            link("sys/class/block/" + name + "p" + p, dir + "/" + name + "p" + p);
            write(dir + "/" + name + "p" + p + "/dev", "179:" + (minor + p));
            write(dir + "/" + name + "p" + p + "/partition", Integer.toString(p));
            write(dir + "/" + name + "p" + p + "/size", "16777216");
        }
    }

    private static void install(String system, String data) throws Exception {
        write("proc/self/mountinfo", "1 0 179:" + minor(system, 2) + " / / ro - ext4 /dev/root ro\n"
                + "2 1 179:" + minor(data, 6) + " / /data rw - f2fs /dev/block/alias rw\n");
        link("dev/block/system", "sys/class/block/" + system + "p2");
        link("dev/block/a36data", "sys/class/block/" + data + "p6");
        write("vendor/etc/fstab.active", path("dev/block/system") + " /system ext4 ro wait\n");
    }

    private static int minor(String disk, int part) {
        return Integer.parseInt(disk.substring(6)) * 32 + part;
    }

    private static List<UsbDrive.Offer> offers(String easy) {
        return new UsbDrive(sRoot, VOLUMES).scan("/vendor/etc/fstab.active", easy);
    }

    public static void main(String[] args) throws Exception {
        sRoot = new File(args[0], "drive");
        disk("mmcblk0", "ff370000.dwmmc", 0);
        disk("mmcblk1", "ff380000.dwmmc", 32);
        disk("mmcblk2", "ff390000.dwmmc", 64);
        write("sys/class/block/mmcblk2/andr36oid_unlock", "locked");
        Files.createDirectories(path("sys/class/block/mmcblk2boot0"));
        Files.createDirectories(path("sys/class/block/mmcblk2boot1"));
        VOLUMES.add(new UsbDrive.Volume("public:179,39", "mmcblk1", "Games", "exfat", false));
        install("mmcblk0", "mmcblk0");
        List<UsbDrive.Offer> rows = offers("mmcblk0p7");
        check("SD: three rows", rows.size() == 3);
        check("SD: only EASYROMS of Android card", rows.get(0).kind == UsbDrive.Kind.EASYROMS
                && rows.get(0).node.equals("mmcblk0p7") && rows.get(0).enabled());
        check("SD: whole slot 2 with its label and type", rows.get(1).kind == UsbDrive.Kind.CARD
                && rows.get(1).node.equals("mmcblk1") && rows.get(1).label.equals("Games")
                && rows.get(1).type.equals("exfat"));
        check("SD: writable eMMC offer", rows.get(2).enabled() && !rows.get(2).forcedRo);
        check("partition size used", rows.get(0).bytes == 8589934592L);
        check("whole disk size used", rows.get(1).bytes == 17179869184L);
        check("missing EASYROMS stays disabled", offers("").get(0).reason == UsbDrive.Reason.SYSTEM);
        install("mmcblk2", "mmcblk2");
        rows = offers("");
        check("eMMC: both SD slots are whole cards", rows.get(0).kind == UsbDrive.Kind.CARD
                && rows.get(1).kind == UsbDrive.Kind.CARD);
        check("eMMC: forced read-only", rows.get(2).enabled() && rows.get(2).forcedRo);
        for (String data : new String[] {"mmcblk0", "mmcblk1"}) {
            install("mmcblk2", data);
            rows = offers(data + "p7");
            final int slot = data.equals("mmcblk0") ? 0 : 1;
            check("data card in either slot: partition only " + data,
                    rows.get(slot).kind == UsbDrive.Kind.EASYROMS
                    && rows.get(slot).node.equals(data + "p7") && rows.get(slot).enabled());
            check("data card in either slot: other whole card " + data,
                    rows.get(1 - slot).kind == UsbDrive.Kind.CARD);
            check("data card in either slot: system eMMC stays ro " + data, rows.get(2).forcedRo);
        }
        VOLUMES.add(new UsbDrive.Volume("private:179,39", "mmcblk1", "", "", true));
        check("internal-storage card dimmed", offers("mmcblk1p7").get(1).reason == UsbDrive.Reason.INTERNAL);
        VOLUMES.remove(VOLUMES.size() - 1);
        install("mmcblk0", "mmcblk0");
        write("sys/class/block/mmcblk1p7/holders/dm-0", "");
        check("mapped storage dimmed", offers("mmcblk0p7").get(1).reason == UsbDrive.Reason.BUSY);
        Files.delete(path("sys/class/block/mmcblk1p7/holders/dm-0"));
        write("sys/class/block/mmcblk1p6/uevent", "PARTNAME=userdata\n");
        check("inactive Android card is never a whole-disk offer",
                offers("mmcblk0p7").get(1).reason == UsbDrive.Reason.ANDROID);
        write("sys/class/block/mmcblk1p6/uevent", "PARTNAME=android_expand\n");
        check("adopted partition without a vold volume stays disabled",
                offers("mmcblk0p7").get(1).reason == UsbDrive.Reason.INTERNAL);
        Files.delete(path("sys/class/block/mmcblk1p6/uevent"));
        final String oldMounts = Files.readString(path("proc/self/mountinfo"));
        write("proc/self/mountinfo", oldMounts + "3 1 179:39 / /mnt/card rw - exfat /dev/block/mmcblk1p7 rw\n");
        check("public mounts can be ejected", offers("mmcblk0p7").get(1).enabled());
        VOLUMES.clear();
        check("mounts outside vold are disabled", offers("mmcblk0p7").get(1).reason == UsbDrive.Reason.BUSY);
        write("proc/self/mountinfo", oldMounts);
        check("unmountable card still offered", offers("mmcblk0p7").get(1).enabled());
        write("sys/class/block/mmcblk1/size", "0");
        check("unusable slot dimmed", offers("mmcblk0p7").get(1).reason == UsbDrive.Reason.UNUSABLE);
        write("sys/class/block/mmcblk1/size", "33554432");
        Files.delete(path("sys/class/block/mmcblk2/andr36oid_unlock"));
        check("eMMC off is absent", offers("mmcblk0p7").size() == 2);
        Files.delete(path("sys/class/block/mmcblk1"));
        check("empty slot absent", offers("mmcblk0p7").size() == 1);
        write("proc/self/mountinfo", "");
        write("vendor/etc/fstab.active", "/unknown /system ext4 ro wait\n");
        check("unknown install fails closed", offers("mmcblk0p7").get(0).reason == UsbDrive.Reason.UNKNOWN);
        // A fstab using real by-name links is enough when app mount namespaces hide mounts.
        link("dev/block/platform/ff370000.dwmmc/by-name/system", "sys/class/block/mmcblk0p2");
        link("dev/block/by-name/a36data", "sys/class/block/mmcblk0p6");
        write("vendor/etc/fstab.active", "/dev/block/platform/ff370000.dwmmc/by-name/system /system ext4 ro wait\n"
                + "/dev/block/by-name/a36data /data f2fs rw wait\n");
        // Absolute Android paths are rooted in the fake tree by the scanner.
        check("fstab aliases protect Android card", offers("mmcblk0p7").get(0).enabled());
        check("no partition prefix collisions", !UsbDrive.belongs("mmcblk10p7", "mmcblk1"));
        check("partition scope stays narrow", !UsbDrive.belongs("mmcblk0p6", "mmcblk0p7"));
        sRoot = new File(args[0], "renumbered-drive");
        disk("mmcblk4", "ff370000.dwmmc", 128);
        disk("mmcblk3", "ff380000.dwmmc", 96);
        install("mmcblk4", "mmcblk4");
        rows = offers("mmcblk4p7");
        check("slot 1 comes from controller, not block number", rows.get(0).slot == 1
                && rows.get(0).node.equals("mmcblk4p7"));
        check("slot 2 comes from controller, not block number", rows.get(1).slot == 2
                && rows.get(1).node.equals("mmcblk3"));
        System.out.println(sPass + " USB drive checks passed");
    }
}
