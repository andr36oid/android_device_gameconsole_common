package org.andr36oid.controllers;

import android.app.AlertDialog;
import android.content.Context;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Asks what does the Fn button's job: the Fn button itself (if there is one), a button combo
 * (Start + Vol-, Start + Vol+, L2 + R2, or one recorded here), or another button, picked from a
 * list or pressed. Saves the answer in ButtonSettings.FN_STANDIN. A copy of this class is in QuickStart: keep them alike.
 */
final class FnChooser {

    /** Buttons offered in the list, best first. A, B and the d-pad are left out: they move
     *  through the menus, and Fn would take them over. */
    private static final int[] CANDIDATES = {158, 125, 314, 312, 313, 310, 311, 307, 308, 315};
    /** Not allowed when pressed: A, B, the d-pad, the volume buttons and Power */
    private static final int[] REFUSED = {304, 305, 103, 108, 105, 106, 115, 114, 116};

    private final Context mContext;
    private final Runnable mOnDone;
    private final Runnable mOnCancel;
    private final boolean mHasFn;

    private FnChooser(Context context, Runnable onDone, Runnable onCancel) {
        mContext = context;
        mOnDone = onDone;
        mOnCancel = onCancel;
        mHasFn = ButtonSettings.hasFn(context);
    }

    /** Shows the chooser on an activity; onDone runs once something was saved (not on B). */
    static void show(Context activity, Runnable onDone) {
        show(activity, onDone, null);
    }

    /** The same; onCancel runs when the chooser is left without saving anything. */
    static void show(Context activity, Runnable onDone, Runnable onCancel) {
        new FnChooser(activity, onDone, onCancel).showMain();
    }

    private void cancel() {
        if (mOnCancel != null) mOnCancel.run();
    }

    /** Stand-ins of the main list that open another dialog instead of being saved */
    private static final String OWN_COMBO = "own_combo";
    private static final String OTHER_BUTTON = "other_button";

    private void showMain() {
        final Set<String> missing = ButtonSettings.missing(mContext);
        final List<String> values = new ArrayList<>();
        final List<CharSequence> labels = new ArrayList<>();
        // Offered also when the console was said to have no Fn (a slip in the first start's
        // tutorial, or the button check): choosing it is the way back to the real Fn button
        values.add("");
        labels.add(mContext.getString(mHasFn ? R.string.fn_standin_fn
                : R.string.fn_standin_fn_after_all));
        if (!missing.contains("vol")) {
            values.add(ButtonSettings.START_VOL_DOWN);
            labels.add(mContext.getString(R.string.fn_standin_start_vol_down_recommended));
            values.add(ButtonSettings.START_VOL_UP);
            labels.add(mContext.getString(R.string.fn_standin_start_vol_up));
        }
        if (!missing.contains("l2") && !missing.contains("r2")) {
            values.add(ButtonSettings.L2_R2);
            labels.add(mContext.getString(R.string.fn_standin_l2_r2));
        }
        values.add(OWN_COMBO);
        labels.add(mContext.getString(R.string.fn_standin_own_combo));
        values.add(OTHER_BUTTON);
        labels.add(mContext.getString(R.string.fn_standin_other));

        final String current = ButtonSettings.fnStandin(mContext);
        int checked = values.indexOf(current);
        if (checked < 0 && ButtonSettings.comboScanCodes(current) != null) {
            checked = values.indexOf(OWN_COMBO);
        } else if (checked < 0 && ButtonSettings.standinScanCode(current) >= 0) {
            checked = values.indexOf(OTHER_BUTTON);
        }
        new AlertDialog.Builder(mContext)
                .setTitle(R.string.fn_standin_title)
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]), checked,
                        (dialog, which) -> {
                            dialog.dismiss();
                            final String value = values.get(which);
                            if (OTHER_BUTTON.equals(value)) {
                                showButtons();
                            } else if (OWN_COMBO.equals(value)) {
                                showComboCapture();
                            } else {
                                save(value);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> cancel())
                .setOnCancelListener(dialog -> cancel())
                .show();
    }

    /**
     * Records a combo: hold one button, press a second one. Works with the buttons alone: the
     * first press starts it, letting go of a single button says a second one is needed, and B
     * pressed on its own cancels. The current stand-in is off meanwhile, so its buttons reach
     * this dialog, and comes back when it closes.
     */
    private void showComboCapture() {
        final String previous = Settings.Global.getString(mContext.getContentResolver(),
                ButtonSettings.FN_STANDIN);
        // Not a stand-in the window manager knows: no combo or button is taken over
        Settings.Global.putString(mContext.getContentResolver(), ButtonSettings.FN_STANDIN,
                "recording");
        final Set<Integer> held = new LinkedHashSet<>();
        final int[] first = {0};
        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setTitle(R.string.fn_standin_own_combo)
                .setMessage(R.string.fn_combo_press_now)
                .create();
        // Back to the previous stand-in; a combo is only saved once confirmed
        dialog.setOnDismissListener(d -> Settings.Global.putString(
                mContext.getContentResolver(), ButtonSettings.FN_STANDIN, previous));
        dialog.setOnKeyListener((d, keyCode, event) -> {
            final int scan = event.getScanCode();
            // Only the console's own buttons work as the combo
            final InputDevice device = event.getDevice();
            if (scan <= 0 || device == null || device.isVirtual() || device.isExternal()) {
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                held.remove(scan);
                if (scan == first[0]) {
                    // Let go before a second button came: B alone cancels, anything else
                    // was a single button
                    first[0] = 0;
                    if (scan == 305 && held.isEmpty()) {
                        // the list shows the stand-in that comes back, not "recording"
                        Settings.Global.putString(mContext.getContentResolver(),
                                ButtonSettings.FN_STANDIN, previous);
                        d.dismiss();
                        showMain();
                    } else {
                        dialog.setMessage(mContext.getString(R.string.fn_combo_refused_single));
                    }
                }
                return true;
            }
            if (event.getRepeatCount() > 0 || held.contains(scan)) return true;
            held.add(scan);
            if (first[0] == 0) {
                if (held.size() == 1) first[0] = scan;
                return true;
            }
            final int a = first[0];
            first[0] = 0;
            final int refusal = ButtonSettings.comboRefusal(a, scan);
            if (refusal != 0) {
                dialog.setMessage(mContext.getString(refusal,
                        ButtonSettings.buttonName(mContext, a),
                        ButtonSettings.buttonName(mContext, scan)));
                return true;
            }
            d.dismiss();
            confirmCombo(a, scan);
            return true;
        });
        dialog.show();
    }

    private void confirmCombo(int a, int b) {
        final String first = ButtonSettings.buttonName(mContext, a);
        final String second = ButtonSettings.buttonName(mContext, b);
        new AlertDialog.Builder(mContext)
                .setTitle(mContext.getString(R.string.fn_combo_confirm_title, first, second))
                .setMessage(mContext.getString(R.string.fn_combo_confirm, first, second))
                .setPositiveButton(R.string.fn_standin_confirm_ok, (dialog, which) ->
                        save(ButtonSettings.combo(a, b)))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> showMain())
                .setOnCancelListener(dialog -> showMain())
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
                .setOnCancelListener(dialog -> showMain())
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
                showButtons();
                return true;
            }
            if (scan <= 0) return true;
            if (scan == ButtonSettings.SCAN_FN) {
                // pressed, so it is there, whatever the missing list said
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
                .setOnCancelListener(dialog -> showButtons())
                .show();
    }

    private void save(String value) {
        // the real Fn button: it can't be on the missing list then, or the start combo
        // would still stand in for it
        if (value.isEmpty()) ButtonSettings.removeMissing(mContext, "fn");
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
