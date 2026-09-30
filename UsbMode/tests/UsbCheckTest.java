package org.andr36oid.usbmode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host test: glob matching, the table parser, and the verdicts for a fake
 * /sys/bus/usb/devices (tests/make-fake-sysfs.sh) with the real table.
 *   UsbCheckTest <fake root> <usb_support.txt> <wifi_id_list.txt>
 */
public class UsbCheckTest {

    private static int sPass, sFail;

    private static void check(String what, boolean ok) {
        if (ok) {
            sPass++;
        } else {
            sFail++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void eq(String what, Object want, Object got) {
        check(what + " (want " + want + ", got " + got + ")",
                want == null ? got == null : want.equals(got));
    }

    public static void main(String[] args) throws IOException {
        globTests();
        tableTests();

        final File root = new File(args[0]);
        final UsbTable t;
        try (BufferedReader r = new BufferedReader(new FileReader(args[1]))) {
            t = UsbTable.parse(r);
        }
        final Map<String, String> wifi = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(args[2]))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] f = line.trim().split("\\s+");
                if (f.length >= 3) wifi.put(UsbTable.id(f[0], f[1]), f[2]);
            }
        }
        realTableTests(t, wifi);
        sysfsTests(root, t, wifi);

        System.out.println(sPass + " passed, " + sFail + " failed");
        System.exit(sFail == 0 ? 0 : 1);
    }

    private static void globTests() {
        check("star", UsbTable.glob("v0BDApC811d*dc*", "v0BDApC811d0200dc00"));
        check("star end", UsbTable.glob("v*", "v1234"));
        check("literal mismatch", !UsbTable.glob("v0BDApC811d*", "v0BDApC812d0200"));
        check("class range", UsbTable.glob("v045Ep028Ed0[0-1]*", "v045Ep028Ed0114"));
        check("class range miss", !UsbTable.glob("v045Ep028Ed0[2-9]*", "v045Ep028Ed0114"));
        check("class list", UsbTable.glob("d[01]*", "d1"));
        check("negated class", UsbTable.glob("d[!0]*", "d1") && !UsbTable.glob("d[!0]*", "d0"));
        check("question", UsbTable.glob("a?c", "abc") && !UsbTable.glob("a?c", "ac"));
        check("two stars backtrack", UsbTable.glob("*icFF*in00", "v1p2icFFiscFFin01icFFin00"));
        check("trailing literal", !UsbTable.glob("*in00", "icFFin01"));
        check("empty pattern", !UsbTable.glob("", "x") && UsbTable.glob("*", ""));
    }

    private static void tableTests() throws IOException {
        final String txt = "# comment\n"
                + "D 8821cu wifi\n"
                + "A 8821cu v0BDApC811d*dc*dsc*dp*icFFiscFFipFFin*\n"
                + "D usbhid input\n"
                + "A usbhid v*p*d*dc*dsc*dp*ic03isc*ip*in*\n"
                + "N 8821cu Realtek RTL8811CU/8821CU\n"
                + "C 0BDA 1A2B wifi\n"
                + "U 0bda 885a Realtek RTL8852AU\n"
                + "garbage\n\nA\nA x\n";
        final UsbTable t = UsbTable.parse(new BufferedReader(new StringReader(txt)));
        eq("table size", 2, t.size());
        eq("kind", "wifi", t.kinds.get("8821cu"));
        eq("chip with spaces", "Realtek RTL8811CU/8821CU", t.chips.get("8821cu"));
        eq("cd lower-cased", "wifi", t.cdMode.get("0bda:1a2b"));
        eq("unsupported", "Realtek RTL8852AU", t.unsupported.get("0bda:885a"));
        List<String> m = t.match("usb:v0BDApC811d0200dc00dsc00dp00icFFiscFFipFFin00");
        eq("vendor match", "[8821cu]", m.toString());
        m = t.match("usb:v046DpC31Cd6400dc00dsc00dp00ic03isc01ip01in00");
        eq("any-vendor match", "[usbhid]", m.toString());
        m = t.match("v0BDApC811d0200dc00dsc00dp00ic03isc00ip00in02");
        eq("both, without usb:", "[usbhid]", m.toString());
        eq("null modalias", 0, t.match(null).size());
    }

    private static String vendorAlias(String id) {
        final String[] p = id.toUpperCase().split(":");
        return "usb:v" + p[0] + "p" + p[1] + "d0100dc00dsc00dp00icFFiscFFipFFin00";
    }

    private static void realTableTests(UsbTable t, Map<String, String> wifi) {
        check("real table has over 1000 IDs (" + t.size() + ")", t.size() > 1000);
        for (String mod : new String[] {"8821cu", "88x2bu", "8188eu", "8188fu", "mt7601u",
                "rt2800usb", "btusb", "xpad", "usbhid", "usb-storage", "uas", "r8152",
                "ax_usb_nic", "cdc_ether", "snd-usb-audio", "qmi_wwan", "option"}) {
            check("real table has " + mod, t.kinds.containsKey(mod));
        }
        eq("kind of 8821cu", "wifi", t.kinds.get("8821cu"));
        eq("kind of btusb", "bluetooth", t.kinds.get("btusb"));
        eq("kind of r8152", "network", t.kinds.get("r8152"));
        eq("kind of xpad", "gamepad", t.kinds.get("xpad"));
        eq("kind of snd-usb-audio", "audio", t.kinds.get("snd-usb-audio"));
        eq("0bda:1a2b is a Wi-Fi driver CD", "wifi", t.cdMode.get("0bda:1a2b"));
        check("LTE sticks are listed as driver CDs",
                "modem".equals(t.cdMode.get("12d1:1f01")) || t.cdMode.size() > 300);

        // Every known-unsupported chip really has no match (else the list lies)
        for (String id : t.unsupported.keySet()) {
            eq("no driver matches " + id, "[]", t.match(vendorAlias(id)).toString());
        }
        // Every ID the Wi-Fi HAL loads a driver for has a Wi-Fi driver in the table
        // (vendor interface, or CDC for rndis_wlan). The HAL names a driver family
        // by its last module (aic8800_fdrv for the aic_load_fw IDs too).
        int missing = 0;
        for (Map.Entry<String, String> e : wifi.entrySet()) {
            final List<String> m = t.match(vendorAlias(e.getKey()));
            m.addAll(t.match(vendorAlias(e.getKey()).replace("icFFiscFFipFF", "ic02isc02ipFF")));
            boolean wifiDriver = false;
            for (String mod : m) wifiDriver |= "wifi".equals(t.kinds.get(mod));
            if (!wifiDriver) {
                missing++;
                if (missing <= 5) System.out.println("  wifi id " + e.getKey() + " " + e.getValue() + " -> " + m);
            }
        }
        eq("Wi-Fi HAL IDs missing from the table (of " + wifi.size() + ")", 0, missing);
    }

    private static UsbCheck.Result find(List<UsbCheck.Result> rs, String name) {
        for (UsbCheck.Result r : rs) {
            if (r.dev.name.equals(name)) return r;
        }
        return null;
    }

    private static List<UsbCheck.Result> run(File root, UsbTable t, Map<String, String> wifi,
            boolean wifiOn) {
        final List<UsbCheck.Result> out = new java.util.ArrayList<>();
        for (UsbScan.Device d : UsbScan.scan(new File(root, "devices"))) {
            out.add(UsbCheck.check(d, t, wifi, wifiOn));
        }
        return out;
    }

    private static void expect(List<UsbCheck.Result> rs, String dev, UsbCheck.Status status,
            String kind, String chip) {
        final UsbCheck.Result r = find(rs, dev);
        check(dev + " found", r != null);
        if (r == null) return;
        eq(dev + " status", status, r.status);
        eq(dev + " kind", kind, r.kind);
        if (chip != null) eq(dev + " chip", chip, r.chip);
    }

    private static void sysfsTests(File root, UsbTable t, Map<String, String> wifi) {
        List<UsbCheck.Result> rs = run(root, t, wifi, false);
        eq("devices (root hub and interfaces skipped)", 19, rs.size());
        check("no root hub", find(rs, "usb1") == null);

        expect(rs, "1-1", UsbCheck.Status.WORKS, "hub", null);
        expect(rs, "1-1.1", UsbCheck.Status.WIFI_OFF, "wifi", "Realtek RTL8811CU/8821CU");
        expect(rs, "1-1.2", UsbCheck.Status.CD_MODE, "wifi", null);
        expect(rs, "1-1.3", UsbCheck.Status.NOT_SUPPORTED, "wifi", "Realtek RTL8852AU");
        expect(rs, "1-1.4", UsbCheck.Status.WORKS, "gamepad", null);
        expect(rs, "1-1.5", UsbCheck.Status.WORKS, "network", null);
        expect(rs, "1-1.6", UsbCheck.Status.NOT_SUPPORTED, "other", null);
        expect(rs, "1-1.7", UsbCheck.Status.NO_POWER, "wifi", null);
        expect(rs, "2-1", UsbCheck.Status.WORKS, "keyboard", null);
        expect(rs, "3-1", UsbCheck.Status.CD_UNKNOWN, "wifi", null);
        // driver CD only when the drive is a CD-ROM and there is no normal disk
        expect(rs, "3-2", UsbCheck.Status.WORKS, "storage", null);
        expect(rs, "3-3", UsbCheck.Status.WORKS, "storage", null);
        expect(rs, "3-4", UsbCheck.Status.CD_UNKNOWN, "wifi", null);
        expect(rs, "3-5", UsbCheck.Status.WORKS, "storage", null);
        expect(rs, "3-6", UsbCheck.Status.WORKS, "storage", null);
        eq("scsi types read", java.util.Arrays.asList(5, 0), find(rs, "3-6").dev.ifaces.get(0).scsiTypes);
        check("details: scsi types", UsbCheck.details(find(rs, "3-1")).contains("  3-1:1.0  08/06/50  driver usb-storage  scsi 5\n"));
        expect(rs, "4-1", UsbCheck.Status.WORKS, "storage", null);
        expect(rs, "5-1", UsbCheck.Status.WORKS, "audio", null);
        expect(rs, "6-1", UsbCheck.Status.BT_ONLY, "bluetooth", "Realtek RTL8723DU");
        expect(rs, "7-1", UsbCheck.Status.NOT_STARTED, "input", null);

        final UsbCheck.Result wifiDongle = find(rs, "1-1.1");
        check("8821cu listed as the missing driver", wifiDongle.missing.contains("8821cu"));
        eq("name joins maker and product", "Realtek 802.11ac NIC", UsbCheck.name(wifiDongle.dev));
        eq("no name", null, UsbCheck.name(find(rs, "1-1.6").dev));
        eq("name keeps product when it starts with the maker", "USB 2.0 Hub",
                UsbCheck.name(find(rs, "1-1").dev));
        eq("name trims and joins spaces", "DragonRise Inc. Generic USB Joystick",
                UsbCheck.name(find(rs, "7-1").dev));

        // Wi-Fi on but the driver still didn't take it
        rs = run(root, t, wifi, true);
        expect(rs, "1-1.1", UsbCheck.Status.NOT_STARTED, "wifi", null);
        // no HAL list known: still "turn Wi-Fi on"
        rs = run(root, t, null, false);
        expect(rs, "1-1.1", UsbCheck.Status.WIFI_OFF, "wifi", null);

        // The saved details
        final String det = UsbCheck.details(find(rs, "1-1.4"));
        check("details: header line", det.startsWith("1-1.4  045e:028e  \"Microsoft Controller\"  480 Mbit/s  USB 2.00\n"));
        check("details: status line", det.contains("\n  WORKS  kind gamepad\n"));
        check("details: interface line", det.contains("  1-1.4:1.0  ff/5d/01  driver xpad\n"));
        check("details: unbound interface", det.contains("  1-1.4:1.3  ff/fd/13  driver -\n"));
        final String det2 = UsbCheck.details(find(rs, "1-1.7"));
        check("details: not configured", det2.contains("  NO_POWER  kind wifi\n  not configured\n"));
        final String det3 = UsbCheck.details(find(rs, "1-1.1"));
        check("details: should be taken by", det3.contains("  should be taken by: 8821cu"));

        // An empty or missing sysfs folder
        eq("missing folder", 0, UsbScan.scan(new File(root, "nothing-here")).size());
    }
}
