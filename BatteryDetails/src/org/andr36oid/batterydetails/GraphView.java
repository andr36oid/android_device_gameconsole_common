package org.andr36oid.batterydetails;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.List;

/** Charge level over time: a filled line, green where it was charging. */
public class GraphView extends View {

    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCharging = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGrid = new Paint();
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private List<History.Point> mPoints;
    private long mStart;
    private long mEnd;

    public GraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLine.setColor(context.getColor(R.color.graph_line));
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(dp(2));
        mLine.setStrokeCap(Paint.Cap.ROUND);
        mCharging.set(mLine);
        mCharging.setColor(context.getColor(R.color.graph_charging));
        mFill.setColor(context.getColor(R.color.graph_fill));
        mGrid.setColor(context.getColor(R.color.graph_grid));
        mGrid.setStrokeWidth(1);
        final TypedValue secondary = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, secondary, true);
        mText.setColor(context.getColorStateList(secondary.resourceId).getDefaultColor());
        mText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10,
                getResources().getDisplayMetrics()));
    }

    void setPoints(List<History.Point> points, long start, long end) {
        mPoints = points;
        mStart = start;
        mEnd = end;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float labelWidth = mText.measureText("100%") + dp(6);
        final float labelHeight = mText.getTextSize() + dp(4);
        final float left = labelWidth;
        final float right = getWidth() - dp(2);
        final float top = dp(4);
        final float bottom = getHeight() - labelHeight;

        // 0, 50 and 100 % lines with labels, "24 h ago" and "now" underneath.
        for (int percent = 0; percent <= 100; percent += 50) {
            final float y = y(percent, top, bottom);
            canvas.drawLine(left, y, right, y, mGrid);
            canvas.drawText(percent + "%", 0, y + mText.getTextSize() / 3, mText);
        }
        final float baseline = getHeight() - dp(2);
        canvas.drawText(getContext().getString(R.string.graph_ago), left, baseline, mText);
        final String now = getContext().getString(R.string.graph_now);
        canvas.drawText(now, right - mText.measureText(now), baseline, mText);

        if (mPoints == null || mPoints.isEmpty() || mEnd <= mStart) {
            return;
        }
        // Area under the line
        mPath.reset();
        final History.Point first = mPoints.get(0);
        mPath.moveTo(x(first.time, left, right), bottom);
        for (History.Point p : mPoints) {
            mPath.lineTo(x(p.time, left, right), y(p.percent, top, bottom));
        }
        mPath.lineTo(x(mPoints.get(mPoints.size() - 1).time, left, right), bottom);
        mPath.close();
        canvas.drawPath(mPath, mFill);

        // The line, segment by segment so charging stretches can be green
        History.Point last = null;
        for (History.Point p : mPoints) {
            if (last != null) {
                canvas.drawLine(x(last.time, left, right), y(last.percent, top, bottom),
                        x(p.time, left, right), y(p.percent, top, bottom),
                        last.charging && p.charging ? mCharging : mLine);
            }
            last = p;
        }
        if (mPoints.size() == 1) {
            canvas.drawPoint(x(first.time, left, right), y(first.percent, top, bottom), mLine);
        }
    }

    private float x(long time, float left, float right) {
        // Clamped: the clock may have been set back since (no network time here).
        final float x = left + (right - left) * (time - mStart) / (float) (mEnd - mStart);
        return Math.max(left, Math.min(right, x));
    }

    private static float y(int percent, float top, float bottom) {
        return bottom - (bottom - top) * Math.max(0, Math.min(100, percent)) / 100f;
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
