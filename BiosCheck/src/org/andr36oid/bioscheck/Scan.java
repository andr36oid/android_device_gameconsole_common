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
        /** Right in the bios folder, where RetroArch looks; else in a subfolder. */
        boolean atTop;
    }

    /** Where EASYROMS and its bios folder are mounted, null if not. */
    String easyroms, biosDir;
    /** The card has no EASYROMS partition (.noroms): the bios folder is on the
     *  internal storage. */
    boolean internal;
    final Set<String> cores = new HashSet<>();
    /** The files in the bios folder and its subfolders. */
    final List<Found> files = new ArrayList<>();

    static Scan parse(Reader in) throws IOException {
        final Scan s = new Scan();
        final BufferedReader r = new BufferedReader(in);
        final List<String[]> fileLines = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            final String[] f = line.split("\t", -1);
            if (f.length < 2) continue;
            switch (f[0]) {
                case "easyroms":
                    s.easyroms = f[1];
                    break;
                case "internal":
                    s.internal = true;
                    break;
                case "bios":
                    s.biosDir = f[1];
                    break;
                case "core":
                    s.cores.add(f[1]);
                    break;
                case "file":
                    if (f.length >= 4) fileLines.add(f);
                    break;
            }
        }
        if (s.biosDir == null) return s;
        for (String[] f : fileLines) {
            final Found x = new Found();
            x.md5 = "-".equals(f[1]) ? null : f[1].toLowerCase(Locale.ROOT);
            try {
                x.size = Long.parseLong(f[2]);
            } catch (NumberFormatException e) {
                x.size = -1;
            }
            x.path = f[3];
            if (!x.path.startsWith(s.biosDir + "/")) continue;
            final int slash = x.path.lastIndexOf('/');
            x.name = x.path.substring(slash + 1);
            x.atTop = slash == s.biosDir.length();
            if (!junk(x.name)) s.files.add(x);
        }
        return s;
    }

    /** The path inside the bios folder, e.g. "psx/scph5501.bin". */
    String inBios(Found f) {
        return f.path.substring(biosDir.length() + 1);
    }

    /** Mac and Windows leave these next to copied files; they're never BIOS files. */
    static boolean junk(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("._") || n.equals(".ds_store") || n.equals("thumbs.db")
                || n.equals("desktop.ini") || n.endsWith(".txt") || n.endsWith(".md");
    }
}
