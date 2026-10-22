package org.andr36oid.perfoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The overlay's text and look from its settings, for the overlay itself and for the preview
 * in its settings page. Read the settings again (new HudFormat) whenever they change.
 */
final class HudFormat {

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final int mLabelColor;
    private final int mValueColor;
    private final boolean mWarnings;
    private final int mGoodColor;
    private final int mOkColor;
    private final int mBadColor;

    HudFormat(Context context) {
        mContext = context;
        mPrefs = Hud.prefs(context);
        mLabelColor = color(context, Hud.get(mPrefs, Hud.KEY_LABEL_COLOR));
        mValueColor = color(context, Hud.get(mPrefs, Hud.KEY_VALUE_COLOR));
        mWarnings = mPrefs.getBoolean(Hud.KEY_WARNING_COLORS, true);
        mGoodColor = context.getColor(R.color.hud_good);
        mOkColor = context.getColor(R.color.hud_ok);
        mBadColor = context.getColor(R.color.hud_bad);
    }

    /** A colour choice from the settings: "accent" follows the console's accent colour. */
    static int color(Context context, String name) {
        if ("accent".equals(name)) {
            return context.getColor(R.color.hud_accent);
        }
        final int id = context.getResources().getIdentifier("hud_color_" + name, "color",
                context.getPackageName());
        return id != 0 ? context.getColor(id) : Color.WHITE;
    }

    /** Text size, background and padding on the overlay's text view. */
    void style(TextView view) {
        view.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        float sp = 11;
        try {
            sp = Float.parseFloat(Hud.get(mPrefs, Hud.KEY_SIZE));
        } catch (NumberFormatException e) {
            // keep the default
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setIncludeFontPadding(false);
        view.setShadowLayer(2, 1, 1, 0xFF000000);
        int alpha = 0xb3;
        try {
            alpha = Integer.parseInt(Hud.get(mPrefs, Hud.KEY_BACKGROUND), 16);
        } catch (NumberFormatException e) {
            // keep the default
        }
        final GradientDrawable background = new GradientDrawable();
        background.setColor(alpha << 24);
        background.setCornerRadius(dp(4));
        view.setBackground(background);
        final int padX = dp(5);
        final int padY = dp(3);
        view.setPadding(padX, padY, padX, padY);
    }

    /** Gravity for the chosen position, e.g. "bottom_center". */
    int gravity() {
        final String position = Hud.get(mPrefs, Hud.KEY_POSITION);
        final int vertical = position.startsWith("bottom") ? Gravity.BOTTOM : Gravity.TOP;
        final int horizontal = position.endsWith("right") ? Gravity.RIGHT
                : position.endsWith("center") ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT;
        return vertical | horizontal;
    }

    boolean needsMoreThanFps() {
        return Hud.showsMoreThanFps(mPrefs);
    }

    CharSequence format(Stats s) {
        final boolean speeds = shows(Hud.KEY_SHOW_SPEEDS);
        final String fps = s.fps < 0 ? "--" : String.valueOf(s.fps);
        final int fpsColor = s.fps < 0 ? mValueColor
                : warn(s.fps >= 50 ? mGoodColor : s.fps >= 25 ? mOkColor : mBadColor);

        final List<Item> items = new ArrayList<>();
        if (shows(Hud.KEY_SHOW_FPS)) {
            items.add(new Item(R.string.hud_fps).add(pad(fps, 3), fpsColor));
        }
        final boolean cpuSpeed = speeds && s.cpuMhz > 0;
        if (shows(Hud.KEY_SHOW_CPU) || cpuSpeed) {
            final Item cpu = new Item(R.string.hud_cpu);
            if (shows(Hud.KEY_SHOW_CPU)) {
                cpu.add(s.cpuPercent < 0 ? " --" : pad(s.cpuPercent + "%", 4), mValueColor);
            }
            if (cpuSpeed) {
                cpu.add(String.format(Locale.US, "%.2fGHz", s.cpuMhz / 1000f), mValueColor);
            }
            items.add(cpu);
        }
        final boolean gpuSpeed = speeds && s.gpuMhz > 0;
        if (shows(Hud.KEY_SHOW_GPU) || gpuSpeed) {
            final Item gpu = new Item(R.string.hud_gpu);
            if (shows(Hud.KEY_SHOW_GPU)) {
                gpu.add(s.gpuPercent < 0 ? " --" : pad(s.gpuPercent + "%", 4), mValueColor);
            }
            if (gpuSpeed) {
                gpu.add(s.gpuMhz + "MHz", mValueColor);
            }
            items.add(gpu);
        }
        if (shows(Hud.KEY_SHOW_TEMP) && !Float.isNaN(s.tempC)) {
            items.add(new Item(R.string.hud_temp).add(Math.round(s.tempC) + "°C",
                    s.tempC >= 75 ? warn(mBadColor) : s.tempC >= 65 ? warn(mOkColor)
                            : mValueColor));
        }
        final boolean power = shows(Hud.KEY_SHOW_POWER) && !Float.isNaN(s.batteryWatts);
        if ((shows(Hud.KEY_SHOW_BATTERY) && s.batteryPercent >= 0) || power) {
            final Item bat = new Item(R.string.hud_bat);
            if (shows(Hud.KEY_SHOW_BATTERY) && s.batteryPercent >= 0) {
                bat.add(s.batteryPercent + "%", s.charging ? warn(mGoodColor)
                        : s.batteryPercent <= 15 ? warn(mBadColor) : mValueColor);
            }
            if (power) {
                bat.add(String.format(Locale.US, "%s%.1fW", s.charging ? "+" : "-",
                        s.batteryWatts), mValueColor);
            }
            items.add(bat);
        }
        final boolean memSpeed = speeds && s.memMhz > 0;
        if ((shows(Hud.KEY_SHOW_RAM) && s.ramPercent >= 0) || memSpeed) {
            final Item ram = new Item(R.string.hud_ram);
            if (shows(Hud.KEY_SHOW_RAM) && s.ramPercent >= 0) {
                ram.add(s.ramPercent + "%", s.ramPercent >= 90 ? warn(mOkColor) : mValueColor);
            }
            if (memSpeed) {
                ram.add(s.memMhz + "MHz", mValueColor);
            }
            items.add(ram);
        }
        if (shows(Hud.KEY_SHOW_STORAGE) && s.storagePercent >= 0) {
            items.add(new Item(R.string.hud_storage).add(s.storagePercent + "%",
                    s.storagePercent >= 90 ? warn(mOkColor) : mValueColor));
        }
        if (shows(Hud.KEY_SHOW_TIME)) {
            items.add(new Item(0).add(DateFormat.getTimeFormat(mContext).format(
                    System.currentTimeMillis()), mValueColor));
        }

        final SpannableStringBuilder out = new SpannableStringBuilder();
        if (items.size() == 1 && shows(Hud.KEY_SHOW_FPS)) {
            // Only the frame rate: "60 FPS", as small as it gets
            append(out, fps, fpsColor);
            append(out, " " + mContext.getString(R.string.hud_fps), mLabelColor);
            return out;
        }
        final String layout = Hud.get(mPrefs, Hud.KEY_LAYOUT);
        final boolean column = Hud.LAYOUT_COLUMN.equals(layout);
        final int breakAt = Hud.LAYOUT_TWO_ROWS.equals(layout) ? (items.size() + 1) / 2 : -1;
        int labelWidth = 0;
        for (Item item : items) labelWidth = Math.max(labelWidth, item.label.length());
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(column || i == breakAt ? "\n" : "  ");
            }
            items.get(i).appendTo(out, column ? labelWidth : 0);
        }
        return out;
    }

    private boolean shows(String key) {
        return Hud.shows(mPrefs, key);
    }

    /** Warning colours only if they're on. */
    private int warn(int color) {
        return mWarnings ? color : mValueColor;
    }

    /** One reading: a label and one or more values. */
    private final class Item {
        final String label;
        final List<String> values = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();

        Item(int labelRes) {
            label = labelRes == 0 ? "" : mContext.getString(labelRes);
        }

        Item add(String value, int color) {
            values.add(value);
            colors.add(color);
            return this;
        }

        void appendTo(SpannableStringBuilder out, int labelWidth) {
            if (!label.isEmpty() || labelWidth > 0) {
                final StringBuilder padded = new StringBuilder(label);
                while (padded.length() < labelWidth) padded.append(' ');
                append(out, padded + " ", mLabelColor);
            }
            for (int i = 0; i < values.size(); i++) {
                append(out, (i > 0 ? " " : "") + values.get(i), colors.get(i));
            }
        }
    }

    private static void append(SpannableStringBuilder out, String text, int color) {
        final int start = out.length();
        out.append(text);
        out.setSpan(new ForegroundColorSpan(color), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Right-aligns short numbers so the rest of the line doesn't jump around. */
    private static String pad(String text, int width) {
        final StringBuilder padded = new StringBuilder();
        for (int i = text.length(); i < width; i++) {
            padded.append(' ');
        }
        return padded.append(text).toString();
    }

    private int dp(int dp) {
        return Math.round(dp * mContext.getResources().getDisplayMetrics().density);
    }
}
