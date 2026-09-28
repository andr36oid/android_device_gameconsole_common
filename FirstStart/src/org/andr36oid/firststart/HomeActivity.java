package org.andr36oid.firststart;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

/**
 * The home screen while the first-start tutorial runs. It has no tutorial logic: each time it
 * comes to the front (the setup wizard ended, FN was pressed, the console started) it tells the
 * quick start guide, and the guide shows its tutorial again. Its own page only shows for a
 * moment, or if the tutorial doesn't come back: then A goes back to the tutorial and Skip
 * tutorial ends it, which gives the home screen back to Daijishou.
 */
public class HomeActivity extends Activity {

    private static final String TAG = "FirstStart";

    private static final String GUIDE_PACKAGE = "org.andr36oid.guide";
    private static final String GUIDE_RECEIVER = GUIDE_PACKAGE + ".FirstStartReceiver";
    /** Home came to the front: after the wizard, after FN, after a start. */
    private static final String ACTION_HOME = GUIDE_PACKAGE + ".action.FIRST_START_HOME";
    /** The user asked for the tutorial again. */
    private static final String ACTION_OPEN = GUIDE_PACKAGE + ".action.FIRST_START_OPEN";
    /** The user wants to skip the tutorial and go to the real home screen. */
    private static final String ACTION_SKIP = GUIDE_PACKAGE + ".action.FIRST_START_SKIP";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.home_activity);
        findViewById(R.id.back).setOnClickListener(v -> tell(ACTION_OPEN));
        findViewById(R.id.skip).setOnClickListener(v -> tell(ACTION_SKIP));
    }

    @Override
    protected void onResume() {
        super.onResume();
        findViewById(R.id.back).requestFocus();
        tell(ACTION_HOME);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // The home screen has nowhere to go back to
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** The guide's receiver only takes this from apps holding its FIRST_START permission. */
    private void tell(String action) {
        try {
            sendBroadcast(new Intent(action)
                    .setComponent(new ComponentName(GUIDE_PACKAGE, GUIDE_RECEIVER))
                    // Also if the guide was force-stopped
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND
                            | Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't reach the quick start guide", e);
        }
    }
}
