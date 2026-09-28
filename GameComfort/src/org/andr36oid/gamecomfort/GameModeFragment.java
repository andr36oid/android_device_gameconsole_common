package org.andr36oid.gamecomfort;

import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

public class GameModeFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_NOW = "game_mode_now";

    private SwitchPreference mNow;
    private SwitchPreference mAutomatic;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.game_mode);
        mNow = (SwitchPreference) findPreference(KEY_NOW);
        mNow.setOnPreferenceChangeListener(this);
        mAutomatic = (SwitchPreference) findPreference(GameMode.KEY_AUTOMATIC);
        mAutomatic.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        // The tile or automatic mode may have switched it in the meantime.
        mNow.setChecked(GameMode.isActive(getActivity()));
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getActivity();
        final boolean on = (Boolean) newValue;
        if (preference == mNow) {
            GameMode.setActive(context, on, false);
            return true;
        }
        // Saved now rather than after returning, the service reads it right away.
        mAutomatic.setChecked(on);
        if (!on && GameMode.isOnByAutomatic(context)) {
            GameMode.setActive(context, false, true);
            mNow.setChecked(false);
        }
        Comfort.update(context);
        return false;
    }
}
