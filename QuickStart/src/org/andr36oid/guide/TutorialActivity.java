package org.andr36oid.guide;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A hands-on tutorial that runs once after the setup wizard. Each step asks the user to press
 * something and ticks it off when it sees it happen. Every step can be skipped (Select or the
 * Skip step button), B goes back a step, and Skip tutorial jumps to the last page, so the user
 * is never stuck.
 *
 * <p>FN never reaches apps: the window manager turns it into Home and runs the FN shortcuts
 * itself. So the FN steps watch the result instead: leaving to the home screen for FN alone, the
 * brightness setting for FN + Vol, the window focus for FN + Y (the shade takes it), and
 * sys.joymouse.active for the joystick mouse.
 */
public class TutorialActivity extends Activity {

    private static final String TAG = "Tutorial";

    static final String ACTION_TUTORIAL = "org.andr36oid.guide.action.TUTORIAL";

    private static final String JOYMOUSE_PACKAGE = "com.gameconsole.joymouse";
    private static final String JOYMOUSE_ACTIVE_PROPERTY = "sys.joymouse.active";
    // Intent.ACTION_CLOSE_SYSTEM_DIALOGS reason when the power menu opens
    private static final String REASON_GLOBAL_ACTIONS = "globalactions";

    /** How long the home screen stays up before the tutorial comes back after FN. */
    private static final long RETURN_DELAY_MS = 2000;
    /** How often FN may take the user out of one FN step before it counts as leaving. */
    private static final int MAX_RETURNS = 2;
    private static final long MOUSE_POLL_MS = 300;
    private static final long FOCUS_CHECK_MS = 300;
    private static final float STICK_THRESHOLD = 0.6f;

    private static final int STEP_WELCOME = 0;
    private static final int STEP_FACE = 1;
    private static final int STEP_DPAD = 2;
    private static final int STEP_STICKS = 3;
    private static final int STEP_SHOULDERS = 4;
    private static final int STEP_HOME = 5;
    private static final int STEP_BRIGHTNESS = 6;
    private static final int STEP_SHADE = 7;
    private static final int STEP_MOUSE = 8;
    private static final int STEP_POWER = 9;
    private static final int STEP_GAMES = 10;
    private static final int STEP_DONE = 11;

    /** Whether a tutorial is on screen or in the background in this process. */
    private static volatile boolean sOpen;

    private static final class Step {
        final int id;
        final int title;
        final int body;
        final int[] tasks;
        // Keys that tick off tasks, in the same order; 0 for tasks that aren't key presses
        final int[] keys;
        boolean[] done;

        Step(int id, int title, int body, int[] tasks, int[] keys) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.tasks = tasks;
            this.keys = keys;
            this.done = new boolean[tasks.length];
        }

        boolean allDone() {
            for (boolean d : done) {
                if (!d) return false;
            }
            return true;
        }

        /** FN is part of the step, so a trip to the home screen gets undone. */
        boolean usesFn() {
            return id == STEP_HOME || id == STEP_BRIGHTNESS || id == STEP_SHADE
                    || id == STEP_MOUSE;
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Step> mSteps = new ArrayList<>();
    // Keys whose down was used by a step: their up is swallowed too, so no fallback key
    // (A is center, B is Back) and no half click is left over
    private final SparseBooleanArray mSwallowUp = new SparseBooleanArray();

    private int mIndex;
    // Where Back goes from the last page after Skip tutorial
    private int mSkippedFrom = -1;
    private boolean mResumed;
    private boolean mReturnPending;
    private int mLeaves;
    private CharSequence mNote;

    private TextView mStepLabel;
    private ProgressBar mProgress;
    private ScrollView mTextScroll;
    private TextView mTitle;
    private TextView mBody;
    private View mSide;
    private LinearLayout mTasks;
    private FrameLayout mExtra;
    private TextView mNoteView;
    private Button mBack;
    private Button mSecondary;
    private Button mNext;
    private StickView mStickView;

    // Detectors, only active on their step
    private ContentObserver mBrightnessObserver;
    private float mLastBrightness;
    private BroadcastReceiver mPowerReceiver;
    private boolean mScreenWentOff;
    private boolean mMousePolling;
    private final float[] mStickAxes = new float[4];

    static boolean isOpen() {
        return sOpen;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sOpen = true;
        buildSteps();

        setContentView(R.layout.tutorial_activity);
        mStepLabel = findViewById(R.id.step_label);
        mProgress = findViewById(R.id.progress);
        mTextScroll = findViewById(R.id.text_scroll);
        mTitle = findViewById(R.id.title);
        mBody = findViewById(R.id.body);
        mSide = findViewById(R.id.side);
        mTasks = findViewById(R.id.tasks);
        mExtra = findViewById(R.id.extra);
        mNoteView = findViewById(R.id.note);
        mBack = findViewById(R.id.back);
        mSecondary = findViewById(R.id.secondary);
        mNext = findViewById(R.id.next);

        mBack.setOnClickListener(v -> goBack());
        mSecondary.setOnClickListener(v -> {
            if (current().id == STEP_DONE) {
                openGuide();
            } else {
                skipTutorial();
            }
        });
        mNext.setOnClickListener(v -> goNext());
        mProgress.setMax(mSteps.size() - 1);

        int start = 0;
        if (savedInstanceState != null) {
            start = savedInstanceState.getInt("step", 0);
            mSkippedFrom = savedInstanceState.getInt("skipped_from", -1);
            for (int i = 0; i < mSteps.size(); i++) {
                final boolean[] done = savedInstanceState.getBooleanArray("done" + i);
                if (done != null && done.length == mSteps.get(i).done.length) {
                    mSteps.get(i).done = done;
                }
            }
        }
        showStep(Math.max(0, Math.min(start, mSteps.size() - 1)));
    }

    private void buildSteps() {
        final boolean fnShortcuts = SystemProperties.getBoolean("ro.andr36oid.fn_hotkeys", false);
        final boolean mouse = hasPackage(JOYMOUSE_PACKAGE);

        add(STEP_WELCOME, R.string.tutorial_welcome_title, R.string.tutorial_welcome_body);
        add(STEP_FACE, R.string.tutorial_face_title, R.string.tutorial_face_body,
                new int[] {R.string.tutorial_press_a, R.string.tutorial_press_b,
                        R.string.tutorial_press_x, R.string.tutorial_press_y},
                new int[] {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y});
        add(STEP_DPAD, R.string.tutorial_dpad_title, R.string.tutorial_dpad_body,
                new int[] {R.string.tutorial_dpad_up, R.string.tutorial_dpad_down,
                        R.string.tutorial_dpad_left, R.string.tutorial_dpad_right},
                new int[] {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT});
        add(STEP_STICKS, R.string.tutorial_sticks_title, R.string.tutorial_sticks_body,
                new int[] {R.string.tutorial_stick_left, R.string.tutorial_stick_right,
                        R.string.tutorial_stick_click},
                new int[] {0, 0, 0});
        add(STEP_SHOULDERS, R.string.tutorial_shoulder_title, R.string.tutorial_shoulder_body,
                new int[] {R.string.tutorial_press_l1, R.string.tutorial_press_r1,
                        R.string.tutorial_press_l2, R.string.tutorial_press_r2},
                new int[] {KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2});
        add(STEP_HOME, R.string.tutorial_home_title, R.string.tutorial_home_body,
                new int[] {R.string.tutorial_home_task}, new int[] {0});
        if (fnShortcuts) {
            add(STEP_BRIGHTNESS, R.string.tutorial_brightness_title,
                    R.string.tutorial_brightness_body,
                    new int[] {R.string.tutorial_brightness_up,
                            R.string.tutorial_brightness_down},
                    new int[] {0, 0});
            add(STEP_SHADE, R.string.tutorial_shade_title, R.string.tutorial_shade_body,
                    new int[] {R.string.tutorial_shade_open, R.string.tutorial_shade_close},
                    new int[] {0, 0});
        }
        if (mouse) {
            add(STEP_MOUSE, R.string.tutorial_mouse_title, fnShortcuts
                            ? R.string.tutorial_mouse_body_fn : R.string.tutorial_mouse_body,
                    new int[] {R.string.tutorial_mouse_on, R.string.tutorial_mouse_off},
                    new int[] {0, 0});
        }
        add(STEP_POWER, R.string.tutorial_power_title, R.string.tutorial_power_body,
                new int[] {R.string.tutorial_power_sleep, R.string.tutorial_power_menu},
                new int[] {0, 0});
        add(STEP_GAMES, R.string.tutorial_games_title, R.string.tutorial_games_body);
        final boolean fnHelp = fnShortcuts
                && SystemProperties.getBoolean("ro.andr36oid.fn_help", false);
        add(STEP_DONE, R.string.tutorial_done_title,
                fnHelp ? R.string.tutorial_done_body_fn_help : R.string.tutorial_done_body);
    }

    private void add(int id, int title, int body) {
        add(id, title, body, new int[0], new int[0]);
    }

    private void add(int id, int title, int body, int[] tasks, int[] keys) {
        mSteps.add(new Step(id, title, body, tasks, keys));
    }

    private Step current() {
        return mSteps.get(mIndex);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("step", mIndex);
        outState.putInt("skipped_from", mSkippedFrom);
        for (int i = 0; i < mSteps.size(); i++) {
            outState.putBooleanArray("done" + i, mSteps.get(i).done);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        mHandler.removeCallbacks(mReturnRunnable);
        mReturnPending = false;
    }

    @Override
    protected void onPause() {
        mResumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopDetectors();
        mHandler.removeCallbacksAndMessages(null);
        sOpen = false;
        super.onDestroy();
    }

    // ---- Navigation

    private void showStep(int index) {
        stopDetectors();
        mIndex = index;
        mLeaves = 0;
        mNote = null;
        final Step step = current();

        mStepLabel.setText(getString(R.string.tutorial_step, index + 1, mSteps.size()));
        mProgress.setProgress(index);
        mTitle.setText(step.title);
        CharSequence body = getText(step.body);
        if (step.id == STEP_GAMES) {
            final CharSequence space = describeEasyroms();
            if (space != null) {
                body = TextUtils.concat(body, "\n\n", space);
            }
        }
        mBody.setText(body);
        mTextScroll.scrollTo(0, 0);

        mTasks.removeAllViews();
        for (int i = 0; i < step.tasks.length; i++) {
            final CheckBox box = new CheckBox(this);
            box.setText(step.tasks[i]);
            box.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            // Only a tick mark: the buttons below are the only things to focus
            box.setFocusable(false);
            box.setClickable(false);
            box.setChecked(step.done[i]);
            mTasks.addView(box, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        mExtra.removeAllViews();
        mStickView = null;
        if (step.id == STEP_STICKS) {
            mStickView = new StickView(this);
            mExtra.addView(mStickView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (step.id == STEP_BRIGHTNESS) {
            mNote = getText(R.string.tutorial_brightness_hint);
        }
        mSide.setVisibility(step.tasks.length > 0 || mExtra.getChildCount() > 0
                ? View.VISIBLE : View.GONE);

        mBack.setVisibility(index == 0 ? View.INVISIBLE : View.VISIBLE);
        mSecondary.setText(step.id == STEP_DONE
                ? R.string.tutorial_open_guide : R.string.tutorial_skip_all);
        startDetectors();
        updateState();
        mNext.requestFocus();
    }

    /** Refreshes the ticks, the note and the Next button after something was done. */
    private void updateState() {
        final Step step = current();
        for (int i = 0; i < mTasks.getChildCount() && i < step.done.length; i++) {
            ((CheckBox) mTasks.getChildAt(i)).setChecked(step.done[i]);
        }
        final boolean complete = step.tasks.length > 0 && step.allDone();
        CharSequence note = mNote;
        if (complete && note == null) {
            note = getText(R.string.tutorial_well_done);
        }
        mNoteView.setText(note);
        mNoteView.setVisibility(note != null ? View.VISIBLE : View.GONE);

        final int next;
        if (step.id == STEP_WELCOME) {
            next = R.string.tutorial_start;
        } else if (step.id == STEP_DONE) {
            next = R.string.tutorial_done;
        } else if (step.tasks.length == 0 || complete) {
            next = R.string.tutorial_next;
        } else {
            next = R.string.tutorial_skip_step;
        }
        mNext.setText(next);
    }

    private void markDone(int task) {
        final Step step = current();
        if (task < 0 || task >= step.done.length || step.done[task]) {
            return;
        }
        final boolean wasComplete = step.allDone();
        step.done[task] = true;
        if (step.id == STEP_HOME || step.id == STEP_BRIGHTNESS) {
            // Keep "welcome back" or the brightness hint only until the step is done
            mNote = step.id == STEP_HOME ? getText(R.string.tutorial_home_back) : null;
        }
        if (step.allDone() && !wasComplete) {
            mNext.requestFocus();
        }
        updateState();
    }

    private void goNext() {
        if (current().id == STEP_DONE) {
            finishTutorial();
        } else if (mIndex < mSteps.size() - 1) {
            showStep(mIndex + 1);
        }
    }

    private void goBack() {
        if (current().id == STEP_DONE && mSkippedFrom >= 0) {
            final int from = mSkippedFrom;
            mSkippedFrom = -1;
            showStep(from);
        } else if (mIndex > 0) {
            showStep(mIndex - 1);
        } else {
            skipTutorial();
        }
    }

    /** Straight to the last page, which says where the guide is. */
    private void skipTutorial() {
        mSkippedFrom = mIndex;
        showStep(mSteps.size() - 1);
    }

    private void finishTutorial() {
        FirstBootReceiver.markShown(this);
        finish();
    }

    private void openGuide() {
        FirstBootReceiver.markShown(this);
        finish();
        try {
            startActivity(new Intent(this, GuideActivity.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't open the guide", e);
        }
    }

    // ---- Buttons the app sees

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean first = down && event.getRepeatCount() == 0;

        if (mSwallowUp.get(key) && (!down || event.getRepeatCount() > 0)) {
            // Repeats and the up of a press that was already used
            if (!down) mSwallowUp.delete(key);
            return true;
        }

        if (key == KeyEvent.KEYCODE_BUTTON_SELECT) {
            // Select always skips the step (its fallback, Menu, is swallowed with it)
            if (first) {
                mSwallowUp.put(key, true);
                goNext();
            }
            return true;
        }

        if (first && handleStepKey(key)) {
            mSwallowUp.put(key, true);
            return true;
        }

        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            // The buttons are all in one row, so up and down scroll the text instead
            if (down) {
                final int direction = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                mTextScroll.smoothScrollBy(0, direction * mTextScroll.getHeight() / 3);
            }
            return true;
        }

        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            if (first) {
                mSwallowUp.put(key, true);
                goBack();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Whether the current step still waits for this key. Once its task is ticked the key does
     * its normal job again, so A can press Skip step and B can go back.
     */
    private boolean isStepKey(int key) {
        final Step step = current();
        for (int i = 0; i < step.keys.length; i++) {
            if (step.keys[i] == key && !step.done[i]) {
                return true;
            }
        }
        return step.id == STEP_STICKS && isStickClick(key) && !step.done[2];
    }

    /** Ticks off a key press the step waits for. Returns true if the key was used. */
    private boolean handleStepKey(int key) {
        final Step step = current();
        if (!isStepKey(key)) {
            return false;
        }
        if (step.id == STEP_STICKS) {
            markDone(2);
            return true;
        }
        for (int i = 0; i < step.keys.length; i++) {
            if (step.keys[i] == key && !step.done[i]) {
                markDone(i);
            }
        }
        return true;
    }

    private static boolean isStickClick(int key) {
        return key == KeyEvent.KEYCODE_BUTTON_THUMBL || key == KeyEvent.KEYCODE_BUTTON_THUMBR;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (current().id == STEP_STICKS && event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            mStickAxes[0] = event.getAxisValue(MotionEvent.AXIS_X);
            mStickAxes[1] = event.getAxisValue(MotionEvent.AXIS_Y);
            mStickAxes[2] = event.getAxisValue(MotionEvent.AXIS_Z);
            mStickAxes[3] = event.getAxisValue(MotionEvent.AXIS_RZ);
            if (mStickView != null) {
                mStickView.setAxes(mStickAxes[0], mStickAxes[1], mStickAxes[2], mStickAxes[3]);
            }
            if (Math.abs(mStickAxes[0]) > STICK_THRESHOLD
                    || Math.abs(mStickAxes[1]) > STICK_THRESHOLD) {
                markDone(0);
            }
            if (Math.abs(mStickAxes[2]) > STICK_THRESHOLD
                    || Math.abs(mStickAxes[3]) > STICK_THRESHOLD) {
                markDone(1);
            }
            // Used here, so the left stick doesn't also move the focus
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    // ---- FN, the shade and the power button: watched from the outside

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        onLeft();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Belt and braces for the FN step, in case the leave hint didn't come. Not when the
        // screen just went off: that's the power step.
        if (current().id == STEP_HOME && isInteractive()) {
            onLeft();
        }
    }

    /**
     * The user went to the home screen, most likely with FN. On a step about FN that's the
     * point (or a slip), so the tutorial comes back, up to MAX_RETURNS times per step. Anywhere
     * else, or after that, the user left on purpose: stay in the background and don't open by
     * itself on the next start.
     */
    private void onLeft() {
        if (isFinishing() || mReturnPending) {
            return;
        }
        final Step step = current();
        if (step.usesFn() && mLeaves < MAX_RETURNS) {
            mLeaves++;
            if (step.id == STEP_HOME) {
                markDone(0);
            } else {
                mNote = getText(R.string.tutorial_fn_alone);
                updateState();
            }
            mReturnPending = true;
            mHandler.postDelayed(mReturnRunnable, RETURN_DELAY_MS);
        } else {
            FirstBootReceiver.markShown(this);
        }
    }

    private final Runnable mReturnRunnable = () -> {
        mReturnPending = false;
        if (isFinishing() || isDestroyed() || mResumed) {
            return;
        }
        try {
            // Runs as the system uid, so it may come back to the front from the background
            startActivity(new Intent(this, TutorialActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't come back after FN", e);
        }
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        mHandler.removeCallbacks(mFocusLost);
        if (!hasFocus) {
            // Checked a moment later: going home with FN also takes the focus first, but
            // then pauses the activity
            mHandler.postDelayed(mFocusLost, FOCUS_CHECK_MS);
        } else if (current().id == STEP_SHADE && current().done[0]) {
            markDone(1);
        }
    }

    /**
     * Still resumed but without focus: a system window is in front. On the shade step that's
     * the shade (the power menu too, but then FN + Y still did something). On the power step
     * it's the power menu, in case its broadcast didn't come.
     */
    private final Runnable mFocusLost = () -> {
        if (!mResumed || hasWindowFocus() || !isInteractive()) {
            return;
        }
        final int id = current().id;
        if (id == STEP_SHADE) {
            markDone(0);
        } else if (id == STEP_POWER) {
            markDone(1);
        }
    };

    private void startDetectors() {
        final int id = current().id;
        if (id == STEP_BRIGHTNESS) {
            mLastBrightness = readBrightness();
            mBrightnessObserver = new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    final float brightness = readBrightness();
                    if (brightness > mLastBrightness + 0.0001f) {
                        markDone(0);
                    } else if (brightness < mLastBrightness - 0.0001f) {
                        markDone(1);
                    }
                    mLastBrightness = brightness;
                }
            };
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FLOAT), false,
                    mBrightnessObserver);
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false,
                    mBrightnessObserver);
        } else if (id == STEP_POWER) {
            mScreenWentOff = false;
            mPowerReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        mScreenWentOff = true;
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        if (mScreenWentOff) {
                            markDone(0);
                        }
                    } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                            && REASON_GLOBAL_ACTIONS.equals(intent.getStringExtra("reason"))) {
                        markDone(1);
                    }
                }
            };
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            registerReceiver(mPowerReceiver, filter);
        } else if (id == STEP_MOUSE) {
            mMousePolling = true;
            mHandler.post(mMousePoll);
        }
    }

    private void stopDetectors() {
        if (mBrightnessObserver != null) {
            getContentResolver().unregisterContentObserver(mBrightnessObserver);
            mBrightnessObserver = null;
        }
        if (mPowerReceiver != null) {
            unregisterReceiver(mPowerReceiver);
            mPowerReceiver = null;
        }
        mMousePolling = false;
        mHandler.removeCallbacks(mMousePoll);
    }

    private final Runnable mMousePoll = new Runnable() {
        @Override
        public void run() {
            if (!mMousePolling || current().id != STEP_MOUSE) {
                return;
            }
            // Set by joyMouse's toggle and by FN + X
            if (SystemProperties.getBoolean(JOYMOUSE_ACTIVE_PROPERTY, false)) {
                markDone(0);
            } else if (current().done[0]) {
                markDone(1);
            }
            mHandler.postDelayed(this, MOUSE_POLL_MS);
        }
    };

    private float readBrightness() {
        final float value = Settings.System.getFloat(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FLOAT, Float.NaN);
        if (!Float.isNaN(value)) {
            return value;
        }
        return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 0)
                / 255f;
    }

    // ---- Helpers

    /** "EASYROMS on this card: 52 GB free of 55 GB", or null if it isn't mounted. */
    private CharSequence describeEasyroms() {
        try {
            for (StorageVolume volume : getSystemService(StorageManager.class)
                    .getStorageVolumes()) {
                final String name = volume.getDescription(this);
                final File dir = volume.getDirectory();
                if (dir == null || name == null
                        || !name.toUpperCase(Locale.ROOT).contains("EASYROMS")) {
                    continue;
                }
                final StatFs stat = new StatFs(dir.getPath());
                return getString(R.string.tutorial_games_space,
                        Formatter.formatShortFileSize(this, stat.getAvailableBytes()),
                        Formatter.formatShortFileSize(this, stat.getTotalBytes()));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't read EASYROMS", e);
        }
        return null;
    }

    private boolean isInteractive() {
        return getSystemService(PowerManager.class).isInteractive();
    }

    private boolean hasPackage(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
