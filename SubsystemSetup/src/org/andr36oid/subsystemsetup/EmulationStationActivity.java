package org.andr36oid.subsystemsetup;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
public class EmulationStationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Always hand off to the dashboard so the user sees the boot/progress screen
        // instead of being dropped back on the launcher while the subsystem starts.
        // When ready, carry the ES launch request so it boots straight into progress.
        Intent i=new Intent(this,SubsystemSetupActivity.class);
        if(SubsystemSetupActivity.isReady() && SubsystemSetupActivity.get(SubsystemSetupActivity.P+"operation").isEmpty()) {
            i.putExtra(SubsystemSetupActivity.EXTRA_LAUNCH,"ES");
            i.putExtra(SubsystemSetupActivity.EXTRA_LAUNCH_TOKEN,SubsystemSetupActivity.mintLaunchToken());
        }
        startActivity(i);
        finish();
    }
}
