package org.andr36oid.statuslight;

import android.app.Activity;
import android.app.AlertDialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds out which colours the LED really shows, like the diagnostic wizard of Raoni07's R36S
 * LED Control: every pin state in turn (low, high, floating), asking what the light shows.
 * The result can replace the console's colour table (persist.andr36oid.led.table).
 */
final class ColorCheck {

    private final Activity mActivity;
    private final Runnable mDone;
    private final List<int[]> mStates = new ArrayList<>();
    /** what the user saw, per state; null for "something else" */
    private final List<String> mSeen = new ArrayList<>();

    ColorCheck(Activity activity, Runnable done) {
        mActivity = activity;
        mDone = done;
    }

    void start() {
        final int pins = Led.pins();
        if (pins < 1) return;
        // floating first: that's what the board shows by itself
        final int total = (int) Math.pow(3, pins);
        for (int n = 0; n < total; n++) {
            final int[] st = new int[pins];
            int v = n;
            for (int p = 0; p < pins; p++) {
                st[p] = (2 + v % 3) % 3;
                v /= 3;
            }
            mStates.add(st);
        }
        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.sl_check)
            .setMessage(mActivity.getString(R.string.sl_check_intro, total))
            .setPositiveButton(R.string.sl_check_go, (d, w) -> ask(0))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static String raw(int[] st) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < st.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(st[i]);
        }
        return sb.toString();
    }

    private void stop() {
        Led.write("raw", "none");
        mDone.run();
    }

    private void ask(int i) {
        if (i == mStates.size()) {
            Led.write("raw", "none");
            finish();
            return;
        }
        Led.write("raw", raw(mStates.get(i)));
        final String[] known = StatusLightFragment.knownColors();
        final int[] labels = StatusLightFragment.knownLabels();
        final CharSequence[] items = new CharSequence[known.length + 1];
        for (int k = 0; k < known.length; k++) items[k] = mActivity.getString(labels[k]);
        items[known.length] = mActivity.getString(R.string.sl_check_other);
        new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.sl_check_step, i + 1, mStates.size()))
            .setItems(items, (d, which) -> {
                mSeen.add(which < known.length ? known[which] : null);
                ask(i + 1);
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> stop())
            .setOnCancelListener(d -> stop())
            .show();
    }

    private static int driven(int[] st) {
        int n = 0;
        for (int s : st) if (s != 2) n++;
        return n;
    }

    private void finish() {
        // per colour, the state that drives the most pins (the most dependable one)
        final Map<String, int[]> best = new LinkedHashMap<>();
        String atRest = null;
        for (int i = 0; i < mStates.size(); i++) {
            final String color = mSeen.get(i);
            if (color == null) continue;
            if (i == 0) atRest = color;
            final int[] prev = best.get(color);
            if (prev == null || driven(mStates.get(i)) > driven(prev)) {
                best.put(color, mStates.get(i));
            }
        }
        if (best.isEmpty()) {
            mDone.run();
            return;
        }
        // the colour at rest goes first: "off" if the light can go off
        final String first = best.containsKey("off") ? "off"
                : atRest != null ? atRest : best.keySet().iterator().next();
        final StringBuilder table = new StringBuilder();
        final StringBuilder names = new StringBuilder();
        appendColor(table, names, first, best.get(first));
        for (Map.Entry<String, int[]> e : best.entrySet()) {
            if (!e.getKey().equals(first)) appendColor(table, names, e.getKey(), e.getValue());
        }
        String msg = mActivity.getString(R.string.sl_check_result, names);
        if (!best.containsKey("off")) msg += "\n\n" + mActivity.getString(R.string.sl_check_no_off);
        final String value = table.toString();
        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.sl_check)
            .setMessage(msg)
            .setPositiveButton(R.string.sl_check_use, (d, w) -> {
                Led.save("table", value);
                mDone.run();
            })
            .setNegativeButton(R.string.sl_check_keep, (d, w) -> mDone.run())
            .show();
    }

    private void appendColor(StringBuilder table, StringBuilder names, String color, int[] st) {
        if (table.length() > 0) {
            table.append(';');
            names.append(", ");
        }
        // "blue 12": the driver takes the states without spaces, which keeps 8 colours
        // under Android's 91 character property limit
        table.append(color).append(' ');
        for (int s : st) table.append(s);
        final String[] known = StatusLightFragment.knownColors();
        final int[] labels = StatusLightFragment.knownLabels();
        for (int k = 0; k < known.length; k++) {
            if (known[k].equals(color)) names.append(mActivity.getString(labels[k]));
        }
    }
}
