package org.andr36oid.subsystemsetup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** EmmcTool's hold ring, with theme colours and a three-second confirmation. */
final class HoldButton extends View {
    private static final long HOLD_MS = 3000;
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFocusRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mOval = new RectF();
    private final RectF mFocusOval = new RectF();
    private final String mLabel;
    private final Runnable mAction;
    private final Runnable mCommit;
    private long mDownAt;
    private boolean mDone;

    HoldButton(Context context, Runnable action) {
        super(context);
        mAction = action;
        mCommit = () -> {
            if (isShown() && isEnabled() && hasWindowFocus()) mAction.run();
        };
        mLabel = context.getString(R.string.subsystem_hold);
        final float dp = getResources().getDisplayMetrics().density;
        mTrack.setStyle(Paint.Style.STROKE);
        mTrack.setStrokeWidth(8 * dp);
        mFill.setStyle(Paint.Style.STROKE);
        mFill.setStrokeWidth(8 * dp);
        mFill.setStrokeCap(Paint.Cap.ROUND);
        mFill.setColor(getResources().getColor(R.color.andr36oid_setup_accent, context.getTheme()));
        mFocusRing.setStyle(Paint.Style.STROKE);
        mFocusRing.setStrokeWidth(3 * dp);
        mFocusRing.setColor(getResources().getColor(R.color.andr36oid_setup_accent, context.getTheme()));
        final TypedValue textColor = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, textColor, true);
        mText.setColor(textColor.resourceId != 0
                ? context.getColorStateList(textColor.resourceId).getDefaultColor() : textColor.data);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(16 * dp);
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setMinimumHeight(Math.round(132 * dp));
        // The ring draws its own circular focus treatment in onDraw; the shared
        // rectangular setup-focus box looks wrong wrapped around a round control.
        setContentDescription(context.getString(R.string.subsystem_hold_hint));
    }

    void cancelHold() {
        removeCallbacks(mCommit);
        mDownAt = 0;
        mDone = false;
        invalidate();
    }

    private static boolean holdKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_SPACE;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        if (!holdKey(code)) return super.onKeyDown(code, event);
        if (event.getRepeatCount() == 0) press();
        return true;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent event) {
        if (!holdKey(code)) return super.onKeyUp(code, event);
        if (!mDone) cancelHold();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                press();
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getX() < 0 || event.getY() < 0 || event.getX() > getWidth()
                        || event.getY() > getHeight()) cancelHold();
                break;
            case MotionEvent.ACTION_UP:
                if (!mDone) cancelHold();
                performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelHold();
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        // A tap announces the control; it cannot bypass the hold.
        super.performClick();
        return true;
    }

    @Override
    public boolean performLongClick() {
        // Accessibility services invoke the explicitly labelled long-click action.
        if (!isEnabled() || mDone) return false;
        mDone = true;
        post(mCommit);
        return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.Button.class.getName());
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) cancelHold();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (!focused) cancelHold();
        invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancelHold();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelHold();
        super.onDetachedFromWindow();
    }

    private void press() {
        if (!isEnabled() || mDone || mDownAt != 0) return;
        mDownAt = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float stroke = mTrack.getStrokeWidth();
        final float size = Math.min(getWidth(), getHeight()) - 2 * stroke;
        final float x = getWidth() / 2f, y = getHeight() / 2f;
        mOval.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
        mTrack.setColor(mText.getColor());
        mTrack.setAlpha(isFocused() ? 150 : 40);
        canvas.drawOval(mOval, mTrack);
        if (isFocused()) {
            final float pad = stroke * 0.9f;
            mFocusOval.set(mOval.left - pad, mOval.top - pad, mOval.right + pad, mOval.bottom + pad);
            canvas.drawOval(mFocusOval, mFocusRing);
        }
        long held = mDone ? HOLD_MS : mDownAt == 0 ? 0 : SystemClock.uptimeMillis() - mDownAt;
        if (held >= HOLD_MS && !mDone) {
            mDone = true;
            held = HOLD_MS;
            post(mCommit);
        }
        if (held > 0) canvas.drawArc(mOval, -90, 360f * held / HOLD_MS, false, mFill);
        final String text = held > 0 ? getContext().getString(R.string.subsystem_hold_seconds,
                (HOLD_MS - held + 999) / 1000) : mLabel;
        final Paint.FontMetrics metrics = mText.getFontMetrics();
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2, mText);
        if (mDownAt != 0 && !mDone) postInvalidateOnAnimation();
    }
}
