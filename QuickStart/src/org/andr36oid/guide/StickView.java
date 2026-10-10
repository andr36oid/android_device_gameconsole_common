package org.andr36oid.guide;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import org.andr36oid.consoleart.CanvasPainter;
import org.andr36oid.consoleart.Palette;
import org.andr36oid.consoleart.StickDrawing;

/** The left and right stick as the shared drawing shows them, following the sticks. */
public class StickView extends View {

    private final CanvasPainter mPainter = new CanvasPainter();
    private final StickDrawing mStick = new StickDrawing();
    private final StickDrawing.Colors mColors;
    private final float[] mAxes = new float[4];

    public StickView(Context context) {
        super(context);
        mColors = new Palette(context).stick;
        mStick.labelSize = 12 * getResources().getDisplayMetrics().scaledDensity;
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
        final float labelHeight = mStick.labelSize * 2;
        final float w = getWidth() / 2f;
        final float h = getHeight() - labelHeight;
        final float radius = Math.min(w, h) / 2f * 0.85f;
        if (radius <= 0) {
            return;
        }
        mPainter.on(canvas).identity();
        for (int stick = 0; stick < 2; stick++) {
            mStick.draw(mPainter, mColors, w * stick + w / 2f, h / 2f, radius,
                    mAxes[stick * 2], mAxes[stick * 2 + 1], false, 0, null, 0, 0,
                    stick == 0 ? "L" : "R");
        }
    }
}
