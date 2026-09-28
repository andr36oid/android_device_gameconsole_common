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
        // A question or problem that the paragraphs below it answer
        final boolean heading;

        Item(List<String> keys, CharSequence text) {
            this(keys, text, false);
        }

        Item(List<String> keys, CharSequence text, boolean heading) {
            this.keys = keys;
            this.text = text;
            this.heading = heading;
        }

        boolean isParagraph() {
            return keys.isEmpty();
        }
    }

    static final class Chapter {
        final CharSequence title;
        final List<Item> items = new ArrayList<>();
        // The help part starts here: the Help app icon opens the guide on this topic
        boolean helpStart;

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
        final boolean fnHelp = fnShortcuts
                && SystemProperties.getBoolean("ro.andr36oid.fn_help", false);
        final boolean fnOverlay = fnShortcuts
                && SystemProperties.getBoolean("ro.andr36oid.fn_overlay", false)
                && hasPackage("org.andr36oid.perfoverlay");
        final boolean usbMode = hasPackage("org.andr36oid.usbmode");
        final boolean batteryDetails = hasPackage("org.andr36oid.batterydetails");
        final boolean magisk = hasPackage("com.topjohnwu.magisk");
        final String dpad = s(R.string.key_dpad);
        final String power = s(R.string.key_power);
        final String volume = s(R.string.key_volume);
        final List<Chapter> chapters = new ArrayList<>();

        Chapter c = chapter(R.string.welcome_title, chapters);
        para(c, R.string.welcome_intro);
        row(c, R.string.welcome_choose, dpad);
        row(c, R.string.welcome_read, "A");
        row(c, R.string.welcome_page, "L1 / R1");
        row(c, R.string.welcome_tutorial, "X");
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
            if (fnOverlay) {
                row(c, R.string.shortcuts_overlay, "FN", "L2");
            }
            if (fnHelp) {
                para(c, R.string.shortcuts_hold_help);
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

        // Help: problems and how to get out of them. Written for someone who may only have a
        // PC and the SD card at hand, so every fix says where on the card to look.
        c = chapter(R.string.trouble_title, chapters);
        c.helpStart = true;
        para(c, R.string.trouble_intro);
        heading(c, R.string.trouble_first_boot_q);
        para(c, R.string.trouble_first_boot_a);
        heading(c, R.string.trouble_panel_q);
        para(c, R.string.trouble_panel_a);
        heading(c, R.string.trouble_stuck_q);
        para(c, R.string.trouble_stuck_a);
        heading(c, R.string.trouble_frozen_q);
        para(c, R.string.trouble_frozen_a);
        heading(c, R.string.trouble_buttons_q);
        para(c, R.string.mapping_reset);
        heading(c, R.string.trouble_recovery_q);
        row(c, R.string.trouble_recovery_a, "FN", power);
        para(c, R.string.trouble_recovery_more);
        heading(c, R.string.trouble_clock_q);
        para(c, R.string.trouble_clock_a);

        c = chapter(R.string.usb_title, chapters);
        para(c, R.string.usb_card);
        if (usbMode) {
            heading(c, R.string.usb_mode_q);
            para(c, R.string.usb_mode_a);
            para(c, R.string.usb_mode_wifi);
            para(c, R.string.usb_adb);
        } else {
            para(c, R.string.usb_host_only);
        }
        heading(c, R.string.usb_dongles_q);
        para(c, R.string.usb_dongles_a);
        para(c, R.string.usb_dongles_trouble);

        c = chapter(R.string.card_title, chapters);
        para(c, R.string.card_layout);
        heading(c, R.string.card_noroms_q);
        para(c, R.string.card_noroms_a);
        heading(c, R.string.card_backup_q);
        para(c, R.string.card_backup_a);

        c = chapter(R.string.battery_title, chapters);
        heading(c, R.string.battery_percent_q);
        para(c, R.string.battery_percent_a);
        if (batteryDetails) {
            para(c, R.string.battery_details);
        }
        heading(c, R.string.battery_sleep_q);
        para(c, R.string.battery_sleep_a);
        heading(c, R.string.battery_storage_q);
        para(c, R.string.battery_storage_a);

        if (magisk) {
            c = chapter(R.string.magisk_title, chapters);
            para(c, R.string.magisk_intro);
            para(c, R.string.magisk_modules);
            heading(c, R.string.magisk_broken_q);
            para(c, R.string.magisk_broken_a);
        }

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

    private boolean hasPackage(String packageName) {
        try {
            mContext.getPackageManager().getPackageInfo(packageName, 0);
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

    private void heading(Chapter chapter, int text) {
        chapter.items.add(new Item(new ArrayList<>(), mContext.getText(text), true));
    }

    private void row(Chapter chapter, int text, String... keys) {
        chapter.items.add(new Item(Arrays.asList(keys), mContext.getText(text)));
    }
}
