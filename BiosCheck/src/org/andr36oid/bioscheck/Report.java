package org.andr36oid.bioscheck;

import org.andr36oid.bioscheck.BiosTable.Bios;
import org.andr36oid.bioscheck.BiosTable.Sys;
import org.andr36oid.bioscheck.Scan.Found;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Matches what the helper found in the bios folder against the BIOS table: the state
 * of every BIOS file and system, and the files that only need a new name. RetroArch
 * reads the files right in the bios folder, by name. Plain Java, so it can be tested
 * on a PC.
 */
final class Report {

    /** State of one BIOS file. */
    static final int FOUND = 0, WRONG_NAME = 1, WRONG_VERSION = 2, MISSING = 3;

    /** State of a system, best first. */
    static final int LEVEL_READY = 0, LEVEL_NOT_NEEDED = 1, LEVEL_MISSING_ONE_OF = 2,
            LEVEL_WRONG_NAME = 3, LEVEL_WRONG_VERSION = 4, LEVEL_MISSING = 5;

    static final class FileState {
        Bios bios;
        int state;
        /** FOUND: the file. WRONG_VERSION: the file with this name. WRONG_NAME: the
         *  right file under another name or in a subfolder. */
        Found file;
        /** WRONG_VERSION: what the file really is, if it's another known BIOS. */
        final List<Bios> really = new ArrayList<>();
        /** WRONG_NAME, and WRONG_VERSION when it's another BIOS: its rename. */
        Rename rename;
    }

    static final class SystemState {
        Sys sys;
        int level;
        final List<FileState> files = new ArrayList<>();
    }

    /** A known BIOS file under another name or in a subfolder, and where it goes. */
    static final class Rename {
        Found file;
        Bios to;
        String toPath;
    }

    final List<SystemState> systems = new ArrayList<>();
    final List<Rename> renames = new ArrayList<>();

    static Report build(BiosTable table, Scan scan) {
        final Report r = new Report();
        r.findRenames(table, scan);
        for (Sys sys : table.systems) {
            if (!shown(sys, scan)) continue;
            final SystemState ss = new SystemState();
            ss.sys = sys;
            for (Bios b : sys.files) {
                if (used(b, scan)) ss.files.add(r.fileState(table, scan, b));
            }
            if (ss.files.isEmpty()) continue;
            ss.level = level(ss.files);
            r.systems.add(ss);
        }
        return r;
    }

    /** Systems of cores we ship are always shown, unless we know which cores are
     *  installed and none of its are. */
    static boolean shown(Sys sys, Scan scan) {
        if (scan.cores.isEmpty()) return !sys.onlyInstalled;
        for (String c : sys.cores) {
            if (scan.cores.contains(c)) return true;
        }
        return false;
    }

    static boolean used(Bios b, Scan scan) {
        if (scan.cores.isEmpty()) return true;
        for (String c : b.cores) {
            if (scan.cores.contains(c)) return true;
        }
        return false;
    }

    static boolean good(Found f, Bios b) {
        return b.anyContent() ? f.name.equalsIgnoreCase(b.name) : b.goodMd5(f.md5);
    }

    /** The file with this name right in the bios folder (any case), or null. */
    private static Found atTop(Scan scan, String name) {
        for (Found f : scan.files) {
            if (f.atTop && f.name.equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    private FileState fileState(BiosTable table, Scan scan, Bios b) {
        final FileState fs = new FileState();
        fs.bios = b;
        fs.file = atTop(scan, b.name);
        if (fs.file != null && good(fs.file, b)) {
            fs.state = FOUND;
        } else if (fs.file != null) {
            fs.state = WRONG_VERSION;
            for (Bios other : table.byMd5(fs.file.md5)) {
                if (other != b) fs.really.add(other);
            }
            fs.rename = renameOf(fs.file);
        } else {
            for (Rename rn : renames) {
                if (rn.to == b) fs.rename = rn;
            }
            fs.state = fs.rename != null ? WRONG_NAME : MISSING;
            if (fs.rename != null) fs.file = fs.rename.file;
        }
        return fs;
    }

    private Rename renameOf(Found f) {
        for (Rename rn : renames) {
            if (rn.file == f) return rn;
        }
        return null;
    }

    static int level(List<FileState> files) {
        boolean anyFound = false;
        boolean missing = false, wrongVersion = false, wrongName = false;
        boolean hasOneOf = false, oneOfFound = false, oneOfWrongName = false,
                oneOfWrongVersion = false;
        for (FileState f : files) {
            anyFound |= f.state == FOUND;
            if (f.bios.need == BiosTable.ONE_OF) {
                hasOneOf = true;
                oneOfFound |= f.state == FOUND;
                oneOfWrongName |= f.state == WRONG_NAME;
                oneOfWrongVersion |= f.state == WRONG_VERSION;
                continue;
            }
            // a wrong optional file is still loaded, and may keep games from starting
            wrongVersion |= f.state == WRONG_VERSION;
            wrongName |= f.state == WRONG_NAME;
            missing |= f.state == MISSING && f.bios.need == BiosTable.REQUIRED;
        }
        if (hasOneOf && !oneOfFound) {
            wrongName |= oneOfWrongName;
            wrongVersion |= oneOfWrongVersion;
        }
        if (missing) return LEVEL_MISSING;
        if (wrongVersion) return LEVEL_WRONG_VERSION;
        if (wrongName) return LEVEL_WRONG_NAME;
        if (hasOneOf && !oneOfFound) return LEVEL_MISSING_ONE_OF;
        return anyFound ? LEVEL_READY : LEVEL_NOT_NEEDED;
    }

    /**
     * Files in the bios folder that are a BIOS we know, but under another name or in a
     * subfolder, where RetroArch doesn't find them. Only renamed to a file a shown
     * system uses, and never onto a file that is there already; so a spare copy of a
     * file that is in place stays as it is.
     */
    private void findRenames(BiosTable table, Scan scan) {
        final Set<String> taken = new HashSet<>();
        for (Found f : scan.files) {
            if (f.atTop) taken.add(BiosTable.key(f.name));
        }
        for (Found f : scan.files) {
            boolean fine = false;
            if (f.atTop) {
                for (Bios b : table.byName(f.name)) fine |= good(f, b) && used(b, scan);
            }
            if (fine) continue;
            for (Bios b : table.byMd5(f.md5)) {
                if (!used(b, scan) || !shown(b.system, scan)
                        || taken.contains(BiosTable.key(b.name))) {
                    continue;
                }
                final Rename rn = new Rename();
                rn.file = f;
                rn.to = b;
                rn.toPath = scan.biosDir + "/" + b.name;
                taken.add(BiosTable.key(b.name));
                renames.add(rn);
                break;
            }
        }
    }

    SystemState system(String id) {
        for (SystemState s : systems) {
            if (s.sys.id.equals(id)) return s;
        }
        return null;
    }

    /** The helper's ops file: one "from TAB to" line per rename. */
    static String ops(List<Rename> renames) {
        final StringBuilder sb = new StringBuilder();
        for (Rename rn : renames) {
            sb.append(rn.file.path).append('\t').append(rn.toPath).append('\n');
        }
        return sb.toString();
    }
}
