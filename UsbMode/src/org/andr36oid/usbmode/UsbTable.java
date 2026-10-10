package org.andr36oid.usbmode;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The USB support table (assets/usb_support.txt), made from the kernel's own
 * device tables by kernel scripts/android/gen-usb-support-table.sh. Plain Java,
 * no android.*, so the host test can use it.
 */
final class UsbTable {

    private static final class Entry {
        final String module;
        final String pattern;

        Entry(String module, String pattern) {
            this.module = module;
            this.pattern = pattern;
        }
    }

    /** module -> wifi, bluetooth, network, audio, gamepad, input, storage, serial, video, other */
    final Map<String, String> kinds = new HashMap<>();
    /** module -> plain chip name, e.g. "Realtek RTL8811CU/8821CU" */
    final Map<String, String> chips = new HashMap<>();
    /** "0bda:1a2b" -> wifi or modem: starts as a driver CD, usb-storage switches it */
    final Map<String, String> cdMode = new HashMap<>();
    /** "0bda:885a" -> "Realtek RTL8852AU": well-known chip with no driver here */
    final Map<String, String> unsupported = new HashMap<>();

    /** Patterns with a fixed vendor, by vendor ("0BDA"), and the rest */
    private final Map<String, List<Entry>> mByVendor = new HashMap<>();
    private final List<Entry> mAnyVendor = new ArrayList<>();
    private int mCount;

    static UsbTable parse(BufferedReader in) throws IOException {
        final UsbTable t = new UsbTable();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.length() < 3 || line.charAt(1) != ' ') continue;
            final String[] f = line.split(" ", 4);
            switch (line.charAt(0)) {
                case 'D':
                    if (f.length >= 3) t.kinds.put(f[1], f[2]);
                    break;
                case 'A':
                    if (f.length >= 3) t.add(f[1], f[2]);
                    break;
                case 'N':
                    if (f.length >= 3) t.chips.put(f[1], line.substring(3 + f[1].length()));
                    break;
                case 'C':
                    if (f.length >= 4) t.cdMode.put(id(f[1], f[2]), f[3].trim());
                    break;
                case 'U':
                    if (f.length >= 4) t.unsupported.put(id(f[1], f[2]), f[3].trim());
                    break;
                default:
                    break;
            }
        }
        return t;
    }

    static String id(String vid, String pid) {
        return vid.toLowerCase() + ":" + pid.toLowerCase();
    }

    int size() {
        return mCount;
    }

    private void add(String module, String pattern) {
        final Entry e = new Entry(module, pattern);
        mCount++;
        final String vendor = fixedVendor(pattern);
        if (vendor == null) {
            mAnyVendor.add(e);
        } else {
            List<Entry> l = mByVendor.get(vendor);
            if (l == null) {
                l = new ArrayList<>();
                mByVendor.put(vendor, l);
            }
            l.add(e);
        }
    }

    /** "v0BDApC811d..." -> "0BDA", null when the vendor has wildcards */
    private static String fixedVendor(String s) {
        if (s.length() < 6 || s.charAt(0) != 'v' || s.charAt(5) != 'p') return null;
        for (int i = 1; i < 5; i++) {
            final char c = s.charAt(i);
            if (c == '*' || c == '?' || c == '[') return null;
        }
        return s.substring(1, 5);
    }

    /**
     * The modules whose device table matches an interface, in table order,
     * each once. The modalias is the interface's sysfs modalias, with or
     * without "usb:".
     */
    List<String> match(String modalias) {
        final List<String> out = new ArrayList<>();
        if (modalias == null) return out;
        final String s = modalias.startsWith("usb:") ? modalias.substring(4) : modalias;
        final String vendor = s.length() >= 5 ? s.substring(1, 5) : "";
        final List<Entry> l = mByVendor.get(vendor);
        if (l != null) matchInto(l, s, out);
        matchInto(mAnyVendor, s, out);
        return out;
    }

    private static void matchInto(List<Entry> l, String s, List<String> out) {
        for (Entry e : l) {
            if (!out.contains(e.module) && glob(e.pattern, s)) out.add(e.module);
        }
    }

    /** fnmatch() as the kernel's modalias patterns use it: *, ? and [a-z0-9] */
    static boolean glob(String p, String s) {
        int pi = 0, si = 0, starP = -1, starS = 0;
        while (si < s.length()) {
            if (pi < p.length()) {
                final char c = p.charAt(pi);
                if (c == '*') {
                    starP = pi++;
                    starS = si;
                    continue;
                }
                if (c == '?') {
                    pi++;
                    si++;
                    continue;
                }
                if (c == '[') {
                    final int end = p.indexOf(']', pi + 2);
                    if (end > 0) {
                        if (inClass(p, pi + 1, end, s.charAt(si))) {
                            pi = end + 1;
                            si++;
                            continue;
                        }
                    } else if (s.charAt(si) == '[') {
                        pi++;
                        si++;
                        continue;
                    }
                } else if (c == s.charAt(si)) {
                    pi++;
                    si++;
                    continue;
                }
            }
            if (starP < 0) return false;
            pi = starP + 1;
            si = ++starS;
        }
        while (pi < p.length() && p.charAt(pi) == '*') pi++;
        return pi == p.length();
    }

    private static boolean inClass(String p, int from, int end, char ch) {
        boolean negate = false;
        int i = from;
        if (p.charAt(i) == '!' || p.charAt(i) == '^') {
            negate = true;
            i++;
        }
        boolean hit = false;
        while (i < end) {
            final char a = p.charAt(i);
            if (i + 2 < end && p.charAt(i + 1) == '-') {
                if (ch >= a && ch <= p.charAt(i + 2)) hit = true;
                i += 3;
            } else {
                if (ch == a) hit = true;
                i++;
            }
        }
        return hit != negate;
    }
}
