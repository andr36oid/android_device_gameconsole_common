package org.andr36oid.guide;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import java.util.Locale;

/**
 * The setup wizard page after the tutorial (org.lineageos.setupwizard,
 * lineage_wizard_script.xml): shut down now to copy games onto EASYROMS, so Daijishou finds
 * them the first time it opens. Only with an EASYROMS partition and Daijishou as the home
 * screen; otherwise it passes straight on.
 *
 * "Shut down now" sets SETTING_SHUTDOWN and goes on: the wizard's last page then finishes
 * the setup by itself, and the guide powers off right away (FirstStart.finish). The
 * first-start launcher stays home, so the next start goes on from there: it waits for
 * Daijishou's library, then Daijishou opens and asks to look for the games.
 */
public class SetupAddGamesActivity extends Activity {

    private static final String TAG = "SetupAddGames";

    /**
     * Settings.Global: power off once the setup is done (read by the wizard and FirstStart).
     * 0, or the boot count of the start it was chosen in (bootCount).
     */
    static final String SETTING_SHUTDOWN = "andr36oid_setup_shutdown";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!HomeChoice.DAIJISHOU.equals(HomeChoice.chosen(this)) || !hasEasyroms(this)) {
            // Nothing to add games to, or the other home screen: not this page
            Log.i(TAG, "Not offered");
            if (savedInstanceState == null) {
                SetupPages.next(this);
            }
            finish();
            return;
        }
        setContentView(R.layout.setup_add_games);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.next).setOnClickListener(v -> {
            setShutdown(false);
            SetupPages.next(this);
        });
        findViewById(R.id.shut_down).setOnClickListener(v -> {
            Log.i(TAG, "Shutting down after the setup to add games");
            setShutdown(true);
            v.setEnabled(false);
            SetupPages.next(this);
        });
        final View next = findViewById(R.id.next);
        next.requestFocus();
    }

    private void setShutdown(boolean on) {
        Settings.Global.putInt(getContentResolver(), SETTING_SHUTDOWN, on ? bootCount(this) : 0);
    }

    /** This start's number (Settings.Global.BOOT_COUNT), at least 1. */
    static int bootCount(Context context) {
        return Math.max(1, Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.BOOT_COUNT, 1));
    }

    /** Whether the card has the EASYROMS partition, mounted. */
    static boolean hasEasyroms(Context context) {
        try {
            for (StorageVolume volume : context.getSystemService(StorageManager.class)
                    .getStorageVolumes()) {
                final String name = volume.getDescription(context);
                if (volume.getDirectory() != null && name != null
                        && name.toUpperCase(Locale.ROOT).contains("EASYROMS")) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't read the storage volumes", e);
        }
        return false;
    }
}
