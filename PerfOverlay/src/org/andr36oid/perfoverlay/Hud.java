package org.andr36oid.perfoverlay;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.text.TextUtils;

import java.util.Arrays;

/** The overlay's settings, and switching it on and off. */
final class Hud {

    static final String KEY_ENABLED = "enabled";

    // What it shows, one checkbox each
    static final String KEY_SHOW_FPS = "show_fps";
    static final String KEY_SHOW_CPU = "show_cpu";
    static final String KEY_SHOW_SPEEDS = "show_speeds";
    static final String KEY_SHOW_TEMP = "show_temp";
    static final String KEY_SHOW_BATTERY = "show_battery";
    static final String KEY_SHOW_POWER = "show_power";
    static final String KEY_SHOW_RAM = "show_ram";
    static final String KEY_SHOW_STORAGE = "show_storage";
    static final String KEY_SHOW_TIME = "show_time";

    // How it looks
    static final String KEY_LAYOUT = "layout";
    static final String KEY_POSITION = "position";
    static final String KEY_SIZE = "size";
    static final String KEY_LABEL_COLOR = "label_color";
    static final String KEY_VALUE_COLOR = "value_color";
    static final String KEY_WARNING_COLORS = "warning_colors";
    static final String KEY_BACKGROUND = "background";
    static final String KEY_INTERVAL = "interval";

    static final String LAYOUT_ROW = "row";
    static final String LAYOUT_TWO_ROWS = "two_rows";
    static final String LAYOUT_COLUMN = "column";

    /** Before the checkboxes: "fps", "compact" or "full". Read once to carry it over. */
    private static final String KEY_OLD_STYLE = "style";

    private static final String[] SHOW_KEYS = {
            KEY_SHOW_FPS, KEY_SHOW_CPU, KEY_SHOW_SPEEDS, KEY_SHOW_TEMP, KEY_SHOW_BATTERY,
            KEY_SHOW_POWER, KEY_SHOW_RAM, KEY_SHOW_STORAGE, KEY_SHOW_TIME,
    };
    private static final String[] LOOK_KEYS = {
            KEY_LAYOUT, KEY_POSITION, KEY_SIZE, KEY_LABEL_COLOR, KEY_VALUE_COLOR,
            KEY_WARNING_COLORS, KEY_BACKGROUND, KEY_INTERVAL,
    };

    private Hud() {
    }

    static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    /** FPS, CPU, temperature and battery are on until changed, like the old "compact". */
    static boolean defaultShown(String key) {
        return KEY_SHOW_FPS.equals(key) || KEY_SHOW_CPU.equals(key)
                || KEY_SHOW_TEMP.equals(key) || KEY_SHOW_BATTERY.equals(key);
    }

    static boolean shows(SharedPreferences prefs, String key) {
        return prefs.getBoolean(key, defaultShown(key));
    }

    static boolean isShowKey(String key) {
        return Arrays.asList(SHOW_KEYS).contains(key);
    }

    /** Anything besides the frame rate, which needs the slower readings. */
    static boolean showsMoreThanFps(SharedPreferences prefs) {
        for (String key : SHOW_KEYS) {
            if (!KEY_SHOW_FPS.equals(key) && shows(prefs, key)) return true;
        }
        return false;
    }

    static String get(SharedPreferences prefs, String key) {
        return prefs.getString(key, defaultValue(key));
    }

    static String defaultValue(String key) {
        switch (key) {
            case KEY_LAYOUT: return LAYOUT_ROW;
            case KEY_POSITION: return "top_left";
            case KEY_SIZE: return "11";
            case KEY_LABEL_COLOR: return "accent";
            case KEY_VALUE_COLOR: return "white";
            case KEY_BACKGROUND: return "b3";
            case KEY_INTERVAL: return "1000";
            default: return null;
        }
    }

    static long intervalMs(SharedPreferences prefs) {
        try {
            return Math.max(250, Long.parseLong(get(prefs, KEY_INTERVAL)));
        } catch (NumberFormatException e) {
            return 1000;
        }
    }

    /** Turns the old style setting into the checkboxes and layout, once. */
    static void migrate(Context context) {
        final SharedPreferences prefs = prefs(context);
        final String style = prefs.getString(KEY_OLD_STYLE, null);
        if (style == null) return;
        final SharedPreferences.Editor edit = prefs.edit().remove(KEY_OLD_STYLE);
        final boolean full = "full".equals(style);
        final boolean onlyFps = "fps".equals(style);
        for (String key : SHOW_KEYS) {
            edit.putBoolean(key, full || (!onlyFps && defaultShown(key))
                    || KEY_SHOW_FPS.equals(key));
        }
        if (full) edit.putString(KEY_LAYOUT, LAYOUT_TWO_ROWS);
        edit.apply();
    }

    /** Everything except on/off back to how it came. */
    static void resetLook(Context context) {
        final SharedPreferences.Editor edit = prefs(context).edit();
        for (String key : SHOW_KEYS) edit.remove(key);
        for (String key : LOOK_KEYS) edit.remove(key);
        edit.apply();
    }

    /** Remembers the choice and starts or stops the overlay to match. */
    static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply();
        apply(context, on);
    }

    /** Starts or stops the overlay service without touching the saved choice. */
    static void apply(Context context, boolean on) {
        // Only needed at boot to put the overlay back
        BootStart.set(context, on);
        final Intent service = new Intent(context, HudService.class);
        if (on) {
            context.startForegroundService(service);
            showTile(context);
        } else {
            context.stopService(service);
        }
        // The tile is an active tile: SystemUI only asks it for its state when told to.
        TileService.requestListeningState(context,
                new ComponentName(context, HudTileService.class));
    }

    /** The first time the overlay is used, its tile appears in Quick Settings. */
    private static void showTile(Context context) {
        final PackageManager pm = context.getPackageManager();
        final ComponentName tile = new ComponentName(context, HudTileService.class);
        if (pm.getComponentEnabledSetting(tile)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return;
        }
        pm.setComponentEnabledSetting(tile, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        final ContentResolver resolver = context.getContentResolver();
        final String spec = "custom(" + tile.flattenToShortString() + ")";
        String tiles = Settings.Secure.getString(resolver, Settings.Secure.QS_TILES);
        if (TextUtils.isEmpty(tiles)) {
            // Unset means the default tiles, which SystemUI expands "default" to.
            tiles = "default";
        }
        if (!Arrays.asList(tiles.split(",")).contains(spec)) {
            Settings.Secure.putString(resolver, Settings.Secure.QS_TILES, tiles + "," + spec);
        }
    }
}
