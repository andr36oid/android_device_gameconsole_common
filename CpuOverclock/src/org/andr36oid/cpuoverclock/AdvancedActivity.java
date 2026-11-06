package org.andr36oid.cpuoverclock;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;

import org.andr36oid.hints.HintBar;

/** The Advanced page of the Performance dashboard: expert clock controls (see OverclockFragment). */
public class AdvancedActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.perf_row_advanced_title);
        HintBar.attachDefault(this);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new OverclockFragment())
                    .commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }
}
