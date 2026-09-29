package org.andr36oid.storagecare;

import android.app.AlertDialog;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageDataObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Settings > Storage > Free up space. Shows the free space of both partitions and the
 * card's health, looks for files that can go (the helper's scan), lets the user review
 * each group, and removes the picked files after asking once more.
 */
public class FreeSpaceFragment extends PreferenceFragment {

    private static final String TAG = "StorageCare";
    private static final int MAX_FOLDERS = 8;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Helper.Runner mRunner;
    private PreferenceCategory mSpace, mHealth, mFound, mFolders;
    private Preference mScan, mRemove;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.free_space);
        mSpace = (PreferenceCategory) findPreference("space");
        mHealth = (PreferenceCategory) findPreference("health");
        mFound = (PreferenceCategory) findPreference("found");
        mFolders = (PreferenceCategory) findPreference("folders");
        mScan = findPreference("scan");
        mRemove = findPreference("remove");
        mRunner = new Helper.Runner(mHandler, new Helper.Runner.Listener() {
            @Override
            public void onProgress(String status) {
                if (!isAdded()) return;
                mScan.setEnabled(false);
                mScan.setSummary(Texts.progress(getActivity(), status));
            }

            @Override
            public void onDone(String status) {
                if (!isAdded()) return;
                finished(status);
            }
        });
        mScan.setOnPreferenceClickListener(p -> {
            startScan();
            return true;
        });
        mRemove.setOnPreferenceClickListener(p -> {
            confirmRemove();
            return true;
        });
        for (String key : new String[] {Prefs.KEEP_STATES, Prefs.KEEP_BACKUPS, Prefs.DUPS}) {
            findPreference(key).setOnPreferenceChangeListener((p, v) -> {
                // the old scan used the old numbers
                mHandler.post(() -> {
                    Picks.clear();
                    refresh();
                });
                return true;
            });
        }
        findPreference(Prefs.WARN).setOnPreferenceChangeListener((p, v) -> {
            mHandler.post(() -> Checks.schedule(getActivity()));
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        mRunner.follow();
        refresh();
    }

    @Override
    public void onPause() {
        mRunner.stop();
        super.onPause();
    }

    // ---- showing ---------------------------------------------------------------------

    private void refresh() {
        final Context c = getActivity();
        showSpace(c);
        showHealth(c);
        showFound(c);
        showFolders(c);
        if (!mRunner.active()) {
            mScan.setEnabled(true);
            mScan.setSummary(Picks.scan == null ? R.string.scan_summary : R.string.scan_again_summary);
        }
    }

    private void showSpace(Context c) {
        mSpace.removeAll();
        final long[] in = Volumes.internal();
        mSpace.addPreference(info(c, getString(R.string.space_internal),
                getString(R.string.space_free_of, Texts.size(c, in[0]), Texts.size(c, in[1]))));
        final VolumeInfo v = Volumes.easyroms(c);
        final long[] er = Volumes.easyroms(v);
        mSpace.addPreference(info(c, getString(R.string.space_easyroms), er != null
                ? getString(R.string.space_free_of, Texts.size(c, er[0]), Texts.size(c, er[1]))
                : getString(R.string.space_easyroms_missing)));
    }

    private void showHealth(Context c) {
        mHealth.removeAll();
        final Map<String, String> h = Helper.health();
        final String problem = Health.problem(h);
        // nothing known, or no card to talk about
        if (h.isEmpty() || (problem == null && !"mounted".equals(h.get("easyroms_state")))) {
            getPreferenceScreen().removePreference(mHealth);
            return;
        }
        getPreferenceScreen().addPreference(mHealth);
        mHealth.addPreference(info(c, Texts.healthTitle(c, problem != null ? problem : ""),
                Texts.healthText(c, problem != null ? problem : "")));
    }

    private void showFound(Context c) {
        mFound.removeAll();
        final Scan s = Picks.scan;
        if (s == null) {
            getPreferenceScreen().removePreference(mFound);
            return;
        }
        getPreferenceScreen().addPreference(mFound);
        for (String group : Scan.GROUPS) {
            final List<Scan.Item> items = s.group(group);
            if (items.isEmpty()) continue;
            final long[] picked = Picks.count(group);
            final Preference p = new Preference(c);
            p.setTitle(getString(R.string.group_title_size, Texts.groupTitle(c, group),
                    Texts.size(c, Picks.total(group))));
            p.setSummary(picked[0] == 0 ? getString(R.string.picked_none, items.size())
                    : picked[0] == items.size() ? getString(R.string.picked_all, items.size())
                    : getString(R.string.picked_some, picked[0], items.size(),
                            Texts.size(c, picked[1])));
            p.setOnPreferenceClickListener(x -> {
                startActivity(new Intent(c, GroupActivity.class).putExtra(GroupActivity.GROUP, group));
                return true;
            });
            mFound.addPreference(p);
        }
        // App caches: PackageManager clears them, so they're a switch, not a list
        final Preference caches = new Preference(c);
        caches.setTitle(Picks.cacheBytes >= 0
                ? getString(R.string.group_title_size, getString(R.string.group_caches),
                        Texts.size(c, Picks.cacheBytes))
                : getString(R.string.group_caches));
        caches.setSummary(Picks.caches ? R.string.caches_picked : R.string.caches_not_picked);
        caches.setOnPreferenceClickListener(x -> {
            Picks.caches = !Picks.caches;
            refresh();
            return true;
        });
        mFound.addPreference(caches);
        if (Picks.cacheBytes < 0) loadCacheSize(c);

        final long[] all = Picks.count(null);
        mRemove.setOrder(Integer.MAX_VALUE);
        mFound.addPreference(mRemove);
        mRemove.setEnabled(all[0] > 0 || Picks.caches);
        mRemove.setSummary(all[0] == 0 && !Picks.caches ? getString(R.string.remove_nothing)
                : getString(R.string.remove_summary, all[0], Texts.size(c, all[1]))
                        + (Picks.caches ? getString(R.string.remove_summary_caches) : ""));
    }

    private void showFolders(Context c) {
        mFolders.removeAll();
        final Scan s = Picks.scan;
        if (s == null || s.folders.isEmpty()) {
            getPreferenceScreen().removePreference(mFolders);
            return;
        }
        getPreferenceScreen().addPreference(mFolders);
        for (int i = 0; i < s.folders.size() && i < MAX_FOLDERS; i++) {
            final Scan.Folder f = s.folders.get(i);
            mFolders.addPreference(info(c, f.name,
                    getString(R.string.folder_summary, Texts.size(c, f.bytes), f.files)));
        }
    }

    private static Preference info(Context c, String title, String summary) {
        final Preference p = new Preference(c);
        p.setTitle(title);
        p.setSummary(summary);
        p.setSelectable(false);
        return p;
    }

    private void loadCacheSize(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            long bytes;
            try {
                bytes = app.getSystemService(StorageStatsManager.class).queryStatsForUser(
                        StorageManager.UUID_DEFAULT, UserHandle.of(UserHandle.myUserId()))
                        .getCacheBytes();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "cache size", e);
                bytes = 0;
            }
            final long b = bytes;
            mHandler.post(() -> {
                Picks.cacheBytes = b;
                if (isAdded() && Picks.scan != null) refresh();
            });
        }, "CacheSize").start();
    }

    // ---- doing -----------------------------------------------------------------------

    private void startScan() {
        if (mRunner.active()) return;
        if (!mRunner.start(Helper.scanRequest(getActivity()))) {
            Toast.makeText(getActivity(), R.string.busy, Toast.LENGTH_SHORT).show();
            return;
        }
    }

    private void confirmRemove() {
        final Context c = getActivity();
        final long[] all = Picks.count(null);
        final StringBuilder msg = new StringBuilder();
        if (all[0] > 0) {
            msg.append(getString(R.string.confirm_files, all[0], Texts.size(c, all[1])));
        }
        if (Picks.caches) {
            if (msg.length() > 0) msg.append("\n\n");
            msg.append(getString(R.string.confirm_caches));
        }
        msg.append("\n\n").append(getString(R.string.confirm_safe, Prefs.keepStates(c)));
        new AlertDialog.Builder(c)
                .setTitle(R.string.confirm_title)
                .setMessage(msg)
                .setPositiveButton(R.string.confirm_remove, (d, w) -> remove())
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private void remove() {
        final List<Scan.Item> items = Picks.pickedItems();
        if (items.isEmpty()) {
            clearCaches(null);
            return;
        }
        if (!Helper.writeDeleteList(items) || !mRunner.start(Helper.deleteRequest(getActivity()))) {
            Toast.makeText(getActivity(), R.string.busy, Toast.LENGTH_SHORT).show();
            return;
        }
    }

    private void finished(String status) {
        final String[] w = status.split(" ");
        if (status.startsWith("done scan")) {
            Picks.take(Helper.scan());
            Picks.cacheBytes = -1;
            refresh();
            return;
        }
        if (status.startsWith("done delete") && w.length >= 6) {
            clearCaches(w);
            return;
        }
        if (status.startsWith("done health")) {
            // a background check finished while we were open
            refresh();
            return;
        }
        Toast.makeText(getActivity(), getString(R.string.failed, status), Toast.LENGTH_LONG).show();
        refresh();
    }

    /** Clears the app caches if picked, then shows what was done and scans again. */
    private void clearCaches(String[] deleteStatus) {
        final Context app = getActivity().getApplicationContext();
        if (!Picks.caches) {
            done(deleteStatus, 0);
            return;
        }
        final long before = Volumes.internal()[0];
        try {
            app.getPackageManager().freeStorageAndNotify(null, Long.MAX_VALUE,
                    new IPackageDataObserver.Stub() {
                        @Override
                        public void onRemoveCompleted(String pkg, boolean succeeded) {
                            final long freed = Math.max(0, Volumes.internal()[0] - before);
                            mHandler.post(() -> done(deleteStatus, freed));
                        }
                    });
        } catch (RuntimeException e) {
            Log.w(TAG, "clearing caches", e);
            done(deleteStatus, 0);
        }
    }

    private void done(String[] w, long cacheFreed) {
        if (!isAdded()) return;
        final Context c = getActivity();
        long files = 0, bytes = 0, failed = 0, refused = 0;
        if (w != null) {
            try {
                files = Long.parseLong(w[2]);
                bytes = Long.parseLong(w[3]);
                failed = Long.parseLong(w[4]);
                refused = Long.parseLong(w[5]);
            } catch (NumberFormatException e) {
                // shown as zero
            }
        }
        final StringBuilder msg = new StringBuilder(getString(R.string.result_removed, files,
                Texts.size(c, bytes)));
        if (Picks.caches) {
            msg.append("\n").append(getString(R.string.result_caches, Texts.size(c, cacheFreed)));
        }
        if (failed + refused > 0) {
            msg.append("\n\n").append(getString(R.string.result_skipped, failed + refused));
        }
        new AlertDialog.Builder(c)
                .setTitle(R.string.result_title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        new Thread(() -> Checks.recheckSpace(c.getApplicationContext()), "Recheck").start();
        Picks.clear();
        startScan();
        refresh();
    }
}
