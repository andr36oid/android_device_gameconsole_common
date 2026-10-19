package org.andr36oid.batterydetails;

import android.content.Context;
import android.preference.Preference;
import android.view.View;

/** The charging path as a live diagram at the top of Charging diagnostics. */
public class ChargeDiagramPreference extends Preference {

    private ChargeDiag mDiag;
    private int mPlugged;
    private ChargeDiagramView mView;

    public ChargeDiagramPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.charge_diagram);
        setSelectable(false);
    }

    /** Redraws the diagram in place, without rebinding the whole list every second. */
    void update(ChargeDiag diag, int plugged) {
        mDiag = diag;
        mPlugged = plugged;
        if (mView != null) mView.set(diag, plugged);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        mView = view.findViewById(R.id.diagram);
        if (mDiag != null) mView.set(mDiag, mPlugged);
    }
}
