package org.andr36oid.rgblights;

import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;

public class RgbLightsFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_COLOUR = "colour";

    private ListPreference mColour;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.rgb_lights_settings);
        mColour = (ListPreference) findPreference(KEY_COLOUR);
        mColour.setOnPreferenceChangeListener(this);
        if (!RgbLights.isPresent()) {
            mColour.setEnabled(false);
            mColour.setSummary(R.string.rgb_missing);
            return;
        }
        String current = RgbLights.get();
        if (mColour.findIndexOfValue(current) < 0) {
            current = RgbLights.OFF;
        }
        mColour.setValue(current);
        mColour.setSummary(mColour.getEntry());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String colour = (String) newValue;
        RgbLights.set(colour);
        mColour.setValue(colour);
        mColour.setSummary(mColour.getEntry());
        return false;
    }
}
