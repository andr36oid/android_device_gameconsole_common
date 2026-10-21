package org.andr36oid.playtime;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import org.andr36oid.hints.HintBar;

/** Settings > Play time > Parental controls; behind the PIN once one is set. */
public class ParentalActivity extends Activity {

    private static final int REQUEST_PIN = 1;
    private static final String STATE_OPEN = "open";
    private boolean mOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HintBar.attachDefault(this);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        mOpen = savedInstanceState != null && savedInstanceState.getBoolean(STATE_OPEN);
        if (mOpen) return;
        if (Parental.hasPin(this) && !Parental.isUnlocked(this)) {
            startActivityForResult(new Intent(this, PinActivity.class), REQUEST_PIN);
        } else {
            open();
        }
    }

    private void open() {
        mOpen = true;
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new ParentalFragment())
                .commit();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PIN) return;
        if (resultCode == RESULT_OK) {
            open();
        } else {
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_OPEN, mOpen);
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }
}
