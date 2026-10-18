package org.andr36oid.cpuoverclock;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.SystemProperties;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.widget.Toast;

public class OverclockFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_PROFILE = "profile";
    private static final String KEY_PROFILE_ABOUT = "profile_about";
    private static final String KEY_CPU_CAP = "cpu_cap";
    private static final String KEY_APP_PROFILES = "app_profiles";
    private static final String KEY_CLOSE_APPS = "close_apps_on_game";
    /** Read by GameComfort when a game comes to the front */
    private static final String SETTING_CLOSE_APPS = "andr36oid_close_apps_on_game";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_UNDERVOLT_CATEGORY = "undervolt_category";
    private static final String KEY_UNDERVOLT = "undervolt";
    private static final String KEY_GPU_RAM_CATEGORY = "gpu_ram_category";
    private static final String KEY_GPU_OC = "gpu_oc";
    private static final String KEY_RAM_OC = "ram_oc";

    private Overclock mOverclock;
    private PerformanceProfiles mProfiles;
    private ListPreference mProfile;
    private Preference mProfileAbout;
    private ListPreference mCpuCap;
    private SwitchPreference mEnabled;
    private ListPreference mSpeed;
    private Undervolt mUndervolt;
    private ListPreference mUndervoltLevel;
    private GpuRamOverclock mGpuRam;
    private SwitchPreference mGpuOc;
    private ListPreference mRamOc;

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
        setUpGpuRam();
        findPreference(KEY_CLOSE_APPS).setOnPreferenceChangeListener(this);

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
        if (KEY_CLOSE_APPS.equals(preference.getKey())) {
            Settings.Global.putInt(getActivity().getContentResolver(), SETTING_CLOSE_APPS,
                    (Boolean) newValue ? 1 : 0);
            update();
            return false;
        }
        if (preference == mGpuOc || preference == mRamOc) {
            final String dev = preference == mGpuOc ? GpuRamOverclock.GPU : GpuRamOverclock.DMC;
            final int hz = preference == mGpuOc
                    ? ((Boolean) newValue ? GpuRamOverclock.getSpeeds(dev)[0] : 0)
                    : Integer.parseInt((String) newValue);
            if (GpuRamOverclock.isExperimental(hz)) {
                // The experimental memory speed only after the warning
                new AlertDialog.Builder(getActivity())
                        .setTitle(R.string.ram_oc_warning_title)
                        .setMessage(R.string.ram_oc_warning)
                        .setPositiveButton(R.string.ram_oc_warning_ok,
                                (d, w) -> setGpuRam(dev, hz))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                setGpuRam(dev, hz);
            }
            return false;
        }
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
        updateGpuRam();
        updateAppProfiles();
        ((SwitchPreference) findPreference(KEY_CLOSE_APPS)).setChecked(
                Settings.Global.getInt(getActivity().getContentResolver(), SETTING_CLOSE_APPS,
                        0) != 0);

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

    private void setUpGpuRam() {
        mGpuRam = new GpuRamOverclock(getActivity());
        final int[] gpu = GpuRamOverclock.getSpeeds(GpuRamOverclock.GPU);
        final int[] ram = GpuRamOverclock.getSpeeds(GpuRamOverclock.DMC);
        if (gpu.length == 0 && ram.length == 0) {
            getPreferenceScreen().removePreference(findPreference(KEY_GPU_RAM_CATEGORY));
            return;
        }
        mGpuOc = (SwitchPreference) findPreference(KEY_GPU_OC);
        mRamOc = (ListPreference) findPreference(KEY_RAM_OC);
        if (gpu.length == 0) {
            mGpuOc.setEnabled(false);
            mGpuOc.setSummary(R.string.gpu_ram_unsupported);
        } else {
            mGpuOc.setOnPreferenceChangeListener(this);
        }
        if (ram.length == 0) {
            mRamOc.setEnabled(false);
            mRamOc.setSummary(R.string.gpu_ram_unsupported);
            return;
        }
        // Off (the stock top), then the overclock speeds, lowest first
        final CharSequence[] entries = new CharSequence[ram.length + 1];
        final CharSequence[] values = new CharSequence[ram.length + 1];
        entries[0] = getString(R.string.ram_oc_off, ramStockMhz());
        values[0] = "0";
        for (int i = 0; i < ram.length; i++) {
            entries[i + 1] = ramLabel(ram[i]);
            values[i + 1] = String.valueOf(ram[i]);
        }
        mRamOc.setEntries(entries);
        mRamOc.setEntryValues(values);
        mRamOc.setOnPreferenceChangeListener(this);
    }

    private String ramLabel(int hz) {
        final String mhz = getString(R.string.speed_mhz, GpuRamOverclock.realMhz(hz));
        return GpuRamOverclock.isExperimental(hz)
                ? getString(R.string.ram_oc_experimental, mhz) : mhz;
    }

    private String ramStockMhz() {
        return getString(R.string.speed_mhz, GpuRamOverclock.getStockMax(GpuRamOverclock.DMC,
                PerformanceProfiles.readAvailable(GpuRamOverclock.DMC)) / 1000000);
    }

    private void setGpuRam(String dev, int hz) {
        mGpuRam.setChosen(dev, hz);
        if (!mProfiles.apply()) {
            Toast.makeText(getActivity(), R.string.gpu_ram_failed, Toast.LENGTH_SHORT).show();
        }
        update();
    }

    private void updateGpuRam() {
        if (mGpuRam == null) {
            return;
        }
        if (mGpuOc != null && mGpuOc.isEnabled()) {
            final int[] gpu = GpuRamOverclock.getSpeeds(GpuRamOverclock.GPU);
            final int stock = GpuRamOverclock.getStockMax(GpuRamOverclock.GPU,
                    PerformanceProfiles.readAvailable(GpuRamOverclock.GPU));
            final boolean on = mGpuRam.getChosen(GpuRamOverclock.GPU) > 0;
            mGpuOc.setChecked(on);
            mGpuOc.setSummary(getString(on ? R.string.gpu_oc_on : R.string.gpu_oc_off,
                    getString(R.string.speed_mhz, gpu[gpu.length - 1] / 1000000),
                    getString(R.string.speed_mhz, stock / 1000000)));
        }
        if (mRamOc != null && mRamOc.isEnabled()) {
            final int hz = mGpuRam.getChosen(GpuRamOverclock.DMC);
            mRamOc.setValue(String.valueOf(hz));
            mRamOc.setSummary(hz == 0 ? getString(R.string.ram_oc_off, ramStockMhz())
                    : ramLabel(hz));
        }
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

    private void updateAppProfiles() {
        final Preference apps = findPreference(KEY_APP_PROFILES);
        if (!AppProfiles.isEnabled(getActivity())) {
            apps.setSummary(R.string.app_profiles_off);
            return;
        }
        final int count = AppProfiles.countSet(getActivity());
        apps.setSummary(getResources().getQuantityString(R.plurals.app_profiles_on, count,
                count));
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
