package org.andr36oid.emmc;

import android.content.Context;
import android.preference.Preference;
import android.view.View;
import android.widget.ProgressBar;

/** A Settings row with a progress bar under its title and summary. */
final class ProgressPreference extends Preference {
    /** 0-1000, or -1: going, but how far isn't known. */
    private int mPermille = -1;

    ProgressPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.install_progress);
        setSelectable(false);
        setPersistent(false);
    }

    void setProgress(int permille) {
        if (permille != mPermille) {
            mPermille = permille;
            notifyChanged();
        }
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        final ProgressBar bar = view.findViewById(R.id.bar);
        bar.setIndeterminate(mPermille < 0);
        if (mPermille >= 0) bar.setProgress(mPermille);
    }
}
