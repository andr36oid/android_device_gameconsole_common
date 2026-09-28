package org.andr36oid.controllertest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Draws the R36S controls, lit while pressed and ticked once they worked, the two sticks
 * with their dead zone, the last key events and the battery.
 */
class ControllerView extends View {

    private static final int BACKGROUND = 0xFF0E1116;
    private static final int PANEL = 0xFF1A1F27;
    private static final int OUTLINE = 0xFF3A4350;
    private static final int TEXT = 0xFFC9D1D9;
    private static final int DIM = 0xFF6E7681;
    private static final int PRESSED = 0xFFFB8C00;
    private static final int TESTED = 0xFF2EA043;
    private static final int DEADZONE = 0x33FB8C00;

    /** The buttons the tester counts. FN is Home and never reaches an app. */
    private static final int[] BUTTONS = {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP,
    };

    private static final int MAX_EVENTS = 2;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMono = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Path mPath = new Path();

    private final Set<Integer> mDown = new HashSet<>();
    private final Set<Integer> mTested = new HashSet<>();
    /** D-pad directions pressed through a hat axis rather than keys */
    private final Set<Integer> mHatDown = new HashSet<>();
    private final ArrayDeque<String> mEvents = new ArrayDeque<>();

    /** Left stick X/Y, right stick Z/RZ, d-pad hat, as the framework reports them (-1..1). */
    private final float[] mAxes = new float[6];
    private final float[] mMin = new float[4];
    private final float[] mMax = new float[4];
    private float mFlatLeft;
    private float mFlatRight;
    private boolean mHasSticks;

    private Battery.Reading mBattery;
    private float mExitProgress;

    ControllerView(Context context) {
        super(context);
        mStroke.setStyle(Paint.Style.STROKE);
        mText.setColor(TEXT);
        mText.setTextAlign(Paint.Align.CENTER);
        mMono.setColor(TEXT);
        mMono.setTypeface(Typeface.MONOSPACE);
        mSmall.setTypeface(Typeface.MONOSPACE);
        mSmall.setTextAlign(Paint.Align.CENTER);
    }

    // Input

    void onKey(KeyEvent event) {
        final int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) {
                return;
            }
            mDown.add(code);
            mTested.add(code);
        } else {
            mDown.remove(code);
        }
        String name = KeyEvent.keyCodeToString(code);
        if (name.startsWith("KEYCODE_")) {
            name = name.substring(8);
        }
        final InputDevice device = event.getDevice();
        mEvents.addFirst(String.format(Locale.US, "%-13s %-4s key %3d  scan %3d  %s",
                name, event.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up",
                code, event.getScanCode(), device != null ? device.getName() : "?"));
        while (mEvents.size() > MAX_EVENTS) {
            mEvents.removeLast();
        }
        invalidate();
    }

    boolean onMotion(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || event.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        mAxes[0] = event.getAxisValue(MotionEvent.AXIS_X);
        mAxes[1] = event.getAxisValue(MotionEvent.AXIS_Y);
        mAxes[2] = event.getAxisValue(MotionEvent.AXIS_Z);
        mAxes[3] = event.getAxisValue(MotionEvent.AXIS_RZ);
        mAxes[4] = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        mAxes[5] = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        for (int i = 0; i < 4; i++) {
            mMin[i] = Math.min(mMin[i], mAxes[i]);
            mMax[i] = Math.max(mMax[i], mAxes[i]);
        }
        // Some clones report the d-pad as a hat instead of keys
        hat(mAxes[4] < -0.5f, KeyEvent.KEYCODE_DPAD_LEFT);
        hat(mAxes[4] > 0.5f, KeyEvent.KEYCODE_DPAD_RIGHT);
        hat(mAxes[5] < -0.5f, KeyEvent.KEYCODE_DPAD_UP);
        hat(mAxes[5] > 0.5f, KeyEvent.KEYCODE_DPAD_DOWN);
        final InputDevice device = event.getDevice();
        if (device != null && !mHasSticks) {
            readRanges(device);
        }
        invalidate();
        return true;
    }

    private void hat(boolean pressed, int code) {
        if (pressed) {
            if (mHatDown.add(code)) {
                mDown.add(code);
                mTested.add(code);
            }
        } else if (mHatDown.remove(code)) {
            mDown.remove(code);
        }
    }

    /** Looks for the internal joypad to show its sticks' dead zones. */
    void updateSticks() {
        mHasSticks = false;
        for (int id : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(id);
            if (device != null && device.supportsSource(InputDevice.SOURCE_JOYSTICK)) {
                readRanges(device);
                if (mHasSticks) {
                    break;
                }
            }
        }
        invalidate();
    }

    private void readRanges(InputDevice device) {
        final InputDevice.MotionRange x = device.getMotionRange(MotionEvent.AXIS_X,
                InputDevice.SOURCE_JOYSTICK);
        final InputDevice.MotionRange z = device.getMotionRange(MotionEvent.AXIS_Z,
                InputDevice.SOURCE_JOYSTICK);
        if (x == null) {
            return;
        }
        mHasSticks = true;
        mFlatLeft = x.getFlat();
        mFlatRight = z != null ? z.getFlat() : 0;
    }

    void releaseAll() {
        mDown.clear();
        mHatDown.clear();
        invalidate();
    }

    void setBattery(Battery.Reading reading) {
        mBattery = reading;
        invalidate();
    }

    void setExitProgress(float progress) {
        mExitProgress = progress;
        invalidate();
    }

    // Drawing. Everything is laid out in fractions of the view, the R36S screen is 640x480.

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        canvas.drawColor(BACKGROUND);
        mStroke.setStrokeWidth(Math.max(1.5f, h * 0.005f));
        mText.setTextSize(h * 0.038f);
        mMono.setTextSize(h * 0.03f);
        mSmall.setTextSize(h * 0.028f);

        // Title
        mText.setColor(DIM);
        canvas.drawText(getContext().getString(R.string.test_title).toUpperCase(Locale.ROOT),
                w / 2, h * 0.05f, mText);

        // Shoulder buttons along the top edge
        final float sy = h * 0.075f;
        final float sh = h * 0.065f;
        shoulder(canvas, w * 0.02f, sy, w * 0.15f, sh, "L2", KeyEvent.KEYCODE_BUTTON_L2);
        shoulder(canvas, w * 0.18f, sy, w * 0.15f, sh, "L1", KeyEvent.KEYCODE_BUTTON_L1);
        shoulder(canvas, w * 0.67f, sy, w * 0.15f, sh, "R1", KeyEvent.KEYCODE_BUTTON_R1);
        shoulder(canvas, w * 0.83f, sy, w * 0.15f, sh, "R2", KeyEvent.KEYCODE_BUTTON_R2);

        // Volume keys sit on the side of the console, shown up top
        pill(canvas, w * 0.43f, h * 0.2f, h * 0.15f, h * 0.06f, "VOL\u2212",
                KeyEvent.KEYCODE_VOLUME_DOWN);
        pill(canvas, w * 0.57f, h * 0.2f, h * 0.15f, h * 0.06f, "VOL+",
                KeyEvent.KEYCODE_VOLUME_UP);

        // D-pad and face buttons
        dpad(canvas, w * 0.17f, h * 0.34f, h * 0.24f);
        final float fx = w * 0.83f;
        final float fy = h * 0.34f;
        final float fd = h * 0.085f;
        final float fr = h * 0.047f;
        face(canvas, fx, fy - fd, fr, "X", KeyEvent.KEYCODE_BUTTON_X);
        face(canvas, fx - fd, fy, fr, "Y", KeyEvent.KEYCODE_BUTTON_Y);
        face(canvas, fx + fd, fy, fr, "A", KeyEvent.KEYCODE_BUTTON_A);
        face(canvas, fx, fy + fd, fr, "B", KeyEvent.KEYCODE_BUTTON_B);

        // Select and start
        pill(canvas, w * 0.42f, h * 0.33f, h * 0.17f, h * 0.06f, "SELECT",
                KeyEvent.KEYCODE_BUTTON_SELECT);
        pill(canvas, w * 0.58f, h * 0.33f, h * 0.17f, h * 0.06f, "START",
                KeyEvent.KEYCODE_BUTTON_START);

        // Sticks
        final float sr = h * 0.1f;
        stick(canvas, w * 0.3f, h * 0.6f, sr, 0, mFlatLeft, "L3",
                KeyEvent.KEYCODE_BUTTON_THUMBL);
        stick(canvas, w * 0.7f, h * 0.6f, sr, 2, mFlatRight, "R3",
                KeyEvent.KEYCODE_BUTTON_THUMBR);
        if (!mHasSticks) {
            mText.setColor(DIM);
            canvas.drawText(getContext().getString(R.string.test_no_stick), w / 2,
                    h * 0.6f, mText);
        }

        // Exit progress while START + SELECT are held
        if (mExitProgress > 0) {
            mStroke.setColor(PRESSED);
            final float r = h * 0.06f;
            mRect.set(w / 2 - r, h * 0.46f - r, w / 2 + r, h * 0.46f + r);
            canvas.drawArc(mRect, -90, 360 * mExitProgress, false, mStroke);
        }

        bottom(canvas, w, h);
    }

    private int fillFor(int code) {
        if (mDown.contains(code)) {
            return PRESSED;
        }
        return PANEL;
    }

    private int outlineFor(int code) {
        if (mDown.contains(code)) {
            return PRESSED;
        }
        return mTested.contains(code) ? TESTED : OUTLINE;
    }

    private int labelFor(int code) {
        return mDown.contains(code) ? BACKGROUND : TEXT;
    }

    private void drawLabel(Canvas canvas, String label, float cx, float cy, int color) {
        mText.setColor(color);
        canvas.drawText(label, cx, cy - (mText.descent() + mText.ascent()) / 2, mText);
    }

    private void shoulder(Canvas canvas, float x, float y, float w, float h, String label,
            int code) {
        mRect.set(x, y, x + w, y + h);
        final float r = h * 0.45f;
        mFill.setColor(fillFor(code));
        canvas.drawRoundRect(mRect, r, r, mFill);
        mStroke.setColor(outlineFor(code));
        canvas.drawRoundRect(mRect, r, r, mStroke);
        drawLabel(canvas, label, mRect.centerX(), mRect.centerY(), labelFor(code));
    }

    private void pill(Canvas canvas, float cx, float cy, float w, float h, String label,
            int code) {
        shoulder(canvas, cx - w / 2, cy - h / 2, w, h, label, code);
    }

    private void face(Canvas canvas, float cx, float cy, float r, String label, int code) {
        mFill.setColor(fillFor(code));
        canvas.drawCircle(cx, cy, r, mFill);
        mStroke.setColor(outlineFor(code));
        canvas.drawCircle(cx, cy, r, mStroke);
        drawLabel(canvas, label, cx, cy, labelFor(code));
    }

    private void dpad(Canvas canvas, float cx, float cy, float size) {
        final float a = size / 3f;
        // hub
        mFill.setColor(PANEL);
        mRect.set(cx - a / 2, cy - a / 2, cx + a / 2, cy + a / 2);
        canvas.drawRect(mRect, mFill);
        arm(canvas, cx - a / 2, cy - size / 2, a, KeyEvent.KEYCODE_DPAD_UP, 0);
        arm(canvas, cx - a / 2, cy + a / 2, a, KeyEvent.KEYCODE_DPAD_DOWN, 180);
        arm(canvas, cx - size / 2, cy - a / 2, a, KeyEvent.KEYCODE_DPAD_LEFT, 270);
        arm(canvas, cx + a / 2, cy - a / 2, a, KeyEvent.KEYCODE_DPAD_RIGHT, 90);
    }

    /** One d-pad arm: an a x a square at (x, y) with an arrow pointing {@code angle}. */
    private void arm(Canvas canvas, float x, float y, float a, int code, float angle) {
        mRect.set(x, y, x + a, y + a);
        final float r = a * 0.18f;
        mFill.setColor(fillFor(code));
        canvas.drawRoundRect(mRect, r, r, mFill);
        mStroke.setColor(outlineFor(code));
        canvas.drawRoundRect(mRect, r, r, mStroke);
        final float cx = mRect.centerX();
        final float cy = mRect.centerY();
        final float t = a * 0.2f;
        mPath.reset();
        mPath.moveTo(cx, cy - t);
        mPath.lineTo(cx + t, cy + t * 0.7f);
        mPath.lineTo(cx - t, cy + t * 0.7f);
        mPath.close();
        canvas.save();
        canvas.rotate(angle, cx, cy);
        mFill.setColor(labelFor(code));
        canvas.drawPath(mPath, mFill);
        canvas.restore();
    }

    private void stick(Canvas canvas, float cx, float cy, float r, int axis, float flat,
            String label, int code) {
        final boolean clicked = mDown.contains(code);
        mFill.setColor(PANEL);
        canvas.drawCircle(cx, cy, r, mFill);
        if (flat > 0) {
            mFill.setColor(DEADZONE);
            canvas.drawCircle(cx, cy, r * Math.min(1f, flat), mFill);
        }
        mStroke.setColor(OUTLINE);
        canvas.drawLine(cx - r, cy, cx + r, cy, mStroke);
        canvas.drawLine(cx, cy - r, cx, cy + r, mStroke);
        mStroke.setColor(outlineFor(code));
        canvas.drawCircle(cx, cy, r, mStroke);

        final float x = mAxes[axis];
        final float y = mAxes[axis + 1];
        final float dot = r * 0.16f;
        mFill.setColor(clicked ? PRESSED : TEXT);
        canvas.drawCircle(cx + x * (r - dot), cy + y * (r - dot), dot, mFill);

        drawLabel(canvas, flat > 0 ? String.format(Locale.US, "%s \u00b7 dz %.2f", label, flat)
                : label, cx, cy - r - mText.getTextSize() * 0.8f, clicked ? PRESSED : DIM);
        mSmall.setColor(TEXT);
        canvas.drawText(String.format(Locale.US, "x %+.3f  y %+.3f", x, y), cx,
                cy + r + mSmall.getTextSize() * 1.4f, mSmall);
        mSmall.setColor(DIM);
        canvas.drawText(String.format(Locale.US, "x %+.2f..%+.2f  y %+.2f..%+.2f",
                mMin[axis], mMax[axis], mMin[axis + 1], mMax[axis + 1]), cx,
                cy + r + mSmall.getTextSize() * 2.6f, mSmall);
    }

    private void bottom(Canvas canvas, float w, float h) {
        final float line = mMono.getTextSize() * 1.25f;
        final float x = w * 0.02f;
        float y = h * 0.8f;

        // Last key events, newest first
        if (mEvents.isEmpty()) {
            mMono.setColor(DIM);
            canvas.drawText(getContext().getString(R.string.test_no_key), x, y, mMono);
            y += line * MAX_EVENTS;
        } else {
            boolean newest = true;
            for (String event : mEvents) {
                mMono.setColor(newest ? TEXT : DIM);
                canvas.drawText(event, x, y, mMono);
                newest = false;
                y += line;
            }
            y += line * (MAX_EVENTS - mEvents.size());
        }

        // Battery, then the chargers
        if (mBattery != null) {
            mMono.setColor(mBattery.charging ? TESTED : TEXT);
            canvas.drawText(mBattery.battery, x, y, mMono);
            y += line;
            mMono.setColor(DIM);
            canvas.drawText(mBattery.others, x, y, mMono);
        }

        // Hint and progress
        int tested = 0;
        for (int code : BUTTONS) {
            if (mTested.contains(code)) {
                tested++;
            }
        }
        mText.setColor(tested == BUTTONS.length ? TESTED : DIM);
        final String progress = getContext().getString(R.string.test_hint, tested,
                BUTTONS.length);
        final String hint = mExitProgress > 0
                ? getContext().getString(R.string.test_leaving)
                : tested == BUTTONS.length
                        ? getContext().getString(R.string.test_all_tested) + "  \u00b7  "
                                + progress
                        : progress;
        canvas.drawText(hint, w / 2, h - mText.getTextSize() * 0.5f, mText);
    }
}
