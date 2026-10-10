package org.andr36oid.gamecomfort.resume;

import java.util.ArrayList;
import java.util.List;

/**
 * PPSSPP 1.19 keeps its recent games in ppsspp.ini, section [Recent], FileName0 being the
 * newest. The ini is in PSP/SYSTEM of its memory stick folder, which the user picks on the
 * first start (memstick_dir.txt in its Android/data folder says where).
 */
public final class Ppsspp {

    public static final String PACKAGE = "org.ppsspp.ppsspp";
    public static final String GAME_ACTIVITY = "org.ppsspp.ppsspp.PpssppActivity";
    /** PpssppActivity starts the game in this extra (the home screen shortcut's way). */
    public static final String EXTRA_GAME = "org.ppsspp.ppsspp.Shortcuts";

    static final String FILES = Paths.STORAGE + "/Android/data/org.ppsspp.ppsspp/files";
    static final String INI = "PSP/SYSTEM/ppsspp.ini";

    private Ppsspp() {
    }

    /** The recent games, newest first. */
    public static List<String> parseRecent(String ini) {
        final List<String> list = new ArrayList<>();
        if (ini == null) {
            return list;
        }
        boolean inRecent = false;
        final List<String[]> numbered = new ArrayList<>();
        for (String line : ini.split("\r?\n")) {
            final String l = line.trim();
            if (l.startsWith("[")) {
                inRecent = l.equalsIgnoreCase("[Recent]");
                continue;
            }
            if (!inRecent) {
                continue;
            }
            final int eq = l.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String key = l.substring(0, eq).trim();
            final String value = l.substring(eq + 1).trim();
            if (!key.startsWith("FileName") || value.isEmpty()) {
                continue;
            }
            try {
                final int n = Integer.parseInt(key.substring(8));
                numbered.add(new String[] {String.valueOf(n), value});
            } catch (NumberFormatException e) {
                // not one of ours
            }
        }
        numbered.sort((a, b) -> Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0])));
        for (String[] n : numbered) {
            list.add(n[1]);
        }
        return list;
    }

    /** memstick_dir.txt, as a root path. */
    public static String memstickFile() {
        return Paths.toRoot(FILES + "/memstick_dir.txt");
    }

    /**
     * Where ppsspp.ini may be, as root paths: in the folder memstick_dir.txt names (a path or
     * a file picker link), then PPSSPP's two defaults.
     */
    public static List<String> iniCandidates(String memstickDirTxt) {
        final List<String> list = new ArrayList<>();
        if (memstickDirTxt != null) {
            final String first = memstickDirTxt.trim().split("\r?\n")[0].trim();
            final String dir = Paths.toRoot(first);
            if (dir != null) {
                list.add(dir + "/" + INI);
            }
        }
        for (String dir : new String[] {Paths.STORAGE, FILES}) {
            final String p = Paths.toRoot(dir) + "/" + INI;
            if (!list.contains(p)) {
                list.add(p);
            }
        }
        return list;
    }
}
