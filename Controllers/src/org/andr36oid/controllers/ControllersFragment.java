package org.andr36oid.controllers;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.view.InputDevice;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings > Controls: everything about the buttons and sticks in one place. The console's
 * own entries open the button mapper, stick calibration, controller test, joystick mouse and
 * touch controls, which used to be entries of their own on the Settings start page.
 */
public class ControllersFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener, InputManager.InputDeviceListener {

    /** Read by gameconsole-emu (retroarch_players); builtin or external. */
    static final String PROP_PLAYER1 = "persist.andr36oid.ra_player1";
    static final String BUILTIN = "builtin";
    static final String EXTERNAL = "external";
    /** 0-100 % of what a game asks for; init writes it to the joypad driver */
    static final String PROP_RUMBLE = "persist.andr36oid.rumble_strength";
    static final String RUMBLE_DEFAULT = "100";
    static final long RUMBLE_TEST_MS = 500;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private InputManager mInputManager;
    private Preference mExternal;
    private Preference mButtonCheck;
    private Preference mFnStandin;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.controls_settings);
        mInputManager = getContext().getSystemService(InputManager.class);
        addApps((PreferenceCategory) findPreference("console"));
        mButtonCheck = findPreference("button_check");
        mButtonCheck.setIntent(new Intent(getContext(), ButtonCheckActivity.class));
        mFnStandin = findPreference("fn_standin");
        mFnStandin.setOnPreferenceClickListener(pref -> {
            FnChooser.show(getActivity(), this::refreshButtons);
            return true;
        });
        mExternal = findPreference("external");
        mExternal.setIntent(new Intent(getContext(), ExternalActivity.class));
        final ListPreference player1 = (ListPreference) findPreference("player1");
        player1.setValue(player1());
        player1.setOnPreferenceChangeListener(this);
        setUpRumble();
    }

    /** The built-in pad, if it has a motor: the kernel only offers rumble where there is one */
    private InputDevice rumblePad() {
        for (int id : mInputManager.getInputDeviceIds()) {
            final InputDevice device = mInputManager.getInputDevice(id);
            if (device != null && device.getVendorId() == Pads.BUILTIN_VENDOR
                    && !device.isExternal() && device.getVibrator().hasVibrator()) {
                return device;
            }
        }
        return null;
    }

    private void setUpRumble() {
        if (rumblePad() == null) {
            getPreferenceScreen().removePreference(findPreference("rumble"));
            return;
        }
        final ListPreference strength = (ListPreference) findPreference("rumble_strength");
        final String saved = SystemProperties.get(PROP_RUMBLE, RUMBLE_DEFAULT);
        strength.setValue(strength.findIndexOfValue(saved) >= 0 ? saved : RUMBLE_DEFAULT);
        strength.setOnPreferenceChangeListener((pref, value) -> {
            SystemProperties.set(PROP_RUMBLE, (String) value);
            // init writes it to the driver; try the new strength right after
            mHandler.postDelayed(this::testRumble, 300);
            return true;
        });
        findPreference("rumble_test").setOnPreferenceClickListener(pref -> {
            testRumble();
            return true;
        });
    }

    private void testRumble() {
        final InputDevice pad = rumblePad();
        if (pad != null) pad.getVibrator().vibrate(RUMBLE_TEST_MS);
    }

    /** Fills in each app's entry from its activity, or drops it when the build lacks it. */
    private void addApps(PreferenceCategory category) {
        final PackageManager pm = getContext().getPackageManager();
        final List<Preference> entries = new ArrayList<>();
        for (int i = 0; i < category.getPreferenceCount(); i++) {
            entries.add(category.getPreference(i));
        }
        for (Preference entry : entries) {
            final ComponentName component = ComponentName.unflattenFromString(entry.getKey());
            if (component == null) {
                // One of this page's own entries
                continue;
            }
            try {
                final ActivityInfo info = pm.getActivityInfo(component, 0);
                entry.setTitle(info.loadLabel(pm));
                entry.setIntent(new Intent().setComponent(component));
            } catch (PackageManager.NameNotFoundException e) {
                category.removePreference(entry);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mInputManager.registerInputDeviceListener(this, mHandler);
        refreshExternal();
        refreshButtons();
    }

    @Override
    public void onPause() {
        mInputManager.unregisterInputDeviceListener(this);
        super.onPause();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        refreshExternal();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        refreshExternal();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        refreshExternal();
    }

    /** "No Fn, no L3/R3" and "Fn shortcuts: Vol+ and Vol- together" */
    private void refreshButtons() {
        if (getActivity() == null) return;
        mButtonCheck.setSummary(ButtonSettings.summary(getContext()));
        mFnStandin.setSummary(ButtonSettings.describeStandin(getContext(),
                ButtonSettings.fnStandin(getContext())));
    }

    /** The names of the connected pads, or that there are none */
    private void refreshExternal() {
        if (getActivity() == null) return;
        final List<String> names = new ArrayList<>();
        for (Pads.Pad pad : Pads.list(getContext())) {
            if (!pad.isBuiltIn()) names.add(pad.name);
        }
        mExternal.setSummary(names.isEmpty() ? getString(R.string.external_none)
                : String.join(getString(R.string.summary_separator), names));
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        // gameconsole-ra-players writes it into retroarch.cfg once RetroArch is closed
        SystemProperties.set(PROP_PLAYER1, (String) newValue);
        Toast.makeText(getContext(), R.string.player1_later, Toast.LENGTH_LONG).show();
        return true;
    }

    static String player1() {
        return EXTERNAL.equals(SystemProperties.get(PROP_PLAYER1)) ? EXTERNAL : BUILTIN;
    }
}
