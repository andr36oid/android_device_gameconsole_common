package org.andr36oid.emmc;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.andr36oid.hints.HintBar;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    /** Progress events from before this run are the last command's, see run(). */
    private long mSkipBefore;

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
    /** The tick box under the text (last pages): up and down scroll the text past it. */
    private LinearLayout mFixedExtra;
    private View mFixedBox;
    /** A button at the end of the list on the right that up from the buttons reaches. */
    private View mSideEntry;
    /** The advanced mode: the images found, where it goes back to. */
    private List<JSONObject> mImages = new ArrayList<>();
    private Runnable mAdvBack;
    /** BootReceiver found a data card the flasher made start again: ask about the eMMC. */
    static final String EXTRA_DATACARD_RETURN = "datacard_return";
    private boolean mAskReturn;
    /** Started from the eMMC's data card (made to start by itself): a restore ends differently. */
    private boolean mOnDatacard;
    /** datacard-options: this SD card, the card in slot 2, what each choice needs. */
    private JSONObject mDcOptions;
    private int mTitleColor;
    /** The user left on purpose (Home): don't come back by ourselves. */
    private boolean mUserLeft;
    /** How often we came back after something else took the screen, see onStop. */
    private int mRefronts;
    private static final int MAX_REFRONTS = 8;
    /**
     * After a restart, the home app (Daijishou) starts its main screen over ours once it has
     * loaded, and Android reports any app starting over us as the user leaving
     * (onUserLeaveHint). Until this long after boot, that doesn't count as leaving.
     */
    private static final long BOOT_SETTLE_MS = 3 * 60 * 1000;

    /** On the checks page: redraws it when a charger comes or goes, or the level changes. */
    private BroadcastReceiver mPowerWatch;
    /** The page asking for the charger: Try again shows once it is in. */
    private BroadcastReceiver mChargeWatch;
    /** The last command run, to start it again (after the charger went in). */
    private Runnable mRetry;

    // The progress page's views, null on other pages
    private TextView mCurName, mCurLine;
    private ProgressBar mCurBar;
    private LinearLayout mFinished;
    private final Set<String> mFinishedRows = new HashSet<>();
    /** finished parts in the order they ended, the last few are on the progress page */
    private final List<Op> mFinishedOps = new ArrayList<>();
    private static final int FINISHED_SHOWN = 4;

    private static final class Op {
        String name, dev, sha, log, state = "start";
        long from, to, done, total, speed, began, ended;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.install_wizard);
        HintBar.attachDefault(this);
        mStepLabel = findViewById(R.id.step_label);
        mProgress = findViewById(R.id.progress);
        mTitle = findViewById(R.id.title);
        mBody = findViewById(R.id.body);
        mTextScroll = findViewById(R.id.text_scroll);
        mSideScroll = findViewById(R.id.side_scroll);
        // Only status: the D-pad goes to the buttons. ScrollView makes itself focusable in
        // code, over the layout's focusable="false".
        mTextScroll.setFocusable(false);
        mSideScroll.setFocusable(false);
        mSide = findViewById(R.id.side);
        // the right pane only while something is in it: a page of text alone gets the
        // whole width, and the D-pad has no empty half to cross
        mSide.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                mSideScroll.setVisibility(View.VISIBLE);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
                if (mSide.getChildCount() == 0) mSideScroll.setVisibility(View.GONE);
            }
        });
        mBodyExtra = findViewById(R.id.body_extra);
        mFixedExtra = findViewById(R.id.fixed_extra);
        mBack = findViewById(R.id.back);
        mMore = findViewById(R.id.more);
        mSecondary = findViewById(R.id.secondary);
        mNext = findViewById(R.id.next);
        mTitleColor = mTitle.getCurrentTextColor();
        mAskReturn = getIntent().getBooleanExtra(EXTRA_DATACARD_RETURN, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mCmd == null) route();
        else poll();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (SystemClock.elapsedRealtime() < BOOT_SETTLE_MS) return;
        mUserLeft = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        mUserLeft = false;
    }

    /**
     * Something else took the screen without the user leaving: after the restarts the home
     * app (Daijishou) finishes starting late and comes up over the installer, in the middle
     * of a step. Come back on top, a few times at most so two apps can't fight over it.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (mUserLeft || isFinishing() || isChangingConfigurations() || mRefronts >= MAX_REFRONTS) {
            return;
        }
        mRefronts++;
        final int task = getTaskId();
        mHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && !mUserLeft) {
                getSystemService(ActivityManager.class).moveTaskToFront(task, 0);
            }
        }, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mPoll);
        stopPowerWatch();
    }

    private void stopPowerWatch() {
        if (mChargeWatch != null) {
            unregisterReceiver(mChargeWatch);
            mChargeWatch = null;
        }
        if (mPowerWatch == null) return;
        unregisterReceiver(mPowerWatch);
        mPowerWatch = null;
    }

    /** Keeps the checks' power line live: the result of inspect, updated from the battery. */
    private void watchPower() {
        if (mPowerWatch != null) return;
        mPowerWatch = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                final JSONObject power = mInspect == null ? null : mInspect.optJSONObject("power");
                if (power == null) return;
                final int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                final int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                final int cap = level < 0 || scale <= 0 ? -1 : level * 100 / scale;
                final boolean charger = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                final int oldCap = power.optInt("capacity", -1);
                final boolean oldCharger = power.optBoolean("charger");
                if (cap == oldCap && charger == oldCharger) return;
                if ((charger || cap >= 60) != (oldCharger || oldCap >= 60)) {
                    // passes or fails now: the installer checks again, its problems too
                    showChecks();
                    return;
                }
                try {
                    power.put("capacity", cap);
                    power.put("charger", charger);
                } catch (org.json.JSONException e) {
                    return;
                }
                showCheckResults();
            }
        };
        registerReceiver(mPowerWatch, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
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
        final boolean dpad = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        final View focus = getCurrentFocus();
        if (dpad && first && (focus == null || !focus.isShown())) {
            // Nothing selected (after a touch, or a page change): the first press selects
            // Next, or the first button there is, instead of getting lost
            ensureFocus();
            return true;
        }
        if (mSideEntry != null && mSideEntry.isShown() && mSideEntry.isEnabled()
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            // up from the buttons reaches the entry once the text is at its top, down
            // goes back to the buttons
            if (focus == mSideEntry && key == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusButtons();
                return true;
            }
            if (focus != mSideEntry && key == KeyEvent.KEYCODE_DPAD_UP
                    && !mTextScroll.canScrollVertically(-1)) {
                mSideEntry.requestFocus();
                return true;
            }
        }
        if (mFixedBox != null && mFixedBox.isShown()
                && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
            // On the box, up and down scroll the text; down at its end goes to the buttons.
            // On the buttons, up goes to the box and down scrolls.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                final boolean up = key == KeyEvent.KEYCODE_DPAD_UP;
                if (focus == mFixedBox) {
                    if (!up && !mTextScroll.canScrollVertically(1)) focusButtons();
                    else mTextScroll.smoothScrollBy(0, (up ? -1 : 1) * mTextScroll.getHeight() / 3);
                } else if (up) {
                    mFixedBox.requestFocus();
                } else {
                    mTextScroll.smoothScrollBy(0, mTextScroll.getHeight() / 3);
                }
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
        if (mAskReturn) {
            showDatacardReturn();
            return;
        }
        final JSONObject st = Installer.state();
        if (st.optBoolean("back")) {
            // the eMMC system made its data card start to put the stock eMMC back
            showDatacardReturn();
            return;
        }
        final String running = st.optString("running"), failed = st.optString("failed");
        final String step1 = st.optString("step1"), step2 = st.optString("step2");
        if (!failed.isEmpty() || !running.isEmpty()) {
            showFailed(failed.isEmpty() ? getString(R.string.inst_cut_off, running) : failed, true);
        } else if (!st.optString("incomplete").isEmpty()) {
            showIncomplete(st.optString("incomplete"));
        } else if (st.optJSONObject("custom") != null) {
            showCustom(st.optJSONObject("custom"));
        } else if (st.optJSONObject("revive") != null) {
            showRevive();
        } else if ("done".equals(st.optString("step3"))) {
            if (datacardDue(st)) showDatacard(st.optJSONObject("datacard"));
            else showDone();
        } else if ("passed".equals(step2)) {
            showNext(3, getString(R.string.inst_step2_passed), this::showFinal);
        } else if ("deferred".equals(step2)) {
            showDeferred();
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
        } else if (st.optJSONObject("backup") != null && st.optJSONObject("datacard") == null) {
            findDataChoices();
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
        stopPowerWatch();
        mCurName = mCurLine = null;
        mCurBar = null;
        mFinished = null;
        mSideFocus = false;
        mSideEntry = null;
        mFixedBox = null;
        mFixedExtra.removeAllViews();
        mFixedExtra.setVisibility(View.GONE);
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
        mSideScroll.setVisibility(View.GONE);
        mBodyExtra.removeAllViews();
        for (Button b : new Button[] {mBack, mMore, mSecondary, mNext}) {
            b.setVisibility(View.GONE);
            b.setEnabled(true);
            b.setOnClickListener(null);
            b.setNextFocusUpId(View.NO_ID);
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
        focusButtons();
    }

    private void focusButtons() {
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
        mHandler.post(this::fitButtons);
    }

    /**
     * The row's buttons share its width: each gets an equal part at most, a longer label
     * goes onto two lines instead of pushing the last button off the screen.
     */
    private void fitButtons() {
        final ViewGroup row = (ViewGroup) mNext.getParent();
        final int width = row.getWidth() - row.getPaddingLeft() - row.getPaddingRight();
        if (width <= 0) {
            row.post(this::fitButtons);
            return;
        }
        int shown = 0, margins = 0;
        final Button[] all = {mBack, mMore, mSecondary, mNext};
        for (Button b : all) {
            if (b.getVisibility() != View.VISIBLE) continue;
            shown++;
            final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) b.getLayoutParams();
            margins += lp.getMarginStart() + lp.getMarginEnd();
        }
        if (shown == 0) return;
        final int each = (width - margins) / shown;
        for (Button b : all) {
            b.setSingleLine(false);
            b.setMaxLines(2);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMaxWidth(each);
        }
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
        return getString(R.string.inst_lbl_step);
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
            page(label, AT_CHECKS, getString(R.string.inst_checks), getString(R.string.inst_intro)
                    + "\n\n" + getString(R.string.inst_inspect_failed));
            button(mBack, getString(R.string.inst_close), this::finish);
            button(mNext, getString(R.string.inst_check_again), this::showChecks);
            return;
        }
        final List<String> problems = new ArrayList<>();
        final JSONArray pa = mInspect.optJSONArray("problems");
        for (int i = 0; pa != null && i < pa.length(); i++) problems.add(pa.optString(i));
        final StringBuilder body = new StringBuilder(getString(R.string.inst_intro));
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
        watchPower();

        button(mBack, getString(R.string.inst_close), this::finish);
        button(mMore, getString(R.string.inst_check_again), this::showChecks);
        if (mTargets.size() > 1) button(mSecondary, getString(R.string.inst_change), this::pickTarget);
        button(mNext, getString(R.string.inst_continue), () -> showWarning(0));
        mNext.setEnabled(ok);
        sideEntry(getString(R.string.inst_adv_entry), this::showCheckResults).setEnabled(ok);
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
        button(mBack, getString(R.string.inst_back),
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
                + getString(R.string.inst_sum_risk) + "\n\n"
                + getString(R.string.inst_start_summary);
        page(getString(R.string.inst_lbl_plan), AT_PLAN, getString(R.string.inst_summary_title), body);
        sideHeader(getString(R.string.inst_layout_title));
        sideRow(0, getString(R.string.inst_layout_text), null);
        for (JSONObject p : plan) {
            sideRow(0, p.optString("Name"), getString(R.string.inst_sectors, size(sectors(p) * 512),
                    p.optLong("First"), p.optLong("Last")));
        }
        button(mBack, getString(R.string.inst_back), () -> showWarning(2));
        // the backup only: the next page says what step 1 writes before it does
        button(mNext, getString(R.string.inst_start), () -> run("backup", false, this::route,
                t.path));
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
        final CheckBox fault = faultBox();
        button(mBack, getString(R.string.inst_close), this::finish);
        restoreButton(mSecondary);
        button(mNext, getString(R.string.inst_write_loader), this::showHold);
        mNext.setEnabled(false);
        fault.setOnCheckedChangeListener((v, on) -> mNext.setEnabled(on));
        fault.requestFocus();
    }

    /**
     * "My fault if it breaks", under the warning in one pane: the text scrolls with up and
     * down, the box stays in view. Right goes to Next (enabled once it is ticked).
     */
    private CheckBox faultBox() {
        mSideScroll.setVisibility(View.GONE);
        final CheckBox fault = new CheckBox(this);
        fault.setText(R.string.inst_my_fault);
        fault.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        fault.setId(View.generateViewId());
        fault.setNextFocusRightId(R.id.next);
        mFixedExtra.addView(fault);
        mFixedExtra.setVisibility(View.VISIBLE);
        mFixedBox = fault;
        return fault;
    }

    private void showHold() {
        page(stepLabel(3), stepAt(3) + 50, getString(R.string.inst_write_loader),
                getString(R.string.inst_hold_hint));
        warn();
        final HoldButton hold = new HoldButton(this, getString(R.string.inst_hold));
        mSide.addView(hold, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));
        hold.setId(View.generateViewId());
        hold.setNextFocusDownId(R.id.back);
        mBack.setNextFocusUpId(hold.getId());
        mSideFocus = true;
        hold.setListener(() -> {
            final String code = Installer.state().optString("loader_sha256");
            if (code.length() < 12) {
                showFailed(getString(R.string.inst_no_loader_code), false);
                return;
            }
            run("write-loader", false, () -> {
                if (datacardDue(Installer.state())) route();
                else showRestart(Restart.DONE);
            }, code.substring(0, 12));
        });
        button(mBack, getString(R.string.inst_cancel), this::showFinal);
        hold.requestFocus();
    }

    // Restarts

    private enum Restart { STEP1, TEST, DONE, STOCK, STOCK_CARD, COPY, RETURNED }

    private void showRestart(Restart kind) {
        final JSONObject b = Installer.state().optJSONObject("backup");
        final String dir = b == null ? "" : b.optString("dir");
        final String text;
        final int step;
        int title = R.string.inst_restart_title;
        switch (kind) {
            case STEP1:
                text = getString(R.string.inst_restart_step1, dir);
                step = 1;
                break;
            case TEST:
                text = getString(R.string.inst_restart_test) + "\n\n" + getString(R.string.inst_test_hands_off);
                step = 2;
                break;
            case DONE:
                text = getString(R.string.inst_restart_done);
                step = 3;
                title = R.string.inst_done_title;
                break;
            case COPY:
                text = getString(R.string.inst_restart_copy);
                step = 3;
                break;
            case STOCK_CARD:
                text = getString(R.string.inst_goback_stock);
                step = -1;
                title = R.string.inst_stock_back_title;
                break;
            case RETURNED:
                text = getString(R.string.inst_dc_returned);
                step = -1;
                title = R.string.inst_dc_ready_title;
                break;
            default:
                text = getString(R.string.inst_restart_stock);
                step = -1;
                title = R.string.inst_stock_back_title;
                break;
        }
        page(step < 0 ? null : stepLabel(step), step < 0 ? -1 : step == 3 ? AT_DONE : stepAt(step) + 100,
                getString(title), text);
        if (step >= 0) steps();
        if (kind == Restart.TEST) {
            button(mSecondary, getString(R.string.inst_test_later),
                    () -> run("cancel-test", true, this::finish));
        }
        if (kind == Restart.DONE || kind == Restart.STOCK || kind == Restart.STOCK_CARD) {
            // Android runs from the SD card: it only comes out once the console is off
            button(mNext, getString(R.string.inst_shut_down), this::shutDown);
        } else {
            button(mNext, getString(R.string.inst_restart_now), this::reboot);
        }
    }

    private void reboot() {
        getSystemService(PowerManager.class).reboot(null);
    }

    private void shutDown() {
        getSystemService(PowerManager.class).shutdown(false, null, false);
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
        sideEntry(getString(R.string.inst_adv_entry), this::showDone);
    }

    /** Step 2 is written, but its test start was put off. */
    private void showDeferred() {
        page(stepLabel(2), stepAt(2) + 100, getString(stepName(2)),
                getString(R.string.inst_test_deferred) + "\n\n" + getString(R.string.inst_test_hands_off));
        steps();
        button(mBack, getString(R.string.inst_close), this::finish);
        restoreButton(mSecondary);
        button(mNext, getString(R.string.inst_test_now),
                () -> run("start-test", true, () -> showRestart(Restart.TEST)));
    }

    /** An update or restore stopped and was left as it is: neither installed nor stock. */
    private void showIncomplete(String cmd) {
        final boolean restore = "restore".equals(cmd);
        final boolean image = "write-image".equals(cmd);
        page(null, -1, getString(R.string.inst_incomplete_title), getString(restore
                ? R.string.inst_incomplete_restore : image ? R.string.inst_incomplete_image
                : R.string.inst_incomplete_update));
        warn();
        if (!image) steps();
        button(mBack, getString(R.string.inst_close), this::finish);
        final JSONObject b = Installer.state().optJSONObject("backup");
        if (image) {
            restoreButton(mSecondary);
            button(mNext, getString(R.string.inst_adv_write_again), () -> showAdvWarning(this::route));
        } else if (restore) {
            if (b != null) {
                final String dir = b.optString("dir");
                button(mNext, getString(R.string.inst_retry),
                        () -> run("restore", false, this::afterRestore, dir));
            }
        } else {
            restoreButton(mSecondary);
            button(mNext, getString(R.string.inst_retry), () -> run(cmd, false, this::showDone));
        }
    }

    /** A restore that wrote everything back, but the boot areas differ from the backup. */
    private void showNotExact(String why) {
        page(null, -1, getString(R.string.inst_not_exact_title),
                why + "\n\n" + getString(R.string.inst_not_exact));
        warn();
        if (!mLogLines.isEmpty()) button(mMore, getString(R.string.inst_show_log), this::showLog);
        button(mNext, getString(R.string.inst_shut_down), this::shutDown);
    }

    // Advanced: any SD card image on the eMMC, at the user's own risk

    /** A button at the end of the list on the right; up from the buttons reaches it. */
    private Button sideEntry(CharSequence text, Runnable back) {
        final Button b = new Button(this, null, 0, R.style.Andr36oidSetupButton_Secondary);
        b.setText(text);
        b.setOnClickListener(v -> showAdvWarning(back));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        mSide.addView(b, lp);
        mSideEntry = b;
        return b;
    }

    /** The eMMC's size, from the checks or the records. */
    private long advEmmcBytes() {
        final long b = emmcBytes();
        return b > 0 ? b : Installer.state().optLong("sectors") * 512;
    }

    private void showAdvWarning(Runnable back) {
        mAdvBack = back;
        page(getString(R.string.inst_lbl_advanced), -1, getString(R.string.inst_adv_warn_title),
                getString(R.string.inst_adv_warn));
        warn();
        button(mBack, getString(R.string.inst_back), back);
        button(mNext, getString(R.string.inst_understand), this::findImages);
    }

    private void findImages() {
        final String media = "/data/media/" + UserHandle.myUserId();
        run("list-images", true, () -> {
            final JSONObject r = Installer.result();
            mImages = r == null ? new ArrayList<>() : Installer.list(r, "result");
            showImages();
        }, media, media + "/Download");
    }

    private void showImages() {
        page(getString(R.string.inst_lbl_advanced), -1, getString(R.string.inst_adv_pick_title),
                getString(mImages.isEmpty() ? R.string.inst_adv_none : R.string.inst_adv_pick));
        warn();
        button(mBack, getString(R.string.inst_back), () -> showAdvWarning(mAdvBack));
        button(mMore, getString(R.string.inst_adv_look_again), this::findImages);
        if (mImages.isEmpty()) {
            mMore.requestFocus();
            return;
        }
        final RadioGroup group = new RadioGroup(this);
        RadioButton last = null;
        for (int i = 0; i < mImages.size(); i++) {
            final JSONObject img = mImages.get(i);
            final RadioButton rb = new RadioButton(this);
            final boolean boot = img.optString("path").startsWith("sdboot:");
            rb.setText(img.optString("name") + "\n" + size(img.optLong("size"))
                    + (boot ? " · " + getString(R.string.inst_adv_on_boot) : ""));
            rb.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
            rb.setId(View.generateViewId());
            rb.setTag(i);
            group.addView(rb);
            last = rb;
        }
        mSide.addView(group);
        mSideFocus = true;
        // down from the last image goes to Next, up from Next comes back
        last.setNextFocusDownId(R.id.next);
        mNext.setNextFocusUpId(last.getId());
        button(mNext, getString(R.string.inst_continue), () -> {
            final View c = group.findViewById(group.getCheckedRadioButtonId());
            if (c != null) showAdvFinal(mImages.get((Integer) c.getTag()));
        });
        mNext.setEnabled(false);
        group.setOnCheckedChangeListener((g, id) -> mNext.setEnabled(id != View.NO_ID));
        group.getChildAt(0).requestFocus();
    }

    private void showAdvFinal(JSONObject img) {
        final String name = img.optString("name");
        final long bytes = img.optLong("size"), emmc = advEmmcBytes();
        // a plain .img is as big as its file; packed ones are checked once unpacked
        final boolean tooBig = name.toLowerCase(Locale.ROOT).endsWith(".img") && emmc > 0 && bytes > emmc;
        page(getString(R.string.inst_lbl_advanced), -1, getString(R.string.inst_adv_final_title, name),
                getString(R.string.inst_adv_final, name, size(bytes), size(emmc))
                        + (tooBig ? "\n\n" + getString(R.string.inst_adv_too_big) : ""));
        warn();
        button(mBack, getString(R.string.inst_back), this::showImages);
        button(mNext, getString(R.string.inst_adv_write), () -> showAdvHold(img));
        mNext.setEnabled(false);
        if (tooBig) return;
        final CheckBox fault = faultBox();
        fault.setOnCheckedChangeListener((v, on) -> mNext.setEnabled(on));
        fault.requestFocus();
    }

    private void showAdvHold(JSONObject img) {
        page(getString(R.string.inst_lbl_advanced), -1, getString(R.string.inst_adv_write),
                getString(R.string.inst_hold_hint));
        warn();
        final HoldButton hold = new HoldButton(this, getString(R.string.inst_hold));
        mSide.addView(hold, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));
        hold.setId(View.generateViewId());
        hold.setNextFocusDownId(R.id.back);
        mBack.setNextFocusUpId(hold.getId());
        mSideFocus = true;
        final String path = img.optString("path"), name = img.optString("name");
        hold.setListener(() -> {
            final JSONObject b = Installer.state().optJSONObject("backup");
            if (b != null && b.optBoolean("verified")) {
                run("write-image", false, () -> showAdvDone(name), path);
            } else if (!mTargets.isEmpty()) {
                // from the start: the stock eMMC's backup first, so restore stays possible
                run("backup", false, () -> run("write-image", false, () -> showAdvDone(name), path),
                        mTargets.get(mTarget).path);
            } else {
                showFailed(getString(R.string.inst_chk_no_target), false);
            }
        });
        button(mBack, getString(R.string.inst_cancel), () -> showAdvFinal(img));
        hold.requestFocus();
    }

    private void showAdvDone(String name) {
        page(null, -1, getString(R.string.inst_adv_done_title), getString(R.string.inst_adv_done, name));
        // Android runs from the SD card: it only comes out once the console is off
        button(mBack, getString(R.string.inst_close), this::finish);
        button(mNext, getString(R.string.inst_shut_down), this::shutDown);
    }

    /** The eMMC holds an image written in the advanced mode. */
    private void showCustom(JSONObject img) {
        page(null, -1, getString(R.string.inst_custom_title),
                getString(R.string.inst_custom, img.optString("name"), size(img.optLong("size"))));
        restoreButton(mSecondary);
        button(mMore, getString(R.string.inst_custom_install), () -> showNext(1,
                getString(R.string.inst_backup_kept),
                () -> run("blank-loader", false, () -> showRestart(Restart.STEP1))));
        button(mNext, getString(R.string.inst_close), this::finish);
        sideEntry(getString(R.string.inst_adv_another), this::route);
    }

    private void showEmmcSystem() {
        final JSONObject c = Installer.confirmResult();
        if (c != null && "confirmed".equals(c.optString("result")) && c.optBoolean("datacard_copy")) {
            showDatacardCopy();
        } else if (c != null && "confirmed".equals(c.optString("result"))) {
            page(stepLabel(2), stepAt(2) + 150, getString(stepName(2)),
                    getString(R.string.inst_test_worked));
            button(mNext, getString(R.string.inst_restart_now), this::reboot);
        } else if (!Installer.datacard().isEmpty()) {
            showGoBack();
        } else {
            page(null, -1, getString(R.string.inst_title), getString(R.string.inst_on_emmc));
            button(mNext, getString(R.string.inst_close), this::finish);
        }
    }

    /**
     * On the eMMC system with a data card: the stock eMMC can only be put back from an SD
     * card start, and the data card can't start by itself. So it is made to start first.
     */
    private void showGoBack() {
        page(null, -1, getString(R.string.inst_goback_title), getString(R.string.inst_goback));
        button(mBack, getString(R.string.inst_close), this::finish);
        button(mNext, getString(R.string.inst_goback_go), () -> run("datacard-back", false, () -> {
            final JSONObject r = Installer.result();
            final boolean slot2 = r != null && "slot 2".equals(r.optString("result"));
            page(null, -1, getString(R.string.inst_goback_done_title),
                    getString(slot2 ? R.string.inst_goback_done_slot2 : R.string.inst_goback_done_slot1));
            button(mNext, getString(R.string.inst_shut_down), this::shutDown);
        }));
    }

    // The data card: where Android keeps its data (datacard.go in emmcinstall)

    /** A data card was chosen and isn't made yet. */
    private static boolean datacardDue(JSONObject st) {
        final JSONObject d = st.optJSONObject("datacard");
        if (d == null) return false;
        final String kind = d.optString("kind"), stage = d.optString("stage");
        return ("original".equals(kind) || "new".equals(kind))
                && !"done".equals(stage) && !"blank".equals(stage);
    }

    private void findDataChoices() {
        run("datacard-options", true, () -> {
            final JSONObject r = Installer.result();
            mDcOptions = r == null ? null : r.optJSONObject("result");
            if (mDcOptions == null) mDcOptions = new JSONObject();
            showDataWhere();
        });
    }

    private RadioButton choice(RadioGroup group, CharSequence title, CharSequence sub, Object tag) {
        final RadioButton rb = new RadioButton(this);
        rb.setText(sub == null ? title : title + "\n" + sub);
        rb.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        rb.setId(View.generateViewId());
        rb.setTag(tag);
        group.addView(rb);
        return rb;
    }

    /** After the backup: on the eMMC, on this card, or on a new card in slot 2? */
    private void showDataWhere() {
        final JSONObject o = mDcOptions;
        final JSONObject slot2 = o.optJSONObject("slot2");
        final boolean card2 = slot2 != null && slot2.optBoolean("present");
        page(getString(R.string.inst_lbl_data), stepAt(1) - 50, getString(R.string.inst_dc_where_title),
                getString(R.string.inst_dc_where));
        final RadioGroup group = new RadioGroup(this);
        final RadioButton first = choice(group, getString(R.string.inst_dc_emmc),
                getString(R.string.inst_dc_emmc_sub), "emmc");
        final RadioButton original = choice(group, getString(R.string.inst_dc_original),
                getString(R.string.inst_dc_original_sub, size(o.optLong("userdata_used"))), "original");
        original.setEnabled(!o.optString("uuid").isEmpty());
        final RadioButton fresh = choice(group, getString(R.string.inst_dc_new), card2
                ? getString(R.string.inst_dc_new_sub, size(slot2.optLong("sectors") * 512))
                : getString(R.string.inst_dc_new_none), "new");
        fresh.setEnabled(card2);
        mSide.addView(group);
        mSideFocus = true;
        fresh.setNextFocusDownId(R.id.next);
        mNext.setNextFocusUpId(fresh.getId());
        button(mBack, getString(R.string.inst_close), this::finish);
        restoreButton(mSecondary);
        button(mNext, getString(R.string.inst_continue), () -> {
            final View c = group.findViewById(group.getCheckedRadioButtonId());
            if (c == null) return;
            final String kind = (String) c.getTag();
            if ("new".equals(kind)) showNewCard();
            else run("datacard-choose", true, this::route, kind);
        });
        mNext.setEnabled(false);
        group.setOnCheckedChangeListener((g, id) -> mNext.setEnabled(id != View.NO_ID));
        first.requestFocus();
    }

    /** A new card: copy everything (A), Android's data (B) or nothing (C), and AUTOAPK. */
    private void showNewCard() {
        final JSONObject o = mDcOptions;
        final JSONObject slot2 = o.optJSONObject("slot2");
        final JSONObject need = o.optJSONObject("need");
        final long have = slot2 == null ? 0 : slot2.optLong("sectors") * 512;
        final long apk = o.optLong("autoapk_bytes");
        final String name = slot2 == null ? "" : slot2.optString("name");
        page(getString(R.string.inst_lbl_data), stepAt(1) - 25, getString(R.string.inst_dc_what_title),
                getString(R.string.inst_dc_what, name, size(have)) + "\n\n" + getString(apk > 0
                        ? R.string.inst_dc_autoapk_off : R.string.inst_dc_autoapk_gone));
        warn();
        final RadioGroup group = new RadioGroup(this);
        final String[] opts = {"A", "B", "C"};
        final int[] titles = {R.string.inst_dc_a, R.string.inst_dc_b, R.string.inst_dc_c};
        final RadioButton[] rbs = new RadioButton[3];
        final CheckBox autoapk = new CheckBox(this);
        final Runnable sizes = () -> {
            for (int i = 0; i < 3; i++) {
                long n = need == null ? 0 : need.optLong(opts[i]);
                if (i > 0 && autoapk.isChecked()) n += apk;
                final String sub = i == 0
                        ? getString(R.string.inst_dc_a_sub, size(o.optLong("easyroms_used")), size(n))
                        : getString(i == 1 ? R.string.inst_dc_b_sub : R.string.inst_dc_c_sub, size(n));
                final boolean fits = n > 0 && n <= have;
                rbs[i].setText(getString(titles[i]) + "\n" + sub
                        + (fits ? "" : "\n" + getString(R.string.inst_dc_too_small, size(n), size(have))));
                rbs[i].setEnabled(fits);
                if (!fits && rbs[i].isChecked()) group.clearCheck();
            }
        };
        for (int i = 0; i < 3; i++) rbs[i] = choice(group, getString(titles[i]), null, opts[i]);
        mSide.addView(group);
        View last = rbs[2];
        if (apk > 0) {
            // A copies it anyway; B and C only when ticked
            autoapk.setText(getString(R.string.inst_dc_autoapk, size(apk)));
            autoapk.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
            autoapk.setId(View.generateViewId());
            autoapk.setChecked(true);
            autoapk.setOnCheckedChangeListener((v, on) -> sizes.run());
            mSide.addView(autoapk);
            last = autoapk;
        }
        sizes.run();
        mSideFocus = true;
        last.setNextFocusDownId(R.id.next);
        mNext.setNextFocusUpId(last.getId());
        button(mBack, getString(R.string.inst_back), this::showDataWhere);
        button(mNext, getString(R.string.inst_continue), () -> {
            final View c = group.findViewById(group.getCheckedRadioButtonId());
            if (c == null) return;
            final String opt = (String) c.getTag();
            run("datacard-choose", true, this::route, "new", opt,
                    apk > 0 && ("A".equals(opt) || autoapk.isChecked()) ? "autoapk" : "-");
        });
        mNext.setEnabled(false);
        group.setOnCheckedChangeListener((g, id) -> mNext.setEnabled(id != View.NO_ID));
        for (RadioButton rb : rbs) {
            if (rb.isEnabled()) {
                rb.requestFocus();
                break;
            }
        }
    }

    /** After step 3: the data card is made last. */
    private void showDatacard(JSONObject d) {
        final String label = getString(R.string.inst_dc_lbl_last);
        final boolean fresh = "C".equals(d.optString("option"));
        if ("original".equals(d.optString("kind"))) {
            page(label, AT_DONE - 50, getString(R.string.inst_dc_orig_title), getString(R.string.inst_dc_orig));
            warn();
            button(mNext, getString(R.string.inst_dc_orig_go),
                    () -> run("datacard-finish", false, () -> showDatacardDone(false)));
        } else if ("prepared".equals(d.optString("stage"))) {
            page(label, AT_DONE - 50, getString(R.string.inst_dc_finish_title), getString(fresh
                    ? R.string.inst_dc_finish_fresh : R.string.inst_dc_finish));
            if (!fresh) {
                button(mMore, getString(R.string.inst_dc_copy_again),
                        () -> run("datacard-prepare", false, () -> showRestart(Restart.COPY)));
            }
            button(mNext, getString(R.string.inst_dc_finish_go),
                    () -> run("datacard-finish", false, () -> showDatacardDone(true)));
        } else {
            page(label, AT_DONE - 100, getString(R.string.inst_dc_new_title),
                    getString(fresh ? R.string.inst_dc_new_fresh : R.string.inst_dc_new_copy));
            warn();
            button(mNext, getString(R.string.inst_dc_new_go), () -> run("datacard-prepare", false, () -> {
                if (fresh) run("datacard-finish", false, () -> showDatacardDone(true));
                else showRestart(Restart.COPY);
            }));
        }
        steps();
        button(mBack, getString(R.string.inst_close), this::finish);
    }

    private void showDatacardDone(boolean newCard) {
        page(getString(R.string.inst_lbl_done), AT_DONE, getString(R.string.inst_dc_ready_title),
                getString(newCard ? R.string.inst_dc_done_new : R.string.inst_dc_done_orig));
        steps();
        // Android runs from the SD card: it only comes out once the console is off
        button(mNext, getString(R.string.inst_shut_down), this::shutDown);
    }

    /** On the eMMC's start for the copy: userdata from the SD card to the new card. */
    private void showDatacardCopy() {
        page(getString(R.string.inst_dc_lbl_last), AT_DONE - 75, getString(R.string.inst_dc_copy_title),
                getString(R.string.inst_dc_copy));
        button(mNext, getString(R.string.inst_dc_copy_go), () -> run("datacard-copy", false, () -> {
            page(getString(R.string.inst_dc_lbl_last), AT_DONE - 50, getString(R.string.inst_dc_copy_title),
                    getString(R.string.inst_dc_copied));
            button(mNext, getString(R.string.inst_restart_now), this::reboot);
        }));
    }

    /**
     * The eMMC's data card started the console (the flasher, or the eMMC system for going
     * back, made it start): bring the eMMC up to it, or put the stock eMMC back?
     */
    private void showDatacardReturn() {
        run("datacard-check", true, () -> {
            final JSONObject r = Installer.result();
            final JSONObject c = r == null ? null : r.optJSONObject("result");
            mAskReturn = false;
            if (c == null || !c.optBoolean("offer")) {
                // not (or no longer) the eMMC's data card; route() would ask again
                if (Installer.state().optBoolean("back")) showDone();
                else route();
                return;
            }
            mOnDatacard = true;
            final String dir = c.optString("backup");
            if (c.optBoolean("back")) showBackRestore(dir);
            else showReturnOrStock(dir);
        });
    }

    private void showReturnOrStock(String dir) {
        page(null, -1, getString(R.string.inst_dc_return_title), getString(R.string.inst_dc_return));
        button(mBack, getString(R.string.inst_dc_keep_sd), this::confirmKeep);
        if (!dir.isEmpty()) restoreButton(mSecondary, dir);
        button(mNext, getString(R.string.inst_dc_return_go),
                () -> run("datacard-return", false, () -> showRestart(Restart.RETURNED)));
    }

    /** Going back to the stock system, its second half: the restore, from the data card. */
    private void showBackRestore(String dir) {
        page(null, -1, getString(R.string.inst_goback_restore_title), dir.isEmpty()
                ? getString(R.string.inst_goback_no_backup)
                : getString(R.string.inst_goback_restore, dir));
        button(mBack, getString(R.string.inst_dc_keep_sd), this::confirmKeep);
        button(mSecondary, getString(R.string.inst_goback_keep),
                () -> run("datacard-return", false, () -> showRestart(Restart.RETURNED)));
        if (!dir.isEmpty()) restoreButton(mNext, dir);
    }

    /**
     * No to making the card a data card again: it stays a normal SD card, which leaves the
     * eMMC's andr36oid set up for this card's data. Asked once more, as that is a trap.
     */
    private void confirmKeep() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.inst_dc_keep_title)
                .setMessage(R.string.inst_dc_keep_warn)
                .setPositiveButton(R.string.inst_dc_keep_yes, (d, x) -> run("datacard-keep", true, () -> {
                    page(null, -1, getString(R.string.inst_dc_kept_title), getString(R.string.inst_dc_kept));
                    button(mNext, getString(R.string.inst_close), this::finish);
                }))
                .setNegativeButton(R.string.inst_cancel, null)
                .show();
    }

    /** After a restore: the data card can start the console again, if there was one. */
    private void afterRestore() {
        if (Installer.state().optJSONObject("revive") != null) showRevive();
        else showRestart(mOnDatacard ? Restart.STOCK_CARD : Restart.STOCK);
    }

    /** The stock eMMC is back, and andr36oid's data card can't start by itself. */
    private void showRevive() {
        page(null, -1, getString(R.string.inst_revive_title), getString(R.string.inst_revive));
        button(mSecondary, getString(R.string.inst_revive_leave),
                () -> run("datacard-revive", true, () -> showRestart(Restart.STOCK), "forget"));
        button(mNext, getString(R.string.inst_revive_go), () -> run("datacard-revive", false, () -> {
            final JSONObject r = Installer.result();
            final boolean thisCard = r != null && "this card".equals(r.optString("result"));
            page(null, -1, getString(R.string.inst_revive_done_title),
                    getString(thisCard ? R.string.inst_revive_done_this : R.string.inst_revive_done_slot2));
            button(mNext, getString(R.string.inst_shut_down), this::shutDown);
        }));
    }

    // Stops and the way back

    /**
     * A run that writes didn't start for the battery (nothing was written). Try again
     * shows as soon as the charger is in, or the battery is at 60% again.
     */
    private void showCharger(String why, Runnable retry) {
        page(null, -1, getString(R.string.inst_failed_title),
                why + "\n\n" + getString(R.string.inst_failed_nothing));
        button(mBack, getString(R.string.inst_close), this::finish);
        button(mNext, getString(R.string.inst_retry), retry);
        mNext.setVisibility(View.GONE);
        mChargeWatch = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                final int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                final int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                final boolean charger = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                final boolean ok = charger || (scale > 0 && level * 100 / scale >= 60);
                if (ok == (mNext.getVisibility() == View.VISIBLE)) return;
                mNext.setVisibility(ok ? View.VISIBLE : View.GONE);
                if (ok) mNext.requestFocus();
                else focusButtons();
                fitButtons();
            }
        };
        registerReceiver(mChargeWatch, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

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
            // an update or restore left half done: the wizard closes, and its
            // next start offers to run it again
            final String running = Installer.state().optString("running");
            final boolean half = running.equals("update-emmc") || running.equals("update-uboot")
                    || running.equals("restore") || running.equals("write-image");
            button(mNext, getString(R.string.inst_leave),
                    () -> run("leave", true, half ? this::finish : this::route));
        } else {
            button(mNext, getString(R.string.inst_close), this::finish);
        }
    }

    /** The way back to the stock eMMC, on b, once there is a backup. */
    private void restoreButton(Button b) {
        final JSONObject backup = Installer.state().optJSONObject("backup");
        if (backup == null) return;
        restoreButton(b, backup.optString("dir"));
    }

    private void restoreButton(Button b, String dir) {
        button(b, getString(R.string.inst_restore), () -> new AlertDialog.Builder(this)
                .setTitle(R.string.inst_restore)
                .setMessage(getString(R.string.inst_restore_text, dir))
                .setPositiveButton(R.string.inst_restore_go,
                        (d, x) -> run("restore", false, this::afterRestore, dir))
                .setNegativeButton(R.string.inst_cancel, null)
                .show());
    }

    private void showLog() {
        dialog(getString(R.string.inst_log), String.join("\n", mLogLines), true);
    }

    // Running a command, with its progress

    /** Starts cmd; quick: a short check, shown as one line instead of the progress page. */
    private void run(String cmd, boolean quick, Runnable after, String... args) {
        mRetry = () -> run(cmd, quick, after, args);
        mOps.clear();
        mLogLines.clear();
        mReader.reset();
        // the progress file still holds the last command's lines until the installer
        // starts and clears it: those must not show as this command's
        mSkipBefore = System.currentTimeMillis() - 2000;
        if (!Installer.start(cmd, args)) {
            showFailed(getString(R.string.inst_cant_start), false);
            return;
        }
        follow(cmd, quick, after);
    }

    /** Shows the progress of cmd (null: whatever runs) and then does after. */
    private void follow(String cmd, boolean quick, Runnable after) {
        if (cmd == null) mSkipBefore = 0;
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
        // what this is and why it takes its time, then what runs now
        CharSequence title = step < 0 ? getString(R.string.inst_working) : getString(stepName(step));
        String body = getString(R.string.inst_keep_on);
        if ("restore".equals(mCmd)) {
            title = getString(R.string.inst_restore);
            body = getString(R.string.inst_restore_working) + "\n\n" + body;
        } else if ("update-emmc".equals(mCmd)) {
            title = getString(R.string.inst_update_emmc);
            body = getString(R.string.inst_update_working) + "\n\n" + body;
        } else if ("write-image".equals(mCmd)) {
            title = getString(R.string.inst_adv_title);
            body = getString(R.string.inst_adv_working) + "\n\n" + body;
        } else if (mCmd.startsWith("datacard-")) {
            title = getString(R.string.inst_dc_working);
            body = getString(R.string.inst_dc_working_text) + "\n\n" + body;
        }
        page(step < 0 ? null : stepLabel(step), step < 0 ? -1 : stepAt(step), title, body);
        // One pane that fits without scrolling: what runs now, then the last parts finished.
        // The full list is in Log and Details.
        mSideScroll.setVisibility(View.GONE);
        mCurName = row(mBodyExtra, 0, getString(R.string.inst_starting), null);
        mCurName.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        mCurBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mCurBar.setFocusable(false);
        mCurBar.setMax(1000);
        mBodyExtra.addView(mCurBar);
        mCurLine = row(mBodyExtra, 0, "", null);
        mFinished = new LinearLayout(this);
        mFinished.setOrientation(LinearLayout.VERTICAL);
        mFinished.setPadding(0, dp(12), 0, 0);
        mBodyExtra.addView(mFinished);
        mFinishedRows.clear();
        mFinishedOps.clear();
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
        if (!r.optBoolean("ok") && r.optBoolean("not_exact")) {
            showNotExact(r.optString("error"));
        } else if (!r.optBoolean("ok") && r.optBoolean("low_battery") && mRetry != null) {
            showCharger(r.optString("error"), mRetry);
        } else if (!r.optBoolean("ok")) {
            final JSONObject st = Installer.state();
            // emmc_written: a chained run stopped after its eMMC part was done
            showFailed(r.optString("error"), r.optBoolean("emmc_written")
                    || !st.optString("failed").isEmpty() || !st.optString("running").isEmpty());
        } else if (after != null) {
            after.run();
        } else {
            route();
        }
    }

    private void apply(JSONObject e) {
        if (!mCmd.isEmpty() && !mCmd.equals(e.optString("step"))) return;
        if (mSkipBefore > 0 && eventTime(e) < mSkipBefore) return;
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

    /** When the installer wrote the event (UTC, ms), 0 if it can't be read. */
    private static long eventTime(JSONObject e) {
        try {
            return java.time.Instant.parse(e.optString("time")).toEpochMilli();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String shortSha(String sha) {
        return sha.length() >= 12 ? "  " + sha.substring(0, 12) : "";
    }

    private void updateProgress() {
        Op current = null;
        for (Op op : mOps.values()) {
            if ("ok".equals(op.state) || "fail".equals(op.state)) {
                if (mFinishedRows.add(op.name)) {
                    mFinishedOps.add(op);
                    showFinished();
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

    /** The last parts finished, one line each; with more, a line counting the earlier ones. */
    private void showFinished() {
        mFinished.removeAllViews();
        final int n = mFinishedOps.size();
        final int shown = n <= FINISHED_SHOWN + 1 ? n : FINISHED_SHOWN;
        if (n > shown) {
            final TextView more = row(mFinished, R.drawable.ic_inst_ok,
                    getString(R.string.inst_finished_earlier, n - shown), null);
            more.setTextColor(mBody.getTextColors());
        }
        for (Op op : mFinishedOps.subList(n - shown, n)) {
            final boolean ok = "ok".equals(op.state);
            final TextView t = row(mFinished, ok ? R.drawable.ic_inst_ok : R.drawable.ic_inst_bad,
                    op.name + "  ·  " + (ok ? getString(R.string.inst_op_ok, size(op.total),
                            DateUtils.formatElapsedTime(Math.max(0, op.ended - op.began) / 1000))
                            : getString(R.string.inst_op_failed, op.log)), null);
            t.setSingleLine(ok);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
        } else {
            sb.append(getString(R.string.inst_sectors, op.dev, op.from, Math.max(op.from, op.to - 1)));
        }
        return sb.toString();
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
                    : "written".equals(st.optString("step2")) ? "restart"
                    : "deferred".equals(st.optString("step2")) ? "deferred" : "",
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
                what = "restart".equals(s[i]) ? R.string.inst_st_restart
                        : "deferred".equals(s[i]) ? R.string.inst_st_deferred : R.string.inst_st_todo;
                icon = 0;
            }
            sideRow(icon, getString(stepName(i)), getString(what));
        }
    }
}
