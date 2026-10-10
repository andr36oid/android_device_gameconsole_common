package org.andr36oid.gamecomfort;

import android.app.Activity;
import android.os.Bundle;

/**
 * "Continue": the Quick Settings tile, FN + A and the power menu start this. It has no window
 * of its own; it finds the last game, brings it back and closes.
 */
public class ContinueActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            finish();
            return;
        }
        final android.content.Context app = getApplicationContext();
        Continue.BACKGROUND.execute(() -> {
            final Continue.Result result = Continue.start(app);
            runOnUiThread(() -> {
                Continue.show(app, result);
                finish();
            });
        });
    }

    @Override
    public void finish() {
        super.finish();
        // No closing animation over the game
        overridePendingTransition(0, 0);
    }
}
