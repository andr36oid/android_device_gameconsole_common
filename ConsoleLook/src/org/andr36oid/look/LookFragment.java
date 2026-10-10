package org.andr36oid.look;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.List;

/** Settings > Display > Console look. Each row is a plain list, picked with the d-pad. */
public class LookFragment extends PreferenceFragment {

    private static final String DEFAULT = "";

    private ListPreference mAccent, mWallpaper, mSounds, mBootAnim;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.look);
        mAccent = (ListPreference) findPreference("accent");
        mWallpaper = (ListPreference) findPreference("wallpaper");
        mSounds = (ListPreference) findPreference("sounds");
        mBootAnim = (ListPreference) findPreference("bootanim");

        mAccent.setOnPreferenceChangeListener((pref, value) -> {
            // SystemUI applies it; every screen, this one too, redraws in the new colour
            Looks.setAccent(getContext(), (String) value);
            return true;
        });
        mWallpaper.setOnPreferenceChangeListener((pref, value) -> {
            final Context context = getContext().getApplicationContext();
            final String name = (String) value;
            // Decoding and saving a picture takes a moment, keep it off the UI thread
            new Thread(() -> {
                final boolean ok = Looks.setWallpaper(context, name);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (!ok) {
                        Toast.makeText(context, R.string.wallpaper_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                    if (isAdded()) refresh();
                });
            }, "ConsoleLookWallpaper").start();
            return false;
        });
        mSounds.setOnPreferenceChangeListener((pref, value) -> {
            final boolean retro = "retro".equals(value);
            if (retro == Looks.retroSoundsOn(getContext())) return false;
            Looks.setRetroSounds(getContext(), retro);
            refresh();
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.sounds_restart_title)
                    .setMessage(R.string.sounds_restart_message)
                    .setPositiveButton(R.string.restart_now, (d, w) ->
                            getContext().getSystemService(PowerManager.class).reboot(null))
                    .setNegativeButton(R.string.later, null)
                    .show();
            return false;
        });
        mBootAnim.setOnPreferenceChangeListener((pref, value) -> {
            Looks.setRetroBootAnimation(Looks.BOOTANIM_RETRO.equals(value));
            refresh();
            return false;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        final Context context = getContext();

        // Accent: the R36S colours first, then LineageOS/AOSP ones
        final List<Looks.Accent> accents = Looks.accents(context);
        final CharSequence[] accentLabels = new CharSequence[accents.size() + 1];
        final CharSequence[] accentValues = new CharSequence[accents.size() + 1];
        accentLabels[0] = getString(R.string.accent_default);
        accentValues[0] = DEFAULT;
        String accentSummary = accentLabels[0].toString();
        final String accent = Looks.currentAccent(context);
        for (int i = 0; i < accents.size(); i++) {
            accentLabels[i + 1] = accents.get(i).label;
            accentValues[i + 1] = accents.get(i).pkg;
            if (accents.get(i).pkg.equals(accent)) accentSummary = accents.get(i).label;
        }
        mAccent.setEntries(accentLabels);
        mAccent.setEntryValues(accentValues);
        mAccent.setValue(accent);
        mAccent.setSummary(accentSummary);

        // Wallpaper
        final Resources res = Looks.wallpaperResources(context);
        final String[] names = res == null ? new String[0] : Looks.wallpaperNames(res);
        final String[] titles = res == null ? new String[0] : Looks.wallpaperTitles(res, names);
        final CharSequence[] wpLabels = new CharSequence[names.length + 1];
        final CharSequence[] wpValues = new CharSequence[names.length + 1];
        wpLabels[0] = getString(R.string.wallpaper_default);
        wpValues[0] = DEFAULT;
        for (int i = 0; i < names.length; i++) {
            wpLabels[i + 1] = titles[i];
            wpValues[i + 1] = names[i];
        }
        mWallpaper.setEntries(wpLabels);
        mWallpaper.setEntryValues(wpValues);
        final String wallpaper = Looks.currentWallpaper(context);
        mWallpaper.setValue(wallpaper);
        String wpSummary = getString(R.string.wallpaper_other);
        if (wallpaper != null) {
            for (int i = 0; i < wpValues.length; i++) {
                if (TextUtils.equals(wpValues[i], wallpaper)) wpSummary = wpLabels[i].toString();
            }
        }
        mWallpaper.setSummary(wpSummary);

        // UI sounds
        mSounds.setEnabled(Looks.retroSoundsInstalled(context));
        mSounds.setEntries(new CharSequence[] {
                getString(R.string.sounds_android), getString(R.string.sounds_retro)});
        mSounds.setEntryValues(new CharSequence[] {"android", "retro"});
        final boolean retro = Looks.retroSoundsOn(context);
        mSounds.setValue(retro ? "retro" : "android");
        mSounds.setSummary(getString(R.string.sounds_summary,
                getString(retro ? R.string.sounds_retro : R.string.sounds_android)));

        // Boot animation
        mBootAnim.setEntries(new CharSequence[] {
                getString(R.string.bootanim_lineage), getString(R.string.bootanim_retro)});
        mBootAnim.setEntryValues(new CharSequence[] {
                Looks.BOOTANIM_LINEAGE, Looks.BOOTANIM_RETRO});
        final boolean retroAnim = Looks.retroBootAnimation();
        mBootAnim.setValue(retroAnim ? Looks.BOOTANIM_RETRO : Looks.BOOTANIM_LINEAGE);
        mBootAnim.setSummary(getString(R.string.bootanim_summary,
                getString(retroAnim ? R.string.bootanim_retro : R.string.bootanim_lineage)));
    }
}
