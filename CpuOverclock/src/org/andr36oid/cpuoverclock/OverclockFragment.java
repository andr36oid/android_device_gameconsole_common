package org.andr36oid.cpuoverclock;

import android.os.Bundle;
import android.os.SystemProperties;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.widget.Toast;

public class OverclockFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_PROFILE = "profile";
    private static final String KEY_PROFILE_ABOUT = "profile_about";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SPEED = "speed";

    private Overclock mOverclock;
    private PerformanceProfiles mProfiles;
    private ListPreference mProfile;
    private Preference mProfileAbout;
    private SwitchPreference mEnabled;
    private ListPreference mSpeed;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mOverclock = new Overclock(getActivity());
        mProfiles = new PerformanceProfiles(getActivity());
        addPreferencesFromResource(R.xml.overclock_settings);
        mProfile = (ListPreference) findPreference(KEY_PROFILE);
        mProfileAbout = findPreference(KEY_PROFILE_ABOUT);
        mEnabled = (SwitchPreference) findPreference(KEY_ENABLED);
        mSpeed = (ListPreference) findPreference(KEY_SPEED);

        final CharSequence[] profileValues = new CharSequence[PerformanceProfiles.COUNT];
        for (int i = 0; i < profileValues.length; i++) {
            profileValues[i] = String.valueOf(i);
        }
        mProfile.setEntryValues(profileValues);
        mProfile.setOnPreferenceChangeListener(this);

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
        // The tiles or FN + R1 may have switched things in the meantime.
        update();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final boolean done;
        if (preference == mProfile) {
            done = mProfiles.set(Integer.parseInt((String) newValue));
        } else if (preference == mEnabled) {
            done = mOverclock.setOn((Boolean) newValue);
        } else {
            done = mOverclock.setChosenSpeed(Integer.parseInt((String) newValue));
        }
        if (!done) {
            Toast.makeText(getActivity(), preference == mProfile
                    ? R.string.profile_failed : R.string.overclock_failed,
                    Toast.LENGTH_SHORT).show();
        }
        // Shows what the kernel ended up with rather than what was asked for.
        update();
        return false;
    }

    private void update() {
        final int profile = mProfiles.get();
        mProfile.setValue(String.valueOf(profile));
        mProfile.setSummary(mProfiles.getName(profile));
        mProfileAbout.setSummary(describe(profile));

        if (!mOverclock.isSupported()) {
            return;
        }
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

    private String describe(int profile) {
        final String text;
        switch (profile) {
            case PerformanceProfiles.BATTERY_SAVER:
                text = getString(R.string.profile_about_saver,
                        mOverclock.formatSpeed(mProfiles.getSaverCpuSpeed()),
                        mProfiles.getGpuLowestMhz());
                break;
            case PerformanceProfiles.PERFORMANCE:
                text = getString(R.string.profile_about_performance,
                        mOverclock.formatSpeed(mProfiles.getPerformanceCpuSpeed()),
                        mProfiles.getGpuHighestMhz());
                break;
            default:
                text = getString(R.string.profile_about_balanced);
                break;
        }
        return text + getString(SystemProperties.getBoolean("ro.andr36oid.fn_hotkeys", false)
                ? R.string.profile_about_footer_fn : R.string.profile_about_footer);
    }
}
