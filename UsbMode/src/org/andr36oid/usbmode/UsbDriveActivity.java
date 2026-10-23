package org.andr36oid.usbmode;

import android.app.Activity;
import android.os.Bundle;

import org.andr36oid.hints.HintBar;

public class UsbDriveActivity extends Activity {

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        HintBar.attachDefault(this);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        if (state == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new UsbDriveFragment())
                    .commit();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ControllerControl.busy()) {
            startActivity(new android.content.Intent(this, ControllerSessionActivity.class));
            finish();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }
}
