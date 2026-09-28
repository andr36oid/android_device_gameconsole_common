package org.andr36oid.bioscheck;

import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import org.andr36oid.bioscheck.Report.Rename;
import org.andr36oid.bioscheck.Report.SystemState;
import org.andr36oid.bioscheck.Scan.Found;

/**
 * The main page: where to put the files, Copy to emulators, files that only need a new
 * name, every system with its state, and the files nothing uses.
 */
public class BiosFragment extends CheckFragment {

    @Override
    protected boolean scanOnResume() {
        return true;
    }

    @Override
    protected void build() {
        final Context c = getActivity();
        if (c == null) return;
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();

        final Preference where = new Preference(c);
        where.setSelectable(false);
        where.setTitle(R.string.where_title);
        if (mFailed) {
            where.setSummary(R.string.check_failed);
        } else if (busy() && mReport == null) {
            where.setSummary(R.string.checking);
        } else if (mReport != null) {
            final String dir = Texts.path(c, mReport.systemDir);
            where.setSummary(mScan.biosDir != null ? c.getString(R.string.where_summary, dir)
                    : c.getString(R.string.where_no_easyroms, dir));
        }
        screen.addPreference(where);
        if (mReport == null) return;

        final Preference copy = new Preference(c);
        copy.setTitle(R.string.copy_title);
        copy.setSummary(copySummary(c, null));
        copy.setEnabled(!busy() && !(mReport.copies.isEmpty() && mReport.conflicts.isEmpty()));
        copy.setOnPreferenceClickListener(p -> {
            copy(null);
            return true;
        });
        screen.addPreference(copy);

        if (!mReport.renames.isEmpty()) {
            final PreferenceCategory cat = category(c, R.string.rename_category);
            for (Rename rn : mReport.renames) {
                final Preference p = new Preference(c);
                p.setTitle(rn.file.name);
                p.setSummary(c.getString(R.string.rename_summary, rn.to.name,
                        rn.to.description));
                p.setEnabled(!busy());
                p.setOnPreferenceClickListener(x -> {
                    askRename(rn);
                    return true;
                });
                cat.addPreference(p);
            }
        }

        final PreferenceCategory systems = category(c, R.string.systems_category);
        for (SystemState s : mReport.systems) {
            final Preference p = new Preference(c);
            p.setTitle(s.sys.name);
            p.setSummary(Texts.systemSummary(c, s));
            p.setIntent(new Intent(c, SystemActivity.class)
                    .putExtra(SystemActivity.EXTRA_SYSTEM, s.sys.id));
            systems.addPreference(p);
        }

        if (!mReport.unknown.isEmpty() || !mReport.duplicates.isEmpty()) {
            final PreferenceCategory cat = category(c, R.string.other_category);
            for (Rename d : mReport.duplicates) {
                cat.addPreference(info(c, d.file.name,
                        c.getString(R.string.duplicate_summary, d.to.name)));
            }
            for (Found f : mReport.unknown) {
                cat.addPreference(info(c, f.name, c.getString(f.md5 == null
                        ? R.string.unknown_summary_big : R.string.unknown_summary)));
            }
        }

        final Preference none = new Preference(c);
        none.setSelectable(false);
        none.setSummary(R.string.none_needed);
        screen.addPreference(none);

        final Preference rescan = new Preference(c);
        rescan.setTitle(R.string.rescan_title);
        rescan.setSummary(busy() ? c.getString(R.string.checking)
                : c.getString(R.string.rescan_summary));
        rescan.setEnabled(!busy());
        rescan.setOnPreferenceClickListener(p -> {
            run(null);
            return true;
        });
        screen.addPreference(rescan);
    }

    private PreferenceCategory category(Context c, int title) {
        final PreferenceCategory cat = new PreferenceCategory(c);
        cat.setTitle(title);
        getPreferenceScreen().addPreference(cat);
        return cat;
    }

    private static Preference info(Context c, String title, String summary) {
        final Preference p = new Preference(c);
        p.setSelectable(false);
        p.setTitle(title);
        p.setSummary(summary);
        return p;
    }
}
