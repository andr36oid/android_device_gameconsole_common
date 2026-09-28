package org.andr36oid.guide;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the guide says. Parts that depend on optional features only show when the build has
 * them, so the guide never tells about a shortcut that isn't there.
 */
final class Chapters {

    /** A paragraph, or a row of buttons with what they do. */
    static final class Item {
        // Buttons pressed together; each entry may hold alternatives split by " / ".
        final List<String> keys;
        final CharSequence text;

        Item(List<String> keys, CharSequence text) {
            this.keys = keys;
            this.text = text;
        }

        boolean isParagraph() {
            return keys.isEmpty();
        }
    }

    static final class Chapter {
        final CharSequence title;
        final List<Item> items = new ArrayList<>();

        Chapter(CharSequence title) {
            this.title = title;
        }
    }

    private final Context mContext;

    private Chapters(Context context) {
        mContext = context;
    }

    static List<Chapter> build(Context context) {
        return new Chapters(context).build();
    }

    private List<Chapter> build() {
        final boolean fnShortcuts = SystemProperties.getBoolean("ro.andr36oid.fn_hotkeys", false);
        final boolean romFolders = SystemProperties.getBoolean("ro.andr36oid.rom_folders", false);
        final boolean profiles = hasComponent(new ComponentName("org.andr36oid.cpuoverclock",
                "org.andr36oid.cpuoverclock.ProfileTileService"));
        final String dpad = s(R.string.key_dpad);
        final String power = s(R.string.key_power);
        final String volume = s(R.string.key_volume);
        final List<Chapter> chapters = new ArrayList<>();

        Chapter c = chapter(R.string.welcome_title, chapters);
        para(c, R.string.welcome_intro);
        row(c, R.string.welcome_choose, dpad);
        row(c, R.string.welcome_read, "A");
        row(c, R.string.welcome_page, "L1 / R1");
        row(c, R.string.welcome_close, "B");
        para(c, R.string.welcome_again);

        c = chapter(R.string.buttons_title, chapters);
        row(c, R.string.buttons_a, "A");
        row(c, R.string.buttons_b, "B");
        row(c, R.string.buttons_fn, "FN");
        row(c, R.string.buttons_power_tap, power);
        row(c, R.string.buttons_power_hold, power);
        row(c, R.string.buttons_volume, volume + "+ / " + volume + "−");
        row(c, R.string.buttons_select, "Select");
        para(c, R.string.buttons_stick);

        if (fnShortcuts) {
            c = chapter(R.string.shortcuts_title, chapters);
            para(c, R.string.shortcuts_intro);
            row(c, R.string.shortcuts_brightness, "FN", volume + "+ / " + volume + "−");
            row(c, R.string.shortcuts_screenshot, "FN", "Start");
            row(c, R.string.shortcuts_last_app, "FN", "Select");
            row(c, R.string.shortcuts_panel, "FN", "Y");
            row(c, R.string.shortcuts_mouse, "FN", "X");
            if (profiles) {
                row(c, R.string.shortcuts_profile, "FN", "R1");
            }
        }

        c = chapter(R.string.games_title, chapters);
        para(c, R.string.games_where);
        para(c, R.string.games_folders);
        if (romFolders) {
            para(c, R.string.games_folders_ready);
        }
        para(c, R.string.games_library);

        c = chapter(R.string.emulators_title, chapters);
        para(c, R.string.emulators_included);
        para(c, R.string.emulators_psp);
        para(c, R.string.emulators_updates);

        c = chapter(R.string.speed_title, chapters);
        if (profiles) {
            para(c, R.string.speed_profiles);
            para(c, R.string.speed_overclock);
        } else {
            para(c, R.string.speed_overclock_alone);
        }
        para(c, R.string.speed_screen);

        c = chapter(R.string.mouse_title, chapters);
        row(c, R.string.mouse_toggle, "L3", "R3");
        row(c, R.string.mouse_pointer, s(R.string.key_right_stick));
        row(c, R.string.mouse_click, "A / R1");
        row(c, R.string.mouse_right_click, "L1");
        row(c, R.string.mouse_scroll, s(R.string.key_left_stick));
        para(c, R.string.mouse_settings);
        para(c, R.string.mapping_settings);
        para(c, R.string.mapping_reset);

        c = chapter(R.string.internet_title, chapters);
        para(c, R.string.internet_wifi);
        para(c, R.string.internet_browser);

        return chapters;
    }

    private boolean hasComponent(ComponentName component) {
        try {
            mContext.getPackageManager().getServiceInfo(component,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String s(int res) {
        return mContext.getString(res);
    }

    private Chapter chapter(int title, List<Chapter> chapters) {
        final Chapter chapter = new Chapter(mContext.getText(title));
        chapters.add(chapter);
        return chapter;
    }

    private void para(Chapter chapter, int text) {
        chapter.items.add(new Item(new ArrayList<>(), mContext.getText(text)));
    }

    private void row(Chapter chapter, int text, String... keys) {
        chapter.items.add(new Item(Arrays.asList(keys), mContext.getText(text)));
    }
}
