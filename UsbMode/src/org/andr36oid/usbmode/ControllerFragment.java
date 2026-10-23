package org.andr36oid.usbmode;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.widget.Toast;

public class ControllerFragment extends PreferenceFragment {
    private AlertDialog mWarning;
    private UsbMode mUsbMode;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        mUsbMode = new UsbMode(getActivity());
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getActivity());
        setPreferenceScreen(screen);
        for (int i = 0; i < ControllerMode.MODES.length; i++) {
            final String mode = ControllerMode.MODES[i];
            final Preference pref = new Preference(getActivity());
            pref.setKey(mode);
            pref.setTitle(ControllerControl.TITLES[i]);
            pref.setSummary(i == 0 ? R.string.controller_xinput_summary
                    : i == 1 ? R.string.controller_gamepad_summary : R.string.controller_keyboard_summary);
            pref.setOnPreferenceClickListener(p -> { start(mode); return true; });
            screen.addPreference(pref);
        }
        final ListPreference layout = new ListPreference(getActivity());
        layout.setKey("keyboard_layout");
        layout.setTitle(R.string.keyboard_layout);
        layout.setEntries(new CharSequence[] {getString(R.string.keyboard_layout_us),
                getString(R.string.keyboard_layout_de), getString(R.string.keyboard_layout_fr)});
        layout.setEntryValues(KeyboardModel.LAYOUTS);
        layout.setDefaultValue(KeyboardModel.defaultLayout(java.util.Locale.getDefault()));
        layout.setSummary("%s");
        screen.addPreference(layout);
        final Preference footer = new Preference(getActivity());
        footer.setTitle(R.string.controller_about);
        footer.setSummary(getString(R.string.controller_battery) + "\n\n" + getString(R.string.usb_drive_cable));
        footer.setOnPreferenceClickListener(p -> {
            new AlertDialog.Builder(getActivity()).setTitle(R.string.controller_title)
                    .setMessage(footer.getSummary()).setPositiveButton(android.R.string.ok, null).show();
            return true;
        });
        screen.addPreference(footer);
    }

    private void start(String mode) {
        if (ControllerControl.busy()) {
            startActivity(new Intent(getActivity(), ControllerSessionActivity.class));
            return;
        }
        if (UsbDriveControl.busy()) {
            Toast.makeText(getActivity(), R.string.controller_busy, Toast.LENGTH_LONG).show();
            return;
        }
        if (mUsbMode.chargingBlocksDevice()) {
            Toast.makeText(getActivity(), R.string.charging_refused, Toast.LENGTH_LONG).show();
            return;
        }
        if (mWarning != null) return;
        mWarning = DeviceModeWarning.show(getActivity(), mUsbMode, new DeviceModeWarning.Callback() {
            @Override
            public void onConfirmed() {
                mWarning = null;
                if (!mUsbMode.chargingBlocksDevice() && !UsbDriveControl.busy())
                    ControllerControl.start(getActivity(), mode);
            }
            @Override
            public void onCancelled() { mWarning = null; }
        });
    }

    @Override
    public void onDestroy() {
        if (mWarning != null) { mWarning.setOnDismissListener(null); mWarning.dismiss(); }
        super.onDestroy();
    }
}
