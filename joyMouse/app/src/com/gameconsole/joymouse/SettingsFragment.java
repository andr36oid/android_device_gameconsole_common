package com.gameconsole.joymouse;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SettingsFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private static final String KEY_TOGGLE_ENABLED = "toggle_enabled";
    private static final String KEY_TOGGLE = "toggle";
    private static final String KEY_TOGGLE_MS = "toggle_ms";
    private static final String KEY_POINTER_STICK = "pointer_stick";
    private static final String KEY_CLASSIC = "classic";
    private static final String KEY_CONTROLS = "controls";
    private static final String KEY_RESET = "reset";

    // Long enough for the daemon to have switched, or given up.
    private static final long ACTIVE_RECHECK_MS = 1500;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mUpdateSummaries = this::updateSummaries;
    private final Runnable mRefreshActive = this::refreshActive;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getPreferenceManager().setPreferenceDataStore(new PropertyStore(getActivity()));
        addPreferencesFromResource(R.xml.joymouse_settings);
        listenToChanges(getPreferenceScreen());
        findPreference(KEY_RESET).setOnPreferenceClickListener(this);
        updateSummaries();
    }

    @Override
    public void onResume() {
        super.onResume();
        // The mode may have been switched with the buttons in the meantime.
        refreshActive();
        updateSummaries();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String key = preference.getKey();
        if (KEY_TOGGLE.equals(key) && ((Set<?>) newValue).size() < 2) {
            Toast.makeText(getActivity(), R.string.toggle_too_few, Toast.LENGTH_LONG).show();
            return false;
        }
        if (PropertyStore.KEY_ACTIVE.equals(key)) {
            mHandler.removeCallbacks(mRefreshActive);
            mHandler.postDelayed(mRefreshActive, ACTIVE_RECHECK_MS);
        }
        // The new value is only stored once this returns.
        mHandler.post(mUpdateSummaries);
        return true;
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        new AlertDialog.Builder(getActivity())
                .setMessage(R.string.reset_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> resetToDefaults())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return true;
    }

    private void listenToChanges(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                listenToChanges((PreferenceGroup) preference);
            } else {
                preference.setOnPreferenceChangeListener(this);
            }
        }
    }

    private void refreshActive() {
        final SwitchPreference active = (SwitchPreference) findPreference(PropertyStore.KEY_ACTIVE);
        active.setChecked(PropertyStore.isActive());
    }

    private void resetToDefaults() {
        final List<String> keys = new ArrayList<>();
        collectSettingKeys(getPreferenceScreen(), keys);
        for (String key : keys) PropertyStore.clear(key);
        getActivity().recreate();
    }

    private static void collectSettingKeys(PreferenceGroup group, List<String> out) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                collectSettingKeys((PreferenceGroup) preference, out);
            } else if (preference.isPersistent() && preference.isSelectable()
                    && !PropertyStore.KEY_ACTIVE.equals(preference.getKey())
                    && !KEY_RESET.equals(preference.getKey())) {
                out.add(preference.getKey());
            }
        }
    }

    private void updateSummaries() {
        final ControlsText.Setup setup = currentSetup();
        final MultiSelectListPreference toggle = (MultiSelectListPreference) findPreference(KEY_TOGGLE);
        toggle.setSummary(ControlsText.chord(getActivity(), orderedButtons(toggle.getValues())));
        findPreference(KEY_CONTROLS).setSummary(ControlsText.controls(getActivity(), setup));
    }

    private ControlsText.Setup currentSetup() {
        final ControlsText.Setup setup = new ControlsText.Setup();
        setup.pointerStick = ((ListPreference) findPreference(KEY_POINTER_STICK)).getValue();
        final boolean classic = ((SwitchPreference) findPreference(KEY_CLASSIC)).isChecked();
        setup.scrollStick = classic ? "none" : "left".equals(setup.pointerStick) ? "right" : "left";
        if (((SwitchPreference) findPreference(KEY_TOGGLE_ENABLED)).isChecked()) {
            setup.toggle = orderedButtons(((MultiSelectListPreference) findPreference(KEY_TOGGLE)).getValues());
        }
        try {
            setup.holdMs = Integer.parseInt(((ListPreference) findPreference(KEY_TOGGLE_MS)).getValue());
        } catch (NumberFormatException e) {
            // Keep the default.
        }
        if (classic) {
            // Only the pointer stick's click does something extra
            setup.bindings.put("left".equals(setup.pointerStick) ? "L3" : "R3", "left");
            return setup;
        }
        for (String button : PropertyStore.BUTTONS) {
            final ListPreference binding =
                    (ListPreference) findPreference("btn_" + button.toLowerCase(Locale.ROOT));
            if (binding != null) setup.bindings.put(button, binding.getValue());
        }
        return setup;
    }

    private static List<String> orderedButtons(Set<String> buttons) {
        final List<String> ordered = new ArrayList<>();
        for (String button : PropertyStore.BUTTONS) {
            if (buttons.contains(button)) ordered.add(button);
        }
        return ordered;
    }
}
