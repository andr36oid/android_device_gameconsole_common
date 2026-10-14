package org.andr36oid.controllers;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data behind Find an unknown button: the scan files the root helper
 * andr36oid-gpio-scan.sh writes, and what the device tree says about the pins.
 */
final class PinScan {

    static final String SCAN_DIR = "/data/system";
    private static final String DT = "/proc/device-tree";
    static final int BANKS = 4;
    static final int PINS = 32;

    private PinScan() {
    }

    /** One scan: per bank which pins stayed low, stayed high or changed. */
    static final class Result {
        final long[] low = new long[BANKS];
        final long[] high = new long[BANKS];
        final long[] changing = new long[BANKS];
        /** outputs: driven by the chip itself, never a button */
        final long[] output = new long[BANKS];
        final String[] mux = new String[BANKS];
        final boolean[] present = new boolean[BANKS];
        /** "device channel" -> {min, max} */
        final Map<String, int[]> adc = new LinkedHashMap<>();
    }

    /** The scan file of a phase (idle or held), or null if there is none or it's unreadable. */
    static Result read(String phase) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(new File(SCAN_DIR, "andr36oid-gpio-scan-" + phase + ".txt")
                    .toPath(), StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            return null;
        }
        final Result r = new Result();
        for (String line : lines) {
            final String[] f = line.trim().split("\\s+");
            try {
                if (f.length >= 4 && f[0].matches("gpio[0-3]")) {
                    final int b = f[0].charAt(4) - '0';
                    for (int i = 1; i < f.length; i++) {
                        final int eq = f[i].indexOf('=');
                        if (eq < 0) continue;
                        final String key = f[i].substring(0, eq);
                        final String val = f[i].substring(eq + 1);
                        switch (key) {
                            case "low": r.low[b] = hex(val); break;
                            case "high": r.high[b] = hex(val); break;
                            case "changing": r.changing[b] = hex(val); break;
                            case "dir": r.output[b] = hex(val); break;
                            case "mux": r.mux[b] = val; break;
                            default: break;
                        }
                    }
                    r.present[b] = true;
                } else if (f.length == 5 && f[0].equals("adc")) {
                    r.adc.put(f[1] + " " + f[2], new int[] {
                            Integer.parseInt(f[3].substring(f[3].indexOf('=') + 1)),
                            Integer.parseInt(f[4].substring(f[4].indexOf('=') + 1))});
                }
            } catch (RuntimeException e) {
                // a damaged line: left out
            }
        }
        return r;
    }

    private static long hex(String v) {
        return Long.parseLong(v.startsWith("0x") ? v.substring(2) : v, 16);
    }

    /** GPIO2_A5 for bank 2, pin 5 */
    static String pinName(int bank, int pin) {
        return "GPIO" + bank + "_" + (char) ('A' + pin / 8) + (pin % 8);
    }

    /** 2A5 as the Kernel Tweaks setting and the kernel want it */
    static String pinCode(int bank, int pin) {
        return "" + bank + (char) ('A' + pin / 8) + (pin % 8);
    }

    /** The iomux function of a pin (0 is GPIO), -1 if unknown */
    static int mux(Result r, int bank, int pin) {
        final String m = r.mux[bank];
        if (m == null || pin >= m.length()) return -1;
        return Character.digit(m.charAt(pin), 16);
    }

    // ---- Device tree

    /** What the device tree has: known buttons, the extra codes it uses, ADC users. */
    static final class Tree {
        /** bank * 32 + pin -> button name */
        final Map<Integer, String> buttons = new HashMap<>();
        /** scan codes of the joypad's own buttons */
        final List<Integer> joypadCodes = new ArrayList<>();
        /** "device channel" (as in the scan file) -> node using it */
        final Map<String, String> adcUsers = new HashMap<>();
    }

    static Tree tree(Context context) {
        final Tree t = new Tree();
        // gpio bank phandles: pinctrl/gpio2@ff260000/phandle -> 2
        final Map<Integer, Integer> banks = new HashMap<>();
        final File[] pinctrl = new File(DT, "pinctrl").listFiles();
        if (pinctrl != null) {
            for (File f : pinctrl) {
                final String n = f.getName();
                if (!n.matches("gpio[0-3]@.*")) continue;
                final int ph = phandle(f);
                if (ph > 0) banks.put(ph, n.charAt(4) - '0');
            }
        }
        // ADC devices: saradc@ff288000 is "ff288000.saradc" in the scan file
        final Map<Integer, String> adcs = new HashMap<>();
        final File[] top = new File(DT).listFiles();
        if (top == null) return t;
        for (File f : top) {
            final String n = f.getName();
            final int at = n.indexOf('@');
            if (at > 0 && n.contains("adc")) {
                final int ph = phandle(f);
                if (ph > 0) adcs.put(ph, n.substring(at + 1) + "." + n.substring(0, at));
            }
        }
        for (File node : top) {
            if (!node.isDirectory()) continue;
            final int[] io = cells(new File(node, "io-channels"));
            for (int i = 0; io != null && i + 1 < io.length; i += 2) {
                final String dev = adcs.get(io[i]);
                if (dev != null) t.adcUsers.put(dev + " " + io[i + 1], node.getName());
            }
            final String compatible = string(new File(node, "compatible"));
            final boolean joypad = compatible.contains("odroidgo3-joypad");
            if (!joypad && !compatible.contains("gpio-keys")) continue;
            final File[] kids = node.listFiles();
            if (kids == null) continue;
            for (File kid : kids) {
                final int[] g = cells(new File(kid, "gpios"));
                if (g == null || g.length < 2) continue;
                final Integer bank = banks.get(g[0]);
                final int[] code = cells(new File(kid, "linux,code"));
                if (joypad && code != null && code.length > 0) t.joypadCodes.add(code[0]);
                if (bank == null || g[1] < 0 || g[1] >= PINS) continue;
                String name = null;
                if (code != null && code.length > 0 && (ButtonSettings.builtIn(code[0]) != null
                        || ButtonSettings.isExtra(code[0]))) {
                    name = ButtonSettings.buttonName(context, code[0]);
                }
                if (name == null) name = string(new File(kid, "label")).trim();
                if (name.isEmpty()) name = kid.getName();
                t.buttons.put(bank * PINS + g[1], name);
            }
        }
        return t;
    }

    private static int phandle(File node) {
        int[] v = cells(new File(node, "phandle"));
        if (v == null) v = cells(new File(node, "linux,phandle"));
        return v == null || v.length == 0 ? -1 : v[0];
    }

    /** A property as big-endian 32-bit cells, or null */
    private static int[] cells(File f) {
        try {
            final byte[] b = Files.readAllBytes(f.toPath());
            final int[] out = new int[b.length / 4];
            for (int i = 0; i < out.length; i++) {
                out[i] = (b[4 * i] & 0xff) << 24 | (b[4 * i + 1] & 0xff) << 16
                        | (b[4 * i + 2] & 0xff) << 8 | (b[4 * i + 3] & 0xff);
            }
            return out;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static String string(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
                    .replace('\0', ' ');
        } catch (IOException | SecurityException e) {
            return "";
        }
    }
}
