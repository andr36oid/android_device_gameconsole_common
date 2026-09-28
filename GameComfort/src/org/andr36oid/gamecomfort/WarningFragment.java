package org.andr36oid.gamecomfort;

import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;

public class WarningFragment extends PreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private Banner mBanner;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.battery_warning);
        mBanner = new Banner(getActivity().getApplicationContext());
        findPreference("preview").setOnPreferenceClickListener(this::preview);
    }

    @Override
    public void onResume() {
        super.onResume();
        Comfort.prefs(getActivity()).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        Comfort.prefs(getActivity()).unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (Comfort.KEY_BATTERY_WARNING.equals(key)) {
            Comfort.update(getActivity());
        }
    }

    /** Shows the banner with the level right now, so it's clear what it looks like. */
    private boolean preview(Preference preference) {
        final int percent = getActivity().getSystemService(BatteryManager.class)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        Comfort.vibrate(getActivity());
        mBanner.show(getString(R.string.banner_text,
                percent >= 0 && percent <= 100 ? percent : 5));
        return true;
    }
}
