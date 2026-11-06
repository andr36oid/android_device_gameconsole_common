package org.andr36oid.rgblights;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemProperties;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * The stick-ring RGB of the R36 Ultra (and any clone that wires it). On the Ultra
 * the rings are a "keros" effect-LED IC that plays ten fixed built-in presets
 * (0 = off, 1-7 steady colours, 8 breathing, 9 rainbow), exposed at
 * {@code /sys/class/leds/keros::ambient/brightness}. Apps cannot write the driver
 * node, so the chosen preset index is kept in a persistent property and
 * init.rk30board.rc writes it to the node, at boot and whenever it changes - the
 * same path the stick calibration uses. The mapping is 1:1: the property value IS
 * the keros brightness/preset value.
 */
final class RgbLights {

    /* Must match the property trigger in init.rk30board.rc. The value is the keros
     * preset index 0..9, written verbatim to the brightness node. */
    static final String PROP_PRESET = "persist.andr36oid.stickled.preset";

    /* When the rings turn off: "screen" | "deepsleep" | "never". init maps it to
     * the keros driver's sleep_mode attr (0/1/2). */
    static final String PROP_SLEEP = "persist.andr36oid.stickled.sleep";

    /** The keros brightness/preset node; its presence means Ultra-class rings. */
    static final String KEROS_NODE = "/sys/class/leds/keros::ambient/brightness";

    /* Joypad LED-engine sysfs, used only as an isPresent() fallback for clones that
     * drive the rings through the odroidgo3-joypad engine rather than a keros chip. */
    private static final String[] DIRS = {
            "/sys/devices/platform/odroidgo3-joypad",
            "/sys/devices/platform/odroidgo3_joypad",
    };

    private RgbLights() {
    }

    private static File dir() {
        for (String path : DIRS) {
            final File d = new File(path);
            if (new File(d, "led_mode").exists()) {
                return d;
            }
        }
        return null;
    }

    /** Whether this console has controllable stick lights. */
    static boolean isPresent() {
        if (new File(KEROS_NODE).exists()) {
            return true;
        }
        final String mode = readNode("led_mode");
        return mode != null && !"unsupported".equals(mode);
    }

    static String readNode(String name) {
        final File d = dir();
        if (d == null) {
            return null;
        }
        final File node = new File(d, name);
        try (BufferedReader r = new BufferedReader(new FileReader(node))) {
            final String line = r.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Set the keros preset directly (0..9); init writes it to the brightness node. */
    static void setPreset(int index) {
        SystemProperties.set(PROP_PRESET, Integer.toString(index));
    }

    /** Read the saved preset back; returns {@code fallback} if unset/unreadable. */
    static int readPreset(int fallback) {
        final String v = SystemProperties.get(PROP_PRESET, "");
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            final int n = Integer.parseInt(v.trim());
            return (n >= 0 && n <= 9) ? n : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Set the sleep behaviour ("screen" | "deepsleep" | "never"). */
    static void setSleep(String value) {
        SystemProperties.set(PROP_SLEEP, value);
    }

    /** Read the saved sleep behaviour; returns {@code fallback} if unset. */
    static String readSleep(String fallback) {
        final String v = SystemProperties.get(PROP_SLEEP, "");
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    /** Shows the Settings entry only where the lights exist. */
    static void updateEntry(Context context) {
        final ComponentName entry = new ComponentName(context, RgbLightsActivity.class);
        final int state = isPresent() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        final PackageManager pm = context.getPackageManager();
        if (pm.getComponentEnabledSetting(entry) != state) {
            pm.setComponentEnabledSetting(entry, state, PackageManager.DONT_KILL_APP);
        }
    }
}
