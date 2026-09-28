package org.andr36oid.savebackup;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;

import java.util.List;

/** Every backup, newest first: when, how many games and files, and where it is. */
public class RestoreFragment extends PreferenceFragment {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Helper.Runner mRunner;
    /** A backup run was going on when the page opened: list once it's done. */
    private boolean mListAfter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(getActivity()));
        mRunner = new Helper.Runner(mHandler, new Helper.Runner.Listener() {
            @Override
            public void onProgress(String status) {
                message(mListAfter ? getString(R.string.waiting_for_backup,
                        Texts.progress(getActivity(), status))
                        : Texts.progress(getActivity(), status));
            }

            @Override
            public void onDone(String status) {
                if (getActivity() == null) return;
                if (mListAfter) {
                    mListAfter = false;
                    list();
                } else if (status.equals("done list")) {
                    build();
                } else {
                    message(Texts.error(getActivity(), status));
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mRunner.follow()) {
            mListAfter = true;
        } else {
            list();
        }
    }

    @Override
    public void onPause() {
        mRunner.stop();
        mListAfter = false;
        super.onPause();
    }

    private void list() {
        if (!mRunner.start(Helper.listRequest())) {
            message(Texts.error(getActivity(), Helper.running() ? "error busy" : "error start"));
        }
    }

    private void message(String text) {
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        final Preference p = new Preference(getActivity());
        p.setSelectable(false);
        p.setSummary(text);
        screen.addPreference(p);
    }

    private void build() {
        final Context c = getActivity();
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        final List<Index.Backup> backups = Helper.index();
        if (backups.isEmpty()) {
            message(getString(R.string.no_backups));
            return;
        }
        for (Index.Backup b : backups) {
            final Preference p = new Preference(c);
            p.setKey(b.name);
            if (!b.ok) {
                p.setTitle(b.name);
                p.setSummary(getString(R.string.backup_broken, Texts.where(c, b.where)));
                p.setSelectable(false);
            } else {
                p.setTitle(Texts.when(c, b.created));
                p.setSummary(getString(R.string.backup_summary,
                        getResources().getQuantityString(R.plurals.games, b.games.size(),
                                b.games.size()),
                        Texts.files(c, b.files), Texts.size(c, b.zipBytes),
                        Texts.where(c, b.where)));
                p.setOnPreferenceClickListener(x -> {
                    startActivity(new Intent(c, GamesActivity.class)
                            .putExtra(GamesFragment.EXTRA_BACKUP, b.name));
                    return true;
                });
            }
            screen.addPreference(p);
        }
    }
}
