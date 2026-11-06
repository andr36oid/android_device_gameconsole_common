package org.andr36oid.subsystemsetup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Daijishou's explicit optional player; the dashboard owns requests and progress. */
public class RetroArchActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!"1".equals(SubsystemBackend.get(SubsystemBackend.DAIJISHOU_CORES))) {
            Toast.makeText(this, R.string.asl_daijishou_disabled, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Intent intent = new Intent(this, SubsystemSetupActivity.class);
        intent.putExtra(SubsystemSetupActivity.EXTRA_LAUNCH, "RACORE");
        intent.putExtra(SubsystemSetupActivity.EXTRA_LAUNCH_TOKEN, SubsystemSetupActivity.mintLaunchToken());
        intent.putExtra("CORE", getIntent().getStringExtra("CORE"));
        intent.putExtra("ROM", getIntent().getStringExtra("ROM"));
        startActivity(intent);
        finish();
    }
}
