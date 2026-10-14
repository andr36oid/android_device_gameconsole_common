package org.andr36oid.controllers;

import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Settings > Controls > Buttons on this console. The user presses every button once and it
 * ticks off; buttons of the built-in controls that the page doesn't know (the extra buttons,
 * scan codes 704-719) are listed below. On Done, the optional buttons that were never pressed
 * are offered as missing and saved in ButtonSettings.MISSING.
 *
 * <p>The first press of each button only ticks it, and every key stays on this page, so B
 * doesn't leave and Select doesn't open a menu. After that the d-pad and A move to Done and
 * press it. Holding Start finishes as well.
 *
 * <p>Fn never reaches apps: the window manager makes it Home. So going home counts as Fn (or
 * the button that stands in for it), and the page comes back by itself, like the tutorial.
 */
public class ButtonCheckActivity extends Activity {

    private static final long START_HOLD_MS = 2000;
    private static final long RETURN_DELAY_MS = 1500;
    /** A volume press this shortly before going home was Vol+ and Vol- together, not Fn */
    private static final long VOLUME_CHORD_MS = 1500;
    private static final int MAX_RETURNS = 3;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // Keys whose down ticked a button: their up and repeats are swallowed too
    private final SparseBooleanArray mSwallowUp = new SparseBooleanArray();
    /** Scan codes pressed on the built-in controls */
    private final Set<Integer> mPressed = new TreeSet<>();
    private final List<CheckBox> mBoxes = new ArrayList<>();

    private TextView mSaved;
    private TextView mExtras;
    private TextView mNote;
    private long mLastVolumeDown;
    private int mReturns;
    private boolean mLeaving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        setContentView(R.layout.button_check);
        mSaved = findViewById(R.id.saved);
        mExtras = findViewById(R.id.extras);
        mNote = findViewById(R.id.note);
        final View done = findViewById(R.id.done);
        done.setOnClickListener(v -> onDone());
        // NO_USER_ACTION: leaving for the finder isn't Fn (see onUserLeaveHint)
        findViewById(R.id.find_unknown).setOnClickListener(v -> startActivity(
                new Intent(this, ButtonFinderActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)));
        done.requestFocus();

        final GridLayout grid = findViewById(R.id.grid);
        for (ButtonSettings.Button button : ButtonSettings.BUILT_IN) {
            final CheckBox box = new CheckBox(this);
            box.setText(button.label);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            // Only a tick mark: Done is the only thing to focus
            box.setFocusable(false);
            box.setClickable(false);
            final GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            params.width = 0;
            grid.addView(box, params);
            mBoxes.add(box);
        }

        if (savedInstanceState != null) {
            final int[] pressed = savedInstanceState.getIntArray("pressed");
            if (pressed != null) {
                for (int code : pressed) mPressed.add(code);
            }
            mReturns = savedInstanceState.getInt("returns");
        }
        final int standin = ButtonSettings.standinScanCode(
                ButtonSettings.fnStandin(this));
        if (standin >= 0) {
            mNote.setText(getString(R.string.check_standin_note,
                    ButtonSettings.buttonName(this, standin)));
            mNote.setVisibility(View.VISIBLE);
        }
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        final int[] pressed = new int[mPressed.size()];
        int i = 0;
        for (int code : mPressed) pressed[i++] = code;
        outState.putIntArray("pressed", pressed);
        outState.putInt("returns", mReturns);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.removeCallbacks(mReturn);
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    private void refresh() {
        mSaved.setText(getString(R.string.check_saved, ButtonSettings.summary(this)));
        for (int i = 0; i < mBoxes.size(); i++) {
            mBoxes.get(i).setChecked(mPressed.contains(ButtonSettings.BUILT_IN[i].scanCode));
        }
        final List<String> extras = new ArrayList<>();
        for (int code : mPressed) {
            if (ButtonSettings.builtIn(code) == null) {
                extras.add(ButtonSettings.buttonName(this, code));
            }
        }
        mExtras.setText(extras.isEmpty() ? getString(R.string.check_extras_none)
                : getString(R.string.check_extras, String.join(", ", extras)));
    }

    // ---- Buttons

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean first = down && event.getRepeatCount() == 0;

        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (down) mLastVolumeDown = SystemClock.uptimeMillis();
        }
        if (mSwallowUp.get(key) && (!down || event.getRepeatCount() > 0)) {
            if (!down) mSwallowUp.delete(key);
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_START) {
            if (first) {
                press(event);
                mHandler.postDelayed(mStartHeld, START_HOLD_MS);
            } else if (!down) {
                mHandler.removeCallbacks(mStartHeld);
            }
            return true;
        }
        if (first && press(event)) {
            mSwallowUp.put(key, true);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // Already ticked: these move to Done and press it
                return super.dispatchKeyEvent(event);
            default:
                // Everything else stays here: B and Back don't leave, the volume doesn't change
                return true;
        }
    }

    /** Notes a press on the built-in controls. Returns true if it was new. */
    private boolean press(KeyEvent event) {
        final InputDevice device = event.getDevice();
        final int scanCode = event.getScanCode();
        if (device == null || device.isExternal() || device.isVirtual() || scanCode <= 0) {
            return false;
        }
        return press(scanCode);
    }

    private boolean press(int scanCode) {
        if (!mPressed.add(scanCode)) return false;
        refresh();
        return true;
    }

    private final Runnable mStartHeld = this::onDone;

    // ---- Fn: seen as going home

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (mLeaving || isFinishing()) return;
        final int standin = ButtonSettings.standinScanCode(
                ButtonSettings.fnStandin(this));
        if (standin >= 0) {
            press(standin);
        } else if (SystemClock.uptimeMillis() - mLastVolumeDown > VOLUME_CHORD_MS) {
            press(ButtonSettings.SCAN_FN);
        }
        if (mReturns < MAX_RETURNS) {
            mReturns++;
            mHandler.postDelayed(mReturn, RETURN_DELAY_MS);
        }
    }

    private final Runnable mReturn = () -> {
        if (isFinishing() || isDestroyed()) return;
        // Runs as the system uid, so it may come back to the front from the background
        try {
            getSystemService(ActivityManager.class).moveTaskToFront(getTaskId(), 0);
        } catch (RuntimeException e) {
            // Stays in the background: Settings > Controls opens it again
        }
    };

    // ---- Done

    private boolean pressed(int scanCode) {
        return mPressed.contains(scanCode);
    }

    private void onDone() {
        mHandler.removeCallbacks(mStartHeld);
        final Set<String> missing = new LinkedHashSet<>();
        for (ButtonSettings.Button button : ButtonSettings.BUILT_IN) {
            if (button.optional != null && !"vol".equals(button.optional)
                    && !pressed(button.scanCode)) {
                missing.add(button.optional);
            }
        }
        if (!pressed(115) && !pressed(114)) missing.add("vol");
        if (missing.isEmpty()) {
            save(missing);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.check_missing_title)
                .setMessage(getString(R.string.check_missing,
                        String.join(", ", ButtonSettings.listMissing(this, missing))))
                .setPositiveButton(R.string.check_missing_save, (dialog, which) ->
                        save(missing))
                .setNegativeButton(R.string.check_missing_back, null)
                .show();
    }

    private void save(Set<String> missing) {
        ButtonSettings.saveMissing(this, missing);
        refresh();
        Toast.makeText(this, getString(R.string.check_saved_toast,
                ButtonSettings.describeMissing(this, missing)), Toast.LENGTH_SHORT).show();
        final String standin = Settings.Global.getString(getContentResolver(),
                ButtonSettings.FN_STANDIN);
        final boolean chosen = ButtonSettings.VOL_BOTH.equals(standin)
                || ButtonSettings.standinScanCode(standin) >= 0;
        if (missing.contains("fn") && !chosen) {
            // No Fn: ask what does its job, then leave
            FnChooser.show(this, this::leave);
        } else {
            leave();
        }
    }

    private void leave() {
        mLeaving = true;
        setResult(RESULT_OK);
        finish();
    }
}
