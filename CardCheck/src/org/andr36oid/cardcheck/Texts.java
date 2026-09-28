package org.andr36oid.cardcheck;

import android.content.Context;
import android.text.format.Formatter;

import java.util.Map;

/** The words for sizes, times and results, shared by the page and the notification. */
final class Texts {

    private static final long MB = 1024 * 1024;

    private Texts() {
    }

    static String size(Context c, long mb) {
        return Formatter.formatFileSize(c, Math.max(0, mb) * MB);
    }

    static String duration(Context c, double seconds) {
        final int minutes = (int) Math.max(1, Math.round(seconds / 60));
        if (minutes < 60) return c.getString(R.string.minutes, minutes);
        return c.getString(R.string.hours_minutes, minutes / 60, minutes % 60);
    }

    static String mbps(Context c, double v) {
        return c.getString(R.string.mb_per_s, v);
    }

    /** Result of a speed test, null if the map holds none. */
    static String speedResult(Context c, Map<String, String> s) {
        if ("failed".equals(s.get("state"))) return error(c, s);
        if (!"done".equals(s.get("state"))) return null;
        final long mb = Checker.number(s, "size_mb");
        final double w = Checker.speed(s, mb, "w0", "w1");
        final double r = Checker.speed(s, mb, "r0", "r1");
        if (Double.isNaN(w) || Double.isNaN(r)) return null;
        final String verdict;
        if (w >= 10 && r >= 40) {
            verdict = c.getString(R.string.speed_good);
        } else if (r >= 15 && w >= 3) {
            verdict = c.getString(R.string.speed_ok);
        } else {
            verdict = c.getString(R.string.speed_slow);
        }
        return c.getString(R.string.speed_result, mbps(c, w), mbps(c, r)) + "\n" + verdict;
    }

    /** Result of a real size check, null if the map holds none. */
    static String fullResult(Context c, Map<String, String> s) {
        final String state = s.get("state");
        if ("failed".equals(state)) return error(c, s);
        if ("stopped".equals(state)) return c.getString(R.string.full_stopped);
        if (!"done".equals(state)) return null;
        final String result = s.get("result");
        if (result == null) return null;
        final long total = Checker.number(s, "total_mb");
        final long good = Checker.number(s, "good_mb");
        final long used = Checker.number(s, "used_mb");
        switch (result) {
            case "pass":
                return c.getString(R.string.full_pass, size(c, total));
            case "bad_data":
                return c.getString(R.string.full_bad_data,
                        size(c, Checker.number(s, "bad_mb")), size(c, good),
                        size(c, used + good));
            case "wraps": {
                String text = c.getString(R.string.full_wraps,
                        size(c, Checker.number(s, "real_mb")));
                final String restored = s.get("restored");
                if ("yes".equals(restored)) {
                    text += " " + c.getString(R.string.full_wraps_restored);
                } else if ("no".equals(restored)) {
                    text += " " + c.getString(R.string.full_wraps_not_restored);
                }
                return text;
            }
            case "write_error":
                return c.getString(R.string.full_write_error, size(c, good));
            default:
                return null;
        }
    }

    static String error(Context c, Map<String, String> s) {
        final String code = s.get("error");
        final int res;
        if ("no_space".equals(code)) {
            res = R.string.error_no_space;
        } else if ("not_mounted".equals(code)) {
            res = R.string.error_not_mounted;
        } else if ("write_error".equals(code)) {
            res = R.string.error_write;
        } else if ("read_error".equals(code)) {
            res = R.string.error_read;
        } else {
            res = R.string.error_other;
        }
        return c.getString(R.string.error_prefix, c.getString(res));
    }

    /**
     * Seconds a real size check of this much data takes, from the card's last speed
     * test, with some time for the checksums and file handling. NaN without a test.
     */
    static double fullEstimate(Map<String, String> speed, long mb) {
        if (!"done".equals(speed.get("state"))) return Double.NaN;
        final long size = Checker.number(speed, "size_mb");
        final double w = Checker.speed(speed, size, "w0", "w1");
        final double r = Checker.speed(speed, size, "r0", "r1");
        if (!(w > 0) || !(r > 0)) return Double.NaN;
        return (mb / w + mb / r) * 1.2;
    }
}
