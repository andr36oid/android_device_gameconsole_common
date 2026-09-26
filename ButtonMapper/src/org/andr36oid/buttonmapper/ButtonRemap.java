package org.andr36oid.buttonmapper;

import android.content.ContentResolver;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hardware button reassignments as InputManagerService reads them from
 * Settings.Global.hardware_button_remap: comma separated "scanCode:KEYCODE" pairs, where
 * KEYCODE is a key code label without the KEYCODE_ prefix, or NONE to disable the button.
 */
final class ButtonRemap {
    static final String SETTING = "hardware_button_remap";
    static final String DISABLED = "NONE";

    private static final String KEYCODE_LABEL_PREFIX = "KEYCODE_";

    // Scan code -> action, sorted to keep the stored value stable.
    private final TreeMap<Integer, String> mActions = new TreeMap<>();

    static ButtonRemap load(ContentResolver resolver) {
        return parse(Settings.Global.getString(resolver, SETTING));
    }

    static ButtonRemap parse(String setting) {
        final ButtonRemap remap = new ButtonRemap();
        if (TextUtils.isEmpty(setting)) {
            return remap;
        }
        for (String rawEntry : setting.split(",")) {
            final String entry = rawEntry.trim();
            final int separator = entry.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            final String action = normalize(entry.substring(separator + 1));
            try {
                final int scanCode = Integer.parseInt(entry.substring(0, separator).trim());
                // Like the framework, the first entry for a scan code wins.
                if (scanCode > 0 && action != null && !remap.mActions.containsKey(scanCode)) {
                    remap.mActions.put(scanCode, action);
                }
            } catch (NumberFormatException e) {
                // Skip it, the framework ignores it as well.
            }
        }
        return remap;
    }

    /** Returns the key code label or NONE for an action as the framework accepts it. */
    private static String normalize(String action) {
        action = action.trim().toUpperCase(Locale.ROOT);
        if (DISABLED.equals(action)) {
            return DISABLED;
        }
        final int keyCode = KeyEvent.keyCodeFromString(action);
        final String label = KeyEvent.keyCodeToString(keyCode);
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN || !label.startsWith(KEYCODE_LABEL_PREFIX)) {
            return null;
        }
        return label.substring(KEYCODE_LABEL_PREFIX.length());
    }

    ButtonRemap copy() {
        final ButtonRemap copy = new ButtonRemap();
        copy.mActions.putAll(mActions);
        return copy;
    }

    boolean isEmpty() {
        return mActions.isEmpty();
    }

    /** Returns the action a button is reassigned to, or null if it keeps its default. */
    String get(int scanCode) {
        return mActions.get(scanCode);
    }

    /** Reassigns a button, a null action restores its default. */
    void set(int scanCode, String action) {
        if (action == null) {
            mActions.remove(scanCode);
        } else {
            mActions.put(scanCode, action);
        }
    }

    void save(ContentResolver resolver) {
        Settings.Global.putString(resolver, SETTING, isEmpty() ? null : toString());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ButtonRemap && mActions.equals(((ButtonRemap) o).mActions);
    }

    @Override
    public int hashCode() {
        return mActions.hashCode();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> entry : mActions.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }
}
