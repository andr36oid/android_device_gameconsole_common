package org.andr36oid.cpuoverclock;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An on/off switch and one row per app that can be opened: Default (follows the chosen
 * profile), Battery saver, Balanced or Performance. Rows stay visible but greyed out while
 * the switch is off, so the suggested profiles can be seen before turning it on.
 */
public class AppProfilesFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_ENABLED = "app_profiles_enabled";

    private SwitchPreference mEnabled;
    private final List<ListPreference> mApps = new ArrayList<>();
    private CharSequence[] mNames;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context context = getActivity();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);
        mNames = getResources().getStringArray(R.array.profile_names);

        mEnabled = new SwitchPreference(context);
        mEnabled.setKey(KEY_ENABLED);
        mEnabled.setPersistent(false);
        mEnabled.setTitle(R.string.app_profiles_switch);
        mEnabled.setSummary(R.string.app_profiles_switch_summary);
        mEnabled.setOnPreferenceChangeListener(this);
        screen.addPreference(mEnabled);

        final PreferenceCategory apps = new PreferenceCategory(context);
        apps.setTitle(R.string.app_profiles_apps);
        screen.addPreference(apps);
        final PackageManager pm = context.getPackageManager();
        final CharSequence[] values = new CharSequence[mNames.length + 1];
        values[0] = String.valueOf(AppProfileRules.FOLLOW);
        for (int i = 0; i < mNames.length; i++) {
            values[i + 1] = String.valueOf(i);
        }
        for (Map.Entry<String, ResolveInfo> app : AppProfiles.launchableApps(context)
                .entrySet()) {
            final ListPreference row = new ListPreference(context);
            row.setKey(app.getKey());
            row.setPersistent(false);
            row.setTitle(app.getValue().loadLabel(pm));
            row.setDialogTitle(row.getTitle());
            row.setIcon(app.getValue().loadIcon(pm));
            row.setEntryValues(values);
            row.setOnPreferenceChangeListener(this);
            apps.addPreference(row);
            mApps.add(row);
        }

        final Preference about = new Preference(context);
        about.setSelectable(false);
        about.setSummary(R.string.app_profiles_about);
        screen.addPreference(about);
    }

    @Override
    public void onResume() {
        super.onResume();
        // The chosen profile may have changed, it is named in "Default"
        update();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mEnabled) {
            AppProfiles.setEnabled(getActivity(), (Boolean) newValue);
        } else {
            AppProfiles.setChoice(getActivity(), preference.getKey(),
                    Integer.parseInt((String) newValue));
        }
        update();
        return false;
    }

    private void update() {
        final Context context = getActivity();
        final boolean on = AppProfiles.isEnabled(context);
        mEnabled.setChecked(on);
        final String follow = getString(R.string.app_profile_default,
                mNames[new PerformanceProfiles(context).get()]);
        final CharSequence[] entries = new CharSequence[mNames.length + 1];
        entries[0] = follow;
        System.arraycopy(mNames, 0, entries, 1, mNames.length);
        for (ListPreference row : mApps) {
            final int profile = AppProfiles.getChoice(context, row.getKey());
            row.setEntries(entries);
            row.setValue(String.valueOf(profile));
            row.setSummary(AppProfileRules.valid(profile) ? mNames[profile] : follow);
            row.setEnabled(on);
        }
    }
}
