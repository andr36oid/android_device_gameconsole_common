package org.andr36oid.bioscheck;

import org.andr36oid.bioscheck.BiosTable.Bios;
import org.andr36oid.bioscheck.BiosTable.Sys;
import org.andr36oid.bioscheck.Scan.Found;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Matches what the helper found against the BIOS table: the state of every BIOS file
 * and system, the files that only need renaming, and what "Copy to emulators" does.
 * Plain Java, so it can be tested on a PC.
 */
final class Report {

    /** State of one BIOS file. */
    static final int OK = 0, READY = 1, WRONG = 2, MISSING = 3;

    /** State of a system, best first. */
    static final int LEVEL_OK = 0, LEVEL_READY = 1, LEVEL_OPTIONAL_MISSING = 2,
            LEVEL_WRONG = 3, LEVEL_RECOMMENDED_MISSING = 4, LEVEL_REQUIRED_MISSING = 5;

    static final class FileState {
        Bios bios;
        int state;
        /** The good file in RetroArch's system folder (OK). */
        Found installed;
        /** A good file elsewhere that "Copy to emulators" puts in place (READY, or WRONG
         *  when a different file is in the way). */
        Found source;
        /** A file with this name but not the known-good content (WRONG). */
        Found wrong;
        /** What the wrong file really is, if it's another known BIOS. */
        final List<Bios> wrongIs = new ArrayList<>();
    }

    static final class SystemState {
        Sys sys;
        int level;
        final List<FileState> files = new ArrayList<>();
    }

    /** A file in the bios folder that is a known BIOS under another name. */
    static final class Rename {
        Found file;
        Bios to;
        String toPath;
    }

    static final class Copy {
        Found from;
        Bios bios;
        String to;
        /** A different file already at {@link #to}; only replaced when the user says so. */
        Found existing;
    }

    final List<SystemState> systems = new ArrayList<>();
    final List<Rename> renames = new ArrayList<>();
    /** Known BIOS files under another name, whose content is already there properly
     *  (to: what it is, toPath null). */
    final List<Rename> duplicates = new ArrayList<>();
    /** Files in the bios folder that aren't any known BIOS. */
    final List<Found> unknown = new ArrayList<>();
    /** Copies to places where nothing is yet. */
    final List<Copy> copies = new ArrayList<>();
    /** Copies over a different file; need a yes first. */
    final List<Copy> conflicts = new ArrayList<>();
    String systemDir;

    static Report build(BiosTable table, Scan scan) {
        final Report r = new Report();
        r.systemDir = scan.systemDir();

        for (Sys sys : table.systems) {
            if (!shown(sys, scan)) continue;
            final SystemState ss = new SystemState();
            ss.sys = sys;
            for (Bios b : sys.files) {
                if (!used(b, scan)) continue;
                ss.files.add(r.fileState(table, scan, b));
            }
            if (ss.files.isEmpty()) continue;
            ss.level = level(ss.files);
            r.systems.add(ss);
        }
        r.findRenames(table, scan);
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

    private FileState fileState(BiosTable table, Scan scan, Bios b) {
        final FileState fs = new FileState();
        fs.bios = b;
        Found inPlace = null;
        for (Found f : scan.files) {
            if (!scan.inSystemDir(f) || !f.name.equalsIgnoreCase(b.name)) continue;
            if (good(f, b)) {
                fs.installed = f;
                fs.state = OK;
                return fs;
            }
            inPlace = f;
        }
        // a good copy anywhere else, the bios folder first; its name doesn't matter
        for (Found f : scan.files) {
            if (f == inPlace || !good(f, b)) continue;
            if (fs.source == null || (f.inBiosFolder && !fs.source.inBiosFolder)) fs.source = f;
        }
        if (inPlace != null) {
            fs.state = WRONG;
            fs.wrong = inPlace;
            if (fs.source != null) {
                final Copy c = copy(fs);
                c.existing = inPlace;
                conflicts.add(c);
            }
        } else if (fs.source != null) {
            fs.state = READY;
            copies.add(copy(fs));
        } else {
            for (Found f : scan.files) {
                if (f.inBiosFolder && f.name.equalsIgnoreCase(b.name)) {
                    fs.wrong = f;
                    break;
                }
            }
            fs.state = fs.wrong != null ? WRONG : MISSING;
        }
        if (fs.wrong != null) {
            for (Bios other : table.byMd5(fs.wrong.md5)) {
                if (other != b) fs.wrongIs.add(other);
            }
        }
        return fs;
    }

    private Copy copy(FileState fs) {
        final Copy c = new Copy();
        c.from = fs.source;
        c.bios = fs.bios;
        c.to = systemDir + "/" + fs.bios.name;
        return c;
    }

    static int level(List<FileState> files) {
        boolean anyReady = false, anyWrong = false, anyOk = false;
        boolean requiredMissing = false, requiredWrong = false;
        boolean hasOneOf = false, oneOfMet = false, oneOfWrong = false;
        for (FileState f : files) {
            final boolean usable = f.state == OK || f.state == READY;
            anyReady |= f.state == READY;
            anyWrong |= f.state == WRONG;
            anyOk |= f.state == OK;
            if (f.bios.need == BiosTable.REQUIRED && !usable) {
                requiredMissing = true;
                requiredWrong |= f.state == WRONG;
            } else if (f.bios.need == BiosTable.ONE_OF) {
                hasOneOf = true;
                oneOfMet |= usable;
                oneOfWrong |= f.state == WRONG;
            }
        }
        if (requiredMissing) return requiredWrong ? LEVEL_WRONG : LEVEL_REQUIRED_MISSING;
        if (hasOneOf && !oneOfMet) return oneOfWrong ? LEVEL_WRONG : LEVEL_RECOMMENDED_MISSING;
        if (anyReady) return LEVEL_READY;
        if (anyWrong) return LEVEL_WRONG;
        if (anyOk) return LEVEL_OK;
        return LEVEL_OPTIONAL_MISSING;
    }

    private void findRenames(BiosTable table, Scan scan) {
        final Set<String> taken = new HashSet<>();
        for (Found f : scan.files) {
            if (f.inBiosFolder) taken.add(BiosTable.key(f.name));
        }
        for (Found f : scan.files) {
            if (!f.inBiosFolder || Scan.junk(f.name)) continue;
            final List<Bios> named = table.byName(f.name);
            boolean fine = false;
            for (Bios b : named) fine |= good(f, b);
            if (fine) continue;
            final List<Bios> is = table.byMd5(f.md5);
            if (is.isEmpty()) {
                // a known name with the wrong content shows up as WRONG with its system
                if (named.isEmpty()) unknown.add(f);
                continue;
            }
            Bios to = null;
            for (int pass = 0; pass < 2 && to == null; pass++) {
                for (Bios b : is) {
                    // BIOS files of installed cores first
                    if (pass == 0 && !used(b, scan)) continue;
                    if (!taken.contains(BiosTable.key(b.name))) {
                        to = b;
                        break;
                    }
                }
            }
            final Rename rn = new Rename();
            rn.file = f;
            if (to == null) {
                rn.to = is.get(0);
                duplicates.add(rn);
                continue;
            }
            rn.to = to;
            rn.toPath = f.path.substring(0, f.path.lastIndexOf('/') + 1) + to.name;
            taken.add(BiosTable.key(to.name));
            renames.add(rn);
        }
    }

    SystemState system(String id) {
        for (SystemState s : systems) {
            if (s.sys.id.equals(id)) return s;
        }
        return null;
    }

    /** Copies and conflicts of one system, or of all if id is null. */
    List<Copy> copiesOf(String id, boolean conflicting) {
        final List<Copy> out = new ArrayList<>();
        for (Copy c : conflicting ? conflicts : copies) {
            if (id == null || c.bios.system.id.equals(id)) out.add(c);
        }
        return out;
    }

    /** The helper's ops file: the copies, the conflicts to replace, the renames. */
    static String ops(List<Copy> copies, List<Copy> replaces, List<Rename> renames) {
        final StringBuilder sb = new StringBuilder();
        for (Copy c : copies) line(sb, "copy", c);
        for (Copy c : replaces) line(sb, "replace", c);
        for (Rename rn : renames) {
            sb.append("rename\t").append(rn.file.path).append('\t').append(rn.toPath)
                    .append('\n');
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String op, Copy c) {
        sb.append(op).append('\t').append(c.from.path).append('\t').append(c.to).append('\t')
                .append(c.from.md5 != null ? c.from.md5 : "-").append('\n');
    }
}
