package org.andr36oid.emmc;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.FrameLayout;
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
 * eMMC. The writing and every check is done by andr36oid-emmcinstall (Installer); this
 * only shows what it does and asks before each step:
 *
 *   checks, three warnings, the layout and every write listed, then
 *   0 backup of the stock eMMC and 1 its loader blanked, restart (the SD card's loader
 *   must start the console alone), 2 andr36oid copied to the eMMC and started from there
 *   once, restart twice, 3 the loader onto the eMMC, after a checkbox and holding A for
 *   10 seconds.
 *
 * Every page is a Settings list like the Built-in eMMC page, so the buttons work as
 * everywhere else. After a restart it opens by itself (BootReceiver) and runs the check
 * for the step.
 */
public class InstallFragment extends PreferenceFragment {
    private static final long POLL_MS = 500;
    /** How long the service may take to show up after ctl.start. */
    private static final long START_GRACE_MS = 5000;
    private static final int LOG_LINES = 400;

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

    // The progress page's rows, null on other pages
    private Preference[] mStepRows;
    private ProgressPreference mOverall, mCurrent;
    private PreferenceCategory mFinished;
    private final Map<String, Preference> mFinishedRows = new HashMap<>();
    private Preference mLogRow;

    private static final class Op {
        String name, dev, sha, log, state = "start";
        long from, to, done, total, speed, began, ended;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mCmd == null) route();
        else poll();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mPoll);
    }

    // Where we are, from the installer's records

    private void route() {
        mCmd = null;
        if (Installer.fromEmmc()) {
            showEmmcSystem();
            return;
        }
        if (!Installer.fromSD()) {
            final PreferenceScreen s = screen(R.string.inst_title);
            text(s, null, getString(R.string.inst_not_sd));
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
            showNext(getString(R.string.inst_step2_passed), this::showFinal);
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
            showNext(getString(R.string.inst_step1_passed),
                    () -> run("write-os", false, () -> showRestart(Restart.TEST)));
        } else if (st.optJSONObject("backup") != null) {
            showNext(getString(R.string.inst_backup_kept),
                    () -> run("blank-loader", false, () -> showRestart(Restart.STEP1)));
        } else {
            showChecks();
        }
    }

    private static String bootId() {
        final String s = Installer.read(new File("/proc/sys/kernel/random/boot_id"), 64);
        return s == null ? "" : s.trim();
    }

    // Rows

    /** A new, empty page. */
    private PreferenceScreen screen(int title) {
        mHandler.removeCallbacks(mPoll);
        mStepRows = null;
        mOverall = mCurrent = null;
        mFinished = null;
        mLogRow = null;
        getActivity().setTitle(title);
        final PreferenceScreen s = getPreferenceManager().createPreferenceScreen(getActivity());
        setPreferenceScreen(s);
        return s;
    }

    private PreferenceCategory category(PreferenceGroup g, CharSequence title) {
        final PreferenceCategory c = new PreferenceCategory(getActivity());
        c.setTitle(title);
        g.addPreference(c);
        return c;
    }

    /** A row to read, not cut short. */
    private Preference text(PreferenceGroup g, CharSequence title, CharSequence body) {
        final Preference p = new Preference(getActivity());
        p.setLayoutResource(R.layout.install_text);
        p.setSelectable(false);
        p.setPersistent(false);
        p.setTitle(title);
        p.setSummary(body);
        g.addPreference(p);
        return p;
    }

    /** A row; click null: only shows something. */
    private Preference row(PreferenceGroup g, CharSequence title, CharSequence summary, Runnable click) {
        final Preference p = new Preference(getActivity());
        p.setPersistent(false);
        p.setTitle(title);
        p.setSummary(summary);
        if (click == null) {
            p.setSelectable(false);
        } else {
            p.setOnPreferenceClickListener(x -> {
                click.run();
                return true;
            });
        }
        g.addPreference(p);
        return p;
    }

    private boolean check(PreferenceGroup g, boolean ok, CharSequence title, CharSequence summary) {
        row(g, title, summary, null).setIcon(ok ? R.drawable.ic_inst_ok : R.drawable.ic_inst_bad);
        return ok;
    }

    private String size(long bytes) {
        return Formatter.formatFileSize(getActivity(), bytes);
    }

    private static long sectors(JSONObject p) {
        return p.optLong("Last") - p.optLong("First") + 1;
    }

    private void dialog(CharSequence title, CharSequence text, boolean mono) {
        final TextView t = new TextView(getActivity());
        t.setText(text);
        t.setTextIsSelectable(false);
        if (mono) {
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }
        final int pad = dp(20);
        t.setPadding(pad, pad / 2, pad, 0);
        final ScrollView scroll = new ScrollView(getActivity());
        scroll.addView(t);
        new AlertDialog.Builder(getActivity())
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

    // Before anything is written

    private void showChecks() {
        mInspect = null;
        run("inspect", true, () -> {
            final JSONObject r = Installer.result();
            mInspect = r == null ? null : r.optJSONObject("result");
            mTargets = Target.list(getActivity());
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
        final PreferenceScreen s = screen(R.string.inst_title);
        if (mInspect == null) {
            text(s, null, getString(R.string.inst_inspect_failed));
            row(s, getString(R.string.inst_check_again), null, this::showChecks);
            return;
        }
        final PreferenceCategory c = category(s, getString(R.string.inst_checks));
        final JSONObject emmc = mInspect.optJSONObject("emmc");
        final JSONObject power = mInspect.optJSONObject("power");
        boolean ok = check(c, "sd".equals(mInspect.optString("booted_from")),
                getString(R.string.inst_chk_sd), null);
        ok &= check(c, emmc != null, emmc == null ? getString(R.string.inst_chk_no_emmc)
                : getString(R.string.inst_chk_emmc, emmc.optString("name"), size(emmcBytes())), null);
        ok &= check(c, mInspect.optJSONArray("plan") != null, getString(R.string.inst_chk_size), null);
        final int cap = power == null ? -1 : power.optInt("capacity", -1);
        final boolean charger = power != null && power.optBoolean("charger");
        ok &= check(c, charger || cap >= 60, charger ? getString(R.string.inst_chk_charger)
                : getString(R.string.inst_chk_battery, cap), null);
        ok &= destRow(c);

        final List<String> problems = new ArrayList<>();
        final JSONArray pa = mInspect.optJSONArray("problems");
        for (int i = 0; pa != null && i < pa.length(); i++) problems.add(pa.optString(i));
        if (!problems.isEmpty()) {
            ok = false;
            final PreferenceCategory p = category(s, getString(R.string.inst_problems));
            for (String why : problems) text(p, null, why);
        }
        final PreferenceCategory next = category(s, getString(R.string.inst_next));
        row(next, getString(R.string.inst_continue), null, () -> showWarning(0)).setEnabled(ok);
        row(next, getString(R.string.inst_check_again), null, this::showChecks);
    }

    /** Where the backup goes: a list to pick from, marked if there is room. */
    private boolean destRow(PreferenceGroup g) {
        if (mTargets.isEmpty()) {
            return check(g, false, getString(R.string.inst_chk_no_target), null);
        }
        final Target t = mTargets.get(mTarget);
        final long need = emmcBytes() + (64L << 20);
        final boolean ok = t.freeBytes() >= need;
        final ListPreference dest = new ListPreference(getActivity());
        dest.setPersistent(false);
        dest.setTitle(getString(R.string.dest_title) + ": " + t.name);
        dest.setDialogTitle(R.string.dest_title);
        dest.setSummary(getString(R.string.inst_chk_room, t.name, size(t.freeBytes()), size(need)));
        dest.setIcon(ok ? R.drawable.ic_inst_ok : R.drawable.ic_inst_bad);
        final CharSequence[] names = new CharSequence[mTargets.size()];
        final CharSequence[] ids = new CharSequence[mTargets.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = mTargets.get(i).name + " (" + size(mTargets.get(i).freeBytes()) + ")";
            ids[i] = String.valueOf(i);
        }
        dest.setEntries(names);
        dest.setEntryValues(ids);
        dest.setValue(String.valueOf(mTarget));
        dest.setOnPreferenceChangeListener((p, v) -> {
            mTarget = Integer.parseInt((String) v);
            mHandler.post(this::showCheckResults);
            return true;
        });
        g.addPreference(dest);
        return ok;
    }

    private void showWarning(int n) {
        final int[][] w = {
            {R.string.inst_warn1_title, R.string.inst_warn1},
            {R.string.inst_warn2_title, R.string.inst_warn2},
            {R.string.inst_warn3_title, R.string.inst_warn3},
        };
        new AlertDialog.Builder(getActivity())
                .setTitle(w[n][0])
                .setMessage(w[n][1])
                .setPositiveButton(R.string.inst_understand, (d, x) -> {
                    if (n + 1 < w.length) showWarning(n + 1);
                    else showPlan();
                })
                .setNegativeButton(R.string.inst_cancel, null)
                .show();
    }

    private void showPlan() {
        final PreferenceScreen s = screen(R.string.inst_summary_title);
        final PreferenceCategory l = category(s, getString(R.string.inst_layout_title));
        text(l, null, getString(R.string.inst_layout_text));
        final List<JSONObject> plan = Installer.list(mInspect, "plan");
        final StringBuilder parts = new StringBuilder();
        for (JSONObject p : plan) {
            row(l, p.optString("Name"), getString(R.string.inst_sectors, size(sectors(p) * 512),
                    p.optLong("First"), p.optLong("Last")), null);
            parts.append("  ").append(p.optString("Name")).append('\n');
        }
        final Target t = mTargets.get(mTarget);
        final long secs = emmcBytes() / 512;
        final PreferenceCategory w = category(s, getString(R.string.inst_summary_title));
        text(w, getString(R.string.inst_step0), getString(R.string.inst_sum_backup, size(emmcBytes()), secs - 1, t.name));
        text(w, getString(R.string.inst_step1), getString(R.string.inst_sum_step1));
        text(w, getString(R.string.inst_step2), getString(R.string.inst_sum_step2, secs - 33, secs - 1, parts.toString()));
        text(w, getString(R.string.inst_step3), getString(R.string.inst_sum_step3));
        text(w, null, getString(R.string.inst_sum_risk));
        final PreferenceCategory next = category(s, getString(R.string.inst_next));
        row(next, getString(R.string.inst_start), getString(R.string.inst_start_summary),
                () -> run("backup", false,
                        () -> run("blank-loader", false, () -> showRestart(Restart.STEP1)), t.path));
    }

    private void showNext(String what, Runnable go) {
        final PreferenceScreen s = screen(R.string.inst_title);
        steps(s);
        final PreferenceCategory n = category(s, getString(R.string.inst_next));
        text(n, null, what);
        row(n, getString(R.string.inst_continue), null, go);
        restoreRow(n);
    }

    // Step 3: the one write that can't be tried out first

    private void showFinal() {
        final PreferenceScreen s = screen(R.string.inst_final_title);
        final JSONObject st = Installer.state();
        text(s, getString(R.string.inst_final_title), getString(R.string.inst_final_text));
        text(s, null, getString(R.string.inst_final_loader, st.optString("loader_sha256")));
        final CheckBoxPreference fault = new CheckBoxPreference(getActivity());
        fault.setPersistent(false);
        fault.setChecked(false);
        fault.setTitle(R.string.inst_my_fault);
        s.addPreference(fault);
        final Preference write = row(s, getString(R.string.inst_write_loader),
                getString(R.string.inst_hold_hint), this::showHold);
        write.setEnabled(false);
        fault.setOnPreferenceChangeListener((p, v) -> {
            write.setEnabled((Boolean) v);
            return true;
        });
    }

    private void showHold() {
        final HoldButton hold = new HoldButton(getActivity(), getString(R.string.inst_hold));
        final FrameLayout frame = new FrameLayout(getActivity());
        frame.setPadding(0, dp(12), 0, dp(12));
        frame.addView(hold, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(150)));
        final AlertDialog d = new AlertDialog.Builder(getActivity())
                .setTitle(R.string.inst_write_loader)
                .setMessage(R.string.inst_hold_hint)
                .setView(frame)
                .setNegativeButton(R.string.inst_cancel, null)
                .create();
        hold.setListener(() -> {
            d.dismiss();
            final String code = Installer.state().optString("loader_sha256");
            if (code.length() < 12) {
                showFailed(getString(R.string.inst_no_loader_code), false);
                return;
            }
            run("write-loader", false, () -> showRestart(Restart.DONE), code.substring(0, 12));
        });
        d.setOnShowListener(x -> hold.requestFocus());
        d.show();
    }

    // Restarts

    private enum Restart { STEP1, TEST, DONE, STOCK }

    private void showRestart(Restart kind) {
        final PreferenceScreen s = screen(R.string.inst_restart_title);
        steps(s);
        final JSONObject b = Installer.state().optJSONObject("backup");
        final String dir = b == null ? "" : b.optString("dir");
        final PreferenceCategory c = category(s, getString(R.string.inst_restart_title));
        switch (kind) {
            case STEP1:
                text(c, null, getString(R.string.inst_restart_step1, dir));
                break;
            case TEST:
                text(c, null, getString(R.string.inst_restart_test));
                break;
            case DONE:
                text(c, null, getString(R.string.inst_restart_done));
                break;
            case STOCK:
                text(c, null, getString(R.string.inst_restart_stock));
                break;
        }
        row(c, getString(R.string.inst_restart_now), null, this::reboot);
        if (kind == Restart.TEST) {
            row(c, getString(R.string.inst_test_later), null,
                    () -> run("cancel-test", true, () -> getActivity().finish()));
        }
    }

    private void reboot() {
        getActivity().getSystemService(PowerManager.class).reboot(null);
    }

    private void showDone() {
        run("inspect", true, () -> {
            final JSONObject r = Installer.result();
            final JSONObject in = r == null ? null : r.optJSONObject("result");
            final PreferenceScreen s = screen(R.string.inst_title);
            steps(s);
            final PreferenceCategory c = category(s, getString(R.string.inst_st_done));
            text(c, null, getString(R.string.inst_done));
            // a newer U-Boot on the SD card (an updated image): the eMMC's can follow it
            if (in != null && !"andr36oid".equals(in.optString("emmc_loader"))) {
                row(c, getString(R.string.inst_update_uboot), getString(R.string.inst_update_uboot_sum),
                        () -> new AlertDialog.Builder(getActivity())
                                .setTitle(R.string.inst_update_uboot)
                                .setMessage(R.string.inst_update_uboot_text)
                                .setPositiveButton(R.string.inst_update_uboot_go,
                                        (d, x) -> run("update-uboot", false, this::showDone))
                                .setNegativeButton(R.string.inst_cancel, null)
                                .show());
            }
            restoreRow(c);
        });
    }

    private void showEmmcSystem() {
        final PreferenceScreen s = screen(R.string.inst_title);
        final JSONObject c = Installer.confirmResult();
        if (c != null && "confirmed".equals(c.optString("result"))) {
            text(s, null, getString(R.string.inst_test_worked));
            row(s, getString(R.string.inst_restart_now), null, this::reboot);
        } else {
            text(s, null, getString(R.string.inst_on_emmc));
        }
    }

    // Stops and the way back

    private void showFailed(String why, boolean wrote) {
        final PreferenceScreen s = screen(R.string.inst_failed_title);
        steps(s);
        final PreferenceCategory c = category(s, getString(R.string.inst_failed_title));
        text(c, null, why);
        final JSONObject b = Installer.state().optJSONObject("backup");
        if (wrote && b != null) {
            text(c, null, getString(R.string.inst_failed_choice, b.optString("dir")));
            restoreRow(c);
            row(c, getString(R.string.inst_leave), null, () -> run("leave", true, this::route));
        } else {
            text(c, null, getString(R.string.inst_failed_nothing));
        }
        if (!mLogLines.isEmpty()) row(c, getString(R.string.inst_show_log), null, this::showLog);
    }

    private void restoreRow(PreferenceGroup g) {
        final JSONObject b = Installer.state().optJSONObject("backup");
        if (b == null) return;
        final String dir = b.optString("dir");
        row(g, getString(R.string.inst_restore), dir, () -> new AlertDialog.Builder(getActivity())
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
            text(screen(R.string.inst_title), null, getString(R.string.inst_checking));
        } else {
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showProgress();
        }
        poll();
    }

    private void showProgress() {
        final PreferenceScreen s = screen(R.string.inst_working);
        steps(s);
        final PreferenceCategory now = category(s, getString(R.string.inst_now));
        mOverall = new ProgressPreference(getActivity());
        now.addPreference(mOverall);
        mCurrent = new ProgressPreference(getActivity());
        now.addPreference(mCurrent);
        mFinished = category(s, getString(R.string.inst_finished));
        mFinishedRows.clear();
        final PreferenceCategory more = category(s, getString(R.string.inst_details));
        mLogRow = row(more, getString(R.string.inst_log), null, this::showLog);
        row(more, getString(R.string.inst_details), null,
                () -> dialog(getString(R.string.inst_details), details(), true));
        updateProgress();
    }

    private void poll() {
        for (JSONObject e : mReader.poll()) apply(e);
        if (mOverall != null) updateProgress();
        final JSONObject r = Installer.result();
        final boolean running = Installer.running();
        final boolean ours = r != null && (mCmd.isEmpty() || mCmd.equals(r.optString("cmd")));
        if (!running && ours) {
            done(r);
            return;
        }
        if (!running && SystemClock.elapsedRealtime() - mStartedAt > START_GRACE_MS) {
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mCmd = null;
            if (mAfter == null) route();
            else showFailed(getString(R.string.inst_no_result), true);
            return;
        }
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    private void done(JSONObject r) {
        getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        Op current = null;
        for (Op op : mOps.values()) {
            if ("ok".equals(op.state) || "fail".equals(op.state)) {
                if (!mFinishedRows.containsKey(op.name)) {
                    final Preference p = row(mFinished, op.name, finishedLine(op), null);
                    p.setIcon("ok".equals(op.state) ? R.drawable.ic_inst_ok : R.drawable.ic_inst_bad);
                    mFinishedRows.put(op.name, p);
                }
            } else {
                current = op;
            }
        }
        final int step = stepOf(mCmd);
        final double frac = current == null || current.total <= 0 ? 0 : (double) current.done / current.total;
        mOverall.setTitle(step < 0 ? getString(R.string.inst_working) : getString(stepName(step)));
        mOverall.setSummary(getString(R.string.inst_elapsed,
                DateUtils.formatElapsedTime((System.currentTimeMillis() - mCmdStart) / 1000)));
        mOverall.setProgress(step < 0 ? -1 : (int) (1000 * (step + frac) / 4));
        if (current == null) {
            mCurrent.setTitle(R.string.inst_starting);
            mCurrent.setSummary(null);
            mCurrent.setProgress(-1);
        } else {
            mCurrent.setTitle(current.name);
            mCurrent.setSummary(runningLine(current));
            mCurrent.setProgress(current.total > 0 ? (int) (1000 * frac) : -1);
        }
        if (!mLogLines.isEmpty()) mLogRow.setSummary(mLogLines.get(mLogLines.size() - 1));
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

    /** The four steps and how far each is (g null: update the rows already there). */
    private void steps(PreferenceGroup g) {
        if (g != null) {
            final PreferenceCategory c = category(g, getString(R.string.inst_steps));
            mStepRows = new Preference[4];
            for (int i = 0; i < 4; i++) mStepRows[i] = row(c, getString(stepName(i)), null, null);
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
            final int what;
            if (i == now && !"done".equals(s[i])) what = R.string.inst_st_running;
            else if ("done".equals(s[i])) what = R.string.inst_st_done;
            else if ("failed".equals(s[i])) what = R.string.inst_st_failed;
            else if ("restart".equals(s[i])) what = R.string.inst_st_restart;
            else what = R.string.inst_st_todo;
            mStepRows[i].setSummary(what);
        }
    }
}
