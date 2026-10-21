package org.andr36oid.smoothnesstest;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.WindowManager;

import org.andr36oid.hints.HintBar;

/**
 * A block moves one step per frame, so a frame that comes late shows as a jump. Choreographer
 * gives the time of every frame: the rate over the last second and the frames that took more
 * than 1.5 frame periods are shown next to the refresh rate the screen is set to.
 * B or Back leaves.
 */
public class SmoothnessTestActivity extends Activity implements Choreographer.FrameCallback {

    /** a frame this much longer than the screen's frame period counts as late */
    private static final float LATE = 1.5f;
    private static final long SECOND_NS = 1_000_000_000L;
    /** a frame time gap longer than this is a pause (the test was in the background) */
    private static final long PAUSE_NS = SECOND_NS / 2;

    private final long[] mTimes = new long[512];
    private final boolean[] mLate = new boolean[mTimes.length];
    private int mHead;
    private int mCount;
    private int mLateTotal;
    private boolean mRunning;
    private SmoothnessView mView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // ask for the peak rate picked in Settings, so the test runs at it
        final float peak = Settings.System.getFloat(getContentResolver(),
                Settings.System.PEAK_REFRESH_RATE, 0f);
        if (peak > 0f) {
            final WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredRefreshRate = peak;
            getWindow().setAttributes(lp);
        }
        mView = new SmoothnessView(this);
        setContentView(mView);
        HintBar.attach(this, "B", getString(org.andr36oid.hints.R.string.andr36oid_hint_back));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRunning = true;
        mCount = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mRunning = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mRunning) {
            return;
        }
        final float screenHz = getDisplay().getMode().getRefreshRate();
        boolean late = false;
        if (mCount > 0) {
            final long gap = frameTimeNanos - mTimes[(mHead + mTimes.length - 1) % mTimes.length];
            if (gap > PAUSE_NS) {
                mCount = 0;
            } else if (screenHz > 0f && gap > LATE * SECOND_NS / screenHz) {
                late = true;
                mLateTotal++;
            }
        }
        mTimes[mHead] = frameTimeNanos;
        mLate[mHead] = late;
        mHead = (mHead + 1) % mTimes.length;
        mCount = Math.min(mCount + 1, mTimes.length);

        // frames within the last second
        int n = 0;
        int lateNow = 0;
        long first = frameTimeNanos;
        for (int i = 1; i <= mCount; i++) {
            final int k = (mHead + mTimes.length - i) % mTimes.length;
            if (frameTimeNanos - mTimes[k] > SECOND_NS) {
                break;
            }
            first = mTimes[k];
            if (mLate[k]) {
                lateNow++;
            }
            n++;
        }
        final float measured = n > 1 ? (n - 1) * (float) SECOND_NS / (frameTimeNanos - first) : 0f;

        mView.step(screenHz, measured, lateNow, mLateTotal);
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
