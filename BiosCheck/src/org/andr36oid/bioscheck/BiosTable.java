package org.andr36oid.bioscheck;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The known BIOS files per system, from res/raw/bios_table.txt (the format and the
 * sources are in its header). Plain Java, so it can be tested on a PC.
 */
final class BiosTable {

    static final int REQUIRED = 0, ONE_OF = 1, OPTIONAL = 2;

    static final class Sys {
        String id, name, note;
        List<String> cores = new ArrayList<>();
        /** Only shown when one of its cores is installed (not one we ship). */
        boolean onlyInstalled;
        final List<Bios> files = new ArrayList<>();
    }

    static final class Bios {
        Sys system;
        String name, description;
        int need;
        /** Known-good MD5s, lower case. Empty: any file with this name counts. */
        final Set<String> md5s = new LinkedHashSet<>();
        /** Cores that load it; the system's cores if the table names none. */
        List<String> cores;

        boolean anyContent() {
            return md5s.isEmpty();
        }

        boolean goodMd5(String md5) {
            return md5 != null && md5s.contains(md5.toLowerCase(Locale.ROOT));
        }
    }

    final List<Sys> systems = new ArrayList<>();
    final List<Bios> files = new ArrayList<>();
    private final Map<String, List<Bios>> mByMd5 = new HashMap<>();
    private final Map<String, List<Bios>> mByName = new HashMap<>();

    static BiosTable parse(Reader in) throws IOException {
        final BiosTable t = new BiosTable();
        final Map<String, Sys> byId = new HashMap<>();
        final BufferedReader r = new BufferedReader(in);
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            final String[] f = line.split("\\|", -1);
            if ("system".equals(f[0]) && f.length >= 6) {
                final Sys s = new Sys();
                s.id = f[1];
                s.name = f[2];
                s.cores = words(f[3]);
                s.onlyInstalled = "installed".equals(f[4]);
                s.note = f[5];
                byId.put(s.id, s);
                t.systems.add(s);
            } else if ("file".equals(f[0]) && f.length >= 6 && byId.containsKey(f[1])) {
                final Bios b = new Bios();
                b.system = byId.get(f[1]);
                b.name = f[2];
                b.need = "required".equals(f[3]) ? REQUIRED
                        : "oneof".equals(f[3]) ? ONE_OF : OPTIONAL;
                if (!"*".equals(f[4].trim())) {
                    for (String m : words(f[4])) b.md5s.add(m.toLowerCase(Locale.ROOT));
                }
                b.description = f[5];
                final List<String> cores = f.length > 6 ? words(f[6]) : null;
                b.cores = cores == null || cores.isEmpty() ? b.system.cores : cores;
                b.system.files.add(b);
                t.add(b);
            } else {
                throw new IOException("bios table line " + n + " is broken: " + line);
            }
        }
        return t;
    }

    private void add(Bios b) {
        files.add(b);
        for (String m : b.md5s) mByMd5.computeIfAbsent(m, k -> new ArrayList<>()).add(b);
        mByName.computeIfAbsent(key(b.name), k -> new ArrayList<>()).add(b);
    }

    /** Table entries with this content, in table order. */
    List<Bios> byMd5(String md5) {
        if (md5 == null) return Collections.emptyList();
        final List<Bios> l = mByMd5.get(md5.toLowerCase(Locale.ROOT));
        return l != null ? l : Collections.emptyList();
    }

    /** Table entries with this file name, ignoring case. */
    List<Bios> byName(String name) {
        final List<Bios> l = mByName.get(key(name));
        return l != null ? l : Collections.emptyList();
    }

    Sys system(String id) {
        for (Sys s : systems) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static List<String> words(String s) {
        final String t = s.trim();
        return t.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(t.split("\\s+")));
    }
}
