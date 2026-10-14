package org.andr36oid.guide;

import android.app.AlertDialog;
import android.content.Context;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Asks what does the Fn button's job: the Fn button itself (if there is one), Vol+ and Vol-
 * pressed together, or another button, picked from a list or pressed. Saves the answer in
 * ButtonSettings.FN_STANDIN. A copy of this class is in Controllers: keep them alike.
 */
final class FnChooser {

    /** Buttons offered in the list, best first. A, B and the d-pad are left out: they move
     *  through the menus, and Fn would take them over. */
    private static final int[] CANDIDATES = {158, 125, 314, 312, 313, 310, 311, 307, 308, 315};
    /** Not allowed when pressed: A, B, the d-pad, the volume buttons and Power */
    private static final int[] REFUSED = {304, 305, 103, 108, 105, 106, 115, 114, 116};

    private final Context mContext;
    private final Runnable mOnDone;
    private final boolean mHasFn;

    private FnChooser(Context context, Runnable onDone) {
        mContext = context;
        mOnDone = onDone;
        mHasFn = ButtonSettings.hasFn(context);
    }

    /** Shows the chooser on an activity; onDone runs once something was saved (not on B). */
    static void show(Context activity, Runnable onDone) {
        new FnChooser(activity, onDone).showMain();
    }

    private void showMain() {
        final List<String> values = new ArrayList<>();
        final List<CharSequence> labels = new ArrayList<>();
        if (mHasFn) {
            values.add("");
            labels.add(mContext.getString(R.string.fn_standin_fn));
        }
        values.add(ButtonSettings.VOL_BOTH);
        labels.add(mContext.getString(R.string.fn_standin_vol_both_recommended));
        values.add(null);
        labels.add(mContext.getString(R.string.fn_standin_other));

        final String current = ButtonSettings.fnStandin(mContext);
        int checked = values.indexOf(current);
        if (checked < 0 && ButtonSettings.standinScanCode(current) >= 0) {
            checked = values.size() - 1;
        }
        new AlertDialog.Builder(mContext)
                .setTitle(R.string.fn_standin_title)
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]), checked,
                        (dialog, which) -> {
                            dialog.dismiss();
                            final String value = values.get(which);
                            if (value == null) {
                                showButtons();
                            } else {
                                save(value);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showButtons() {
        final Set<String> missing = ButtonSettings.missing(mContext);
        final List<Integer> codes = new ArrayList<>();
        final List<CharSequence> labels = new ArrayList<>();
        codes.add(-1);
        labels.add(mContext.getString(R.string.fn_standin_press));
        for (int code : CANDIDATES) {
            final ButtonSettings.Button button = ButtonSettings.builtIn(code);
            if (button.optional != null && missing.contains(button.optional)) continue;
            codes.add(code);
            labels.add(mContext.getString(button.label));
        }
        new AlertDialog.Builder(mContext)
                .setTitle(R.string.fn_standin_other)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    final int code = codes.get(which);
                    if (code < 0) {
                        showCapture();
                    } else {
                        confirm(code);
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> showMain())
                .show();
    }

    /** Waits for a button press. Every key is used here, B cancels. */
    private void showCapture() {
        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setTitle(R.string.fn_standin_other)
                .setMessage(R.string.fn_standin_press_now)
                .create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
                return true;
            }
            final int scan = event.getScanCode();
            if (keyCode == KeyEvent.KEYCODE_BACK || scan == 305) {
                d.dismiss();
                return true;
            }
            if (scan <= 0) return true;
            if (scan == ButtonSettings.SCAN_FN) {
                d.dismiss();
                save("");
            } else if (contains(REFUSED, scan)) {
                dialog.setMessage(mContext.getString(R.string.fn_standin_refused,
                        ButtonSettings.buttonName(mContext, scan)));
            } else {
                d.dismiss();
                confirm(scan);
            }
            return true;
        });
        dialog.show();
    }

    private void confirm(int scanCode) {
        final String name = ButtonSettings.buttonName(mContext, scanCode);
        new AlertDialog.Builder(mContext)
                .setTitle(mContext.getString(R.string.fn_standin_confirm_title, name))
                .setMessage(mContext.getString(R.string.fn_standin_confirm, name))
                .setPositiveButton(R.string.fn_standin_confirm_ok, (dialog, which) ->
                        save(ButtonSettings.SCAN_PREFIX + scanCode))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> showButtons())
                .show();
    }

    private void save(String value) {
        ButtonSettings.setFnStandin(mContext, value);
        if (mOnDone != null) mOnDone.run();
    }

    private static boolean contains(int[] codes, int code) {
        for (int c : codes) {
            if (c == code) return true;
        }
        return false;
    }
}
