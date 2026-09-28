package org.andr36oid.controllertest;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Battery readings for the tester. The fuel gauge's own numbers come from
 * /sys/class/power_supply, which is what the battery drain investigation needs; if that
 * can't be read, BatteryManager's view of it is used instead.
 */
final class Battery {

    private static final String POWER_SUPPLY = "/sys/class/power_supply";

    /** What the tester shows, e.g. "battery  87%  3.980 V  -412 mA  31.2 °C  Discharging". */
    static final class Reading {
        /** The battery's line */
        final String battery;
        /** The chargers on one line, e.g. "usb  connected  5.00 V | ac  not connected" */
        final String others;
        /** 0-100, or -1 if unknown */
        final int percent;
        final boolean charging;

        Reading(String battery, String others, int percent, boolean charging) {
            this.battery = battery;
            this.others = others;
            this.percent = percent;
            this.charging = charging;
        }
    }

    private final Context mContext;

    Battery(Context context) {
        mContext = context;
    }

    Reading read() {
        final Reading sysfs = readSysfs();
        return sysfs != null ? sysfs : readBatteryManager();
    }

    private Reading readSysfs() {
        final File[] supplies = new File(POWER_SUPPLY).listFiles();
        if (supplies == null || supplies.length == 0) {
            return null;
        }
        Arrays.sort(supplies);
        String battery = null;
        final List<String> others = new ArrayList<>();
        int percent = -1;
        boolean charging = false;
        for (File supply : supplies) {
            final String type = read(new File(supply, "type"));
            final StringBuilder line = new StringBuilder(supply.getName());
            if ("Battery".equals(type)) {
                final String capacity = read(new File(supply, "capacity"));
                final String status = read(new File(supply, "status"));
                if (capacity != null) {
                    line.append("  ").append(capacity).append('%');
                    try {
                        percent = Integer.parseInt(capacity);
                    } catch (NumberFormatException e) {
                        // leave unknown
                    }
                }
                appendMicro(line, supply, "voltage_now", "%.3f V");
                appendMicro(line, supply, "current_now", "%+.0f mA", 1000);
                final String temp = read(new File(supply, "temp"));
                if (temp != null) {
                    try {
                        line.append(String.format(Locale.US, "  %.1f °C",
                                Integer.parseInt(temp) / 10f));
                    } catch (NumberFormatException e) {
                        // skip
                    }
                }
                appendMicro(line, supply, "charge_now", "%.0f mAh", 1000);
                if (status != null) {
                    line.append("  ").append(status);
                    charging = "Charging".equals(status) || "Full".equals(status);
                }
            } else {
                final String online = read(new File(supply, "online"));
                if (online == null) {
                    continue;
                }
                line.append("  ").append("1".equals(online) ? "connected" : "not connected");
                appendMicro(line, supply, "voltage_now", "%.2f V");
                appendMicro(line, supply, "current_max", "max %.0f mA", 1000);
                others.add(line.toString());
                continue;
            }
            if (battery == null) {
                battery = line.toString();
            } else {
                others.add(line.toString());
            }
        }
        if (battery == null) {
            return null;
        }
        return new Reading(battery, String.join("  |  ", others), percent, charging);
    }

    private Reading readBatteryManager() {
        final Intent intent = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null) {
            return new Reading(mContext.getString(R.string.test_battery_unknown), "", -1,
                    false);
        }
        final BatteryManager manager = mContext.getSystemService(BatteryManager.class);
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final int percent = level >= 0 && scale > 0 ? level * 100 / scale : -1;
        final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        final boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        final StringBuilder line = new StringBuilder("battery");
        line.append("  ").append(percent).append('%');
        line.append(String.format(Locale.US, "  %.3f V",
                intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f));
        if (manager != null) {
            final int current = manager.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (current != Integer.MIN_VALUE) {
                line.append(String.format(Locale.US, "  %+.0f mA", current / 1000f));
            }
        }
        line.append(String.format(Locale.US, "  %.1f °C",
                intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f));
        line.append(charging ? "  Charging" : "  Discharging");
        return new Reading(line.toString(), "", percent, charging);
    }

    private static void appendMicro(StringBuilder line, File supply, String name,
            String format) {
        appendMicro(line, supply, name, format, 1000000);
    }

    /** Appends a sysfs value given in micro units, divided by {@code divisor}. */
    private static void appendMicro(StringBuilder line, File supply, String name,
            String format, int divisor) {
        final String value = read(new File(supply, name));
        if (value == null) {
            return;
        }
        try {
            line.append("  ").append(String.format(Locale.US, format,
                    Long.parseLong(value) / (double) divisor));
        } catch (NumberFormatException e) {
            // skip
        }
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
