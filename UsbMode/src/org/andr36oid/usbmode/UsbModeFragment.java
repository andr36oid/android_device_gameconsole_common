package org.andr36oid.usbmode;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.widget.Toast;

public class UsbModeFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_DEVICE = "device";

    private UsbMode mUsbMode;
    private SwitchPreference mDevice;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUsbMode = new UsbMode(getActivity());
        addPreferencesFromResource(R.xml.usb_mode_settings);
        mDevice = (SwitchPreference) findPreference(KEY_DEVICE);

        if (!mUsbMode.isSupported()) {
            mDevice.setEnabled(false);
            mDevice.setSummary(R.string.usb_mode_unsupported);
            return;
        }
        mDevice.setOnPreferenceChangeListener(this);
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
        if (!mUsbMode.setDevice((Boolean) newValue)) {
            Toast.makeText(getActivity(), R.string.usb_mode_failed, Toast.LENGTH_SHORT).show();
        }
        // Shows what the kernel ended up with rather than what was asked for.
        update();
        return false;
    }

    private void update() {
        final boolean device = mUsbMode.isDevice();
        mDevice.setChecked(device);
        mDevice.setSummary(device ? R.string.usb_mode_switch_on : R.string.usb_mode_switch_off);
    }
}
