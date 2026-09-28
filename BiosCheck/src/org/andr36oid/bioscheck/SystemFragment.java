package org.andr36oid.bioscheck;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import org.andr36oid.bioscheck.Report.FileState;
import org.andr36oid.bioscheck.Report.SystemState;

/** One system: what it needs, and each of its BIOS files. A on a file shows its details. */
public class SystemFragment extends CheckFragment {

    private String mId;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mId = getActivity().getIntent().getStringExtra(SystemActivity.EXTRA_SYSTEM);
    }

    @Override
    protected void build() {
        final Context c = getActivity();
        if (c == null) return;
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        final SystemState s = mReport != null ? mReport.system(mId) : null;
        if (mReport != null && !mounted()) {
            screen.addPreference(text(c, c.getString(R.string.no_easyroms_title),
                    c.getString(R.string.no_easyroms_summary)));
            return;
        }
        if (s == null) {
            screen.addPreference(text(c, null, c.getString(
                    busy() ? R.string.checking : R.string.check_failed)));
            return;
        }
        getActivity().setTitle(s.sys.name);
        screen.addPreference(text(c, null, s.sys.note));

        for (FileState f : s.files) {
            final Preference p = new Preference(c);
            p.setTitle(f.bios.name);
            p.setSummary(Texts.fileState(c, mScan, s, f) + "\n" + f.bios.description);
            p.setOnPreferenceClickListener(x -> {
                details(s, f);
                return true;
            });
            screen.addPreference(p);
        }
    }

    /** A file's details; Fix file names right there when it only needs a new name. */
    private void details(SystemState s, FileState f) {
        final Context c = getActivity();
        final AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(f.bios.name)
                .setMessage(Texts.fileDetails(c, mScan, s, f))
                .setNegativeButton(R.string.close, null);
        if (f.rename != null && !busy()) {
            b.setPositiveButton(R.string.fix_title, (d, w) -> askFixNames());
        }
        b.show();
    }
}
