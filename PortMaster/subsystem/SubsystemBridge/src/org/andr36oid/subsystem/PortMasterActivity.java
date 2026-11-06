package org.andr36oid.subsystem;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Useful front door for the launcher entry required by Daijishou's player check. */
public class PortMasterActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, LaunchPortActivity.class).putExtra("ROM", "PM"));
        finish();
    }
}
