package org.andr36oid.gamecomfort;

import java.util.Arrays;

/**
 * When to warn about a low battery. Each threshold warns once per discharge. Charging arms a
 * threshold again once the level is back above it, so a loose cable at 5 % doesn't warn over
 * and over. Plain Java, no Android classes, so it can be tested on the build host.
 */
final class LowBattery {

    /** Nothing warned yet in this discharge. */
    static final int NONE = Integer.MAX_VALUE;

    private LowBattery() {
    }

    /** "15,5" to {15, 5}; "off", empty or junk to none. */
    static int[] parse(String value) {
        if (value == null || value.isEmpty() || "off".equals(value)) {
            return new int[0];
        }
        final String[] parts = value.split(",");
        final int[] thresholds = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                final int t = Integer.parseInt(part.trim());
                if (t > 0 && t < 100) {
                    thresholds[count++] = t;
                }
            } catch (NumberFormatException e) {
                // skip it
            }
        }
        return Arrays.copyOf(thresholds, count);
    }

    /**
     * The lowest threshold at or above the level, NONE if the level is above them all. That
     * is the threshold to warn about: starting at 4 % warns once for 5 %, not for 15 % too.
     */
    static int reached(int[] thresholds, int percent) {
        int result = NONE;
        for (int t : thresholds) {
            if (percent <= t && t < result) {
                result = t;
            }
        }
        return result;
    }

    /**
     * The new "warned" state after a battery update. {@code warned} is the lowest threshold
     * already warned about in this discharge, NONE if none.
     */
    static int nextWarned(int[] thresholds, int warned, int percent, boolean plugged) {
        if (percent < 0) {
            return warned;
        }
        final int reached = reached(thresholds, percent);
        if (plugged) {
            // Charging: forget the thresholds the level is back above, keep the rest.
            return warned == NONE || reached == NONE ? NONE : Math.max(warned, reached);
        }
        return Math.min(warned, reached);
    }

    /** The threshold to warn about now, or NONE. Only while running on battery. */
    static int warnAbout(int[] thresholds, int warned, int percent, boolean plugged) {
        if (plugged || percent < 0) {
            return NONE;
        }
        final int reached = reached(thresholds, percent);
        return reached < warned ? reached : NONE;
    }
}
