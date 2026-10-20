package org.andr36oid.emmc;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The built-in eMMC: what the chip is, how worn, what is on it, and backups of all of it or
 * single partitions to image files, with live progress. Everything is only read.
 */
public class EmmcFragment extends PreferenceFragment {

    private static final long POLL_MS = 1000;
    /** How long the service may take to show up after ctl.start. */
    private static final long START_GRACE_MS = 5000;
    private static final String DT_MARK = "/proc/device-tree/dwmmc@ff390000/andr36oid,builtin-emmc";
    private static final String PARAM = "/sys/module/dw_mmc_rockchip/parameters/builtin_emmc";
    private static final String BACKUP_FOLDER = "eMMC backup/";
    /** The item rows come after the fixed rows of the backup category. */
    private static final int ITEM_ORDER = 100;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = this::refresh;
    /** Info and status files and free space are read here, not on the main thread. */
    private HandlerThread mThread;
    private Handler mWorker;
    /** Counts refresh() calls; a read that finishes after a newer one is dropped. */
    private int mGeneration;

    private Preference mState, mModel, mSize, mMade, mWear, mSpeed, mProgress, mResult;
    private PreferenceCategory mChipCategory, mSpeedCategory, mBackupCategory;
    private ListPreference mDest;
    private final List<Preference> mItems = new ArrayList<>();
    private List<Target> mTargets = new ArrayList<>();
    private String mTargetId;
    private EmmcInfo mInfo;
    /** When we started the helper, until it shows as running. */
    private long mStartedAt;
    /** What we started (Helper.DUMP or SPEED), until the helper has said so itself. */
    private String mStartedAction;

    /** The console's device tree lets Kernel Tweaks switch the eMMC on. */
    static boolean consoleHasEmmc() {
        return new File(DT_MARK).exists();
    }

    /** Kernel Tweaks switched it on for this start. */
    static boolean switchedOn() {
        final String v = Helper.read(new File(PARAM));
        return v != null && (v.startsWith("Y") || v.startsWith("1"));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.emmc);
        mThread = new HandlerThread("EmmcTool");
        mThread.start();
        mWorker = new Handler(mThread.getLooper());
        mState = findPreference("state");
        mChipCategory = (PreferenceCategory) findPreference("chip_category");
        mModel = findPreference("model");
        mSize = findPreference("size");
        mMade = findPreference("made");
        mWear = findPreference("wear");
        mSpeedCategory = (PreferenceCategory) findPreference("speed_category");
        mSpeed = findPreference("speed");
        mBackupCategory = (PreferenceCategory) findPreference("backup_category");
        mDest = (ListPreference) findPreference("dest");
        mProgress = findPreference("progress");
        mResult = findPreference("result");

        mDest.setOnPreferenceChangeListener((pref, value) -> {
            mTargetId = (String) value;
            showTargets();
            return true;
        });
        mProgress.setOnPreferenceClickListener(pref -> {
            confirmStop();
            return true;
        });
        mSpeed.setOnPreferenceClickListener(pref -> {
            startSpeed();
            return true;
        });
        mState.setOnPreferenceClickListener(pref -> {
            openKernelTweaks();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        mTargets = Target.list(getActivity());
        if (find(mTargetId) == null) mTargetId = mTargets.get(0).id;
        showTargets();
        // only the state row until the details are read
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removePreference(mChipCategory);
        screen.removePreference(mSpeedCategory);
        screen.removePreference(mBackupCategory);
        screen.addPreference(mState);
        if (!consoleHasEmmc() || !switchedOn()) {
            mState.setSummary(consoleHasEmmc() ? R.string.state_off : R.string.state_no_emmc);
            mState.setTitle(consoleHasEmmc() ? getString(R.string.state_open_tweaks) : null);
            mState.setSelectable(consoleHasEmmc());
            return;
        }
        // read the details again; a backup that runs read them when it started
        mInfo = null;
        if (!Helper.running() && Helper.info()) mStartedAt = SystemClock.elapsedRealtime();
        mState.setTitle(null);
        mState.setSelectable(false);
        mState.setSummary(R.string.state_reading);
        refresh();
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mPoll);
        mGeneration++;
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mThread.quitSafely();
        super.onDestroy();
    }

    private Target find(String id) {
        for (Target t : mTargets) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    private void showTargets() {
        final String[] names = new String[mTargets.size()];
        final String[] ids = new String[mTargets.size()];
        for (int i = 0; i < names.length; i++) {
            final Target t = mTargets.get(i);
            names[i] = getString(R.string.dest_free, t.name,
                    Formatter.formatFileSize(getActivity(), t.freeBytes()));
            ids[i] = t.id;
        }
        mDest.setEntries(names);
        mDest.setEntryValues(ids);
        mDest.setValue(mTargetId);
        mDest.setSummary(names[ids.length > 0 ? indexOf(ids, mTargetId) : 0]);
    }

    private static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(s)) return i;
        }
        return 0;
    }

    private void refresh() {
        mHandler.removeCallbacks(mPoll);
        final int generation = ++mGeneration;
        mWorker.post(() -> {
            final boolean running = Helper.running();
            final EmmcInfo info = Helper.readInfo();
            final Map<String, String> status = Helper.status();
            final Map<String, String> speed = Helper.speedResult();
            final Map<String, String> dump = Helper.dumpResult();
            mHandler.post(() -> {
                if (generation != mGeneration || !isAdded()) return;
                show(running, info, status, speed, dump);
            });
        });
    }

    private void show(boolean running, EmmcInfo info, Map<String, String> status,
            Map<String, String> speed, Map<String, String> dump) {
        final long now = SystemClock.elapsedRealtime();
        final boolean starting = !running && mStartedAt != 0 && now - mStartedAt < START_GRACE_MS;
        if (running) mStartedAt = 0;
        final boolean busy = running || starting;
        String action = mStartedAction;
        if ("running".equals(status.get("state"))) action = status.get("action");
        if (!busy) {
            action = null;
            mStartedAction = null;
        }

        if (info != null && mInfo == null) showInfo(info);
        if (mInfo == null) {
            mState.setSummary(busy ? R.string.state_reading : R.string.state_failed);
        }
        showProgress(Helper.DUMP.equals(action), status, dump);
        showSpeed(Helper.SPEED.equals(action), speed);
        mSpeed.setEnabled(!busy);
        for (Preference p : mItems) p.setEnabled(!busy);
        if (busy) mHandler.postDelayed(mPoll, POLL_MS);
    }

    private void showInfo(EmmcInfo info) {
        mInfo = info;
        final PreferenceScreen screen = getPreferenceScreen();
        if (!info.present) {
            screen.removePreference(mChipCategory);
            screen.removePreference(mSpeedCategory);
            screen.removePreference(mBackupCategory);
            screen.addPreference(mState);
            mState.setSummary(R.string.state_missing);
            return;
        }
        screen.removePreference(mState);
        screen.addPreference(mChipCategory);
        screen.addPreference(mSpeedCategory);
        screen.addPreference(mBackupCategory);

        final String name = info.field("name");
        final String maker = EmmcInfo.maker(info.field("manfid"));
        mModel.setSummary(name == null ? getString(R.string.unknown)
                : maker == null ? name : getString(R.string.model_maker, name, maker));
        final String version = EmmcInfo.version(info.field("rev"));
        final String size = Formatter.formatFileSize(getActivity(), info.bytes());
        mSize.setSummary(version == null ? size : getString(R.string.size_version, size, version));
        final String date = info.field("date");
        final String fw = info.field("fwrev");
        mMade.setSummary(date == null ? getString(R.string.unknown)
                : fw == null ? date : getString(R.string.made_firmware, date, fw));
        mWear.setSummary(wear(info));

        for (Preference p : mItems) mBackupCategory.removePreference(p);
        mItems.clear();
        final StringBuilder whole = new StringBuilder(info.disk);
        for (EmmcInfo.Part b : info.boots) whole.append(' ').append(b.block);
        addItem(getString(R.string.whole_title), getString(R.string.whole_summary,
                Formatter.formatFileSize(getActivity(), info.wholeBytes())),
                whole.toString(), info.wholeBytes());
        for (EmmcInfo.Part p : info.parts) {
            final String title = p.name.isEmpty()
                    ? getString(R.string.part_title, p.number)
                    : getString(R.string.part_title_name, p.number, p.name);
            final String bytes = Formatter.formatFileSize(getActivity(), p.bytes());
            final String summary;
            if (p.type.isEmpty()) {
                summary = bytes;
            } else if (p.label.isEmpty()) {
                summary = getString(R.string.part_summary_type, bytes, p.type);
            } else {
                summary = getString(R.string.part_summary_label, bytes, p.type, p.label);
            }
            addItem(title, summary, p.block, p.bytes());
        }
        for (EmmcInfo.Part b : info.boots) {
            addItem(getString(R.string.boot_title, b.number), getString(R.string.boot_summary,
                    Formatter.formatFileSize(getActivity(), b.bytes())), b.block, b.bytes());
        }
    }

    private String wear(EmmcInfo info) {
        final int used = EmmcInfo.lifeUsed(info.field("life_time"));
        final int eol = EmmcInfo.preEol(info.field("pre_eol_info"));
        final String life;
        if (used == 0) {
            life = getString(R.string.wear_unknown);
        } else if (used > 10) {
            life = getString(R.string.wear_worn_out);
        } else {
            life = getString(R.string.wear_used, (used - 1) * 10, used * 10);
        }
        if (eol == 2) return life + "\n" + getString(R.string.wear_spare_low);
        if (eol == 3) return life + "\n" + getString(R.string.wear_spare_urgent);
        return life;
    }

    private void addItem(String title, String summary, String items, long bytes) {
        final Preference p = new Preference(getActivity());
        p.setTitle(title);
        p.setSummary(summary);
        p.setOrder(ITEM_ORDER + mItems.size());
        p.setPersistent(false);
        p.setOnPreferenceClickListener(pref -> {
            confirmDump(title, items, bytes);
            return true;
        });
        mItems.add(p);
        mBackupCategory.addPreference(p);
    }

    private void showSpeed(boolean testing, Map<String, String> r) {
        if (testing) {
            mSpeed.setSummary(R.string.speed_running);
            return;
        }
        final long seqBytes = Helper.number(r, "seq_bytes");
        final long ops = Helper.number(r, "rr_ops");
        final double seqS = seconds(r, "seq_s"), rrS = seconds(r, "rr_s");
        if ("done".equals(r.get("state")) && seqBytes > 0 && ops > 0 && seqS > 0 && rrS > 0) {
            mSpeed.setSummary(getString(R.string.speed_result,
                    Formatter.formatFileSize(getActivity(), (long) (seqBytes / seqS)),
                    Math.round(ops / rrS),
                    Formatter.formatFileSize(getActivity(), (long) (ops * 4096 / rrS))));
        } else if ("failed".equals(r.get("state"))) {
            mSpeed.setSummary(R.string.speed_failed);
        } else {
            mSpeed.setSummary(R.string.speed_summary);
        }
    }

    private static double seconds(Map<String, String> r, String key) {
        try {
            return Double.parseDouble(r.get(key));
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    private void startSpeed() {
        if (Helper.running() || !Helper.speed()) {
            Toast.makeText(getActivity(), R.string.speed_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        mStartedAt = SystemClock.elapsedRealtime();
        mStartedAction = Helper.SPEED;
        refresh();
    }

    private void showProgress(boolean dumping, Map<String, String> status,
            Map<String, String> dump) {
        if (dumping) {
            mBackupCategory.addPreference(mProgress);
            mBackupCategory.removePreference(mResult);
            final long done = Helper.number(status, "done_mb");
            final long total = Helper.number(status, "total_mb");
            final long seconds = Helper.number(status, "now") - Helper.number(status, "started");
            if (done <= 0 || total <= 0 || seconds <= 0) {
                mProgress.setSummary(R.string.progress_starting);
            } else {
                final long leftMs = (total - done) * seconds * 1000 / done;
                mProgress.setSummary(getString(R.string.progress_summary,
                        Formatter.formatFileSize(getActivity(), done * 1024 * 1024),
                        Formatter.formatFileSize(getActivity(), total * 1024 * 1024),
                        Formatter.formatShortElapsedTime(getActivity(), leftMs)));
            }
            return;
        }
        mBackupCategory.removePreference(mProgress);
        final String result = result(dump);
        if (result == null) {
            mBackupCategory.removePreference(mResult);
        } else {
            mResult.setSummary(result);
            mBackupCategory.addPreference(mResult);
        }
    }

    /** What the last backup did, null if there was none. */
    private String result(Map<String, String> s) {
        if (!Helper.DUMP.equals(s.get("action"))) return null;
        final String state = s.get("state");
        final String dest = TextUtils.isEmpty(s.get("dest_name")) ? "?" : s.get("dest_name");
        final String folder = s.get("folder");
        final int i = folder == null ? -1 : folder.indexOf(BACKUP_FOLDER);
        final String where = i < 0 ? "" : folder.substring(i);
        if ("done".equals(state)) return getString(R.string.result_done, dest, where);
        if ("stopped".equals(state)) return getString(R.string.result_stopped, dest, where);
        if ("failed".equals(state)) {
            switch (String.valueOf(s.get("error"))) {
                case "nospace":
                    return getString(R.string.result_no_space, dest);
                case "read":
                    return getString(R.string.result_read_error, dest, where);
                default:
                    return getString(R.string.result_failed);
            }
        }
        return null;
    }

    private void confirmDump(String title, String items, long bytes) {
        final Target t = find(mTargetId);
        if (t == null) return;
        final String size = Formatter.formatFileSize(getActivity(), bytes);
        if (bytes > t.freeBytes()) {
            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.no_space_title)
                    .setMessage(getString(R.string.no_space_text, t.name, size,
                            Formatter.formatFileSize(getActivity(), t.freeBytes())))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String text = getString(R.string.confirm_text, size, t.name);
        if (t.splitMb > 0 && bytes > t.splitMb * 1024L * 1024L) {
            text += "\n\n" + getString(R.string.confirm_split);
        }
        new AlertDialog.Builder(getActivity())
                .setTitle(getString(R.string.confirm_title, title))
                .setMessage(text)
                .setPositiveButton(R.string.confirm_ok, (d, w) -> {
                    if (Helper.running() || !Helper.dump(items, t)) {
                        Toast.makeText(getActivity(), R.string.result_failed,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    mStartedAt = SystemClock.elapsedRealtime();
                    mStartedAction = Helper.DUMP;
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmStop() {
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.stop_title)
                .setMessage(R.string.stop_text)
                .setPositiveButton(R.string.stop_ok, (d, w) -> {
                    Helper.stop();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openKernelTweaks() {
        try {
            startActivity(new Intent().setComponent(new ComponentName(
                    "org.andr36oid.kerneltweaks", "org.andr36oid.kerneltweaks.KernelTweaksActivity")));
        } catch (ActivityNotFoundException e) {
            // not on this build
        }
    }
}
