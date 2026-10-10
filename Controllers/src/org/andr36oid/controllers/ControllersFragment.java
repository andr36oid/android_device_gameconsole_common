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

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private InputManager mInputManager;
    private Preference mExternal;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.controls_settings);
        mInputManager = getContext().getSystemService(InputManager.class);
        addApps((PreferenceCategory) findPreference("console"));
        mExternal = findPreference("external");
        mExternal.setIntent(new Intent(getContext(), ExternalActivity.class));
        final ListPreference player1 = (ListPreference) findPreference("player1");
        player1.setValue(player1());
        player1.setOnPreferenceChangeListener(this);
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
