package org.andr36oid.keyboard;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.widget.Toast;

public class SettingsFragment extends PreferenceFragment {
    private Preference which;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getPreferenceManager().setSharedPreferencesName(GamepadIme.PREFS);
        addPreferencesFromResource(R.xml.settings);
        which = findPreference("which_keyboard");
        which.setOnPreferenceClickListener(p -> {
            if (ImeSwitch.selfIsCurrent(getActivity())) {
                if (!ImeSwitch.leanbackInstalled(getActivity())) {
                    Toast.makeText(getActivity(), R.string.no_other_keyboard,
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                ImeSwitch.makeDefault(getActivity(), ImeSwitch.LEANBACK);
            } else {
                ImeSwitch.makeDefault(getActivity(), ImeSwitch.SELF);
            }
            updateWhich();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        updateWhich();
    }

    private void updateWhich() {
        boolean self = ImeSwitch.selfIsCurrent(getActivity());
        which.setTitle(self ? R.string.which_self : R.string.which_other);
        which.setSummary(self ? R.string.which_self_summary : R.string.which_other_summary);
    }
}
