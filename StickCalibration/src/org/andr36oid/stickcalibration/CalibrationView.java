package org.andr36oid.stickcalibration;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;

/**
 * Two stick circles with the live stick position, the dead zone and, while calibrating, the
 * path each stick took; a title, a message block and the key hints below. Colours come from
 * the DayNight theme. Laid out in fractions of the view for the 640x480 screen.
 */
class CalibrationView extends View {

    private static final int TRAIL = 240;

    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mDim = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private final int mFg;
    private final int mSecondary;
    private final int mAccent;

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
    private String mHint = "";
    private String mFootnote = "";

    CalibrationView(Context context) {
        super(context);
        final TypedArray a = context.obtainStyledAttributes(new int[] {
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary,
                android.R.attr.colorAccent});
        mFg = a.getColor(0, 0xFFFFFFFF);
        mSecondary = a.getColor(1, 0xFF9E9E9E);
        mAccent = a.getColor(2, 0xFF80CBC4);
        a.recycle();

        mStroke.setStyle(Paint.Style.STROKE);
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

    void setTexts(String title, String message, String left, String right, String hint,
            String footnote) {
        mTitleText = title;
        mMessage = message;
        mLeftLabel = left;
        mRightLabel = right;
        mHint = hint;
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

        // Hints and footnote at the bottom
        final float hintY = h - pad - sp(4) - (mFootnote.isEmpty() ? 0 : sp(18));
        mText.setColor(mAccent);
        canvas.drawText(mHint, pad, hintY, mText);
        mText.setColor(mFg);
        if (!mFootnote.isEmpty()) {
            canvas.drawText(mFootnote, pad, h - pad, mDim);
        }
    }

    private void drawStick(Canvas canvas, float cx, float cy, float r, int stick,
            String label) {
        mStroke.setColor(mSecondary);
        mStroke.setStrokeWidth(sp(2));
        canvas.drawCircle(cx, cy, r, mStroke);
        mStroke.setStrokeWidth(sp(1));
        canvas.drawLine(cx - r, cy, cx + r, cy, mStroke);
        canvas.drawLine(cx, cy - r, cx, cy + r, mStroke);

        if (mDeadzone > 0) {
            mFill.setColor((mAccent & 0x00FFFFFF) | 0x40000000);
            canvas.drawCircle(cx, cy, r * mDeadzone, mFill);
        }

        final int n = mTrailCount[stick];
        if (mShowTrail && n > 1) {
            mPath.reset();
            final int first = (mTrailHead[stick] - n + TRAIL) % TRAIL;
            for (int k = 0; k < n; k++) {
                final int i = (first + k) % TRAIL;
                final float x = cx + clamp(mTrail[stick][i * 2]) * r;
                final float y = cy + clamp(mTrail[stick][i * 2 + 1]) * r;
                if (k == 0) {
                    mPath.moveTo(x, y);
                } else {
                    mPath.lineTo(x, y);
                }
            }
            mStroke.setColor(mAccent);
            mStroke.setStrokeWidth(sp(2));
            canvas.drawPath(mPath, mStroke);
        }

        mFill.setColor(mFg);
        canvas.drawCircle(cx + clamp(mAxes[stick * 2]) * r,
                cy + clamp(mAxes[stick * 2 + 1]) * r, sp(7), mFill);

        final float tw = mDim.measureText(label);
        canvas.drawText(label, cx - tw / 2, cy + r + sp(20), mDim);
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
