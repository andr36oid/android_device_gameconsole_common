package com.gameconsole.joymouse;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.SystemProperties;
import android.preference.PreferenceDataStore;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps every preference in a system property, which is where the joyMouse
 * daemon reads its settings from: key "speed" is persist.sys.joymouse.speed,
 * and "active" is the runtime switch sys.joymouse.active.
 *
 * With a data store, android.preference ignores the defaultValue attributes,
 * so a setting without a property would show as off or empty. The defaults
 * are read from the preference XML instead and returned for unset properties.
 */
final class PropertyStore implements PreferenceDataStore {
    private static final String TAG = "JoyMouseSettings";

    static final String KEY_ACTIVE = "active";
    static final String PREFIX = "persist.sys.joymouse.";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    static final String ACTIVE_PROPERTY = "sys.joymouse.active";

    /** Button names in the daemon's order, also the values of R.array.button_values. */
    static final String[] BUTTONS = {
        "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3",
        "SELECT", "START", "MODE", "UP", "DOWN", "LEFT", "RIGHT",
    };

    private final Map<String, String> mDefaults;

    PropertyStore(Context context) {
        mDefaults = loadDefaults(context);
    }

    /** Key to default value from the preference XML, string sets joined like "L3+R3". */
    private static Map<String, String> loadDefaults(Context context) {
        final Map<String, String> defaults = new HashMap<>();
        final Resources res = context.getResources();
        try (XmlResourceParser parser = res.getXml(R.xml.joymouse_settings)) {
            for (int type = parser.getEventType(); type != XmlPullParser.END_DOCUMENT;
                    type = parser.next()) {
                if (type != XmlPullParser.START_TAG) continue;
                final String key = parser.getAttributeValue(ANDROID_NS, "key");
                if (key == null) continue;
                final int id = parser.getAttributeResourceValue(ANDROID_NS, "defaultValue", 0);
                final String value = id != 0 && "array".equals(res.getResourceTypeName(id))
                        ? String.join("+", res.getStringArray(id))
                        : parser.getAttributeValue(ANDROID_NS, "defaultValue");
                if (value != null) defaults.put(key, value);
            }
        } catch (XmlPullParserException | IOException e) {
            Log.w(TAG, "Cannot read the default settings", e);
        }
        return defaults;
    }

    /** The property's value, or the default when it isn't set. */
    private String get(String key) {
        final String value = SystemProperties.get(propertyFor(key));
        return value.isEmpty() ? mDefaults.get(key) : value;
    }

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
        final String value = get(key);
        return value == null ? defValue : value;
    }

    @Override
    public void putString(String key, String value) {
        set(key, value == null ? "" : value);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        final String value = get(key);
        if (value == null) return defValue;
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        set(key, value ? "1" : "0");
    }

    /** Button combinations are stored like "L3+R3". */
    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        final String value = get(key);
        if (value == null) return defValues;
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
