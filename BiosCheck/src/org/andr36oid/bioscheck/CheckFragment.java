package org.andr36oid.bioscheck;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceFragment;
import android.util.Log;
import android.widget.ListView;
import android.widget.Toast;

import org.andr36oid.bioscheck.Report.Copy;
import org.andr36oid.bioscheck.Report.Rename;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * What both pages share: the BIOS table, running the helper and waiting for it, and the
 * copy and rename actions. Subclasses show the report.
 */
abstract class CheckFragment extends PreferenceFragment {

    private static final String TAG = "BiosCheck";
    private static final long POLL_MS = 500;
    /** How long the service may take to show up after ctl.start. */
    private static final long START_GRACE_MS = 5000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = this::poll;

    private BiosTable mTable;
    protected Report mReport;
    protected Scan mScan;
    private long mStartedAt;
    private boolean mApplying;
    protected boolean mFailed;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(getActivity()));
        mTable = loadTable(getActivity());
    }

    @Override
    public void onResume() {
        super.onResume();
        mStartedAt = 0;
        load();
        if (Helper.running()) {
            mStartedAt = SystemClock.elapsedRealtime();
            poll();
        } else if (scanOnResume()) {
            run(null);
        } else {
            show();
        }
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mPoll);
        super.onPause();
    }

    /** The main page checks again every time it's shown; the detail page uses its scan. */
    protected abstract boolean scanOnResume();

    /** Fills the screen from mReport (null before the first scan), busy while the helper
     *  runs. */
    protected abstract void build();

    /** Rebuilds the page, keeping the D-pad selection where it was. */
    protected void show() {
        final ListView list = getView() != null ? getListView() : null;
        final int selected = list != null ? list.getSelectedItemPosition() : -1;
        build();
        if (list != null && selected > 0) {
            // the list gets its new items on the next loop
            list.post(() -> list.setSelection(Math.min(selected, list.getCount() - 1)));
        }
    }

    protected boolean busy() {
        return mStartedAt != 0;
    }

    protected boolean applying() {
        return busy() && mApplying;
    }

    static BiosTable loadTable(Context c) {
        try (InputStream in = c.getResources().openRawResource(R.raw.bios_table)) {
            return BiosTable.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "can't read the BIOS table", e);
            return new BiosTable();
        }
    }

    private void load() {
        mScan = Helper.readScan();
        mReport = mScan != null ? Report.build(mTable, mScan) : null;
    }

    /** Scans (ops null) or applies the operations and scans. */
    protected void run(String ops) {
        mApplying = ops != null;
        mFailed = false;
        if (!(ops != null ? Helper.apply(ops) : Helper.scan())) {
            mFailed = true;
            mStartedAt = 0;
            show();
            return;
        }
        mStartedAt = SystemClock.elapsedRealtime();
        show();
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    private void poll() {
        mHandler.removeCallbacks(mPoll);
        final String status = Helper.status();
        final boolean finished = status != null && !status.startsWith("running");
        if (!finished) {
            if (Helper.running()
                    || SystemClock.elapsedRealtime() - mStartedAt < START_GRACE_MS) {
                mHandler.postDelayed(mPoll, POLL_MS);
                return;
            }
            // never started, or stopped half way
            mFailed = true;
        }
        mStartedAt = 0;
        mFailed |= finished && status.startsWith("error");
        load();
        if (mApplying && getActivity() != null) {
            Toast.makeText(getActivity(), Texts.applied(getActivity(), Helper.result()),
                    Toast.LENGTH_LONG).show();
        }
        mApplying = false;
        show();
    }

    /** Copies the good files of a system (or of all, id null), asking before replacing. */
    protected void copy(String id) {
        if (mReport == null || busy()) return;
        final List<Copy> copies = mReport.copiesOf(id, false);
        final List<Copy> conflicts = mReport.copiesOf(id, true);
        if (conflicts.isEmpty()) {
            if (!copies.isEmpty()) run(Report.ops(copies, conflicts, noRenames()));
            return;
        }
        askReplace(copies, conflicts);
    }

    protected void askReplace(List<Copy> copies, List<Copy> conflicts) {
        final Context c = getActivity();
        final StringBuilder list = new StringBuilder();
        for (Copy x : conflicts) {
            final List<BiosTable.Bios> is = mTable.byMd5(x.existing.md5);
            list.append(c.getString(R.string.replace_line, x.bios.name,
                    is.isEmpty() ? c.getString(R.string.replace_unknown) : is.get(0).name));
        }
        final AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(R.string.replace_dialog_title)
                .setMessage(c.getString(R.string.replace_dialog_message, list))
                .setPositiveButton(R.string.replace_button,
                        (d, w) -> run(Report.ops(copies, conflicts, noRenames())))
                .setNegativeButton(android.R.string.cancel, null);
        if (!copies.isEmpty()) {
            b.setNeutralButton(R.string.keep_button,
                    (d, w) -> run(Report.ops(copies, Collections.emptyList(), noRenames())));
        }
        b.show();
    }

    protected void askRename(Rename rn) {
        if (busy()) return;
        final Context c = getActivity();
        new AlertDialog.Builder(c)
                .setTitle(R.string.rename_dialog_title)
                .setMessage(c.getString(R.string.rename_dialog_message, rn.file.name,
                        rn.to.name, rn.to.description))
                .setPositiveButton(R.string.rename_button, (d, w) -> run(Report.ops(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.singletonList(rn))))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    protected String copySummary(Context c, String id) {
        if (applying()) return c.getString(R.string.copy_running);
        final int copies = mReport.copiesOf(id, false).size();
        final int conflicts = mReport.copiesOf(id, true).size();
        if (copies == 0 && conflicts == 0) return c.getString(R.string.copy_nothing);
        final StringBuilder sb = new StringBuilder();
        if (copies > 0) {
            sb.append(c.getResources().getQuantityString(R.plurals.copy_summary, copies,
                    copies));
        }
        if (conflicts > 0) {
            if (sb.length() > 0) sb.append(". ");
            sb.append(c.getResources().getQuantityString(R.plurals.copy_summary_conflicts,
                    conflicts, conflicts));
        }
        return sb.toString();
    }

    private static List<Rename> noRenames() {
        return Collections.emptyList();
    }
}
