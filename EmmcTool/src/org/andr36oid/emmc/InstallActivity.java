package org.andr36oid.emmc;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings > System > eMMC Installation: andr36oid from the SD card onto the built-in
 * eMMC, as a wizard laid out like the first start tutorial. The writing and every check is
 * done by andr36oid-emmcinstall (Installer); this only shows what it does and asks before
 * each step:
 *
 *   checks, three warning pages, the layout and every write listed, then
 *   0 backup of the stock eMMC and 1 its loader blanked, restart (the SD card's loader
 *   must start the console alone), 2 andr36oid copied to the eMMC and started from there
 *   once, restart twice, 3 the loader onto the eMMC, after a checkbox and holding A for
 *   10 seconds.
 *
 * After a restart it opens by itself (BootReceiver) and runs the check for the step.
 */
public class InstallActivity extends Activity {
    private static final long POLL_MS = 500;
    /** How long the service may take to show up after ctl.start. */
    private static final long START_GRACE_MS = 5000;
    private static final int LOG_LINES = 400;
    private static final int WARN_COLOR = 0xFFE53935;

    // How far along the whole installation each page is, of 1000
    private static final int AT_CHECKS = 50, AT_WARN = 100, AT_PLAN = 250, AT_STEP = 300,
            AT_DONE = 1000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = this::poll;
    private final Installer.ProgressReader mReader = new Installer.ProgressReader();

    /** The command running, what follows when it ends well. */
    private String mCmd;
    private Runnable mAfter;
    private long mStartedAt, mCmdStart;
    private JSONObject mInspect;
    private List<Target> mTargets = new ArrayList<>();
    private int mTarget;

    private final Map<String, Op> mOps = new LinkedHashMap<>();
    private final List<String> mLogLines = new ArrayList<>();

    private TextView mStepLabel, mTitle, mBody;
    private ProgressBar mProgress;
    private ScrollView mTextScroll, mSideScroll;
    private LinearLayout mSide, mBodyExtra;
    private Button mBack, mMore, mSecondary, mNext;
    /** Something on the right takes the D-pad (the checkbox, the hold ring). */
    private boolean mSideFocus;
    private int mTitleColor;

    // The progress page's views, null on other pages
    private TextView mCurName, mCurLine;
    private ProgressBar mCurBar;
    private LinearLayout mFinished;
    private final Map<String, View> mFinishedRows = new HashMap<>();

    private static final class Op {
        String name, dev, sha, log, state = "start";
        long from, to, done, total, speed, began, ended;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.install_wizard);
        mStepLabel = findViewById(R.id.step_label);
        mProgress = findViewById(R.id.progress);
        mTitle = findViewById(R.id.title);
        mBody = findViewById(R.id.body);
        mTextScroll = findViewById(R.id.text_scroll);
        mSideScroll = findViewById(R.id.side_scroll);
        mSide = findViewById(R.id.side);
        mBodyExtra = findViewById(R.id.body_extra);
        mBack = findViewById(R.id.back);
        mMore = findViewById(R.id.more);
        mSecondary = findViewById(R.id.secondary);
        mNext = findViewById(R.id.next);
        mTitleColor = mTitle.getCurrentTextColor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mCmd == null) route();
        else poll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mPoll);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        final boolean first = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            if (first) {
                if (mBack.getVisibility() == View.VISIBLE) mBack.performClick();
                else finish();
            }
            return true;
        }
        if (!mSideFocus && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
            // The buttons are all in one row, so up and down scroll both sides instead
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                final int direction = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                mTextScroll.smoothScrollBy(0, direction * mTextScroll.getHeight() / 3);
                mSideScroll.smoothScrollBy(0, direction * mSideScroll.getHeight() / 3);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // Where we are, from the installer's records

    private void route() {
        mCmd = null;
        if (Installer.fromEmmc()) {
            showEmmcSystem();
            return;
        }
        if (!Installer.fromSD()) {
            page(null, -1, getString(R.string.inst_title), getString(R.string.inst_not_sd));
            button(mNext, getString(R.string.inst_close), this::finish);
            return;
        }
        if (Installer.running()) {
            follow(null, false, null);
            return;
        }
        final JSONObject st = Installer.state();
        final String running = st.optString("running"), failed = st.optString("failed");
        final String step1 = st.optString("step1"), step2 = st.optString("step2");
        if (!failed.isEmpty() || !running.isEmpty()) {
            showFailed(failed.isEmpty() ? getString(R.string.inst_cut_off, running) : failed, true);
        } else if ("done".equals(st.optString("step3"))) {
            showDone();
        } else if ("passed".equals(step2)) {
            showNext(3, getString(R.string.inst_step2_passed), this::showFinal);
        } else if ("written".equals(step2)) {
            run("check-test", true, () -> {
                final JSONObject r = Installer.result();
                if (r != null && "waiting".equals(r.optString("result"))) showRestart(Restart.TEST);
                else route();
            });
        } else if ("failed".equals(step2)) {
            showFailed(getString(R.string.inst_test_failed), true);
        } else if ("blanked".equals(step1)) {
            if (bootId().equals(st.optString("step1_boot_id"))) showRestart(Restart.STEP1);
            else run("check-loader", true, this::route);
        } else if ("passed".equals(step1)) {
            showNext(2, getString(R.string.inst_step1_passed),
                    () -> run("write-os", false, () -> showRestart(Restart.TEST)));
        } else if (st.optJSONObject("backup") != null) {
            showNext(1, getString(R.string.inst_backup_kept),
                    () -> run("blank-loader", false, () -> showRestart(Restart.STEP1)));
        } else {
            showChecks();
        }
    }

    private static String bootId() {
        final String s = Installer.read(new File("/proc/sys/kernel/random/boot_id"), 64);
        return s == null ? "" : s.trim();
    }

    // The page

    /** A new page; label null: none, at: how far along of 1000, -1 no bar. */
    private void page(String label, int at, CharSequence title, CharSequence body) {
        mHandler.removeCallbacks(mPoll);
        mCurName = mCurLine = null;
        mCurBar = null;
        mFinished = null;
        mSideFocus = false;
        mStepLabel.setText(label);
        mStepLabel.setVisibility(label == null ? View.GONE : View.VISIBLE);
        mProgress.setVisibility(at < 0 ? View.INVISIBLE : View.VISIBLE);
        mProgress.setIndeterminate(false);
        mProgress.setProgress(Math.max(0, at));
        mTitle.setText(title);
        mTitle.setTextColor(mTitleColor);
        mBody.setText(body);
        mTextScroll.scrollTo(0, 0);
        mSideScroll.scrollTo(0, 0);
        mSide.removeAllViews();
        mBodyExtra.removeAllViews();
        for (Button b : new Button[] {mBack, mMore, mSecondary, mNext}) {
            b.setVisibility(View.GONE);
            b.setEnabled(true);
            b.setOnClickListener(null);
        }
        mHandler.post(this::ensureFocus);
    }

    /**
     * Something on the page has the D-pad: else, pages without Next (the progress page)
     * could not reach their buttons, as up and down scroll the text.
     */
    private void ensureFocus() {
        final View f = getCurrentFocus();
        if (f != null && f.isShown() && f.isEnabled()) return;
        for (Button b : new Button[] {mNext, mSecondary, mMore, mBack}) {
            if (b.getVisibility() == View.VISIBLE && b.isEnabled()) {
                b.requestFocus();
                return;
            }
        }
    }

    /** Red title with a warning sign, for the pages that can cost the console. */
    private void warn() {
        mTitle.setText("⚠ " + mTitle.getText());
        mTitle.setTextColor(WARN_COLOR);
    }

    private void button(Button b, CharSequence text, Runnable click) {
        b.setText(text);
        b.setVisibility(View.VISIBLE);
        b.setOnClickListener(v -> click.run());
        if (b == mNext) b.requestFocus();
    }

    private TextView sideHeader(CharSequence text) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextAppearance(android.R.style.TextAppearance_Material_Body2);
        t.setTextColor(mBody.getTextColors());
        t.setPadding(0, mSide.getChildCount() == 0 ? 0 : dp(12), 0, dp(4));
        mSide.addView(t);
        return t;
    }

    /** A line on the right; icon 0: none. */
    private TextView sideRow(int icon, CharSequence title, CharSequence sub) {
        return row(mSide, icon, title, sub);
    }

    private TextView row(LinearLayout parent, int icon, CharSequence title, CharSequence sub) {
        final TextView t = new TextView(this);
        t.setText(sub == null || sub.length() == 0 ? title : title + "\n" + sub);
        t.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        t.setCompoundDrawablePadding(dp(8));
        t.setPadding(0, dp(3), 0, dp(3));
        parent.addView(t);
        return t;
    }

    private boolean check(boolean ok, CharSequence title, CharSequence sub) {
        sideRow(ok ? R.drawable.ic_inst_ok : R.drawable.ic_inst_bad, title, sub);
        return ok;
    }

    private String size(long bytes) {
        return Formatter.formatFileSize(this, bytes);
    }

    private static long sectors(JSONObject p) {
        return p.optLong("Last") - p.optLong("First") + 1;
    }

    private void dialog(CharSequence title, CharSequence text, boolean mono) {
        final TextView t = new TextView(this);
        t.setText(text);
        if (mono) {
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }
        final int pad = dp(20);
        t.setPadding(pad, pad / 2, pad, 0);
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(t);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(R.string.inst_close, null)
                .show();
        if (mono) scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private String stepLabel(int step) {
        return getString(R.string.inst_lbl_step, getString(stepName(step)));
    }

    private static int stepAt(int step) {
        return AT_STEP + step * 175;
    }

    // Before anything is written

    private void showChecks() {
        mInspect = null;
        run("inspect", true, () -> {
            final JSONObject r = Installer.result();
            mInspect = r == null ? null : r.optJSONObject("result");
            mTargets = Target.list(this);
            mTarget = 0;
            long best = -1;
            for (int i = 0; i < mTargets.size(); i++) {
                if (mTargets.get(i).freeBytes() > best) {
                    best = mTargets.get(i).freeBytes();
                    mTarget = i;
                }
            }
            showCheckResults();
        });
    }

    private long emmcBytes() {
        final JSONObject e = mInspect == null ? null : mInspect.optJSONObject("emmc");
        return e == null ? 0 : e.optLong("sectors") * 512;
    }

    private void showCheckResults() {
        final String label = getString(R.string.inst_lbl_checks);
        if (mInspect == null) {
            page(label, AT_CHECKS, getString(R.string.inst_checks), getString(R.string.inst_inspect_failed));
            button(mBack, getString(R.string.inst_close), this::finish);
            button(mNext, getString(R.string.inst_check_again), this::showChecks);
            return;
        }
        final List<String> problems = new ArrayList<>();
        final JSONArray pa = mInspect.optJSONArray("problems");
        for (int i = 0; pa != null && i < pa.length(); i++) problems.add(pa.optString(i));
        final StringBuilder body = new StringBuilder(getString(R.string.inst_summary));
        if (!problems.isEmpty()) {
            body.append("\n\n").append(getString(R.string.inst_problems)).append(':');
            for (String why : problems) body.append("\n• ").append(why);
        }
        page(label, AT_CHECKS, getString(R.string.inst_checks), body);

        final JSONObject emmc = mInspect.optJSONObject("emmc");
        final JSONObject power = mInspect.optJSONObject("power");
        boolean ok = check("sd".equals(mInspect.optString("booted_from")),
                getString(R.string.inst_chk_sd), null);
        ok &= check(emmc != null, emmc == null ? getString(R.string.inst_chk_no_emmc)
                : getString(R.string.inst_chk_emmc, emmc.optString("name"), size(emmcBytes())), null);
        ok &= check(mInspect.optJSONArray("plan") != null, getString(R.string.inst_chk_size), null);
        final int cap = power == null ? -1 : power.optInt("capacity", -1);
        final boolean charger = power != null && power.optBoolean("charger");
        ok &= check(charger || cap >= 60, charger ? getString(R.string.inst_chk_charger)
                : getString(R.string.inst_chk_battery, cap), null);
        ok &= destRow();
        ok &= problems.isEmpty();

        button(mBack, getString(R.string.inst_close), this::finish);
        button(mMore, getString(R.string.inst_check_again), this::showChecks);
        if (mTargets.size() > 1) button(mSecondary, getString(R.string.inst_change), this::pickTarget);
        button(mNext, getString(R.string.inst_continue), () -> showWarning(0));
        mNext.setEnabled(ok);
        if (!ok) mMore.requestFocus();
    }

    /** Where the backup goes, marked if there is room. */
    private boolean destRow() {
        if (mTargets.isEmpty()) return check(false, getString(R.string.inst_chk_no_target), null);
        final Target t = mTargets.get(mTarget);
        final long need = emmcBytes() + (64L << 20);
        return check(t.freeBytes() >= need, getString(R.string.dest_title) + ": " + t.name,
                getString(R.string.inst_chk_room, t.name, size(t.freeBytes()), size(need)));
    }

    private void pickTarget() {
        final CharSequence[] names = new CharSequence[mTargets.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = mTargets.get(i).name + " (" + size(mTargets.get(i).freeBytes()) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dest_title)
                .setSingleChoiceItems(names, mTarget, (d, which) -> {
                    mTarget = which;
                    d.dismiss();
                    showCheckResults();
                })
                .setNegativeButton(R.string.inst_cancel, null)
                .show();
    }

    private void showWarning(int n) {
        final int[][] w = {
            {R.string.inst_warn1_title, R.string.inst_warn1},
            {R.string.inst_warn2_title, R.string.inst_warn2},
            {R.string.inst_warn3_title, R.string.inst_warn3},
        };
        page(getString(R.string.inst_lbl_warning, n + 1, w.length), AT_WARN + n * 50,
                getString(w[n][0]), getString(w[n][1]));
        warn();
        button(mBack, getString(R.string.inst_cancel),
                n == 0 ? this::showCheckResults : () -> showWarning(n - 1));
        button(mNext, getString(R.string.inst_understand),
                n + 1 < w.length ? () -> showWarning(n + 1) : this::showPlan);
    }

    private void showPlan() {
        final Target t = mTargets.get(mTarget);
        final long secs = emmcBytes() / 512;
        final List<JSONObject> plan = Installer.list(mInspect, "plan");
        final StringBuilder parts = new StringBuilder();
        for (JSONObject p : plan) parts.append("  ").append(p.optString("Name")).append('\n');
        final String body = getString(R.string.inst_step0) + "\n"
                + getString(R.string.inst_sum_backup, size(emmcBytes()), secs - 1, t.name) + "\n\n"
                + getString(R.string.inst_step1) + "\n" + getString(R.string.inst_sum_step1) + "\n\n"
                + getString(R.string.inst_step2) + "\n"
                + getString(R.string.inst_sum_step2, secs - 33, secs - 1, parts.toString()) + "\n\n"
                + getString(R.string.inst_step3) + "\n" + getString(R.string.inst_sum_step3) + "\n\n"
                + getString(R.string.inst_sum_risk);
        page(getString(R.string.inst_lbl_plan), AT_PLAN, getString(R.string.inst_summary_title), body);
        sideHeader(getString(R.string.inst_layout_title));
        sideRow(0, getString(R.string.inst_layout_text), null);
        for (JSONObject p : plan) {
            sideRow(0, p.optString("Name"), getString(R.string.inst_sectors, size(sectors(p) * 512),
                    p.optLong("First"), p.optLong("Last")));
        }
        button(mBack, getString(R.string.inst_back), () -> showWarning(2));
        button(mNext, getString(R.string.inst_start), () -> run("backup", false,
                () -> run("blank-loader", false, () -> showRestart(Restart.STEP1)), t.path));
    }

    /** A step passed: what comes next. */
    private void showNext(int step, String what, Runnable go) {
        page(stepLabel(step), stepAt(step), getString(stepName(step)), what);
        steps();
        button(mBack, getString(R.string.inst_close), this::finish);
        restoreButton(mSecondary);
        button(mNext, getString(R.string.inst_continue), go);
    }

    // Step 3: the one write that can't be tried out first

    private void showFinal() {
        final JSONObject st = Installer.state();
        page(stepLabel(3), stepAt(3), getString(R.string.inst_final_title),
                getString(R.string.inst_final_text) + "\n\n"
                        + getString(R.string.inst_final_loader, st.optString("loader_sha256")));
        warn();
        final CheckBox fault = new CheckBox(this);
        fault.setText(R.string.inst_my_fault);
        fault.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        mSide.addView(fault);
        mSideFocus = true;
        button(mBack, getString(R.string.inst_close), this::finish);
        restoreButton(mSecondary);
        button(mNext, getString(R.string.inst_write_loader), this::showHold);
        mNext.setEnabled(false);
        fault.setOnCheckedChangeListener((v, on) -> mNext.setEnabled(on));
        fault.requestFocus();
    }

    private void showHold() {
        page(stepLabel(3), stepAt(3) + 50, getString(R.string.inst_write_loader),
                getString(R.string.inst_hold_hint));
        warn();
        final HoldButton hold = new HoldButton(this, getString(R.string.inst_hold));
        mSide.addView(hold, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));
        mSideFocus = true;
        hold.setListener(() -> {
            final String code = Installer.state().optString("loader_sha256");
            if (code.length() < 12) {
                showFailed(getString(R.string.inst_no_loader_code), false);
                return;
            }
            run("write-loader", false, () -> showRestart(Restart.DONE), code.substring(0, 12));
        });
        button(mBack, getString(R.string.inst_cancel), this::showFinal);
        hold.requestFocus();
    }

    // Restarts

    private enum Restart { STEP1, TEST, DONE, STOCK }

    private void showRestart(Restart kind) {
        final JSONObject b = Installer.state().optJSONObject("backup");
        final String dir = b == null ? "" : b.optString("dir");
        final String text;
        final int step;
        switch (kind) {
            case STEP1:
                text = getString(R.string.inst_restart_step1, dir);
                step = 1;
                break;
            case TEST:
                text = getString(R.string.inst_restart_test);
                step = 2;
                break;
            case DONE:
                text = getString(R.string.inst_restart_done);
                step = 3;
                break;
            default:
                text = getString(R.string.inst_restart_stock);
                step = -1;
                break;
        }
        page(step < 0 ? null : stepLabel(step), step < 0 ? -1 : step == 3 ? AT_DONE : stepAt(step) + 100,
                getString(R.string.inst_restart_title), text);
        if (step >= 0) steps();
        if (kind == Restart.TEST) {
            button(mSecondary, getString(R.string.inst_test_later),
                    () -> run("cancel-test", true, this::finish));
        }
        button(mNext, getString(R.string.inst_restart_now), this::reboot);
    }

    private void reboot() {
        getSystemService(PowerManager.class).reboot(null);
    }

    private void showDone() {
        page(getString(R.string.inst_lbl_done), AT_DONE, getString(R.string.inst_done_title),
                getString(R.string.inst_done));
        steps();
        // an updated image on the SD card: the eMMC's andr36oid can follow it
        button(mMore, getString(R.string.inst_update_emmc), () -> new AlertDialog.Builder(this)
                .setTitle(R.string.inst_update_emmc)
                .setMessage(R.string.inst_update_emmc_text)
                .setPositiveButton(R.string.inst_update_emmc_go,
                        (d, x) -> run("update-emmc", false, this::showDone))
                .setNegativeButton(R.string.inst_cancel, null)
                .show());
        restoreButton(mSecondary);
        button(mNext, getString(R.string.inst_close), this::finish);
    }

    private void showEmmcSystem() {
        final JSONObject c = Installer.confirmResult();
        if (c != null && "confirmed".equals(c.optString("result"))) {
            page(stepLabel(2), stepAt(2) + 150, getString(R.string.inst_title),
                    getString(R.string.inst_test_worked));
            button(mNext, getString(R.string.inst_restart_now), this::reboot);
        } else {
            page(null, -1, getString(R.string.inst_title), getString(R.string.inst_on_emmc));
            button(mNext, getString(R.string.inst_close), this::finish);
        }
    }

    // Stops and the way back

    private void showFailed(String why, boolean wrote) {
        final JSONObject b = Installer.state().optJSONObject("backup");
        final boolean choice = wrote && b != null;
        page(null, -1, getString(R.string.inst_failed_title), why + "\n\n" + (choice
                ? getString(R.string.inst_failed_choice, b.optString("dir"))
                : getString(R.string.inst_failed_nothing)));
        warn();
        steps();
        if (!mLogLines.isEmpty()) button(mMore, getString(R.string.inst_show_log), this::showLog);
        if (choice) {
            restoreButton(mSecondary);
            button(mNext, getString(R.string.inst_leave), () -> run("leave", true, this::route));
        } else {
            button(mNext, getString(R.string.inst_close), this::finish);
        }
    }

    /** The way back to the stock eMMC, on b, once there is a backup. */
    private void restoreButton(Button b) {
        final JSONObject backup = Installer.state().optJSONObject("backup");
        if (backup == null) return;
        final String dir = backup.optString("dir");
        button(b, getString(R.string.inst_restore), () -> new AlertDialog.Builder(this)
                .setTitle(R.string.inst_restore)
                .setMessage(getString(R.string.inst_restore_text, dir))
                .setPositiveButton(R.string.inst_restore_go,
                        (d, x) -> run("restore", false, () -> showRestart(Restart.STOCK), dir))
                .setNegativeButton(R.string.inst_cancel, null)
                .show());
    }

    private void showLog() {
        dialog(getString(R.string.inst_log), String.join("\n", mLogLines), true);
    }

    // Running a command, with its progress

    /** Starts cmd; quick: a short check, shown as one line instead of the progress page. */
    private void run(String cmd, boolean quick, Runnable after, String... args) {
        mOps.clear();
        mLogLines.clear();
        mReader.reset();
        if (!Installer.start(cmd, args)) {
            showFailed(getString(R.string.inst_cant_start), false);
            return;
        }
        follow(cmd, quick, after);
    }

    /** Shows the progress of cmd (null: whatever runs) and then does after. */
    private void follow(String cmd, boolean quick, Runnable after) {
        mCmd = cmd == null ? "" : cmd;
        mAfter = after;
        mStartedAt = SystemClock.elapsedRealtime();
        mCmdStart = System.currentTimeMillis();
        if (quick) {
            page(null, 0, getString(R.string.inst_checking), null);
            mProgress.setIndeterminate(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showProgress();
        }
        poll();
    }

    private void showProgress() {
        final int step = stepOf(mCmd);
        page(step < 0 ? null : stepLabel(step), step < 0 ? -1 : stepAt(step),
                step < 0 ? getString(R.string.inst_working) : getString(stepName(step)),
                getString(R.string.inst_keep_on));
        // left: what runs now; right: what is finished
        mCurName = row(mBodyExtra, 0, getString(R.string.inst_starting), null);
        mCurName.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        mCurBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mCurBar.setMax(1000);
        mBodyExtra.addView(mCurBar);
        mCurLine = row(mBodyExtra, 0, "", null);
        sideHeader(getString(R.string.inst_finished));
        mFinished = new LinearLayout(this);
        mFinished.setOrientation(LinearLayout.VERTICAL);
        mSide.addView(mFinished);
        mFinishedRows.clear();
        button(mMore, getString(R.string.inst_log), this::showLog);
        button(mSecondary, getString(R.string.inst_details),
                () -> dialog(getString(R.string.inst_details), details(), true));
        updateProgress();
    }

    private void poll() {
        for (JSONObject e : mReader.poll()) apply(e);
        if (mCurBar != null) updateProgress();
        final JSONObject r = Installer.result();
        final boolean running = Installer.running();
        final boolean ours = r != null && (mCmd.isEmpty() || mCmd.equals(r.optString("cmd")));
        if (!running && ours) {
            done(r);
            return;
        }
        if (!running && SystemClock.elapsedRealtime() - mStartedAt > START_GRACE_MS) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mCmd = null;
            if (mAfter == null) route();
            else showFailed(getString(R.string.inst_no_result), true);
            return;
        }
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    private void done(JSONObject r) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final Runnable after = mAfter;
        mCmd = null;
        mAfter = null;
        if (!r.optBoolean("ok")) {
            final JSONObject st = Installer.state();
            showFailed(r.optString("error"),
                    !st.optString("failed").isEmpty() || !st.optString("running").isEmpty());
        } else if (after != null) {
            after.run();
        } else {
            route();
        }
    }

    private void apply(JSONObject e) {
        if (mCmd.isEmpty()) mCmd = e.optString("step");
        final String state = e.optString("state");
        final String name = e.optString("op");
        final String time = e.optString("time");
        final String clock = time.length() >= 19 ? time.substring(11, 19) : time;
        if (!name.isEmpty()) {
            Op op = mOps.get(name);
            if (op == null || "start".equals(state)) {
                op = new Op();
                op.name = name;
                op.began = System.currentTimeMillis();
                mOps.put(name, op);
            }
            op.state = state;
            op.dev = e.optString("dev");
            op.from = e.optLong("from");
            op.to = e.optLong("to");
            op.total = e.optLong("total");
            op.done = e.optLong("done");
            op.speed = e.optLong("speed");
            if (!"run".equals(state)) {
                op.sha = e.optString("sha256");
                op.log = e.optString("log");
            }
            if ("ok".equals(state) || "fail".equals(state)) op.ended = System.currentTimeMillis();
        }
        String line = null;
        switch (state) {
            case "start":
                line = String.format(Locale.ROOT, "%s  %s  (%s %d-%d)", clock, name, e.optString("dev"),
                        e.optLong("from"), Math.max(e.optLong("from"), e.optLong("to") - 1));
                break;
            case "ok":
                line = clock + "  OK " + name + shortSha(e.optString("sha256")) + "  " + e.optString("log");
                break;
            case "fail":
                line = clock + "  FAILED " + name + ": " + e.optString("log");
                break;
            case "":
                line = clock + "  " + e.optString("log");
                break;
        }
        if (line != null) {
            mLogLines.add(line.trim());
            if (mLogLines.size() > LOG_LINES) mLogLines.remove(0);
        }
    }

    private static String shortSha(String sha) {
        return sha.length() >= 12 ? "  " + sha.substring(0, 12) : "";
    }

    private void updateProgress() {
        Op current = null;
        for (Op op : mOps.values()) {
            if ("ok".equals(op.state) || "fail".equals(op.state)) {
                if (!mFinishedRows.containsKey(op.name)) {
                    final TextView row = sideRow("ok".equals(op.state) ? R.drawable.ic_inst_ok
                            : R.drawable.ic_inst_bad, op.name, finishedLine(op));
                    mSide.removeView(row);
                    mFinished.addView(row);
                    mFinishedRows.put(op.name, row);
                    mSideScroll.post(() -> mSideScroll.fullScroll(View.FOCUS_DOWN));
                }
            } else {
                current = op;
            }
        }
        final int step = stepOf(mCmd);
        final double frac = current == null || current.total <= 0 ? 0 : (double) current.done / current.total;
        if (step >= 0) mProgress.setProgress(stepAt(step) + (int) (175 * frac));
        final String elapsed = getString(R.string.inst_elapsed,
                DateUtils.formatElapsedTime((System.currentTimeMillis() - mCmdStart) / 1000));
        if (current == null) {
            mCurName.setText(getString(R.string.inst_starting));
            mCurBar.setIndeterminate(true);
            mCurLine.setText(elapsed);
        } else {
            mCurName.setText(current.name);
            mCurBar.setIndeterminate(current.total <= 0);
            mCurBar.setProgress((int) (1000 * frac));
            mCurLine.setText(runningLine(current) + "\n" + elapsed);
        }
    }

    private String runningLine(Op op) {
        final StringBuilder sb = new StringBuilder();
        if (op.total > 0) {
            sb.append(getString(R.string.inst_op_run, size(op.done), size(op.total), size(op.speed)));
            if (op.speed > 0 && op.total > op.done) {
                sb.append(", ").append(getString(R.string.inst_left,
                        DateUtils.formatElapsedTime((op.total - op.done) / op.speed)));
            }
            sb.append('\n');
        }
        sb.append(getString(R.string.inst_sectors, op.dev, op.from, Math.max(op.from, op.to - 1)));
        return sb.toString();
    }

    private String finishedLine(Op op) {
        if ("fail".equals(op.state)) return getString(R.string.inst_op_failed, op.log);
        final String took = DateUtils.formatElapsedTime(Math.max(0, op.ended - op.began) / 1000);
        String line = op.sha != null && op.sha.length() >= 12
                ? getString(R.string.inst_op_ok_sha, size(op.total), took, op.sha.substring(0, 12))
                : getString(R.string.inst_op_ok, size(op.total), took);
        if (op.log != null && !op.log.isEmpty()) line += "\n" + op.log;
        return line;
    }

    private String details() {
        final JSONObject st = Installer.state();
        final StringBuilder sb = new StringBuilder();
        sb.append("eMMC CID   ").append(st.optString("cid")).append('\n');
        sb.append("sectors    ").append(st.optLong("sectors")).append('\n');
        sb.append("SD loader  ").append(st.optString("loader_sha256")).append('\n');
        final JSONObject b = st.optJSONObject("backup");
        if (b != null) {
            sb.append("backup     ").append(b.optString("dir")).append('\n');
            sb.append("           sha256 ").append(b.optString("sha256")).append('\n');
        }
        if (mInspect != null) {
            sb.append("eMMC loader ").append(mInspect.optString("emmc_loader")).append('\n');
            sb.append("eMMC table before:\n");
            for (JSONObject p : Installer.list(mInspect, "emmc_table")) {
                sb.append(String.format(Locale.ROOT, "  %2d %-12s %d-%d\n", p.optInt("Num"),
                        p.optString("Name"), p.optLong("First"), p.optLong("Last")));
            }
        }
        sb.append("eMMC table after:\n");
        for (JSONObject p : Installer.list(st, "plan")) {
            sb.append(String.format(Locale.ROOT, "  %2d %-12s %d-%d\n", p.optInt("Num"),
                    p.optString("Name"), p.optLong("First"), p.optLong("Last")));
        }
        if (!mTargets.isEmpty()) {
            final Target t = mTargets.get(mTarget);
            sb.append("free on ").append(t.name).append("  ").append(size(t.freeBytes())).append('\n');
        }
        return sb.toString().trim();
    }

    private static int stepOf(String cmd) {
        if (cmd == null) return -1;
        switch (cmd) {
            case "backup":
                return 0;
            case "blank-loader":
            case "check-loader":
                return 1;
            case "write-os":
            case "check-test":
            case "start-test":
            case "cancel-test":
                return 2;
            case "write-loader":
                return 3;
            default:
                return -1;
        }
    }

    private static int stepName(int step) {
        final int[] names = {R.string.inst_step0, R.string.inst_step1, R.string.inst_step2, R.string.inst_step3};
        return names[step];
    }

    /** The four steps and how far each is, on the right. */
    private void steps() {
        sideHeader(getString(R.string.inst_steps));
        final JSONObject st = Installer.state();
        final JSONObject b = st.optJSONObject("backup");
        final String[] s = {
            b != null && b.optBoolean("verified") ? "done" : "",
            "passed".equals(st.optString("step1")) ? "done"
                    : "blanked".equals(st.optString("step1")) ? "restart" : "",
            "passed".equals(st.optString("step2")) ? "done"
                    : "failed".equals(st.optString("step2")) ? "failed"
                    : "written".equals(st.optString("step2")) ? "restart" : "",
            "done".equals(st.optString("step3")) ? "done" : "",
        };
        for (int i = 0; i < 4; i++) {
            final int what, icon;
            if ("done".equals(s[i])) {
                what = R.string.inst_st_done;
                icon = R.drawable.ic_inst_ok;
            } else if ("failed".equals(s[i])) {
                what = R.string.inst_st_failed;
                icon = R.drawable.ic_inst_bad;
            } else {
                what = "restart".equals(s[i]) ? R.string.inst_st_restart : R.string.inst_st_todo;
                icon = 0;
            }
            sideRow(icon, getString(stepName(i)), getString(what));
        }
    }
}
