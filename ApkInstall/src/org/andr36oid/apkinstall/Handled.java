package org.andr36oid.apkinstall;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * APKs the user already answered for: installed, or left unticked when installing others.
 * They aren't offered again until the file changes (another size or date). "Not now"
 * answers nothing, so those come up again at the next start.
 */
final class Handled {

    private static final String PREFS = "handled";
    private static final String KEY = "apks";

    private Handled() {
    }

    static List<Helper.Apk> unanswered(Context context, List<Helper.Apk> apks) {
        final Set<String> done = prefs(context).getStringSet(KEY, new HashSet<>());
        final List<Helper.Apk> out = new ArrayList<>();
        for (Helper.Apk a : apks) {
            if (!done.contains(a.key())) out.add(a);
        }
        return out;
    }

    static void add(Context context, List<Helper.Apk> apks) {
        final Set<String> done = new HashSet<>(prefs(context).getStringSet(KEY, new HashSet<>()));
        for (Helper.Apk a : apks) done.add(a.key());
        prefs(context).edit().putStringSet(KEY, done).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
