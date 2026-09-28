package org.andr36oid.bioscheck;

import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import org.andr36oid.bioscheck.Report.SystemState;

/**
 * The main page: where the files go, Fix file names when some have the wrong name, and
 * every system that uses a BIOS with its state in a few words.
 */
public class BiosFragment extends CheckFragment {

    @Override
    protected void build() {
        final Context c = getActivity();
        if (c == null) return;
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();

        if (unavailable(c)) return;
        screen.addPreference(text(c, null, c.getString(mScan != null && mScan.internal
                ? R.string.intro_internal : R.string.intro)));
        if (mFailed) {
            screen.addPreference(text(c, null, c.getString(R.string.check_failed)));
            return;
        }
        if (mReport == null) {
            screen.addPreference(text(c, null, c.getString(R.string.checking)));
            return;
        }

        if (!mReport.renames.isEmpty()) {
            final int n = mReport.renames.size();
            final Preference fix = new Preference(c);
            fix.setTitle(R.string.fix_title);
            fix.setSummary(c.getResources().getQuantityString(R.plurals.fix_summary, n, n));
            fix.setEnabled(!busy());
            fix.setOnPreferenceClickListener(p -> {
                askFixNames();
                return true;
            });
            screen.addPreference(fix);
        }

        final PreferenceCategory systems = new PreferenceCategory(c);
        systems.setTitle(R.string.systems_category);
        screen.addPreference(systems);
        for (SystemState s : mReport.systems) {
            final Preference p = new Preference(c);
            p.setTitle(s.sys.name);
            p.setSummary(Texts.systemSummary(c, s));
            p.setIntent(new Intent(c, SystemActivity.class)
                    .putExtra(SystemActivity.EXTRA_SYSTEM, s.sys.id));
            systems.addPreference(p);
        }
    }
}
