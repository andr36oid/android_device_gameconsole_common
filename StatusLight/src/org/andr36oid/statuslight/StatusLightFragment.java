package org.andr36oid.statuslight;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings > Display > Status light. Battery styles follow Raoni07's R36S LED Control
 * presets, built from the colours this console's LED can show. Charging and notification
 * colours are the Android part; the notification light itself is Android's (liblights).
 */
public class StatusLightFragment extends PreferenceFragment {

    private static final long REFRESH_MS = 2000;
    private static final long APPLY_MS = 400;
    private static final String NOTIFICATION_PULSE = "notification_light_pulse";
    private static final String LINEAGE_PARTS = "org.lineageos.lineageparts";
    private static final String LINEAGE_LIGHTS = LINEAGE_PARTS + ".parts.notification_lights";

    /** colour names the driver uses and their labels; the check offers these */
    private static final String[] KNOWN = {
        "off", "red", "green", "blue", "purple", "pink", "orange", "white",
    };
    private static final int[] KNOWN_LABELS = {
        R.string.color_off, R.string.color_red, R.string.color_green, R.string.color_blue,
        R.string.color_purple, R.string.color_pink, R.string.color_orange, R.string.color_white,
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Preference mNow;
    private Preference mStyle;
    private SwitchPreference mBlink;
    private Preference mCharging;
    private Preference mFull;
    private SwitchPreference mNotify;
    private List<String> mColors = new ArrayList<>();

    /** a battery style: mode "battery" with rules, or mode alone */
    private static final class Style {
        final String label;
        final String mode;
        final String battery;

        Style(String label, String mode, String battery) {
            this.label = label;
            this.mode = mode;
            this.battery = battery;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context c = getActivity();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);

        final Preference intro = new Preference(c);
        intro.setSummary(Led.present() ? R.string.sl_intro : R.string.sl_missing);
        intro.setSelectable(false);
        screen.addPreference(intro);
        if (!Led.present()) return;

        mNow = new Preference(c);
        mNow.setTitle(R.string.sl_now);
        mNow.setSelectable(false);
        screen.addPreference(mNow);

        final PreferenceCategory battery = category(screen, R.string.sl_cat_battery);
        mStyle = new Preference(c);
        mStyle.setTitle(R.string.sl_style);
        mStyle.setOnPreferenceClickListener(p -> {
            showStyles();
            return true;
        });
        battery.addPreference(mStyle);
        mBlink = new SwitchPreference(c);
        mBlink.setPersistent(false);
        mBlink.setTitle(R.string.sl_blink_low);
        mBlink.setSummary(R.string.sl_blink_low_summary);
        mBlink.setOnPreferenceChangeListener((p, value) -> {
            final String rules = Led.read("battery");
            if (rules != null) Led.save("battery", withBlink(rules, (Boolean) value));
            refreshSoon();
            return true;
        });
        battery.addPreference(mBlink);

        final PreferenceCategory charger = category(screen, R.string.sl_cat_charger);
        mCharging = new Preference(c);
        mCharging.setTitle(R.string.sl_charging);
        mCharging.setOnPreferenceClickListener(p -> {
            showChargerChoice("charging", R.string.sl_charging, R.string.sl_follow_battery);
            return true;
        });
        charger.addPreference(mCharging);
        mFull = new Preference(c);
        mFull.setTitle(R.string.sl_full);
        mFull.setOnPreferenceClickListener(p -> {
            showChargerChoice("full", R.string.sl_full, R.string.sl_follow_charging);
            return true;
        });
        charger.addPreference(mFull);

        final PreferenceCategory notif = category(screen, R.string.sl_cat_notifications);
        mNotify = new SwitchPreference(c);
        mNotify.setPersistent(false);
        mNotify.setTitle(R.string.sl_notify);
        mNotify.setSummary(R.string.sl_notify_summary);
        mNotify.setOnPreferenceChangeListener((p, value) -> {
            Settings.System.putInt(c.getContentResolver(), NOTIFICATION_PULSE,
                    (Boolean) value ? 1 : 0);
            return true;
        });
        notif.addPreference(mNotify);
        final Intent lights = new Intent(LINEAGE_LIGHTS).setComponent(
                new ComponentName(LINEAGE_PARTS, LINEAGE_PARTS + ".PartsActivity"));
        if (c.getPackageManager().resolveActivity(lights, 0) != null) {
            final Preference perApp = new Preference(c);
            perApp.setTitle(R.string.sl_notify_colors);
            perApp.setSummary(R.string.sl_notify_colors_summary);
            perApp.setIntent(lights);
            notif.addPreference(perApp);
        }

        final PreferenceCategory tools = category(screen, R.string.sl_cat_tools);
        final Preference check = new Preference(c);
        check.setTitle(R.string.sl_check);
        check.setSummary(R.string.sl_check_summary);
        check.setOnPreferenceClickListener(p -> {
            new ColorCheck(getActivity(), this::refreshSoon).start();
            return true;
        });
        tools.addPreference(check);
        final Preference reset = new Preference(c);
        reset.setTitle(R.string.sl_reset);
        reset.setSummary(R.string.sl_reset_summary);
        reset.setOnPreferenceClickListener(p -> {
            Led.resetAll();
            refreshSoon();
            return true;
        });
        tools.addPreference(reset);
    }

    private PreferenceCategory category(PreferenceScreen screen, int title) {
        final PreferenceCategory cat = new PreferenceCategory(getActivity());
        cat.setTitle(title);
        screen.addPreference(cat);
        return cat;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mNow != null) refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    // ---------------------------------------------------------------------- state

    private void refreshSoon() {
        mHandler.removeCallbacksAndMessages(null);
        mHandler.postDelayed(this::refresh, APPLY_MS);
    }

    private void refresh() {
        if (getActivity() == null) return;
        mColors = Led.colors();
        final String mode = Led.read("mode");
        final String battery = Led.read("battery");

        mNow.setSummary(describeState(Led.read("state")));
        final Style style = currentStyle(mode, battery);
        mStyle.setSummary(style != null ? style.label
                : getString(R.string.sl_style_custom, describeRules(battery)));
        mBlink.setEnabled("battery".equals(mode));
        mBlink.setChecked(battery != null && hasBlink(battery));
        mCharging.setSummary(describeCharger(Led.read("charging"), R.string.sl_follow_battery));
        mFull.setSummary(describeCharger(Led.read("full"), R.string.sl_follow_charging));
        mNotify.setChecked(Settings.System.getInt(getActivity().getContentResolver(),
                NOTIFICATION_PULSE, 1) != 0);

        mHandler.removeCallbacksAndMessages(null);
        mHandler.postDelayed(this::refresh, REFRESH_MS);
    }

    String colorLabel(String name) {
        for (int i = 0; i < KNOWN.length; i++) {
            if (KNOWN[i].equals(name)) return getString(KNOWN_LABELS[i]);
        }
        return name;
    }

    /** "red", "red+off" as "red, blinking" */
    private String showLabel(String show) {
        final int plus = show.indexOf('+');
        if (plus < 0) return colorLabel(show);
        return getString(R.string.sl_blinking, colorLabel(show.substring(0, plus)));
    }

    /** the driver's state file: "red battery 8%", "notify blue", "raw" */
    private String describeState(String state) {
        if (state == null) return "";
        final String[] f = state.split("\\s+");
        if (f.length >= 2 && "notify".equals(f[0])) {
            return getString(R.string.sl_now_notify, colorLabel(f[1]));
        }
        if ("raw".equals(f[0])) return getString(R.string.sl_now_check);
        final String what = Led.RELEASE.equals(f[0]) ? getString(R.string.sl_hardware)
                : showLabel(f[0]);
        final String why = f.length >= 2 ? f[1] : "";
        final String level = f.length >= 3 ? f[2].replace("%", "") : "";
        switch (why) {
            case "battery":
                return getString(R.string.sl_now_battery, what, level);
            case "charging":
                return getString(R.string.sl_now_charging, what);
            case "full":
                return getString(R.string.sl_now_full, what);
            default:
                return what;
        }
    }

    private String describeCharger(String value, int followLabel) {
        if (value == null) return "";
        if (Led.FOLLOW.equals(value)) return getString(followLabel);
        if (Led.RELEASE.equals(value)) return getString(R.string.sl_hardware);
        return showLabel(value);
    }

    /** "30 blue 11 purple 0 red" as "blue · purple below 30 % · red below 11 %" */
    private String describeRules(String rules) {
        if (rules == null) return "";
        final String[] f = rules.split("\\s+");
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < f.length; i += 2) {
            if (i > 0) {
                sb.append(" · ").append(getString(R.string.sl_below, showLabel(f[i + 1]), f[i - 2]));
            } else {
                sb.append(showLabel(f[1]));
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------- styles

    /** the tool's presets, for whichever colours this LED has */
    private List<Style> styles() {
        final List<Style> out = new ArrayList<>();
        if (mColors.isEmpty()) return out;
        final String rest = mColors.get(0);
        final String mix = mColors.contains("purple") ? "purple"
                : mColors.contains("pink") ? "pink" : null;
        if (mColors.contains("red")) {
            // a light that's on while the console is (blue on clones), then the same with
            // the light at rest
            if (mColors.contains("blue") && !rest.equals("blue")) {
                for (int t : new int[] {10, 20, 30}) {
                    out.add(new Style(getString(R.string.sl_style_color_red_low,
                            colorLabel("blue"), Integer.toString(t)),
                            "battery", (t + 1) + " blue 0 red"));
                }
            }
            for (int t : new int[] {10, 20, 30}) {
                out.add(new Style(getString(R.string.sl_style_red_low, Integer.toString(t)),
                        "battery", (t + 1) + " " + rest + " 0 red"));
            }
            if (mix != null) {
                if (mColors.contains("blue")) {
                    out.add(new Style(getString(R.string.sl_style_three, colorLabel("blue"),
                            colorLabel(mix)), "battery", "30 blue 11 " + mix + " 0 red"));
                }
                if (!rest.equals("blue")) {
                    out.add(new Style(getString(R.string.sl_style_two, colorLabel(mix)),
                            "battery", "30 " + rest + " 11 " + mix + " 0 red"));
                }
            }
        }
        for (String color : mColors) {
            out.add(new Style(getString(R.string.sl_style_always, colorLabel(color)), color,
                    null));
        }
        out.add(new Style(getString(R.string.sl_hardware), Led.RELEASE, null));
        return out;
    }

    private Style currentStyle(String mode, String battery) {
        if (mode == null) return null;
        final String plain = battery == null ? null : withBlink(battery, false);
        for (Style s : styles()) {
            if (!s.mode.equals(mode)) continue;
            if (s.battery == null || s.battery.equals(plain)) return s;
        }
        return null;
    }

    private void showStyles() {
        final List<Style> list = styles();
        final CharSequence[] labels = new CharSequence[list.size()];
        final Style cur = currentStyle(Led.read("mode"), Led.read("battery"));
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = list.get(i).label;
            if (list.get(i) == cur) checked = i;
        }
        new AlertDialog.Builder(getActivity())
            .setTitle(R.string.sl_style)
            .setSingleChoiceItems(labels, checked, (d, which) -> {
                d.dismiss();
                final Style s = list.get(which);
                if (s.battery != null) Led.save("battery", withBlink(s.battery, mBlink.isChecked()));
                Led.save("mode", s.mode);
                refreshSoon();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static boolean hasBlink(String rules) {
        return rules.contains("+");
    }

    /** blink the lowest level's colour with the colour at rest, or stop that */
    private String withBlink(String rules, boolean blink) {
        final String[] f = rules.split("\\s+");
        if (f.length < 2) return rules;
        final int last = f.length - 1;
        final String color = f[last].split("\\+")[0];
        final String rest = mColors.isEmpty() ? color : mColors.get(0);
        f[last] = blink && !color.equals(rest) ? color + "+" + rest : color;
        return String.join(" ", f);
    }

    private void showChargerChoice(String file, int title, int followLabel) {
        final Map<String, String> choices = new LinkedHashMap<>();
        choices.put(Led.FOLLOW, getString(followLabel));
        choices.put(Led.RELEASE, getString(R.string.sl_hardware));
        for (String color : mColors) choices.put(color, colorLabel(color));
        final List<String> values = new ArrayList<>(choices.keySet());
        final String cur = Led.read(file);
        new AlertDialog.Builder(getActivity())
            .setTitle(title)
            .setSingleChoiceItems(choices.values().toArray(new CharSequence[0]),
                    values.indexOf(cur), (d, which) -> {
                        d.dismiss();
                        Led.save(file, values.get(which));
                        refreshSoon();
                    })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    static String[] knownColors() {
        return KNOWN;
    }

    static int[] knownLabels() {
        return KNOWN_LABELS;
    }
}
