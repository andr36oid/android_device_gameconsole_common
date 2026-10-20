package org.andr36oid.emmc;

import android.app.ActionBar;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings > System > eMMC Installation: andr36oid from the SD card onto the built-in
 * eMMC. The writing and every check is done by andr36oid-emmcinstall (Installer); this
 * only shows what it does and asks before each step:
 *
 *   checks, three warnings, the layout, every write listed, then
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

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = this::poll;
    private final Installer.ProgressReader mReader = new Installer.ProgressReader();

    private LinearLayout mPage;
    private ScrollView mScroll;

    /** The command running, what follows when it ends well. */
    private String mCmd;
    private Runnable mAfter;
    private long mStartedAt, mCmdStart;
    private JSONObject mInspect;
    private List<Target> mTargets = new ArrayList<>();
    private int mTarget;

    private final Map<String, Op> mOps = new LinkedHashMap<>();
    private final List<String> mLogLines = new ArrayList<>();
    private TextView[] mStepRows;
    private ProgressBar mBar;
    private TextView mElapsed, mOpsView, mLogView, mDetailsView;
    private ScrollView mLogScroll;

    private static final class Op {
        String name, dev, sha, log, state = "start";
        long from, to, done, total, speed, began, ended;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        mScroll = new ScrollView(this);
        mPage = new LinearLayout(this);
        mPage.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        mPage.setPadding(pad, pad / 2, pad, pad);
        mScroll.addView(mPage);
        setContentView(mScroll);
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
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    // Where we are, from the installer's records

    private void route() {
        mCmd = null;
        if (Installer.fromEmmc()) {
            showEmmcSystem();
            return;
        }
        if (!Installer.fromSD()) {
            info(getString(R.string.inst_title), getString(R.string.inst_not_sd));
            return;
        }
        if (Installer.running()) {
            follow(null, null);
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
            showNext(getString(R.string.inst_step2_passed), this::showFinal);
        } else if ("written".equals(step2)) {
            run("check-test", () -> {
                final JSONObject r = Installer.result();
                if (r != null && "waiting".equals(r.optString("result"))) showRestart(Restart.TEST);
                else route();
            });
        } else if ("failed".equals(step2)) {
            showFailed(getString(R.string.inst_test_failed), true);
        } else if ("blanked".equals(step1)) {
            if (bootId().equals(st.optString("step1_boot_id"))) showRestart(Restart.STEP1);
            else run("check-loader", this::route);
        } else if ("passed".equals(step1)) {
            showNext(getString(R.string.inst_step1_passed),
                    () -> run("write-os", () -> showRestart(Restart.TEST)));
        } else if (st.optJSONObject("backup") != null) {
            showNext(getString(R.string.inst_backup_kept),
                    () -> run("blank-loader", () -> showRestart(Restart.STEP1)));
        } else {
            showChecks();
        }
    }

    private static String bootId() {
        final String s = Installer.read(new java.io.File("/proc/sys/kernel/random/boot_id"), 64);
        return s == null ? "" : s.trim();
    }

    // Page building

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private LinearLayout page(String title) {
        mHandler.removeCallbacks(mPoll);
        mPage.removeAllViews();
        mStepRows = null;
        setTitle(title);
        mScroll.scrollTo(0, 0);
        return mPage;
    }

    private TextView text(CharSequence s) {
        final TextView t = new TextView(this);
        t.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        t.setText(s);
        t.setPadding(0, dp(6), 0, dp(6));
        mPage.addView(t);
        return t;
    }

    private TextView heading(CharSequence s) {
        final TextView t = text(s);
        t.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView mono(ViewGroup parent, CharSequence s) {
        final TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setText(s);
        if (parent != null) parent.addView(t);
        return t;
    }

    private Button button(String label, Runnable r) {
        final Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        mPage.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void info(String title, String body) {
        page(title);
        text(body);
        button(getString(R.string.inst_close), this::finish).requestFocus();
    }

    private String size(long bytes) {
        return Formatter.formatFileSize(this, bytes);
    }

    private static long sectors(JSONObject p) {
        return p.optLong("Last") - p.optLong("First") + 1;
    }

    // Before anything is written

    private void showChecks() {
        page(getString(R.string.inst_title));
        text(getString(R.string.inst_checking));
        mInspect = null;
        run("inspect", () -> {
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
        page(getString(R.string.inst_title));
        heading(getString(R.string.inst_checks));
        if (mInspect == null) {
            text(getString(R.string.inst_inspect_failed));
            button(getString(R.string.inst_check_again), this::showChecks);
            return;
        }
        final JSONObject emmc = mInspect.optJSONObject("emmc");
        final JSONObject power = mInspect.optJSONObject("power");
        boolean ok = true;
        final StringBuilder sb = new StringBuilder();
        ok &= check(sb, "sd".equals(mInspect.optString("booted_from")), getString(R.string.inst_chk_sd));
        ok &= check(sb, emmc != null, emmc == null ? getString(R.string.inst_chk_no_emmc)
                : getString(R.string.inst_chk_emmc, emmc.optString("name"), size(emmcBytes())));
        ok &= check(sb, mInspect.optJSONArray("plan") != null, getString(R.string.inst_chk_size));
        final int cap = power == null ? -1 : power.optInt("capacity", -1);
        final boolean charger = power != null && power.optBoolean("charger");
        ok &= check(sb, charger || cap >= 60, charger ? getString(R.string.inst_chk_charger)
                : getString(R.string.inst_chk_battery, cap));
        final Target t = mTargets.isEmpty() ? null : mTargets.get(mTarget);
        final long need = emmcBytes() + (64L << 20);
        ok &= check(sb, t != null && t.freeBytes() >= need, t == null ? getString(R.string.inst_chk_no_target)
                : getString(R.string.inst_chk_room, t.name, size(t.freeBytes()), size(need)));
        text(sb.toString().trim());
        final List<String> problems = new ArrayList<>();
        final org.json.JSONArray pa = mInspect.optJSONArray("problems");
        for (int i = 0; pa != null && i < pa.length(); i++) problems.add(pa.optString(i));
        if (!problems.isEmpty()) {
            ok = false;
            mono(mPage, String.join("\n", problems));
        }
        if (mTargets.size() > 1) {
            button(getString(R.string.inst_change_target), () -> {
                mTarget = (mTarget + 1) % mTargets.size();
                showCheckResults();
            });
        }
        final Button go = button(getString(R.string.inst_continue), () -> showWarning(0));
        go.setEnabled(ok);
        button(getString(R.string.inst_check_again), this::showChecks);
        if (ok) go.requestFocus();
    }

    private static boolean check(StringBuilder sb, boolean ok, String line) {
        sb.append(ok ? "✓  " : "✗  ").append(line).append('\n');
        return ok;
    }

    private void showWarning(int n) {
        final int[][] w = {
            {R.string.inst_warn1_title, R.string.inst_warn1},
            {R.string.inst_warn2_title, R.string.inst_warn2},
            {R.string.inst_warn3_title, R.string.inst_warn3},
        };
        page(getString(w[n][0]));
        heading(getString(w[n][0]));
        text(getString(w[n][1]));
        button(getString(R.string.inst_understand), () -> {
            if (n + 1 < w.length) showWarning(n + 1);
            else showLayout();
        }).requestFocus();
        button(getString(R.string.inst_cancel), this::finish);
    }

    private void showLayout() {
        page(getString(R.string.inst_layout_title));
        heading(getString(R.string.inst_layout_title));
        text(getString(R.string.inst_layout_text));
        final StringBuilder sb = new StringBuilder();
        for (JSONObject p : Installer.list(mInspect, "plan")) {
            sb.append(String.format(Locale.ROOT, "%-9s %10s\n", p.optString("Name"), size(sectors(p) * 512)));
        }
        mono(mPage, sb.toString().trim());
        button(getString(R.string.inst_continue), this::showSummary).requestFocus();
        button(getString(R.string.inst_cancel), this::finish);
    }

    private void showSummary() {
        page(getString(R.string.inst_summary_title));
        heading(getString(R.string.inst_summary_title));
        final Target t = mTargets.get(mTarget);
        final long total = emmcBytes();
        final long secs = total / 512;
        final StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.inst_sum_backup, size(total), secs - 1, t.name)).append("\n\n");
        sb.append(getString(R.string.inst_sum_step1)).append("\n\n");
        final StringBuilder parts = new StringBuilder();
        for (JSONObject p : Installer.list(mInspect, "plan")) {
            parts.append(String.format(Locale.ROOT, "  %s %d-%d\n", p.optString("Name"),
                    p.optLong("First"), p.optLong("Last")));
        }
        sb.append(getString(R.string.inst_sum_step2, secs - 33, secs - 1, parts.toString())).append("\n");
        sb.append(getString(R.string.inst_sum_step3));
        mono(mPage, sb.toString());
        text(getString(R.string.inst_sum_risk));
        button(getString(R.string.inst_start), () -> run("backup",
                () -> run("blank-loader", () -> showRestart(Restart.STEP1)), t.path)).requestFocus();
        button(getString(R.string.inst_cancel), this::finish);
    }

    private void showNext(String what, Runnable go) {
        page(getString(R.string.inst_title));
        steps(mPage);
        text(what);
        button(getString(R.string.inst_continue), go).requestFocus();
        restoreButton();
        button(getString(R.string.inst_later), this::finish);
    }

    // Step 3: the one write that can't be tried out first

    private void showFinal() {
        page(getString(R.string.inst_final_title));
        heading(getString(R.string.inst_final_title));
        text(getString(R.string.inst_final_text));
        final JSONObject st = Installer.state();
        mono(mPage, getString(R.string.inst_final_loader, st.optString("loader_sha256")));
        final CheckBox fault = new CheckBox(this);
        fault.setText(R.string.inst_my_fault);
        mPage.addView(fault);
        final HoldButton hold = new HoldButton(this, getString(R.string.inst_hold));
        hold.setEnabled(false);
        mPage.addView(hold, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)));
        final TextView hint = text(getString(R.string.inst_hold_hint));
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        fault.setOnCheckedChangeListener((v, checked) -> hold.setEnabled(checked));
        hold.setListener(() -> {
            final String code = Installer.state().optString("loader_sha256");
            if (code.length() < 12) {
                showFailed(getString(R.string.inst_no_loader_code), false);
                return;
            }
            run("write-loader", () -> showRestart(Restart.DONE), code.substring(0, 12));
        });
        button(getString(R.string.inst_later), this::finish);
        fault.requestFocus();
    }

    // Restarts

    private enum Restart { STEP1, TEST, DONE, STOCK }

    private void showRestart(Restart kind) {
        page(getString(R.string.inst_restart_title));
        steps(mPage);
        final JSONObject st = Installer.state();
        final JSONObject b = st.optJSONObject("backup");
        final String dir = b == null ? "" : b.optString("dir");
        switch (kind) {
            case STEP1:
                text(getString(R.string.inst_restart_step1, dir));
                break;
            case TEST:
                text(getString(R.string.inst_restart_test));
                break;
            case DONE:
                text(getString(R.string.inst_restart_done));
                break;
            case STOCK:
                text(getString(R.string.inst_restart_stock));
                break;
        }
        button(getString(R.string.inst_restart_now), this::reboot).requestFocus();
        if (kind == Restart.TEST) {
            button(getString(R.string.inst_test_later), () -> run("cancel-test", this::finish));
        }
        button(getString(R.string.inst_later), this::finish);
    }

    private void reboot() {
        getSystemService(PowerManager.class).reboot(null);
    }

    private void showDone() {
        page(getString(R.string.inst_title));
        steps(mPage);
        text(getString(R.string.inst_done));
        restoreButton();
        button(getString(R.string.inst_close), this::finish).requestFocus();
    }

    private void showEmmcSystem() {
        page(getString(R.string.inst_title));
        final JSONObject c = Installer.confirmResult();
        if (c != null && "confirmed".equals(c.optString("result"))) {
            text(getString(R.string.inst_test_worked));
            button(getString(R.string.inst_restart_now), this::reboot).requestFocus();
            button(getString(R.string.inst_later), this::finish);
        } else {
            text(getString(R.string.inst_on_emmc));
            button(getString(R.string.inst_close), this::finish).requestFocus();
        }
    }

    // Stops and the way back

    private void showFailed(String why, boolean wrote) {
        page(getString(R.string.inst_failed_title));
        steps(mPage);
        heading(getString(R.string.inst_failed_title));
        mono(mPage, why);
        final JSONObject st = Installer.state();
        final JSONObject b = st.optJSONObject("backup");
        if (wrote && b != null) {
            text(getString(R.string.inst_failed_choice, b.optString("dir")));
            restoreButton();
            button(getString(R.string.inst_leave), () -> run("leave", this::route));
        } else {
            text(getString(R.string.inst_failed_nothing));
        }
        if (!mLogLines.isEmpty()) {
            button(getString(R.string.inst_show_log), () -> {
                mono(mPage, String.join("\n", mLogLines));
            });
        }
        button(getString(R.string.inst_close), this::finish).requestFocus();
    }

    private void restoreButton() {
        final JSONObject b = Installer.state().optJSONObject("backup");
        if (b == null) return;
        button(getString(R.string.inst_restore), () -> {
            page(getString(R.string.inst_restore));
            heading(getString(R.string.inst_restore));
            text(getString(R.string.inst_restore_text, b.optString("dir")));
            button(getString(R.string.inst_restore_go),
                    () -> run("restore", () -> showRestart(Restart.STOCK), b.optString("dir")));
            button(getString(R.string.inst_cancel), this::route).requestFocus();
        });
    }

    // Running a command, with its progress

    private void run(String cmd, Runnable after, String... args) {
        mOps.clear();
        mLogLines.clear();
        mReader.reset();
        if (!Installer.start(cmd, args)) {
            showFailed(getString(R.string.inst_cant_start), false);
            return;
        }
        follow(cmd, after);
    }

    /** Shows the progress of cmd (null: whatever runs) and then does after. */
    private void follow(String cmd, Runnable after) {
        mCmd = cmd == null ? "" : cmd;
        mAfter = after;
        mStartedAt = SystemClock.elapsedRealtime();
        mCmdStart = System.currentTimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showProgress();
        poll();
    }

    private void showProgress() {
        page(getString(R.string.inst_working));
        mStepRows = null;
        steps(mPage);
        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setMax(1000);
        mPage.addView(mBar);
        mElapsed = text("");
        mOpsView = mono(mPage, "");
        mOpsView.setPadding(0, dp(6), 0, dp(6));
        mLogScroll = new ScrollView(this);
        mLogScroll.setFocusable(true);
        mLogView = mono(null, "");
        mLogScroll.addView(mLogView);
        mPage.addView(mLogScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        mDetailsView = mono(null, "");
        mDetailsView.setVisibility(View.GONE);
        final Button details = button(getString(R.string.inst_details), () ->
                mDetailsView.setVisibility(mDetailsView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        mPage.addView(mDetailsView);
        details.requestFocus();
        updateProgress();
    }

    private void poll() {
        for (JSONObject e : mReader.poll()) apply(e);
        if (mStepRows != null) updateProgress();
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
        steps(null);
        final StringBuilder ops = new StringBuilder();
        Op current = null;
        for (Op op : mOps.values()) {
            ops.append(opLine(op)).append('\n');
            if (!"ok".equals(op.state) && !"fail".equals(op.state)) current = op;
        }
        mOpsView.setText(ops.toString().trim());
        final int step = stepOf(mCmd);
        final double frac = current == null || current.total <= 0 ? 0 : (double) current.done / current.total;
        mBar.setProgress((int) (1000 * (Math.max(step, 0) + frac) / 4));
        mElapsed.setText(getString(R.string.inst_elapsed,
                DateUtils.formatElapsedTime((System.currentTimeMillis() - mCmdStart) / 1000)));
        final boolean atEnd = !mLogScroll.canScrollVertically(1);
        mLogView.setText(String.join("\n", mLogLines));
        if (atEnd) mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
        mDetailsView.setText(details());
    }

    private String opLine(Op op) {
        final String name = String.format(Locale.ROOT, "%-34s", op.name);
        switch (op.state) {
            case "ok":
                return name + " " + size(op.total) + "  "
                        + DateUtils.formatElapsedTime(Math.max(0, op.ended - op.began) / 1000)
                        + shortSha(op.sha) + "  OK";
            case "fail":
                return name + " FAILED";
            default:
                String left = "";
                if (op.speed > 0 && op.total > op.done) {
                    left = "  " + getString(R.string.inst_left,
                            DateUtils.formatElapsedTime((op.total - op.done) / op.speed));
                }
                return String.format(Locale.ROOT, "%s %s %d-%d\n    %s / %s  %s/s%s", name, op.dev,
                        op.from, Math.max(op.from, op.to - 1), size(op.done), size(op.total),
                        size(op.speed), left);
        }
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

    /** The four steps with how far each is, at the top of the page (parent null: update). */
    private void steps(ViewGroup parent) {
        final int[] names = {R.string.inst_step0, R.string.inst_step1, R.string.inst_step2, R.string.inst_step3};
        if (parent != null) {
            mStepRows = new TextView[4];
            for (int i = 0; i < 4; i++) mStepRows[i] = mono(parent, "");
        }
        if (mStepRows == null) return;
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
        final int now = mCmd == null ? -1 : stepOf(mCmd);
        for (int i = 0; i < 4; i++) {
            String mark = "·", what = "";
            if (i == now && !"done".equals(s[i])) {
                mark = "▶";
                what = getString(R.string.inst_st_running);
            } else if ("done".equals(s[i])) {
                mark = "✓";
                what = getString(R.string.inst_st_done);
            } else if ("failed".equals(s[i])) {
                mark = "✗";
                what = getString(R.string.inst_st_failed);
            } else if ("restart".equals(s[i])) {
                mark = "↻";
                what = getString(R.string.inst_st_restart);
            }
            mStepRows[i].setText(mark + " " + getString(names[i]) + (what.isEmpty() ? "" : "  " + what));
        }
    }
}
