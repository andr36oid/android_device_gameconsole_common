package org.andr36oid.controllertest;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Full-screen controller tester. Every key goes to the tester, so it is left by holding
 * START + SELECT (or a long press on Back, for pads that have one). FN is Home on this
 * console, which the system handles before any app sees it.
 */
public class ControllerTestActivity extends Activity {

    private static final long EXIT_HOLD_MS = 1500;
    private static final long BATTERY_POLL_MS = 1000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ControllerView mView;
    private Battery mBattery;
    private boolean mStartDown;
    private boolean mSelectDown;
    private long mExitSince;

    private final Runnable mExitTick = new Runnable() {
        @Override
        public void run() {
            if (mExitSince == 0) {
                return;
            }
            final float progress = (System.currentTimeMillis() - mExitSince)
                    / (float) EXIT_HOLD_MS;
            mView.setExitProgress(Math.min(1f, progress));
            if (progress >= 1f) {
                finish();
                return;
            }
            mHandler.postDelayed(this, 30);
        }
    };

    private final Runnable mBatteryTick = new Runnable() {
        @Override
        public void run() {
            mView.setBattery(mBattery.read());
            mHandler.postDelayed(this, BATTERY_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mView = new ControllerView(this);
        mView.setFocusable(true);
        mView.setFocusableInTouchMode(true);
        setContentView(mView);
        mBattery = new Battery(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        final WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.hide(WindowInsets.Type.systemBars());
            insets.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        mView.requestFocus();
        mView.updateSticks();
        mHandler.post(mBatteryTick);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mBatteryTick);
        stopExit();
        mStartDown = false;
        mSelectDown = false;
        mView.releaseAll();
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (event.getAction() != KeyEvent.ACTION_MULTIPLE) {
            mView.onKey(event);
        }
        if (code == KeyEvent.KEYCODE_BUTTON_START) {
            mStartDown = down;
        } else if (code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            mSelectDown = down;
        } else if (code == KeyEvent.KEYCODE_BACK) {
            if (down && event.getRepeatCount() == 0) {
                event.startTracking();
            } else if (down && event.isLongPress()) {
                finish();
            }
            return true;
        }
        if (mStartDown && mSelectDown) {
            startExit();
        } else {
            stopExit();
        }
        // Everything else stays in the tester, volume keys included
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mView.onMotion(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void startExit() {
        if (mExitSince != 0) {
            return;
        }
        mExitSince = System.currentTimeMillis();
        mHandler.post(mExitTick);
    }

    private void stopExit() {
        if (mExitSince == 0) {
            return;
        }
        mExitSince = 0;
        mHandler.removeCallbacks(mExitTick);
        mView.setExitProgress(0f);
    }
}
