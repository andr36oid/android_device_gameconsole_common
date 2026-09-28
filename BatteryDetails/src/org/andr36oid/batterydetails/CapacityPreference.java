package org.andr36oid.batterydetails;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * A slider for the battery capacity in 100 mAh steps. D-pad left and right move it (500 mAh
 * steps while a direction is held), and the value is handed on once the buttons have been
 * left alone for a moment, or when a pointer lets go of the bar, not on every step.
 */
public class CapacityPreference extends Preference {

    /** How long after the last press the value counts as chosen */
    private static final long SETTLE_MS = 800;
    /** Key repeats before holding a direction switches to big steps */
    private static final int FAST_AFTER_REPEATS = 5;
    private static final int FAST_STEPS = 5;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCommit = this::commit;

    /** The chosen value, mAh */
    private int mValue = Capacity.MIN_MAH;
    /** The value on screen, differs from mValue while the slider is moving */
    private int mShown = Capacity.MIN_MAH;
    private int mDefault = Capacity.MIN_MAH;
    private View mView;

    public CapacityPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.capacity_slider);
        setPersistent(false);
    }

    void setValue(int mah, int defaultMah) {
        mHandler.removeCallbacks(mCommit);
        mValue = mShown = Capacity.clamp(mah);
        mDefault = defaultMah;
        notifyChanged();
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        mView = view;
        final SeekBar bar = view.findViewById(R.id.seekbar);
        bar.setMax(toProgress(Capacity.MAX_MAH));
        bar.setProgress(toProgress(mShown));
        bar.setEnabled(isEnabled());
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) show(fromProgress(progress), false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mHandler.removeCallbacks(mCommit);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                commit();
            }
        });
        showValueText();
    }

    /** PreferenceFragment hands the list's key events to the selected row. */
    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        final int direction = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
        if (direction == 0 || !isEnabled()) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            mHandler.removeCallbacks(mCommit);
            final int steps = event.getRepeatCount() >= FAST_AFTER_REPEATS ? FAST_STEPS : 1;
            show(mShown + direction * steps * Capacity.STEP_MAH, true);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            mHandler.removeCallbacks(mCommit);
            mHandler.postDelayed(mCommit, SETTLE_MS);
        }
        return true;
    }

    private void show(int mah, boolean moveBar) {
        mShown = Capacity.clamp(mah);
        if (mView != null && moveBar) {
            ((SeekBar) mView.findViewById(R.id.seekbar)).setProgress(toProgress(mShown));
        }
        showValueText();
    }

    private void showValueText() {
        if (mView == null) return;
        final TextView value = mView.findViewById(R.id.value);
        value.setText(getContext().getString(mShown == mDefault
                ? R.string.value_capacity_default : R.string.value_capacity, mShown));
    }

    /** Hands on a pending value now, e.g. when the page is left before it settled. */
    void commit() {
        mHandler.removeCallbacks(mCommit);
        if (mShown == mValue) return;
        if (callChangeListener(mShown)) {
            mValue = mShown;
        } else {
            show(mValue, true);
        }
    }

    private static int toProgress(int mah) {
        return (mah - Capacity.MIN_MAH) / Capacity.STEP_MAH;
    }

    private static int fromProgress(int progress) {
        return Capacity.MIN_MAH + progress * Capacity.STEP_MAH;
    }
}
