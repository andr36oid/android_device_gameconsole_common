package org.andr36oid.smoothnesstest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Locale;

/** The moving block and the numbers; step() moves the block by one step and redraws. */
class SmoothnessView extends View {

    /** steps a crossing of the screen takes */
    private static final int STEPS = 120;

    private final Paint mBlock = new Paint();
    private final Paint mMark = new Paint();
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mPos;
    private int mDir = 1;
    private String mScreen = "";
    private String mMeasured = "";
    private String mLate = "";

    SmoothnessView(Context context) {
        super(context);
        mBlock.setColor(Color.WHITE);
        mMark.setColor(0xFF404040);
        final float sp = getResources().getDisplayMetrics().scaledDensity;
        mText.setColor(Color.WHITE);
        mText.setTextSize(18 * sp);
        mDim.setColor(0xFFA0A0A0);
        mDim.setTextSize(14 * sp);
    }

    void step(float screenHz, float measured, int lateNow, int lateTotal) {
        mPos += mDir;
        if (mPos >= STEPS || mPos <= 0) {
            mDir = -mDir;
        }
        final Context c = getContext();
        mScreen = c.getString(R.string.smooth_screen, hz(screenHz));
        mMeasured = c.getString(R.string.smooth_measured,
                String.format(Locale.getDefault(), "%.1f", measured));
        mLate = c.getString(R.string.smooth_late, lateNow, lateTotal);
        invalidate();
    }

    private static String hz(float rate) {
        final String s = String.format(Locale.getDefault(), "%.2f", rate);
        return s.replaceAll("[.,]?0+$", "");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        final int size = Math.max(16, Math.min(w, h) / 8);
        final float top = h * 0.55f;
        final float x = (w - size) * mPos / (float) STEPS;
        // marks every 10 steps, so a jump is easier to see against them
        for (int i = 0; i <= STEPS; i += 10) {
            final float mx = (w - size) * i / (float) STEPS + size / 2f;
            canvas.drawRect(mx - 1, top + size + 6, mx + 1, top + size + 18, mMark);
        }
        canvas.drawRect(x, top, x + size, top + size, mBlock);

        final float line = mText.getTextSize() * 1.4f;
        float y = line * 1.2f;
        final float left = line * 0.6f;
        canvas.drawText(mScreen, left, y, mText);
        canvas.drawText(mMeasured, left, y += line, mText);
        canvas.drawText(mLate, left, y += line, mText);
        canvas.drawText(getContext().getString(R.string.smooth_hint), left, h - line * 0.6f,
                mDim);
    }
}
