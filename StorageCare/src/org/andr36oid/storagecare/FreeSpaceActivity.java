package org.andr36oid.storagecare;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;

import org.andr36oid.hints.HintBar;

/** Settings > Storage > Free up space (and where the storage warnings lead). */
public class FreeSpaceActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HintBar.attachDefault(this);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new FreeSpaceFragment())
                    .commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }
}
