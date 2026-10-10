package org.andr36oid.cardcheck;

import java.util.Map;

/** Numbers out of the helper's key=value status and result files. Plain Java. */
final class Stats {

    private Stats() {
    }

    /** Seconds from a "seconds.nanoseconds" value, NaN if missing. */
    static double seconds(Map<String, String> s, String key) {
        try {
            return Double.parseDouble(s.get(key));
        } catch (NullPointerException | NumberFormatException e) {
            return Double.NaN;
        }
    }

    static long number(Map<String, String> s, String key) {
        try {
            return Long.parseLong(s.get(key));
        } catch (NullPointerException | NumberFormatException e) {
            return -1;
        }
    }

    /** MB/s between two time keys, NaN if they're missing. */
    static double speed(Map<String, String> s, long mb, String from, String to) {
        final double t = seconds(s, to) - seconds(s, from);
        return t > 0 ? mb / t : Double.NaN;
    }

    /** Small blocks a second for "rr" (reads) or "rw" (writes), NaN if not measured. */
    static double iops(Map<String, String> s, String prefix) {
        final long ops = number(s, prefix + "_ops");
        final double t = seconds(s, prefix + "_s");
        return ops > 0 && t > 0 ? ops / t : Double.NaN;
    }
}
