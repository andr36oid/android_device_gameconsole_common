package org.andr36oid.cardcheck;

import android.content.Context;
import android.os.Build;
import android.text.format.Formatter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
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
        final int level = Verdict.level(s);
        final String verdict = c.getString(level == Verdict.GOOD ? R.string.speed_good
                : level == Verdict.OK ? R.string.speed_ok : R.string.speed_slow);
        final StringBuilder sb = new StringBuilder(
                c.getString(R.string.speed_result, mbps(c, w), mbps(c, r)));
        final double reads = Stats.iops(s, "rr");
        final double writes = Stats.iops(s, "rw");
        if (!Double.isNaN(reads) && !Double.isNaN(writes)) {
            sb.append('\n').append(c.getString(R.string.speed_small, whole(reads),
                    whole(writes)));
        }
        return sb.append('\n').append(verdict).toString();
    }

    /** 1850.4 -> "1,850" (as the language writes it). */
    static String whole(double v) {
        return String.format(Locale.getDefault(), "%,d", Math.round(v));
    }

    /** "Marked Class 10, U3, A1" and how the console talks to the card; may be empty. */
    static String cardLines(Context c, CardId id, Map<String, String> speed) {
        final StringBuilder sb = new StringBuilder();
        if (id != null && !id.marks().isEmpty()) {
            sb.append(c.getString(R.string.details_marks, id.marks()));
        }
        final Verdict.Bus bus = Verdict.Bus.of(speed.get("bus"));
        if (bus != Verdict.Bus.UNKNOWN) {
            if (sb.length() > 0) sb.append('\n');
            if (bus.uhs()) {
                sb.append(c.getString(R.string.details_bus_fast, bus.name()));
            } else if (bus.mbps >= 25) {
                sb.append(c.getString(R.string.details_bus_normal, bus.mbps));
            } else {
                sb.append(c.getString(R.string.details_bus_slow, bus.mbps));
            }
        }
        return sb.toString();
    }

    /** "What this means": the advice for this card, one paragraph per point. */
    static String advice(Context c, Card card, CardId id, Map<String, String> speed,
            Map<String, String> full, boolean paused) {
        final Calendar now = Calendar.getInstance();
        final int year = clockTrusted() ? now.get(Calendar.YEAR) : 0;
        final List<Verdict.Item> items = Verdict.advise(id, card.diskBytes, speed, full,
                paused, year, now.get(Calendar.MONTH) + 1);
        final StringBuilder sb = new StringBuilder();
        for (Verdict.Item i : items) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(adviceItem(c, id, i));
        }
        return sb.toString();
    }

    /**
     * After an empty battery the clock starts at the build date (Android moves an older
     * clock up to it), so an offline console may be far behind. Until the clock is well
     * past the build date, a card made "in the future" isn't counted as a fake sign.
     */
    private static boolean clockTrusted() {
        return System.currentTimeMillis() > Build.TIME + CLOCK_SLACK_MS;
    }

    private static final long CLOCK_SLACK_MS = 30L * 24 * 60 * 60 * 1000;

    private static String adviceItem(Context c, CardId id, Verdict.Item i) {
        switch (i.kind) {
            case PAUSED: return c.getString(R.string.advice_paused);
            case FAKE: return c.getString(R.string.advice_fake);
            case WRITE_ERROR: return c.getString(R.string.advice_write_error);
            case SIGNS: return c.getString(R.string.advice_signs, signs(c, id, i.signs));
            case SIGNS_BUT_REAL:
                return c.getString(R.string.advice_signs_but_real, signs(c, id, i.signs));
            case CLAIM_WRITE:
                return c.getString(R.string.advice_claim_write, (int) i.a, mbps(c, i.b));
            case CLAIM_SMALL: return c.getString(R.string.advice_claim_small, (int) i.a);
            case BUS_SLOW: return c.getString(R.string.advice_bus_slow, (int) i.a);
            case SLOW: return c.getString(R.string.advice_slow);
            case UNTESTED: return c.getString(R.string.advice_untested);
            default: return c.getString(R.string.advice_good);
        }
    }

    private static String signs(Context c, CardId id, List<CardId.Sign> signs) {
        final List<String> words = new ArrayList<>();
        for (CardId.Sign sign : signs) {
            switch (sign) {
                case NO_MAKER:
                    words.add(c.getString(R.string.sign_no_maker));
                    break;
                case BRAND_MISMATCH:
                    words.add(c.getString(R.string.sign_brand_mismatch));
                    break;
                case SIZE_MISMATCH:
                    words.add(c.getString(R.string.sign_size_mismatch));
                    break;
                case SPEC_TOO_OLD:
                    words.add(c.getString(R.string.sign_spec_too_old));
                    break;
                case BAD_DATE:
                    words.add(c.getString(R.string.sign_bad_date,
                            String.format(Locale.US, "%02d/%04d", id.month, id.year)));
                    break;
                case TOO_OLD_FOR_SIZE:
                    words.add(c.getString(R.string.sign_too_old, id.year));
                    break;
                default:
                    break;
            }
        }
        return String.join("; ", words);
    }

    /** The summary of a paused real size check, from its progress file. */
    static String paused(Context c, Map<String, String> progress) {
        final long total = Stats.number(progress, "total_mb");
        if ("verify".equals(progress.get("phase"))) {
            return c.getString(R.string.full_paused_verify,
                    size(c, Stats.number(progress, "checked_mb")), size(c, total));
        }
        return c.getString(R.string.full_paused_write,
                size(c, Stats.number(progress, "written_mb")), size(c, total));
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
        } else if ("paused".equals(code)) {
            res = R.string.error_paused;
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
