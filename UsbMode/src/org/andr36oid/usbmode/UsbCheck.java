package org.andr36oid.usbmode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides for each plugged-in USB device whether it works, and why not.
 * Plain Java, no android.*, so the host test can run it.
 */
final class UsbCheck {

    enum Status {
        /** a driver took it */
        WORKS,
        /** Bluetooth half of a Wi-Fi + Bluetooth dongle works, the Wi-Fi half has no driver */
        BT_ONLY,
        /** a Wi-Fi driver exists; the Wi-Fi HAL loads it once Wi-Fi is on */
        WIFI_OFF,
        /** a driver exists for it, but it did not take the device */
        NOT_STARTED,
        /** seen, but never set up (usually not enough power on the port) */
        NO_POWER,
        /** starts as a driver CD; usb-storage switches it in a moment */
        CD_MODE,
        /** looks like a Wi-Fi driver CD we don't know how to switch */
        CD_UNKNOWN,
        /** no driver for this chip */
        NOT_SUPPORTED,
    }

    static final class Result {
        UsbScan.Device dev;
        Status status;
        /** wifi bluetooth network audio gamepad keyboard mouse input storage serial video hub other */
        String kind;
        /** plain chip name when known (driver's chip, or a known unsupported chip) */
        String chip;
        /** drivers that took a part of it */
        final List<String> bound = new ArrayList<>();
        /** drivers in the table that should take a part that nobody took */
        final List<String> missing = new ArrayList<>();
    }

    /** Priority when a device has several parts, first wins */
    private static final List<String> KIND_ORDER = Arrays.asList("wifi", "network", "gamepad",
            "audio", "bluetooth", "video", "storage", "serial", "keyboard", "mouse", "input",
            "hub", "other");

    /** Chip makers whose Wi-Fi dongles come up as a driver CD */
    private static final List<String> WIFI_CD_VENDORS = Arrays.asList("0bda", "0e8d", "a69c",
            "148f", "2357", "7392", "2001", "0b05", "2604");

    /**
     * @param wifiIds  /vendor/etc/wifi_id_list.txt as "vvvv:pppp" -> module, or null
     *                 when unknown (then every Wi-Fi match counts as loadable)
     * @param wifiOn   whether Wi-Fi is switched on
     */
    static Result check(UsbScan.Device d, UsbTable t, Map<String, String> wifiIds, boolean wifiOn) {
        final Result r = new Result();
        r.dev = d;
        final String id = d.id();

        if (d.isHub()) {
            r.kind = "hub";
            r.status = d.configured && !d.ifaces.isEmpty() ? Status.WORKS : Status.NO_POWER;
            for (UsbScan.Iface i : d.ifaces) addOnce(r.bound, i.driver);
            return r;
        }

        final String cd = t.cdMode.get(id);
        // a known ID, unless its drive says it is a plain disk
        if (cd != null && !(storageIsDisk(d) && !storageIsCd(d))) {
            r.kind = "wifi".equals(cd) ? "wifi" : "network";
            r.status = Status.CD_MODE;
            for (UsbScan.Iface i : d.ifaces) addOnce(r.bound, i.driver);
            return r;
        }

        if (looksLikeWifiCd(d)) {
            r.kind = "wifi";
            r.status = Status.CD_UNKNOWN;
            for (UsbScan.Iface i : d.ifaces) addOnce(r.bound, i.driver);
            return r;
        }

        if (!d.configured || d.ifaces.isEmpty()) {
            r.kind = guessKind(d, null);
            r.status = Status.NO_POWER;
            return r;
        }

        String wifiMissing = null;          // Wi-Fi module for a part nobody took
        boolean wifiBound = false, otherMissing = false, vendorUnknown = false;
        boolean onlyBluetoothBound = true;
        final List<String> kinds = new ArrayList<>();
        for (UsbScan.Iface i : d.ifaces) {
            final List<String> m = t.match(i.modalias);
            String kind = null;
            for (String mod : m) {
                final String k = t.kinds.get(mod);
                if (k != null) {
                    kind = k;
                    break;
                }
            }
            if (kind == null || "input".equals(kind)) kind = classKind(i, kind);
            if (i.driver != null) {
                addOnce(r.bound, i.driver);
                if ("wifi".equals(kind)) wifiBound = true;
                if (!"bluetooth".equals(kind)) onlyBluetoothBound = false;
                if (kind != null) kinds.add(kind);
                if (r.chip == null) r.chip = chipOf(t, m);
            } else if (!m.isEmpty()) {
                if ("wifi".equals(kind)) {
                    final String hal = wifiIds == null ? m.get(0) : wifiIds.get(id);
                    if (wifiMissing == null) wifiMissing = hal != null ? hal : m.get(0);
                    if (r.chip == null) r.chip = chipOf(t, m);
                } else {
                    otherMissing = true;
                }
                for (String mod : m) addOnce(r.missing, mod);
                if (kind != null) kinds.add(kind);
            } else if (i.cls == 0xff || i.cls == -1) {
                vendorUnknown = true;
            }
        }
        r.kind = pickKind(kinds, d);

        if (wifiMissing != null && !wifiBound) {
            r.kind = "wifi";
            final boolean halLoads = wifiIds == null || wifiIds.containsKey(id);
            r.status = halLoads && !wifiOn ? Status.WIFI_OFF : Status.NOT_STARTED;
            return r;
        }
        if (otherMissing && r.bound.isEmpty()) {
            r.status = Status.NOT_STARTED;
            return r;
        }
        if (!r.bound.isEmpty()) {
            if (onlyBluetoothBound && vendorUnknown) {
                r.status = Status.BT_ONLY;
                r.chip = t.unsupported.get(id);
                return r;
            }
            r.status = Status.WORKS;
            return r;
        }
        // nobody took it and nothing in the table matches
        r.chip = t.unsupported.get(id);
        if (r.chip != null && "other".equals(r.kind)) r.kind = "wifi";
        r.status = Status.NOT_SUPPORTED;
        return r;
    }

    private static String chipOf(UsbTable t, List<String> modules) {
        for (String mod : modules) {
            final String c = t.chips.get(mod);
            if (c != null) return c;
        }
        return null;
    }

    private static void addOnce(List<String> l, String s) {
        if (s != null && !l.contains(s)) l.add(s);
    }

    /** What an interface is from its class, when the table doesn't say */
    static String classKind(UsbScan.Iface i, String tableKind) {
        switch (i.cls) {
            case 0x01:
                return "audio";
            case 0x02:
                return i.subClass == 0x02 ? "serial" : "network";
            case 0x03:
                if (i.subClass == 0x01 && i.protocol == 0x01) return "keyboard";
                if (i.subClass == 0x01 && i.protocol == 0x02) return "mouse";
                return "input";
            case 0x08:
                return "storage";
            case 0x09:
                return "hub";
            case 0x0a:
                return null;    // data half of a network or serial part
            case 0x0e:
                return "video";
            case 0xe0:
                if (i.subClass == 0x01 && i.protocol == 0x01) return "bluetooth";
                if (i.subClass == 0x01 && i.protocol == 0x03) return "network";
                return tableKind;
            case 0xef:
                if (i.subClass == 0x04) return "network";
                return tableKind;
            default:
                return tableKind;
        }
    }

    private static String pickKind(List<String> kinds, UsbScan.Device d) {
        for (String k : KIND_ORDER) {
            if (kinds.contains(k)) return k;
        }
        return guessKind(d, null);
    }

    /** From the maker's own name for it, when nothing else says */
    static String guessKind(UsbScan.Device d, String fallback) {
        final String s = (nz(d.manufacturer) + " " + nz(d.product)).toLowerCase(Locale.ROOT);
        if (s.matches(".*(wlan|802\\.11|wi-?fi|wireless lan|wireless adapter|\\bnic\\b|11ac|11ax|11n).*")) {
            return "wifi";
        }
        if (s.matches(".*(ethernet|\\blan\\b|gigabit).*")) return "network";
        if (s.matches(".*(bluetooth|\\bbt\\b).*")) return "bluetooth";
        if (s.matches(".*(controller|gamepad|joystick|\\bpad\\b).*")) return "gamepad";
        if (s.matches(".*(audio|sound|headset|dac).*")) return "audio";
        return fallback != null ? fallback : "other";
    }

    /**
     * The storage part is a CD-ROM: a drive of SCSI type 5, or the ATAPI (0x02) or
     * SFF-8070i (0x05) subclass. Driver CDs mostly say "SCSI" (0x06), so the drive
     * type is what usually tells.
     */
    static boolean storageIsCd(UsbScan.Device d) {
        for (UsbScan.Iface i : d.ifaces) {
            if (i.cls != 0x08) continue;
            if (i.subClass == 0x02 || i.subClass == 0x05) return true;
            if (i.scsiTypes.contains(5)) return true;
        }
        return false;
    }

    /** The storage part has a normal drive: SCSI type 0 (disk) or 0x0e (simple disk) */
    static boolean storageIsDisk(UsbScan.Device d) {
        for (UsbScan.Iface i : d.ifaces) {
            if (i.cls != 0x08) continue;
            if (i.scsiTypes.contains(0) || i.scsiTypes.contains(0x0e)) return true;
        }
        return false;
    }

    /**
     * A CD-ROM from a Wi-Fi chip maker that calls itself a disk or CD, and has no
     * normal drive. A USB stick or disk case with a Realtek or MediaTek chip is a
     * disk, not a driver CD.
     */
    static boolean looksLikeWifiCd(UsbScan.Device d) {
        if (!WIFI_CD_VENDORS.contains(d.vid)) return false;
        if (!storageIsCd(d) || storageIsDisk(d)) return false;
        final String s = (nz(d.manufacturer) + " " + nz(d.product)).toLowerCase(Locale.ROOT);
        if (s.matches(".*(crw|card|reader|sd/mmc|flash).*")) return false;
        return s.matches(".*(disk|cd|driver|wlan|wi-?fi|wireless|802\\.11).*");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** The maker's name for it: "Realtek 802.11ac NIC", or null when it has none */
    static String name(UsbScan.Device d) {
        final String m = d.manufacturer == null ? "" : d.manufacturer.trim().replaceAll("\\s+", " ");
        final String p = d.product == null ? "" : d.product.trim().replaceAll("\\s+", " ");
        if (p.isEmpty()) return m.isEmpty() ? null : m;
        if (m.isEmpty() || p.toLowerCase(Locale.ROOT).startsWith(m.toLowerCase(Locale.ROOT))) return p;
        return m + " " + p;
    }

    /** English, for support: one block per device */
    static String details(Result r) {
        final UsbScan.Device d = r.dev;
        final StringBuilder sb = new StringBuilder();
        sb.append(d.name).append("  ").append(d.id()).append("  ");
        final String n = name(d);
        sb.append(n == null ? "(no name)" : "\"" + n + "\"");
        if (d.speed != null) sb.append("  ").append(d.speed).append(" Mbit/s");
        if (d.version != null) sb.append("  USB ").append(d.version);
        sb.append('\n');
        sb.append("  ").append(r.status).append("  kind ").append(r.kind);
        if (r.chip != null) sb.append("  chip ").append(r.chip);
        sb.append('\n');
        if (!d.configured) sb.append("  not configured\n");
        sb.append("  port ").append(portOf(d.name)).append(" of ").append(parentOf(d.name));
        if (d.maxPower != null) sb.append("  asks for ").append(d.maxPower);
        if (d.ports >= 0) sb.append("  hub with ").append(d.ports).append(" ports");
        sb.append('\n');
        for (UsbScan.Iface i : d.ifaces) {
            sb.append("  ").append(i.name)
                    .append(String.format(Locale.ROOT, "  %02x/%02x/%02x", i.cls & 0xff,
                            i.subClass & 0xff, i.protocol & 0xff))
                    .append("  driver ").append(i.driver == null ? "-" : i.driver);
            if (!i.scsiTypes.isEmpty()) {
                sb.append("  scsi");
                for (int ty : i.scsiTypes) sb.append(' ').append(ty);
            }
            sb.append('\n');
        }
        if (!r.missing.isEmpty()) {
            sb.append("  should be taken by: ").append(join(r.missing)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Every bus, hub and port as a tree, like USBTreeView on Windows: where each device
     * is plugged in, its speed and what it asks the port for. English, for support.
     */
    static String tree(List<UsbScan.Bus> buses, List<Result> results) {
        final StringBuilder sb = new StringBuilder();
        for (UsbScan.Bus b : buses) {
            sb.append("Bus ").append(b.number).append(" (").append(b.name).append(')');
            if (b.product != null && !b.product.isEmpty()) sb.append("  ").append(b.product);
            if (b.speed != null) sb.append("  ").append(b.speed).append(" Mbit/s");
            if (b.ports >= 0) sb.append("  ").append(b.ports).append(b.ports == 1 ? " port" : " ports");
            sb.append('\n');
            branch(sb, b.name, results, "");
        }
        if (buses.isEmpty()) sb.append("No USB host controller.\n");
        return sb.toString();
    }

    private static void branch(StringBuilder sb, String parent, List<Result> results, String indent) {
        final List<Result> kids = new ArrayList<>();
        for (Result r : results) if (parentOf(r.dev.name).equals(parent)) kids.add(r);
        Collections.sort(kids, (a, b) -> Integer.compare(portOf(a.dev.name), portOf(b.dev.name)));
        for (int k = 0; k < kids.size(); k++) {
            final Result r = kids.get(k);
            final UsbScan.Device d = r.dev;
            final boolean last = k == kids.size() - 1;
            sb.append(indent).append("+- port ").append(portOf(d.name)).append(": ")
                    .append(d.id());
            final String n = name(d);
            if (n != null) sb.append(" \"").append(n).append('"');
            if (d.speed != null) sb.append("  ").append(d.speed).append(" Mbit/s");
            if (d.version != null) sb.append("  USB ").append(d.version);
            if (d.maxPower != null) sb.append("  ").append(d.maxPower);
            if (d.ports >= 0) sb.append("  hub, ").append(d.ports).append(" ports");
            sb.append("  ").append(r.status).append('\n');
            branch(sb, d.name, results, indent + (last ? "   " : "|  "));
        }
    }

    /** "1-1.2" is on hub "1-1", "1-1" on bus "usb1" */
    static String parentOf(String name) {
        final int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(0, dot);
        final int dash = name.indexOf('-');
        return dash > 0 ? "usb" + name.substring(0, dash) : "";
    }

    /** The port on its parent: "1-1.2" is on port 2 */
    static int portOf(String name) {
        final int cut = Math.max(name.lastIndexOf('.'), name.indexOf('-'));
        try {
            return Integer.parseInt(name.substring(cut + 1));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return -1;
        }
    }

    static String join(List<String> l) {
        final StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private UsbCheck() {
    }
}
