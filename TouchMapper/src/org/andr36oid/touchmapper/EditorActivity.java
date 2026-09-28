package org.andr36oid.touchmapper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen editor, over the game it edits (translucent, the game stays visible). Works with
 * the buttons alone: a crosshair to point, a button to press for each control, menus for the
 * rest. Opened with FN + L1 in the game, or from Settings > Touch controls.
 */
public class EditorActivity extends Activity implements Choreographer.FrameCallback {

    static final String EXTRA_PACKAGE = "package";

    private enum Mode {
        BROWSE,          // crosshair moves, A adds or changes
        MOVE,            // the selected control follows the crosshair
        CAPTURE_BUTTON,  // waiting for the button of a tap, hold or swipe
        CAPTURE_STICK,   // waiting for the stick of a joystick or camera
    }

    private static final float STICK_DEADZONE = 0.2f;
    private static final float CAPTURE_THRESHOLD = 0.6f;
    private static final long CANCEL_HOLD_MS = 700;
    /** Crosshair speed at full tilt, share of the shorter screen side per second. */
    private static final float CURSOR_SPEED = 0.9f;
    private static final float DPAD_SPEED = 0.35f;
    private static final float DPAD_FAST_SPEED = 0.8f;
    private static final long DPAD_FAST_AFTER_MS = 600;
    private static final float DPAD_STEP_PX = 3f;
    private static final float SIZE_STEP = 1.12f;
    private static final float RESIZE_SPEED = 1.2f;  // per second at full tilt, as a factor

    private EditorView mView;
    private Profile mProfile;
    private String mSavedJson;
    private String mAppLabel;
    private boolean mInvertRightY;

    private Mode mMode = Mode.BROWSE;
    private int mLayer;
    private float mCursorX = -1;
    private float mCursorY = -1;
    private Profile.Control mSelected;
    private Profile.Control mDraft;              // being added
    private Profile.Control mCaptureTarget;      // changing its button or stick
    private float mMoveFromX;
    private float mMoveFromY;
    private String mMessage;

    // Held input for continuous movement
    private float mLeftX;
    private float mLeftY;
    private float mRightY;
    private int mDpadX;
    private int mDpadY;
    private int mHatX;
    private int mHatY;
    private long mDpadSince;
    private boolean mFrameScheduled;
    private long mLastFrameNanos;

    // After a stick was picked for a control: ignore the sticks until they are let go.
    private boolean mWaitForSticksCentered;

    // Button capture: act on release, so B can be held to cancel
    private String mCaptureDown;
    private long mCaptureDownAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String pkg = getIntent().getStringExtra(EXTRA_PACKAGE);
        if (pkg == null) {
            finish();
            return;
        }
        // The pad belongs to the editor now, not to the game's touch controls.
        Profiles.setActive(null);

        mProfile = Profiles.loadOrCreate(pkg);
        mSavedJson = json(mProfile);
        mAppLabel = appLabel(pkg);
        mInvertRightY = invertRightY();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);
        mView = new EditorView(this);
        setContentView(mView);
        final WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.hide(WindowInsets.Type.systemBars());
            insets.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        mView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (mCursorX < 0 && v.getWidth() > 0) {
                mCursorX = v.getWidth() / 2f;
                mCursorY = v.getHeight() / 2f;
            }
            mCursorX = Math.min(mCursorX, v.getWidth() - 1);
            mCursorY = Math.min(mCursorY, v.getHeight() - 1);
            refresh();
        });
        refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // FN (Home) or anything else that covers the editor leaves it; unsaved changes go.
        if (!isFinishing()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDestroy();
        // Let the watcher put the game's touch controls back.
        WatcherService.refresh(this);
    }

    // --- Input ----------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_HOME
                || code == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(event);
        }
        switch (mMode) {
            case CAPTURE_BUTTON:
                captureButton(event);
                return true;
            case CAPTURE_STICK:
                captureStickKey(event);
                return true;
            default:
                break;
        }
        if (isDpad(code)) {
            trackDpad(event);
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        if (event.getRepeatCount() > 0 && code != KeyEvent.KEYCODE_BUTTON_L1
                && code != KeyEvent.KEYCODE_BUTTON_R1) {
            return true;
        }
        if (mMode == Mode.MOVE) {
            onMoveKey(code);
        } else {
            onBrowseKey(code);
        }
        return true;
    }

    private static boolean isDpad(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private void onBrowseKey(int code) {
        mMessage = null;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (mSelected != null) {
                    showControlMenu(mSelected);
                } else {
                    showAddMenu();
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (mSelected != null) {
                    removeControl(mSelected);
                    mMessage = getString(R.string.editor_deleted);
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                switchLayer();
                break;
            case KeyEvent.KEYCODE_BUTTON_L1:
                resize(mSelected, 1f / SIZE_STEP);
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                resize(mSelected, SIZE_STEP);
                break;
            case KeyEvent.KEYCODE_BUTTON_L2:
                jump(-1);
                break;
            case KeyEvent.KEYCODE_BUTTON_R2:
                jump(1);
                break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_MENU:
                showOptions();
                break;
            case KeyEvent.KEYCODE_BUTTON_START:
                saveAndClose();
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                close();
                break;
            default:
                break;
        }
        refresh();
    }

    private void onMoveKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                mMode = Mode.BROWSE;
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (mSelected != null) {
                    mSelected.x = mMoveFromX;
                    mSelected.y = mMoveFromY;
                    mCursorX = MarkerPainter.centerX(mSelected, mView.getWidth());
                    mCursorY = MarkerPainter.centerY(mSelected, mView.getHeight());
                }
                mMode = Mode.BROWSE;
                break;
            case KeyEvent.KEYCODE_BUTTON_L1:
                resize(mSelected, 1f / SIZE_STEP);
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                resize(mSelected, SIZE_STEP);
                break;
            default:
                break;
        }
        refresh();
    }

    private void trackDpad(KeyEvent event) {
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down && event.getRepeatCount() > 0) {
            return;
        }
        final int before = mDpadX * 3 + mDpadY;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: mDpadX = down ? -1 : (mDpadX == -1 ? 0 : mDpadX); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: mDpadX = down ? 1 : (mDpadX == 1 ? 0 : mDpadX); break;
            case KeyEvent.KEYCODE_DPAD_UP: mDpadY = down ? -1 : (mDpadY == -1 ? 0 : mDpadY); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: mDpadY = down ? 1 : (mDpadY == 1 ? 0 : mDpadY); break;
            default: break;
        }
        if (down) {
            // One small step right away, for fine placement; holding keeps it going.
            moveCursor(mDpadX * DPAD_STEP_PX, mDpadY * DPAD_STEP_PX);
            if (before == 0) {
                mDpadSince = SystemClock.uptimeMillis();
            }
        }
        scheduleFrame();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || event.getAction() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event);
        }
        final float lx = event.getAxisValue(MotionEvent.AXIS_X);
        final float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        final float rx = event.getAxisValue(MotionEvent.AXIS_Z);
        float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
        if (mInvertRightY) {
            ry = -ry;
        }
        // The d-pad of some pads comes as a hat
        final float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        final float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);

        if (mMode == Mode.CAPTURE_STICK) {
            if (Math.hypot(lx, ly) > CAPTURE_THRESHOLD) {
                stickCaptured(Profile.STICK_LEFT);
            } else if (Math.hypot(rx, ry) > CAPTURE_THRESHOLD) {
                stickCaptured(Profile.STICK_RIGHT);
            } else if (Math.abs(hx) > 0.5f || Math.abs(hy) > 0.5f) {
                stickCaptured(Profile.STICK_DPAD);
            }
            return true;
        }
        if (mWaitForSticksCentered) {
            if (deadzone(lx) != 0 || deadzone(ly) != 0 || deadzone(rx) != 0 || deadzone(ry) != 0) {
                return true;
            }
            mWaitForSticksCentered = false;
        }
        mLeftX = deadzone(lx);
        mLeftY = deadzone(ly);
        mRightY = deadzone(ry);
        final int hatX = Math.round(hx);
        final int hatY = Math.round(hy);
        if ((hatX != 0 || hatY != 0) && mHatX == 0 && mHatY == 0 && mDpadX == 0 && mDpadY == 0) {
            mDpadSince = SystemClock.uptimeMillis();
        }
        mHatX = hatX;
        mHatY = hatY;
        scheduleFrame();
        return true;
    }

    private static float deadzone(float v) {
        if (Math.abs(v) < STICK_DEADZONE) {
            return 0f;
        }
        final float s = (Math.abs(v) - STICK_DEADZONE) / (1f - STICK_DEADZONE);
        return Math.copySign(Math.min(1f, s), v);
    }

    private void scheduleFrame() {
        if (!mFrameScheduled) {
            mFrameScheduled = true;
            mLastFrameNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        mFrameScheduled = false;
        final float dt = mLastFrameNanos == 0 ? 1f / 60f
                : Math.min((frameTimeNanos - mLastFrameNanos) / 1e9f, 0.05f);
        mLastFrameNanos = frameTimeNanos;
        final float side = Math.min(mView.getWidth(), mView.getHeight());
        boolean again = false;

        if (mMode == Mode.BROWSE || mMode == Mode.MOVE) {
            float dx = 0;
            float dy = 0;
            if (mLeftX != 0 || mLeftY != 0) {
                // Slow near the middle for fine placement, fast at full tilt
                final float gain = (float) Math.hypot(mLeftX, mLeftY);
                dx += mLeftX * gain * CURSOR_SPEED * side * dt;
                dy += mLeftY * gain * CURSOR_SPEED * side * dt;
                again = true;
            }
            final int dirX = mDpadX != 0 ? mDpadX : mHatX;
            final int dirY = mDpadY != 0 ? mDpadY : mHatY;
            if (dirX != 0 || dirY != 0) {
                final boolean fast = SystemClock.uptimeMillis() - mDpadSince > DPAD_FAST_AFTER_MS;
                final float speed = (fast ? DPAD_FAST_SPEED : DPAD_SPEED) * side * dt;
                dx += dirX * speed;
                dy += dirY * speed;
                again = true;
            }
            if (dx != 0 || dy != 0) {
                moveCursor(dx, dy);
            }
            if (mRightY != 0 && mSelected != null) {
                // Right stick up: bigger
                resize(mSelected, (float) Math.pow(RESIZE_SPEED, -mRightY * dt * 4));
                again = true;
            }
        }
        if (again) {
            mFrameScheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            mLastFrameNanos = 0;
        }
    }

    private void moveCursor(float dx, float dy) {
        final int w = mView.getWidth();
        final int h = mView.getHeight();
        if (w <= 0) {
            return;
        }
        mCursorX = Math.max(0, Math.min(w - 1, mCursorX + dx));
        mCursorY = Math.max(0, Math.min(h - 1, mCursorY + dy));
        if (mMode == Mode.MOVE && mSelected != null) {
            mSelected.x = mCursorX / Math.max(w - 1, 1);
            mSelected.y = mCursorY / Math.max(h - 1, 1);
        } else if (mMode == Mode.BROWSE) {
            updateSelection();
            mMessage = null;
        }
        refresh();
    }

    // --- Browsing -------------------------------------------------------------------------

    /** The control under the crosshair on the layer being edited, nearest first. */
    private void updateSelection() {
        final int w = mView.getWidth();
        final int h = mView.getHeight();
        Profile.Control best = null;
        float bestDistance = Float.MAX_VALUE;
        final float minReach = Math.min(w, h) * 0.06f;
        for (Profile.Control c : mProfile.controls) {
            if (c.layer != mLayer) {
                continue;
            }
            final float d = (float) Math.hypot(MarkerPainter.centerX(c, w) - mCursorX,
                    MarkerPainter.centerY(c, h) - mCursorY);
            // Sticks are picked near their middle, so a big joystick doesn't swallow the
            // controls around it.
            final float reach = c.usesStick() || Profile.SWIPE.equals(c.type) ? minReach * 1.4f
                    : Math.max(minReach, MarkerPainter.radiusPx(c, w, h));
            if (d <= reach && d < bestDistance) {
                best = c;
                bestDistance = d;
            }
        }
        mSelected = best;
    }

    /** L2 / R2: jump the crosshair to the previous or next control. */
    private void jump(int direction) {
        final List<Profile.Control> onLayer = new ArrayList<>();
        for (Profile.Control c : mProfile.controls) {
            if (c.layer == mLayer) {
                onLayer.add(c);
            }
        }
        if (onLayer.isEmpty()) {
            return;
        }
        int i = onLayer.indexOf(mSelected);
        i = i < 0 ? (direction > 0 ? 0 : onLayer.size() - 1)
                : (i + direction + onLayer.size()) % onLayer.size();
        final Profile.Control c = onLayer.get(i);
        mCursorX = MarkerPainter.centerX(c, mView.getWidth());
        mCursorY = MarkerPainter.centerY(c, mView.getHeight());
        mSelected = c;
    }

    private void switchLayer() {
        if (mProfile.shift == null) {
            mMessage = getString(R.string.editor_no_shift);
            return;
        }
        mLayer = 1 - mLayer;
        updateSelection();
    }

    private void resize(Profile.Control c, float factor) {
        if (c == null) {
            return;
        }
        if (Profile.SWIPE.equals(c.type)) {
            c.length = Profile.clamp(c.length * factor, 0.05f, 1f);
        } else {
            c.radius = Profile.clamp(c.radius * factor, 0.03f, 0.6f);
        }
        refresh();
    }

    private void removeControl(Profile.Control c) {
        mProfile.controls.remove(c);
        if (mSelected == c) {
            mSelected = null;
        }
        updateSelection();
    }

    // --- Menus ----------------------------------------------------------------------------

    private void showAddMenu() {
        final String[] types = {Profile.TAP, Profile.HOLD, Profile.JOYSTICK, Profile.CAMERA,
                Profile.SWIPE};
        final String[] items = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            items[i] = typeDescription(types[i]);
        }
        dialog(R.string.editor_add_title)
                .setItems(items, (d, which) -> startAdding(types[which]))
                .show();
    }

    private void startAdding(String type) {
        final Profile.Control c = new Profile.Control();
        c.type = type;
        c.layer = mLayer;
        c.x = mCursorX / Math.max(mView.getWidth() - 1, 1);
        c.y = mCursorY / Math.max(mView.getHeight() - 1, 1);
        switch (type) {
            case Profile.JOYSTICK:
                c.radius = Profile.DEFAULT_JOYSTICK_RADIUS;
                break;
            case Profile.CAMERA:
                c.radius = Profile.DEFAULT_CAMERA_RADIUS;
                break;
            default:
                c.radius = Profile.DEFAULT_TAP_RADIUS;
                break;
        }
        mDraft = c;
        mCaptureTarget = null;
        beginCapture(c);
    }

    private void beginCapture(Profile.Control c) {
        mCaptureDown = null;
        mMode = c.usesStick() ? Mode.CAPTURE_STICK : Mode.CAPTURE_BUTTON;
        mMessage = null;
        refresh();
    }

    private void showControlMenu(Profile.Control c) {
        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        items.add(getString(R.string.editor_menu_move));
        actions.add(() -> startMove(c));
        items.add(getString(c.usesStick() ? R.string.editor_menu_stick
                : R.string.editor_menu_button, Inputs.longLabel(c.input())));
        actions.add(() -> {
            mCaptureTarget = c;
            mDraft = null;
            beginCapture(c);
        });
        items.add(getString(R.string.editor_menu_type, typeName(c.type)));
        actions.add(() -> showTypeMenu(c));
        if (Profile.SWIPE.equals(c.type)) {
            items.add(getString(R.string.editor_menu_direction, directionName(c.angle)));
            actions.add(() -> showDirectionMenu(c));
        }
        if (Profile.CAMERA.equals(c.type)) {
            items.add(getString(R.string.editor_menu_speed, Math.round(c.speed * 100)));
            actions.add(() -> showSpeedMenu(c));
        }
        items.add(getString(R.string.editor_menu_delete));
        actions.add(() -> {
            removeControl(c);
            refresh();
        });
        dialog(R.string.editor_control_title)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Tap, hold and swipe share buttons; joystick and camera share sticks. */
    private void showTypeMenu(Profile.Control c) {
        final String[] types = c.usesStick()
                ? new String[] {Profile.JOYSTICK, Profile.CAMERA}
                : new String[] {Profile.TAP, Profile.HOLD, Profile.SWIPE};
        final String[] items = new String[types.length];
        int checked = 0;
        for (int i = 0; i < types.length; i++) {
            items[i] = typeDescription(types[i]);
            if (types[i].equals(c.type)) {
                checked = i;
            }
        }
        dialog(R.string.editor_type_title)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    final String type = types[which];
                    if (!type.equals(c.type)) {
                        c.type = type;
                        if (Profile.CAMERA.equals(type)
                                && c.radius < Profile.DEFAULT_CAMERA_RADIUS / 2) {
                            c.radius = Profile.DEFAULT_CAMERA_RADIUS;
                        }
                    }
                    d.dismiss();
                    if (Profile.SWIPE.equals(type)) {
                        showDirectionMenu(c);
                    }
                    refresh();
                })
                .show();
    }

    private static final int[] DIRECTIONS = {270, 90, 180, 0, 225, 315, 135, 45};

    private void showDirectionMenu(Profile.Control c) {
        final String[] items = new String[DIRECTIONS.length];
        int checked = -1;
        for (int i = 0; i < DIRECTIONS.length; i++) {
            items[i] = directionName(DIRECTIONS[i]);
            if (Math.round(c.angle) == DIRECTIONS[i]) {
                checked = i;
            }
        }
        dialog(R.string.editor_direction_title)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    c.angle = DIRECTIONS[which];
                    d.dismiss();
                    refresh();
                })
                .show();
    }

    private static final int[] SPEEDS = {25, 50, 75, 100, 150, 200, 300, 400};

    private void showSpeedMenu(Profile.Control c) {
        final String[] items = new String[SPEEDS.length];
        int checked = -1;
        for (int i = 0; i < SPEEDS.length; i++) {
            items[i] = getString(R.string.percent, SPEEDS[i]);
            if (Math.round(c.speed * 100) == SPEEDS[i]) {
                checked = i;
            }
        }
        dialog(R.string.editor_speed_title)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    c.speed = SPEEDS[which] / 100f;
                    d.dismiss();
                    refresh();
                })
                .show();
    }

    private void startMove(Profile.Control c) {
        mSelected = c;
        mMoveFromX = c.x;
        mMoveFromY = c.y;
        mCursorX = MarkerPainter.centerX(c, mView.getWidth());
        mCursorY = MarkerPainter.centerY(c, mView.getHeight());
        mMode = Mode.MOVE;
        refresh();
    }

    private void showOptions() {
        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        items.add(getString(mProfile.enabled ? R.string.options_enabled_on
                : R.string.options_enabled_off));
        actions.add(() -> {
            mProfile.enabled = !mProfile.enabled;
            refresh();
        });
        items.add(getString(mProfile.hints ? R.string.options_hints_on
                : R.string.options_hints_off));
        actions.add(() -> {
            mProfile.hints = !mProfile.hints;
            refresh();
        });
        items.add(getString(R.string.options_shift, mProfile.shift == null
                ? getString(R.string.shift_none) : Inputs.longLabel(mProfile.shift)));
        actions.add(this::showShiftMenu);
        items.add(getString(R.string.options_deadzone, mProfile.deadzone));
        actions.add(this::showDeadzoneMenu);
        items.add(getString(R.string.options_help));
        actions.add(this::showHelp);
        items.add(getString(R.string.options_clear));
        actions.add(() -> dialog(R.string.options_clear)
                .setMessage(R.string.options_clear_message)
                .setPositiveButton(R.string.options_clear_confirm, (d, w) -> {
                    mProfile.controls.clear();
                    mSelected = null;
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        dialog(R.string.options_title)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void showShiftMenu() {
        final String[] values = new String[Inputs.SHIFT_BUTTONS.length + 1];
        final String[] items = new String[values.length];
        values[0] = null;
        items[0] = getString(R.string.shift_none);
        int checked = 0;
        for (int i = 0; i < Inputs.SHIFT_BUTTONS.length; i++) {
            values[i + 1] = Inputs.SHIFT_BUTTONS[i];
            items[i + 1] = Inputs.longLabel(Inputs.SHIFT_BUTTONS[i]);
            if (Inputs.SHIFT_BUTTONS[i].equals(mProfile.shift)) {
                checked = i + 1;
            }
        }
        dialog(R.string.shift_title)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    setShift(values[which]);
                })
                .show();
    }

    private void setShift(String shift) {
        mProfile.shift = shift;
        int removed = 0;
        for (int i = mProfile.controls.size() - 1; i >= 0; i--) {
            final Profile.Control c = mProfile.controls.get(i);
            final boolean onShift = shift != null && shift.equals(c.button);
            final boolean orphan = shift == null && c.layer == 1;
            if (onShift || orphan) {
                mProfile.controls.remove(i);
                removed++;
            }
        }
        if (shift == null) {
            mLayer = 0;
        }
        mSelected = null;
        updateSelection();
        mMessage = removed > 0 ? getResources().getQuantityString(R.plurals.shift_removed,
                removed, removed) : null;
        refresh();
    }

    private static final int[] DEADZONES = {5, 10, 15, 20, 25, 30, 40};

    private void showDeadzoneMenu() {
        final String[] items = new String[DEADZONES.length];
        int checked = -1;
        for (int i = 0; i < DEADZONES.length; i++) {
            items[i] = getString(R.string.percent, DEADZONES[i]);
            if (DEADZONES[i] == mProfile.deadzone) {
                checked = i;
            }
        }
        dialog(R.string.deadzone_title)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    mProfile.deadzone = DEADZONES[which];
                    d.dismiss();
                    refresh();
                })
                .show();
    }

    private void showHelp() {
        dialog(R.string.help_title)
                .setMessage(R.string.help_text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private AlertDialog.Builder dialog(int title) {
        return new AlertDialog.Builder(this).setTitle(title);
    }

    // --- Capturing a button or stick ------------------------------------------------------

    private void captureButton(KeyEvent event) {
        final String button = Inputs.button(event);
        final boolean isB = "B".equals(button) || event.getKeyCode() == KeyEvent.KEYCODE_BACK;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                mCaptureDown = button;
                mCaptureDownAt = SystemClock.uptimeMillis();
            } else if (isB && SystemClock.uptimeMillis() - mCaptureDownAt >= CANCEL_HOLD_MS) {
                cancelCapture();
            }
            return;
        }
        if (button == null || !button.equals(mCaptureDown)) {
            return;
        }
        mCaptureDown = null;
        if (isB && SystemClock.uptimeMillis() - mCaptureDownAt >= CANCEL_HOLD_MS) {
            cancelCapture();
            return;
        }
        buttonCaptured(button);
    }

    private void captureStickKey(KeyEvent event) {
        final String button = Inputs.button(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return;
        }
        if (Inputs.isDpad(button)) {
            stickCaptured(Profile.STICK_DPAD);
        } else if ("B".equals(button) || event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            cancelCapture();
        }
    }

    private void cancelCapture() {
        mDraft = null;
        mCaptureTarget = null;
        mCaptureDown = null;
        mMode = Mode.BROWSE;
        mMessage = getString(R.string.editor_cancelled);
        refresh();
    }

    private void buttonCaptured(String button) {
        final Profile.Control target = mCaptureTarget != null ? mCaptureTarget : mDraft;
        if (target == null) {
            cancelCapture();
            return;
        }
        if (button.equals(mProfile.shift)) {
            mMessage = getString(R.string.editor_is_shift, Inputs.longLabel(button));
            refresh();
            return;
        }
        String note = null;
        final Profile.Control other = mProfile.find(button, target.layer);
        if (other != null && other != target) {
            mProfile.controls.remove(other);
            note = getString(R.string.editor_replaced, Inputs.longLabel(button));
        }
        if (Inputs.isDpad(button)) {
            final Profile.Control dpad = mProfile.find(Profile.STICK_DPAD, target.layer);
            if (dpad != null) {
                mProfile.controls.remove(dpad);
                note = getString(R.string.editor_replaced_dpad);
            }
        }
        target.button = button;
        finishCapture(target, note);
        if (Profile.SWIPE.equals(target.type) && mCaptureTarget == null) {
            showDirectionMenu(target);
        }
    }

    private void stickCaptured(String stick) {
        final Profile.Control target = mCaptureTarget != null ? mCaptureTarget : mDraft;
        if (target == null) {
            cancelCapture();
            return;
        }
        String note = null;
        final Profile.Control other = mProfile.find(stick, target.layer);
        if (other != null && other != target) {
            mProfile.controls.remove(other);
            note = getString(R.string.editor_replaced, Inputs.longLabel(stick));
        }
        if (Profile.STICK_DPAD.equals(stick)) {
            // The d-pad as a joystick takes all four directions on its layer.
            for (int i = mProfile.controls.size() - 1; i >= 0; i--) {
                final Profile.Control c = mProfile.controls.get(i);
                if (c != target && c.layer == target.layer && Inputs.isDpad(c.button)) {
                    mProfile.controls.remove(i);
                    note = getString(R.string.editor_replaced_dpad_buttons);
                }
            }
        }
        target.stick = stick;
        finishCapture(target, note);
    }

    private void finishCapture(Profile.Control target, String note) {
        if (mCaptureTarget == null && !mProfile.controls.contains(target)) {
            mProfile.controls.add(target);
        }
        mDraft = null;
        mCaptureTarget = null;
        mSelected = target;
        mMode = Mode.BROWSE;
        mMessage = note;
        // The stick that was just pushed must not move the crosshair or resize.
        mLeftX = 0;
        mLeftY = 0;
        mRightY = 0;
        mWaitForSticksCentered = true;
        refresh();
    }

    // --- Saving ---------------------------------------------------------------------------

    private boolean isDirty() {
        return !json(mProfile).equals(mSavedJson);
    }

    private void saveAndClose() {
        if (!Profiles.save(this, mProfile)) {
            mMessage = getString(R.string.editor_save_failed);
            refresh();
            return;
        }
        android.widget.Toast.makeText(this, getString(R.string.editor_saved, mAppLabel),
                android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }

    private void close() {
        if (!isDirty()) {
            finish();
            return;
        }
        dialog(R.string.editor_unsaved_title)
                .setMessage(R.string.editor_unsaved_message)
                .setPositiveButton(R.string.editor_save, (d, w) -> saveAndClose())
                .setNegativeButton(R.string.editor_discard, (d, w) -> finish())
                .setNeutralButton(R.string.editor_keep_editing, null)
                .show();
    }

    private static String json(Profile p) {
        try {
            return p.toJson();
        } catch (org.json.JSONException e) {
            return "";
        }
    }

    // --- Showing the state ----------------------------------------------------------------

    private void refresh() {
        if (mView == null || mProfile == null) {
            return;
        }
        mView.setProfile(mProfile, mLayer);
        mView.setSelected(mMode == Mode.CAPTURE_BUTTON || mMode == Mode.CAPTURE_STICK
                ? mCaptureTarget : mSelected);
        mView.setDraft(mDraft);
        mView.setCursor(mCursorX, mCursorY,
                mMode == Mode.BROWSE || mMode == Mode.MOVE);

        final String layer = mProfile.shift == null ? null : mLayer == 0
                ? getString(R.string.editor_layer_normal)
                : getString(R.string.editor_layer_shift, Inputs.longLabel(mProfile.shift));
        String status = layer;
        if (!mProfile.enabled) {
            status = getString(R.string.editor_profile_off);
        }
        mView.setTitle(getString(R.string.editor_title, mAppLabel), status);

        final List<EditorView.Hint> hints = new ArrayList<>();
        switch (mMode) {
            case BROWSE:
                mView.setPrompt(mMessage, null);
                if (mSelected != null) {
                    hints.add(new EditorView.Hint("A", getString(R.string.hint_change)));
                    hints.add(new EditorView.Hint("X", getString(R.string.hint_delete)));
                    hints.add(new EditorView.Hint("L1/R1", getString(R.string.hint_size)));
                } else {
                    hints.add(new EditorView.Hint("A", getString(R.string.hint_add)));
                }
                hints.add(new EditorView.Hint("L2/R2", getString(R.string.hint_next)));
                if (mProfile.shift != null) {
                    hints.add(new EditorView.Hint("Y", getString(R.string.hint_layer)));
                }
                hints.add(new EditorView.Hint("Select", getString(R.string.hint_options)));
                hints.add(new EditorView.Hint("Start", getString(R.string.hint_save)));
                hints.add(new EditorView.Hint("B", getString(R.string.hint_close)));
                break;
            case MOVE:
                mView.setPrompt(null, null);
                hints.add(new EditorView.Hint("✚", getString(R.string.hint_move)));
                hints.add(new EditorView.Hint("L1/R1", getString(R.string.hint_size)));
                hints.add(new EditorView.Hint("A", getString(R.string.hint_place)));
                hints.add(new EditorView.Hint("B", getString(R.string.hint_cancel)));
                break;
            case CAPTURE_BUTTON: {
                final Profile.Control target = mCaptureTarget != null ? mCaptureTarget : mDraft;
                mView.setPrompt(getString(R.string.capture_button,
                        typeName(target != null ? target.type : Profile.TAP).toLowerCase()),
                        mMessage != null ? mMessage : getString(R.string.capture_button_cancel));
                hints.add(new EditorView.Hint("B", getString(R.string.hint_hold_cancel)));
                break;
            }
            case CAPTURE_STICK: {
                final Profile.Control target = mCaptureTarget != null ? mCaptureTarget : mDraft;
                mView.setPrompt(getString(R.string.capture_stick,
                        typeName(target != null ? target.type : Profile.JOYSTICK).toLowerCase()),
                        getString(R.string.capture_stick_detail));
                hints.add(new EditorView.Hint("B", getString(R.string.hint_cancel)));
                break;
            }
        }
        mView.setHints(hints);
    }

    private String typeName(String type) {
        switch (type) {
            case Profile.HOLD: return getString(R.string.type_hold);
            case Profile.JOYSTICK: return getString(R.string.type_joystick);
            case Profile.CAMERA: return getString(R.string.type_camera);
            case Profile.SWIPE: return getString(R.string.type_swipe);
            default: return getString(R.string.type_tap);
        }
    }

    private String typeDescription(String type) {
        switch (type) {
            case Profile.HOLD: return getString(R.string.type_hold_long);
            case Profile.JOYSTICK: return getString(R.string.type_joystick_long);
            case Profile.CAMERA: return getString(R.string.type_camera_long);
            case Profile.SWIPE: return getString(R.string.type_swipe_long);
            default: return getString(R.string.type_tap_long);
        }
    }

    private String directionName(float angle) {
        switch (Math.round(angle) % 360) {
            case 270: return getString(R.string.direction_up);
            case 90: return getString(R.string.direction_down);
            case 180: return getString(R.string.direction_left);
            case 0: return getString(R.string.direction_right);
            case 225: return getString(R.string.direction_up_left);
            case 315: return getString(R.string.direction_up_right);
            case 135: return getString(R.string.direction_down_left);
            case 45: return getString(R.string.direction_down_right);
            default: return getString(R.string.direction_degrees, Math.round(angle));
        }
    }

    private String appLabel(String pkg) {
        final PackageManager pm = getPackageManager();
        try {
            final ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return info.loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Same device default as joyMouse: the right stick's Y is reversed except on the R36S. */
    private static boolean invertRightY() {
        final String set = SystemProperties.get("persist.sys.joymouse.invert_ry", "");
        if ("1".equals(set) || "true".equals(set)) {
            return true;
        }
        if ("0".equals(set) || "false".equals(set)) {
            return false;
        }
        return !"r36s".equals(SystemProperties.get("ro.product.device", ""));
    }
}
