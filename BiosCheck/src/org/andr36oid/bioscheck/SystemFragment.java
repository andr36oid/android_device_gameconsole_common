package org.andr36oid.bioscheck;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import org.andr36oid.bioscheck.Report.Copy;
import org.andr36oid.bioscheck.Report.FileState;
import org.andr36oid.bioscheck.Report.Rename;
import org.andr36oid.bioscheck.Report.SystemState;

import java.util.Collections;

/** One system: what it needs, and every BIOS file with its state. A opens a file's details. */
public class SystemFragment extends CheckFragment {

    private String mId;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mId = getActivity().getIntent().getStringExtra(SystemActivity.EXTRA_SYSTEM);
    }

    @Override
    protected boolean scanOnResume() {
        return false;
    }

    @Override
    protected void build() {
        final Context c = getActivity();
        if (c == null) return;
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        final SystemState s = mReport != null ? mReport.system(mId) : null;
        if (s == null) {
            final Preference p = new Preference(c);
            p.setSelectable(false);
            p.setSummary(busy() ? R.string.checking : R.string.check_failed);
            screen.addPreference(p);
            return;
        }
        getActivity().setTitle(s.sys.name);

        final Preference note = new Preference(c);
        note.setSelectable(false);
        note.setTitle(Texts.systemSummary(c, s));
        note.setSummary(s.sys.note);
        screen.addPreference(note);

        if (!mReport.copiesOf(mId, false).isEmpty() || !mReport.copiesOf(mId, true).isEmpty()) {
            final Preference copy = new Preference(c);
            copy.setTitle(R.string.copy_system_title);
            copy.setSummary(copySummary(c, mId));
            copy.setEnabled(!busy());
            copy.setOnPreferenceClickListener(p -> {
                copy(mId);
                return true;
            });
            screen.addPreference(copy);
        }

        final PreferenceCategory files = new PreferenceCategory(c);
        files.setTitle(R.string.files_category);
        screen.addPreference(files);
        for (FileState f : s.files) {
            final Preference p = new Preference(c);
            p.setTitle(f.bios.name);
            p.setSummary(Texts.fileState(c, f) + "\n" + f.bios.description);
            p.setOnPreferenceClickListener(x -> {
                details(f);
                return true;
            });
            files.addPreference(p);
        }
    }

    /** A file's details, with the one thing that would fix it, if there is one. */
    private void details(FileState f) {
        final Context c = getActivity();
        final AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(f.bios.name)
                .setMessage(Texts.fileDetails(c, f))
                .setNegativeButton(R.string.close, null);
        final Rename rn = renameTo(f);
        Copy conflict = null;
        for (Copy x : mReport.conflicts) {
            if (x.bios == f.bios) conflict = x;
        }
        if (busy()) {
            // nothing to do while the helper runs
        } else if (conflict != null) {
            final Copy x = conflict;
            b.setPositiveButton(R.string.replace_button, (d, w) -> askReplace(
                    Collections.emptyList(), Collections.singletonList(x)));
        } else if (f.state == Report.READY) {
            b.setPositiveButton(R.string.copy_title, (d, w) -> copy(mId));
            // the good file is in the bios folder under another name
            if (rn != null) b.setNeutralButton(R.string.rename_button, (d, w) -> askRename(rn));
        }
        b.show();
    }

    private Rename renameTo(FileState f) {
        for (Rename rn : mReport.renames) {
            if (rn.to == f.bios) return rn;
        }
        return null;
    }
}
