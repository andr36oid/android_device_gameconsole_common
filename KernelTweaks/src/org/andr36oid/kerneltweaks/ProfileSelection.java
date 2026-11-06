package org.andr36oid.kerneltweaks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Candidate changes and an explicit subset, without property writes or helper calls. */
final class ProfileSelection {
    final Map<Tweak, String> changes = new LinkedHashMap<>();
    final List<Tweak> unavailable = new ArrayList<>();
    private final Set<Tweak> selected = new LinkedHashSet<>();

    ProfileSelection(Profile profile, Map<Tweak, String> saved, Map<Tweak, String> defaults) {
        for (Map.Entry<Tweak, String> entry : profile.values.entrySet()) {
            final Tweak t = entry.getKey();
            if (defaults.getOrDefault(t, "").isEmpty()) {
                unavailable.add(t);
                continue;
            }
            if (t.once || !Profile.allowed(t, entry.getValue())) continue;
            if (!entry.getValue().equals(value(t, saved, defaults))) {
                changes.put(t, entry.getValue());
            }
        }
        selected.addAll(changes.keySet());
    }

    void select(Tweak t, boolean checked) {
        if (!changes.containsKey(t)) return;
        if (checked) selected.add(t);
        else selected.remove(t);
    }

    boolean selected(Tweak t) { return selected.contains(t); }

    void safeOnly() {
        selected.clear();
        for (Tweak t : changes.keySet()) if (t.level == Tweak.SAFE) selected.add(t);
    }

    static String value(Tweak t, Map<Tweak, String> saved, Map<Tweak, String> defaults) {
        final String savedValue = saved.getOrDefault(t, "");
        return savedValue.isEmpty() ? defaults.getOrDefault(t, "") : savedValue;
    }

    static final class Result {
        final Map<Tweak, String> values = new LinkedHashMap<>();
        boolean valid = true;
        boolean boot;
    }

    Result result(Map<Tweak, String> saved, Map<Tweak, String> defaults) {
        final Result result = new Result();
        final Map<Tweak, String> next = new LinkedHashMap<>(saved);
        boolean dependencyChanged = false;
        for (Tweak t : changes.keySet()) {
            if (!selected.contains(t)) continue;
            final String wanted = changes.get(t);
            // Revalidate against current availability and the current catalog at Apply.
            if (defaults.getOrDefault(t, "").isEmpty() || t.once || !Profile.allowed(t, wanted)) {
                result.valid = false;
                return result;
            }
            next.put(t, wanted);
            dependencyChanged |= "storage".equals(t.key) || "fuse".equals(t.key);
            if (!wanted.equals(value(t, saved, defaults))) {
                result.values.put(t, wanted);
                result.boot |= t.atBoot;
            }
        }
        String storage = "", fuse = "";
        for (Tweak t : defaults.keySet()) {
            if ("storage".equals(t.key)) storage = value(t, next, defaults);
            if ("fuse".equals(t.key)) fuse = value(t, next, defaults);
        }
        // Unselected imported values cannot satisfy or introduce dependencies.
        if (dependencyChanged && "fuse".equals(storage) && "off".equals(fuse)) {
            result.valid = false;
        }
        return result;
    }
}
