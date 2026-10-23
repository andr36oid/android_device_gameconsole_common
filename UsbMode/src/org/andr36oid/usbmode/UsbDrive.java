package org.andr36oid.usbmode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Storage offers from the active mounts and fstab, with no Android dependencies. */
final class UsbDrive {
    enum Kind { EASYROMS, CARD, EMMC, IMAGE }
    enum Reason { NONE, INTERNAL, SYSTEM, ANDROID, UNKNOWN, BUSY, UNUSABLE }

    static final class Volume {
        String id, disk, label, type;
        boolean internal;

        Volume(String id, String disk, String label, String type, boolean internal) {
            this.id = id;
            this.disk = disk;
            this.label = label;
            this.type = type;
            this.internal = internal;
        }
    }

    static final class Offer {
        Kind kind;
        Reason reason = Reason.NONE;
        String disk, node, cid, label = "", type = "", stamp = "";
        int slot;
        long bytes;
        boolean forcedRo;

        boolean enabled() { return reason == Reason.NONE; }
        String identity() { return node + ":" + cid + (kind == Kind.IMAGE ? ":" + stamp : ""); }
        boolean defaultCdrom() { return kind == Kind.IMAGE
                && node.regionMatches(true, node.length() - 4, ".iso", 0, 4); }
        boolean floppy() { return kind == Kind.IMAGE && node.matches("(?i).*\\.(img|ima|vfd)")
                && (bytes == 737280 || bytes == 1474560 || bytes == 2949120); }
        boolean cdromPossible() { return !floppy() && bytes >= 614400 && bytes <= 2359293952L
                && bytes % 2048 == 0; }
    }

    static final class Images {
        boolean mounted;
        final List<Offer> offers = new ArrayList<>();
    }

    /** Root publishes basenames and stat identities; the app cannot read media_rw. */
    static Images images(File inventory) {
        final Images result = new Images();
        try {
            final List<String> lines = Files.readAllLines(inventory.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) return result;
            final String[] header = lines.get(0).split("\t", -1);
            if (header.length != 3 || !header[0].equals("ready")
                    || !header[1].matches("[0-9a-fA-F]{32}") || !header[2].matches("[01]")) {
                return result;
            }
            result.mounted = true;
            for (int i = 1; i < lines.size(); i++) {
                final String[] f = lines.get(i).split("\t", -1);
                if (f.length != 3 || !f[0].equals("file") || !f[1].matches("[0-9]+:[0-9]+:[0-9]+")
                        || f[2].length() > 91 || f[2].contains("..")
                        || !f[2].matches("[a-zA-Z0-9][a-zA-Z0-9._ -]*\\.(?i:iso|img|ima|vfd)")) continue;
                final Offer o = new Offer();
                o.kind = Kind.IMAGE;
                o.node = o.label = f[2];
                o.cid = header[1];
                o.stamp = f[1];
                o.bytes = number(f[1].substring(f[1].lastIndexOf(':') + 1));
                o.forcedRo = header[2].equals("1");
                if (o.bytes > 0 && (!o.node.matches("(?i).*\\.(ima|vfd)") || o.floppy())) result.offers.add(o);
            }
            result.offers.sort((a, b) -> {
                final int folded = a.node.compareToIgnoreCase(b.node);
                return folded != 0 ? folded : a.node.compareTo(b.node);
            });
        } catch (IOException e) {
            // A missing inventory is unavailable until the helper finishes scanning.
        }
        return result;
    }

    private final File mRoot;
    private final List<Volume> mVolumes;
    private final Set<String> mProtected = new HashSet<>();
    private boolean mSystem, mData;

    UsbDrive(File root, List<Volume> volumes) {
        mRoot = root;
        mVolumes = volumes;
    }

    List<Offer> scan(String fstab, String easyroms) {
        final List<String[]> mounts = new ArrayList<>();
        final Set<String> publicNumbers = new HashSet<>();
        for (Volume v : mVolumes) {
            if (v.id.startsWith("public:")) publicNumbers.add(v.id.substring(7).replace(',', ':'));
        }
        // Mount device numbers also work when mount sources are aliases or /dev/root.
        for (String line : read("/proc/self/mountinfo").split("\n")) {
            final String[] f = line.split(" +");
            if (f.length < 6) continue;
            mounts.add(f);
            if (!systemPoint(f[4])) continue;
            final String node = nodeForNumbers(f[2]);
            if (node.isEmpty()) continue;
            protect(node, f[4]);
        }
        for (String line : read(fstab).split("\n")) {
            final String[] f = line.trim().split("\\s+");
            if (f.length < 3 || !f[0].startsWith("/dev/block/")
                    || !systemPoint(f[1])) continue;
            try {
                final String node = file(f[0]).getCanonicalFile().getName();
                if (new File(file("/sys/class/block"), node).exists()) protect(node, f[1]);
            } catch (IOException e) {
                // An unresolved system source leaves the layout unknown.
            }
        }
        final List<Offer> offers = new ArrayList<>();
        final File[] disks = file("/sys/class/block").listFiles();
        if (disks == null) return offers;
        for (File disk : disks) {
            if (!disk.getName().matches("mmcblk[0-9]+")) continue;
            final Offer o = new Offer();
            o.disk = o.node = disk.getName();
            o.cid = read("/sys/class/block/" + o.disk + "/device/cid");
            final String path;
            try {
                path = disk.getCanonicalPath();
            } catch (IOException e) {
                continue;
            }
            if (path.contains("/ff370000.dwmmc/")) o.slot = 1;
            else if (path.contains("/ff380000.dwmmc/")) o.slot = 2;
            else if (!o.disk.equals("mmcblk2")
                    || !new File(disk, "andr36oid_unlock").exists()) continue;
            if (o.slot == 0) {
                o.kind = Kind.EMMC;
                o.forcedRo = mProtected.contains(o.disk);
                if (!new File(file("/sys/class/block"), o.disk + "boot0").exists()
                        || !new File(file("/sys/class/block"), o.disk + "boot1").exists()) {
                    o.reason = Reason.UNUSABLE;
                }
            } else if (mProtected.contains(o.disk)) {
                o.kind = Kind.EASYROMS;
                o.node = o.disk + "p7";
                if (!o.node.equals(easyroms) || !"7".equals(read(
                        "/sys/class/block/" + o.node + "/partition"))) {
                    o.reason = Reason.SYSTEM;
                }
            } else {
                o.kind = Kind.CARD;
            }
            for (File part : disks) {
                if (o.kind == Kind.EMMC) break;
                if (!belongs(part.getName(), o.disk)) continue;
                for (String line : read("/sys/class/block/" + part.getName() + "/uevent").split("\n")) {
                    if ("PARTNAME=android_expand".equals(line) || "PARTNAME=android_meta".equals(line)) {
                        o.reason = Reason.INTERNAL;
                    } else if (o.kind == Kind.CARD && ("PARTNAME=system".equals(line)
                            || "PARTNAME=vendor".equals(line) || "PARTNAME=userdata".equals(line))) {
                        o.reason = Reason.ANDROID;
                    }
                }
            }
            for (Volume v : mVolumes) {
                if (!o.disk.equals(v.disk)) continue;
                if (v.internal) o.reason = Reason.INTERNAL;
                if (o.label.isEmpty() && v.label != null) o.label = v.label;
                if (o.type.isEmpty() && v.type != null) o.type = v.type;
            }
            o.bytes = number(read("/sys/class/block/" + o.node + "/size")) * 512;
            if (o.bytes <= 0 || !o.cid.matches("[0-9a-fA-F]{32}")) o.reason = Reason.UNUSABLE;
            if (!mSystem || !mData) o.reason = Reason.UNKNOWN;
            // A mapped device may contain adopted storage, even while it isn't mounted.
            for (File part : disks) {
                if (!belongs(part.getName(), o.node) && !(o.kind == Kind.EMMC
                        && part.getName().matches("mmcblk2boot[01]"))) continue;
                final File[] holders = new File(part, "holders").listFiles();
                if (holders != null && holders.length != 0 && !o.forcedRo && o.enabled()) {
                    o.reason = Reason.BUSY;
                }
                final String numbers = read("/sys/class/block/" + part.getName() + "/dev");
                for (String[] m : mounts) {
                    if (!m[2].equals(numbers) || (o.forcedRo && systemPoint(m[4]))) continue;
                    if (!publicNumbers.contains(numbers) && o.enabled()) o.reason = Reason.BUSY;
                }
            }
            offers.add(o);
        }
        offers.sort((a, b) -> Integer.compare(a.slot == 0 ? 3 : a.slot,
                b.slot == 0 ? 3 : b.slot));
        return offers;
    }

    private void protect(String node, String point) {
        if (!node.matches("mmcblk[0-9]+p[0-9]+")) return;
        mProtected.add(node.substring(0, node.lastIndexOf('p')));
        if ("/".equals(point) || "/system".equals(point)) mSystem = true;
        if ("/data".equals(point)) mData = true;
    }

    private String nodeForNumbers(String numbers) {
        final File[] nodes = file("/sys/class/block").listFiles();
        if (nodes != null) for (File node : nodes) {
            if (numbers.equals(read("/sys/class/block/" + node.getName() + "/dev"))) {
                return node.getName();
            }
        }
        return "";
    }

    static boolean systemPoint(String point) {
        return "/".equals(point) || "/system".equals(point) || "/vendor".equals(point)
                || "/data".equals(point) || "/metadata".equals(point) || "/cache".equals(point);
    }

    static boolean belongs(String node, String target) {
        return node.equals(target) || (target.matches("mmcblk[0-9]+")
                && node.matches(target + "p[0-9]+"));
    }

    private File file(String path) { return new File(mRoot, path.replaceFirst("^/", "")); }

    private String read(String path) {
        try {
            return new String(Files.readAllBytes(file(path).toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static long number(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }
}
