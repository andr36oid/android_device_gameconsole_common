package org.andr36oid.batterydetails;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * What the RK817 charger driver sees right now, from its andr36oid_diag file: the power
 * chip's plug-in flag, the charger detect pin, the driver's charger state, the 5 V boost for
 * USB devices, how often each plug interrupt fired since boot and the charger registers.
 */
final class ChargeDiag {

    private static final String FILE = "/sys/class/power_supply/ac/device/andr36oid_diag";

    /** RK817 charger states (CHG_STS, register 0xEB bits 4-6) */
    static final int STS_OFF = 0;
    static final int STS_DONE = 4;

    private final Map<String, String> mValues;
    final Gauge gauge;
    /** The USB phy sees 5 V on the port (1/0), Gauge.UNKNOWN without its extcon */
    final int vbus;

    private ChargeDiag(Map<String, String> values, Gauge gauge, int vbus) {
        mValues = values;
        this.gauge = gauge;
        this.vbus = vbus;
    }

    static boolean isSupported() {
        return Files.isReadable(Paths.get(FILE));
    }

    static ChargeDiag read() {
        final Map<String, String> values = new HashMap<>();
        try {
            for (String line : new String(Files.readAllBytes(Paths.get(FILE)),
                    StandardCharsets.UTF_8).split("\n")) {
                final int eq = line.indexOf('=');
                if (eq > 0) values.put(line.substring(0, eq), line.substring(eq + 1).trim());
            }
        } catch (IOException | SecurityException e) {
            // all unknown
        }
        return new ChargeDiag(values, Gauge.read(), readVbus());
    }

    /**
     * The USB phy's extcon (the one with a USB_HOST cable) says USB or a USB charger type
     * while it sees 5 V coming in on the port.
     */
    private static int readVbus() {
        final File[] devices = new File("/sys/class/extcon").listFiles();
        if (devices == null) return Gauge.UNKNOWN;
        for (File device : devices) {
            final String state;
            try {
                state = new String(Files.readAllBytes(new File(device, "state").toPath()),
                        StandardCharsets.UTF_8);
            } catch (IOException | SecurityException e) {
                continue;
            }
            if (!state.contains("USB_HOST=")) continue;
            for (String line : state.split("\n")) {
                if ((line.startsWith("USB=") || line.startsWith("CHG_USB"))
                        && line.endsWith("=1")) {
                    return 1;
                }
            }
            return 0;
        }
        return Gauge.UNKNOWN;
    }

    String raw(String key) {
        return mValues.get(key);
    }

    /** The value as a number (hex for reg_*), Gauge.UNKNOWN when missing. */
    int get(String key) {
        final String value = mValues.get(key);
        if (value == null) return Gauge.UNKNOWN;
        try {
            return Integer.parseInt(value, key.startsWith("reg_") ? 16 : 10);
        } catch (NumberFormatException e) {
            return Gauge.UNKNOWN;
        }
    }

    boolean is(String key) {
        return get(key) == 1;
    }

    /** The driver counts a charger (DC or USB) as plugged in */
    boolean driverSeesCharger() {
        return is("usb_in") || is("dc_in");
    }

    /** The 5 V boost for USB devices is on (register 0xB4 bit 1) */
    boolean boostOn() {
        final int b4 = get("reg_b4");
        return b4 != Gauge.UNKNOWN && (b4 & 0x02) != 0;
    }

    /** Current into the battery, mA, Gauge.UNKNOWN if unknown */
    int currentMa() {
        return gauge.current;
    }

    /** Every register as "b4=63 e4=.." for the screen and the log */
    String registers() {
        final StringBuilder text = new StringBuilder();
        for (String reg : new String[] {"b4", "e4", "e5", "e6", "eb", "ec"}) {
            final String value = mValues.get("reg_" + reg);
            if (value == null) continue;
            if (text.length() > 0) text.append("  ");
            text.append(reg.toUpperCase()).append('=').append(value);
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return mValues.toString() + " current=" + gauge.current + " status=" + gauge.status;
    }
}
