package org.andr36oid.bioscheck;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** What the helper found (its scan file, see helper/andr36oid-bioscheck.sh). */
final class Scan {

    static final class Found {
        String path, name, md5;
        long size;
        /** In the bios folder on EASYROMS, else in one of RetroArch's folders. */
        boolean inBiosFolder;
    }

    /** Where EASYROMS and its bios folder are mounted, null if not. */
    String easyroms, biosDir;
    /** RetroArch's system folder as set in retroarch.cfg, null if left at default. */
    String cfgSystemDir;
    String defaultSystemDir;
    final Set<String> cores = new HashSet<>();
    final List<Found> files = new ArrayList<>();

    static Scan parse(Reader in) throws IOException {
        final Scan s = new Scan();
        final BufferedReader r = new BufferedReader(in);
        final List<String[]> fileLines = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            final String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "easyroms":
                    if (f.length > 1) s.easyroms = f[1];
                    break;
                case "bios":
                    if (f.length > 1) s.biosDir = f[1];
                    break;
                case "system":
                    if (f.length < 3) break;
                    if ("cfg".equals(f[1])) s.cfgSystemDir = f[2];
                    else s.defaultSystemDir = f[2];
                    break;
                case "core":
                    if (f.length > 1) s.cores.add(f[1]);
                    break;
                case "file":
                    if (f.length >= 4) fileLines.add(f);
                    break;
            }
        }
        for (String[] f : fileLines) {
            final Found x = new Found();
            x.md5 = "-".equals(f[1]) ? null : f[1].toLowerCase(Locale.ROOT);
            try {
                x.size = Long.parseLong(f[2]);
            } catch (NumberFormatException e) {
                x.size = -1;
            }
            x.path = f[3];
            x.name = x.path.substring(x.path.lastIndexOf('/') + 1);
            x.inBiosFolder = s.biosDir != null && x.path.startsWith(s.biosDir + "/");
            s.files.add(x);
        }
        return s;
    }

    /** The folder RetroArch reads BIOS files from. */
    String systemDir() {
        return cfgSystemDir != null ? cfgSystemDir : defaultSystemDir;
    }

    /** Files right in RetroArch's system folder, where the cores look. */
    boolean inSystemDir(Found f) {
        final String dir = systemDir();
        return dir != null && f.path.equals(dir + "/" + f.name);
    }

    /** Mac and Windows leave these next to copied files; they're never BIOS files. */
    static boolean junk(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("._") || n.equals(".ds_store") || n.equals("thumbs.db")
                || n.equals("desktop.ini") || n.endsWith(".txt") || n.endsWith(".md");
    }
}
