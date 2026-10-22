package org.andr36oid.usbmode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the USB devices from sysfs (/sys/bus/usb/devices, world-readable).
 * Plain Java, no android.*, so the host test can run it on a fake tree.
 */
final class UsbScan {

    static final String SYSFS = "/sys/bus/usb/devices";

    static final class Iface {
        String name;        // "1-1:1.0"
        int number;
        int cls = -1, subClass = -1, protocol = -1;
        String modalias;    // "usb:v0BDApC811d0200dc00dsc00dp00icFFiscFFipFFin00"
        String driver;      // bound driver, null if none
        /** SCSI peripheral type of each drive of a storage part: 0 disk, 5 CD-ROM */
        final List<Integer> scsiTypes = new ArrayList<>();
    }

    static final class Device {
        String name;        // "1-1", "1-1.2" behind a hub
        String vid = "0000", pid = "0000";
        String manufacturer, product;
        int devClass = -1;
        String speed;       // Mbit/s: 1.5 12 480 5000
        String version;     // "2.00"
        String maxPower;    // what it asks the port for: "500mA"
        int ports = -1;     // a hub's downstream ports (maxchild)
        boolean configured;
        final List<Iface> ifaces = new ArrayList<>();

        String id() {
            return vid + ":" + pid;
        }

        boolean isHub() {
            if (devClass == 9) return true;
            return ifaces.size() == 1 && ifaces.get(0).cls == 9;
        }
    }

    /** A host controller's root hub (usb1, usb2): the top of the USB tree */
    static final class Bus {
        String name;        // "usb1"
        int number;         // 1, the first part of its devices' names ("1-1")
        String product;     // "DWC OTG Controller"
        String speed;
        int ports = -1;
    }

    static List<Bus> buses(File root) {
        final List<Bus> out = new ArrayList<>();
        final String[] names = root.list();
        if (names == null) return out;
        Arrays.sort(names);
        for (String n : names) {
            if (!n.matches("usb\\d+")) continue;
            final File d = new File(root, n);
            final Bus b = new Bus();
            b.name = n;
            b.number = Integer.parseInt(n.substring(3));
            b.product = read(d, "product");
            b.speed = read(d, "speed");
            b.ports = dec(read(d, "maxchild"));
            out.add(b);
        }
        return out;
    }

    static List<Device> scan(File root) {
        final List<Device> out = new ArrayList<>();
        final String[] names = root.list();
        if (names == null) return out;
        Arrays.sort(names);
        for (String n : names) {
            // root hubs (usb1, usb2) and interfaces (1-1:1.0) are not devices
            if (n.startsWith("usb") || n.indexOf(':') >= 0) continue;
            final File d = new File(root, n);
            final String vid = read(d, "idVendor");
            if (vid == null) continue;
            final Device dev = new Device();
            dev.name = n;
            dev.vid = vid.toLowerCase();
            dev.pid = orDefault(read(d, "idProduct"), "0000").toLowerCase();
            dev.manufacturer = read(d, "manufacturer");
            dev.product = read(d, "product");
            dev.devClass = hex(read(d, "bDeviceClass"));
            dev.speed = read(d, "speed");
            dev.version = read(d, "version");
            dev.maxPower = read(d, "bMaxPower");
            dev.ports = dec(read(d, "maxchild"));
            final String cfg = read(d, "bConfigurationValue");
            dev.configured = cfg != null && !cfg.isEmpty() && !"0".equals(cfg);
            for (String m : names) {
                if (!m.startsWith(n + ":")) continue;
                final File f = new File(root, m);
                final Iface i = new Iface();
                i.name = m;
                i.number = hex(read(f, "bInterfaceNumber"));
                i.cls = hex(read(f, "bInterfaceClass"));
                i.subClass = hex(read(f, "bInterfaceSubClass"));
                i.protocol = hex(read(f, "bInterfaceProtocol"));
                i.modalias = read(f, "modalias");
                i.driver = driverOf(f);
                if (i.cls == 0x08) scsiTypes(f, i.scsiTypes);
                dev.ifaces.add(i);
            }
            out.add(dev);
        }
        return out;
    }

    /** usb-storage puts its drives at hostN/targetN:0:0/N:0:0:L/type */
    private static void scsiTypes(File iface, List<Integer> out) {
        final File[] hosts = iface.listFiles((dir, n) -> n.startsWith("host"));
        if (hosts == null) return;
        Arrays.sort(hosts);
        for (File h : hosts) {
            final File[] targets = h.listFiles((dir, n) -> n.startsWith("target"));
            if (targets == null) continue;
            Arrays.sort(targets);
            for (File t : targets) {
                final File[] luns = t.listFiles((dir, n) -> n.indexOf(':') >= 0);
                if (luns == null) continue;
                Arrays.sort(luns);
                for (File l : luns) {
                    final String type = read(l, "type");
                    if (type == null) continue;
                    try {
                        out.add(Integer.parseInt(type));
                    } catch (NumberFormatException e) {
                        // not a number: skip it
                    }
                }
            }
        }
    }

    private static String driverOf(File iface) {
        final File link = new File(iface, "driver");
        if (!link.exists()) return null;
        try {
            return link.getCanonicalFile().getName();
        } catch (IOException e) {
            return null;
        }
    }

    /** First line of a sysfs file, trimmed; null if missing or unreadable */
    static String read(File dir, String name) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(dir, name)))) {
            final String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static int hex(String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int dec(String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String orDefault(String s, String d) {
        return s == null || s.isEmpty() ? d : s;
    }

    private UsbScan() {
    }
}
