/*
 * SPDX-License-Identifier: MIT
 *
 * Daijisho -> root bridge for the andr36oid Subsystem for dArkOSen.
 *
 * Daijisho launches a "player" by am-starting an activity with the selected
 * file's path (as the VIEW data URI, or an extra). We cannot run the subsystem
 * broker from here - it needs root to tear the framework down and hand off the
 * display. So we do the one thing a system-uid app can do: set the properties
 * init is waiting on, and let init start the (root) broker service. Then finish
 * immediately; the broker stops the whole framework (this activity included)
 * for the duration and restarts it when the port exits, returning the user to
 * Daijisho.
 *
 *   sys.andr36oid.subsystem.port    = "ES" | "PM" | <port name>
 *   sys.andr36oid.subsystem.request = "run"   (the trigger)
 */
package org.andr36oid.subsystem;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

public class LaunchPortActivity extends Activity {
    private static final String TAG = "SubsystemBridge";
    private static final String P_PORT = "sys.andr36oid.subsystem.port";
    private static final String P_REQ  = "sys.andr36oid.subsystem.request";
    // The broker searches these for a user-supplied dArkOSen image.
    private static final String[] IMAGE_GLOB_DIRS = {
        "/data/subsystem/darkosen", "/mnt/media_rw", "/storage",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String spec = resolveSpec();
        if (spec == null) {
            Log.w(TAG, "no ROM in launch intent");
            finish();
            return;
        }
        if (!subsystemInstalled()) {
            Toast.makeText(this, R.string.no_subsystem, Toast.LENGTH_LONG).show();
            Log.w(TAG, "subsystem image not found; not launching " + spec);
            finish();
            return;
        }

        Log.i(TAG, "requesting subsystem launch: " + spec);
        SystemProperties.set(P_PORT, spec);
        SystemProperties.set(P_REQ, "run");   // init starts andr36oid-subsystem
        finish();
    }

    /** Turn the launch intent into the broker's port spec. */
    private String resolveSpec() {
        String raw = getIntent().getStringExtra("ROM");
        if (raw == null) {
            Uri d = getIntent().getData();
            if (d != null) raw = d.getPath();
        }
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.equalsIgnoreCase("ES") || raw.equalsIgnoreCase("PM")) {
            return raw.toUpperCase();
        }
        // A Daijisho entry is a file path; the broker wants the port's name under
        // /roms/ports. Strip directory + a single .sh/.SH suffix.
        String name = new File(raw).getName();
        if (name.length() > 3) {
            String low = name.toLowerCase();
            if (low.endsWith(".sh")) name = name.substring(0, name.length() - 3);
        }
        return name.isEmpty() ? null : name;
    }

    private boolean subsystemInstalled() {
        for (String dir : IMAGE_GLOB_DIRS) {
            File f = new File(dir);
            if (hasImage(f, 0)) return true;
        }
        // test prototype location
        return new File("/data/local/tmp/darkosen.img").exists();
    }

    /** Shallow scan for a *.img under dir (depth <= 2), without walking all of /storage. */
    private boolean hasImage(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 2) return false;
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File k : kids) {
            if (k.isFile() && k.getName().toLowerCase().endsWith(".img")) return true;
            if (k.isDirectory() && depth < 2 && hasImage(k, depth + 1)) return true;
        }
        return false;
    }
}
