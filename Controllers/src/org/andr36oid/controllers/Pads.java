package org.andr36oid.controllers;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The game controllers Android sees, with what sysfs adds: how they are connected and the
 * battery that their kernel driver reports (hid-sony, hid-playstation, hid-nintendo, hid-input
 * batteries of Bluetooth pads...). Android 11 has no API for a pad's battery.
 */
final class Pads {

    static final int BUS_OTHER = 0;
    static final int BUS_BUILTIN = 1;
    static final int BUS_USB = 2;
    static final int BUS_BLUETOOTH = 3;

    /** The built-in pad: odroidgo3-joypad, 0x484b:0x1100 ("GO-Super Gamepad"). */
    static final int BUILTIN_VENDOR = 0x484b;

    private static final String INPUT = "/sys/class/input";
    private static final String POWER_SUPPLY = "/sys/class/power_supply";

    static final class Pad {
        String name;
        int bus;
        /** 0-100, or -1 */
        int batteryPercent = -1;
        /** "Full", "High", "Normal", "Low", "Critical" when there is no percentage */
        String batteryLevel;
        boolean charging;
        boolean hasBattery;

        boolean isBuiltIn() {
            return bus == BUS_BUILTIN;
        }
    }

    /** One /sys/class/input/inputN */
    private static final class Node {
        String name;
        int vendor;
        int product;
        int bustype;
        String parent;
        boolean used;
    }

    private static final class Battery {
        int percent = -1;
        String level;
        boolean charging;
    }

    private Pads() {
    }

    static List<Pad> list(Context context) {
        final List<Node> nodes = readNodes();
        final Map<String, Battery> batteries = readBatteries();
        final List<Pad> pads = new ArrayList<>();
        final InputManager im = context.getSystemService(InputManager.class);
        for (int id : im.getInputDeviceIds()) {
            final InputDevice device = im.getInputDevice(id);
            if (device == null || device.isVirtual() || !isPad(device)) continue;
            // joyMouse's copy of the built-in pad (uinput) has the same name and IDs as the
            // real one: only as many pads per name as sysfs has real devices for
            final Node node = take(nodes, device);
            if (node == null) continue;
            final Pad pad = new Pad();
            pad.name = device.getName();
            if (device.getVendorId() == BUILTIN_VENDOR && !device.isExternal()) {
                pad.bus = BUS_BUILTIN;
            } else if (node.bustype == 0x03) {
                pad.bus = BUS_USB;
            } else if (node.bustype == 0x05) {
                pad.bus = BUS_BLUETOOTH;
            } else {
                pad.bus = device.isExternal() ? BUS_OTHER : BUS_BUILTIN;
            }
            final Battery battery = node.parent != null ? batteries.get(node.parent) : null;
            if (battery != null) {
                pad.hasBattery = true;
                pad.batteryPercent = battery.percent;
                pad.batteryLevel = battery.level;
                pad.charging = battery.charging;
            }
            pads.add(pad);
        }
        // the built-in pad first
        final List<Pad> sorted = new ArrayList<>();
        for (Pad pad : pads) if (pad.isBuiltIn()) sorted.add(pad);
        for (Pad pad : pads) if (!pad.isBuiltIn()) sorted.add(pad);
        return sorted;
    }

    static boolean isPad(InputDevice device) {
        final int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static Node take(List<Node> nodes, InputDevice device) {
        for (Node node : nodes) {
            if (!node.used && node.vendor == device.getVendorId()
                    && node.product == device.getProductId()
                    && node.name.equals(device.getName())) {
                node.used = true;
                return node;
            }
        }
        return null;
    }

    private static List<Node> readNodes() {
        final List<Node> nodes = new ArrayList<>();
        final File[] dirs = new File(INPUT).listFiles();
        if (dirs == null) return nodes;
        for (File dir : dirs) {
            if (!dir.getName().matches("input[0-9]+")) continue;
            final String path = canonical(dir);
            // uinput devices (joyMouse's copies, touch controls) sit right in virtual/input;
            // Bluetooth pads through uhid are in virtual/misc/uhid and count
            if (path == null || path.startsWith("/sys/devices/virtual/input/")) continue;
            final Node node = new Node();
            node.name = read(new File(dir, "name"));
            if (node.name == null) continue;
            node.vendor = hex(read(new File(dir, "id/vendor")));
            node.product = hex(read(new File(dir, "id/product")));
            node.bustype = hex(read(new File(dir, "id/bustype")));
            node.parent = canonical(new File(dir, "device"));
            nodes.add(node);
        }
        return nodes;
    }

    /** Batteries by the device they belong to (the HID device, same parent as its inputs). */
    private static Map<String, Battery> readBatteries() {
        final Map<String, Battery> batteries = new HashMap<>();
        final File[] supplies = new File(POWER_SUPPLY).listFiles();
        if (supplies == null) return batteries;
        for (File supply : supplies) {
            // pads say "Device"; the console's own battery and chargers say "System" or nothing
            if (!"Device".equals(read(new File(supply, "scope")))) continue;
            final String parent = canonical(new File(supply, "device"));
            if (parent == null) continue;
            final Battery battery = new Battery();
            final String capacity = read(new File(supply, "capacity"));
            if (capacity != null) {
                try {
                    battery.percent = Math.max(0, Math.min(100, Integer.parseInt(capacity)));
                } catch (NumberFormatException e) {
                    battery.percent = -1;
                }
            }
            battery.level = read(new File(supply, "capacity_level"));
            if ("Unknown".equals(battery.level)) battery.level = null;
            battery.charging = "Charging".equals(read(new File(supply, "status")));
            batteries.put(parent, battery);
        }
        return batteries;
    }

    private static String canonical(File file) {
        try {
            return file.exists() ? file.getCanonicalPath() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String read(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            final String line = reader.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static int hex(String value) {
        if (value == null) return -1;
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
