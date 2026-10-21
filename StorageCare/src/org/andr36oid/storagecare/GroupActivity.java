package org.andr36oid.storagecare;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;

import org.andr36oid.hints.HintBar;

/** The review page of one group (old save states, screenshots, …). */
public class GroupActivity extends Activity {

    static final String GROUP = "group";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HintBar.attachDefault(this);
        final String group = getIntent().getStringExtra(GROUP);
        if (Picks.scan == null || group == null) {
            // the app was restarted: the picks are gone, go back to the main page
            finish();
            return;
        }
        setTitle(Texts.groupTitle(this, group));
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            final GroupFragment f = new GroupFragment();
            final Bundle args = new Bundle();
            args.putString(GROUP, group);
            f.setArguments(args);
            getFragmentManager().beginTransaction().replace(android.R.id.content, f).commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }
}
