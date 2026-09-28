package org.andr36oid.guide;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Two circles with a dot each that follow the left and right stick, in the theme's colors. */
public class StickView extends View {

    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] mAxes = new float[4];

    public StickView(Context context) {
        super(context);
        final TypedArray a = context.obtainStyledAttributes(new int[] {
                android.R.attr.textColorSecondary, android.R.attr.colorAccent});
        final int ring = a.getColor(0, 0xFF888888);
        final int dot = a.getColor(1, 0xFF80CBC4);
        a.recycle();
        final float density = getResources().getDisplayMetrics().density;
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(2 * density);
        mRing.setColor(ring);
        mDot.setColor(dot);
        mLabel.setColor(ring);
        mLabel.setTextAlign(Paint.Align.CENTER);
        mLabel.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
    }

    /** Left x, left y, right x, right y, each from -1 to 1. */
    void setAxes(float lx, float ly, float rx, float ry) {
        mAxes[0] = lx;
        mAxes[1] = ly;
        mAxes[2] = rx;
        mAxes[3] = ry;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float labelHeight = mLabel.getTextSize() * 1.6f;
        final float w = getWidth() / 2f;
        final float h = getHeight() - labelHeight;
        final float radius = Math.max(0, Math.min(w, h) / 2f - mRing.getStrokeWidth() * 4);
        if (radius <= 0) {
            return;
        }
        final float dot = Math.max(4, radius / 5f);
        for (int stick = 0; stick < 2; stick++) {
            final float cx = w * stick + w / 2f;
            final float cy = h / 2f;
            canvas.drawCircle(cx, cy, radius, mRing);
            canvas.drawCircle(cx + clamp(mAxes[stick * 2]) * (radius - dot),
                    cy + clamp(mAxes[stick * 2 + 1]) * (radius - dot), dot, mDot);
            canvas.drawText(stick == 0 ? "L" : "R", cx, getHeight() - mLabel.getTextSize() / 2,
                    mLabel);
        }
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }
}
