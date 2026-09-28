package org.andr36oid.playtime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Seven bars, one per day, today on the right and highlighted. */
public class WeekChart extends View {

    private final Paint mBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mToday = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mValue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final SimpleDateFormat mDayName =
            new SimpleDateFormat("EEE", Locale.getDefault());

    private long[] mDayMs;
    private long[] mDayStart;

    public WeekChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        mBar.setColor(context.getColor(R.color.chart_bar));
        mToday.setColor(context.getColor(R.color.chart_bar_today));
        final TypedValue secondary = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, secondary, true);
        final int textColor = context.getColorStateList(secondary.resourceId).getDefaultColor();
        mText.setColor(textColor);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(sp(11));
        mValue.setColor(textColor);
        mValue.setTextAlign(Paint.Align.CENTER);
        mValue.setTextSize(sp(10));
    }

    void setDays(long[] dayMs, long[] dayStart) {
        mDayMs = dayMs;
        mDayStart = dayStart;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mDayMs == null) {
            return;
        }
        long max = 60 * 60 * 1000L; // at least an hour, so a short day doesn't look full
        for (long ms : mDayMs) {
            max = Math.max(max, ms);
        }
        final int days = mDayMs.length;
        final float slot = (float) getWidth() / days;
        final float barWidth = slot * 0.55f;
        final float labelHeight = sp(15);
        final float valueHeight = sp(13);
        final float top = valueHeight;
        final float bottom = getHeight() - labelHeight;
        final float radius = dp(3);
        for (int i = 0; i < days; i++) {
            final float center = slot * i + slot / 2;
            final float height = Math.max((bottom - top) * mDayMs[i] / max,
                    mDayMs[i] > 0 ? dp(2) : 0);
            mRect.set(center - barWidth / 2, bottom - height, center + barWidth / 2, bottom);
            canvas.drawRoundRect(mRect, radius, radius, i == days - 1 ? mToday : mBar);
            if (mDayMs[i] > 0) {
                canvas.drawText(shortDuration(mDayMs[i]), center, mRect.top - dp(3), mValue);
            }
            canvas.drawText(mDayName.format(new Date(mDayStart[i])), center,
                    getHeight() - dp(3), mText);
        }
    }

    /** "0:45", "2:10": fits above a narrow bar. */
    private static String shortDuration(long ms) {
        final long minutes = Math.max(1, ms / 60000);
        return String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60);
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private float sp(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics());
    }
}
