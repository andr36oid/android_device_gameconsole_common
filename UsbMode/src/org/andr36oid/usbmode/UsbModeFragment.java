package org.andr36oid.usbmode;

import android.app.AlertDialog;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.widget.Toast;

public class UsbModeFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_DEVICE = "device";

    /** Set by the Quick Settings tile: ask right away, and close again on Cancel. */
    static final String EXTRA_ASK_DEVICE = "org.andr36oid.usbmode.extra.ASK_DEVICE";

    private UsbMode mUsbMode;
    private SwitchPreference mDevice;
    private AlertDialog mWarning;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUsbMode = new UsbMode(getActivity());
        addPreferencesFromResource(R.xml.usb_mode_settings);
        mDevice = (SwitchPreference) findPreference(KEY_DEVICE);
        final ListPreference functions = (ListPreference) findPreference(UsbMode.KEY_FUNCTIONS);
        functions.setValue(mUsbMode.getFunctions());

        if (!mUsbMode.isSupported()) {
            mDevice.setEnabled(false);
            mDevice.setSummary(R.string.usb_mode_unsupported);
            functions.setEnabled(false);
            return;
        }
        mDevice.setOnPreferenceChangeListener(this);
        functions.setOnPreferenceChangeListener(this);

        if (savedInstanceState == null
                && getActivity().getIntent().getBooleanExtra(EXTRA_ASK_DEVICE, false)
                && !mUsbMode.isDevice()) {
            askForDevice(true);
        }
    }

    @Override
    public void onDestroy() {
        if (mWarning != null) {
            mWarning.setOnDismissListener(null);
            mWarning.dismiss();
            mWarning = null;
        }
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        // The tile may have switched it in the meantime.
        if (mUsbMode.isSupported()) {
            update();
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (UsbMode.KEY_FUNCTIONS.equals(preference.getKey())) {
            // Saves the choice and applies it right away when already in device mode.
            mUsbMode.setFunctions((String) newValue);
            return true;
        }
        if ((Boolean) newValue) {
            askForDevice(false);
        } else {
            switchDevice(false);
        }
        return false;
    }

    /** Device mode only after the warning about built-in Wi-Fi was confirmed. */
    private void askForDevice(final boolean fromTile) {
        if (mWarning != null) {
            return;
        }
        if (mUsbMode.isCharging()) {
            Toast.makeText(getActivity(), R.string.charging_refused, Toast.LENGTH_LONG).show();
            if (fromTile) {
                getActivity().finish();
            }
            return;
        }
        mWarning = DeviceModeWarning.show(getActivity(), mUsbMode,
                new DeviceModeWarning.Callback() {
                    @Override
                    public void onConfirmed() {
                        mWarning = null;
                        switchDevice(true);
                    }

                    @Override
                    public void onCancelled() {
                        mWarning = null;
                        if (fromTile && getActivity() != null) {
                            getActivity().finish();
                        }
                    }
                });
    }

    private void switchDevice(boolean device) {
        if (getActivity() == null) {
            return;
        }
        if (device && mUsbMode.isCharging()) {
            // Plugged in while the warning was open.
            Toast.makeText(getActivity(), R.string.charging_refused, Toast.LENGTH_LONG).show();
        } else if (!mUsbMode.setDevice(device)) {
            Toast.makeText(getActivity(), R.string.usb_mode_failed, Toast.LENGTH_SHORT).show();
        }
        // Shows what the kernel ended up with rather than what was asked for.
        update();
    }

    private void update() {
        final boolean device = mUsbMode.isDevice();
        mDevice.setChecked(device);
        mDevice.setSummary(device ? R.string.usb_mode_switch_on : R.string.usb_mode_switch_off);
    }
}
