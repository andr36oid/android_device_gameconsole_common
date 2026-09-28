package org.andr36oid.batterydetails;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/** The last 24 hours of charge level. */
public class GraphPreference extends Preference {

    static final long SPAN_MS = 24 * 3600 * 1000L;

    private List<History.Point> mPoints;
    private long mEnd;

    public GraphPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.graph);
        setSelectable(false);
    }

    void reload() {
        mEnd = System.currentTimeMillis();
        mPoints = History.load(getContext(), mEnd - SPAN_MS);
        notifyChanged();
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        if (mPoints == null) {
            return;
        }
        ((GraphView) view.findViewById(R.id.graph)).setPoints(mPoints, mEnd - SPAN_MS, mEnd);
        // One point is just a dot, say why.
        view.findViewById(R.id.empty).setVisibility(mPoints.size() < 2 ? View.VISIBLE
                : View.GONE);
    }
}
