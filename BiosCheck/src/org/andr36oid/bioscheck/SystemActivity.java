package org.andr36oid.bioscheck;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;

/** One system's BIOS files, opened from the main page. B goes back. */
public class SystemActivity extends Activity {

    static final String EXTRA_SYSTEM = "system";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SystemFragment())
                    .commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }
}
