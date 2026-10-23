package org.andr36oid.usbmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.andr36oid.hints.HintBar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** The hub and conditional wizard share the tutorial's page and Back/Next pattern. */
public class UsbDriveActivity extends Activity {
    private static final int HUB = 0, CONNECT = 1, IMAGES = 2, CREATE = 3, PROGRESS = 4, SESSION = 5;
    private static final class Work {
        final Handler handler = new Handler(Looper.getMainLooper());
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final AtomicInteger operation = new AtomicInteger();
        UsbDriveActivity activity;
        UsbFunctions policy = UsbDeviceControl.policies();
        int policyFor = -1;
        boolean scanning, querying, loaded, debugging, pendingRequest;
        String wifi = "";
        UsbDrive.Images images = new UsbDrive.Images();
        List<UsbDrive.Offer> offers = new ArrayList<>();

    }
    private Work mWork;
    private final UsbPageFocus mPageFocus = new UsbPageFocus();
    private boolean mReading;
    private final List<RadioButton> mSizeChoices = new ArrayList<>();
    private boolean mCustomLabel, mAwaitCreate, mUseAfterCreate, mSessionOpened, mLoaded, mCancelling;
    private int mCreateFunctions;
    private String mCreatedName = "", mCreatedCid = "", mUiError = "", mCompanion = "";
    private long mCreateWaitUntil;
    private final Handler mHandler = new Handler();
    private UsbMode mUsbMode;
    private UsbFunctions mPolicy;
    private SharedPreferences mPrefs;
    private LinearLayout mChoices;
    private TextView mStep, mTitle, mBody, mNote;
    private ProgressBar mProgress;
    private Button mNext, mBack;
    private ScrollView mTextScroll, mChoiceScroll;
    private AlertDialog mDialog;
    private int mPage = HUB, mIndex, mCreateIndex, mFunctions, mShownPage = -1, mShownStep = -1;
    private boolean mResumed, mAdvanced, mMacro, mRo, mCdrom, mDebugging;
    private String mPreset, mSelectedKey, mFocus = "", mLive = "";
    private String mImageName = "image1", mImageSize = "1", mImageFs = "fat32", mImageLabel;
    private long mNextScan;
    private UsbDrive.Offer mSelected;
    private UsbDrive.Images mImages = new UsbDrive.Images();
    private List<UsbDrive.Offer> mOffers = new ArrayList<>();
    private EditText mEdit;
    private final Runnable mPoll = new Runnable() {
        @Override public void run() {
            refresh();
            if (mResumed) mHandler.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(R.layout.usb_drive_activity);
        HintBar.attachDefault(this);
        mUsbMode = new UsbMode(this);
        mWork = (Work) getLastNonConfigurationInstance();
        if (mWork == null) mWork = new Work();
        mWork.activity = this; mPolicy = mWork.policy; mImages = mWork.images; mOffers = mWork.offers; mLoaded = mWork.loaded;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPreset = mPrefs.getString("usb_preset", "custom");
        mFunctions = mPrefs.getInt("usb_functions", UsbFunctions.XINPUT);
        mSelectedKey = mPrefs.getString("usb_storage", "");
        mCdrom = mPrefs.getBoolean("usb_cdrom", true);
        mRo = mPrefs.getBoolean("usb_ro", true);
        mMacro = mPrefs.getBoolean("usb_macro", false);
        mImageLabel = VirtualDrive.defaultLabel(mImageName, mImageFs);
        if (state != null) {
            mCancelling = state.getBoolean("cancelling");
            mCompanion = state.getString("companion", "");
            mCustomLabel = state.getBoolean("custom_label"); mAwaitCreate = state.getBoolean("await_create");
            mUseAfterCreate = state.getBoolean("use_after_create"); mCreateFunctions = state.getInt("create_functions");
            mCreatedName = state.getString("created_name", ""); mCreatedCid = state.getString("created_cid", "");
            mPage = state.getInt("page"); mIndex = state.getInt("index"); mCreateIndex = state.getInt("create_index");
            mAdvanced = state.getBoolean("advanced"); mFocus = state.getString("focus", "");
            mImageName = state.getString("image_name", ""); mImageSize = state.getString("image_size", "1");
            mImageFs = state.getString("image_fs", "fat32"); mImageLabel = state.getString("image_label", mImageLabel);
        }
        mStep = findViewById(R.id.step_label); mTitle = findViewById(R.id.title);
        mBody = findViewById(R.id.body); mNote = findViewById(R.id.note);
        mChoices = findViewById(R.id.choices); mProgress = findViewById(R.id.progress);
        mTextScroll = findViewById(R.id.text_scroll); mChoiceScroll = findViewById(R.id.choice_scroll);
        mNext = findViewById(R.id.next); mBack = findViewById(R.id.back);
        mNext.setOnClickListener(v -> skipForward()); mBack.setOnClickListener(v -> goBack());
        mNext.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = "next"; });
        mBack.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = "back"; });
        findSelected(); render(); scan();
    }

    @Override public void onResume() {
        super.onResume();
        mResumed = true; mSessionOpened = false;
        mLive = ""; mShownPage = -1; mReading = false;
        if (mDialog == null) render();
        mHandler.post(mPoll);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && mChoices != null) postPageFocus();
    }
    @Override public void onPause() {
        mResumed = false; mHandler.removeCallbacks(mPoll);
        saveEntry(); super.onPause();
    }
    @Override public Object onRetainNonConfigurationInstance() { return mWork; }
    @Override public void onDestroy() {
        if (mWork.activity == this) mWork.activity = null;
        if (!isChangingConfigurations()) { mWork.operation.incrementAndGet(); mWork.executor.shutdownNow(); }
        if (mDialog != null) mDialog.dismiss();
        super.onDestroy();
    }
    @Override public void onSaveInstanceState(Bundle state) {
        saveEntry();
        state.putBoolean("cancelling", mCancelling);
        state.putString("companion", mCompanion);
        state.putBoolean("custom_label", mCustomLabel); state.putBoolean("await_create", mAwaitCreate);
        state.putBoolean("use_after_create", mUseAfterCreate); state.putInt("create_functions", mCreateFunctions);
        state.putString("created_name", mCreatedName); state.putString("created_cid", mCreatedCid);
        state.putInt("page", mPage); state.putInt("index", mIndex); state.putInt("create_index", mCreateIndex);
        state.putBoolean("advanced", mAdvanced); state.putString("focus", mFocus);
        state.putString("image_name", mImageName); state.putString("image_size", mImageSize);
        state.putString("image_fs", mImageFs); state.putString("image_label", mImageLabel);
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() { goBack(); }
    @Override public boolean onNavigateUp() { goBack(); return true; }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) goBack();
            return true;
        }
        if (getCurrentFocus() instanceof EditText && (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER)) {
            if (event.getAction() == KeyEvent.ACTION_UP) showIme((EditText) getCurrentFocus());
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && inChoices(getCurrentFocus())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                mReading = true; mTextScroll.setFocusable(true); mTextScroll.requestFocus();
                mPageFocus.applied(mPageFocus.version());
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && getCurrentFocus() == mTextScroll) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) focusFirstChoice();
            return true;
        }
        if ((event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN)
                && (getCurrentFocus() == mNext || getCurrentFocus() == mBack) && hasActionableChoice()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) focusFirstChoice();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && inChoices(getCurrentFocus())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (mNext.isEnabled() && mNext.getVisibility() == View.VISIBLE) mNext.requestFocus(); else mBack.requestFocus();
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && (getCurrentFocus() == mNext || getCurrentFocus() == mBack)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) focusFirstChoice();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
            return super.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                    KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount()));
        }
        if (mChoices.getChildCount() == 0 && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) mTextScroll.smoothScrollBy(0,
                    (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * mTextScroll.getHeight() / 3);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int step() {
        final List<Integer> steps = UsbWizard.steps(mPreset, mFunctions);
        mIndex = Math.max(0, Math.min(mIndex, steps.size() - 1));
        return steps.get(mIndex);
    }
    private void open(int page) {
        saveEntry(); mPage = page;
        if (page != PROGRESS && page != SESSION) mCancelling = false; mFocus = ""; mLive = "";
        if (page == IMAGES || page == CREATE || page == CONNECT) { scan(); mNextScan = 0; }
        render();
    }
    private void goBack() {
        if (mDialog != null) { mDialog.dismiss(); return; }
        saveEntry();
        if (mPage == PROGRESS || mPage == SESSION || UsbDriveControl.working()) {
            mWork.operation.incrementAndGet(); mWork.pendingRequest = false; mAwaitCreate = false; mCancelling = true;
            UsbDriveControl.stop(); return;
        }
        if (mPage == CONNECT && mIndex > 0) { mIndex--; mFocus = ""; render(); }
        else if (mPage == CREATE && mCreateIndex > 0) { mCreateIndex--; mFocus = ""; render(); }
        else if (mPage == CREATE) open(IMAGES);
        else if (mPage != HUB) open(HUB);
        else finish();
    }
    private void skipForward() {
        if (!mNext.isEnabled()) return;
        if (mPage == CONNECT) {
            if (step() == UsbWizard.REVIEW) { mCompanion = ""; start(); }
            else { mIndex++; mFocus = ""; render(); }
        } else if (mPage == CREATE) {
            saveEntry();
            if (!validEntry()) return;
            if (mCreateIndex < 3) { mCreateIndex++; mFocus = ""; render(); }
            else submitCreate(true);
        } else if (mPage == PROGRESS || mPage == SESSION) goBack();

    }

    private void findSelected() {
        mSelected = null;
        for (UsbDrive.Offer offer : mOffers) if (offer.identity().equals(mSelectedKey)) mSelected = offer;
    }
    private void scan() {
        if (mWork.scanning || mWork.pendingRequest || UsbDriveControl.busy()) return;
        final Work work = mWork;
        final android.content.Context context = getApplicationContext();
        work.scanning = true;
        work.executor.execute(() -> {
            List<UsbDrive.Offer> offers;
            UsbDrive.Images images;
            String wifi = "";
            boolean debugging;
            try {
                offers = UsbDriveControl.scan(context); images = UsbDriveControl.images(); offers.addAll(images.offers);
                String iface = new UsbMode(context).getWifiOnOtgPort();
                if (iface != null) wifi = context.getString(R.string.usb_emulator_wifi_info, iface);
                debugging = UsbDeviceControl.debugging(context);
            } catch (RuntimeException e) {
                offers = new ArrayList<>(); images = new UsbDrive.Images(); debugging = work.debugging;
            }
            final List<UsbDrive.Offer> found = offers;
            final UsbDrive.Images inventory = images;
            final String notice = wifi;
            final boolean adb = debugging;
            work.handler.post(() -> {
                work.scanning = false; work.loaded = true;
                work.offers = found; work.images = inventory; work.wifi = notice; work.debugging = adb;
                if (work.activity != null) work.activity.scanned();
            });
        });
    }
    private void scanned() {
        if (!mLoaded) { mLoaded = true; mShownPage = -1; mShownStep = -1; }
        boolean debugChanged = mDebugging != mWork.debugging; mDebugging = mWork.debugging;
        if (!mWork.debugging) mCreateFunctions &= ~UsbFunctions.ADB;
        final long oldMax = VirtualDrive.maximum(mImages.free, mImages.filesystem);
        final boolean oldWritable = mImages.writable;
        mOffers = mWork.offers; mImages = mWork.images; findSelected();
        if (!mWork.debugging && (mFunctions & UsbFunctions.ADB) != 0) { mFunctions &= ~UsbFunctions.ADB; save(); }
        if (mAwaitCreate && "done".equals(UsbDriveControl.virtual("result"))) { created(); return; }
        if (mPage == CREATE) {
            if (oldMax != VirtualDrive.maximum(mImages.free, mImages.filesystem) || oldWritable != mImages.writable || mCreateIndex == 3 && debugChanged) {
                saveEntry(); render();
            }
        } else if (mPage != PROGRESS && mPage != SESSION && mDialog == null) render();
    }
    private void queryPolicy(int mask) {
        if (mWork.policyFor == mask || mWork.querying) return;
        final Work work = mWork;
        work.querying = true;
        work.executor.execute(() -> {
            UsbFunctions policy = UsbDeviceControl.policies();
            policy.queryMissing(UsbFunctions.pageMasks(mask));
            work.handler.post(() -> {
                work.querying = false; work.policy = policy; work.policyFor = mask;
                if (work.activity != null) {
                    UsbDriveActivity activity = work.activity; activity.mPolicy = policy;
                    if (activity.mPage == CONNECT || activity.mPage == CREATE && activity.mCreateIndex == 3) {
                        if (activity.getCurrentFocus() == activity.mBack && !activity.hasActionableChoice()) activity.mShownPage = -1;
                        activity.render();
                    }
                }
            });
        });
    }
    private void refresh() {
        if (mWork.pendingRequest || UsbDriveControl.working()) {
            if (mPage != PROGRESS && mPage != SESSION) open(PROGRESS);
            progress(mPage == PROGRESS); return;
        }
        if (mAwaitCreate) {
            if ("done".equals(UsbDriveControl.virtual("result"))) {
                if (mCreateWaitUntil == 0) mCreateWaitUntil = SystemClock.elapsedRealtime() + 10000;
                if (SystemClock.elapsedRealtime() >= mCreateWaitUntil) {
                    mAwaitCreate = false; mUiError = getString(R.string.usb_virtual_changed); open(IMAGES);
                } else { scan(); mBody.setText(R.string.usb_image_finding); }
                return;
            }
            mAwaitCreate = false;
        }
        if (UsbDriveControl.busy()) {
            if (mPage != SESSION) open(SESSION);
            progress(false);
            if (mResumed && "sharing".equals(UsbDriveControl.state()) && !mSessionOpened) {
                mSessionOpened = true;
                Intent launch = mCompanion.isEmpty() ? null : getPackageManager().getLaunchIntentForPackage(mCompanion);
                if (launch != null) {
                    android.os.SystemProperties.set(UsbDeviceControl.PREFIX + "companion", "1"); mCompanion = ""; startActivity(launch);
                } else startActivity(new Intent(this, ControllerSessionActivity.class));
            }
            return;
        }
        if (mPage == PROGRESS) open(IMAGES);
        if (mPage == SESSION) open(HUB);
        final boolean relevant = mPage == HUB || mPage == IMAGES || mPage == CREATE || mPage == CONNECT
                && (step() == UsbWizard.STORAGE || step() == UsbWizard.FUNCTIONS || step() == UsbWizard.REVIEW);
        if (relevant && SystemClock.elapsedRealtime() >= mNextScan) {
            UsbDriveControl.scanImages(); scan(); mNextScan = SystemClock.elapsedRealtime() + 5000;
        }
        final String signature = UsbDriveControl.state() + UsbDriveControl.virtual("result")
                + UsbDriveControl.virtual("error") + UsbDriveControl.virtual("detail") + UsbDriveControl.detail();
        if (!signature.equals(mLive)) {
            mLive = signature;
            if (mPage != CREATE && mDialog == null) render();
        }
    }
    private void progress(boolean image) {
        if (mCancelling || "stopping".equals(UsbDriveControl.state())) {
            mProgress.setIndeterminate(true); mTitle.setText(R.string.usb_emulator_stopping);
            mBody.setText(R.string.usb_emulator_stop_wait); mNext.setEnabled(false); mBack.setEnabled(false); return;
        }
        String stage = image ? UsbDriveControl.virtual("stage") : UsbDriveControl.stage();
        int percent = 0;
        try { percent = Integer.parseInt(UsbDriveControl.virtual("percent")); } catch (NumberFormatException e) { }
        boolean allocate = image && "allocate".equals(stage);
        mProgress.setIndeterminate(!allocate); mProgress.setProgress(Math.max(0, Math.min(100, percent)));
        mTitle.setText(image ? R.string.usb_virtual_working : R.string.usb_device_preparing);
        boolean physical = mSelected != null && mSelected.kind != UsbDrive.Kind.IMAGE;
        int preparing = "release_storage".equals(stage) && physical
                ? mSelected.kind == UsbDrive.Kind.EASYROMS ? R.string.usb_emulator_preparing_easyroms : R.string.usb_emulator_preparing_storage
                : R.string.usb_device_preparing;
        mBody.setText(image ? getString(virtualStage(stage)) + (allocate ? " · " + percent + "%"
                + (slowAllocation() ? "\n\n" + getString(R.string.usb_virtual_allocation) : "") : "") : getString(preparing));
        if (!image && UsbWizard.captured(stage, "1".equals(android.os.SystemProperties.get(UsbDeviceControl.PREFIX + "ready")))) {
            mNext.setVisibility(View.GONE); mBack.setVisibility(View.GONE); mNote.setText(R.string.usb_emulator_session_hint);
        }
    }
    private void operationFailed(String detail) {
        mWork.pendingRequest = false; mAwaitCreate = false; mUiError = detail;
        open(IMAGES);
    }

    private void render() {
        final int position = mPage == CONNECT ? step() : mPage == CREATE ? mCreateIndex : 0;
        final boolean same = mPage == mShownPage && position == mShownStep;
        final int textY = same ? mTextScroll.getScrollY() : 0;
        mShownPage = mPage; mShownStep = position;
        if (!same) mReading = false;
        mTextScroll.setFocusable(mReading);
        final String focus = same && getCurrentFocus() != null && getCurrentFocus().getTag() instanceof String
                ? (String) getCurrentFocus().getTag() : same ? mFocus : "";
        mFocus = focus;
        mEdit = null; mSizeChoices.clear(); mChoices.removeAllViews(); mNote.setText("");
        mNext.setVisibility(View.VISIBLE); mNext.setEnabled(true);
        mNext.setText(R.string.usb_wizard_next); mBack.setVisibility(View.VISIBLE); mBack.setEnabled(true); mBack.setText(R.string.usb_wizard_back);
        mProgress.setIndeterminate(false); mProgress.setMax(100); mProgress.setProgress(0);
        mStep.setText(R.string.usb_device_title);
        if (mPage == CONNECT) {
            int current = step();
            List<Integer> steps = UsbWizard.steps(mPreset, mFunctions);
            mStep.setText(getString(R.string.usb_wizard_step, mIndex + 1, steps.size()));
            mProgress.setProgress((mIndex + 1) * 100 / steps.size());
            if (current == UsbWizard.FUNCTIONS || current == UsbWizard.CONTROLLER || current == UsbWizard.KEYBOARD || current == UsbWizard.REVIEW)
                queryPolicy(mFunctions);
            switch (current) {
                case UsbWizard.ROLE: roles(); break;
                case UsbWizard.FUNCTIONS: functions(); break;
                case UsbWizard.CONTROLLER: controllers(); break;
                case UsbWizard.STORAGE: storage(); break;
                case UsbWizard.KEYBOARD: keyboard(); break;
                case UsbWizard.MOUSE: mouse(); break;
                default: review(); break;
            }
        } else if (mPage == IMAGES) images();
        else if (mPage == CREATE) create();
        else if (mPage == PROGRESS) {
            page(R.string.usb_virtual_working, "");
            mNext.setText(android.R.string.cancel); spinner(); progress(true);
        } else if (mPage == SESSION) {
            page(R.string.usb_device_preparing, "");
            mNext.setText(android.R.string.cancel); spinner(); progress(false);
        } else hub();
        mTextScroll.scrollTo(0, textY);
        mPageFocus.begin(same, mReading ? "read" : focus);
        postPageFocus();
    }
    private void postPageFocus() {
        final int version = mPageFocus.version();
        mChoices.post(() -> {
            if (isFinishing() || !mPageFocus.ready(version, mResumed, hasWindowFocus(), mDialog != null)) return;
            final String target = mPageFocus.target();
            View chosen = "next".equals(target) ? mNext : "back".equals(target) ? mBack
                    : mReading && "read".equals(target) ? mTextScroll : mChoices.findViewWithTag(target);
            if (!actionable(chosen)) {
                chosen = null;
                for (int i = 0; i < mChoices.getChildCount(); i++) {
                    View child = mChoices.getChildAt(i);
                    if (actionable(child)) { chosen = child; break; }
                }
            }
            if (chosen == null) chosen = actionable(mNext) ? mNext : actionable(mBack) ? mBack : null;
            if (chosen == null || chosen.requestFocus()) mPageFocus.applied(version);
        });
    }
    private boolean actionable(View view) {
        return view != null && view.isShown() && view.isEnabled() && view.isFocusable();
    }

    private void page(int title, String body) { mTitle.setText(title); mBody.setText(body); }
    private Button row(String key, String title, String summary, boolean enabled, Runnable action) {
        Button button = new Button(this, null, 0, R.style.Andr36oidSetupButton_Secondary);
        button.setId(View.generateViewId());
        button.setBackground(getDrawable(R.drawable.usb_choice_background));
        button.setForeground(getDrawable(R.drawable.andr36oid_setup_focus));
        button.setTextColor(getColorStateList(R.color.usb_choice_text));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setTag(key); button.setText(summary.isEmpty() ? title : title + "\n" + summary);
        button.setAllCaps(false); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setEnabled(enabled); button.setMinHeight(dp(64));
        button.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = key; });
        button.setOnClickListener(v -> action.run());
        mChoices.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }
    private RadioButton choice(String key, String title, String summary, boolean checked, boolean enabled, Runnable action) {
        RadioButton choice = new RadioButton(this);
        choice.setId(View.generateViewId()); choice.setTag(key);
        choice.setText(summary.isEmpty() ? title : title + "\n" + summary);
        choice.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        choice.setTextColor(getColorStateList(R.color.usb_choice_text));
        choice.setBackground(getDrawable(R.drawable.usb_choice_background));
        choice.setForeground(getDrawable(R.drawable.andr36oid_setup_focus));
        choice.setPadding(dp(12), dp(10), dp(12), dp(10)); choice.setMinHeight(dp(64));
        choice.setChecked(checked); choice.setEnabled(enabled);
        choice.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = key; });
        choice.setOnClickListener(v -> action.run());
        mChoices.addView(choice, new LinearLayout.LayoutParams(-1, -2));
        return choice;
    }
    private void toggle(String key, int title, String summary, boolean checked, boolean enabled, Runnable action) {
        CheckBox box = new CheckBox(this); box.setTag(key); box.setText(getString(title) + "\n" + summary);
        box.setChecked(checked); box.setEnabled(enabled); box.setMinHeight(dp(64));
        box.setId(View.generateViewId()); box.setBackground(getDrawable(R.drawable.usb_choice_background));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setForeground(getDrawable(R.drawable.andr36oid_setup_focus));
        box.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = key; });
        box.setOnClickListener(v -> action.run());
        mChoices.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void hub() {
        page(R.string.usb_device_title, getString(R.string.usb_device_about) + "\n\n" + getString(R.string.usb_drive_cable) + (mWork.wifi.isEmpty() ? "" : "\n\n" + mWork.wifi));
        mNext.setVisibility(View.GONE);
        row("connect", getString(R.string.usb_hub_connect), getString(R.string.usb_wizard_connect_summary), mWork.loaded,
                () -> { mIndex = 0; open(CONNECT); });
        row("images", getString(R.string.usb_hub_images), getString(R.string.usb_virtual_summary), mWork.loaded, () -> open(IMAGES));
        liveResult();
    }
    private void liveResult() {
        String status = "error".equals(UsbDriveControl.state()) ? getString(R.string.usb_device_failed)
                + "\n" + UsbDriveControl.error() + "\n" + UsbDriveControl.detail() : "";
        if (!status.isEmpty()) { mNote.setText(status); return; }
        String result = UsbDriveControl.virtual("result");
        String detail = "";
        if ("error".equals(result)) detail = virtualError(UsbDriveControl.virtual("error")) + "\n" + UsbDriveControl.virtual("detail");
        else if ("done".equals(result)) detail = getString(R.string.usb_virtual_done);
        else if ("cancelled".equals(result)) detail = getString(R.string.usb_virtual_cancelled);
        if (!detail.isEmpty()) status += (status.isEmpty() ? "" : "\n\n") + detail;
        if (!mUiError.isEmpty()) status = mUiError;
        if (!status.isEmpty()) mNote.setText(status);
    }
    private void roles() {
        page(R.string.usb_device_preset, getString(R.string.usb_wizard_role_summary));
        final int[] titles = {R.string.usb_device_pc, R.string.usb_device_game, R.string.usb_drive_title,
                R.string.usb_device_custom, R.string.usb_device_wired, R.string.usb_device_music,
                R.string.usb_jiggle_preset, R.string.usb_macro_title, R.string.usb_link_title};
        final int[] descriptions = {R.string.usb_wizard_pc_summary, R.string.controller_xinput_summary, R.string.usb_drive_intro,
                R.string.usb_wizard_custom_summary, R.string.usb_device_network_summary, R.string.usb_device_midi_summary,
                R.string.usb_jiggle_summary, R.string.usb_macro_summary, R.string.usb_wizard_link_summary};
        for (int i = 0; i < UsbWizard.ROLES.length; i++) {
            final String role = UsbWizard.ROLES[i];
            choice(role, getString(titles[i]), getString(descriptions[i]), role.equals(mPreset), true, () -> {
                chooseRole(role); skipForward();
            });
        }
    }
    private void chooseRole(String role) {
        if ("custom".equals(mPreset)) saveCustom();
        mPreset = role;
        if ("custom".equals(role)) {
            mFunctions = mPrefs.getInt("usb_custom_functions", UsbFunctions.XINPUT);
            mMacro = mPrefs.getBoolean("usb_custom_macro", false);
            mSelectedKey = mPrefs.getString("usb_custom_storage", "");
            mCdrom = mPrefs.getBoolean("usb_custom_cdrom", true); mRo = mPrefs.getBoolean("usb_custom_ro", true);
            mPrefs.edit().putBoolean("usb_jiggle", mPrefs.getBoolean("usb_custom_jiggle", false)).apply();
        } else {
            mFunctions = UsbWizard.preset(role, UsbDeviceControl.debugging(this));
            mMacro = "macro".equals(role);
            mPrefs.edit().putBoolean("usb_jiggle", "awake".equals(role)).apply();
            if ((mFunctions & UsbFunctions.STORAGE) != 0) {
                mSelectedKey = "";
                for (UsbDrive.Offer offer : mOffers) if (offer.enabled()
                        && (!"pc".equals(role) || offer.kind == UsbDrive.Kind.IMAGE && offer.cdromPossible())) {
                    mSelectedKey = offer.identity(); break;
                }
                if ("pc".equals(role)) { mCdrom = true; mRo = true; }
            }
        }
        save(); findSelected();
    }
    private void saveCustom() {
        mPrefs.edit().putInt("usb_custom_functions", mFunctions).putString("usb_custom_storage", mSelectedKey)
                .putBoolean("usb_custom_macro", mMacro).putBoolean("usb_custom_jiggle", mPrefs.getBoolean("usb_jiggle", false))
                .putBoolean("usb_custom_cdrom", mCdrom).putBoolean("usb_custom_ro", mRo).apply();
    }
    private void save() {
        mPrefs.edit().putString("usb_preset", mPreset).putInt("usb_functions", mFunctions)
                .putString("usb_storage", mSelectedKey).putBoolean("usb_cdrom", mCdrom)
                .putBoolean("usb_ro", mRo).putBoolean("usb_macro", mMacro).apply();
        if ("custom".equals(mPreset)) saveCustom();
    }
    private void changed() { save(); render(); }
    private static int controllerTitle(int bit) {
        switch (bit) {
            case UsbFunctions.XINPUT: return R.string.controller_xinput;
            case UsbFunctions.GAMEPAD: return R.string.controller_gamepad;
            case UsbFunctions.SWITCH: return R.string.usb_device_switch;
            case UsbFunctions.PS4: return R.string.usb_ds4_title;
            case UsbFunctions.PS3: return R.string.usb_device_ps3;
            case UsbFunctions.GAMECUBE: return R.string.usb_device_gamecube;
            case UsbFunctions.XBOX: return R.string.usb_device_xbox;
            default: return R.string.usb_device_no_controller;
        }
    }
    private String functionName(int bit) {
        for (int controller : UsbFunctions.CONTROLLER_BITS) if (controller != 0 && bit == controller) return getString(controllerTitle(bit));
        final int[] bits = UsbFunctions.BITS;
        final int[] titles = functionTitles();
        for (int i = 0; i < bits.length; i++) if (bit == bits[i]) return getString(titles[i]);
        return getString(bit == UsbFunctions.SOUNDBOARD ? R.string.usb_sound_title : R.string.flipper_title);
    }
    private int[] functionTitles() {
        return new int[] {R.string.usb_device_storage, R.string.usb_device_keyboard, R.string.usb_device_mouse,
                R.string.usb_device_media, R.string.usb_device_midi, R.string.usb_device_network,
                R.string.usb_device_adb, R.string.usb_device_sound, R.string.usb_device_serial, R.string.usb_device_rndis};
    }
    private String reason(String reason, int mask) {
        if ("routing".equals(reason) || "standalone".equals(reason) || "networks".equals(reason) || "controllers".equals(reason) || "sound".equals(reason)) {
            int[] pair = UsbWizard.blockers(reason, mask);
            return getString("sound".equals(reason) ? R.string.usb_wizard_requires : R.string.usb_wizard_conflict,
                    functionName(pair[0]), functionName(pair[1]));
        }
        switch (reason) {
            case "endpoints": return getString(R.string.usb_device_endpoint_limit);
            case "empty": return getString(R.string.usb_device_empty);
            case "storage": return getString(R.string.usb_device_pick_storage);
            case "debugging": return getString(R.string.usb_device_debugging_off);
            case "checking": return getString(R.string.usb_emulator_checking);
            default: return getString(mWork.querying ? R.string.usb_emulator_checking : R.string.usb_device_policy_unavailable);
        }
    }
    private void functions() {
        page(R.string.usb_wizard_functions, getString(R.string.usb_wizard_custom_summary));
        row("controller", getString(R.string.usb_device_controller_type),
                getString(controllerTitle(mFunctions & UsbFunctions.CONTROLLERS)), true, () -> {
                    mIndex = UsbWizard.steps(mPreset, mFunctions).indexOf(UsbWizard.CONTROLLER); mFocus = ""; render();
                });
        final int[] bits = UsbFunctions.BITS;
        final int[] titles = functionTitles();
        final int[] summaries = {R.string.usb_drive_intro, R.string.controller_keyboard_summary, R.string.usb_device_mouse_summary,
                R.string.usb_device_media_summary, R.string.usb_device_midi_summary, R.string.usb_device_network_summary,
                R.string.usb_device_adb_summary, R.string.usb_device_sound_summary, R.string.usb_device_serial_summary, R.string.usb_device_rndis_summary};
        for (int i = 0; i < bits.length; i++) {
            final int bit = bits[i];
            if (bit == UsbFunctions.SOUND) {
                row("advanced", getString(R.string.usb_device_advanced), getString(mAdvanced ? R.string.usb_device_collapse
                        : R.string.usb_device_advanced_summary), true, () -> { mAdvanced = !mAdvanced; render(); });
                if (mAdvanced) toggle("webusb", R.string.usb_webusb_title, getString(R.string.usb_webusb_summary),
                        mPrefs.getBoolean("usb_webusb", true), true, () -> {
                            mPrefs.edit().putBoolean("usb_webusb", !mPrefs.getBoolean("usb_webusb", true)).apply(); render();
                        });
            }
            if ((bit == UsbFunctions.SOUND || bit == UsbFunctions.SERIAL || bit == UsbFunctions.RNDIS) && !mAdvanced) continue;
            final boolean checked = (mFunctions & bit) != 0;
            String why = checked ? "ok" : mPolicy.reason(mFunctions | bit);
            if (bit == UsbFunctions.STORAGE && !checked && "ok".equals(why)) {
                boolean available = false;
                for (UsbDrive.Offer offer : mOffers) available |= offer.enabled();
                if (!available) why = "storage";
            }
            if (bit == UsbFunctions.ADB && !UsbDeviceControl.debugging(this)) why = "debugging";
            toggle("function:" + bit, titles[i], "ok".equals(why) ? getString(summaries[i]) : reason(why, mFunctions | bit),
                    checked, checked || "ok".equals(why), () -> {
                        mFunctions ^= bit;
                        if ((mFunctions & UsbFunctions.SOUND) == 0) mFunctions &= ~UsbFunctions.SOUNDBOARD;
                        if ((mFunctions & (UsbFunctions.KEYBOARD | UsbFunctions.MEDIA)) != (UsbFunctions.KEYBOARD | UsbFunctions.MEDIA)) mMacro = false;
                        changed();
                    });
            if (bit == UsbFunctions.SOUND && checked) sound();
        }
    }
    private void sound() {
        final boolean board = (mFunctions & UsbFunctions.SOUNDBOARD) != 0;
        final String why = board ? "ok" : mPolicy.reason(mFunctions | UsbFunctions.SOUNDBOARD);
        toggle("soundboard", R.string.usb_sound_title, "ok".equals(why) ? getString(R.string.usb_sound_summary)
                : reason(why, mFunctions | UsbFunctions.SOUNDBOARD), board, board || "ok".equals(why),
                () -> { mFunctions ^= UsbFunctions.SOUNDBOARD; changed(); });
        if (board) toggle("mic", R.string.usb_sound_mic, "", mPrefs.getBoolean("usb_sound_live", false), true, () -> {
            boolean enable = !mPrefs.getBoolean("usb_sound_live", false);
            if (enable && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 1); return;
            }
            mPrefs.edit().putBoolean("usb_sound_live", enable).apply(); render();
        });
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            mPrefs.edit().putBoolean("usb_sound_live", true).apply();
        render();
    }
    private void controllers() {
        page(R.string.usb_device_controller_type, getString(R.string.usb_wizard_controller_summary));
        final int[] summaries = {0, R.string.controller_xinput_summary, R.string.controller_gamepad_summary,
                R.string.usb_device_switch_summary, R.string.usb_device_ps3_summary, R.string.usb_device_gamecube_summary,
                R.string.usb_device_xbox_summary, R.string.usb_ds4_summary};
        for (int i = 0; i < UsbFunctions.CONTROLLER_BITS.length; i++) {
            final int bit = UsbFunctions.CONTROLLER_BITS[i];
            final int mask = (mFunctions & ~UsbFunctions.CONTROLLERS) | bit;
            final String why = mPolicy.reason(mask);
            final boolean enabled = mask == 0 || "ok".equals(why) || (mFunctions & UsbFunctions.CONTROLLERS) == bit;
            choice("controller:" + bit, getString(controllerTitle(bit)), enabled ? i == 0 ? "" : getString(summaries[i])
                    : reason(why, mask), (mFunctions & UsbFunctions.CONTROLLERS) == bit, enabled,
                    () -> { mFunctions = mask; changed(); });
        }
    }
    private void storage() {
        page(R.string.usb_device_target, getString(R.string.usb_drive_intro) + "\n\n" + getString(R.string.usb_drive_image_hint));
        for (UsbDrive.Offer offer : mOffers) choice(offer.identity(), name(offer), offerDetail(offer),
                offer.identity().equals(mSelectedKey), offer.enabled(), () -> {
                    mSelected = offer; mSelectedKey = offer.identity();
                    mCdrom = offer.defaultCdrom() && offer.cdromPossible();
                    if (offer.kind == UsbDrive.Kind.IMAGE && !offer.type.isEmpty()) mRo = false;
                    changed();
                });
        if (mSelected != null && mSelected.kind == UsbDrive.Kind.IMAGE) {
            choice("cdrom", getString(R.string.usb_drive_image_cd), getString(mSelected.cdromPossible()
                    ? R.string.usb_drive_image_cd_ro : mSelected.floppy() ? R.string.usb_floppy_summary : R.string.usb_drive_image_cd_size),
                    mCdrom, mSelected.cdromPossible(), () -> { mCdrom = true; changed(); });
            choice("usb-stick", getString(R.string.usb_drive_image_usb), getString(mSelected.floppy()
                    ? R.string.usb_floppy_summary : R.string.usb_drive_image_ro_hint), !mCdrom, true,
                    () -> { mCdrom = false; changed(); });
        }
        row("new-image", getString(R.string.usb_virtual_create), getString(R.string.usb_virtual_summary),
                mImages.mounted && mImages.writable && VirtualDrive.maximum(mImages.free, mImages.filesystem) >= VirtualDrive.GB,
                () -> newImage(true));
        boolean fixed = mSelected == null || mSelected.forcedRo || mSelected.kind == UsbDrive.Kind.IMAGE && mCdrom;
        toggle("ro", R.string.usb_drive_ro, readOnlyReason(), mRo || fixed, !fixed, () -> { mRo = !mRo; changed(); });
        mNext.setEnabled(mSelected != null && mSelected.enabled() && (!mCdrom || mSelected.kind != UsbDrive.Kind.IMAGE || mSelected.cdromPossible()));
    }
    private void keyboard() {
        page(R.string.usb_device_keyboard, getString(R.string.controller_keyboard_summary));
        final int[] layouts = {R.string.keyboard_layout_us, R.string.keyboard_layout_de, R.string.keyboard_layout_fr};
        final String selected = mPrefs.getString("keyboard_layout", KeyboardModel.defaultLayout(java.util.Locale.getDefault()));
        for (int i = 0; i < layouts.length; i++) {
            final String layout = KeyboardModel.LAYOUTS[i];
            choice(layout, getString(layouts[i]), "", layout.equals(selected), true, () -> {
                mPrefs.edit().putString("keyboard_layout", layout).apply(); render();
            });
        }
        final String why = mMacro ? "ok" : mPolicy.reason(mFunctions | UsbFunctions.MEDIA);
        toggle("macro", R.string.usb_macro_title, "ok".equals(why) ? getString(R.string.usb_macro_summary)
                : reason(why, mFunctions | UsbFunctions.MEDIA), mMacro, mMacro || "ok".equals(why), () -> {
                    mMacro = !mMacro; if (mMacro) mFunctions |= UsbFunctions.MEDIA; changed();
                });
        MacroProfiles profiles = MacroSettings.load(this);
        row("profiles", getString(R.string.usb_macro_profiles), profiles.profiles.get(MacroSettings.selected(this, profiles)).name,
                true, () -> MacroSettings.show(this, this::render));
    }
    private void mouse() {
        page(R.string.usb_device_mouse, getString(R.string.usb_device_mouse_summary));
        toggle("jiggle", R.string.usb_jiggle_title, getString(R.string.usb_jiggle_summary), mPrefs.getBoolean("usb_jiggle", false), true, () -> {
            mPrefs.edit().putBoolean("usb_jiggle", !mPrefs.getBoolean("usb_jiggle", false)).apply(); changed();
        });
        String[] titles = getString(R.string.usb_jiggle_times).split("\\|");
        String[] seconds = {"30", "60", "120", "300"};
        for (int i = 0; i < seconds.length; i++) {
            final String value = seconds[i];
            choice("interval:" + value, titles[i], "", value.equals(mPrefs.getString("usb_jiggle_interval", "60")), true, () -> {
                mPrefs.edit().putString("usb_jiggle_interval", value).apply(); render();
            });
        }
    }
    private void review() {
        StringBuilder names = new StringBuilder();
        for (int bit = 1; bit <= UsbFunctions.FLIPPER; bit <<= 1) if ((mFunctions & bit) != 0) {
            if (names.length() > 0) names.append(" + ");
            names.append(functionName(bit));
        }
        String body = getString(R.string.usb_wizard_review_summary, names.toString());
        if ((mFunctions & UsbFunctions.STORAGE) != 0 && mSelected != null)
            body += "\n\n" + name(mSelected) + " · " + getString(mSelected.kind == UsbDrive.Kind.IMAGE && mCdrom
                    ? R.string.usb_drive_image_cd : R.string.usb_drive_image_usb) + "\n"
                    + getString(mRo || mSelected.forcedRo || mSelected.kind == UsbDrive.Kind.IMAGE && mCdrom
                    ? R.string.usb_drive_ro : R.string.usb_wizard_writable);
        if ((mFunctions & UsbFunctions.KEYBOARD) != 0) {
            String layout = mPrefs.getString("keyboard_layout", KeyboardModel.defaultLayout(java.util.Locale.getDefault()));
            int index = "de".equals(layout) ? 1 : "fr".equals(layout) ? 2 : 0;
            body += "\n\n" + getString(R.string.keyboard_layout) + ": " + getString(new int[] {
                    R.string.keyboard_layout_us, R.string.keyboard_layout_de, R.string.keyboard_layout_fr}[index]);
            if (mMacro) {
                MacroProfiles profiles = MacroSettings.load(this);
                body += "\n" + getString(R.string.usb_macro_title) + ": " + profiles.profiles.get(MacroSettings.selected(this, profiles)).name;
            }
        }
        if ((mFunctions & UsbFunctions.MOUSE) != 0 && mPrefs.getBoolean("usb_jiggle", false)) {
            int interval = java.util.Arrays.asList("30", "60", "120", "300").indexOf(mPrefs.getString("usb_jiggle_interval", "60"));
            body += "\n\n" + getString(R.string.usb_jiggle_title) + " · " + getString(R.string.usb_jiggle_times).split("\\|")[
                    interval < 0 ? 1 : interval];
        }
        if ((mFunctions & UsbFunctions.CONTROLLERS) != 0) body += "\n\n" + getString(R.string.usb_emulator_controller_hint);
        int input = mFunctions & (UsbFunctions.KEYBOARD | UsbFunctions.MOUSE);
        if (input != 0) body += "\n\n" + getString(input == (UsbFunctions.KEYBOARD | UsbFunctions.MOUSE)
                ? R.string.usb_emulator_input_hint : input == UsbFunctions.KEYBOARD ? R.string.usb_emulator_keyboard_hint : R.string.usb_emulator_mouse_hint);
        if ((mFunctions & UsbFunctions.STORAGE) != 0) body += "\n\n" + getString(UsbWizard.bootMenu(mFunctions, mSelected, mCdrom)
                ? R.string.usb_device_boot_hint : mSelected != null && mSelected.kind == UsbDrive.Kind.IMAGE && mCdrom
                ? R.string.usb_emulator_cd_hint : R.string.usb_emulator_drive_hint);
        body += "\n\n" + getString(R.string.usb_drive_cable);
        if ((mFunctions & (UsbFunctions.NETWORK | UsbFunctions.RNDIS)) != 0)
            body += "\n\n" + getString(R.string.usb_link_guide) + "\n\n" + getString(R.string.usb_stream_guide) + "\n\n" + getString(R.string.usb_device_link_cable);
        final boolean companion = (mFunctions & (UsbFunctions.NETWORK | UsbFunctions.RNDIS)) != 0
                && (mFunctions & ~(UsbFunctions.NETWORK | UsbFunctions.RNDIS | UsbFunctions.ADB)) == 0;
        if (companion) {
            row("retroarch", getString(R.string.usb_link_open), getString(R.string.usb_emulator_start_app),
                    "ok".equals(startReason()), () -> startCompanion("com.retroarch"));
            row("moonlight", getString(R.string.usb_stream_open), getString(R.string.usb_emulator_start_app),
                    "ok".equals(startReason()), () -> startCompanion("com.limelight"));
        }
        page(R.string.usb_wizard_review, body);
        mNext.setText(R.string.usb_device_start);
        String why = startReason();
        mNext.setEnabled("ok".equals(why));
        if (!"ok".equals(why)) mNote.setText(reason(why, mFunctions));
    }
    private String startReason() {
        String why = mWork.policyFor == mFunctions ? mPolicy.reason(mFunctions) : "checking";
        if ("ok".equals(why) && (mFunctions & UsbFunctions.STORAGE) != 0
                && (mSelected == null || !mSelected.enabled())) why = "storage";
        if ((mFunctions & UsbFunctions.ADB) != 0 && !UsbDeviceControl.debugging(this)) why = "debugging";
        return why;
    }
    private void startCompanion(String app) {
        if (getPackageManager().getLaunchIntentForPackage(app) == null) {
            details(getString(R.string.usb_device_title), getString(R.string.usb_link_missing)); return;
        }
        mCompanion = app; start();
    }
    private void start() {
        if (!"ok".equals(startReason())) { render(); return; }
        if ((mFunctions & UsbFunctions.STORAGE) != 0) ask(mSelected, mSelected.kind == UsbDrive.Kind.IMAGE && mCdrom);
        else beginSession(null, true, false);
    }
    private void beginSession(UsbDrive.Offer offer, boolean ro, boolean cdrom) {
        if (UsbDriveControl.busy() || mWork.pendingRequest) return;
        final Work work = mWork;
        final int operation = work.operation.incrementAndGet(), mask = mFunctions;
        final android.content.Context context = getApplicationContext();
        work.pendingRequest = true; mCancelling = false; mSessionOpened = false; mUiError = ""; open(SESSION);
        work.executor.execute(() -> {
            try {
                UsbMode usb = new UsbMode(context);
                if (!usb.isSupported() || usb.chargingBlocksDevice()) throw new IllegalStateException(context.getString(R.string.charging_refused));
                UsbDrive.Offer fresh = offer;
                if (offer != null) {
                    fresh = null;
                    List<UsbDrive.Offer> offers = UsbDriveControl.scan(context); offers.addAll(UsbDriveControl.images().offers);
                    for (UsbDrive.Offer candidate : offers) if (candidate.enabled() && candidate.identity().equals(offer.identity())) fresh = candidate;
                    if (fresh == null) throw new IllegalStateException(context.getString(R.string.usb_virtual_changed));
                }
                if (work.operation.get() != operation) return;
                usb.setConfirmedThisBoot();
                boolean started = UsbDeviceControl.request(context, mask, fresh, ro, cdrom, () -> work.operation.get() == operation);
                work.handler.post(() -> {
                    if (work.operation.get() != operation) return;
                    work.pendingRequest = false;
                    if (work.activity != null) {
                        if (started) work.activity.refresh();
                        else { work.activity.mUiError = context.getString(R.string.usb_device_failed); work.activity.open(HUB); }
                    }
                });
            } catch (RuntimeException e) {
                work.handler.post(() -> {
                    if (work.operation.get() != operation) return;
                    work.pendingRequest = false;
                    if (work.activity != null) {
                        work.activity.mUiError = e.getMessage() == null ? context.getString(R.string.usb_device_failed) : e.getMessage(); work.activity.open(HUB);
                    }
                });
            }
        });
    }
    private void images() {
        page(R.string.usb_hub_images, getString(R.string.usb_drive_image_hint));
        mNext.setVisibility(View.GONE);
        row("create", getString(R.string.usb_virtual_create), getString(mImages.mounted && mImages.writable
                ? R.string.usb_virtual_summary : R.string.usb_virtual_unavailable), mImages.mounted && mImages.writable
                && VirtualDrive.maximum(mImages.free, mImages.filesystem) >= VirtualDrive.GB, () -> {
                    newImage(false);
                });
        for (UsbDrive.Offer offer : mImages.offers)
            row(offer.identity(), name(offer), offerDetail(offer), true, () -> imageActions(offer));
        if (mImages.offers.isEmpty()) mNote.setText(R.string.usb_drive_image_empty);
        liveResult();
    }
    private String offerDetail(UsbDrive.Offer offer) {
        return Formatter.formatFileSize(this, offer.bytes) + " · " + (offer.type.isEmpty()
                ? getString(R.string.usb_wizard_unknown_filesystem) : offer.type)
                + " · " + explanation(offer);
    }
    private void imageActions(UsbDrive.Offer offer) {
        if (mDialog != null || UsbDriveControl.busy()) return;
        showDialog(new AlertDialog.Builder(this).setTitle(name(offer)).setItems(new CharSequence[] {
                getString(R.string.usb_virtual_use), getString(R.string.usb_virtual_repair),
                getString(R.string.usb_virtual_delete), getString(R.string.usb_virtual_rename)}, (d, which) -> mHandler.post(() -> {
                    if (which == 0) {
                        mFunctions |= UsbFunctions.STORAGE; mPreset = "custom";
                        mSelectedKey = offer.identity(); mSelected = offer;
                        mCdrom = offer.defaultCdrom() && offer.cdromPossible();
                        if (!offer.type.isEmpty()) mRo = false;
                        save();
                        mIndex = UsbWizard.steps(mPreset, mFunctions).indexOf(UsbWizard.STORAGE); open(CONNECT);
                    } else if (offer.forcedRo || !mImages.mounted || !mImages.writable) {
                        details(name(offer), getString(R.string.usb_virtual_unavailable));
                    } else if (which == 3) rename(offer);
                    else confirmImageAction(offer, which == 1 ? "repair" : "delete");
                })).setNegativeButton(android.R.string.cancel, null).create());
    }
    private void confirmImageAction(UsbDrive.Offer offer, String action) {
        boolean repair = "repair".equals(action);
        showDialog(new AlertDialog.Builder(this).setTitle(name(offer))
                .setMessage(repair ? R.string.usb_virtual_repair_confirm : R.string.usb_virtual_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(repair ? R.string.usb_virtual_repair : R.string.usb_virtual_delete,
                        (d, which) -> {
                            final android.content.Context context = getApplicationContext();
                            runImageOperation(() -> UsbDriveControl.maintain(context, offer, action));
                        }).create());
        mDialog.getButton(DialogInterface.BUTTON_NEGATIVE).requestFocus();
    }
    private void rename(UsbDrive.Offer offer) {
        EditText field = new EditText(this); field.setSingleLine(true);
        field.setText(offer.node.substring(0, offer.node.lastIndexOf('.'))); UsbTextInput.bind(field);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.usb_virtual_rename).setView(field)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = field.getText().toString();
            if (!VirtualDrive.name(value)) { field.setError(getString(R.string.usb_virtual_bad_name)); return; }
            UsbTextInput.hide(field); dialog.dismiss();
            final android.content.Context context = getApplicationContext();
            runImageOperation(() -> UsbDriveControl.rename(context, offer, value));
        }));
        showDialog(dialog);
    }
    private void newImage(boolean fromStorage) {
        List<String> names = new ArrayList<>();
        for (UsbDrive.Offer offer : mImages.offers) names.add(offer.node);
        mImageName = VirtualDrive.nextName(names); mImageFs = "fat32"; mImageSize = "1";
        mCustomLabel = false; mImageLabel = VirtualDrive.defaultLabel(mImageName, mImageFs);
        mCreateFunctions = fromStorage ? mFunctions : UsbWizard.preset("drive", mWork.debugging);
        mCreateIndex = 0; mUiError = ""; mCompanion = ""; open(CREATE);
    }
    private void create() {
        mStep.setText(getString(R.string.usb_wizard_step, mCreateIndex + 1, 4));
        mProgress.setProgress((mCreateIndex + 1) * 25);
        mNext.setEnabled(mImages.mounted && mImages.writable && VirtualDrive.maximum(mImages.free, mImages.filesystem) >= VirtualDrive.GB);
        if (mCreateIndex == 0) {
            page(R.string.usb_virtual_name, getString(R.string.usb_image_name_hint));
            editEntry(mImageName, false);
        } else if (mCreateIndex == 1) {
            page(R.string.usb_virtual_size, getString(R.string.usb_virtual_available,
                    VirtualDrive.maximum(mImages.free, mImages.filesystem) / VirtualDrive.GB));
            editEntry(mImageSize, true);
            for (String size : VirtualDrive.presets(mImages.free, mImages.filesystem)) {
                RadioButton option = choice("size:" + size, getString(R.string.usb_wizard_gb, size), "",
                        VirtualDrive.presetSelected(mImageSize, size), true, () -> {
                            mImageSize = size; mEdit.setText(size); updateSizeChoices();
                        });
                mSizeChoices.add(option);
            }
        } else if (mCreateIndex == 2) {
            page(R.string.usb_wizard_filesystem, getString(R.string.usb_virtual_summary));
            String[] names = getString(R.string.usb_virtual_filesystems).split("\\|");
            int[] summaries = {R.string.usb_virtual_fat32, R.string.usb_virtual_exfat, R.string.usb_virtual_ntfs, R.string.usb_virtual_ext4};
            for (int i = 0; i < names.length; i++) {
                final String fs = VirtualDrive.FILESYSTEMS[i];
                choice(fs, names[i], getString(summaries[i]), fs.equals(mImageFs), true, () -> {
                    mImageFs = fs;
                    if (!mCustomLabel) mImageLabel = VirtualDrive.defaultLabel(mImageName, fs);
                    render();
                });
            }
        } else {
            if (!mCustomLabel) mImageLabel = VirtualDrive.defaultLabel(mImageName, mImageFs);
            queryPolicy(mCreateFunctions);
            page(R.string.usb_wizard_review, mImageName + ".img · " + getString(R.string.usb_wizard_gb, mImageSize)
                    + " · " + mImageFs + "\n\n" + getString(R.string.usb_virtual_label) + ": " + mImageLabel
                    + (slowAllocation() ? "\n\n" + getString(R.string.usb_virtual_allocation) : ""));
            row("label", getString(R.string.usb_image_change_label), mImageLabel, true, () -> editLabel());
            row("create-only", getString(R.string.usb_virtual_create), getString(R.string.usb_image_create_only), mNext.isEnabled(), () -> submitCreate(false));
            mNext.setText(R.string.usb_image_create_use);
            String why = mWork.policyFor == mCreateFunctions ? mPolicy.reason(mCreateFunctions) : "checking";
            mNext.setEnabled(mNext.isEnabled() && "ok".equals(why));
            if (!"ok".equals(why)) mNote.setText(reason(why, mCreateFunctions));
        }
        if (!mImages.mounted || !mImages.writable) mNote.setText(R.string.usb_virtual_unavailable);
        else if (VirtualDrive.maximum(mImages.free, mImages.filesystem) < VirtualDrive.GB) mNote.setText(R.string.usb_virtual_bad_size);
    }
    private void editLabel() {
        EditText field = new EditText(this); field.setSingleLine(true); field.setText(mImageLabel); UsbTextInput.bind(field);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.usb_virtual_label).setView(field)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = field.getText().toString();
                if (!VirtualDrive.label(value, mImageFs)) { field.setError(getString(R.string.usb_virtual_bad_label)); return; }
                mImageLabel = value; mCustomLabel = true; UsbTextInput.hide(field); dialog.dismiss(); render();
            });
            field.requestFocus();
        });
        showDialog(dialog);
    }
    private void editEntry(String value, boolean numeric) {
        mEdit = new EditText(this); mEdit.setSingleLine(true); mEdit.setText(value);
        mEdit.setId(View.generateViewId()); mEdit.setTag("entry");
        mEdit.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        mEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        UsbTextInput.bind(mEdit);
        final EditText editor = mEdit;
        mEdit.setOnEditorActionListener((v, action, event) -> {
            UsbTextInput.hide(editor);
            if (mEdit == editor && mPage == CREATE) { saveEntry(); mNext.requestFocus(); }
            return true;
        });
        mEdit.setOnFocusChangeListener((v, focused) -> {
            if (mEdit != editor) return;
            if (focused) mFocus = "entry";
            else if (numeric) saveEntry();
        });
        mEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (mEdit != editor || mPage != CREATE || mCreateIndex != (numeric ? 1 : 0)) return;
                if (numeric) { mImageSize = value.toString(); updateSizeChoices(); }
                else { mImageName = value.toString(); if (!mCustomLabel) mImageLabel = VirtualDrive.defaultLabel(mImageName, mImageFs); }
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        mChoices.addView(mEdit, new LinearLayout.LayoutParams(-1, -2));
    }
    private void updateSizeChoices() {
        for (RadioButton option : mSizeChoices) option.setChecked(VirtualDrive.presetSelected(mImageSize,
                option.getTag().toString().substring("size:".length())));
    }
    private void saveEntry() {
        if (mPage != CREATE || mEdit == null) return;
        if (mCreateIndex == 0) mImageName = mEdit.getText().toString();
        else if (mCreateIndex == 1) {
            String value = mEdit.getText().toString();
            mImageSize = VirtualDrive.clampSize(value, mImages.free, mImages.filesystem);
            if (!value.equals(mImageSize)) {
                mEdit.setText(mImageSize); mNote.setText(getString(R.string.usb_image_size_clamped, mImageSize));
            }
            updateSizeChoices();
        }
        if (!mCustomLabel) mImageLabel = VirtualDrive.defaultLabel(mImageName, mImageFs);
    }
    private boolean validEntry() {
        int error = 0;
        if (!VirtualDrive.name(mImageName)) error = R.string.usb_virtual_bad_name;
        else if (mCreateIndex >= 1 && (VirtualDrive.size(mImageSize) == 0
                || VirtualDrive.size(mImageSize) > VirtualDrive.maximum(mImages.free, mImages.filesystem))) error = R.string.usb_virtual_bad_size;
        else if (mCreateIndex >= 3 && !VirtualDrive.label(mImageLabel, mImageFs)) error = R.string.usb_virtual_bad_label;
        if (error == 0) return true;
        mNote.setText(error);
        if (mEdit != null) mEdit.setError(getString(error));
        return false;
    }
    private void submitCreate(boolean use) {
        saveEntry();
        if (!validEntry() || !mImages.mounted || !mImages.writable) return;
        if (use && (mWork.policyFor != mCreateFunctions || !"ok".equals(mPolicy.reason(mCreateFunctions)))) return;
        final android.content.Context context = getApplicationContext();
        final UsbDrive.Images images = mImages;
        final String name = mImageName, size = mImageSize, fs = mImageFs, label = mImageLabel;
        mCreatedName = name + ".img"; mCreatedCid = images.cid;
        mUseAfterCreate = use; mAwaitCreate = true; mCreateWaitUntil = 0;
        runImageOperation(() -> UsbDriveControl.create(context, images, name, size, fs, label));
    }
    private void runImageOperation(Runnable request) {
        if (UsbDriveControl.busy() || mWork.pendingRequest) return;
        final Work work = mWork; final int operation = work.operation.incrementAndGet();
        work.pendingRequest = true; mCancelling = false; mUiError = ""; open(PROGRESS);
        work.executor.execute(() -> {
            try {
                if (work.operation.get() != operation) return;
                request.run();
                if (work.operation.get() != operation) { UsbDriveControl.stop(); return; }
                work.handler.post(() -> {
                    if (work.operation.get() == operation) {
                        work.pendingRequest = false; if (work.activity != null) work.activity.refresh();
                    }
                });
            } catch (RuntimeException e) {
                UsbDriveControl.stop();
                work.handler.post(() -> {
                    if (work.operation.get() == operation) {
                        work.pendingRequest = false;
                        if (work.activity != null) work.activity.operationFailed(e.getMessage() == null
                                ? work.activity.getString(R.string.usb_virtual_failed) : e.getMessage());
                    }
                });
            }
        });
    }
    private void created() {
        if (!mResumed) return;
        for (UsbDrive.Offer image : mImages.offers) if (image.node.equals(mCreatedName) && image.cid.equals(mCreatedCid)) {
            mAwaitCreate = false; mSelected = image; mSelectedKey = image.identity(); mCdrom = false; mRo = false;
            if (mUseAfterCreate) { mFunctions = mCreateFunctions; mPreset = "custom"; }
            save();
            if (mUseAfterCreate) beginSession(image, false, false);
            else open(IMAGES);
            return;
        }
    }
    private boolean slowAllocation() {
        return "exfat".equals(mImages.filesystem) || "vfat".equals(mImages.filesystem) || "msdos".equals(mImages.filesystem);
    }
    private void spinner() {
        ProgressBar busy = new ProgressBar(this);
        mChoices.addView(busy, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }
    private boolean inChoices(View view) {
        while (view != null) {
            if (view == mChoices) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }
    private boolean hasActionableChoice() {
        for (int i = 0; i < mChoices.getChildCount(); i++) {
            View child = mChoices.getChildAt(i);
            if (actionable(child)) return true;
        }
        return false;
    }
    private void focusFirstChoice() {
        mReading = false; mTextScroll.setFocusable(false);
        mPageFocus.applied(mPageFocus.version());
        for (int i = 0; i < mChoices.getChildCount(); i++) {
            View child = mChoices.getChildAt(i);
            if (actionable(child)) { child.requestFocus(); return; }
        }
        mBack.requestFocus();
    }
    private void showIme(EditText field) { UsbTextInput.show(field); }
    private void showDialog(AlertDialog dialog) {
        if (mDialog != null) return;
        mDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (mDialog == dialog) {
                mDialog = null; mPageFocus.begin(true, mFocus); postPageFocus();
            }
        });
        dialog.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) dialog.dismiss();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BUTTON_A && dialog.getCurrentFocus() instanceof EditText) {
                if (event.getAction() == KeyEvent.ACTION_UP) UsbTextInput.show((EditText) dialog.getCurrentFocus());
                return true;
            }
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                dialog.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                        KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount())); return true;
            }
            return false;
        });
        dialog.show();
    }
    private void details(CharSequence title, CharSequence message) {
        showDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).create());
    }
    private int virtualStage(String stage) {
        switch (stage) {
            case "allocate": return R.string.usb_virtual_allocate;
            case "partition": return R.string.usb_virtual_partition;
            case "format": return R.string.usb_virtual_format;
            case "flush": return R.string.usb_virtual_flush;
            case "repair": return R.string.usb_virtual_repair;
            default: return R.string.usb_virtual_validate;
        }
    }

    private String virtualError(String error) {
        switch (error) {
            case "name": return getString(R.string.usb_virtual_bad_name);
            case "label": return getString(R.string.usb_virtual_bad_label);
            case "size": case "space": return getString(R.string.usb_virtual_bad_size);
            case "fat_limit": return getString(R.string.usb_virtual_fat_limit);
            case "exists": return getString(R.string.usb_virtual_exists);
            case "layout": return getString(R.string.usb_virtual_layout);
            case "changed": return getString(R.string.usb_virtual_changed);
            case "unavailable": return getString(R.string.usb_virtual_unavailable);
            case "recovery": return getString(R.string.usb_virtual_recovery);
            case "shared": return getString(R.string.usb_drive_image_in_use);
            default: return getString(R.string.usb_virtual_step_failed, getString(virtualStage(error)));
        }
    }

    private void ask(UsbDrive.Offer offer, boolean cdrom) {
        if (mDialog != null || UsbDriveControl.busy()) return;
        if (cdrom && !offer.cdromPossible()) {
            details(name(offer), getString(R.string.usb_drive_image_cd_size));
            return;
        }
        if (!mUsbMode.isSupported()) {
            Toast.makeText(this, R.string.usb_mode_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        if (mUsbMode.chargingBlocksDevice()) {
            Toast.makeText(this, R.string.charging_refused, Toast.LENGTH_LONG).show();
            return;
        }
        final boolean ro = cdrom || mRo || offer.forcedRo;
        final boolean scary = offer.kind == UsbDrive.Kind.EMMC && !ro;
        final StringBuilder message = new StringBuilder();
        if (offer.kind == UsbDrive.Kind.IMAGE) message.append(getString(cdrom
                ? R.string.usb_drive_image_cd : R.string.usb_drive_image_usb)).append("\n\n");
        if (scary) message.append(getString(R.string.usb_drive_emmc_warning)).append("\n\n");
        if (offer.kind == UsbDrive.Kind.IMAGE) {
            message.append(getString(R.string.usb_drive_image_in_use));
            if (cdrom) message.append("\n\n").append(getString(R.string.usb_drive_image_cd_ro));
            else if (!ro) message.append("\n\n").append(getString(R.string.usb_drive_image_writable));
        } else message.append(getString(R.string.usb_drive_apps));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(scary ? R.string.usb_drive_emmc_title : R.string.usb_drive_confirm)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(scary ? R.string.usb_drive_sure : R.string.usb_drive_share,
                        (d, which) -> mHandler.post(() -> beginSession(offer, ro, cdrom)))
                .create();
        mDialog = dialog;
        dialog.setOnDismissListener(d -> mDialog = null);
        dialog.setOnShowListener(d -> {
            final Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            cancel.setFocusable(true);
            cancel.requestFocus();
            if (!scary) return;
            final Button confirm = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            final long until = SystemClock.elapsedRealtime() + 5000;
            confirm.setEnabled(false);
            final Runnable countdown = new Runnable() {
                @Override
                public void run() {
                    if (!dialog.isShowing()) return;
                    final int left = (int) Math.max(0, (until - SystemClock.elapsedRealtime() + 999) / 1000);
                    if (left == 0) {
                        confirm.setText(R.string.usb_drive_sure);
                        confirm.setEnabled(true);
                    } else {
                        confirm.setText(getString(R.string.usb_drive_countdown, left));
                        mHandler.postDelayed(this, 200);
                    }
                }
            };
            countdown.run();
        });
        dialog.show();
    }

    private String name(UsbDrive.Offer o) {
        if (o.kind == UsbDrive.Kind.IMAGE) return o.label;
        if (o.kind == UsbDrive.Kind.EMMC) return getString(R.string.usb_drive_emmc);
        if (o.kind == UsbDrive.Kind.EASYROMS) return getString(R.string.usb_drive_easyroms, o.slot);
        return o.label.isEmpty() ? getString(R.string.usb_drive_slot, o.slot)
                : getString(R.string.usb_drive_card, o.slot, o.label);
    }

    private String explanation(UsbDrive.Offer o) {
        if (o.kind == UsbDrive.Kind.IMAGE) return getString(o.defaultCdrom()
                ? R.string.usb_drive_image_cd : R.string.usb_drive_image_usb);
        switch (o.reason) {
            case INTERNAL: return getString(R.string.usb_drive_internal);
            case SYSTEM: return getString(R.string.usb_drive_system_card);
            case ANDROID: return getString(R.string.usb_drive_android_card);
            case UNKNOWN: return getString(R.string.usb_drive_unknown);
            case BUSY: return getString(R.string.usb_drive_busy);
            case UNUSABLE: return getString(R.string.usb_drive_unusable);
            default: break;
        }
        if (o.kind == UsbDrive.Kind.EASYROMS) return getString(R.string.usb_drive_games);
        if (o.kind == UsbDrive.Kind.CARD) return getString(R.string.usb_drive_whole);
        return getString(o.forcedRo ? R.string.usb_drive_system_ro : R.string.usb_drive_chip);
    }
    private String readOnlyReason() {
        if (mSelected == null) return getString(R.string.usb_drive_empty);
        if (mSelected.forcedRo) return getString(mSelected.kind == UsbDrive.Kind.IMAGE
                ? R.string.usb_drive_image_volume_ro : R.string.usb_drive_system_ro);
        if (!mSelected.enabled()) return explanation(mSelected);
        if (mSelected.kind == UsbDrive.Kind.IMAGE) return getString(R.string.usb_drive_image_ro_hint);
        return getString(R.string.usb_drive_ro_hint);
    }


}
