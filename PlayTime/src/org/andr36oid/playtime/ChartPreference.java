package org.andr36oid.playtime;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/** Today's and the week's play time above the week chart. */
public class ChartPreference extends Preference {

    private Usage mUsage;

    public ChartPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.week_chart);
        setSelectable(false);
    }

    void setUsage(Usage usage) {
        mUsage = usage;
        notifyChanged();
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        if (mUsage == null) {
            return;
        }
        final Context context = getContext();
        ((TextView) view.findViewById(R.id.today)).setText(context.getString(R.string.today,
                Usage.format(context, mUsage.todayMs())));
        ((TextView) view.findViewById(R.id.week)).setText(context.getString(R.string.this_week,
                Usage.format(context, mUsage.weekMs)));
        ((WeekChart) view.findViewById(R.id.chart)).setDays(mUsage.dayMs, mUsage.dayStart);
    }
}
