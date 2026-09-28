package org.andr36oid.bioscheck;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.util.Log;
import android.widget.ListView;
import android.widget.Toast;

import org.andr36oid.bioscheck.Report.Rename;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * What both pages share: the BIOS table, running the helper and waiting for it, and
 * fixing file names. Every time a page is shown it checks the bios folder again.
 * Subclasses show the report.
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
    private boolean mRenaming;
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
        } else {
            run(null);
        }
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mPoll);
        super.onPause();
    }

    /** Fills the screen from mReport (null before the first scan). */
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

    /** True when there is a scan and EASYROMS was there for it. */
    protected boolean mounted() {
        return mScan != null && mScan.biosDir != null;
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

    /** Scans (ops null) or renames and scans. */
    private void run(String ops) {
        mRenaming = ops != null;
        mFailed = false;
        if (!(ops != null ? Helper.rename(ops) : Helper.scan())) {
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
        if (mRenaming && getActivity() != null) {
            final String text = Texts.renamed(getActivity(), Helper.result());
            if (!text.isEmpty()) Toast.makeText(getActivity(), text, Toast.LENGTH_LONG).show();
        }
        mRenaming = false;
        show();
    }

    /** A row that is only there to read. */
    protected static Preference text(Context c, CharSequence title, CharSequence summary) {
        final Preference p = new Preference(c);
        p.setSelectable(false);
        p.setTitle(title);
        p.setSummary(summary);
        return p;
    }

    /** Lists the renames and does them after a yes. */
    protected void askFixNames() {
        if (mReport == null || mReport.renames.isEmpty() || busy()) return;
        final Context c = getActivity();
        final StringBuilder list = new StringBuilder();
        for (Rename rn : mReport.renames) {
            list.append(c.getString(R.string.fix_line, mScan.inBios(rn.file), rn.to.name));
        }
        final String ops = Report.ops(mReport.renames);
        new AlertDialog.Builder(c)
                .setTitle(R.string.fix_dialog_title)
                .setMessage(c.getString(R.string.fix_dialog_message, list))
                .setPositiveButton(R.string.fix_button, (d, w) -> run(ops))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
