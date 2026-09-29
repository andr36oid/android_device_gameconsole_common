package org.andr36oid.cpuoverclock;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The rules of the per-app profiles, in plain Java so they can be tested on a PC
 * (CpuOverclock/tests/run-tests.sh).
 *
 * Three layers decide the profile in effect, the first one set wins:
 *   1. a pick with the tile or FN + R1 while an app with its own profile is in front,
 *      until that app is left
 *   2. the profile of the app in front
 *   3. the chosen profile (the one picked in Settings, or by game mode for a while)
 */
final class AppProfileRules {

    /** No profile of its own: the app follows the chosen profile. */
    static final int FOLLOW = -1;
    // Same numbers as PerformanceProfiles, which can't be used on a PC (android.*)
    static final int SAVER = 0;
    static final int BALANCED = 1;
    static final int PERFORMANCE = 2;
    private static final int COUNT = 3;

    /**
     * Apps that sit on top of a game for a moment without being a new app to play: the
     * touch controls editor and the recent apps screen. Coming back from them is not a
     * new app, so a pick made in the game is kept.
     */
    private static final Set<String> PASSING = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("org.andr36oid.touchmapper", "com.android.systemui")));

    private AppProfileRules() {
    }

    /** What is in front and what it asked for. Immutable. */
    static final class Front {
        static final Front NONE = new Front(null, FOLLOW, FOLLOW);

        /** The app in front, null if none is known. */
        final String pkg;
        /** Its own profile, FOLLOW if it has none. */
        final int profile;
        /** Picked by hand while it is in front, FOLLOW if nothing was picked. */
        final int picked;

        Front(String pkg, int profile, int picked) {
            this.pkg = pkg;
            this.profile = profile;
            this.picked = picked;
        }

        /** Whether the app in front has a profile of its own (a pick then only lasts in it). */
        boolean hasOwn() {
            return pkg != null && valid(profile);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Front)) {
                return false;
            }
            final Front f = (Front) o;
            return profile == f.profile && picked == f.picked
                    && (pkg == null ? f.pkg == null : pkg.equals(f.pkg));
        }

        @Override
        public int hashCode() {
            return (pkg == null ? 0 : pkg.hashCode()) * 31 + profile * 7 + picked;
        }

        @Override
        public String toString() {
            return pkg + "/" + profile + "/" + picked;
        }
    }

    static boolean valid(int profile) {
        return profile >= 0 && profile < COUNT;
    }

    /**
     * Shipped defaults, "package=performance" style lines. Names: saver, balanced,
     * performance. Bad lines are skipped.
     */
    static Map<String, Integer> parseDefaults(String[] lines) {
        final Map<String, Integer> result = new HashMap<>();
        for (String line : lines) {
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final int profile = profileByName(line.substring(eq + 1).trim());
            if (valid(profile)) {
                result.put(line.substring(0, eq).trim(), profile);
            }
        }
        return result;
    }

    static int profileByName(String name) {
        switch (name) {
            case "saver":
                return SAVER;
            case "balanced":
                return BALANCED;
            case "performance":
                return PERFORMANCE;
            default:
                return FOLLOW;
        }
    }

    /**
     * The profile an app asks for, FOLLOW for none. The user's choice beats the shipped
     * default, and "Default" (FOLLOW) chosen by the user beats a shipped profile too. This
     * app itself never gets one: its settings screen shows and sets the chosen profile.
     */
    static int profileFor(String pkg, Map<String, Integer> chosen,
            Map<String, Integer> defaults, String ownPkg) {
        if (pkg == null || pkg.equals(ownPkg)) {
            return FOLLOW;
        }
        final Integer own = chosen.containsKey(pkg) ? chosen.get(pkg) : defaults.get(pkg);
        return own != null && valid(own) ? own : FOLLOW;
    }

    /** Whether any app has a profile of its own, so the watcher has something to do. */
    static boolean anySet(Map<String, Integer> chosen, Map<String, Integer> defaults) {
        for (Map.Entry<String, Integer> e : chosen.entrySet()) {
            if (valid(e.getValue())) {
                return true;
            }
        }
        for (Map.Entry<String, Integer> e : defaults.entrySet()) {
            if (!chosen.containsKey(e.getKey()) && valid(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Whether the app just passes over the one in front (see PASSING). */
    static boolean isPassing(String pkg) {
        return PASSING.contains(pkg);
    }

    /**
     * A new app came to the front. The same app again keeps the pick; a passing one
     * changes nothing; any other app drops the pick.
     */
    static Front onFront(Front old, String pkg, int profile) {
        if (pkg == null || isPassing(pkg)) {
            return old;
        }
        if (pkg.equals(old.pkg)) {
            // Same app, its profile may have been changed in the list meanwhile
            return new Front(pkg, profile, valid(profile) ? old.picked : FOLLOW);
        }
        return new Front(pkg, profile, FOLLOW);
    }

    /**
     * A pick with the tile or FN + R1. Returns the new state if the pick only lasts while
     * the app is in front, null if it changes the chosen profile (no own profile in front).
     */
    static Front onPick(Front front, int profile) {
        if (!front.hasOwn() || !valid(profile)) {
            return null;
        }
        return new Front(front.pkg, front.profile, profile);
    }

    /** The profile in effect. */
    static int effective(boolean enabled, Front front, int chosen) {
        if (!enabled || !front.hasOwn()) {
            return chosen;
        }
        return valid(front.picked) ? front.picked : front.profile;
    }
}
