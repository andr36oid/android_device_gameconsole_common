package org.andr36oid.gamecomfort;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.text.TextUtils;

import java.util.Arrays;

/**
 * Game mode: Do Not Disturb (priority only) and no pop-up notifications. What was set before
 * is remembered and put back when it goes off. Runs as the system user, which may change Do
 * Not Disturb and global settings without asking.
 */
final class GameMode {

    static final String KEY_AUTOMATIC = "game_mode_automatic";

    private static final String KEY_ACTIVE = "game_mode_active";
    /** Turned on by automatic mode rather than by hand, so leaving the game turns it off. */
    private static final String KEY_BY_AUTOMATIC = "game_mode_by_automatic";
    /** Do Not Disturb before, or 0 if game mode didn't change it. */
    private static final String KEY_SAVED_FILTER = "game_mode_saved_filter";
    /** Heads-up setting before, or -1 if game mode didn't change it. */
    private static final String KEY_SAVED_HEADS_UP = "game_mode_saved_heads_up";
    private static final String KEY_TILE_ADDED = "game_mode_tile_added";

    private static final int FILTER = NotificationManager.INTERRUPTION_FILTER_PRIORITY;

    private GameMode() {
    }

    private static SharedPreferences prefs(Context context) {
        return Comfort.prefs(context);
    }

    static boolean isActive(Context context) {
        return prefs(context).getBoolean(KEY_ACTIVE, false);
    }

    static boolean isAutomatic(Context context) {
        return prefs(context).getBoolean(KEY_AUTOMATIC, false);
    }

    static boolean isOnByAutomatic(Context context) {
        return isActive(context) && prefs(context).getBoolean(KEY_BY_AUTOMATIC, false);
    }

    /**
     * Turns game mode on or off. {@code automatic} tells whether automatic mode does it; by
     * hand wins, so switching it on by hand in a game keeps it on after the game.
     */
    static void setActive(Context context, boolean on, boolean automatic) {
        final SharedPreferences prefs = prefs(context);
        if (on == isActive(context)) {
            if (on && !automatic) {
                prefs.edit().putBoolean(KEY_BY_AUTOMATIC, false).apply();
            }
            return;
        }
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        final ContentResolver resolver = context.getContentResolver();
        final SharedPreferences.Editor edit = prefs.edit();
        if (on) {
            // A stricter Do Not Disturb the user already has stays as it is.
            final int filter = nm.getCurrentInterruptionFilter();
            if (filter == NotificationManager.INTERRUPTION_FILTER_ALL
                    || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                nm.setInterruptionFilter(FILTER);
                edit.putInt(KEY_SAVED_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL);
            } else {
                edit.putInt(KEY_SAVED_FILTER, 0);
            }
            final int headsUp = Settings.Global.getInt(resolver,
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, Settings.Global.HEADS_UP_ON);
            if (headsUp != Settings.Global.HEADS_UP_OFF) {
                Settings.Global.putInt(resolver, Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                        Settings.Global.HEADS_UP_OFF);
                edit.putInt(KEY_SAVED_HEADS_UP, headsUp);
            } else {
                edit.putInt(KEY_SAVED_HEADS_UP, -1);
            }
            edit.putBoolean(KEY_BY_AUTOMATIC, automatic);
        } else {
            // Put things back, unless the user changed them in the meantime.
            final int savedFilter = prefs.getInt(KEY_SAVED_FILTER, 0);
            if (savedFilter != 0 && nm.getCurrentInterruptionFilter() == FILTER) {
                nm.setInterruptionFilter(savedFilter);
            }
            final int savedHeadsUp = prefs.getInt(KEY_SAVED_HEADS_UP, -1);
            if (savedHeadsUp >= 0 && Settings.Global.getInt(resolver,
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, -1)
                    == Settings.Global.HEADS_UP_OFF) {
                Settings.Global.putInt(resolver, Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                        savedHeadsUp);
            }
            edit.remove(KEY_SAVED_FILTER).remove(KEY_SAVED_HEADS_UP)
                    .putBoolean(KEY_BY_AUTOMATIC, false);
        }
        edit.putBoolean(KEY_ACTIVE, on).apply();
        TileService.requestListeningState(context,
                new ComponentName(context, GameModeTileService.class));
    }

    /** Puts the tile into Quick Settings once, the user may remove it afterwards. */
    static void addTileOnce(Context context) {
        final SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_TILE_ADDED, false)) {
            return;
        }
        final ContentResolver resolver = context.getContentResolver();
        final ComponentName tile = new ComponentName(context, GameModeTileService.class);
        final String spec = "custom(" + tile.flattenToShortString() + ")";
        String tiles = Settings.Secure.getString(resolver, Settings.Secure.QS_TILES);
        if (TextUtils.isEmpty(tiles)) {
            // Unset means the default tiles, which SystemUI expands "default" to.
            tiles = "default";
        }
        if (!Arrays.asList(tiles.split(",")).contains(spec)) {
            Settings.Secure.putString(resolver, Settings.Secure.QS_TILES, tiles + "," + spec);
        }
        prefs.edit().putBoolean(KEY_TILE_ADDED, true).apply();
    }
}
