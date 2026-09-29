package org.andr36oid.keyboard;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;

/** Settings > System > Languages & input > On-screen keyboard > Gamepad keyboard. */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }
}
