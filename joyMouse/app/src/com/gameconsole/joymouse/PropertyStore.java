package com.gameconsole.joymouse;

import android.os.SystemProperties;
import android.preference.PreferenceDataStore;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps every preference in a system property, which is where the joyMouse
 * daemon reads its settings from: key "speed" is persist.sys.joymouse.speed,
 * and "active" is the runtime switch sys.joymouse.active.
 */
final class PropertyStore implements PreferenceDataStore {
    private static final String TAG = "JoyMouseSettings";

    static final String KEY_ACTIVE = "active";
    static final String PREFIX = "persist.sys.joymouse.";
    static final String ACTIVE_PROPERTY = "sys.joymouse.active";

    /** Button names in the daemon's order, also the values of R.array.button_values. */
    static final String[] BUTTONS = {
        "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3",
        "SELECT", "START", "MODE", "UP", "DOWN", "LEFT", "RIGHT",
    };

    static String propertyFor(String key) {
        return KEY_ACTIVE.equals(key) ? ACTIVE_PROPERTY : PREFIX + key;
    }

    static boolean isActive() {
        return "1".equals(SystemProperties.get(ACTIVE_PROPERTY));
    }

    static void clear(String key) {
        set(key, "");
    }

    @Override
    public String getString(String key, String defValue) {
        final String value = SystemProperties.get(propertyFor(key));
        return value.isEmpty() ? defValue : value;
    }

    @Override
    public void putString(String key, String value) {
        set(key, value == null ? "" : value);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        final String value = SystemProperties.get(propertyFor(key));
        if (value.isEmpty()) return defValue;
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        set(key, value ? "1" : "0");
    }

    /** Button combinations are stored like "L3+R3". */
    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        final String value = SystemProperties.get(propertyFor(key));
        if (value.isEmpty()) return defValues;
        final Set<String> buttons = new HashSet<>();
        for (String button : value.split("\\+")) {
            final String name = button.trim().toUpperCase(java.util.Locale.ROOT);
            if (!name.isEmpty() && !name.equals("NONE")) buttons.add(name);
        }
        return buttons;
    }

    @Override
    public void putStringSet(String key, Set<String> values) {
        final StringBuilder text = new StringBuilder();
        for (String button : BUTTONS) {
            if (values == null || !values.contains(button)) continue;
            if (text.length() > 0) text.append('+');
            text.append(button);
        }
        set(key, text.toString());
    }

    private static void set(String key, String value) {
        try {
            SystemProperties.set(propertyFor(key), value);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot store " + key, e);
        }
    }
}
