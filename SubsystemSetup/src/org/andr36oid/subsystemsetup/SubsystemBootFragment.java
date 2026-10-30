package org.andr36oid.subsystemsetup;

import android.content.Context;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.widget.Toast;

public class SubsystemBootFragment extends PreferenceFragment {

    private SwitchPreference mBoot;
    private ListPreference mTarget;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context c = getActivity();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);

        // The switch carries its own explanation: its summary always states what the
        // console will do at startup and how to override it with START, so the screen
        // needs no separate explainer or footer.
        mBoot = new SwitchPreference(c);
        mBoot.setPersistent(false);
        mBoot.setTitle(R.string.subsystem_boot_default);
        mBoot.setOnPreferenceChangeListener((p, value) -> {
            final boolean enabled = (Boolean) value;
            // Write the fallback explicitly before making the subsystem the default OS.
            if (enabled && !save(SubsystemBackend.DEFAULT_TARGET, SubsystemBackend.defaultTarget())) {
                return false;
            }
            if (!save(SubsystemBackend.DEFAULT, enabled ? "subsystem" : "android")) return false;
            refresh();
            return true;
        });
        screen.addPreference(mBoot);

        // The default target only matters when the subsystem boots automatically, so it
        // greys out while the switch is off.
        mTarget = new ListPreference(c);
        mTarget.setPersistent(false);
        mTarget.setTitle(R.string.subsystem_default_target);
        mTarget.setDialogTitle(R.string.subsystem_default_target);
        mTarget.setEntries(new CharSequence[] {getString(R.string.subsystem_target_es),
                getString(R.string.subsystem_target_pm)});
        mTarget.setEntryValues(new CharSequence[] {"ES", "PM"});
        mTarget.setOnPreferenceChangeListener((p, value) -> {
            if (!save(SubsystemBackend.DEFAULT_TARGET, (String) value)) return false;
            refresh();
            return true;
        });
        screen.addPreference(mTarget);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private boolean save(String prop, String value) {
        if (SubsystemBackend.set(prop, value)) return true;
        Toast.makeText(getActivity(), R.string.subsystem_setting_failed, Toast.LENGTH_LONG).show();
        return false;
    }

    private void refresh() {
        final boolean enabled = "subsystem".equals(SubsystemBackend.get(SubsystemBackend.DEFAULT));
        mBoot.setChecked(enabled);
        mBoot.setSummary(enabled ? R.string.subsystem_boot_override
                : R.string.subsystem_boot_android_override);
        mTarget.setEnabled(enabled);
        mTarget.setValue(SubsystemBackend.defaultTarget());
        mTarget.setSummary(mTarget.getEntry());
    }
}
