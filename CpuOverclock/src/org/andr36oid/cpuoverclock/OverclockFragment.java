package org.andr36oid.cpuoverclock;

import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.widget.Toast;

public class OverclockFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SPEED = "speed";

    private Overclock mOverclock;
    private SwitchPreference mEnabled;
    private ListPreference mSpeed;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mOverclock = new Overclock(getActivity());
        addPreferencesFromResource(R.xml.overclock_settings);
        mEnabled = (SwitchPreference) findPreference(KEY_ENABLED);
        mSpeed = (ListPreference) findPreference(KEY_SPEED);

        if (!mOverclock.isSupported()) {
            mEnabled.setEnabled(false);
            mEnabled.setSummary(R.string.overclock_unsupported);
            mSpeed.setEnabled(false);
            return;
        }
        final int[] speeds = mOverclock.getSpeeds();
        final CharSequence[] entries = new CharSequence[speeds.length];
        final CharSequence[] values = new CharSequence[speeds.length];
        for (int i = 0; i < speeds.length; i++) {
            entries[i] = mOverclock.formatSpeed(speeds[i]);
            values[i] = String.valueOf(speeds[i]);
        }
        mSpeed.setEntries(entries);
        mSpeed.setEntryValues(values);
        mEnabled.setOnPreferenceChangeListener(this);
        mSpeed.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        // The tile may have switched it in the meantime.
        if (mOverclock.isSupported()) {
            update();
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final boolean done;
        if (preference == mEnabled) {
            done = mOverclock.setOn((Boolean) newValue);
        } else {
            done = mOverclock.setChosenSpeed(Integer.parseInt((String) newValue));
        }
        if (!done) {
            Toast.makeText(getActivity(), R.string.overclock_failed, Toast.LENGTH_SHORT).show();
        }
        // Shows what the kernel ended up with rather than what was asked for.
        update();
        return false;
    }

    private void update() {
        final boolean on = mOverclock.isOn();
        mEnabled.setChecked(on);
        mEnabled.setSummary(on
                ? getString(R.string.overclock_switch_on,
                        mOverclock.formatSpeed(mOverclock.getMaxSpeed()))
                : getString(R.string.overclock_switch_off,
                        mOverclock.formatSpeed(mOverclock.getStandardSpeed())));
        final int chosen = mOverclock.getChosenSpeed();
        mSpeed.setValue(String.valueOf(chosen));
        mSpeed.setSummary(mOverclock.formatSpeed(chosen));
    }
}
