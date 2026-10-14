package org.andr36oid.kerneltweaks;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings > System > Kernel Tweaks. A choice is saved in persist.andr36oid.kt.<key> (or
 * sys.andr36oid.kt.<key> for the ones that only last until a restart), empty for the
 * default; the root helper andr36oid-ktweaks.sh writes it to the kernel. The helper puts the
 * boot defaults in sys.andr36oid.ktd.<key> and what the kernel took in
 * sys.andr36oid.ktc.<key>, so this page only reads properties.
 */
public class KernelTweaksFragment extends PreferenceFragment {

    private static final String SERVICE = "andr36oid-ktweaks";
    private static final String PAUSED_PROP = "persist.andr36oid.kt.paused";
    private static final String REQUEST_PROP = "sys.andr36oid.kt.request";
    private static final String DONE_PROP = "sys.andr36oid.kt.done";
    private static final String FAILED_PROP = "sys.andr36oid.kt.failed";
    private static final String PREF_DOCS = "show_docs";
    private static final long POLL_MS = 200;
    private static final long WAIT_MS = 5000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<Tweak, Preference> mRows = new HashMap<>();
    private final List<Tweak> mTweaks = new ArrayList<>();
    private SharedPreferences mPrefs;
    private SwitchPreference mUse;
    private SwitchPreference mDocs;
    private Preference mReset;
    private String mWaitingFor;
    private long mWaitUntil;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context c = getActivity();
        mPrefs = c.getSharedPreferences("kernel_tweaks", Context.MODE_PRIVATE);
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);

        final Preference intro = new Preference(c);
        intro.setSummary(R.string.kt_intro);
        intro.setSelectable(false);
        screen.addPreference(intro);

        mUse = new SwitchPreference(c);
        mUse.setPersistent(false);
        mUse.setTitle(R.string.kt_use);
        mUse.setOnPreferenceChangeListener((p, value) -> {
            SystemProperties.set(PAUSED_PROP, (Boolean) value ? "0" : "1");
            apply();
            return true;
        });
        screen.addPreference(mUse);

        mDocs = new SwitchPreference(c);
        mDocs.setPersistent(false);
        mDocs.setTitle(R.string.kt_show_docs);
        mDocs.setSummary(R.string.kt_show_docs_summary);
        mDocs.setChecked(mPrefs.getBoolean(PREF_DOCS, false));
        mDocs.setOnPreferenceChangeListener((p, value) -> {
            mPrefs.edit().putBoolean(PREF_DOCS, (Boolean) value).apply();
            mHandler.post(this::refresh);
            return true;
        });
        screen.addPreference(mDocs);

        mReset = new Preference(c);
        mReset.setTitle(R.string.kt_reset);
        mReset.setSummary(R.string.kt_reset_summary);
        mReset.setOnPreferenceClickListener(p -> {
            new AlertDialog.Builder(c)
                .setMessage(R.string.kt_reset_confirm)
                .setPositiveButton(R.string.kt_reset, (d, w) -> {
                    for (Tweak t : mTweaks) SystemProperties.set(t.prop(), "");
                    apply();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        });
        screen.addPreference(mReset);

        for (Tweaks.Category cat : Tweaks.all()) {
            final PreferenceCategory group = new PreferenceCategory(c);
            group.setTitle(cat.title);
            screen.addPreference(group);
            if (cat.linkPackage != null) {
                final Intent intent = new Intent().setComponent(
                        new ComponentName(cat.linkPackage, cat.linkActivity));
                if (c.getPackageManager().resolveActivity(intent, 0) != null) {
                    final Preference link = new Preference(c);
                    link.setTitle(cat.linkTitle);
                    link.setSummary(cat.linkSummary);
                    link.setIntent(intent);
                    group.addPreference(link);
                }
            }
            for (Tweak t : cat.tweaks) {
                final Preference row = new Preference(c);
                row.setTitle(t.title);
                row.setOnPreferenceClickListener(p -> {
                    if (t.kind == Tweak.AXES) showAxes(t);
                    else showChoice(t);
                    return true;
                });
                group.addPreference(row);
                mRows.put(t, row);
                mTweaks.add(t);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    // ---------------------------------------------------------------------------- state

    private static String get(String prop) {
        return SystemProperties.get(prop, "").trim();
    }

    private static String def(Tweak t) {
        return get(t.defaultProp());
    }

    /** what the kernel has now: the helper's last report, else the boot default */
    private static String cur(Tweak t) {
        final String v = get(t.currentProp());
        return v.isEmpty() ? def(t) : v;
    }

    /** "1" switched off here, "crash" by the helper after two starts that didn't finish */
    private boolean paused() {
        final String v = get(PAUSED_PROP);
        return "1".equals(v) || "crash".equals(v);
    }

    private void refresh() {
        final Context c = getActivity();
        if (c == null) return;
        final boolean docs = mDocs.isChecked();
        final boolean paused = paused();
        final List<String> failed = Arrays.asList(get(FAILED_PROP).split("\\s+"));
        int changed = 0;
        int saved = 0;

        for (Tweak t : mTweaks) {
            final Preference row = mRows.get(t);
            final String def = def(t);
            final String cur = cur(t);
            final String want = get(t.prop());
            if (!want.isEmpty()) saved++;
            CharSequence summary;
            // what the next start gets: tweaks switched off start with the defaults
            final String next = paused || want.isEmpty() ? def : want;
            if (def.isEmpty()) {
                row.setEnabled(false);
                summary = getString(R.string.kt_unavailable);
            } else {
                row.setEnabled(true);
                final String curLabel = label(t, cur);
                if (!cur.equals(def)) changed++;
                if (t.atBoot && !next.equals(cur)) {
                    summary = getString(R.string.kt_after_restart, label(t, next), curLabel);
                } else if (!want.isEmpty() && !want.equals(cur) && failed.contains(t.key)) {
                    summary = getString(R.string.kt_failed, curLabel);
                } else if (paused && !t.once && !want.isEmpty()) {
                    summary = getString(R.string.kt_paused_value, label(t, want));
                } else if (!cur.equals(def)) {
                    summary = t.kind == Tweak.AXES ? curLabel
                            : getString(R.string.kt_changed, curLabel, label(t, def));
                    if (t.once) summary = summary + "\n" + getString(R.string.kt_once);
                } else {
                    summary = curLabel;
                }
            }
            if (docs) summary = summary + "\n" + getString(t.why);
            row.setSummary(summary);
        }

        mUse.setChecked(!paused);
        if (paused) {
            mUse.setSummary("crash".equals(get(PAUSED_PROP)) ? R.string.kt_paused_crash
                    : R.string.kt_use_off);
        } else if (changed == 0) {
            mUse.setSummary(R.string.kt_use_none);
        } else {
            mUse.setSummary(getResources().getQuantityString(R.plurals.kt_use_count, changed,
                    changed));
        }
        mReset.setEnabled(saved > 0 || changed > 0);
    }

    /** a start option's value for the next start */
    private String nextValue(Tweak t) {
        final String want = get(t.prop());
        return paused() || want.isEmpty() ? def(t) : want;
    }

    private String label(Tweak t, String value) {
        if (t.kind == Tweak.AXES) return Tweaks.axesLabel(getActivity(), value, def(t));
        return t.label(getActivity(), value);
    }

    // ---------------------------------------------------------------------------- changes

    /** save one tweak; the default is saved as "" so it follows the kernel */
    private void save(Tweak t, String value) {
        SystemProperties.set(t.prop(), value.equals(def(t)) ? "" : value);
        apply();
    }

    /** ask the root helper to write everything, and refresh once it's done */
    private void apply() {
        mWaitingFor = Long.toString(SystemClock.elapsedRealtime());
        mWaitUntil = SystemClock.uptimeMillis() + WAIT_MS;
        SystemProperties.set(REQUEST_PROP, mWaitingFor);
        SystemProperties.set("ctl.start", SERVICE);
        mHandler.removeCallbacksAndMessages(null);
        mHandler.postDelayed(this::waitForHelper, POLL_MS);
    }

    private void waitForHelper() {
        if (mWaitingFor.equals(get(DONE_PROP)) || SystemClock.uptimeMillis() > mWaitUntil) {
            refresh();
            return;
        }
        mHandler.postDelayed(this::waitForHelper, POLL_MS);
    }

    // ---------------------------------------------------------------------------- dialogs

    private void showChoice(Tweak t) {
        final String def = def(t);
        final String cur = cur(t);
        final List<String> values = new ArrayList<>(Arrays.asList(t.values));
        if (!values.contains(def)) values.add(0, def);
        final CharSequence[] labels = new CharSequence[values.size()];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            final String v = values.get(i);
            labels[i] = v.equals(def) ? getString(R.string.kt_default_mark, label(t, v))
                    : label(t, v);
            if (v.equals(t.atBoot ? nextValue(t) : cur)) checked = i;
        }
        new AlertDialog.Builder(getActivity())
            .setTitle(t.title)
            .setSingleChoiceItems(labels, checked, (d, which) -> {
                d.dismiss();
                save(t, values.get(which));
            })
            .setNeutralButton(R.string.kt_explain, (d, w) -> showDoc(t))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showAxes(Tweak t) {
        final boolean[] def = Tweaks.axes(def(t));
        final boolean[] cur = Tweaks.axes(cur(t));
        if (def == null || cur == null) return;
        final CharSequence[] names = new CharSequence[Tweaks.AXIS_NAMES.length];
        final boolean[] flipped = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = getString(Tweaks.AXIS_NAMES[i]);
            flipped[i] = cur[i] != def[i];
        }
        new AlertDialog.Builder(getActivity())
            .setTitle(t.title)
            .setMultiChoiceItems(names, flipped, (d, which, on) -> flipped[which] = on)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                final boolean[] out = new boolean[def.length];
                for (int i = 0; i < out.length; i++) out[i] = def[i] ^ flipped[i];
                save(t, Tweaks.axesValue(out));
            })
            .setNeutralButton(R.string.kt_explain, (d, w) -> showDoc(t))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showDoc(Tweak t) {
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        heading(sb, getString(R.string.kt_doc_what));
        sb.append(getString(t.what)).append("\n\n");
        heading(sb, getString(R.string.kt_doc_why));
        sb.append(getString(t.why)).append("\n\n");
        final String def = def(t);
        if (!def.isEmpty()) {
            sb.append(getString(R.string.kt_doc_default, t.kind == Tweak.AXES
                    ? getString(R.string.kt_stick_normal) : label(t, def))).append('\n');
        }
        if (t.once) sb.append(getString(R.string.kt_once)).append('\n');
        if (t.atBoot) {
            sb.append(getString(R.string.kt_doc_boot_option, t.file));
        } else {
            sb.append(getString(R.string.kt_doc_file, t.file));
        }
        new AlertDialog.Builder(getActivity())
            .setTitle(t.title)
            .setMessage(sb)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private static void heading(SpannableStringBuilder sb, String text) {
        final int start = sb.length();
        sb.append(text);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n');
    }
}
