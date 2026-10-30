package org.andr36oid.syncsettings;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import java.util.LinkedHashMap;
import java.util.Map;

public class SyncSettingsFragment extends PreferenceFragment {

    private static final String MASTER = "persist.andr36oid.subsystem_autosync";
    private static final String PREFIX = "persist.andr36oid.sync.";
    private final Map<String, SwitchPreference> mRows = new LinkedHashMap<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context c = getActivity();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);

        final SwitchPreference master = addSwitch(screen, MASTER, R.string.sync_master);
        master.setSummary(R.string.sync_master_summary);

        final PreferenceCategory what = category(screen, R.string.sync_what);
        addSwitch(what, PREFIX + "favorite", R.string.sync_favorite);
        addSwitch(what, PREFIX + "playcount", R.string.sync_playcount);
        addSwitch(what, PREFIX + "lastplayed", R.string.sync_lastplayed);
        addSwitch(what, PREFIX + "desc", R.string.sync_desc);
        addSwitch(what, PREFIX + "art", R.string.sync_art);

        final PreferenceCategory when = category(screen, R.string.sync_when);
        addSwitch(when, PREFIX + "on_session", R.string.sync_on_session);
        addSwitch(when, PREFIX + "at_boot", R.string.sync_at_boot);

        final PreferenceCategory retroarch = category(screen, R.string.sync_retroarch_category);
        final SwitchPreference share = addSwitch(retroarch, PREFIX + "retroarch",
                R.string.sync_retroarch);
        share.setSummary(R.string.sync_retroarch_summary);

        final Preference footer = new Preference(c);
        footer.setSummary(R.string.sync_footer);
        footer.setSelectable(false);
        screen.addPreference(footer);
        footer.setDependency(MASTER);
    }

    @Override
    public void onResume() {
        super.onResume();
        for (Map.Entry<String, SwitchPreference> entry : mRows.entrySet()) {
            entry.getValue().setChecked(enabled(entry.getKey()));
        }
    }

    private boolean enabled(String prop) {
        final String value = SystemProperties.get(prop, "");
        return (PREFIX + "retroarch").equals(prop) ? "1".equals(value) : !"0".equals(value);
    }

    private PreferenceCategory category(PreferenceScreen screen, int title) {
        final PreferenceCategory group = new PreferenceCategory(getActivity());
        group.setTitle(title);
        screen.addPreference(group);
        group.setDependency(MASTER);
        return group;
    }

    private SwitchPreference addSwitch(PreferenceGroup group, String prop, int title) {
        final SwitchPreference row = new SwitchPreference(getActivity());
        row.setKey(prop);
        row.setPersistent(false);
        row.setTitle(title);
        row.setChecked(enabled(prop));
        row.setOnPreferenceChangeListener((p, value) -> {
            SystemProperties.set(prop, (Boolean) value ? "1" : "0");
            return true;
        });
        group.addPreference(row);
        if (!MASTER.equals(prop)) row.setDependency(MASTER);
        mRows.put(prop, row);
        return row;
    }
}
