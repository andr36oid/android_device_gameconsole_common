package org.andr36oid.buttonmapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.andr36oid.consoleart.CanvasPainter;
import org.andr36oid.consoleart.ConsoleDrawing;
import org.andr36oid.consoleart.Palette;
import org.andr36oid.consoleart.R36s;

import java.util.Arrays;

/**
 * Drawing of the console (the shared R36S drawing) with all of its buttons, one of them
 * selected. The D-pad moves the selection and pressing a button jumps to it. OK (the A button), or pressing the selected
 * button again, activates it. The console's screen shows a hint.
 */
public class ControllerView extends View {

    interface Listener {
        void onButtonSelected(int index);

        void onButtonActivated(int index);
    }

    private static final float HINT_SIZE = 7f;
    private static final float MIN_HINT_SIZE = 4.5f;
    private static final float HINT_PADDING = 5f;
    private static final float TOUCH_SLOP = 3f;
    // Highest ratio of sideways to forward distance the D-pad still moves to.
    private static final float MOVE_CONE = 1.5f;

    private static final int SCREEN_TEXT_COLOR = 0xffe8eaed;
    private static final float ABSENT_ALPHA = 0.3f;
    private static final float UNFOCUSED_SELECTION_ALPHA = 0.4f;

    private final TextPaint mHintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final CanvasPainter mPainter = new CanvasPainter();
    private final ConsoleDrawing mDrawing = new ConsoleDrawing();
    private final ConsoleDrawing.Colors mColors;
    private final ConsoleDrawing.Styler mStyler = this::style;
    private final int[] mControlIndex = new int[R36s.Control.values().length];

    private final boolean[] mPresent = new boolean[HardwareButton.ALL.length];
    private final boolean[] mChanged = new boolean[HardwareButton.ALL.length];
    private int mSelection;
    private CharSequence mHint = "";
    private Listener mListener;

    // Keys pressed on this view, handled once they are released.
    private boolean mActivatePending;
    private int mPickPending = -1;

    public ControllerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        Arrays.fill(mPresent, true);

        mColors = new Palette(context).console;
        Arrays.fill(mControlIndex, -1);
        for (int i = 0; i < HardwareButton.ALL.length; i++) {
            mControlIndex[HardwareButton.ALL[i].control.ordinal()] = i;
        }

        mHintPaint.setColor(SCREEN_TEXT_COLOR);
        setFocusable(true);
    }

    void setListener(Listener listener) {
        mListener = listener;
    }

    /** Sets which buttons the console has, the others are greyed out and can't be selected. */
    void setPresent(boolean[] present) {
        System.arraycopy(present, 0, mPresent, 0, mPresent.length);
        if (!mPresent[mSelection]) {
            for (int i = 0; i < mPresent.length; i++) {
                if (mPresent[i]) {
                    mSelection = i;
                    break;
                }
            }
        }
        invalidate();
    }

    /** Sets which buttons don't do what they do by default, they get a badge. */
    void setChanged(boolean[] changed) {
        if (!Arrays.equals(mChanged, changed)) {
            System.arraycopy(changed, 0, mChanged, 0, mChanged.length);
            invalidate();
        }
    }

    void setHint(CharSequence hint) {
        if (!TextUtils.equals(mHint, hint)) {
            mHint = hint;
            invalidate();
        }
    }

    int getSelection() {
        return mSelection;
    }

    void setSelection(int index) {
        if (index < 0 || index >= mPresent.length || !mPresent[index] || index == mSelection) {
            return;
        }
        mSelection = index;
        invalidate();
        if (mListener != null) {
            mListener.onButtonSelected(index);
        }
    }

    /**
     * Returns the button a key press picks, or -1 for keys that move around, confirm or go back
     * and for keys that don't come from a built-in button.
     */
    int findPickedButton(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
                return -1;
        }
        if (isActivateKey(keyCode)) {
            return -1;
        }
        final InputDevice device = event.getDevice();
        if (device == null || device.isVirtual() || device.isExternal()) {
            return -1;
        }
        final int index = HardwareButton.indexOf(event.getScanCode());
        return index >= 0 && mPresent[index] ? index : -1;
    }

    private static boolean isActivateKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return true;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return moveSelection(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return moveSelection(0, 1);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return moveSelection(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return moveSelection(1, 0);
        }
        if (isActivateKey(keyCode)) {
            if (event.getRepeatCount() == 0) {
                mActivatePending = true;
            }
            return true;
        }
        final int index = findPickedButton(keyCode, event);
        if (index >= 0) {
            if (event.getRepeatCount() == 0) {
                mPickPending = index;
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isActivateKey(keyCode)) {
            if (mActivatePending) {
                mActivatePending = false;
                activate(mSelection);
            }
            return true;
        }
        final int index = findPickedButton(keyCode, event);
        if (index >= 0) {
            if (mPickPending == index) {
                mPickPending = -1;
                if (index == mSelection) {
                    activate(index);
                } else {
                    setSelection(index);
                }
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        mActivatePending = false;
        mPickPending = -1;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            final int index = findButtonAt(event.getX(), event.getY());
            if (index >= 0) {
                setSelection(index);
                activate(index);
            }
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void activate(int index) {
        if (mListener != null) {
            mListener.onButtonActivated(index);
        }
    }

    /** Selects the closest button in a direction, returns false if there is none. */
    private boolean moveSelection(int directionX, int directionY) {
        final RectF from = HardwareButton.ALL[mSelection].bounds;
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < HardwareButton.ALL.length; i++) {
            if (i == mSelection || !mPresent[i]) {
                continue;
            }
            final RectF to = HardwareButton.ALL[i].bounds;
            final float offsetX = to.centerX() - from.centerX();
            final float offsetY = to.centerY() - from.centerY();
            final float along = offsetX * directionX + offsetY * directionY;
            final float across = Math.abs(offsetX * directionY) + Math.abs(offsetY * directionX);
            if (along <= 1 || across > along * MOVE_CONE) {
                continue;
            }
            final float score = along + 2 * across;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) {
            // Let the focus move on to the rest of the screen.
            return false;
        }
        setSelection(best);
        return true;
    }

    private int findButtonAt(float x, float y) {
        if (mPainter.scale() <= 0) {
            return -1;
        }
        final float unitX = mPainter.unitsX(x);
        final float unitY = mPainter.unitsY(y);
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < HardwareButton.ALL.length; i++) {
            final RectF bounds = HardwareButton.ALL[i].bounds;
            if (!mPresent[i]
                    || unitX < bounds.left - TOUCH_SLOP || unitX > bounds.right + TOUCH_SLOP
                    || unitY < bounds.top - TOUCH_SLOP || unitY > bounds.bottom + TOUCH_SLOP) {
                continue;
            }
            final float distance = Math.abs(unitX - bounds.centerX())
                    + Math.abs(unitY - bounds.centerY());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float width = getWidth() - getPaddingLeft() - getPaddingRight();
        final float height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (mPainter.on(canvas).fit(R36s.VIEWPORT, getPaddingLeft(), getPaddingTop(), width,
                height) <= 0) {
            return;
        }
        mDrawing.draw(mPainter, mColors, mStyler);
        drawHint(canvas);
    }

    /** The selection in the accent colour, missing buttons faded, changed ones badged. */
    private void style(R36s.Control control, ConsoleDrawing.Look look) {
        final int index = mControlIndex[control.ordinal()];
        if (index < 0) {
            return;
        }
        if (index == mSelection) {
            final int accent = isFocused() ? mColors.accent
                    : Palette.alpha(mColors.accent, UNFOCUSED_SELECTION_ALPHA);
            look.fill = accent;
            look.outline = mColors.accent;
            look.content = isFocused() ? mColors.onAccent : mColors.label;
            look.caption = mColors.accent;
            look.focus = isFocused();
        }
        if (!mPresent[index]) {
            look.fill = Palette.alpha(look.fill, ABSENT_ALPHA);
            look.outline = Palette.alpha(look.outline, ABSENT_ALPHA);
            look.content = Palette.alpha(look.content, ABSENT_ALPHA);
            look.caption = Palette.alpha(look.caption, ABSENT_ALPHA);
        }
        look.badge = mChanged[index];
    }

    private void drawHint(Canvas canvas) {
        if (TextUtils.isEmpty(mHint)) {
            return;
        }
        final RectF screen = mPainter.map(R36s.SCREEN, mRect);
        final float padding = mPainter.px(HINT_PADDING);
        final int width = (int) (screen.width() - 2 * padding);
        final float maxHeight = screen.height() - 2 * padding;
        if (width <= 0 || maxHeight <= 0) {
            return;
        }
        // Shrink the text until it fits the screen.
        StaticLayout layout = buildHint(HINT_SIZE, width);
        for (float size = HINT_SIZE - 0.5f; layout.getHeight() > maxHeight
                && size >= MIN_HINT_SIZE; size -= 0.5f) {
            layout = buildHint(size, width);
        }
        canvas.save();
        canvas.clipRect(screen);
        canvas.translate(screen.left + padding, screen.centerY() - layout.getHeight() / 2f);
        layout.draw(canvas);
        canvas.restore();
    }

    private StaticLayout buildHint(float size, int width) {
        mHintPaint.setTextSize(mPainter.px(size));
        return StaticLayout.Builder.obtain(mHint, 0, mHint.length(), mHintPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build();
    }
}
