package org.andr36oid.look;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.ImageView;

import java.util.List;

/** Settings > Personalization. Each row is a plain list, picked with the d-pad. */
public class LookFragment extends PreferenceFragment {

    private static final String DEFAULT = "";

    /** The boot animations, with a frame of each for the picker */
    private static final String[] BOOTANIM_VALUES = {Looks.BOOTANIM_LINEAGE,
            Looks.BOOTANIM_RETRO, Looks.BOOTANIM_ORBIT, Looks.BOOTANIM_SIGNAL,
            Looks.BOOTANIM_CARTRIDGE, Looks.BOOTANIM_XP};
    private static final int[] BOOTANIM_LABELS = {R.string.bootanim_lineage,
            R.string.bootanim_retro, R.string.bootanim_orbit, R.string.bootanim_signal,
            R.string.bootanim_cartridge, R.string.bootanim_xp};
    private static final int[] BOOTANIM_PREVIEWS = {R.drawable.bootanim_lineage,
            R.drawable.bootanim_retro, R.drawable.bootanim_orbit, R.drawable.bootanim_signal,
            R.drawable.bootanim_cartridge, R.drawable.bootanim_xp};

    private static int bootAnimationIndex() {
        final String current = Looks.bootAnimation();
        for (int i = 0; i < BOOTANIM_VALUES.length; i++) {
            if (BOOTANIM_VALUES[i].equals(current)) return i;
        }
        return 0;
    }

    private ListPreference mAccent, mSounds;
    private Preference mBootAnim;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.look);
        mAccent = (ListPreference) findPreference("accent");
        mSounds = (ListPreference) findPreference("sounds");
        mBootAnim = findPreference("bootanim");

        mAccent.setOnPreferenceChangeListener((pref, value) -> {
            // SystemUI applies it; every screen, this one too, redraws in the new colour
            Looks.setAccent(getContext(), (String) value);
            return true;
        });
        mSounds.setOnPreferenceChangeListener((pref, value) -> {
            final boolean retro = "retro".equals(value);
            if (retro == Looks.retroSoundsOn()) return false;
            Looks.setRetroSounds(getContext(), retro);
            refresh();
            return false;
        });
        mBootAnim.setOnPreferenceClickListener(pref -> {
            showBootAnimations();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** A list like the other rows', with a frame of each animation next to its name. */
    private void showBootAnimations() {
        final Context context = getContext();
        final int current = bootAnimationIndex();
        final String[] labels = new String[BOOTANIM_LABELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(BOOTANIM_LABELS[i]);
        final LayoutInflater inflater = LayoutInflater.from(context);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, 0,
                labels) {
            @Override
            public View getView(int position, View view, ViewGroup parent) {
                if (view == null) view = inflater.inflate(R.layout.bootanim_item, parent, false);
                ((ImageView) view.findViewById(R.id.preview))
                        .setImageResource(BOOTANIM_PREVIEWS[position]);
                final CheckedTextView label = view.findViewById(R.id.label);
                label.setText(getItem(position));
                label.setChecked(position == current);
                return view;
            }
        };
        new AlertDialog.Builder(context)
                .setTitle(R.string.bootanim_title)
                .setSingleChoiceItems(adapter, current, (dialog, which) -> {
                    Looks.setBootAnimation(BOOTANIM_VALUES[which]);
                    dialog.dismiss();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

        // UI sounds
        mSounds.setEnabled(Looks.retroSoundsInstalled());
        mSounds.setEntries(new CharSequence[] {
                getString(R.string.sounds_android), getString(R.string.sounds_retro)});
        mSounds.setEntryValues(new CharSequence[] {"android", "retro"});
        final boolean retro = Looks.retroSoundsOn();
        mSounds.setValue(retro ? "retro" : "android");
        mSounds.setSummary(getString(R.string.sounds_summary,
                getString(retro ? R.string.sounds_retro : R.string.sounds_android)));

        // Boot animation
        mBootAnim.setSummary(getString(R.string.bootanim_summary,
                getString(BOOTANIM_LABELS[bootAnimationIndex()])));
    }
}
