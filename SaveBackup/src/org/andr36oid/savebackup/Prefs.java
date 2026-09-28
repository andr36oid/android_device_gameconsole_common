package org.andr36oid.savebackup;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** The page's settings (the switch and the list write them). */
final class Prefs {

    static final String AUTO = "auto";
    static final String KEEP = "keep";
    /** Until when the session job has looked at the app usage events. */
    static final String SESSION_SEEN = "session_seen";

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c);
    }

    static boolean auto(Context c) {
        return get(c).getBoolean(AUTO, true);
    }

    static int keep(Context c) {
        try {
            final int k = Integer.parseInt(get(c).getString(KEEP, "10"));
            return k >= 1 && k <= 100 ? k : Engine.DEFAULT_KEEP;
        } catch (NumberFormatException e) {
            return Engine.DEFAULT_KEEP;
        }
    }
}
