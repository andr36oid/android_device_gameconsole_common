package org.andr36oid.bioscheck;

import android.content.Context;

import org.andr36oid.bioscheck.BiosTable.Bios;
import org.andr36oid.bioscheck.Report.FileState;
import org.andr36oid.bioscheck.Report.SystemState;
import org.andr36oid.bioscheck.Scan.Found;

import java.util.ArrayList;
import java.util.List;

/** The words for what the report found. */
final class Texts {

    private static final String MEDIA = "/data/media/0";
    private static final String MEDIA_RW = "/mnt/media_rw/";

    private Texts() {
    }

    /** A path as the user knows it: Internal storage/..., EASYROMS/... */
    static String path(Context c, String path) {
        if (path == null) return "";
        if (path.equals(MEDIA) || path.startsWith(MEDIA + "/")) {
            return c.getString(R.string.path_internal) + path.substring(MEDIA.length());
        }
        if (path.startsWith(MEDIA_RW)) {
            final int slash = path.indexOf('/', MEDIA_RW.length());
            return c.getString(R.string.path_easyroms)
                    + (slash < 0 ? "" : path.substring(slash));
        }
        return path;
    }

    static String systemSummary(Context c, SystemState s) {
        switch (s.level) {
            case Report.LEVEL_OK:
                return c.getString(R.string.level_ok, names(s, Report.OK));
            case Report.LEVEL_READY:
                return c.getString(R.string.level_ready);
            case Report.LEVEL_WRONG:
                return c.getString(R.string.level_wrong, names(s, Report.WRONG));
            case Report.LEVEL_RECOMMENDED_MISSING:
                return c.getString(R.string.level_recommended_missing);
            case Report.LEVEL_REQUIRED_MISSING: {
                final List<String> missing = new ArrayList<>();
                for (FileState f : s.files) {
                    if (f.bios.need == BiosTable.REQUIRED && f.state == Report.MISSING) {
                        missing.add(f.bios.name);
                    }
                }
                return c.getString(R.string.level_required_missing, String.join(", ", missing));
            }
            default:
                return c.getString(R.string.level_optional_missing);
        }
    }

    private static String names(SystemState s, int state) {
        final List<String> l = new ArrayList<>();
        for (FileState f : s.files) {
            if (f.state == state) l.add(f.bios.name);
        }
        return String.join(", ", l);
    }

    /** The first line of a file on the detail page. */
    static String fileState(Context c, FileState f) {
        switch (f.state) {
            case Report.OK:
                return c.getString(R.string.state_ok);
            case Report.READY:
                if (!f.source.inBiosFolder) return c.getString(R.string.state_ready_elsewhere);
                return f.source.name.equals(f.bios.name) ? c.getString(R.string.state_ready)
                        : c.getString(R.string.state_ready_as, f.source.name);
            case Report.WRONG: {
                if (f.source != null) return c.getString(R.string.state_conflict);
                final String what = f.wrongIs.isEmpty()
                        ? c.getString(R.string.state_wrong_unknown)
                        : c.getString(R.string.state_wrong_is, f.wrongIs.get(0).name,
                                f.wrongIs.get(0).description);
                return what + " " + c.getString(f.wrong.inBiosFolder
                        ? R.string.state_wrong_in_bios : R.string.state_wrong_in_system);
            }
            default:
                return c.getString(f.bios.need == BiosTable.REQUIRED
                        ? R.string.state_missing_required
                        : f.bios.need == BiosTable.ONE_OF ? R.string.state_missing_oneof
                        : R.string.state_missing_optional);
        }
    }

    /** Everything about one file, for its dialog. */
    static String fileDetails(Context c, FileState f) {
        final Bios b = f.bios;
        final StringBuilder sb = new StringBuilder(fileState(c, f)).append("\n\n");
        sb.append(c.getString(R.string.details_what, b.description)).append('\n');
        sb.append(c.getString(R.string.details_cores, String.join(", ", b.cores))).append('\n');
        sb.append(b.anyContent() ? c.getString(R.string.details_md5_any)
                : c.getString(R.string.details_md5, String.join(", ", b.md5s)));
        for (Found x : new Found[] { f.installed, f.source, f.wrong }) {
            if (x == null) continue;
            sb.append("\n\n").append(c.getString(R.string.details_found, path(c, x.path),
                    x.md5 != null ? x.md5 : "-"));
        }
        return sb.toString();
    }

    /** What the helper did, for a toast. */
    static String applied(Context c, List<String> result) {
        int copied = 0, renamed = 0, kept = 0, failed = 0;
        for (String line : result) {
            if (line.startsWith("copied ")) copied++;
            else if (line.startsWith("renamed ")) renamed++;
            else if (line.startsWith("kept ") || line.startsWith("exists ")) kept++;
            else if (line.startsWith("failed ")) failed++;
        }
        final StringBuilder sb = new StringBuilder();
        if (copied > 0) sb.append(plural(c, R.plurals.done_copied, copied));
        if (renamed > 0) sb.append(plural(c, R.plurals.done_renamed, renamed));
        if (kept > 0) sb.append(plural(c, R.plurals.done_kept, kept));
        if (failed > 0) sb.append(plural(c, R.plurals.done_failed, failed));
        return sb.length() > 0 ? sb.toString().trim() : c.getString(R.string.done_nothing);
    }

    private static String plural(Context c, int id, int n) {
        return c.getResources().getQuantityString(id, n, n) + " ";
    }
}
