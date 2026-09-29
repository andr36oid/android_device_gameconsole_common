package org.andr36oid.storagecare;

import android.content.Context;
import android.content.SharedPreferences;

/** The page's options and what the space check last warned about. */
final class Prefs {

    static final String KEEP_STATES = "keep_states";
    static final String KEEP_BACKUPS = "keep_backups";
    static final String DUPS = "dups";
    static final String WARN = "warn";

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(c.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    static int keepStates(Context c) {
        return number(c, KEEP_STATES, 3);
    }

    static int keepBackups(Context c) {
        return number(c, KEEP_BACKUPS, 3);
    }

    static boolean dups(Context c) {
        return get(c).getBoolean(DUPS, false);
    }

    /** Low space and card warnings (notifications), on by default. */
    static boolean warn(Context c) {
        return get(c).getBoolean(WARN, true);
    }

    /** The level last warned about for a volume (Space.OK/LOW/FULL). */
    static int warned(Context c, String volume) {
        return get(c).getInt("warned_" + volume, Space.OK);
    }

    static void setWarned(Context c, String volume, int level) {
        get(c).edit().putInt("warned_" + volume, level).apply();
    }

    /** A card problem is told once per start of the console: the boot count it was told in. */
    static int healthTold(Context c, String problem) {
        return get(c).getInt("told_" + problem, -1);
    }

    static void setHealthTold(Context c, String problem, int boot) {
        get(c).edit().putInt("told_" + problem, boot).apply();
    }

    private static int number(Context c, String key, int def) {
        try {
            return Math.max(1, Integer.parseInt(get(c).getString(key, "" + def)));
        } catch (NumberFormatException | ClassCastException e) {
            return def;
        }
    }
}
