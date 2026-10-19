package org.andr36oid.perfoverlay;

import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

public class OverlayFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_RESET = "reset";

    private SwitchPreference mEnabled;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Hud.migrate(getActivity());
        load();
    }

    private void load() {
        addPreferencesFromResource(R.xml.overlay_settings);
        mEnabled = (SwitchPreference) findPreference(Hud.KEY_ENABLED);
        mEnabled.setOnPreferenceChangeListener(this);
        swatches((ListPreference) findPreference(Hud.KEY_LABEL_COLOR));
        swatches((ListPreference) findPreference(Hud.KEY_VALUE_COLOR));
    }

    @Override
    public void onResume() {
        super.onResume();
        // The tile or the notification may have switched it in the meantime.
        mEnabled.setChecked(Hud.isEnabled(getActivity()));
        final boolean blocked = Hud.isBlocked();
        mEnabled.setEnabled(!blocked);
        mEnabled.setSummary(blocked ? getString(R.string.overlay_blocked) : null);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        // The preference saves the value itself, this only starts or stops the overlay.
        Hud.apply(getActivity(), (Boolean) newValue);
        return true;
    }

    @Override
    public boolean onPreferenceTreeClick(android.preference.PreferenceScreen screen,
            Preference preference) {
        if (KEY_RESET.equals(preference.getKey())) {
            Hud.resetLook(getActivity());
            // Rebuild the page so every setting shows its default again
            getPreferenceScreen().removeAll();
            load();
            mEnabled.setChecked(Hud.isEnabled(getActivity()));
            return true;
        }
        return super.onPreferenceTreeClick(screen, preference);
    }

    /** A dot in each colour in front of its name. */
    private void swatches(ListPreference list) {
        final CharSequence[] names = list.getEntries();
        final CharSequence[] values = list.getEntryValues();
        final CharSequence[] shown = new CharSequence[names.length];
        for (int i = 0; i < names.length; i++) {
            final SpannableStringBuilder entry = new SpannableStringBuilder("●  ");
            entry.setSpan(new ForegroundColorSpan(
                    HudFormat.color(getActivity(), values[i].toString())), 0, 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown[i] = entry.append(names[i]);
        }
        list.setEntries(shown);
    }
}
