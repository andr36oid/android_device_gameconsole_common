package org.andr36oid.emmc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * The last confirmation: a ring that fills while A (or the screen) is held, with the
 * seconds left in the middle. Letting go before it is full empties it again.
 */
final class HoldButton extends View {
    static final long HOLD_MS = 10_000;

    interface Listener {
        void onHeld();
    }

    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mOval = new RectF();
    private final String mLabel;
    private Listener mListener;
    /** When the hold began, 0 when not held. */
    private long mDownAt;
    private boolean mDone;

    HoldButton(Context context, String label) {
        super(context);
        mLabel = label;
        final float dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                getResources().getDisplayMetrics());
        mTrack.setStyle(Paint.Style.STROKE);
        mTrack.setStrokeWidth(8 * dp);
        mTrack.setColor(0x44FFFFFF);
        mFill.setStyle(Paint.Style.STROKE);
        mFill.setStrokeWidth(8 * dp);
        mFill.setStrokeCap(Paint.Cap.ROUND);
        mFill.setColor(0xFFE53935);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setColor(0xFFFFFFFF);
        mText.setTextSize(14 * dp);
        setFocusable(true);
        setClickable(true);
        setMinimumHeight((int) (120 * dp));
        setContentDescription(label);
    }

    void setListener(Listener l) {
        mListener = l;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) release();
        invalidate();
    }

    private static boolean holdKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_SPACE;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!holdKey(keyCode)) return super.onKeyDown(keyCode, event);
        if (event.getRepeatCount() == 0) press();
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!holdKey(keyCode)) return super.onKeyUp(keyCode, event);
        release();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                press();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
        }
        return true;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect r) {
        super.onFocusChanged(gainFocus, direction, r);
        if (!gainFocus) release();
        invalidate();
    }

    private void press() {
        if (!isEnabled() || mDone) return;
        mDownAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private void release() {
        if (mDone) return;
        mDownAt = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        final float stroke = mTrack.getStrokeWidth();
        final float size = Math.min(getWidth(), getHeight()) - 2 * stroke;
        final float cx = getWidth() / 2f, cy = getHeight() / 2f;
        mOval.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        mTrack.setColor(isFocused() ? 0x88FFFFFF : 0x44FFFFFF);
        c.drawOval(mOval, mTrack);
        long held = mDone ? HOLD_MS : mDownAt == 0 ? 0 : SystemClock.uptimeMillis() - mDownAt;
        if (held >= HOLD_MS && !mDone) {
            mDone = true;
            held = HOLD_MS;
            if (mListener != null) post(mListener::onHeld);
        }
        if (held > 0) c.drawArc(mOval, -90, 360f * held / HOLD_MS, false, mFill);
        final String middle = !isEnabled() ? "—"
                : held > 0 ? String.valueOf((HOLD_MS - held + 999) / 1000) : mLabel;
        final Paint.FontMetrics fm = mText.getFontMetrics();
        c.drawText(middle, cx, cy - (fm.ascent + fm.descent) / 2, mText);
        if (mDownAt != 0 && !mDone) postInvalidateOnAnimation();
    }
}
