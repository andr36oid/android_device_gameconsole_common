package org.andr36oid.storagecare;

import android.content.Context;
import android.text.format.Formatter;

/** Words for groups, card problems and helper status lines. */
final class Texts {

    private Texts() {
    }

    static String size(Context c, long bytes) {
        return Formatter.formatShortFileSize(c, bytes);
    }

    static String groupTitle(Context c, String group) {
        switch (group) {
            case Scan.STATES: return c.getString(R.string.group_states);
            case Scan.BACKUPS: return c.getString(R.string.group_backups);
            case Scan.RACACHE: return c.getString(R.string.group_racache);
            case Scan.THUMBS: return c.getString(R.string.group_thumbs);
            case Scan.SHOTS: return c.getString(R.string.group_shots);
            case Scan.APKS: return c.getString(R.string.group_apks);
            case Scan.DUPS: return c.getString(R.string.group_dups);
            default: return group;
        }
    }

    /** One line on what the group is, under its title on the review page. */
    static String groupAbout(Context c, String group) {
        switch (group) {
            case Scan.STATES:
                return c.getString(R.string.about_states, Prefs.keepStates(c));
            case Scan.BACKUPS:
                return c.getString(R.string.about_backups, Prefs.keepBackups(c));
            case Scan.RACACHE: return c.getString(R.string.about_racache);
            case Scan.THUMBS: return c.getString(R.string.about_thumbs);
            case Scan.SHOTS: return c.getString(R.string.about_shots);
            case Scan.APKS: return c.getString(R.string.about_apks);
            case Scan.DUPS: return c.getString(R.string.about_dups);
            default: return "";
        }
    }

    static String healthTitle(Context c, String problem) {
        switch (problem) {
            case Health.UNMOUNTABLE: return c.getString(R.string.health_unmountable);
            case Health.READ_ONLY: return c.getString(R.string.health_read_only);
            case Health.ERRORS: return c.getString(R.string.health_errors);
            case Health.FIXED: return c.getString(R.string.health_fixed);
            default: return c.getString(R.string.health_ok);
        }
    }

    static String healthText(Context c, String problem) {
        switch (problem) {
            case Health.UNMOUNTABLE: return c.getString(R.string.health_unmountable_text);
            case Health.READ_ONLY: return c.getString(R.string.health_read_only_text);
            case Health.ERRORS: return c.getString(R.string.health_errors_text);
            case Health.FIXED: return c.getString(R.string.health_fixed_text);
            default: return c.getString(R.string.health_ok_text);
        }
    }

    /** "running scan 1200" and the like, for the button's summary. */
    static String progress(Context c, String status) {
        final String[] w = status.split(" ");
        if (w.length >= 3 && "scan".equals(w[1])) {
            return c.getString(R.string.scan_running_files, w[2]);
        }
        if (w.length >= 2 && "dups".equals(w[1])) return c.getString(R.string.scan_running_dups);
        if (w.length >= 4 && "delete".equals(w[1])) {
            return c.getString(R.string.delete_running, w[2], w[3]);
        }
        return c.getString(R.string.scan_running);
    }
}
