package org.andr36oid.savebackup;

import android.content.Context;
import android.text.format.DateUtils;
import android.text.format.Formatter;

import java.util.ArrayList;
import java.util.List;

/** The words for what the engine reports. */
final class Texts {

    private Texts() {
    }

    /** "Today, 14:02", "Yesterday, 09:10", "Sun, 27 Sep, 14:02" */
    static String when(Context c, long t) {
        final String time = DateUtils.formatDateTime(c, t, DateUtils.FORMAT_SHOW_TIME);
        if (DateUtils.isToday(t)) return c.getString(R.string.today, time);
        if (DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS)) {
            return c.getString(R.string.yesterday, time);
        }
        return DateUtils.formatDateTime(c, t, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY
                | DateUtils.FORMAT_ABBREV_WEEKDAY | DateUtils.FORMAT_ABBREV_MONTH);
    }

    static String size(Context c, long bytes) {
        return Formatter.formatShortFileSize(c, bytes);
    }

    static String files(Context c, int n) {
        return c.getResources().getQuantityString(R.plurals.files, n, n);
    }

    /** The summary under Save backup in Settings and on the page. */
    static String lastSummary(Context c, Index.Last last) {
        final String s;
        if (last.time <= 0) {
            s = c.getString("nothing".equals(last.result)
                    ? R.string.last_no_saves : R.string.last_none);
        } else {
            s = c.getString(R.string.last_backup, when(c, last.time), files(c, last.files));
        }
        if (last.result.startsWith("error")) {
            return c.getString(R.string.last_failed, s, error(c, last.result));
        }
        return s;
    }

    /** Where a backup is: "internal storage, EASYROMS" */
    static String where(Context c, List<String> where) {
        final List<String> names = new ArrayList<>();
        for (String w : where) {
            switch (w) {
                case Roots.INTERNAL: names.add(c.getString(R.string.where_internal)); break;
                case Roots.EASYROMS: names.add(c.getString(R.string.where_easyroms)); break;
                case Roots.USB: names.add(c.getString(R.string.where_usb)); break;
                default: names.add(w); break;
            }
        }
        return String.join(", ", names);
    }

    /** A "running …" status line as a sentence. */
    static String progress(Context c, String status) {
        final String[] f = status.split(" ");
        final String step = f.length > 1 ? f[1] : "";
        switch (step) {
            case "scan": return c.getString(R.string.step_scan);
            case "backup":
                if (f.length >= 4) {
                    return c.getString(R.string.step_backup, Index.num(f[2]) + 1,
                            Index.num(f[3]));
                }
                return c.getString(R.string.step_backup_plain);
            case "copy": return c.getString(R.string.step_copy);
            case "list": return c.getString(R.string.step_list);
            case "plan": return c.getString(R.string.step_plan);
            case "restore": return c.getString(R.string.step_restore);
            default: return c.getString(R.string.step_start);
        }
    }

    /** The end of a backup run, for a toast or the summary. */
    static String backupDone(Context c, String status) {
        final String[] f = status.split(" ");
        if (status.startsWith("done backup") && f.length >= 4) {
            return c.getString(R.string.backup_done, files(c, (int) Index.num(f[3])));
        }
        if (status.startsWith("done unchanged")) return c.getString(R.string.backup_unchanged);
        if (status.startsWith("done nothing")) return c.getString(R.string.backup_nothing);
        return error(c, status);
    }

    static String error(Context c, String status) {
        final String code = status.startsWith("error ") ? status.substring(6) : status;
        switch (code) {
            case "no_storage": return c.getString(R.string.error_no_storage);
            case "no_space": return c.getString(R.string.error_no_space);
            case "write_failed": return c.getString(R.string.error_write_failed);
            case "no_usb": return c.getString(R.string.error_no_usb);
            case "not_found": return c.getString(R.string.error_not_found);
            case "safety_failed": return c.getString(R.string.error_safety_failed);
            case "busy": return c.getString(R.string.error_busy);
            default: return c.getString(R.string.error_start);
        }
    }
}
