package org.andr36oid.usbmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.service.quicksettings.TileService;

/** Chooses a normal wizard preset; never starts sharing or switches USB roles itself. */
public final class UsbFunctionsActivity extends Activity {
    static final String EXTRA_PRESET = "org.andr36oid.usbmode.extra.FUNCTION_PRESET";
    private static final String[] ROLES = {"game", "input", "drive"};
    private AlertDialog mDialog;

    static boolean isTilePreferences(Intent intent) {
        if (intent == null || !TileService.ACTION_QS_TILE_PREFERENCES.equals(intent.getAction())) {
            return false;
        }
        final ComponentName tile = intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME);
        return tile != null && UsbFunctionsTileService.class.getName().equals(tile.getClassName())
                && UsbFunctionsTileService.class.getPackage().getName().equals(tile.getPackageName());
    }

    static String initialRole(Intent intent, boolean recreated, boolean supported, boolean busy) {
        if (intent == null || recreated || !supported || busy) return null;
        final String preset = intent.getStringExtra(EXTRA_PRESET);
        for (String role : ROLES) if (role.equals(preset)) return role;
        return null;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!new UsbMode(this).isSupported()) { finish(); return; }
        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.usb_functions_title)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish());
        if (UsbDriveControl.busy()) {
            builder.setMessage(R.string.usb_functions_active)
                    .setPositiveButton(R.string.usb_functions_open, (dialog, which) -> open(null));
        } else {
            final CharSequence[] labels = {getString(R.string.usb_role_game_summary),
                    getString(R.string.usb_role_input_summary), getString(R.string.usb_role_drive_summary)};
            builder.setItems(labels, (dialog, which) -> {
                if (isFinishing() || isDestroyed()) return;
                if (!new UsbMode(this).isSupported()) { finish(); return; }
                // A session arriving while the chooser is open always wins.
                open(UsbDriveControl.busy() ? null : ROLES[which]);
            });
        }
        mDialog = builder.create();
        mDialog.setOnDismissListener(dialog -> finish());
        UsbDialogInput.show(mDialog);
    }

    private void open(String preset) {
        if (isFinishing() || isDestroyed() || !new UsbMode(this).isSupported()) return;
        final Intent intent = new Intent(this, UsbDriveActivity.class);
        if (preset != null && !UsbDriveControl.busy()) intent.putExtra(EXTRA_PRESET, preset);
        startActivity(intent);
        finish();
    }

    @Override public void onDestroy() {
        if (mDialog != null) {
            mDialog.setOnDismissListener(null);
            mDialog.dismiss();
        }
        super.onDestroy();
    }
}
