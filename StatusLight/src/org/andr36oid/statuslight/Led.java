package org.andr36oid.statuslight;

import android.os.SystemProperties;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The status LED driver's files (kernel drivers/soc/rockchip/andr36oid_status_led.c). A
 * setting is saved in persist.andr36oid.led.<file> and init writes it to the driver, also at
 * boot; "default" goes back to the console's DTB. The colour check writes "raw" directly.
 */
final class Led {

    static final String DIR = "/sys/devices/platform/andr36oid-status-led/";
    static final String PROP = "persist.andr36oid.led.";
    static final String[] SETTINGS = {"table", "battery", "charging", "full", "mode"};

    /** "release": the pins float and the board does what it does without software */
    static final String RELEASE = "release";
    /** charging: keep the battery colours; full: same as while charging */
    static final String FOLLOW = "battery";

    private Led() {
    }

    static boolean present() {
        return read("colors") != null;
    }

    static String read(String file) {
        try (BufferedReader r = new BufferedReader(new FileReader(DIR + file))) {
            final String line = r.readLine();
            return line == null ? null : line.trim();
        } catch (IOException e) {
            return null;
        }
    }

    static boolean write(String file, String value) {
        try (FileWriter w = new FileWriter(DIR + file)) {
            w.write(value);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** colour names; the first is the LED at rest ("off", or green on a genuine R36S) */
    static List<String> colors() {
        final String s = read("colors");
        if (s == null || s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split("\\s+")));
    }

    static int pins() {
        try {
            return Integer.parseInt(read("pins"));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static void save(String file, String value) {
        SystemProperties.set(PROP + file, value);
    }

    static void resetAll() {
        for (String s : SETTINGS) save(s, "default");
    }
}
