package org.andr36oid.bioscheck;

import android.content.Context;

import org.andr36oid.bioscheck.BiosTable.Bios;
import org.andr36oid.bioscheck.Report.FileState;
import org.andr36oid.bioscheck.Report.SystemState;

import java.util.ArrayList;
import java.util.List;

/** The words for what the report found. */
final class Texts {

    private static final String MEDIA_RW = "/mnt/media_rw/";

    private Texts() {
    }

    /** A path as the user knows it: EASYROMS/bios/... */
    static String path(Context c, String path) {
        if (path == null || !path.startsWith(MEDIA_RW)) return path;
        final int slash = path.indexOf('/', MEDIA_RW.length());
        return c.getString(R.string.path_easyroms) + (slash < 0 ? "" : path.substring(slash));
    }

    /** A system's one line on the main page. */
    static String systemSummary(Context c, SystemState s) {
        switch (s.level) {
            case Report.LEVEL_READY:
                return c.getString(R.string.level_ready);
            case Report.LEVEL_MISSING:
                return c.getString(R.string.level_missing, names(s, Report.MISSING, true));
            case Report.LEVEL_MISSING_ONE_OF:
                return c.getString(R.string.level_missing_one_of, firstOneOf(s));
            case Report.LEVEL_WRONG_VERSION:
                return c.getString(R.string.level_wrong_version,
                        names(s, Report.WRONG_VERSION, false));
            case Report.LEVEL_WRONG_NAME:
                return c.getString(R.string.level_wrong_name);
            default:
                return c.getString(R.string.level_not_needed);
        }
    }

    private static String names(SystemState s, int state, boolean requiredOnly) {
        final List<String> l = new ArrayList<>();
        for (FileState f : s.files) {
            if (f.state != state) continue;
            if (requiredOnly && f.bios.need != BiosTable.REQUIRED) continue;
            // a wrong spare one of a set that is fine doesn't matter
            if (!requiredOnly && f.bios.need == BiosTable.ONE_OF && oneOfFound(s)) continue;
            l.add(f.bios.name);
        }
        return String.join(", ", l);
    }

    private static String firstOneOf(SystemState s) {
        for (FileState f : s.files) {
            if (f.bios.need == BiosTable.ONE_OF) return f.bios.name;
        }
        return "";
    }

    private static boolean oneOfFound(SystemState s) {
        for (FileState f : s.files) {
            if (f.bios.need == BiosTable.ONE_OF && f.state == Report.FOUND) return true;
        }
        return false;
    }

    /** A file's line on the system page. */
    static String fileState(Context c, Scan scan, SystemState s, FileState f) {
        switch (f.state) {
            case Report.FOUND:
                return c.getString(R.string.state_found);
            case Report.WRONG_NAME:
                return c.getString(R.string.state_wrong_name, scan.inBios(f.file));
            case Report.WRONG_VERSION:
                return f.rename != null
                        ? c.getString(R.string.state_wrong_version_is, f.rename.to.name)
                        : c.getString(R.string.state_wrong_version);
            default:
                if (f.bios.need == BiosTable.REQUIRED) {
                    return c.getString(R.string.state_missing_needed);
                }
                if (f.bios.need == BiosTable.ONE_OF && !oneOfFound(s)) {
                    return c.getString(R.string.state_missing_one_of);
                }
                return c.getString(R.string.state_missing_optional);
        }
    }

    /** Everything about one file, for its details dialog. */
    static String fileDetails(Context c, Scan scan, SystemState s, FileState f) {
        final Bios b = f.bios;
        final StringBuilder sb = new StringBuilder(fileState(c, scan, s, f)).append("\n\n");
        sb.append(c.getString(R.string.details_what, b.description)).append('\n');
        sb.append(c.getString(R.string.details_cores, String.join(", ", b.cores))).append('\n');
        sb.append(b.anyContent() ? c.getString(R.string.details_md5_any)
                : c.getString(R.string.details_md5, String.join(", ", b.md5s)));
        if (f.file != null) {
            sb.append("\n\n").append(f.file.md5 != null
                    ? c.getString(R.string.details_file, path(c, f.file.path), f.file.md5)
                    : c.getString(R.string.details_file_big, path(c, f.file.path)));
        }
        return sb.toString();
    }

    /** What the rename did, for a toast. */
    static String renamed(Context c, List<String> result) {
        int renamed = 0, failed = 0;
        for (String line : result) {
            if (line.startsWith("renamed ")) renamed++;
            else failed++;
        }
        final StringBuilder sb = new StringBuilder();
        if (renamed > 0) {
            sb.append(c.getResources().getQuantityString(R.plurals.fix_done, renamed, renamed));
        }
        if (failed > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.getResources().getQuantityString(R.plurals.fix_failed, failed, failed));
        }
        return sb.toString();
    }
}
