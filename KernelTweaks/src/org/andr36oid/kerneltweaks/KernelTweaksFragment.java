package org.andr36oid.kerneltweaks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.text.style.StyleSpan;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    /** how long Restart waits for the helper to save the start options */
    private static final long REBOOT_WAIT_MS = 15000;
    /** risk dot colors, light enough for the dark theme: green, amber, red */
    private static final int[] LEVEL_COLORS = {0xff66bb6a, 0xffffca28, 0xffef5350};
    private static final int[] LEVEL_TEXTS = {
        R.string.kt_level_safe, R.string.kt_level_last_resort, R.string.kt_level_risky,
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<Tweak, Preference> mRows = new HashMap<>();
    private final List<Tweak> mTweaks = new ArrayList<>();
    private SharedPreferences mPrefs;
    private SwitchPreference mUse;
    private SwitchPreference mDocs;
    private Preference mReset;
    private Preference mExport;
    private Preference mImport;
    /** "storage", for the FUSE driver row, which stays on while storage uses FUSE */
    private Tweak mStorage;
    /** something was changed since the page opened or the restart question was asked */
    private boolean mChanged;
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
            mChanged = true;
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
                    mChanged = true;
                    apply();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        });
        screen.addPreference(mReset);

        mExport = new Preference(c);
        mExport.setTitle(R.string.kt_profile_export);
        mExport.setSummary(R.string.kt_profile_export_summary);
        mExport.setOnPreferenceClickListener(p -> {
            final String readme = getString(R.string.kt_profile_readme);
            profileTask(() -> Profiles.export(readme), path -> profileMessage(
                    getString(R.string.kt_profile_saved, path)));
            return true;
        });
        screen.addPreference(mExport);

        mImport = new Preference(c);
        mImport.setTitle(R.string.kt_profile_import);
        mImport.setSummary(R.string.kt_profile_import_summary);
        mImport.setOnPreferenceClickListener(p -> {
            profileTask(() -> Profiles.scan(mTweaks), this::showProfiles);
            return true;
        });
        screen.addPreference(mImport);

        // what the dots left of each tweak mean
        final Preference legend = new Preference(c);
        legend.setTitle(R.string.kt_legend);
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int level = Tweak.SAFE; level <= Tweak.RISKY; level++) {
            if (sb.length() > 0) sb.append('\n');
            levelLine(c, sb, level);
        }
        legend.setSummary(sb);
        legend.setSelectable(false);
        screen.addPreference(legend);

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
                    // lined up with the tweaks below it, which have a risk dot
                    link.setIconSpaceReserved(true);
                    group.addPreference(link);
                }
            }
            for (Tweak t : cat.tweaks) {
                final Preference row = new Preference(c);
                row.setTitle(t.title);
                row.setIcon(levelDot(c, t.level));
                row.setOnPreferenceClickListener(p -> {
                    if (t.kind == Tweak.AXES) showAxes(t);
                    else showChoice(t);
                    return true;
                });
                group.addPreference(row);
                mRows.put(t, row);
                mTweaks.add(t);
                if ("storage".equals(t.key)) mStorage = t;
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
            final String next = nextValue(t);
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
                if (fuseLocked(t)) {
                    row.setEnabled(false);
                    summary = getString(R.string.kt_fuse_needed) + "\n" + summary;
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

    /** the value for the next start (for start options; the others take it right away) */
    private String nextValue(Tweak t) {
        // the helper keeps the FUSE driver on while the next start uses FUSE storage
        if (fuseLocked(t)) return "on";
        final String want = get(t.prop());
        return paused() || want.isEmpty() ? def(t) : want;
    }

    /** the FUSE driver row while storage is set to FUSE: FUSE storage can't do without it */
    private boolean fuseLocked(Tweak t) {
        return "fuse".equals(t.key) && mStorage != null && "fuse".equals(nextValue(mStorage));
    }

    /** a start option whose value for the next start isn't the one running now */
    private boolean restartPending() {
        for (Tweak t : mTweaks) {
            if (t.atBoot && !def(t).isEmpty() && !nextValue(t).equals(cur(t))) return true;
        }
        return false;
    }

    private String label(Tweak t, String value) {
        if (t.kind == Tweak.AXES) return Tweaks.axesLabel(getActivity(), value, def(t));
        if ("cma_size".equals(t.key) && "default".equals(value)) {
            // The kernel config reserves 128 MB. boot.ini uses 64 MB on 512 MB
            // consoles; the helper keeps that base value separate from tweaks.
            final String base = def(t);
            final String size = base.isEmpty() || "default".equals(base) ? "128" : base;
            return getString(R.string.kt_cma_default, getString(R.string.kt_unit_mb, size));
        }
        return t.label(getActivity(), value);
    }

    // ---------------------------------------------------------------------------- changes

    /** save one tweak; the default is saved as "" so it follows the kernel */
    private void save(Tweak t, String value) {
        SystemProperties.set(t.prop(), value.equals(def(t)) ? "" : value);
        mChanged = true;
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

    // ---------------------------------------------------------------------------- profiles

    private interface ProfileWork<T> {
        T run() throws IOException;
    }

    private <T> void profileTask(ProfileWork<T> work, Consumer<T> done) {
        mExport.setEnabled(false);
        mImport.setEnabled(false);
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            T result = null;
            boolean ok = false;
            try {
                result = work.run();
                ok = true;
            } catch (IOException | RuntimeException e) {
                // the helper may be busy, or the card may have gone away
            }
            final T value = result;
            final boolean success = ok;
            h.post(() -> {
                if (getActivity() == null) return;
                mExport.setEnabled(true);
                mImport.setEnabled(true);
                if (success) done.accept(value);
                else profileMessage(getString(R.string.kt_profile_error));
            });
        }, "ktprofiles").start();
    }

    private void profileMessage(String message) {
        new AlertDialog.Builder(getActivity())
            .setTitle(R.string.kt_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void showProfiles(Profiles.Scan scan) {
        if (scan.entries.isEmpty()) {
            profileMessage(getString(R.string.kt_profile_none)
                    + (scan.skipped == 0 ? "" : "\n\n"
                            + getString(R.string.kt_profile_skipped, scan.skipped)));
            return;
        }
        final CharSequence[] names = new CharSequence[scan.entries.size()];
        for (int i = 0; i < names.length; i++) {
            final Profiles.Entry entry = scan.entries.get(i);
            names[i] = entry.builtin ? getString(R.string.kt_profile_recommended, entry.name)
                    : entry.name;
        }
        final Runnable choose = () -> new AlertDialog.Builder(getActivity())
            .setTitle(R.string.kt_profile_import)
            .setItems(names, (d, which) -> showProfile(scan.entries.get(which), scan.console))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        if (scan.skipped > 0) {
            new AlertDialog.Builder(getActivity())
                .setMessage(getString(R.string.kt_profile_skipped, scan.skipped))
                .setPositiveButton(android.R.string.ok, (d, w) -> choose.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        } else {
            choose.run();
        }
    }

    private void showProfile(Profiles.Entry entry, Profile console) {
        final Profile profile = entry.profile;
        final StringBuilder sb = new StringBuilder();
        final List<String> differences = profile.differences(console.identity);
        if (!differences.isEmpty()) {
            sb.append(getString(R.string.kt_profile_mismatch)).append('\n');
            final int[] titles = {R.string.kt_profile_model, R.string.kt_profile_compatible,
                    R.string.kt_profile_boot, R.string.kt_profile_version, R.string.kt_profile_panel};
            for (int i = 0; i < Profile.IDENTITY.length; i++) {
                final String key = Profile.IDENTITY[i];
                if (!differences.contains(key)) continue;
                sb.append(getString(R.string.kt_profile_change, getString(titles[i]),
                        console.identity.getOrDefault(key, ""),
                        profile.identity.getOrDefault(key, ""))).append('\n');
            }
            sb.append('\n');
        }
        final Map<Tweak, String> changes = new LinkedHashMap<>();
        final List<String> unavailable = new ArrayList<>();
        String storage = mStorage == null ? "" : get(mStorage.prop());
        if (profile.values.containsKey(mStorage)) storage = profile.values.get(mStorage);
        if (storage.isEmpty() && mStorage != null) storage = def(mStorage);
        boolean boot = false;
        for (Map.Entry<Tweak, String> v : profile.values.entrySet()) {
            final Tweak t = v.getKey();
            if (def(t).isEmpty()) {
                unavailable.add(getString(t.title));
                continue;
            }
            if ("fuse".equals(t.key) && "off".equals(v.getValue()) && "fuse".equals(storage)) {
                sb.append(getString(t.title)).append(": ")
                        .append(getString(R.string.kt_fuse_needed)).append('\n');
                continue;
            }
            if (v.getValue().equals(get(t.prop()))) continue;
            changes.put(t, v.getValue());
            sb.append(getString(R.string.kt_profile_change, getString(t.title),
                    label(t, cur(t)), label(t, v.getValue()))).append('\n');
            boot |= t.atBoot;
        }
        if (changes.isEmpty()) sb.append(getString(R.string.kt_profile_no_changes)).append('\n');
        if (!profile.unknown.isEmpty()) sb.append('\n').append(getString(
                R.string.kt_profile_unknown, String.join(", ", profile.unknown))).append('\n');
        if (!profile.invalid.isEmpty()) sb.append('\n').append(getString(
                R.string.kt_profile_invalid, String.join(", ", profile.invalid))).append('\n');
        if (!unavailable.isEmpty()) sb.append('\n').append(getString(
                R.string.kt_profile_unavailable, String.join(", ", unavailable))).append('\n');
        if (boot) sb.append('\n').append(getString(R.string.kt_restart_message)).append('\n');
        if (paused()) sb.append('\n').append(getString(R.string.kt_profile_paused)).append('\n');
        sb.append('\n').append(getString(R.string.kt_profile_keep));
        final AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity())
            .setTitle(entry.name)
            .setMessage(sb)
            .setNegativeButton(android.R.string.cancel, null);
        if (!changes.isEmpty()) {
            dialog.setPositiveButton(R.string.kt_profile_apply, (d, w) -> {
                for (Map.Entry<Tweak, String> v : changes.entrySet()) {
                    SystemProperties.set(v.getKey().prop(), v.getValue());
                }
                mChanged = true;
                apply();
            });
        }
        dialog.show();
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
        levelLine(getActivity(), sb, t.level);
        sb.append("\n\n");
        final String def = def(t);
        if (!def.isEmpty()) {
            sb.append(getString(R.string.kt_doc_default, t.kind == Tweak.AXES
                    ? getString(R.string.kt_stick_normal) : label(t, def))).append('\n');
        }
        if (t.once) sb.append(getString(R.string.kt_once)).append('\n');
        if (t.atBoot) {
            sb.append(getString(R.string.kt_doc_boot_option, t.file));
        } else if (t.systemSetting) {
            sb.append(getString(R.string.kt_doc_property, t.file));
        } else {
            sb.append(getString(R.string.kt_doc_file, t.file));
        }
        new AlertDialog.Builder(getActivity())
            .setTitle(t.title)
            .setMessage(sb)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    // ---------------------------------------------------------------------------- restart

    /**
     * Leaving the page (Back or Up) after a change that needs a restart: asks whether to
     * restart now. True when the question is up, the activity then finishes or restarts.
     */
    boolean askRestart() {
        final Activity a = getActivity();
        if (a == null || !mChanged || !restartPending()) return false;
        // asked once per change: Back on the question, then Back again, leaves
        mChanged = false;
        final AlertDialog d = new AlertDialog.Builder(a)
            .setTitle(R.string.kt_restart_title)
            .setMessage(R.string.kt_restart_message)
            .setPositiveButton(R.string.kt_restart, (x, w) -> rebootWhenSaved())
            .setNegativeButton(R.string.kt_restart_later, (x, w) -> a.finish())
            .show();
        // the gamepad starts on Restart
        d.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        return true;
    }

    /** restart once the helper has written the start options to BOOT, or after a while */
    private void rebootWhenSaved() {
        final Context app = getActivity().getApplicationContext();
        final String waitingFor = mWaitingFor;
        final long until = SystemClock.uptimeMillis() + REBOOT_WAIT_MS;
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                if (waitingFor != null && !waitingFor.equals(get(DONE_PROP))
                        && SystemClock.uptimeMillis() < until) {
                    h.postDelayed(this, POLL_MS);
                    return;
                }
                app.getSystemService(PowerManager.class).reboot(null);
            }
        });
    }

    // ---------------------------------------------------------------------------- risk

    /** a filled circle in the level's color, 12 dp across */
    private static Drawable levelDot(Context c, int level) {
        final int size = Math.round(12 * c.getResources().getDisplayMetrics().density);
        final GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(LEVEL_COLORS[level - Tweak.SAFE]);
        d.setSize(size, size);
        d.setBounds(0, 0, size, size);
        return d;
    }

    /** the level's dot and its explanation, e.g. "(green) Safe: fine to try on any console" */
    private static void levelLine(Context c, SpannableStringBuilder sb, int level) {
        final int start = sb.length();
        sb.append(' ');
        sb.setSpan(new ImageSpan(levelDot(c, level), ImageSpan.ALIGN_BASELINE), start,
                sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("  ").append(c.getString(LEVEL_TEXTS[level - Tweak.SAFE]));
    }

    private static void heading(SpannableStringBuilder sb, String text) {
        final int start = sb.length();
        sb.append(text);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n');
    }
}
