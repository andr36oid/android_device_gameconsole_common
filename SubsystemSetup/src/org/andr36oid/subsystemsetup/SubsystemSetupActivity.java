package org.andr36oid.subsystemsetup;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

import org.andr36oid.hints.HintBar;

public class SubsystemSetupActivity extends Activity {

    void setOptimizationActive(boolean active) {
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onPause() {
        setOptimizationActive(false);
        super.onPause();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HintBar.attachDefault(this);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SubsystemSetupFragment())
                    .commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }
}
