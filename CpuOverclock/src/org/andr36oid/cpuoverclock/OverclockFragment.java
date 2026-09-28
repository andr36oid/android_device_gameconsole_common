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
    private static final String KEY_CPU_CAP = "cpu_cap";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_UNDERVOLT_CATEGORY = "undervolt_category";
    private static final String KEY_UNDERVOLT = "undervolt";

    private Overclock mOverclock;
    private PerformanceProfiles mProfiles;
    private ListPreference mProfile;
    private Preference mProfileAbout;
    private ListPreference mCpuCap;
    private SwitchPreference mEnabled;
    private ListPreference mSpeed;
    private Undervolt mUndervolt;
    private ListPreference mUndervoltLevel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mOverclock = new Overclock(getActivity());
        mProfiles = new PerformanceProfiles(getActivity());
        addPreferencesFromResource(R.xml.overclock_settings);
        mProfile = (ListPreference) findPreference(KEY_PROFILE);
        mProfileAbout = findPreference(KEY_PROFILE_ABOUT);
        mCpuCap = (ListPreference) findPreference(KEY_CPU_CAP);
        mEnabled = (SwitchPreference) findPreference(KEY_ENABLED);
        mSpeed = (ListPreference) findPreference(KEY_SPEED);
        setUpUndervolt();

        final CharSequence[] profileValues = new CharSequence[PerformanceProfiles.COUNT];
        for (int i = 0; i < profileValues.length; i++) {
            profileValues[i] = String.valueOf(i);
        }
        mProfile.setEntryValues(profileValues);
        mProfile.setOnPreferenceChangeListener(this);

        // Stock first, then the standard speeds below the top, highest first.
        final int[] caps = mProfiles.getCpuCapChoices();
        final CharSequence[] capEntries = new CharSequence[caps.length + 1];
        final CharSequence[] capValues = new CharSequence[caps.length + 1];
        capEntries[0] = getString(R.string.cpu_cap_stock,
                mProfiles.formatMhz(mOverclock.getStandardSpeed()));
        capValues[0] = "0";
        for (int i = 0; i < caps.length; i++) {
            capEntries[i + 1] = mProfiles.formatMhz(caps[i]);
            capValues[i + 1] = String.valueOf(caps[i]);
        }
        mCpuCap.setEntries(capEntries);
        mCpuCap.setEntryValues(capValues);
        if (caps.length == 0) {
            mCpuCap.setEnabled(false);
        }
        mCpuCap.setOnPreferenceChangeListener(this);

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
        } else if (preference == mCpuCap) {
            done = mProfiles.setCpuCap(Integer.parseInt((String) newValue));
        } else if (preference == mUndervoltLevel) {
            done = mUndervolt.set(Integer.parseInt((String) newValue));
        } else if (preference == mEnabled) {
            done = mOverclock.setOn((Boolean) newValue);
        } else {
            done = mOverclock.setChosenSpeed(Integer.parseInt((String) newValue));
        }
        if (!done) {
            Toast.makeText(getActivity(), preference == mProfile
                    ? R.string.profile_failed : preference == mUndervoltLevel
                    ? R.string.undervolt_failed : R.string.overclock_failed,
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
        updateCpuCap();
        updateUndervolt();

        if (!mOverclock.isSupported()) {
            return;
        }
        final boolean on = mOverclock.isOn();
        mEnabled.setChecked(on);
        mEnabled.setSummary(on
                ? getString(R.string.overclock_switch_on,
                        mOverclock.formatSpeed(mOverclock.getMaxSpeed()))
                : getString(R.string.overclock_switch_off,
                        mOverclock.formatSpeed(mOverclock.getMaxSpeed())));
        final int chosen = mOverclock.getChosenSpeed();
        mSpeed.setValue(String.valueOf(chosen));
        mSpeed.setSummary(mOverclock.formatSpeed(chosen));
    }

    private void setUpUndervolt() {
        mUndervolt = new Undervolt();
        if (!mUndervolt.isSupported()) {
            getPreferenceScreen().removePreference(findPreference(KEY_UNDERVOLT_CATEGORY));
            return;
        }
        mUndervoltLevel = (ListPreference) findPreference(KEY_UNDERVOLT);
        final int[] steps = Undervolt.STEPS_UV;
        final CharSequence[] entries = new CharSequence[steps.length];
        final CharSequence[] values = new CharSequence[steps.length];
        for (int i = 0; i < steps.length; i++) {
            entries[i] = steps[i] == 0 ? getString(R.string.undervolt_off)
                    : getString(R.string.undervolt_step, steps[i] / 1000);
            values[i] = String.valueOf(steps[i]);
        }
        mUndervoltLevel.setEntries(entries);
        mUndervoltLevel.setEntryValues(values);
        mUndervoltLevel.setOnPreferenceChangeListener(this);
    }

    private void updateUndervolt() {
        if (mUndervoltLevel == null) {
            return;
        }
        final int uv = mUndervolt.get();
        mUndervoltLevel.setValue(String.valueOf(uv));
        mUndervoltLevel.setSummary(uv <= 0 ? getString(R.string.undervolt_off)
                : getString(R.string.undervolt_step, uv / 1000));
    }

    private void updateCpuCap() {
        final int cap = mProfiles.getCpuCap();
        mCpuCap.setValue(String.valueOf(cap));
        final String state;
        if (cap == 0) {
            state = getString(R.string.cpu_cap_none);
        } else if (mOverclock.isOn()) {
            state = getString(R.string.cpu_cap_on_hold, mProfiles.formatMhz(cap));
        } else if (mProfiles.getActiveCpuCap() > 0) {
            state = getString(R.string.cpu_cap_active,
                    mProfiles.formatMhz(mProfiles.getActiveCpuCap()));
        } else {
            state = getString(R.string.cpu_cap_profile_lower, mProfiles.formatMhz(cap),
                    mProfiles.formatMhz(mProfiles.getProfileCpuMax()));
        }
        mCpuCap.setSummary(state + "\n" + getString(R.string.cpu_cap_note));
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
