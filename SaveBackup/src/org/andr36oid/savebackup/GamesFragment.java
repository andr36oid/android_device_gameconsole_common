package org.andr36oid.savebackup;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.PreferenceFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One backup: Restore all, and its games. A on either first compares the backup with the
 * console and shows what would change; nothing is written before the user says yes, and
 * saves that are newer on the console need a second, explicit yes.
 */
public class GamesFragment extends PreferenceFragment {

    static final String EXTRA_BACKUP = "backup";
    /** How many kept/replaced newer files the dialog names. */
    private static final int MAX_NAMED = 6;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Helper.Runner mRunner;
    private String mName;
    /** The game being restored, null for all. */
    private String mGame;
    private String mGameLabel;
    private Preference mBusyPref;
    private CharSequence mBusySummary;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mName = getActivity().getIntent().getStringExtra(EXTRA_BACKUP);
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(getActivity()));
        mRunner = new Helper.Runner(mHandler, new Helper.Runner.Listener() {
            @Override
            public void onProgress(String status) {
                if (mBusyPref != null) mBusyPref.setSummary(Texts.progress(getActivity(), status));
            }

            @Override
            public void onDone(String status) {
                if (getActivity() == null) return;
                idle();
                if (status.equals("done plan")) {
                    showPlan(Helper.plan());
                } else if (status.startsWith("done restore")) {
                    showResult(Helper.result());
                } else {
                    new AlertDialog.Builder(getActivity())
                            .setMessage(Texts.error(getActivity(), status))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            }
        });
        build();
    }

    @Override
    public void onPause() {
        mRunner.stop();
        idle();
        super.onPause();
    }

    private void build() {
        final Context c = getActivity();
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        Index.Backup backup = null;
        for (Index.Backup b : Helper.index()) {
            if (b.name.equals(mName)) backup = b;
        }
        if (backup == null) {
            final Preference p = new Preference(c);
            p.setSelectable(false);
            p.setSummary(R.string.error_not_found);
            screen.addPreference(p);
            return;
        }
        getActivity().setTitle(Texts.when(c, backup.created));

        final Preference all = new Preference(c);
        all.setTitle(R.string.restore_all_title);
        all.setSummary(getString(R.string.restore_all_summary, Texts.files(c, backup.files)));
        all.setOnPreferenceClickListener(p -> {
            plan(p, null, null);
            return true;
        });
        screen.addPreference(all);

        final PreferenceCategory games = new PreferenceCategory(c);
        games.setTitle(getResources().getQuantityString(R.plurals.games, backup.games.size(),
                backup.games.size()));
        screen.addPreference(games);
        for (Index.Game g : backup.games) {
            final Preference p = new Preference(c);
            p.setTitle(g.label);
            p.setSummary(getString(R.string.game_summary, Texts.files(c, g.files),
                    String.join(", ", g.folders), Texts.when(c, g.newest)));
            p.setOnPreferenceClickListener(x -> {
                plan(x, g.key, g.label);
                return true;
            });
            games.addPreference(p);
        }
    }

    private void plan(Preference p, String game, String label) {
        if (mRunner.active()) return;
        mGame = game;
        mGameLabel = label;
        if (!mRunner.start(Helper.planRequest(mName, game))) {
            new AlertDialog.Builder(getActivity())
                    .setMessage(Texts.error(getActivity(),
                            Helper.running() ? "error busy" : "error start"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        busy(p);
    }

    private void busy(Preference p) {
        idle();
        mBusyPref = p;
        mBusySummary = p.getSummary();
    }

    private void idle() {
        if (mBusyPref != null) mBusyPref.setSummary(mBusySummary);
        mBusyPref = null;
    }

    /** What restoring would do, and the choices. */
    private void showPlan(Index.Plan plan) {
        final Context c = getActivity();
        final int missing = plan.count(Restorer.MISSING);
        final int older = plan.count(Restorer.OLDER);
        final int newer = plan.count(Restorer.NEWER);
        final int same = plan.count(Restorer.SAME);
        final int unavailable = plan.count(Restorer.UNAVAILABLE);

        final StringBuilder msg = new StringBuilder();
        if (missing > 0) line(msg, plural(R.plurals.plan_missing, missing));
        if (older > 0) line(msg, plural(R.plurals.plan_older, older));
        if (newer > 0) {
            line(msg, plural(R.plurals.plan_newer, newer));
            int named = 0;
            for (Index.PlanItem i : plan.items) {
                if (!i.state.equals(Restorer.NEWER)) continue;
                if (named++ == MAX_NAMED) {
                    msg.append('\n').append(getString(R.string.plan_more, newer - MAX_NAMED));
                    break;
                }
                msg.append('\n').append(getString(R.string.plan_newer_item, fileName(i.id),
                        Texts.when(c, i.consoleMtime), Texts.when(c, i.backupMtime)));
            }
        }
        if (same > 0) line(msg, plural(R.plurals.plan_same, same));
        if (unavailable > 0) line(msg, plural(R.plurals.plan_unavailable, unavailable));

        final String title = mGameLabel != null ? getString(R.string.plan_title_game, mGameLabel)
                : getString(R.string.plan_title_all);
        final AlertDialog.Builder b = new AlertDialog.Builder(c).setTitle(title);
        if (missing + older + newer == 0) {
            line(msg, getString(R.string.plan_nothing));
            b.setMessage(msg.toString()).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        line(msg, getString(R.string.plan_note));
        b.setMessage(msg.toString()).setNegativeButton(android.R.string.cancel, null);
        if (missing + older > 0) {
            b.setPositiveButton(R.string.restore_button, (d, w) -> restore(false));
        }
        if (newer > 0) {
            b.setNeutralButton(R.string.replace_newer_button, (d, w) -> confirmNewer(newer));
        }
        b.show();
    }

    /** The second yes, for going back to older progress. */
    private void confirmNewer(int newer) {
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.replace_newer_title)
                .setMessage(plural(R.plurals.replace_newer_message, newer))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.replace_button, (d, w) -> restore(true))
                .show();
    }

    private void restore(boolean newer) {
        if (mRunner.active()) return;
        if (!mRunner.start(Helper.restoreRequest(getActivity(), mName, mGame, newer))) {
            new AlertDialog.Builder(getActivity())
                    .setMessage(Texts.error(getActivity(),
                            Helper.running() ? "error busy" : "error start"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final Preference p = mGame == null ? getPreferenceScreen().getPreference(0)
                : findGame(mGame);
        if (p != null) busy(p);
    }

    private Preference findGame(String key) {
        final PreferenceScreen screen = getPreferenceScreen();
        if (screen.getPreferenceCount() < 2) return null;
        final PreferenceCategory games = (PreferenceCategory) screen.getPreference(1);
        final Index.Backup b = backup();
        if (b == null) return null;
        for (int i = 0; i < b.games.size() && i < games.getPreferenceCount(); i++) {
            if (b.games.get(i).key.equals(key)) return games.getPreference(i);
        }
        return null;
    }

    private Index.Backup backup() {
        for (Index.Backup b : Helper.index()) {
            if (b.name.equals(mName)) return b;
        }
        return null;
    }

    private void showResult(Map<String, String> r) {
        final List<String> lines = new ArrayList<>();
        final int restored = (int) Index.num(r.get("restored"));
        final int kept = (int) Index.num(r.get("kept"));
        final int failed = (int) Index.num(r.get("failed"));
        lines.add(plural(R.plurals.result_restored, restored));
        if (kept > 0) lines.add(plural(R.plurals.result_kept, kept));
        if (failed > 0) lines.add(plural(R.plurals.result_failed, failed));
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.result_title)
                .setMessage(String.join("\n\n", lines))
                .setPositiveButton(android.R.string.ok, null)
                .show();
        SummaryProvider.changed(getActivity());
    }

    private String plural(int id, int n) {
        return getResources().getQuantityString(id, n, n);
    }

    private static void line(StringBuilder b, String s) {
        if (b.length() > 0) b.append("\n\n");
        b.append(s);
    }

    private static String fileName(String id) {
        return id.substring(id.lastIndexOf('/') + 1);
    }
}
