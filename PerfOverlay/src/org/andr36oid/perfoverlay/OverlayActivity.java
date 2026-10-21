package org.andr36oid.perfoverlay;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;

import org.andr36oid.hints.HintBar;

public class OverlayActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        setContentView(R.layout.overlay_activity);
        HintBar.attachDefault(this);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(R.id.settings, new OverlayFragment())
                    .commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }
}
