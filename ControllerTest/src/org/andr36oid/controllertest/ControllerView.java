package org.andr36oid.controllertest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.andr36oid.consoleart.CanvasPainter;
import org.andr36oid.consoleart.ConsoleDrawing;
import org.andr36oid.consoleart.Palette;
import org.andr36oid.consoleart.R36s;
import org.andr36oid.consoleart.StickDrawing;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Draws the R36S (the shared drawing) with its controls lit while pressed and outlined green
 * once they worked; next to it the two sticks with their dead zone, the last key events and
 * the battery.
 */
class ControllerView extends View {

    private static final int TESTED = 0xFF2EA043;

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

    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMono = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final CanvasPainter mPainter = new CanvasPainter();
    private final ConsoleDrawing mConsole = new ConsoleDrawing();
    private final StickDrawing mStick = new StickDrawing();
    private final Palette mPalette;
    private final ConsoleDrawing.Styler mStyler = this::style;

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
        mPalette = new Palette(context);
        mText.setTextAlign(Paint.Align.CENTER);
        mMono.setTypeface(Typeface.MONOSPACE);
        mArc.setStyle(Paint.Style.STROKE);
        mArc.setStrokeCap(Paint.Cap.ROUND);
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
        mEvents.addFirst(String.format(Locale.US, "%-13s %-4s key %3d scan %3d  %s",
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

    // Drawing, laid out in fractions of a 4:3 box (the R36S screen is 640x480). Other screen
    // shapes (720x720) get the largest 4:3 box, centred. The console on the left, the rest on
    // the right, the hint along the bottom.

    @Override
    protected void onDraw(Canvas canvas) {
        final ConsoleDrawing.Colors c = mPalette.console;
        canvas.drawColor(c.background);
        final float w = Math.min(getWidth(), getHeight() * 4f / 3f);
        final float h = w * 3f / 4f;
        final float ox = (getWidth() - w) / 2f;
        final float oy = (getHeight() - h) / 2f;

        mPainter.on(canvas).fit(R36s.VIEWPORT, ox, oy, w * 0.45f, h * 0.92f);
        mConsole.draw(mPainter, c, mStyler);
        consoleScreen(canvas, mPainter.map(R36s.SCREEN, mRect), h);

        final float left = ox + w * 0.47f;
        final float width = ox + w - left - w * 0.02f;
        mText.setTextSize(h * 0.034f);
        mMono.setTextSize(h * 0.027f);
        sticks(canvas, left, width, oy, h);
        log(canvas, left, width, oy + h * 0.66f, h);

        // Hint and progress along the bottom
        int tested = 0;
        for (int code : BUTTONS) {
            if (mTested.contains(code)) {
                tested++;
            }
        }
        final String progress = getContext().getString(R.string.test_hint, tested,
                BUTTONS.length);
        final String hint = mExitProgress > 0
                ? getContext().getString(R.string.test_leaving)
                : tested == BUTTONS.length
                        ? getContext().getString(R.string.test_all_tested) + "  \u00b7  "
                                + progress
                        : progress;
        mText.setColor(tested == BUTTONS.length ? TESTED : c.caption);
        mText.setTextAlign(Paint.Align.CENTER);
        fitted(canvas, hint, ox + w / 2, oy + h - h * 0.025f, w * 0.96f, mText);
    }

    /** The title on the console's screen, and a ring filling up while leaving. */
    private void consoleScreen(Canvas canvas, RectF screen, float h) {
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(h * 0.034f);
        mText.setColor(0xFFE8EAED);
        fitted(canvas, getContext().getString(R.string.test_title), screen.centerX(),
                screen.top + screen.height() * 0.25f, screen.width() * 0.9f, mText);
        if (mExitProgress > 0) {
            final float r = screen.height() * 0.2f;
            mArc.setStrokeWidth(h * 0.008f);
            mArc.setColor(mPalette.console.accent);
            mRect.set(screen.centerX() - r, screen.centerY() + r * 0.3f - r,
                    screen.centerX() + r, screen.centerY() + r * 0.3f + r);
            canvas.drawArc(mRect, -90, 360 * mExitProgress, false, mArc);
        }
    }

    private void sticks(Canvas canvas, float left, float width, float top, float h) {
        final float r = Math.min(h * 0.13f, width * 0.19f);
        final float cy = top + h * 0.08f + r + mText.getTextSize();
        mPainter.identity();
        mStick.labelSize = 0;
        if (!mHasSticks) {
            mText.setTextAlign(Paint.Align.CENTER);
            mText.setColor(mPalette.console.caption);
            canvas.drawText(getContext().getString(R.string.test_no_stick), left + width / 2,
                    cy, mText);
            return;
        }
        stick(canvas, left + width * 0.25f, cy, r, 0, mFlatLeft, "L3",
                KeyEvent.KEYCODE_BUTTON_THUMBL, width / 2);
        stick(canvas, left + width * 0.75f, cy, r, 2, mFlatRight, "R3",
                KeyEvent.KEYCODE_BUTTON_THUMBR, width / 2);
    }

    private void stick(Canvas canvas, float cx, float cy, float r, int axis, float flat,
            String label, int code, float room) {
        final boolean clicked = mDown.contains(code);
        final float x = mAxes[axis];
        final float y = mAxes[axis + 1];
        mStick.draw(mPainter, mPalette.stick, cx, cy, r, x, y, clicked, flat, null, 0, 0,
                null);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setColor(clicked ? mPalette.console.accent : mPalette.console.caption);
        fitted(canvas, flat > 0 ? String.format(Locale.US, "%s \u00b7 dz %.2f", label, flat)
                : label, cx, cy - r - mText.getTextSize() * 0.6f, room * 0.95f, mText);
        mMono.setTextAlign(Paint.Align.CENTER);
        mMono.setColor(mPalette.console.label);
        fitted(canvas, String.format(Locale.US, "x %+.3f  y %+.3f", x, y), cx,
                cy + r + mMono.getTextSize() * 1.5f, room * 0.95f, mMono);
        mMono.setColor(mPalette.console.caption);
        fitted(canvas, String.format(Locale.US, "x %+.2f..%+.2f  y %+.2f..%+.2f",
                mMin[axis], mMax[axis], mMin[axis + 1], mMax[axis + 1]), cx,
                cy + r + mMono.getTextSize() * 2.8f, room * 0.95f, mMono);
    }

    /** The last key events, newest first, then the battery and the chargers. */
    private void log(Canvas canvas, float left, float width, float top, float h) {
        final ConsoleDrawing.Colors c = mPalette.console;
        final float line = mMono.getTextSize() * 1.35f;
        float y = top;
        mMono.setTextAlign(Paint.Align.LEFT);
        if (mEvents.isEmpty()) {
            mMono.setColor(c.caption);
            fitted(canvas, getContext().getString(R.string.test_no_key), left, y, width, mMono);
            y += line * MAX_EVENTS;
        } else {
            boolean newest = true;
            for (String event : mEvents) {
                mMono.setColor(newest ? c.label : c.caption);
                fitted(canvas, event, left, y, width, mMono);
                newest = false;
                y += line;
            }
            y += line * (MAX_EVENTS - mEvents.size());
        }
        if (mBattery != null) {
            y += line * 0.5f;
            mMono.setColor(mBattery.charging ? TESTED : c.label);
            fitted(canvas, mBattery.battery, left, y, width, mMono);
            y += line;
            mMono.setColor(c.caption);
            fitted(canvas, mBattery.others, left, y, width, mMono);
        }
    }

    /** Draws a line of text, smaller if it is wider than {@code max}. */
    private static void fitted(Canvas canvas, String text, float x, float y, float max,
            Paint paint) {
        final float size = paint.getTextSize();
        final float textWidth = paint.measureText(text);
        if (textWidth > max && textWidth > 0) {
            paint.setTextSize(size * max / textWidth);
        }
        canvas.drawText(text, x, y, paint);
        paint.setTextSize(size);
    }

    /** Pressed in the accent colour, green once it has worked; sticks tilt with the axes. */
    private void style(R36s.Control control, ConsoleDrawing.Look look) {
        final int code = keyCode(control);
        if (code == 0) {
            return;
        }
        if (mDown.contains(code)) {
            look.fill = mPalette.console.accent;
            look.outline = mPalette.console.accent;
            look.content = mPalette.console.onAccent;
            look.caption = mPalette.console.accent;
        } else if (mTested.contains(code)) {
            look.outline = TESTED;
        }
        if (control == R36s.Control.L3) {
            look.stickX = mAxes[0];
            look.stickY = mAxes[1];
        } else if (control == R36s.Control.R3) {
            look.stickX = mAxes[2];
            look.stickY = mAxes[3];
        }
    }

    /** The key a control sends; 0 for FN and power, which never reach an app. */
    private static int keyCode(R36s.Control control) {
        switch (control) {
            case DPAD_UP: return KeyEvent.KEYCODE_DPAD_UP;
            case DPAD_DOWN: return KeyEvent.KEYCODE_DPAD_DOWN;
            case DPAD_LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
            case DPAD_RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case A: return KeyEvent.KEYCODE_BUTTON_A;
            case B: return KeyEvent.KEYCODE_BUTTON_B;
            case X: return KeyEvent.KEYCODE_BUTTON_X;
            case Y: return KeyEvent.KEYCODE_BUTTON_Y;
            case L1: return KeyEvent.KEYCODE_BUTTON_L1;
            case L2: return KeyEvent.KEYCODE_BUTTON_L2;
            case R1: return KeyEvent.KEYCODE_BUTTON_R1;
            case R2: return KeyEvent.KEYCODE_BUTTON_R2;
            case L3: return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case R3: return KeyEvent.KEYCODE_BUTTON_THUMBR;
            case SELECT: return KeyEvent.KEYCODE_BUTTON_SELECT;
            case START: return KeyEvent.KEYCODE_BUTTON_START;
            case VOLUME_DOWN: return KeyEvent.KEYCODE_VOLUME_DOWN;
            case VOLUME_UP: return KeyEvent.KEYCODE_VOLUME_UP;
            default: return 0;
        }
    }
}
