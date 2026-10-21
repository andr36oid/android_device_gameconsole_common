package org.andr36oid.stickcalibration;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;

import org.andr36oid.consoleart.CanvasPainter;
import org.andr36oid.consoleart.Palette;
import org.andr36oid.consoleart.StickDrawing;

/**
 * The two sticks (the shared stick drawing) with the live position, the dead zone and, while
 * calibrating, the path each stick took; a title, a message block and the key hints below. Colours come from
 * the DayNight theme. Laid out in fractions of the view for the 640x480 screen.
 */
class CalibrationView extends View {

    private static final int TRAIL = 240;

    private final TextPaint mTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mDim = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final CanvasPainter mPainter = new CanvasPainter();
    private final StickDrawing mStick = new StickDrawing();
    private final StickDrawing.Colors mStickColors;

    private final int mFg;
    private final int mSecondary;

    /** Left X/Y, right X/Y as the framework reports them, -1..1 */
    private final float[] mAxes = new float[4];
    /** Recent positions per stick, x/y pairs, a ring buffer */
    private final float[][] mTrail = new float[2][TRAIL * 2];
    private final int[] mTrailCount = new int[2];
    private final int[] mTrailHead = new int[2];
    private boolean mShowTrail;

    private float mDeadzone;
    private String mTitleText = "";
    private String mMessage = "";
    private String mLeftLabel = "";
    private String mRightLabel = "";
    private String mFootnote = "";

    CalibrationView(Context context) {
        super(context);
        final TypedArray a = context.obtainStyledAttributes(new int[] {
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary,
                android.R.attr.colorAccent});
        mFg = a.getColor(0, 0xFFFFFFFF);
        mSecondary = a.getColor(1, 0xFF9E9E9E);
        a.recycle();

        mStickColors = new Palette(context).stick;
        mTitle.setColor(mFg);
        mTitle.setTextSize(sp(20));
        mText.setColor(mFg);
        mText.setTextSize(sp(15));
        mDim.setColor(mSecondary);
        mDim.setTextSize(sp(13));
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    void setAxes(float lx, float ly, float rx, float ry) {
        mAxes[0] = lx;
        mAxes[1] = ly;
        mAxes[2] = rx;
        mAxes[3] = ry;
        if (mShowTrail) {
            addTrail(0, lx, ly);
            addTrail(1, rx, ry);
        }
        invalidate();
    }

    private void addTrail(int stick, float x, float y) {
        final int i = mTrailHead[stick];
        mTrail[stick][i * 2] = x;
        mTrail[stick][i * 2 + 1] = y;
        mTrailHead[stick] = (i + 1) % TRAIL;
        mTrailCount[stick] = Math.min(TRAIL, mTrailCount[stick] + 1);
    }

    void setShowTrail(boolean show) {
        mShowTrail = show;
        mTrailCount[0] = mTrailCount[1] = 0;
        mTrailHead[0] = mTrailHead[1] = 0;
        invalidate();
    }

    /** Dead zone drawn around the middle, 0..1 of the travel; 0 hides it. */
    void setDeadzone(float deadzone) {
        mDeadzone = deadzone;
        invalidate();
    }

    void setTexts(String title, String message, String left, String right, String footnote) {
        mTitleText = title;
        mMessage = message;
        mLeftLabel = left;
        mRightLabel = right;
        mFootnote = footnote;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final float pad = w * 0.04f;

        canvas.drawText(mTitleText, pad, pad - mTitle.ascent(), mTitle);

        // Sticks: two circles side by side in the upper half
        final float radius = Math.min(w * 0.17f, h * 0.2f);
        final float cy = h * 0.12f + radius + sp(8);
        drawStick(canvas, w * 0.28f, cy, radius, 0, mLeftLabel);
        drawStick(canvas, w * 0.72f, cy, radius, 1, mRightLabel);

        // Message, wrapped, below the labels
        final int width = (int) (w - pad * 2);
        final float msgTop = cy + radius + sp(34);
        final StaticLayout msg = StaticLayout.Builder.obtain(mMessage, 0, mMessage.length(),
                mText, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        canvas.save();
        canvas.translate(pad, msgTop);
        msg.draw(canvas);
        canvas.restore();

        // Footnote at the bottom, the button hints are in the bar under the view
        if (!mFootnote.isEmpty()) {
            canvas.drawText(mFootnote, pad, h - pad, mDim);
        }
    }

    private void drawStick(Canvas canvas, float cx, float cy, float r, int stick,
            String label) {
        mStick.labelSize = mDim.getTextSize();
        mPainter.on(canvas).identity();
        mStick.draw(mPainter, mStickColors, cx, cy, r, mAxes[stick * 2], mAxes[stick * 2 + 1],
                false, mDeadzone, mShowTrail ? mTrail[stick] : null, mTrailHead[stick],
                mTrailCount[stick], label);
    }
}
