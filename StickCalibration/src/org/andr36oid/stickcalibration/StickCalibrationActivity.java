package org.andr36oid.stickcalibration;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import java.util.Locale;

/**
 * Settings > Calibrate sticks. A starts: circle both sticks, let go, press A. The ends and the
 * middle of every axis go to the joypad driver, which then scales the sticks itself, so every
 * game and app (RetroArch, PPSSPP, Daijishou, joystick mouse) gets the same corrected sticks.
 * Left/right change the dead zone, X twice goes back to the factory settings, B leaves.
 */
public class StickCalibrationActivity extends Activity {

    private enum State { UNSUPPORTED, IDLE, CAPTURE, MEASURE }

    private static final long POLL_CAPTURE_MS = 20;
    private static final long POLL_IDLE_MS = 150;
    /** Time for the thumb to leave the stick after pressing A */
    private static final long SETTLE_MS = 300;
    private static final long MEASURE_MS = 700;
    private static final long VERIFY_MS = 500;
    private static final long RESET_CONFIRM_MS = 3000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private CalibrationView mView;
    private StickDriver mDriver;
    private State mState = State.IDLE;

    private Calibration mCurrent;
    private int mDeadzone;
    private Calibration.Capture mCapture;
    private long mMeasureStart;
    private long mResetAskedAt;
    private String mMessage = "";
    private int[] mRaw;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            poll();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mView = new CalibrationView(this);
        mView.setFocusable(true);
        setContentView(mView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mView.requestFocus();
        mDriver = StickDriver.find();
        mMessage = "";
        if (mDriver == null) {
            mState = State.UNSUPPORTED;
        } else {
            enterIdle();
        }
        update();
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacksAndMessages(null);
        if (mState == State.CAPTURE || mState == State.MEASURE) {
            mState = State.IDLE;
            mView.setShowTrail(false);
        }
        super.onPause();
    }

    private void enterIdle() {
        mState = State.IDLE;
        mCapture = null;
        mCurrent = mDriver.readCalibration();
        mDeadzone = mCurrent != null ? mDriver.readDeadzone() : StickDriver.storedDeadzone();
        mView.setShowTrail(false);
        schedulePoll(POLL_IDLE_MS);
    }

    private void schedulePoll(long delay) {
        mHandler.removeCallbacks(mPoll);
        mHandler.postDelayed(mPoll, delay);
    }

    private void poll() {
        mRaw = mDriver.readRaw();
        switch (mState) {
            case IDLE:
                update();
                schedulePoll(POLL_IDLE_MS);
                break;
            case CAPTURE:
                mCapture.add(mRaw);
                update();
                schedulePoll(POLL_CAPTURE_MS);
                break;
            case MEASURE:
                final long t = SystemClock.uptimeMillis() - mMeasureStart;
                if (t >= SETTLE_MS) {
                    mCapture.addRest(mRaw);
                }
                if (t >= SETTLE_MS + MEASURE_MS) {
                    finishMeasure();
                } else {
                    schedulePoll(POLL_CAPTURE_MS);
                }
                break;
            default:
                break;
        }
    }

    private void startCapture() {
        mState = State.CAPTURE;
        mCapture = new Calibration.Capture();
        mMessage = "";
        mView.setShowTrail(true);
        update();
        schedulePoll(0);
    }

    private void startMeasure() {
        mState = State.MEASURE;
        mCapture.clearRest();
        mMeasureStart = SystemClock.uptimeMillis();
        update();
        schedulePoll(POLL_CAPTURE_MS);
    }

    private void finishMeasure() {
        if (!mCapture.finish(mDeadzone)) {
            switch (mCapture.problem) {
                case LEFT_SHORT:
                    mMessage = getString(R.string.cal_left_short);
                    break;
                case RIGHT_SHORT:
                    mMessage = getString(R.string.cal_right_short);
                    break;
                default:
                    mMessage = getString(R.string.cal_not_still);
                    break;
            }
            // Keep what was collected, the user can carry on circling
            mState = State.CAPTURE;
            update();
            schedulePoll(POLL_CAPTURE_MS);
            return;
        }
        final Calibration cal = mCapture.result;
        StickDriver.store(cal, mCapture.deadzone);
        mMessage = getString(R.string.cal_saved);
        enterIdle();
        // Init applies the properties a moment later; check that the driver took them
        mHandler.postDelayed(() -> {
            final Calibration now = mDriver.readCalibration();
            if (now == null || !now.format().equals(cal.format())) {
                mMessage = getString(R.string.cal_not_applied);
            }
            mCurrent = now;
            mDeadzone = mDriver.readDeadzone();
            update();
        }, VERIFY_MS);
        update();
    }

    private void reset() {
        final long now = SystemClock.uptimeMillis();
        if (now - mResetAskedAt > RESET_CONFIRM_MS) {
            mResetAskedAt = now;
            mMessage = getString(R.string.cal_reset_confirm);
            update();
            return;
        }
        mResetAskedAt = 0;
        StickDriver.store(null, Calibration.DEFAULT_DEADZONE);
        mCurrent = null;
        mDeadzone = Calibration.DEFAULT_DEADZONE;
        mMessage = getString(R.string.cal_reset_done);
        mHandler.postDelayed(() -> {
            mCurrent = mDriver.readCalibration();
            update();
        }, VERIFY_MS);
        update();
    }

    private void changeDeadzone(int delta) {
        if (mCurrent == null) {
            mMessage = getString(R.string.cal_deadzone_needs_cal);
            update();
            return;
        }
        final int dz = Math.max(0, Math.min(Calibration.MAX_DEADZONE, mDeadzone + delta));
        if (dz != mDeadzone) {
            mDeadzone = dz;
            StickDriver.storeDeadzone(dz);
        }
        mMessage = "";
        update();
    }

    private void update() {
        final String title = getString(R.string.cal_title);
        String left = getString(R.string.cal_left);
        String right = getString(R.string.cal_right);
        final StringBuilder msg = new StringBuilder();
        String hint;
        switch (mState) {
            case UNSUPPORTED:
                msg.append(getString(R.string.cal_status_unsupported));
                hint = getString(R.string.cal_hint_back);
                mView.setDeadzone(0);
                break;
            case CAPTURE:
                msg.append(getString(R.string.cal_step_circles)).append('\n')
                        .append(getString(R.string.cal_step_release));
                left = left + " · " + directions(0);
                right = right + " · " + directions(1);
                hint = getString(R.string.cal_hint_capture);
                mView.setDeadzone(0);
                break;
            case MEASURE:
                msg.append(getString(R.string.cal_measuring));
                hint = getString(R.string.cal_hint_capture);
                break;
            default:
                if (mCurrent != null) {
                    msg.append(getString(R.string.cal_status_calibrated)).append(" · ")
                            .append(getString(R.string.cal_deadzone, mDeadzone));
                    hint = getString(R.string.cal_hint_idle);
                    mView.setDeadzone(mDeadzone / 100f);
                } else {
                    msg.append(getString(R.string.cal_status_factory));
                    hint = getString(R.string.cal_hint_idle_factory);
                    mView.setDeadzone(0);
                }
                break;
        }
        if (!mMessage.isEmpty()) {
            msg.append("\n\n").append(mMessage);
        }
        mView.setTexts(title, msg.toString(), left, right, hint, footnote());
    }

    private String directions(int stick) {
        final int n = mCapture != null ? mCapture.directions(stick) : 0;
        return n >= 4 ? getString(R.string.cal_directions_done)
                : getString(R.string.cal_directions, n);
    }

    /** Raw driver readings, small, for support questions. */
    private String footnote() {
        if (mRaw == null) {
            return "";
        }
        return String.format(Locale.US, "raw  L %d %d   R %d %d", mRaw[0], mRaw[1], mRaw[2],
                mRaw[3]);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        final boolean first = event.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!first) {
                    break;
                }
                if (mState == State.IDLE) {
                    startCapture();
                } else if (mState == State.CAPTURE && mCapture.started()) {
                    startMeasure();
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (!first) {
                    break;
                }
                if (mState == State.CAPTURE || mState == State.MEASURE) {
                    mMessage = "";
                    enterIdle();
                    update();
                } else {
                    finish();
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first && mState == State.IDLE) {
                    reset();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mState == State.IDLE) {
                    changeDeadzone(-1);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mState == State.IDLE) {
                    changeDeadzone(1);
                }
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            // Consumed, so the framework doesn't turn stick moves into d-pad presses
            mView.setAxes(event.getAxisValue(MotionEvent.AXIS_X),
                    event.getAxisValue(MotionEvent.AXIS_Y),
                    event.getAxisValue(MotionEvent.AXIS_Z),
                    event.getAxisValue(MotionEvent.AXIS_RZ));
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }
}
