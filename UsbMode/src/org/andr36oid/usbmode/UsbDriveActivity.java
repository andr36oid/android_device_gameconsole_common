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

/** The hub and conditional wizard share the tutorial's page and Back/Next pattern. */
public class UsbDriveActivity extends Activity {
    private static final int HUB = 0, CONNECT = 1, IMAGES = 2, CREATE = 3, PROGRESS = 4, SESSION = 5;
    private static final class Work {
        final Handler handler = new Handler(Looper.getMainLooper());
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final UsbRequest request = new UsbRequest();
        final UsbImageCreate creation = new UsbImageCreate();
        UsbDriveActivity activity;
        UsbFunctions policy = UsbDeviceControl.policies();
        int policyFor = -1;
        boolean scanning, querying, loaded, debugging;
        String wifi = "", error = "";
        UsbDrive.Images images = new UsbDrive.Images();
        List<UsbDrive.Offer> offers = new ArrayList<>();

    }
    private Work mWork;
    private final UsbPageFocus mPageFocus = new UsbPageFocus();
    private final List<RadioButton> mSizeChoices = new ArrayList<>();
    private boolean mCustomLabel, mSessionOpened, mLoaded, mCancelling;
    private int mCreateFunctions;
    private String mUiError = "", mCompanion = "";
    private final Handler mHandler = new Handler();
    private UsbMode mUsbMode;
    private UsbFunctions mPolicy;
    private SharedPreferences mPrefs;
    private LinearLayout mChoices;
    private TextView mStep, mTitle, mBody, mNote;
    private ProgressBar mProgress;
    private Button mNext, mBack;
    private HintBar mHints;
    private ScrollView mTextScroll, mChoicesScroll;
    private AlertDialog mDialog;
    private int mPage = HUB, mIndex, mCreateIndex, mFunctions, mShownPage = -1, mShownStep = -1;
    private boolean mResumed, mAdvanced, mMacro, mRo, mCdrom, mDebugging;
    private String mPreset, mSelectedKey, mFocus = "", mLive = "";
    private String mImageName = "image1", mImageSize = "1", mImageFs = "fat32", mImageLabel, mFormCid = "";
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
        mHints = HintBar.attachDefault(this);
        mUsbMode = new UsbMode(this);
        mWork = (Work) getLastNonConfigurationInstance();
        final boolean retained = mWork != null;
        if (!retained) mWork = new Work();
        mUiError = mWork.error;
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
            mCustomLabel = state.getBoolean("custom_label"); mCreateFunctions = state.getInt("create_functions");
            if (!retained) {
                mWork.creation.waiting = state.getBoolean("await_create");
                mWork.creation.use = state.getBoolean("use_after_create");
                mWork.creation.functions = state.getInt("created_functions", mCreateFunctions);
                mWork.creation.name = state.getString("created_name", ""); mWork.creation.cid = state.getString("created_cid", "");
            }
            mPage = state.getInt("page"); mIndex = state.getInt("index"); mCreateIndex = state.getInt("create_index");
            mAdvanced = state.getBoolean("advanced"); mFocus = state.getString("focus", "");
            mImageName = state.getString("image_name", ""); mImageSize = state.getString("image_size", "1");
            mImageFs = state.getString("image_fs", "fat32"); mImageLabel = state.getString("image_label", mImageLabel);
            mFormCid = state.getString("form_cid", "");
        }
        mStep = findViewById(R.id.step_label); mTitle = findViewById(R.id.title);
        mBody = findViewById(R.id.body); mNote = findViewById(R.id.note);
        mChoices = findViewById(R.id.choices); mProgress = findViewById(R.id.progress);
        mTextScroll = findViewById(R.id.text_scroll); mChoicesScroll = findViewById(R.id.choices_scroll);
        mNext = findViewById(R.id.next); mBack = findViewById(R.id.back);
        mNext.setOnClickListener(v -> skipForward()); mBack.setOnClickListener(v -> goBack());
        mNext.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = "next"; });
        mBack.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = "back"; });
        findSelected(); render(); scan();
    }

    @Override public void onResume() {
        super.onResume();
        mResumed = true; mSessionOpened = false;
        mLive = ""; mShownPage = -1;
        if (mDialog == null) render();
        mHandler.post(mPoll);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && mChoices != null) postPageFocus();
    }
    @Override public void onPause() {
        mResumed = false; mHandler.removeCallbacks(mPoll);
        saveEntry(); UsbTextInput.hide(this); super.onPause();
    }
    @Override public Object onRetainNonConfigurationInstance() { return mWork; }
    @Override public void onDestroy() {
        if (mWork.activity == this) mWork.activity = null;
        if (!isChangingConfigurations()) { mWork.request.cancel(); mWork.executor.shutdownNow(); }
        if (mDialog != null) mDialog.dismiss();
        super.onDestroy();
    }
    @Override public void onSaveInstanceState(Bundle state) {
        saveEntry();
        state.putBoolean("cancelling", mCancelling);
        state.putString("companion", mCompanion);
        state.putBoolean("custom_label", mCustomLabel); state.putBoolean("await_create", mWork.creation.waiting);
        state.putBoolean("use_after_create", mWork.creation.use); state.putInt("create_functions", mCreateFunctions);
        state.putInt("created_functions", mWork.creation.functions);
        state.putString("created_name", mWork.creation.name); state.putString("created_cid", mWork.creation.cid);
        state.putInt("page", mPage); state.putInt("index", mIndex); state.putInt("create_index", mCreateIndex);
        state.putBoolean("advanced", mAdvanced); state.putString("focus", mFocus);
        state.putString("image_name", mImageName); state.putString("image_size", mImageSize);
        state.putString("image_fs", mImageFs); state.putString("image_label", mImageLabel);
        state.putString("form_cid", mFormCid);
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() { goBack(); }
    @Override public boolean onNavigateUp() { goBack(); return true; }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // GamepadKeyboard sends cursor keys through the editor's InputConnection.
        if (getCurrentFocus() instanceof EditText && (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0)
            return super.dispatchKeyEvent(event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) goBack();
            return true;
        }
        if (getCurrentFocus() instanceof EditText && UsbTextInput.activate((EditText) getCurrentFocus(), event.getKeyCode(), event)) return true;
        // Single column: Down from the last choice drops to the footer buttons,
        // Up from a footer button returns to the last choice.
        if (inChoices(getCurrentFocus()) && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                && android.view.FocusFinder.getInstance().findNextFocus(mChoices, getCurrentFocus(), View.FOCUS_DOWN) == null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (actionable(mNext)) mNext.requestFocus(); else if (actionable(mBack)) mBack.requestFocus();
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                && (getCurrentFocus() == mNext || getCurrentFocus() == mBack) && hasActionableChoice()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) focusLastChoice();
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
            mWork.request.cancel(); mWork.creation.waiting = false; mCancelling = true;
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
        if (mWork.scanning || mWork.request.pending() || UsbDriveControl.busy()) return;
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
        final String oldCid = mImages.cid;
        mOffers = mWork.offers; mImages = mWork.images; findSelected();
        if (!mWork.debugging && (mFunctions & UsbFunctions.ADB) != 0) { mFunctions &= ~UsbFunctions.ADB; save(); }
        if (mWork.creation.waiting && "done".equals(UsbDriveControl.virtual("result"))) { created(); return; }
        if (mPage == CREATE) {
            if (oldMax != VirtualDrive.maximum(mImages.free, mImages.filesystem) || oldWritable != mImages.writable
                    || !oldCid.equals(mImages.cid) || mCreateIndex == 3 && debugChanged) {
                if (mEdit != null && mEdit.isFocused() && mDialog == null) {
                    // Keep the editor and its live InputConnection while the free-space scan changes.
                    saveEntry();
                    if (mCreateIndex == 1) { sizeDescription(); sizeChoices(); }
                    mNext.setEnabled(createProblem() == 0);
                    if (createProblem() != 0) mNote.setText(createProblem());
                } else if (mDialog == null) { saveEntry(); render(); }
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
        if (mWork.request.pending() || UsbDriveControl.working()) {
            final boolean image = !mWork.request.pending() || mWork.request.image();
            final int page = image ? PROGRESS : SESSION;
            if (mPage != page) open(page);
            progress(image); return;
        }
        if (UsbDriveControl.busy()) {
            if (mWork.creation.waiting) {
                final String prefix = UsbDeviceControl.PREFIX;
                if (!mWork.creation.session(android.os.SystemProperties.get(prefix + "target"),
                        android.os.SystemProperties.get(prefix + "image"), android.os.SystemProperties.get(prefix + "cid")))
                    mUiError = mWork.error = getString(R.string.controller_busy);
                mWork.creation.waiting = false;
            }
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
        if (mWork.creation.waiting) {
            if ("done".equals(UsbDriveControl.virtual("result"))) {
                if (mWork.creation.waitUntil == 0) mWork.creation.waitUntil = SystemClock.elapsedRealtime() + 10000;
                if (SystemClock.elapsedRealtime() >= mWork.creation.waitUntil) {
                    mWork.creation.waiting = false; mUiError = getString(R.string.usb_virtual_changed); open(IMAGES);
                } else { scan(); mBody.setText(R.string.usb_image_finding); }
                return;
            }
            mWork.creation.waiting = false;
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
                : sessionStage(stage);
        mBody.setText(image ? getString(virtualStage(stage)) + (allocate ? " · " + percent + "%"
                + (slowAllocation() ? "\n\n" + getString(R.string.usb_virtual_allocation) : "") : "") : getString(preparing));
        if (!image && UsbWizard.captured(stage, "1".equals(android.os.SystemProperties.get(UsbDeviceControl.PREFIX + "ready")))) {
            mNext.setVisibility(View.GONE); mBack.setVisibility(View.GONE); mNote.setText(R.string.usb_emulator_session_hint);
        } else if (!image) {
            // Until the host enumerates, keep telling the user to connect the cable and what to try.
            mNote.setText(getString(R.string.usb_emulator_cable_prompt) + "\n\n" + getString(R.string.usb_emulator_cable_hint));
        }
    }
    private void operationFailed(String detail) {
        mWork.creation.waiting = false; mUiError = mWork.error = detail;
        open(mWork.request.image() ? IMAGES : HUB);
    }

    private void render() {
        final int position = mPage == CONNECT ? step() : mPage == CREATE ? mCreateIndex : 0;
        final boolean same = mPage == mShownPage && position == mShownStep;
        final int textY = same ? mTextScroll.getScrollY() : 0;
        mShownPage = mPage; mShownStep = position;
        final String focus = same && getCurrentFocus() != null && getCurrentFocus().getTag() instanceof String
                ? (String) getCurrentFocus().getTag() : same ? mFocus : "";
        mFocus = focus;
        if (mEdit != null) UsbTextInput.hide(mEdit);
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
        // No choices on this page (e.g. Review is all summary text): drop the empty choices
        // region so the explanation fills the screen instead of leaving a blank pane.
        mChoicesScroll.setVisibility(mChoices.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        mTextScroll.scrollTo(0, textY);
        updateHints();
        mPageFocus.begin(same, focus);
        postPageFocus();
    }
    private void postPageFocus() {
        final int version = mPageFocus.version();
        mChoices.post(() -> {
            if (isFinishing() || !mPageFocus.ready(version, mResumed, hasWindowFocus(), mDialog != null)) return;
            final String target = mPageFocus.target();
            View chosen = "next".equals(target) ? mNext : "back".equals(target) ? mBack
                    : mChoices.findViewWithTag(target);
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

    // Button hints reflect what A/B do on the current screen, in the shared andr36oid bar.
    private void updateHints() {
        if (mHints == null) return;
        if (mPage == SESSION || mPage == PROGRESS) {
            mHints.set("B", getString(R.string.usb_hint_cancel));
            return;
        }
        String select = getString(R.string.andr36oid_hint_select);
        if (mPage == CONNECT) {
            int current = step();
            if (current == UsbWizard.REVIEW) select = getString(R.string.usb_hint_start);
            else if (current != UsbWizard.ROLE && current != UsbWizard.FUNCTIONS) select = getString(R.string.usb_hint_next);
        }
        mHints.set("A", select, "B", getString(R.string.andr36oid_hint_back));
    }
    private void page(int title, String body) { mTitle.setText(title); mBody.setText(body); }
    // A choice reads as "<bold title>\n<plain what-it-does line>" so options are easy to tell apart.
    private CharSequence label(String title, String summary) {
        if (summary == null || summary.isEmpty()) return title;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(title);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length(),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n').append(summary);
        return sb;
    }
    private void divider() {
        View line = new View(this);
        line.setBackgroundColor(0x33ffffff);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.topMargin = dp(10); lp.bottomMargin = dp(6);
        mChoices.addView(line, lp);
    }
    private Button row(String key, String title, String summary, boolean enabled, Runnable action) {
        Button button = new Button(this, null, 0, R.style.Andr36oidSetupButton_Secondary);
        button.setId(View.generateViewId());
        button.setBackground(getDrawable(R.drawable.usb_choice_background));
        button.setForeground(getDrawable(R.drawable.andr36oid_setup_focus));
        button.setTextColor(getColorStateList(R.color.usb_choice_text));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setTag(key); button.setText(label(title, summary));
        button.setAllCaps(false); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setEnabled(enabled); button.setMinHeight(dp(64));
        button.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = key; });
        button.setOnClickListener(v -> action.run());
        mChoices.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }
    private RadioButton choice(String key, String title, String summary, boolean checked, boolean enabled, Runnable action) {
        return choice(key, title, summary, checked, enabled, 0, action);
    }
    private RadioButton choice(String key, String title, String summary, boolean checked, boolean enabled, int icon, Runnable action) {
        RadioButton choice = new RadioButton(this);
        choice.setId(View.generateViewId()); choice.setTag(key);
        choice.setText(label(title, summary));
        choice.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        choice.setTextColor(getColorStateList(R.color.usb_choice_text));
        choice.setBackground(getDrawable(R.drawable.usb_choice_background));
        choice.setForeground(getDrawable(R.drawable.andr36oid_setup_focus));
        choice.setPadding(dp(12), dp(10), dp(12), dp(10)); choice.setMinHeight(dp(64));
        if (icon != 0) {
            choice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            choice.setCompoundDrawablePadding(dp(12));
            choice.setCompoundDrawableTintList(getColorStateList(R.color.usb_choice_text));
        }
        choice.setChecked(checked); choice.setEnabled(enabled);
        choice.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = key; });
        choice.setOnClickListener(v -> action.run());
        mChoices.addView(choice, new LinearLayout.LayoutParams(-1, -2));
        return choice;
    }
    private void toggle(String key, int title, String summary, boolean checked, boolean enabled, Runnable action) {
        CheckBox box = new CheckBox(this); box.setTag(key); box.setText(label(getString(title), summary));
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
        mNext.setVisibility(View.GONE); mBack.setEnabled(mWork.loaded);
        if (!mWork.loaded) spinner();
        row("connect", getString(R.string.usb_hub_connect), getString(R.string.usb_wizard_connect_summary), mWork.loaded,
                () -> { mIndex = 0; open(CONNECT); });
        row("images", getString(R.string.usb_hub_images), getString(R.string.usb_virtual_summary), mWork.loaded, () -> open(IMAGES));
        liveResult();
    }
    private void liveResult() {
        String status = "error".equals(UsbDriveControl.state()) ? getString(R.string.usb_device_failed)
                + "\n" + UsbDriveControl.error() + "\n" + UsbDriveControl.detail()
                + "\n\n" + getString(R.string.usb_emulator_cable_hint) : "";
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
        // Parallel to UsbWizard.ROLES: pc, game, drive, wired, music, awake, macro, link, custom.
        final int[] titles = {R.string.usb_device_pc, R.string.usb_device_game, R.string.usb_drive_title,
                R.string.usb_device_wired, R.string.usb_device_music, R.string.usb_jiggle_preset,
                R.string.usb_macro_title, R.string.usb_link_title, R.string.usb_device_custom};
        final int[] descriptions = {R.string.usb_role_pc_summary, R.string.usb_role_game_summary, R.string.usb_role_drive_summary,
                R.string.usb_role_wired_summary, R.string.usb_role_music_summary, R.string.usb_role_awake_summary,
                R.string.usb_role_macro_summary, R.string.usb_role_link_summary, R.string.usb_role_custom_summary};
        final int[] icons = {R.drawable.ic_usecase_pc, R.drawable.ic_usecase_game, R.drawable.ic_usecase_drive,
                R.drawable.ic_usecase_wired, R.drawable.ic_usecase_music, R.drawable.ic_usecase_awake,
                R.drawable.ic_usecase_macro, R.drawable.ic_usecase_link, R.drawable.ic_usecase_custom};
        for (int i = 0; i < UsbWizard.ROLES.length; i++) {
            final String role = UsbWizard.ROLES[i];
            if ("custom".equals(role)) divider();
            choice(role, getString(titles[i]), getString(descriptions[i]), role.equals(mPreset), true, icons[i], () -> {
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
            if (pair[0] == 0 || pair[1] == 0) return getString(R.string.usb_device_policy_unavailable);
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
        // PC setup boots from an optical image, so it only lists ISOs; physical USB drives
        // belong to the drive/custom use cases, not here.
        final boolean isoOnly = "pc".equals(mPreset);
        page(R.string.usb_device_target, getString(isoOnly ? R.string.usb_drive_iso_hint : R.string.usb_drive_intro)
                + "\n\n" + getString(R.string.usb_drive_image_hint));
        for (UsbDrive.Offer offer : mOffers) {
            if (isoOnly && !(offer.kind == UsbDrive.Kind.IMAGE && offer.cdromPossible())) continue;
            choice(offer.identity(), name(offer), offerDetail(offer),
                offer.identity().equals(mSelectedKey), offer.enabled(), offerIcon(offer), () -> {
                    mSelected = offer; mSelectedKey = offer.identity();
                    mCdrom = offer.defaultCdrom() && offer.cdromPossible();
                    if (offer.kind == UsbDrive.Kind.IMAGE && !offer.type.isEmpty()) mRo = false;
                    changed();
                });
        }
        // Only Custom exposes the CD-vs-drive toggle; Drive and PC take the sensible default
        // (ISO -> CD, image file -> drive) so picking the image advances without a second pick.
        if ("custom".equals(mPreset) && mSelected != null && mSelected.kind == UsbDrive.Kind.IMAGE) {
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
        if (!mWork.wifi.isEmpty()) body += "\n\n" + mWork.wifi;
        if ((mFunctions & (UsbFunctions.NETWORK | UsbFunctions.RNDIS)) != 0)
            body += "\n\n" + getString(R.string.usb_link_guide) + "\n\n" + getString(R.string.usb_stream_guide) + "\n\n" + getString(R.string.usb_device_link_cable);
        final boolean companion = (mFunctions & (UsbFunctions.NETWORK | UsbFunctions.RNDIS)) != 0
                && (mFunctions & ~(UsbFunctions.NETWORK | UsbFunctions.RNDIS | UsbFunctions.ADB)) == 0;
        if (companion) {
            // Connecting the network without any app is the default; apps are only offered when installed.
            row("connect_only", getString(R.string.usb_device_connect_only), getString(R.string.usb_device_connect_only_summary),
                    "ok".equals(startReason()), () -> { mCompanion = ""; start(); });
            if (getPackageManager().getLaunchIntentForPackage("com.retroarch") != null)
                row("retroarch", getString(R.string.usb_link_open), getString(R.string.usb_emulator_start_app),
                        "ok".equals(startReason()), () -> startCompanion("com.retroarch"));
            if (getPackageManager().getLaunchIntentForPackage("com.limelight") != null)
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
    private boolean beginSession(UsbDrive.Offer offer, boolean ro, boolean cdrom) {
        final Work work = mWork;
        final int operation = work.request.begin(false, UsbDriveControl.busy()), mask = mFunctions;
        if (operation == 0) { Toast.makeText(this, R.string.controller_busy, Toast.LENGTH_LONG).show(); return false; }
        final android.content.Context context = getApplicationContext();
        mCancelling = false; mSessionOpened = false; mUiError = work.error = ""; open(SESSION);
        UsbRequest.executor.execute(() -> {
            String failure = "";
            boolean submitted = false;
            try {
                if (!work.request.current(operation)) return;
                UsbMode usb = new UsbMode(context);
                if (!usb.isSupported() || usb.chargingBlocksDevice()) throw new IllegalStateException(context.getString(R.string.charging_refused));
                UsbDrive.Offer fresh = offer;
                if (offer != null) {
                    fresh = null;
                    List<UsbDrive.Offer> offers = UsbDriveControl.scan(context); offers.addAll(UsbDriveControl.images().offers);
                    for (UsbDrive.Offer candidate : offers) if (candidate.enabled() && candidate.identity().equals(offer.identity())) fresh = candidate;
                    if (fresh == null) throw new IllegalStateException(context.getString(R.string.usb_virtual_changed));
                }
                if (!work.request.current(operation)) return;
                usb.setConfirmedThisBoot();
                submitted = true;
                if (!UsbDeviceControl.request(context, mask, fresh, ro, cdrom, () -> work.request.current(operation)))
                    failure = context.getString(R.string.usb_device_failed);
            } catch (RuntimeException e) {
                failure = e.getMessage() == null ? context.getString(R.string.usb_device_failed) : e.getMessage();
            } finally {
                try {
                    if (submitted && (!work.request.current(operation) || !failure.isEmpty())) UsbDriveControl.stop();
                } finally { completeRequest(work, operation, failure); }
            }
        });
        return true;
    }
    private static void completeRequest(Work work, int operation, String failure) {
        work.handler.post(() -> {
            if (!work.request.finish(operation)) return;
            if (work.request.current(operation) && !failure.isEmpty()) {
                work.error = failure; work.creation.waiting = false;
                if (work.activity != null) work.activity.operationFailed(failure);
            } else if (work.activity != null) work.activity.refresh();
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
                        // The image is already chosen, so go straight to Review (Back still reaches Storage).
                        mIndex = UsbWizard.steps(mPreset, mFunctions).indexOf(UsbWizard.REVIEW); open(CONNECT);
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
        mFormCid = mImages.cid;
        mImageName = VirtualDrive.nextName(names); mImageFs = "fat32"; mImageSize = "1";
        mCustomLabel = false; mImageLabel = VirtualDrive.defaultLabel(mImageName, mImageFs);
        mCreateFunctions = fromStorage ? mFunctions : UsbWizard.preset("drive", mWork.debugging);
        mCreateIndex = 0; mUiError = ""; mCompanion = ""; open(CREATE);
    }
    private void create() {
        mStep.setText(getString(R.string.usb_wizard_step, mCreateIndex + 1, 4));
        mProgress.setProgress((mCreateIndex + 1) * 25);
        mNext.setEnabled(createProblem() == 0);
        if (mCreateIndex == 0) {
            page(R.string.usb_virtual_name, getString(R.string.usb_image_name_hint));
            editEntry(mImageName, false);
        } else if (mCreateIndex == 1) {
            mTitle.setText(R.string.usb_virtual_size); sizeDescription();
            editEntry(mImageSize, true);
            sizeChoices();
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
        if (createProblem() != 0) mNote.setText(createProblem());
    }
    private int createProblem() {
        if (!VirtualDrive.sameCard(mFormCid, mImages)) return R.string.usb_image_card_changed;
        if (!mImages.mounted || !mImages.writable) return R.string.usb_virtual_unavailable;
        return VirtualDrive.maximum(mImages.free, mImages.filesystem) < VirtualDrive.GB ? R.string.usb_virtual_bad_size : 0;
    }
    private void sizeDescription() {
        mBody.setText(getString(R.string.usb_virtual_available, VirtualDrive.maximum(mImages.free, mImages.filesystem) / VirtualDrive.GB));
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
            else { UsbTextInput.hide(editor); if (numeric) saveEntry(); }
        });
        mEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (mEdit != editor || mPage != CREATE || mCreateIndex != (numeric ? 1 : 0)) return;
                if (numeric) { mImageSize = value.toString(); updateSizeChoices(); }
                else { mImageName = value.toString(); if (!mCustomLabel) mImageLabel = VirtualDrive.defaultLabel(mImageName, mImageFs); }
            }
            @Override public void afterTextChanged(Editable value) {
                if (mEdit == editor && mPage == CREATE && mCreateIndex == 1 && numeric) saveEntry();
            }
        });
        mChoices.addView(mEdit, new LinearLayout.LayoutParams(-1, -2));
    }
    private void sizeChoices() {
        for (String size : VirtualDrive.presets(mImages.free, mImages.filesystem)) {
            if (mChoices.findViewWithTag("size:" + size) != null) continue;
            RadioButton option = choice("size:" + size, getString(R.string.usb_wizard_gb, size), "",
                    VirtualDrive.presetSelected(mImageSize, size), true, () -> {
                        mImageSize = size; mEdit.setText(size); updateSizeChoices();
                    });
            mSizeChoices.add(option);
        }
        updateSizeChoices();
    }
    private void updateSizeChoices() {
        for (RadioButton option : mSizeChoices) {
            String size = option.getTag().toString().substring("size:".length());
            option.setVisibility(VirtualDrive.size(size) <= VirtualDrive.maximum(mImages.free, mImages.filesystem) ? View.VISIBLE : View.GONE);
            option.setChecked(VirtualDrive.presetSelected(mImageSize, size));
        }
    }
    private void saveEntry() {
        if (mPage != CREATE || mEdit == null) return;
        if (mCreateIndex == 0) mImageName = mEdit.getText().toString();
        else if (mCreateIndex == 1) {
            String value = mEdit.getText().toString();
            mImageSize = VirtualDrive.clampSize(value, mImages.free, mImages.filesystem);
            if (!value.equals(mImageSize)) {
                mEdit.setText(mImageSize); mEdit.setSelection(mImageSize.length());
                mNote.setText(getString(R.string.usb_image_size_clamped, mImageSize));
            }
            updateSizeChoices();
        }
        if (!mCustomLabel) mImageLabel = VirtualDrive.defaultLabel(mImageName, mImageFs);
    }
    private boolean validEntry() {
        int error = createProblem();
        if (error != 0) { mNote.setText(error); return false; }
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
        if (runImageOperation(() -> {
            UsbDrive.Images fresh = UsbDriveControl.images();
            if (!VirtualDrive.sameCard(images.cid, fresh)) throw new IllegalStateException(context.getString(R.string.usb_image_card_changed));
            if (!fresh.mounted || !fresh.writable) throw new IllegalStateException(context.getString(R.string.usb_virtual_unavailable));
            if (VirtualDrive.size(size) > VirtualDrive.maximum(fresh.free, fresh.filesystem))
                throw new IllegalStateException(context.getString(R.string.usb_virtual_bad_size));
            UsbDriveControl.create(context, fresh, name, size, fs, label);
        })) mWork.creation.begin(name + ".img", images.cid, mCreateFunctions, use);
    }
    private boolean runImageOperation(Runnable request) {
        final Work work = mWork;
        final int operation = work.request.begin(true, UsbDriveControl.busy());
        if (operation == 0) { Toast.makeText(this, R.string.controller_busy, Toast.LENGTH_LONG).show(); return false; }
        mCancelling = false; mUiError = work.error = ""; open(PROGRESS);
        final String failed = getString(R.string.usb_virtual_failed);
        UsbRequest.executor.execute(() -> {
            String failure = "";
            boolean submitted = false;
            try {
                if (!work.request.current(operation)) return;
                submitted = true; request.run();
            } catch (RuntimeException e) {
                failure = e.getMessage() == null ? failed : e.getMessage();
            } finally {
                try {
                    if (submitted && (!work.request.current(operation) || !failure.isEmpty())) UsbDriveControl.stop();
                } finally { completeRequest(work, operation, failure); }
            }
        });
        return true;
    }
    private void created() {
        if (!mResumed || mWork.request.pending() || UsbDriveControl.busy()) return;
        for (UsbDrive.Offer image : mImages.offers) if (mWork.creation.matches(image)) {
            mSelected = image; mSelectedKey = image.identity(); mCdrom = false; mRo = false;
            if (mWork.creation.use) { mFunctions = mWork.creation.functions; mPreset = "custom"; }
            save();
            if (mWork.creation.use) {
                if (!beginSession(image, false, false)) return;
                mWork.creation.waiting = false;
            } else { mWork.creation.waiting = false; open(IMAGES); }
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
    private void focusLastChoice() {
        mPageFocus.applied(mPageFocus.version());
        for (int i = mChoices.getChildCount() - 1; i >= 0; i--) {
            View child = mChoices.getChildAt(i);
            if (actionable(child)) { child.requestFocus(); return; }
        }
        mBack.requestFocus();
    }
    private void showDialog(AlertDialog dialog) {
        if (mDialog != null) return;
        mDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (mDialog == dialog) {
                mDialog = null; mPageFocus.begin(true, mFocus); postPageFocus();
            }
        });
        UsbDialogInput.show(dialog);
    }
    private void details(CharSequence title, CharSequence message) {
        showDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).create());
    }
    // Filetype indicator for a storage tile: optical image, other disk image, or a real drive.
    private int offerIcon(UsbDrive.Offer offer) {
        if (offer.kind == UsbDrive.Kind.IMAGE)
            return offer.cdromPossible() ? R.drawable.ic_file_iso : R.drawable.ic_file_img;
        return R.drawable.ic_usecase_drive;
    }
    // Readable line for each session step so Preparing shows progress instead of a bare spinner.
    private int sessionStage(String stage) {
        switch (stage) {
            case "validate": return R.string.usb_stage_validate;
            case "save_controller": case "save_backlight": return R.string.usb_stage_save;
            case "gate_android": return R.string.usb_stage_gate;
            case "unbind_udc": return R.string.usb_stage_unbind;
            case "configure_drive": return R.string.usb_stage_configure;
            case "controller_daemon": return R.string.usb_stage_controller;
            case "device_role": return R.string.usb_stage_role;
            case "bind_drive": case "bind_udc": return R.string.usb_stage_bind;
            default: return R.string.usb_device_preparing;
        }
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
            case "fs": return getString(R.string.usb_virtual_hostfs);
            case "tools": return getString(R.string.usb_virtual_missing_tool);
            case "loop": return getString(R.string.usb_virtual_loop);
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
            // The "don't edit the file" warning always shows; the "EASYROMS stays available"
            // reassurance only when EASYROMS is not already visible in Android (user's rule).
            if (!"1".equals(android.os.SystemProperties.get("sys.andr36oid.easyroms.ready")))
                message.append(getString(R.string.usb_drive_easyroms_stays)).append(' ');
            message.append(getString(R.string.usb_drive_image_warning));
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
        showDialog(dialog);
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
