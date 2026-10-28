package org.andr36oid.subsystemsetup;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.andr36oid.hints.HintBar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-house setup pages and settings; all privileged work is requested via properties. */
public class SubsystemSetupActivity extends Activity {
    private enum Screen { WIZARD, HOME, SETTINGS, ADVANCED, PICKER, CONFIRM, CONSOLE }
    private static final long POLL_MS = 1000;
    private static final long START_TIMEOUT_MS = 30_000;
    private static final int MAX_ENTRIES = 500;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private final Runnable mPoll = this::poll;
    private final List<View> mControls = new ArrayList<>();
    private final List<Button> mLaunchButtons = new ArrayList<>();
    private final List<View> mIdleControls = new ArrayList<>();
    private SubsystemBackend mBackend;
    private SharedPreferences mPrefs;
    private SubsystemBackend.Snapshot mSnapshot = new SubsystemBackend.Snapshot();
    private Screen mScreen, mReturnScreen = Screen.HOME, mConfirmReturn = Screen.HOME;
    private Screen mConsoleReturn = Screen.HOME;
    private int mStep, mPickerGeneration, mRefreshGeneration;
    private int mHeldConfirmKey = KeyEvent.KEYCODE_UNKNOWN, mConsumedConfirmKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean mResumed, mPolling, mWorking, mScanRequested;
    private boolean mHandoff = true, mPending, mSawRunning, mLaunchTimedOut, mFollowLog = true;
    private boolean mConfirmDownload, mSelectedValid, mValidating;
    private String mRootfs = "", mTarget = "ES", mPickerPath = "";
    private long mLaunchAt, mScanAt, mValidatedAt;
    private List<File> mRoots = new ArrayList<>();
    private Runnable mBackAction;
    private TextView mLabel, mTitle, mBody, mStatus, mDownloadStatus, mConsoleStatus, mLogText;
    private ProgressBar mProgress;
    private LinearLayout mSide, mBodyExtra;
    private ScrollView mTextScroll, mSideScroll, mLogScroll;
    private View mPanes, mConsole;
    private Button mBack, mSecondary, mNext, mDownloadButton, mScanButton;
    private HoldButton mHold;
    private HintBar mHints;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.subsystem_wizard);
        mHints = HintBar.attachDefault(this);
        mBackend = new SubsystemBackend(this);
        mPrefs = getSharedPreferences("subsystem_setup", Context.MODE_PRIVATE);
        mLabel = findViewById(R.id.step_label);
        mTitle = findViewById(R.id.title);
        mBody = findViewById(R.id.body);
        mProgress = findViewById(R.id.progress);
        mTextScroll = findViewById(R.id.text_scroll);
        mSideScroll = findViewById(R.id.side_scroll);
        mSideScroll.setFocusable(false);
        mSide = findViewById(R.id.side);
        mBodyExtra = findViewById(R.id.body_extra);
        mPanes = findViewById(R.id.panes);
        mConsole = findViewById(R.id.console);
        mConsoleStatus = findViewById(R.id.console_status);
        mLogText = findViewById(R.id.log_text);
        mLogScroll = findViewById(R.id.log_scroll);
        mBack = findViewById(R.id.back);
        mSecondary = findViewById(R.id.secondary);
        mNext = findViewById(R.id.next);
        ((View) mBack.getParent()).addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) fitButtons();
        });
        mLogScroll.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) setFollowLog(false);
            return false;
        });
        mRootfs = SubsystemBackend.get(SubsystemBackend.ROOTFS);
        mHandoff = !"0".equals(SubsystemBackend.get(SubsystemBackend.HANDOFF));
        mSnapshot.image = SubsystemBackend.get(SubsystemBackend.IMAGE);
        mSnapshot.session = SubsystemBackend.get(SubsystemBackend.SESSION);
        mPending = mPrefs.getBoolean("launch_pending", false);
        mLaunchAt = mPrefs.getLong("launch_elapsed", 0);
        mSawRunning = mPrefs.getBoolean("launch_saw_running", false);
        if (mLaunchAt > SystemClock.elapsedRealtime()) clearPending();
        if (state != null) {
            mStep = Math.max(0, Math.min(3, state.getInt("step")));
            mReturnScreen = Screen.valueOf(state.getString("return", Screen.HOME.name()));
            mConfirmReturn = Screen.valueOf(state.getString("confirm_return", Screen.HOME.name()));
            mConsoleReturn = Screen.valueOf(state.getString("console_return", Screen.HOME.name()));
            mRootfs = state.getString("rootfs", mRootfs);
            mHandoff = state.getBoolean("handoff", mHandoff);
            mTarget = state.getString("target", "ES");
            mPickerPath = state.getString("picker", "");
            mConfirmDownload = state.getBoolean("download_confirm");
            mScanRequested = state.getBoolean("scan_requested");
            mScanAt = state.getLong("scan_at");
            mFollowLog = state.getBoolean("follow", true);
            mLaunchTimedOut = state.getBoolean("launch_timeout");
            show(Screen.valueOf(state.getString("screen", Screen.HOME.name())));
        } else if (mPending || running()) {
            mReturnScreen = Screen.HOME;
            mConsoleReturn = !mHandoff || !mRootfs.isEmpty() ? Screen.ADVANCED : Screen.HOME;
            show(Screen.CONSOLE);
        } else {
            show(mPrefs.getBoolean("setup_complete", false) ? Screen.HOME : Screen.WIZARD);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        mHandler.removeCallbacks(mPoll);
        mHandler.post(mPoll);
        if (mScreen == Screen.ADVANCED) validateSelection();
    }

    @Override
    protected void onPause() {
        mResumed = false;
        mHeldConfirmKey = mConsumedConfirmKey = KeyEvent.KEYCODE_UNKNOWN;
        mHandler.removeCallbacks(mPoll);
        if (mHold != null) mHold.cancelHold();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mPickerGeneration++;
        mHandler.removeCallbacksAndMessages(null);
        mIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("screen", mScreen.name());
        state.putString("return", mReturnScreen.name());
        state.putString("confirm_return", mConfirmReturn.name());
        state.putString("console_return", mConsoleReturn.name());
        state.putInt("step", mStep);
        state.putString("rootfs", mRootfs);
        state.putBoolean("handoff", mHandoff);
        state.putString("target", mTarget);
        state.putString("picker", mPickerPath);
        state.putBoolean("download_confirm", mConfirmDownload);
        state.putBoolean("scan_requested", mScanRequested);
        state.putLong("scan_at", mScanAt);
        state.putBoolean("follow", mFollowLog);
        state.putBoolean("launch_timeout", mLaunchTimedOut);
    }

    private void poll() {
        if (!mResumed || mPolling) return;
        mPolling = true;
        final boolean readLog = mScreen == Screen.CONSOLE;
        final int generation = mRefreshGeneration;
        mIo.execute(() -> {
            final SubsystemBackend.Snapshot snapshot = mBackend.poll(readLog);
            mHandler.post(() -> {
                mPolling = false;
                if (isDestroyed()) return;
                if (generation != mRefreshGeneration) {
                    if (mResumed) mHandler.post(mPoll);
                    return;
                }
                mSnapshot = snapshot;
                if (snapshot.notice != 0) message(snapshot.notice);
                if (running()) mLaunchTimedOut = false;
                if (mPending) {
                    if (running()) {
                        mSawRunning = true;
                        mPrefs.edit().putBoolean("launch_saw_running", true).apply();
                    } else if ("ended".equals(snapshot.session)
                            && (mSawRunning || !snapshot.log.isEmpty())) {
                        clearPending();
                    } else if (!mSawRunning && SystemClock.elapsedRealtime() - mLaunchAt > START_TIMEOUT_MS) {
                        mLaunchTimedOut = true;
                        clearPending();
                    }
                }
                if (!mScanRequested && !snapshot.downloading && !busy()) requestScan();
                if (mScreen == Screen.ADVANCED && !mValidating
                        && SystemClock.elapsedRealtime() - mValidatedAt > 5000) validateSelection();
                update();
                if (mResumed) mHandler.postDelayed(mPoll, POLL_MS);
            });
        });
    }

    private void clearPending() {
        mPending = false;
        if (mPrefs != null) mPrefs.edit().remove("launch_pending")
                .remove("launch_elapsed").remove("launch_saw_running").apply();
    }

    private boolean running() {
        return "running".equals(mSnapshot.session);
    }

    private boolean busy() {
        return mWorking || mPending || running();
    }

    private void requestScan() {
        if (busy()) return;
        mScanRequested = true;
        mScanAt = SystemClock.elapsedRealtime();
        if (!SubsystemBackend.set(SubsystemBackend.REQUEST, "scan")) message(R.string.subsystem_scan_failed);
        update();
    }

    private void show(Screen screen) {
        if (mHold != null) mHold.cancelHold();
        mHold = null;
        mPickerGeneration++;
        mScreen = screen;
        mControls.clear();
        mLaunchButtons.clear();
        mIdleControls.clear();
        mSide.removeAllViews();
        mBodyExtra.removeAllViews();
        mStatus = mDownloadStatus = null;
        mDownloadButton = mScanButton = null;
        mBackAction = this::finish;
        mPanes.setVisibility(screen == Screen.CONSOLE ? View.GONE : View.VISIBLE);
        mConsole.setVisibility(screen == Screen.CONSOLE ? View.VISIBLE : View.GONE);
        mSideScroll.setVisibility(View.VISIBLE);
        mTextScroll.scrollTo(0, 0);
        mSideScroll.scrollTo(0, 0);
        mProgress.setVisibility(View.GONE);
        mProgress.setIndeterminate(false);
        mHints.setLeading(null);
        mHints.set("A", getString(R.string.andr36oid_hint_select),
                "B", getString(R.string.andr36oid_hint_back));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        for (Button button : new Button[] {mBack, mSecondary, mNext}) {
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
            button.setEnabled(true);
        }
        switch (screen) {
            case WIZARD: wizard(); break;
            case HOME: home(); break;
            case SETTINGS: settings(); break;
            case ADVANCED: advanced(); break;
            case PICKER: picker(); break;
            case CONFIRM: confirm(); break;
            case CONSOLE: console(); break;
        }
        update();
        wireFocus();
        mHandler.post(() -> {
            if (isDestroyed() || mScreen != screen) return;
            fitButtons();
            if (mHold != null) mHold.requestFocus();
            else if (screen == Screen.HOME || screen == Screen.SETTINGS || screen == Screen.ADVANCED || screen == Screen.PICKER) focusFirst();
            else if (mNext.isShown() && mNext.isEnabled()) mNext.requestFocus();
            else focusFirst();
        });
    }

    private void page(int label, int title, CharSequence body) {
        mLabel.setText(label);
        mTitle.setText(title);
        mBody.setText(body);
        mTextScroll.setContentDescription(getString(title));
    }

    private void wizard() {
        mProgress.setVisibility(View.VISIBLE);
        mProgress.setProgress(mStep + 1);
        mLabel.setText(getString(R.string.subsystem_step_count, mStep + 1, 4));
        mBackAction = () -> {
            if (mStep == 0) show(Screen.HOME);
            else { mStep--; show(Screen.WIZARD); }
        };
        nav(mBack, mStep == 0 ? R.string.subsystem_close : R.string.subsystem_back, mBackAction);
        switch (mStep) {
            case 0:
                mTitle.setText(R.string.subsystem_welcome);
                mBody.setText(getString(R.string.subsystem_intro) + "\n\n"
                        + getString(R.string.subsystem_about));
                section(mSide, R.string.subsystem_setup);
                text(mSide, getString(R.string.subsystem_step_image) + "\n"
                        + getString(R.string.subsystem_step_image_summary));
                text(mSide, getString(R.string.subsystem_step_portmaster) + "\n"
                        + getString(R.string.subsystem_step_portmaster_summary));
                text(mSide, getString(R.string.subsystem_step_ports) + "\n"
                        + getString(R.string.subsystem_step_ports_summary));
                nav(mSecondary, R.string.subsystem_settings, () -> openFromHere(Screen.SETTINGS));
                nav(mNext, R.string.subsystem_continue, this::nextStep);
                break;
            case 1:
                mTitle.setText(R.string.subsystem_image_title);
                mBody.setText(getString(R.string.subsystem_step_image_summary) + "\n\n"
                        + getString(R.string.subsystem_offline_first));
                imageStatus();
                mScanButton = action(mSide, R.string.subsystem_check_again, null, this::requestScan, false);
                section(mSide, R.string.subsystem_get_image);
                mDownloadStatus = text(mSide, "");
                mDownloadButton = action(mSide, R.string.subsystem_download_image, null,
                        this::confirmDownload, false);
                nav(mSecondary, R.string.subsystem_advanced, () -> openFromHere(Screen.ADVANCED));
                nav(mNext, R.string.subsystem_continue, this::nextStep);
                break;
            case 2:
                mTitle.setText(R.string.subsystem_ports_title);
                mBody.setText(R.string.subsystem_step_portmaster_summary);
                section(mSide, R.string.subsystem_step_ports);
                text(mSide, getString(R.string.subsystem_step_ports_summary));
                text(mSide, getString(R.string.subsystem_ports_note));
                nav(mNext, R.string.subsystem_continue, this::nextStep);
                break;
            default:
                mTitle.setText(R.string.subsystem_ready_title);
                mBody.setText(R.string.subsystem_ready_body);
                imageStatus();
                section(mSide, R.string.subsystem_boot_settings);
                text(mSide, getString(R.string.subsystem_boot_override));
                nav(mSecondary, R.string.subsystem_settings, () -> openFromHere(Screen.SETTINGS));
                nav(mNext, R.string.subsystem_finish, () -> {
                    mPrefs.edit().putBoolean("setup_complete", true).apply();
                    show(Screen.HOME);
                });
        }
        mTextScroll.setContentDescription(mTitle.getText());
    }

    private void nextStep() {
        if (mStep == 1 && !canLaunch(false)) return;
        mStep = Math.min(3, mStep + 1);
        show(Screen.WIZARD);
    }

    private void home() {
        page(R.string.subsystem_home, R.string.subsystem_title, getString(R.string.subsystem_home_body));
        imageStatus();
        text(mBodyExtra, getString(R.string.subsystem_boot_override));
        section(mSide, R.string.subsystem_launch);
        launchAction("ES", false);
        launchAction("PM", false);
        action(mSide, R.string.subsystem_setup, null, () -> {
            mStep = 0;
            show(Screen.WIZARD);
        }, false);
        nav(mBack, R.string.subsystem_close, this::finish);
        nav(mSecondary, R.string.subsystem_advanced, () -> openFromHere(Screen.ADVANCED));
        nav(mNext, R.string.subsystem_settings, () -> openFromHere(Screen.SETTINGS));
        if (running()) action(mSide, R.string.subsystem_boot_log, null, () -> {
            mConsoleReturn = Screen.HOME;
            show(Screen.CONSOLE);
        }, false);
    }

    private void openFromHere(Screen screen) {
        mReturnScreen = mScreen;
        show(screen);
    }

    private void settings() {
        page(R.string.subsystem_home, R.string.subsystem_settings,
                getString(R.string.subsystem_boot_explainer) + "\n\n" + getString(R.string.subsystem_boot_override));
        mBackAction = () -> show(mReturnScreen);
        final Switch boot = new Switch(this);
        boot.setText(R.string.subsystem_boot_default);
        boot.setChecked("subsystem".equals(SubsystemBackend.get(SubsystemBackend.DEFAULT)));
        decorateControl(boot);
        mSide.addView(boot, rowParams());
        boot.setOnCheckedChangeListener((view, enabled) -> {
            // Write the fallback explicitly before making the subsystem the default OS.
            if (enabled && !SubsystemBackend.set(SubsystemBackend.DEFAULT_TARGET, selectedBootTarget())) {
                boot.setChecked(false);
                message(R.string.subsystem_setting_failed);
                return;
            }
            if (!SubsystemBackend.set(SubsystemBackend.DEFAULT, enabled ? "subsystem" : "android")) {
                boot.setOnCheckedChangeListener(null);
                boot.setChecked(!enabled);
                message(R.string.subsystem_setting_failed);
                // Rebuild once to restore the listener and the actual property values.
                show(Screen.SETTINGS);
            }
        });
        section(mSide, R.string.subsystem_default_target);
        final RadioGroup targets = new RadioGroup(this);
        final RadioButton es = radio(targets, R.string.subsystem_target_es);
        final RadioButton pm = radio(targets, R.string.subsystem_target_pm);
        targets.check("PM".equals(SubsystemBackend.defaultTarget()) ? pm.getId() : es.getId());
        targets.setOnCheckedChangeListener((group, id) -> {
            if (!SubsystemBackend.set(SubsystemBackend.DEFAULT_TARGET, id == pm.getId() ? "PM" : "ES")) {
                message(R.string.subsystem_setting_failed);
                show(Screen.SETTINGS);
            }
        });
        mSide.addView(targets);
        nav(mBack, R.string.subsystem_back, mBackAction);
        nav(mNext, R.string.subsystem_done, mBackAction);
    }

    private String selectedBootTarget() {
        return SubsystemBackend.defaultTarget();
    }

    private void advanced() {
        page(R.string.subsystem_advanced, R.string.subsystem_advanced,
                getString(R.string.subsystem_advanced_body));
        mBackAction = () -> show(mReturnScreen);
        section(mBodyExtra, R.string.subsystem_rootfs);
        text(mBodyExtra, mRootfs.isEmpty() ? getString(R.string.subsystem_automatic_image) : mRootfs);
        mIdleControls.add(action(mSide, R.string.subsystem_choose_image, null, () -> {
            mPickerPath = "";
            show(Screen.PICKER);
        }, false));
        mIdleControls.add(action(mSide, R.string.subsystem_automatic_image, null, () -> {
            if (SubsystemBackend.set(SubsystemBackend.ROOTFS, "")) {
                mRootfs = "";
                mSelectedValid = false;
                requestScan();
                show(Screen.ADVANCED);
            } else message(R.string.subsystem_setting_failed);
        }, false));
        section(mSide, R.string.subsystem_display_mode);
        final RadioGroup modes = new RadioGroup(this);
        final RadioButton full = radio(modes, R.string.subsystem_full_handoff);
        final RadioButton keep = radio(modes, R.string.subsystem_keep_android);
        modes.check(mHandoff ? full.getId() : keep.getId());
        mIdleControls.add(full);
        mIdleControls.add(keep);
        modes.setOnCheckedChangeListener((group, id) -> {
            final boolean handoff = id == full.getId();
            if (SubsystemBackend.set(SubsystemBackend.HANDOFF, handoff ? "1" : "0")) {
                mHandoff = handoff;
                show(Screen.ADVANCED);
                mHandler.post(() -> {
                    for (View control : mControls) {
                        if (control instanceof RadioButton && ((RadioButton) control).isChecked()) {
                            control.requestFocus();
                            break;
                        }
                    }
                });
            } else {
                message(R.string.subsystem_setting_failed);
                show(Screen.ADVANCED);
            }
        });
        mSide.addView(modes);
        text(mSide, getString(mHandoff ? R.string.subsystem_handoff_explainer
                : R.string.subsystem_headless_explainer));
        imageStatus();
        section(mSide, R.string.subsystem_launch);
        launchAction("ES", true);
        launchAction("PM", true);
        nav(mBack, R.string.subsystem_back, mBackAction);
        if (!mHandoff || running() || mPending) nav(mSecondary, R.string.subsystem_boot_log, () -> {
            mConsoleReturn = Screen.ADVANCED;
            show(Screen.CONSOLE);
        });
        validateSelection();
    }

    private void validateSelection() {
        if (mValidating) return;
        mValidating = true;
        final String selected = mRootfs;
        mIo.execute(() -> {
            final List<File> roots = mBackend.storageRoots();
            final boolean valid = !selected.isEmpty() && SubsystemBackend.eligible(new File(selected), roots);
            mHandler.post(() -> {
                mValidating = false;
                mValidatedAt = SystemClock.elapsedRealtime();
                if (isDestroyed() || !selected.equals(mRootfs)) return;
                mSelectedValid = valid;
                update();
            });
        });
    }

    private void imageStatus() {
        final LinearLayout parent = mScreen == Screen.HOME || mScreen == Screen.ADVANCED ? mBodyExtra : mSide;
        section(parent, R.string.subsystem_status);
        mStatus = text(parent, "");
        mStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private void launchAction(String target, boolean advanced) {
        final boolean es = "ES".equals(target);
        final Button button = action(mSide, es ? R.string.subsystem_open_es : R.string.subsystem_open_pm,
                getString(es ? R.string.subsystem_open_es_summary : R.string.subsystem_open_pm_summary),
                () -> {
                    if (!canLaunch(advanced)) return;
                    mTarget = target;
                    mConfirmReturn = mScreen;
                    mConfirmDownload = false;
                    show(Screen.CONFIRM);
                }, es);
        mLaunchButtons.add(button);
    }

    private boolean canLaunch(boolean advanced) {
        return !busy() && !mSnapshot.downloading && (advanced && !mRootfs.isEmpty()
                ? mSelectedValid : "1".equals(mSnapshot.image));
    }

    private void confirmDownload() {
        if (!mSnapshot.canDownload || busy()) return;
        mConfirmDownload = true;
        mConfirmReturn = mScreen;
        show(Screen.CONFIRM);
    }

    private void confirm() {
        final boolean fullHandoff = mConfirmReturn != Screen.ADVANCED || mHandoff;
        page(mConfirmDownload ? R.string.subsystem_get_image : R.string.subsystem_launch,
                mConfirmDownload ? R.string.subsystem_download_image : fullHandoff
                        ? R.string.subsystem_handoff_confirm : R.string.subsystem_headless_confirm,
                getString(mConfirmDownload ? R.string.subsystem_download_confirm : fullHandoff
                        ? R.string.subsystem_handoff_explainer : R.string.subsystem_headless_explainer));
        mBackAction = () -> show(mConfirmReturn);
        if (mConfirmDownload) mDownloadStatus = text(mBodyExtra, "");
        if (!mConfirmDownload) {
            section(mBodyExtra, R.string.subsystem_launch_target);
            text(mBodyExtra, getString("PM".equals(mTarget) ? R.string.subsystem_target_pm : R.string.subsystem_target_es));
            section(mBodyExtra, R.string.subsystem_rootfs);
            text(mBodyExtra, mConfirmReturn == Screen.ADVANCED && !mRootfs.isEmpty()
                    ? mRootfs : getString(R.string.subsystem_automatic_image));
        }
        text(mSide, getString(R.string.subsystem_hold_hint));
        mHold = new HoldButton(this, () -> {
            // The confirming key can still be down when the next page appears.
            mConsumedConfirmKey = mHeldConfirmKey;
            if (mConfirmDownload) startDownload();
            else launch(mConfirmReturn == Screen.ADVANCED);
        });
        decorateControl(mHold);
        mSide.addView(mHold, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        nav(mBack, R.string.subsystem_cancel, mBackAction);
        mHints.set("A", getString(R.string.subsystem_hold), "B", getString(R.string.subsystem_cancel));
    }

    private void startDownload() {
        if (mWorking || busy() || !mSnapshot.canDownload) return;
        mWorking = true;
        update();
        mIo.execute(() -> {
            final int result = mBackend.downloadImage();
            mHandler.post(() -> {
                mWorking = false;
                if (isDestroyed()) return;
                if (result == R.string.subsystem_download_started) mSnapshot.downloading = true;
                message(result);
                show(mConfirmReturn);
            });
        });
    }

    private void launch(boolean advanced) {
        if (!canLaunch(advanced)) { show(mConfirmReturn); return; }
        final String rootfs = advanced ? mRootfs : "";
        final boolean handoff = !advanced || mHandoff;
        final String target = mTarget;
        mRefreshGeneration++;
        mWorking = true;
        mLaunchTimedOut = false;
        mSawRunning = false;
        mLaunchAt = SystemClock.elapsedRealtime();
        final long startedAt = mLaunchAt;
        mSnapshot.log = "";
        update();
        mIo.execute(() -> {
            final boolean valid = rootfs.isEmpty() || SubsystemBackend.eligible(new File(rootfs), mBackend.storageRoots());
            mBackend.log.beginLaunch();
            final boolean configured = valid && !"running".equals(SubsystemBackend.get(SubsystemBackend.SESSION))
                    && SubsystemBackend.set(SubsystemBackend.ROOTFS, rootfs)
                    && SubsystemBackend.set(SubsystemBackend.HANDOFF, handoff ? "1" : "0")
                    && SubsystemBackend.set(SubsystemBackend.PORT, target);
            // Persist the console boundary before a full handoff can stop this process.
            if (configured) mPrefs.edit().putBoolean("launch_pending", true)
                    .putLong("launch_elapsed", startedAt).putBoolean("launch_saw_running", false)
                    .putString("boot_log_boundary", mBackend.log.save()).commit();
            final boolean success = configured && SubsystemBackend.set(SubsystemBackend.REQUEST, "run");
            if (!success) mPrefs.edit().remove("launch_pending").remove("launch_elapsed")
                    .remove("launch_saw_running").apply();
            mHandler.post(() -> {
                mWorking = false;
                if (isDestroyed()) return;
                if (!success) {
                    message(valid ? R.string.subsystem_launch_failed : R.string.subsystem_image_invalid);
                    show(mConfirmReturn);
                    return;
                }
                mPending = true;
                if (!advanced) {
                    mRootfs = "";
                    mHandoff = true;
                }
                mConsoleReturn = advanced ? Screen.ADVANCED : Screen.HOME;
                mFollowLog = true;
                show(Screen.CONSOLE);
            });
        });
    }

    private void console() {
        mLabel.setText(R.string.subsystem_boot_log);
        mBackAction = () -> show(mConsoleReturn);
        nav(mBack, R.string.subsystem_back, mBackAction);
        nav(mSecondary, mFollowLog ? R.string.subsystem_pause_log : R.string.subsystem_follow_log,
                () -> setFollowLog(!mFollowLog));
        nav(mNext, R.string.subsystem_done, mBackAction);
        mLogText.setFocusable(false);
        mLogScroll.setFocusable(true);
    }

    private void setFollowLog(boolean follow) {
        mFollowLog = follow;
        if (mScreen != Screen.CONSOLE) return;
        mSecondary.setText(follow ? R.string.subsystem_pause_log : R.string.subsystem_follow_log);
        if (follow) mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void update() {
        if (mStatus != null) {
            final int status;
            if (running()) status = R.string.subsystem_session_running;
            else if (mPending || mWorking) status = R.string.subsystem_session_starting;
            else if (mSnapshot.downloading) status = R.string.subsystem_download_pending;
            else if (mScreen == Screen.ADVANCED && !mRootfs.isEmpty()) {
                status = mValidating ? R.string.subsystem_checking : mSelectedValid
                        ? R.string.subsystem_selected_image_ready : R.string.subsystem_image_invalid;
            } else if ("1".equals(mSnapshot.image)) status = R.string.subsystem_installed;
            else if (mSnapshot.image.isEmpty()) status = mScanRequested && SystemClock.elapsedRealtime() - mScanAt > 15_000
                    ? R.string.subsystem_scan_waiting : R.string.subsystem_checking;
            else status = R.string.subsystem_missing;
            setText(mStatus, getString(status));
        }
        for (Button launch : mLaunchButtons) launch.setEnabled(canLaunch(mScreen == Screen.ADVANCED));
        for (View control : mIdleControls) control.setEnabled(!busy());
        if (mScreen == Screen.WIZARD && (mStep == 1 || mStep == 3)) mNext.setEnabled(canLaunch(false));
        if (mScanButton != null) mScanButton.setEnabled(!busy());
        if (mDownloadStatus != null) {
            setText(mDownloadStatus, mSnapshot.downloading && mSnapshot.downloadPercent >= 0
                    ? getString(R.string.subsystem_download_progress, mSnapshot.downloadPercent)
                    : getString(mSnapshot.downloadText));
        }
        if (mDownloadButton != null) {
            // No network assumption: this offer appears only after validated connectivity.
            mDownloadButton.setVisibility(mSnapshot.online && !mSnapshot.downloading ? View.VISIBLE : View.GONE);
            mDownloadButton.setEnabled(mSnapshot.canDownload && !busy());
            wireFocus();
        }
        if (mHold != null) mHold.setEnabled(mConfirmDownload ? mSnapshot.canDownload && !busy()
                : canLaunch(mConfirmReturn == Screen.ADVANCED));
        if (mScreen == Screen.CONSOLE) {
            int status = running() && mSnapshot.provisioning ? R.string.subsystem_provisioning
                    : running() ? R.string.subsystem_session_running : mPending || mWorking
                    ? R.string.subsystem_session_starting : mLaunchTimedOut ? R.string.subsystem_session_timeout
                    : "ended".equals(mSnapshot.session) ? R.string.subsystem_session_ended : R.string.subsystem_session_idle;
            setText(mConsoleStatus, getString(status));
            mProgress.setVisibility((mPending && !running()) || (running() && mSnapshot.provisioning)
                    ? View.VISIBLE : View.GONE);
            mProgress.setIndeterminate(true);
            mNext.setVisibility(!mPending && !running() ? View.VISIBLE : View.GONE);
            final String log = mSnapshot.logUnreadable ? getString(R.string.subsystem_log_unreadable)
                    : mSnapshot.log.isEmpty() ? getString(R.string.subsystem_log_waiting) : mSnapshot.log;
            if (!log.contentEquals(mLogText.getText())) {
                mLogText.setText(log);
                if (mFollowLog) mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
            }
            if (mResumed && (mPending || running())) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            wireFocus();
            fitButtons();
        }
        final View focused = getCurrentFocus();
        if (focused != null && (!focused.isEnabled() || !focused.isShown())) focusFirst();
    }

    private static void setText(TextView view, CharSequence text) {
        if (!text.toString().contentEquals(view.getText())) view.setText(text);
    }

    private void picker() {
        page(R.string.subsystem_advanced, R.string.subsystem_choose_image, getString(R.string.subsystem_picker_body));
        mBackAction = this::pickerUp;
        nav(mBack, R.string.subsystem_back, mBackAction);
        nav(mSecondary, R.string.subsystem_refresh, () -> show(Screen.PICKER));
        if (!mPickerPath.isEmpty()) text(mBodyExtra, mPickerPath);
        text(mSide, getString(R.string.subsystem_checking));
        final int generation = mPickerGeneration;
        final String path = mPickerPath;
        mIo.execute(() -> {
            final List<File> roots = mBackend.storageRoots();
            final List<File> entries = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            boolean failed = false, limited = false;
            try {
                if (path.isEmpty()) {
                    entries.addAll(roots);
                    for (File root : roots) labels.add(mBackend.storageLabel(root));
                }
                else {
                    final File directory = new File(path).getCanonicalFile();
                    if (!SubsystemBackend.inside(directory, roots)) throw new IOException("Location unmounted");
                    final File[] files = directory.listFiles();
                    if (files == null) throw new IOException("Location unreadable");
                    Arrays.sort(files, Comparator.comparing((File f) -> !f.isDirectory())
                            .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                    for (File file : files) {
                        if (!file.isHidden() && SubsystemBackend.inside(file, roots)
                                && ((file.isDirectory() && file.canRead()) || SubsystemBackend.eligible(file, roots))) {
                            if (entries.size() == MAX_ENTRIES) { limited = true; break; }
                            entries.add(file.getCanonicalFile());
                        }
                    }
                }
            } catch (IOException | SecurityException e) { failed = true; }
            final boolean error = failed, truncated = limited;
            final List<Boolean> folders = new ArrayList<>();
            final List<String> summaries = new ArrayList<>();
            for (File file : entries) {
                final boolean directory = file.isDirectory();
                folders.add(directory);
                summaries.add(directory ? file.getAbsolutePath() : Formatter.formatFileSize(this, file.length()));
            }
            mHandler.post(() -> {
                if (isDestroyed() || mScreen != Screen.PICKER || mPickerGeneration != generation) return;
                mRoots = roots;
                mSide.removeAllViews();
                mControls.clear();
                if (error) text(mSide, getString(R.string.subsystem_picker_unavailable));
                else if (entries.isEmpty()) text(mSide, getString(path.isEmpty()
                        ? R.string.subsystem_picker_no_storage : R.string.subsystem_picker_empty));
                for (int i = 0; i < entries.size(); i++) {
                    final File file = entries.get(i);
                    final boolean directory = folders.get(i);
                    final Button button = action(mSide, path.isEmpty() ? labels.get(i) : file.getName(), summaries.get(i),
                            () -> {
                                if (directory) { mPickerPath = file.getAbsolutePath(); show(Screen.PICKER); }
                                else chooseImage(file);
                            }, false);
                    button.setContentDescription(getString(directory ? R.string.subsystem_picker_folder
                            : R.string.subsystem_picker_image, file.getAbsolutePath()));
                }
                if (truncated) text(mSide, getString(R.string.subsystem_picker_limited));
                wireFocus();
                focusFirst();
            });
        });
    }

    private void pickerUp() {
        if (mPickerPath.isEmpty()) { show(Screen.ADVANCED); return; }
        final File current = new File(mPickerPath);
        boolean atRoot = false;
        for (File root : mRoots) if (root.equals(current)) { atRoot = true; break; }
        mPickerPath = atRoot || current.getParentFile() == null ? "" : current.getParent();
        show(Screen.PICKER);
    }

    private void chooseImage(File file) {
        if (busy()) { message(R.string.subsystem_session_running); return; }
        mWorking = true;
        mIo.execute(() -> {
            final boolean valid = SubsystemBackend.eligible(file, mBackend.storageRoots());
            final boolean success = valid && SubsystemBackend.set(SubsystemBackend.ROOTFS, file.getAbsolutePath());
            mHandler.post(() -> {
                mWorking = false;
                if (isDestroyed()) return;
                if (success) {
                    mRootfs = file.getAbsolutePath();
                    mSelectedValid = true;
                    if (mScreen == Screen.PICKER) show(Screen.ADVANCED);
                    else update();
                } else message(valid ? R.string.subsystem_setting_failed : R.string.subsystem_image_invalid);
            });
        });
    }

    private TextView text(LinearLayout parent, CharSequence value) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        view.setTextColor(mBody.getTextColors());
        view.setTextSize(15);
        view.setLineSpacing(0, 1.1f);
        view.setPadding(0, 0, 0, dp(12));
        parent.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private void section(LinearLayout parent, int title) {
        final TextView view = text(parent, getString(title));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(mTitle.getTextColors());
        view.setPadding(0, dp(8), 0, dp(6));
        view.setAccessibilityHeading(true);
    }

    private Button action(LinearLayout parent, int title, CharSequence summary, Runnable click, boolean primary) {
        return action(parent, getString(title), summary, click, primary);
    }

    private Button action(LinearLayout parent, CharSequence title, CharSequence summary, Runnable click, boolean primary) {
        final Button button = new Button(this, null, 0, primary ? R.style.Andr36oidSetupButton_Primary
                : R.style.Andr36oidSetupButton_Secondary);
        final SpannableStringBuilder label = new SpannableStringBuilder(title);
        label.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (summary != null && summary.length() > 0) {
            label.append('\n');
            final int start = label.length();
            label.append(summary);
            label.setSpan(new RelativeSizeSpan(0.85f), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setMinHeight(dp(52));
        button.setSingleLine(false);
        button.setOnClickListener(view -> click.run());
        decorateControl(button);
        parent.addView(button, rowParams());
        return button;
    }

    private RadioButton radio(RadioGroup parent, int title) {
        final RadioButton button = new RadioButton(this);
        button.setText(title);
        button.setTextSize(15);
        button.setMinHeight(dp(52));
        decorateControl(button);
        parent.addView(button, new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private void decorateControl(View view) {
        view.setId(View.generateViewId());
        view.setFocusable(true);
        view.setForeground(getDrawable(R.drawable.andr36oid_setup_focus));
        view.setMinimumHeight(dp(48));
        mControls.add(view);
    }

    private LinearLayout.LayoutParams rowParams() {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        return params;
    }

    private void nav(Button button, int label, Runnable action) {
        button.setText(label);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(view -> action.run());
    }

    private List<View> availableControls() {
        final List<View> available = new ArrayList<>();
        for (View control : mControls) if (control.getVisibility() == View.VISIBLE && control.isEnabled()) available.add(control);
        return available;
    }

    private void wireFocus() {
        final List<View> available = availableControls();
        final View footer = firstFooter();
        final int lastId = available.isEmpty() ? R.id.text_scroll : available.get(available.size() - 1).getId();
        for (int i = 0; i < available.size(); i++) {
            final View control = available.get(i);
            control.setNextFocusUpId(i == 0 ? R.id.text_scroll : available.get(i - 1).getId());
            control.setNextFocusDownId(i + 1 < available.size() ? available.get(i + 1).getId() : footer.getId());
            control.setNextFocusLeftId(R.id.text_scroll);
        }
        mTextScroll.setNextFocusRightId(available.isEmpty() ? footer.getId() : available.get(0).getId());
        final List<Button> buttons = new ArrayList<>();
        for (Button button : new Button[] {mBack, mSecondary, mNext}) {
            if (button.getVisibility() == View.VISIBLE && button.isEnabled()) buttons.add(button);
            button.setNextFocusUpId(mScreen == Screen.CONSOLE ? R.id.log_scroll : lastId);
        }
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setNextFocusLeftId(buttons.get(Math.max(0, i - 1)).getId());
            buttons.get(i).setNextFocusRightId(buttons.get(Math.min(buttons.size() - 1, i + 1)).getId());
        }
        mLogScroll.setNextFocusDownId(footer.getId());
    }

    private View firstFooter() {
        for (Button button : new Button[] {mNext, mSecondary, mBack}) {
            if (button.getVisibility() == View.VISIBLE && button.isEnabled()) return button;
        }
        return mBack;
    }

    private void focusFirst() {
        final List<View> controls = availableControls();
        if (!controls.isEmpty()) controls.get(0).requestFocus();
        else firstFooter().requestFocus();
    }

    private void fitButtons() {
        final ViewGroup row = (ViewGroup) mBack.getParent();
        final int width = row.getWidth();
        if (width <= 0) return;
        int count = 0;
        for (Button button : new Button[] {mBack, mSecondary, mNext}) if (button.getVisibility() == View.VISIBLE) count++;
        for (Button button : new Button[] {mBack, mSecondary, mNext}) {
            button.setSingleLine(false);
            button.setMaxLines(2);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMaxWidth(Math.max(dp(48), (width - dp(16)) / Math.max(1, count)));
        }
    }

    @Override
    public void onBackPressed() {
        if (mBackAction != null) mBackAction.run();
        else finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (key != KeyEvent.KEYCODE_UNKNOWN && key == mConsumedConfirmKey) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mConsumedConfirmKey = mHeldConfirmKey = KeyEvent.KEYCODE_UNKNOWN;
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            if (down && event.getRepeatCount() == 0) onBackPressed();
            return true;
        }
        final View focused = getCurrentFocus();
        if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_SPACE) {
            if (down && event.getRepeatCount() == 0 && focused instanceof HoldButton) mHeldConfirmKey = key;
            else if (!down && mHeldConfirmKey == key) mHeldConfirmKey = KeyEvent.KEYCODE_UNKNOWN;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_A && !(focused instanceof HoldButton)) {
            // Android's stock widgets do not consistently treat gamepad A as click.
            if (focused == null || !focused.isShown() || !focused.isEnabled()) { focusFirst(); return true; }
            return focused.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(),
                    event.getAction(), KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount(),
                    event.getMetaState(), event.getDeviceId(), event.getScanCode(), event.getFlags(), event.getSource()));
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (focused == null || !focused.isEnabled() || !focused.isShown()) {
                if (down) focusFirst();
                return true;
            }
            if (mScreen == Screen.CONSOLE && focused == mLogScroll && key == KeyEvent.KEYCODE_DPAD_UP && down) setFollowLog(false);
            if (focused == mTextScroll && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) focusFirst();
                return true;
            }
            if (focused == mTextScroll && key == KeyEvent.KEYCODE_DPAD_DOWN && !mTextScroll.canScrollVertically(1)) {
                if (down) firstFooter().requestFocus();
                return true;
            }
            if (mScreen != Screen.CONSOLE && availableControls().isEmpty()
                    && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
                if (down) mTextScroll.smoothScrollBy(0, (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * mTextScroll.getHeight() / 3);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void message(int text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
