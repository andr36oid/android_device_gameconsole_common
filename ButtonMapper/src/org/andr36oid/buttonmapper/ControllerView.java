package org.andr36oid.buttonmapper;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

/**
 * Drawing of the console with all of its buttons, one of them selected. The D-pad moves the
 * selection and pressing a button jumps to it. OK (the A button), or pressing the selected
 * button again, activates it. The console's screen shows a hint.
 */
public class ControllerView extends View {

    interface Listener {
        void onButtonSelected(int index);

        void onButtonActivated(int index);
    }

    // Everything is laid out in these units, see HardwareButton#bounds.
    private static final RectF VIEWPORT = new RectF(4, -1, 236, 150);
    private static final RectF BODY = new RectF(8, 12, 232, 146);
    private static final float BODY_RADIUS = 30;
    private static final RectF SCREEN = new RectF(72, 26, 168, 98);
    private static final float SCREEN_RADIUS = 5;
    private static final RectF DPAD_VERTICAL = new RectF(33, 37, 47, 79);
    private static final RectF DPAD_HORIZONTAL = new RectF(19, 51, 61, 65);
    private static final float DPAD_RADIUS = 3;

    private static final float STROKE_WIDTH = 1.2f;
    private static final float FOCUS_WIDTH = 1.6f;
    private static final float FOCUS_GAP = 2.5f;
    private static final float BADGE_RADIUS = 3.2f;
    private static final float LABEL_SIZE = 7.5f;
    private static final float SMALL_LABEL_SIZE = 6.5f;
    private static final float ARROW_SIZE = 3f;
    private static final float POWER_ICON_RADIUS = 3.4f;
    private static final float HINT_SIZE = 7f;
    private static final float MIN_HINT_SIZE = 4.5f;
    private static final float HINT_PADDING = 6f;
    private static final float TOUCH_SLOP = 3f;
    // Highest ratio of sideways to forward distance the D-pad still moves to.
    private static final float MOVE_CONE = 1.5f;

    private static final int SCREEN_COLOR = 0xff202124;
    private static final int SCREEN_TEXT_COLOR = 0xffe8eaed;
    private static final float ABSENT_ALPHA = 0.3f;
    private static final float UNFOCUSED_SELECTION_ALPHA = 0.4f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface mMediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final TextPaint mHintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final Path mOtherPath = new Path();
    private final RectF mRect = new RectF();
    private final RectF mButtonRect = new RectF();

    private final int mBodyColor;
    private final int mButtonColor;
    private final int mOutlineColor;
    private final int mLabelColor;
    private final int mAccentColor;
    private final int mOnAccentColor;
    private final int mBackgroundColor;
    // Middle of the face buttons, their badges sit on the side facing away from it.
    private final float mFaceCenterX;
    private final float mFaceCenterY;

    private final boolean[] mPresent = new boolean[HardwareButton.ALL.length];
    private final boolean[] mChanged = new boolean[HardwareButton.ALL.length];
    private int mSelection;
    private CharSequence mHint = "";
    private Listener mListener;

    // Keys pressed on this view, handled once they are released.
    private boolean mActivatePending;
    private int mPickPending = -1;

    // Transform from layout units to pixels, set up by onDraw().
    private float mScale;
    private float mOffsetX;
    private float mOffsetY;

    public ControllerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        Arrays.fill(mPresent, true);

        final int textColor = resolveColor(context, android.R.attr.textColorPrimary);
        mLabelColor = textColor;
        mBodyColor = multiplyAlpha(textColor, 0.06f);
        mButtonColor = multiplyAlpha(textColor, 0.14f);
        mOutlineColor = multiplyAlpha(textColor, 0.45f);
        mAccentColor = resolveColor(context, android.R.attr.colorAccent);
        // Whichever of white and near black stands out more on the accent color.
        final float luminance = Color.luminance(mAccentColor);
        mOnAccentColor = luminance > 0.21f ? 0xff202124 : Color.WHITE;
        mBackgroundColor = resolveColor(context, android.R.attr.colorBackground);

        float sumX = 0;
        float sumY = 0;
        int count = 0;
        for (HardwareButton button : HardwareButton.ALL) {
            if (button.shape == HardwareButton.SHAPE_ROUND) {
                sumX += button.bounds.centerX();
                sumY += button.bounds.centerY();
                count++;
            }
        }
        mFaceCenterX = count > 0 ? sumX / count : 0;
        mFaceCenterY = count > 0 ? sumY / count : 0;

        mLabelPaint.setTextAlign(Paint.Align.CENTER);
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
        if (mScale <= 0) {
            return -1;
        }
        final float unitX = (x - mOffsetX) / mScale + VIEWPORT.left;
        final float unitY = (y - mOffsetY) / mScale + VIEWPORT.top;
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
        mScale = Math.min(width / VIEWPORT.width(), height / VIEWPORT.height());
        if (mScale <= 0) {
            return;
        }
        mOffsetX = getPaddingLeft() + (width - VIEWPORT.width() * mScale) / 2;
        mOffsetY = getPaddingTop() + (height - VIEWPORT.height() * mScale) / 2;

        mPath.reset();
        mPath.addRoundRect(map(BODY, mRect), px(BODY_RADIUS), px(BODY_RADIUS),
                Path.Direction.CW);
        fillAndStroke(canvas, mPath, mBodyColor, mOutlineColor);

        mPath.reset();
        mPath.addRoundRect(map(SCREEN, mRect), px(SCREEN_RADIUS), px(SCREEN_RADIUS),
                Path.Direction.CW);
        fillAndStroke(canvas, mPath, SCREEN_COLOR, mOutlineColor);
        drawHint(canvas);

        // The D-pad cross, its arms are drawn on top of it.
        mPath.reset();
        mPath.addRoundRect(map(DPAD_VERTICAL, mRect), px(DPAD_RADIUS), px(DPAD_RADIUS),
                Path.Direction.CW);
        mOtherPath.reset();
        mOtherPath.addRoundRect(map(DPAD_HORIZONTAL, mRect), px(DPAD_RADIUS), px(DPAD_RADIUS),
                Path.Direction.CW);
        mPath.op(mOtherPath, Path.Op.UNION);
        fillAndStroke(canvas, mPath, mButtonColor, mOutlineColor);

        for (int i = 0; i < HardwareButton.ALL.length; i++) {
            drawButton(canvas, i);
        }
    }

    private void drawButton(Canvas canvas, int index) {
        final HardwareButton button = HardwareButton.ALL[index];
        final boolean selected = index == mSelection;
        final float alpha = mPresent[index] ? 1f : ABSENT_ALPHA;
        final RectF bounds = map(button.bounds, mButtonRect);

        // D-pad arms are part of the cross, only the selected one is filled.
        if (button.shape != HardwareButton.SHAPE_DPAD || selected) {
            final int fill = !selected ? mButtonColor : isFocused() ? mAccentColor
                    : multiplyAlpha(mAccentColor, UNFOCUSED_SELECTION_ALPHA);
            fillAndStroke(canvas, shapePath(button, bounds, 0), multiplyAlpha(fill, alpha),
                    multiplyAlpha(selected ? mAccentColor : mOutlineColor, alpha));
        }
        if (selected && isFocused()) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(px(FOCUS_WIDTH));
            mPaint.setColor(mAccentColor);
            canvas.drawPath(shapePath(button, bounds, FOCUS_GAP), mPaint);
        }

        final boolean onFill = selected && isFocused() && !button.labelBelow;
        final int contentColor = multiplyAlpha(onFill ? mOnAccentColor
                : selected && button.labelBelow ? mAccentColor : mLabelColor, alpha);
        if (button.shape == HardwareButton.SHAPE_STICK) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(px(STROKE_WIDTH));
            mPaint.setColor(multiplyAlpha(onFill ? mOnAccentColor : mOutlineColor, alpha));
            canvas.drawCircle(bounds.centerX(), bounds.centerY(), bounds.width() * 0.32f, mPaint);
        }
        switch (button.icon) {
            case HardwareButton.ICON_UP:
            case HardwareButton.ICON_DOWN:
            case HardwareButton.ICON_LEFT:
            case HardwareButton.ICON_RIGHT:
                drawArrow(canvas, bounds, button.icon, contentColor);
                break;
            case HardwareButton.ICON_POWER:
                drawPowerIcon(canvas, bounds.centerX(), bounds.centerY(),
                        px(POWER_ICON_RADIUS), contentColor);
                break;
            default:
                drawLabel(canvas, button, bounds, contentColor);
                break;
        }

        if (mChanged[index]) {
            drawBadge(canvas, button, bounds);
        }
    }

    private Path shapePath(HardwareButton button, RectF bounds, float grow) {
        mRect.set(bounds);
        mRect.inset(-px(grow), -px(grow));
        mPath.reset();
        switch (button.shape) {
            case HardwareButton.SHAPE_ROUND:
            case HardwareButton.SHAPE_STICK:
                mPath.addCircle(mRect.centerX(), mRect.centerY(), mRect.width() / 2,
                        Path.Direction.CW);
                break;
            case HardwareButton.SHAPE_PILL:
                mPath.addRoundRect(mRect, mRect.height() / 2, mRect.height() / 2,
                        Path.Direction.CW);
                break;
            default:
                mPath.addRoundRect(mRect, px(DPAD_RADIUS + grow), px(DPAD_RADIUS + grow),
                        Path.Direction.CW);
                break;
        }
        return mPath;
    }

    private void drawLabel(Canvas canvas, HardwareButton button, RectF bounds, int color) {
        mLabelPaint.setColor(color);
        mLabelPaint.setTextSize(px(button.labelBelow ? SMALL_LABEL_SIZE : LABEL_SIZE));
        mLabelPaint.setTypeface(button.shape == HardwareButton.SHAPE_ROUND
                ? Typeface.DEFAULT_BOLD : mMediumTypeface);
        final Paint.FontMetrics metrics = mLabelPaint.getFontMetrics();
        final float baseline = button.labelBelow
                ? bounds.bottom + px(1.5f) - metrics.ascent
                : bounds.centerY() - (metrics.ascent + metrics.descent) / 2;
        canvas.drawText(button.label, bounds.centerX(), baseline, mLabelPaint);
    }

    private void drawArrow(Canvas canvas, RectF bounds, int direction, int color) {
        final float x = bounds.centerX();
        final float y = bounds.centerY();
        final float size = px(ARROW_SIZE);
        final float back = size * 0.6f;
        mPath.reset();
        switch (direction) {
            case HardwareButton.ICON_UP:
                mPath.moveTo(x, y - size);
                mPath.lineTo(x + size, y + back);
                mPath.lineTo(x - size, y + back);
                break;
            case HardwareButton.ICON_DOWN:
                mPath.moveTo(x, y + size);
                mPath.lineTo(x - size, y - back);
                mPath.lineTo(x + size, y - back);
                break;
            case HardwareButton.ICON_LEFT:
                mPath.moveTo(x - size, y);
                mPath.lineTo(x + back, y - size);
                mPath.lineTo(x + back, y + size);
                break;
            default:
                mPath.moveTo(x + size, y);
                mPath.lineTo(x - back, y + size);
                mPath.lineTo(x - back, y - size);
                break;
        }
        mPath.close();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(color);
        canvas.drawPath(mPath, mPaint);
    }

    private void drawPowerIcon(Canvas canvas, float x, float y, float radius, int color) {
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(px(STROKE_WIDTH));
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setColor(color);
        mRect.set(x - radius, y - radius, x + radius, y + radius);
        // Circle open at the top, with a line through the gap.
        canvas.drawArc(mRect, -60, 300, false, mPaint);
        canvas.drawLine(x, y - radius * 1.25f, x, y - radius * 0.15f, mPaint);
        mPaint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawBadge(Canvas canvas, HardwareButton button, RectF bounds) {
        float x = bounds.right;
        float y = bounds.top;
        if (button.shape == HardwareButton.SHAPE_ROUND) {
            // On the rim, facing away from the other face buttons.
            final float dx = button.bounds.centerX() - mFaceCenterX;
            final float dy = button.bounds.centerY() - mFaceCenterY;
            final float length = (float) Math.hypot(dx, dy);
            final float radius = bounds.width() / 2;
            x = bounds.centerX() + (length > 0 ? dx / length * radius : radius);
            y = bounds.centerY() + (length > 0 ? dy / length * radius : 0);
        } else if (button.shape == HardwareButton.SHAPE_STICK) {
            // On the rim, towards the top right.
            final float offset = bounds.width() / 2 * 0.7071f;
            x = bounds.centerX() + offset;
            y = bounds.centerY() - offset;
        } else if (button.icon == HardwareButton.ICON_DOWN) {
            y = bounds.bottom;
        } else if (button.icon == HardwareButton.ICON_LEFT) {
            x = bounds.left;
        }
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mAccentColor);
        canvas.drawCircle(x, y, px(BADGE_RADIUS), mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(px(1f));
        mPaint.setColor(mBackgroundColor);
        canvas.drawCircle(x, y, px(BADGE_RADIUS), mPaint);
    }

    private void drawHint(Canvas canvas) {
        if (TextUtils.isEmpty(mHint)) {
            return;
        }
        final RectF screen = map(SCREEN, mRect);
        final float padding = px(HINT_PADDING);
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
        mHintPaint.setTextSize(px(size));
        return StaticLayout.Builder.obtain(mHint, 0, mHint.length(), mHintPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build();
    }

    private void fillAndStroke(Canvas canvas, Path path, int fillColor, int strokeColor) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(fillColor);
        canvas.drawPath(path, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(px(STROKE_WIDTH));
        mPaint.setColor(strokeColor);
        canvas.drawPath(path, mPaint);
    }

    private float px(float units) {
        return units * mScale;
    }

    private RectF map(RectF units, RectF out) {
        out.set(mOffsetX + (units.left - VIEWPORT.left) * mScale,
                mOffsetY + (units.top - VIEWPORT.top) * mScale,
                mOffsetX + (units.right - VIEWPORT.left) * mScale,
                mOffsetY + (units.bottom - VIEWPORT.top) * mScale);
        return out;
    }

    private static int resolveColor(Context context, int attr) {
        final TypedArray array = context.obtainStyledAttributes(new int[] { attr });
        try {
            return array.getColor(0, Color.GRAY);
        } finally {
            array.recycle();
        }
    }

    private static int multiplyAlpha(int color, float factor) {
        return (color & 0x00ffffff) | (Math.round(Color.alpha(color) * factor) << 24);
    }
}
