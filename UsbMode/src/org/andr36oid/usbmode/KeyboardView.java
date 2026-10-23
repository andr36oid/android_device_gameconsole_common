package org.andr36oid.usbmode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

final class KeyboardView extends View {
    private final KeyboardModel mModel;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private String mStatus;
    private volatile int mLeds;
    private final int mBackground, mText, mSecondary, mAccent, mSurface, mHeld;

    KeyboardView(Context context, KeyboardModel model) {
        super(context);
        mModel = model;
        mBackground = context.getColor(R.color.andr36oid_setup_background);
        mAccent = context.getColor(R.color.andr36oid_setup_accent);
        mSurface = context.getColor(R.color.andr36oid_setup_focus_fill);
        mHeld = Color.argb(100, Color.red(mAccent), Color.green(mAccent), Color.blue(mAccent));
        android.content.res.TypedArray colors = context.obtainStyledAttributes(new int[] {
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
        mText = colors.getColor(0, Color.WHITE); mSecondary = colors.getColor(1, mText); colors.recycle();
        mStatus = context.getString(R.string.controller_preparing);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(context.getString(R.string.keyboard_legend));
    }
    void setStatus(String status) { mStatus = status; invalidate(); }
    void setLeds(int leds) { mLeds = leds; }

    private void text(Canvas canvas, String text, float x, float y, float size, int color) {
        mPaint.setColor(color); mPaint.setTextSize(size);
        mPaint.setTypeface(Typeface.DEFAULT);
        mPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(text, x, y, mPaint);
    }
    private int actionLabel(int action) {
        switch (action) {
            case KeyboardModel.MOUSE_LEFT: return R.string.keyboard_mouse_left;
            case KeyboardModel.MOUSE_RIGHT: return R.string.keyboard_mouse_right;
            case KeyboardModel.MOUSE_MIDDLE: return R.string.keyboard_mouse_middle;
            case KeyboardModel.WHEEL_UP: return R.string.keyboard_wheel_up;
            case KeyboardModel.CHANGE_DISC: return R.string.usb_disc_change;
            case KeyboardModel.TYPE_FILE: return R.string.keyboard_type_file;
            case KeyboardModel.WHEEL_DOWN: return R.string.keyboard_wheel_down;
            default: return R.string.usb_device_stop;
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(mBackground);
        canvas.save();
        // Work in screen pixels, independent of Android's density on small panels.
        final float scale = getWidth() / 640f;
        canvas.scale(scale, scale);
        final float height = getHeight() / scale;
        final float rowHeight = (height - 190) / mModel.rows.length;
        final String[] status = mStatus.split("\\n");
        text(canvas, status[0], 8, 19, 12, mText);
        if (status.length > 1) text(canvas, status[1], 8, 39, 12, mText);
        text(canvas, getContext().getString(R.string.keyboard_leds,
                (mLeds & 2) != 0 ? "●" : "○", (mLeds & 1) != 0 ? "●" : "○"),
                510, 59, 12, mAccent);
        text(canvas, getContext().getString(R.string.keyboard_exit), 8, 59, 12, mSecondary);
        synchronized (mModel) {
            final String[] states = {getContext().getString(R.string.keyboard_modifier_off),
                    getContext().getString(R.string.keyboard_modifier_next),
                    getContext().getString(R.string.keyboard_modifier_locked)};
            text(canvas, getContext().getString(R.string.keyboard_modifiers,
                    states[mModel.modifier(1)], states[mModel.modifier(0)]), 8, 82, 12, mAccent);
            for (int row = 0; row < mModel.rows.length; row++) {
                final KeyboardModel.Key[] keys = mModel.rows[row];
                float x = 6;
                for (int column = 0; column < keys.length; column++) {
                    final KeyboardModel.Key key = keys[column];
                    final float width = key.width / mModel.rowWidth(row) * 628;
                    final float top = 95 + row * rowHeight;
                    final boolean selected = row == mModel.row() && column == mModel.column();
                    final int modifier = key.usage >= 0xe0 ? mModel.modifier(key.usage - 0xe0) : 0;
                    final boolean mouse = key.action >= 1 && key.action <= 3
                            && (mModel.mouseLocked() & (1 << (key.action - 1))) != 0;
                    mBounds.set(x + 1, top, x + width - 1, top + rowHeight - 4);
                    mPaint.setColor(selected ? mAccent : modifier != 0 || mouse ? mHeld : mSurface);
                    canvas.drawRoundRect(mBounds, 4, 4, mPaint);
                    final String label = key.action == 0 ? mModel.label(key)
                            : getContext().getString(actionLabel(key.action));
                    mPaint.setTextSize(13);
                    final float size = Math.min(13, 13 * (width - 6) / Math.max(1, mPaint.measureText(label)));
                    mPaint.setTextSize(size);
                    text(canvas, label, x + (width - mPaint.measureText(label)) / 2,
                            top + rowHeight / 2 + 4, size, selected ? Color.BLACK : mText);
                    x += width;
                }
            }
        }
        final String[] legend = getContext().getString(R.string.keyboard_legend).split("\n");
        for (int i = 0; i < legend.length; i++) text(canvas, legend[i], 8, height - 75 + i * 20, 12, mSecondary);
        canvas.restore();
    }
}
