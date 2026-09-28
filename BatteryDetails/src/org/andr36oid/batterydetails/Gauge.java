package org.andr36oid.batterydetails;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * One reading of the battery straight from the fuel gauge driver in sysfs (the RK817 on the
 * R36S). Current is positive while charging. The RK817 has no battery temperature sensor, so
 * the temperature is the processor's.
 */
final class Gauge {

    static final int UNKNOWN = Integer.MIN_VALUE;

    long time;
    int percent = UNKNOWN;
    /** mV */
    int voltage = UNKNOWN;
    /** mA, positive going in */
    int current = UNKNOWN;
    /** Design capacity, mAh */
    int capacity = UNKNOWN;
    /** tenths of a degree C */
    int temperature = UNKNOWN;
    String status;

    boolean isCharging() {
        return "Charging".equals(status);
    }

    boolean isFull() {
        return "Full".equals(status);
    }

    static Gauge read() {
        final Gauge gauge = new Gauge();
        gauge.time = System.currentTimeMillis();
        final String dir = findBattery();
        if (dir != null) {
            gauge.percent = readInt(dir + "capacity", 1);
            gauge.voltage = readInt(dir + "voltage_now", 1000);
            gauge.current = readInt(dir + "current_now", 1000);
            gauge.capacity = readInt(dir + "charge_full_design", 1000);
            gauge.status = read(dir + "status");
        }
        final String zone = findThermalZone();
        if (zone != null) {
            gauge.temperature = readInt(zone, 100);
        }
        return gauge;
    }

    /**
     * Minutes until full while charging, or until empty on battery, from the current right
     * now. UNKNOWN when there's nothing sensible to say.
     */
    int minutesLeft() {
        if (percent == UNKNOWN || current == UNKNOWN || capacity == UNKNOWN || capacity <= 0) {
            return UNKNOWN;
        }
        final int milliAmps = Math.abs(current);
        if (milliAmps < 20) {
            return UNKNOWN;
        }
        final float remaining = isCharging() ? (100 - percent) / 100f : percent / 100f;
        final int minutes = Math.round(remaining * capacity * 60 / milliAmps);
        // Charging slows down near the top, and on battery it's only a snapshot.
        return minutes > 0 && minutes < 48 * 60 ? minutes : UNKNOWN;
    }

    static String findBattery() {
        final File[] supplies = new File("/sys/class/power_supply").listFiles();
        if (supplies == null) {
            return null;
        }
        for (File supply : supplies) {
            if ("Battery".equals(read(supply.getPath() + "/type"))) {
                return supply.getPath() + "/";
            }
        }
        return null;
    }

    private static String findThermalZone() {
        final File[] zones = new File("/sys/class/thermal").listFiles(
                (dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null || zones.length == 0) {
            return null;
        }
        for (File zone : zones) {
            final String type = read(zone.getPath() + "/type");
            if (type != null && type.contains("soc")) {
                return zone.getPath() + "/temp";
            }
        }
        return zones[0].getPath() + "/temp";
    }

    static int readInt(String path, int divisor) {
        final String text = read(path);
        if (text == null) {
            return UNKNOWN;
        }
        try {
            return (int) (Long.parseLong(text) / divisor);
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
