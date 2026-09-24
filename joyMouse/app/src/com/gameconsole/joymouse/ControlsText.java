package com.gameconsole.joymouse;

import android.content.Context;
import android.content.res.Resources;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Describes the mouse mode controls, for the toast and the settings page. */
final class ControlsText {

    /** What mouse mode is set up to do. */
    static final class Setup {
        String pointerStick = "right";
        String scrollStick = "left";                       // "none" without a second stick
        List<String> toggle = new ArrayList<>();           // empty when switched off
        int holdMs = 1000;
        Map<String, String> bindings = new LinkedHashMap<>();  // button -> action value
    }

    // Actions in the order they are listed, and what they read as.
    private static final String[] ACTIONS = {
        "left", "right", "middle", "back", "forward", "precision", "scroll",
    };
    private static final int[] ACTION_TEXT = {
        R.string.does_click, R.string.does_right_click, R.string.does_middle_click,
        R.string.does_back, R.string.does_forward, R.string.does_precision, R.string.does_scroll,
    };

    private ControlsText() {}

    /** Toast text for a mode change. */
    static String announcement(Context context, Setup setup, boolean active) {
        final Resources res = context.getResources();
        final List<String> lines = new ArrayList<>();
        if (active) {
            lines.add(res.getString(R.string.mode_on));
            lines.addAll(controlLines(context, setup));
            if (!setup.toggle.isEmpty()) {
                lines.add(res.getString(R.string.hold_to_switch_off, chord(context, setup.toggle)));
            }
        } else {
            lines.add(res.getString(R.string.mode_off));
            if (setup.toggle.isEmpty()) {
                lines.add(res.getString(R.string.switch_in_settings));
            } else {
                lines.add(res.getString(R.string.hold_to_switch_on, chord(context, setup.toggle),
                        seconds(context, setup.holdMs)));
            }
        }
        return String.join("\n", lines);
    }

    /** The controls while mouse mode is on, one topic per line. */
    static String controls(Context context, Setup setup) {
        return String.join("\n", controlLines(context, setup));
    }

    /** "L3 + R3" */
    static String chord(Context context, List<String> buttons) {
        final List<String> names = new ArrayList<>();
        for (String button : buttons) names.add(shortName(context, button));
        return String.join(context.getString(R.string.chord_separator), names);
    }

    private static List<String> controlLines(Context context, Setup setup) {
        final Resources res = context.getResources();
        final List<String> lines = new ArrayList<>();
        final String pointer = stickName(context, setup.pointerStick);
        if ("none".equals(setup.scrollStick) || setup.scrollStick == null) {
            lines.add(res.getString(R.string.sticks_pointer, pointer));
        } else {
            lines.add(res.getString(R.string.sticks_pointer_scroll, pointer,
                    stickName(context, setup.scrollStick)));
        }

        final List<String> groups = new ArrayList<>();
        for (int i = 0; i < ACTIONS.length; i++) {
            final List<String> buttons = new ArrayList<>();
            for (Map.Entry<String, String> binding : setup.bindings.entrySet()) {
                String action = binding.getValue();
                if ("smart".equals(action)) action = "left";
                if (ACTIONS[i].equals(action)) buttons.add(shortName(context, binding.getKey()));
            }
            if (buttons.isEmpty()) continue;
            groups.add(res.getString(R.string.binding,
                    String.join(res.getString(R.string.button_separator), buttons),
                    res.getString(ACTION_TEXT[i])));
        }
        if (!groups.isEmpty()) lines.add(String.join(res.getString(R.string.list_separator), groups));
        return lines;
    }

    private static String stickName(Context context, String side) {
        return context.getString("left".equals(side) ? R.string.stick_left : R.string.stick_right);
    }

    private static String shortName(Context context, String button) {
        final String[] values = context.getResources().getStringArray(R.array.button_values);
        final String[] labels = context.getResources().getStringArray(R.array.button_short_labels);
        for (int i = 0; i < values.length && i < labels.length; i++) {
            if (values[i].equalsIgnoreCase(button)) return labels[i];
        }
        return button;
    }

    private static String seconds(Context context, int ms) {
        final String value = new BigDecimal(ms).movePointLeft(3).stripTrailingZeros().toPlainString();
        return context.getString(R.string.seconds, value);
    }
}
