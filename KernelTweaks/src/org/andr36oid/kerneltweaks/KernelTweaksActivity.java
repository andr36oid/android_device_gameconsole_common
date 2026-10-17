package org.andr36oid.kerneltweaks;

import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;

public class KernelTweaksActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new KernelTweaksFragment())
                    .commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        if (!askRestart()) finish();
        return true;
    }

    @Override
    public void onBackPressed() {
        if (!askRestart()) super.onBackPressed();
    }

    /** true when the page asks whether to restart now, before it is left */
    private boolean askRestart() {
        final Fragment f = getFragmentManager().findFragmentById(android.R.id.content);
        return f instanceof KernelTweaksFragment && ((KernelTweaksFragment) f).askRestart();
    }
}
