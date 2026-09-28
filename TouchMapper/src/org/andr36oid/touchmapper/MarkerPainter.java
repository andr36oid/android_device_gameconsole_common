package org.andr36oid.touchmapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * Draws the controls of a profile: in the editor, and as hints on top of the game. Colours are
 * white on a dark shadow so they read on any game picture; the editor adds the theme accent for
 * the selected control.
 */
final class MarkerPainter {

    private final float mDp;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect mDash;
    private final Path mPath = new Path();
    private int mAlpha = 255;

    MarkerPainter(Context context) {
        mDp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                context.getResources().getDisplayMetrics());
        mFill.setStyle(Paint.Style.FILL);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeCap(Paint.Cap.ROUND);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mText.setShadowLayer(2 * mDp, 0, 0, 0xFF000000);
        mDash = new DashPathEffect(new float[] {6 * mDp, 4 * mDp}, 0);
    }

    /** 255 in the editor, less on top of a game. */
    void setAlpha(int alpha) {
        mAlpha = alpha;
    }

    float dp(float v) {
        return v * mDp;
    }

    static float centerX(Profile.Control c, int width) {
        return c.x * Math.max(width - 1, 1);
    }

    static float centerY(Profile.Control c, int height) {
        return c.y * Math.max(height - 1, 1);
    }

    /** Radius of the marker on screen: the stick reach, or the tap size. */
    static float radiusPx(Profile.Control c, int width, int height) {
        return c.radius * Math.min(width, height);
    }

    /**
     * @param shiftLabel prefix for controls of the shift layer, e.g. "L2+", or null
     * @param accent colour of the outline, 0 for white
     * @param dim draw faintly (the other layer in the editor)
     */
    void draw(Canvas canvas, Profile.Control c, int width, int height, String shiftLabel,
            int accent, boolean dim) {
        final float cx = centerX(c, width);
        final float cy = centerY(c, height);
        final float r = radiusPx(c, width, height);
        final int alpha = dim ? mAlpha / 3 : mAlpha;
        final int line = accent != 0 ? accent : 0xFFFFFFFF;
        final boolean layer1 = c.layer == 1;

        mStroke.setPathEffect(layer1 ? mDash : null);
        mStroke.setStrokeWidth(dp(accent != 0 ? 3 : 2));
        mStroke.setColor(withAlpha(line, alpha));
        mFill.setColor(withAlpha(0x66000000, alpha));

        String label = Inputs.label(c.input());
        if (layer1 && shiftLabel != null) {
            label = shiftLabel + label;
        }
        final float knob = dp(16);

        switch (c.type) {
            case Profile.JOYSTICK:
                // The reach of the stick, and the knob in the middle
                mFill.setColor(withAlpha(0x33000000, alpha));
                canvas.drawCircle(cx, cy, r, mFill);
                canvas.drawCircle(cx, cy, r, mStroke);
                mFill.setColor(withAlpha(0x88000000, alpha));
                canvas.drawCircle(cx, cy, knob, mFill);
                canvas.drawCircle(cx, cy, knob, mStroke);
                drawLabel(canvas, label, cx, cy, knob, alpha);
                break;
            case Profile.CAMERA:
                // The area the finger drags in, with four arrows
                mFill.setColor(withAlpha(0x22000000, alpha));
                canvas.drawCircle(cx, cy, r, mFill);
                mStroke.setPathEffect(mDash);
                canvas.drawCircle(cx, cy, r, mStroke);
                mStroke.setPathEffect(null);
                for (int i = 0; i < 4; i++) {
                    final double a = Math.PI / 2 * i;
                    arrow(canvas, cx, cy, (float) Math.cos(a), (float) Math.sin(a),
                            knob + dp(4), knob + dp(12));
                }
                mFill.setColor(withAlpha(0x88000000, alpha));
                canvas.drawCircle(cx, cy, knob, mFill);
                canvas.drawCircle(cx, cy, knob, mStroke);
                drawLabel(canvas, label, cx, cy, knob, alpha);
                break;
            case Profile.SWIPE: {
                final double a = Math.toRadians(c.angle);
                final float len = c.length * Math.min(width, height);
                final float dx = (float) Math.cos(a);
                final float dy = (float) Math.sin(a);
                canvas.drawLine(cx + dx * knob, cy + dy * knob, cx + dx * len, cy + dy * len,
                        mStroke);
                arrow(canvas, cx, cy, dx, dy, len - dp(10), len);
                canvas.drawCircle(cx, cy, knob, mFill);
                canvas.drawCircle(cx, cy, knob, mStroke);
                drawLabel(canvas, label, cx, cy, knob, alpha);
                break;
            }
            case Profile.HOLD: {
                final float rr = Math.max(knob, r);
                canvas.drawCircle(cx, cy, rr, mFill);
                canvas.drawCircle(cx, cy, rr, mStroke);
                // Second ring: the finger stays down
                canvas.drawCircle(cx, cy, rr - dp(4), mStroke);
                drawLabel(canvas, label, cx, cy, rr, alpha);
                break;
            }
            default: {
                final float rr = Math.max(knob, r);
                canvas.drawCircle(cx, cy, rr, mFill);
                canvas.drawCircle(cx, cy, rr, mStroke);
                drawLabel(canvas, label, cx, cy, rr, alpha);
                break;
            }
        }
    }

    private void arrow(Canvas canvas, float cx, float cy, float dx, float dy, float from,
            float to) {
        final float head = dp(6);
        final float tipX = cx + dx * to;
        final float tipY = cy + dy * to;
        mPath.reset();
        mPath.moveTo(tipX - dx * head - dy * head, tipY - dy * head + dx * head);
        mPath.lineTo(tipX, tipY);
        mPath.lineTo(tipX - dx * head + dy * head, tipY - dy * head - dx * head);
        canvas.drawPath(mPath, mStroke);
        if (to - from > head) {
            canvas.drawLine(cx + dx * from, cy + dy * from, tipX, tipY, mStroke);
        }
    }

    private void drawLabel(Canvas canvas, String label, float cx, float cy, float r, int alpha) {
        float size = Math.min(dp(14), r * 1.1f);
        if (label.length() > 3) {
            size *= 0.75f;
        }
        mText.setTextSize(Math.max(dp(9), size));
        mText.setColor(withAlpha(0xFFFFFFFF, alpha));
        final Paint.FontMetrics fm = mText.getFontMetrics();
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2, mText);
    }

    static int withAlpha(int color, int alpha) {
        final int a = (color >>> 24) * alpha / 255;
        return (a << 24) | (color & 0xFFFFFF);
    }
}
