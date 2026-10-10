package org.andr36oid.gamecomfort;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

import org.andr36oid.gamecomfort.resume.Target;

public class GameModeFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_NOW = "game_mode_now";
    private static final String KEY_CONTINUE_NOW = "continue_now";

    private SwitchPreference mNow;
    private SwitchPreference mAutomatic;
    private Preference mContinueNow;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.game_mode);
        mNow = (SwitchPreference) findPreference(KEY_NOW);
        mNow.setOnPreferenceChangeListener(this);
        mAutomatic = (SwitchPreference) findPreference(GameMode.KEY_AUTOMATIC);
        mAutomatic.setOnPreferenceChangeListener(this);
        mContinueNow = findPreference(KEY_CONTINUE_NOW);
        mContinueNow.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(getActivity(), ContinueActivity.class));
            return true;
        });
        if (!hasProfiles()) {
            getPreferenceScreen().removePreference(findPreference(GameMode.KEY_PROFILE));
            getPreferenceScreen().removePreference(findPreference(GameMode.KEY_STEADY_MEMORY));
        }
    }

    private boolean hasProfiles() {
        try {
            getActivity().getPackageManager().getServiceInfo(GameMode.PROFILES_TILE,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // The tile or automatic mode may have switched it in the meantime.
        mNow.setChecked(GameMode.isActive(getActivity()));
        showContinue(Continue.cached(getActivity()));
        // Look again, the game may have changed since
        final Context app = getActivity().getApplicationContext();
        Continue.BACKGROUND.execute(() -> {
            final Target target = Continue.refresh(app);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded()) {
                        showContinue(target);
                    }
                });
            }
        });
    }

    private void showContinue(Target target) {
        if (target == null) {
            mContinueNow.setTitle(R.string.continue_now_none);
            mContinueNow.setSummary(null);
            mContinueNow.setEnabled(false);
            return;
        }
        mContinueNow.setEnabled(true);
        mContinueNow.setTitle(getString(R.string.continue_now, target.label));
        switch (target.kind) {
            case Target.RETROARCH:
                mContinueNow.setSummary("RetroArch, " + target.coreName);
                break;
            case Target.PPSSPP:
                mContinueNow.setSummary(R.string.continue_now_ppsspp);
                break;
            default:
                mContinueNow.setSummary(R.string.continue_now_app);
                break;
        }
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
        // The tile says "Automatic" while game mode is off
        GameMode.updateTile(context);
        return false;
    }
}
