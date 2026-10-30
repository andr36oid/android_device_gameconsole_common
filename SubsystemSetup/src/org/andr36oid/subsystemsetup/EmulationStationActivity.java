package org.andr36oid.subsystemsetup;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
public class EmulationStationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(!SubsystemSetupActivity.isReady() || !SubsystemSetupActivity.get(SubsystemSetupActivity.P+"operation").isEmpty()) {
            startActivity(new Intent(this,SubsystemSetupActivity.class));finish();return;
        }
        try {
            SubsystemSetupActivity.put(SubsystemBackend.ROOTFS,"");
            SubsystemSetupActivity.put(SubsystemBackend.HANDOFF,"1");
            SubsystemSetupActivity.put(SubsystemBackend.PORT,"ES");
            SubsystemSetupActivity.put(SubsystemBackend.REQUEST,"run");
        } catch(RuntimeException e) { startActivity(new Intent(this,SubsystemSetupActivity.class)); }
        finally { finish(); }
    }
}
