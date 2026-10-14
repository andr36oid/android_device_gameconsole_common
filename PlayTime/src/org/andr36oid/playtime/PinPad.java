package org.andr36oid.playtime;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A PIN pad for the R36S and its clones: no touchscreen needed. A 3 x 4 keypad moved over
 * with the D-pad, big enough to read at arm's length on a 3.5" screen. A presses the key,
 * B deletes the last digit (or goes back when nothing is typed, where that is allowed),
 * Start confirms. Digits from a keyboard work too. Done after PIN_LENGTH digits.
 */
final class PinPad extends LinearLayout {

    interface Listener {
        void onPin(String pin);

        /** B with nothing typed; only where going back is allowed */
        void onCancel();
    }

    private static final String DOT_SET = "●";
    private static final String DOT_EMPTY = "○";

    private final StringBuilder mPin = new StringBuilder();
    private final Listener mListener;
    private final boolean mCancelable;
    private final TextView mDots;
    private final TextView mMessage;
    private final Button mFive;

    PinPad(Context context, boolean cancelable, Listener listener) {
        super(context);
        mListener = listener;
        mCancelable = cancelable;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        mDots = new TextView(context);
        mDots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        mDots.setLetterSpacing(0.4f);
        mDots.setGravity(Gravity.CENTER);
        addView(mDots);

        mMessage = new TextView(context);
        mMessage.setGravity(Gravity.CENTER);
        mMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mMessage.setTextColor(getResources().getColor(android.R.color.holo_red_light, null));
        addView(mMessage);

        final GridLayout grid = new GridLayout(context);
        grid.setColumnCount(3);
        final String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "\u2190", "0", "OK"};
        Button five = null;
        for (String k : keys) {
            final Button b = new Button(context);
            b.setText(k);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setFocusable(true);
            b.setOnClickListener(v -> press(k));
            final GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(68);
            lp.height = dp(48);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(b, lp);
            if ("5".equals(k)) five = b;
        }
        mFive = five;
        addView(grid);

        final TextView hint = new TextView(context);
        hint.setText(R.string.pin_hint);
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setAlpha(0.7f);
        addView(hint);
        update();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Puts the focus in the middle of the pad, so any digit is two presses away. */
    void focus() {
        mFive.requestFocus();
    }

    void clear() {
        mPin.setLength(0);
        update();
    }

    void setMessage(CharSequence message) {
        mMessage.setText(message);
    }

    private void press(String key) {
        switch (key) {
            case "\u2190":
                back();
                break;
            case "OK":
                submit();
                break;
            default:
                if (mPin.length() < Parental.PIN_LENGTH) {
                    mPin.append(key);
                    mMessage.setText("");
                    update();
                    if (mPin.length() == Parental.PIN_LENGTH) submit();
                }
                break;
        }
    }

    private void back() {
        if (mPin.length() > 0) {
            mPin.setLength(mPin.length() - 1);
            update();
        } else if (mCancelable) {
            mListener.onCancel();
        }
    }

    private void submit() {
        if (mPin.length() < Parental.PIN_LENGTH) return;
        final String pin = mPin.toString();
        clear();
        mListener.onPin(pin);
    }

    private void update() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Parental.PIN_LENGTH; i++) {
            sb.append(i < mPin.length() ? DOT_SET : DOT_EMPTY);
        }
        mDots.setText(sb);
    }

    /** The pad's own keys; the activity passes every key press here first. */
    boolean handleKey(KeyEvent event) {
        final int code = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (down) press(String.valueOf(code - KeyEvent.KEYCODE_0));
            return true;
        }
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
            if (down) press(String.valueOf(code - KeyEvent.KEYCODE_NUMPAD_0));
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: {
                final View focused = findFocus();
                if (down && event.getRepeatCount() == 0 && focused != null) {
                    focused.performClick();
                }
                return true;
            }
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DEL:
                if (down && event.getRepeatCount() == 0) back();
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && event.getRepeatCount() == 0) submit();
                return true;
            default:
                return false;
        }
    }
}
