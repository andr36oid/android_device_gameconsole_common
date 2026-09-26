package org.andr36oid.buttonmapper;

/** What a button can be set to do, in the order and groups the chooser shows them. */
final class ButtonActions {

    static final class Item {
        // Key code label, NONE to turn the button off, null for a group header.
        final String value;
        final int titleRes;
        // Offer to swap with the button that already does this.
        final boolean swappable;

        private Item(String value, int titleRes, boolean swappable) {
            this.value = value;
            this.titleRes = titleRes;
            this.swappable = swappable;
        }

        boolean isHeader() {
            return value == null;
        }
    }

    static final Item[] ITEMS = {
            header(R.string.section_game),
            swappable("BUTTON_A", R.string.action_button_a),
            swappable("BUTTON_B", R.string.action_button_b),
            swappable("BUTTON_X", R.string.action_button_x),
            swappable("BUTTON_Y", R.string.action_button_y),
            swappable("BUTTON_L1", R.string.action_button_l1),
            swappable("BUTTON_R1", R.string.action_button_r1),
            swappable("BUTTON_L2", R.string.action_button_l2),
            swappable("BUTTON_R2", R.string.action_button_r2),
            swappable("BUTTON_THUMBL", R.string.action_button_l3),
            swappable("BUTTON_THUMBR", R.string.action_button_r3),
            swappable("BUTTON_SELECT", R.string.action_button_select),
            swappable("BUTTON_START", R.string.action_button_start),

            header(R.string.section_menu),
            swappable("DPAD_UP", R.string.action_up),
            swappable("DPAD_DOWN", R.string.action_down),
            swappable("DPAD_LEFT", R.string.action_left),
            swappable("DPAD_RIGHT", R.string.action_right),
            swappable("DPAD_CENTER", R.string.action_ok),
            swappable("BACK", R.string.action_back),
            swappable("HOME", R.string.action_home),
            swappable("MENU", R.string.action_menu),

            header(R.string.section_system),
            item("SYSRQ", R.string.action_screenshot),
            item("POWER", R.string.action_power),
            item("VOLUME_UP", R.string.action_volume_up),
            item("VOLUME_DOWN", R.string.action_volume_down),
            item("VOLUME_MUTE", R.string.action_mute),
            item("BRIGHTNESS_UP", R.string.action_brightness_up),
            item("BRIGHTNESS_DOWN", R.string.action_brightness_down),

            header(R.string.section_media),
            item("MEDIA_PLAY_PAUSE", R.string.action_play_pause),
            item("MEDIA_NEXT", R.string.action_next),
            item("MEDIA_PREVIOUS", R.string.action_previous),

            header(R.string.section_off),
            item(ButtonRemap.DISABLED, R.string.action_nothing),
    };

    private ButtonActions() {
    }

    private static Item header(int titleRes) {
        return new Item(null, titleRes, false);
    }

    private static Item item(String value, int titleRes) {
        return new Item(value, titleRes, false);
    }

    private static Item swappable(String value, int titleRes) {
        return new Item(value, titleRes, true);
    }

    /** Returns the item for an action, or null if the chooser doesn't offer it. */
    static Item find(String value) {
        for (Item item : ITEMS) {
            if (!item.isHeader() && item.value.equals(value)) {
                return item;
            }
        }
        return null;
    }
}
