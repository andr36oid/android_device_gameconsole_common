package org.andr36oid.usbmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.andr36oid.hints.HintBar;

import java.util.ArrayList;
import java.util.List;

/** The hub and conditional wizard share the tutorial's page and Back/Next pattern. */
public class UsbDriveActivity extends Activity {
    private static final int HUB = 0, CONNECT = 1, IMAGES = 2, CREATE = 3, PROGRESS = 4, SESSION = 5;
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
    private String mImageName = "", mImageSize = "1", mImageFs = "fat32", mImageLabel;
    private long mNextScan;
    private UsbDrive.Offer mSelected;
    private UsbDrive.Images mImages = new UsbDrive.Images();
    private List<UsbDrive.Offer> mOffers = new ArrayList<>();
    private EditText mEdit;
    private final Runnable mPoll = new Runnable() {
        @Override public void run() {
            refresh();
            if (mResumed) mHandler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.usb_drive_activity);
        HintBar.attachDefault(this);
        mUsbMode = new UsbMode(this);
        mPolicy = UsbDeviceControl.policies();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPreset = mPrefs.getString("usb_preset", "custom");
        mFunctions = mPrefs.getInt("usb_functions", UsbFunctions.XINPUT);
        mSelectedKey = mPrefs.getString("usb_storage", "");
        mCdrom = mPrefs.getBoolean("usb_cdrom", true);
        mRo = mPrefs.getBoolean("usb_ro", true);
        mMacro = mPrefs.getBoolean("usb_macro", false);
        mImageLabel = getString(R.string.usb_virtual_default_label);
        if (state != null) {
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
        mTextScroll.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = "text"; });
        scan(); render();
    }

    @Override public void onResume() {
        super.onResume();
        mResumed = true;
        mPolicy = UsbDeviceControl.policies();
        mLive = ""; render(); mHandler.post(mPoll);
    }
    @Override public void onPause() {
        mResumed = false; mHandler.removeCallbacks(mPoll);
        saveEntry(); super.onPause();
    }
    @Override public void onDestroy() {
        if (mDialog != null) mDialog.dismiss();
        super.onDestroy();
    }
    @Override public void onSaveInstanceState(Bundle state) {
        saveEntry();
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
        saveEntry(); mPage = page; mFocus = ""; mLive = "";
        if (page == IMAGES || page == CREATE || page == CONNECT) { scan(); mNextScan = 0; }
        render();
    }
    private void goBack() {
        if (mDialog != null) { mDialog.dismiss(); return; }
        saveEntry();
        if (mPage == PROGRESS || UsbDriveControl.working()) { UsbDriveControl.stop(); return; }
        if (mPage == CONNECT && mIndex > 0) { mIndex--; mFocus = ""; render(); }
        else if (mPage == CREATE && mCreateIndex > 0) { mCreateIndex--; mFocus = ""; render(); }
        else if (mPage == CREATE) open(IMAGES);
        else if (mPage != HUB) open(HUB);
        else finish();
    }
    private void skipForward() {
        if (!mNext.isEnabled()) return;
        if (mPage == CONNECT) {
            if (step() == UsbWizard.REVIEW) start();
            else { mIndex++; mFocus = ""; render(); }
        } else if (mPage == CREATE) {
            saveEntry();
            if (!validEntry()) return;
            if (mCreateIndex < 3) { mCreateIndex++; mFocus = ""; render(); }
            else {
                scan();
                if (!mImages.mounted || !mImages.writable || !validEntry()) return;
                if (UsbDriveControl.busy()) { refresh(); return; }
                UsbDriveControl.create(this, mImages, mImageName, mImageSize, mImageFs, mImageLabel);
                open(PROGRESS);
            }
        } else if (mPage == PROGRESS) UsbDriveControl.stop();
        else if (mPage == SESSION) startActivity(new Intent(this, ControllerSessionActivity.class));
    }

    private void scan() {
        mOffers = UsbDriveControl.scan(this); mImages = UsbDriveControl.images();
        mOffers.addAll(mImages.offers); mSelected = null;
        for (UsbDrive.Offer offer : mOffers) if (offer.identity().equals(mSelectedKey)) mSelected = offer;
    }
    private void refresh() {
        if (UsbDriveControl.working()) {
            if (mPage != PROGRESS) open(PROGRESS);
            mProgress.setIndeterminate(false);
            int percent;
            try { percent = Integer.parseInt(UsbDriveControl.virtual("percent")); } catch (NumberFormatException e) { percent = 0; }
            mProgress.setProgress(percent);
            mBody.setText(getString(virtualStage(UsbDriveControl.virtual("stage"))) + " · " + percent + "%\n\n"
                    + getString(R.string.usb_virtual_allocation));
            return;
        }
        if (UsbDriveControl.busy()) {
            if (mPage != SESSION) {
                open(SESSION); startActivity(new Intent(this, ControllerSessionActivity.class));
            }
            return;
        }
        if (mPage == PROGRESS) open(IMAGES);
        if (mPage == SESSION) open(HUB);
        final boolean relevant = mPage == IMAGES || mPage == CREATE || mPage == CONNECT
                && (step() == UsbWizard.STORAGE || step() == UsbWizard.FUNCTIONS || step() == UsbWizard.REVIEW);
        if (relevant && SystemClock.elapsedRealtime() >= mNextScan) {
            UsbDriveControl.scanImages(); mNextScan = SystemClock.elapsedRealtime() + 5000;
        }
        if (relevant) scan();
        if (mPage == CREATE) {
            mNext.setEnabled(mImages.mounted && mImages.writable);
            if (!mNext.isEnabled()) mNote.setText(R.string.usb_virtual_unavailable);
        }
        final boolean debugging = UsbDeviceControl.debugging(this);
        if (!debugging && (mFunctions & UsbFunctions.ADB) != 0) { mFunctions &= ~UsbFunctions.ADB; save(); }
        final StringBuilder signature = new StringBuilder(UsbDriveControl.state()).append(debugging)
                .append(mImages.mounted).append(mImages.writable).append(mImages.free)
                .append(UsbDriveControl.virtual("result")).append(UsbDriveControl.virtual("error")).append(UsbDriveControl.virtual("detail"));
        if (relevant) for (UsbDrive.Offer offer : mOffers) signature.append(offer.identity()).append(offer.reason).append(offer.type).append(offer.forcedRo);
        if (!signature.toString().equals(mLive)) {
            if (debugging != mDebugging) mPolicy = UsbDeviceControl.policies();
            mDebugging = debugging; mLive = signature.toString();
            if (mPage != CREATE && mDialog == null) render();
        }
    }

    private void render() {
        final int position = mPage == CONNECT ? step() : mPage == CREATE ? mCreateIndex : 0;
        final boolean same = mPage == mShownPage && position == mShownStep;
        final int textY = same ? mTextScroll.getScrollY() : 0;
        final int choiceY = same ? mChoiceScroll.getScrollY() : 0;
        mShownPage = mPage; mShownStep = position;
        mEdit = null; mChoices.removeAllViews(); mNote.setText("");
        mNext.setVisibility(View.VISIBLE); mNext.setEnabled(true);
        mNext.setText(R.string.usb_wizard_next); mBack.setText(R.string.usb_wizard_back);
        mProgress.setIndeterminate(false); mProgress.setMax(100); mProgress.setProgress(0);
        mStep.setText(R.string.usb_device_title);
        if (mPage == CONNECT) {
            int current = step();
            List<Integer> steps = UsbWizard.steps(mPreset, mFunctions);
            mStep.setText(getString(R.string.usb_wizard_step, mIndex + 1, steps.size()));
            mProgress.setProgress((mIndex + 1) * 100 / steps.size());
            if (current == UsbWizard.FUNCTIONS || current == UsbWizard.CONTROLLER || current == UsbWizard.KEYBOARD || current == UsbWizard.REVIEW)
                mPolicy.queryMissing(UsbFunctions.pageMasks(mFunctions));
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
            page(R.string.usb_virtual_working, getString(R.string.usb_virtual_allocation));
            mNext.setText(android.R.string.cancel); mProgress.setIndeterminate(true);
        } else if (mPage == SESSION) {
            page(R.string.usb_device_session, getString(R.string.usb_device_preparing));
            mNext.setText(R.string.usb_device_session);
        } else hub();
        mTextScroll.scrollTo(0, textY);
        final String focus = mFocus;
        mChoices.post(() -> {
            if (isFinishing()) return;
            mChoiceScroll.scrollTo(0, choiceY);
            View chosen = "next".equals(focus) && mNext.getVisibility() == View.VISIBLE ? mNext
                    : "back".equals(focus) ? mBack : "text".equals(focus) ? mTextScroll : mChoices.findViewWithTag(focus);
            if (chosen == null || !chosen.isEnabled()) {
                chosen = null;
                for (int i = 0; i < mChoices.getChildCount(); i++) {
                    View child = mChoices.getChildAt(i);
                    if (child.isFocusable() && child.isEnabled()) { chosen = child; break; }
                }
            }
            if (chosen != null) chosen.requestFocus();
            else if (mNext.isEnabled() && mNext.getVisibility() == View.VISIBLE) mNext.requestFocus();
            else mBack.requestFocus();
        });
    }
    private void page(int title, String body) { mTitle.setText(title); mBody.setText(body); }
    private Button row(String key, String title, String summary, boolean enabled, Runnable action) {
        Button button = new Button(this);
        button.setTag(key); button.setText(summary.isEmpty() ? title : title + "\n" + summary);
        button.setAllCaps(false); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setEnabled(enabled); button.setMinHeight(dp(64));
        button.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = key; });
        button.setOnClickListener(v -> action.run());
        mChoices.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }
    private void choice(String key, String title, String summary, boolean checked, boolean enabled, Runnable action) {
        row(key, (checked ? "● " : "○ ") + title, summary, enabled, action);
    }
    private void toggle(String key, int title, String summary, boolean checked, boolean enabled, Runnable action) {
        CheckBox box = new CheckBox(this); box.setTag(key); box.setText(getString(title) + "\n" + summary);
        box.setChecked(checked); box.setEnabled(enabled); box.setMinHeight(dp(64));
        box.setForeground(getDrawable(R.drawable.andr36oid_setup_focus));
        box.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = key; });
        box.setOnClickListener(v -> action.run());
        mChoices.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void hub() {
        page(R.string.usb_device_title, getString(R.string.usb_device_about) + "\n\n" + getString(R.string.usb_drive_cable));
        mNext.setVisibility(View.GONE);
        row("connect", getString(R.string.usb_hub_connect), getString(R.string.usb_wizard_connect_summary), true,
                () -> { mIndex = 0; open(CONNECT); });
        row("images", getString(R.string.usb_hub_images), getString(R.string.usb_virtual_summary), true, () -> open(IMAGES));
        liveResult();
    }
    private void liveResult() {
        String status = "error".equals(UsbDriveControl.state()) ? getString(R.string.usb_device_failed) : "";
        String result = UsbDriveControl.virtual("result");
        String detail = "";
        if ("error".equals(result)) detail = virtualError(UsbDriveControl.virtual("error")) + "\n" + UsbDriveControl.virtual("detail");
        else if ("done".equals(result)) detail = getString(R.string.usb_virtual_done);
        else if ("cancelled".equals(result)) detail = getString(R.string.usb_virtual_cancelled);
        if (!detail.isEmpty()) status += (status.isEmpty() ? "" : "\n\n") + detail;
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
        save(); scan();
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
            default: return getString(R.string.usb_device_policy_unavailable);
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
        body += "\n\n" + getString(R.string.usb_device_boot_hint) + "\n\n" + getString(R.string.usb_drive_cable);
        if ((mFunctions & (UsbFunctions.NETWORK | UsbFunctions.RNDIS)) != 0)
            body += "\n\n" + getString(R.string.usb_link_guide) + "\n\n" + getString(R.string.usb_stream_guide) + "\n\n" + getString(R.string.usb_device_link_cable);
        page(R.string.usb_wizard_review, body);
        mNext.setText(R.string.usb_device_start);
        String why = startReason();
        mNext.setEnabled("ok".equals(why));
        if (!"ok".equals(why)) mNote.setText(reason(why, mFunctions));
    }
    private String startReason() {
        String why = mPolicy.reason(mFunctions);
        if ("ok".equals(why) && (mFunctions & UsbFunctions.STORAGE) != 0
                && (mSelected == null || !mSelected.enabled())) why = "storage";
        if ((mFunctions & UsbFunctions.ADB) != 0 && !UsbDeviceControl.debugging(this)) why = "debugging";
        return why;
    }
    private void start() {
        scan();
        mPolicy.queryMissing(UsbFunctions.pageMasks(mFunctions));
        if (!"ok".equals(startReason())) { render(); return; }
        if ((mFunctions & UsbFunctions.STORAGE) != 0) ask(mSelected, mSelected.kind == UsbDrive.Kind.IMAGE && mCdrom);
        else warn(null, true, false);
    }
    private void warn(UsbDrive.Offer offer, boolean ro, boolean cdrom) {
        if (mDialog != null || UsbDriveControl.busy()) return;
        if (!mUsbMode.isSupported() || mUsbMode.chargingBlocksDevice()) {
            Toast.makeText(this, mUsbMode.chargingBlocksDevice() ? R.string.charging_refused : R.string.usb_mode_unsupported, Toast.LENGTH_LONG).show(); return;
        }
        mDialog = DeviceModeWarning.show(this, mUsbMode, new DeviceModeWarning.Callback() {
            @Override public void onConfirmed() {
                mDialog = null;
                if (offer == null) {
                    if (mUsbMode.chargingBlocksDevice()) {
                        Toast.makeText(UsbDriveActivity.this, R.string.charging_refused, Toast.LENGTH_LONG).show(); return;
                    }
                    UsbDeviceControl.start(UsbDriveActivity.this, mFunctions, null, true, false);
                } else share(offer, ro, cdrom);
            }
            @Override public void onCancelled() { mDialog = null; }
        });
    }
    private void images() {
        page(R.string.usb_hub_images, getString(R.string.usb_drive_image_hint));
        mNext.setVisibility(View.GONE);
        row("create", getString(R.string.usb_virtual_create), getString(mImages.mounted && mImages.writable
                ? R.string.usb_virtual_summary : R.string.usb_virtual_unavailable), mImages.mounted && mImages.writable, () -> {
                    mCreateIndex = 0; open(CREATE);
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
                        mPolicy.queryMissing(UsbFunctions.pageMasks(mFunctions));
                        String why = mPolicy.reason(mFunctions | UsbFunctions.STORAGE);
                        if (!"ok".equals(why)) { details(name(offer), reason(why, mFunctions | UsbFunctions.STORAGE)); return; }
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
                        (d, which) -> { if (!UsbDriveControl.busy()) { UsbDriveControl.maintain(this, offer, action); open(PROGRESS); } }).create());
        mDialog.getButton(DialogInterface.BUTTON_NEGATIVE).requestFocus();
    }
    private void rename(UsbDrive.Offer offer) {
        String basename = offer.node.substring(0, offer.node.lastIndexOf('.'));
        padText(getString(R.string.usb_virtual_rename), basename, false, value -> {
            if (!VirtualDrive.name(value)) { details(name(offer), getString(R.string.usb_virtual_bad_name)); return; }
            if (UsbDriveControl.busy()) { refresh(); return; }
            UsbDriveControl.rename(this, offer, value); open(PROGRESS);
        });
    }
    private void create() {
        mStep.setText(getString(R.string.usb_wizard_step, mCreateIndex + 1, 4));
        mProgress.setProgress((mCreateIndex + 1) * 25);
        if (mCreateIndex == 0) {
            page(R.string.usb_virtual_name, getString(R.string.usb_virtual_bad_name));
            editEntry(mImageName, false);
        } else if (mCreateIndex == 1) {
            page(R.string.usb_virtual_size, getString(R.string.usb_virtual_available,
                    VirtualDrive.maximum(mImages.free, mImages.filesystem) / VirtualDrive.GB));
            editEntry(mImageSize, true);
            for (String size : new String[] {"1", "2", "4", "8", "16", "32", "64", "1024"})
                choice("size:" + size, getString(R.string.usb_wizard_gb, size), "", size.equals(mImageSize), true,
                        () -> { mImageSize = size; mEdit.setText(size); });
        } else if (mCreateIndex == 2) {
            page(R.string.usb_wizard_filesystem, getString(R.string.usb_virtual_summary));
            String[] names = getString(R.string.usb_virtual_filesystems).split("\\|");
            int[] summaries = {R.string.usb_virtual_fat32, R.string.usb_virtual_exfat, R.string.usb_virtual_ntfs, R.string.usb_virtual_ext4};
            for (int i = 0; i < names.length; i++) {
                final String fs = VirtualDrive.FILESYSTEMS[i];
                choice(fs, names[i], getString(summaries[i]), fs.equals(mImageFs), true, () -> { mImageFs = fs; render(); });
            }
        } else {
            page(R.string.usb_virtual_label, mImageName + ".img · " + getString(R.string.usb_wizard_gb, mImageSize)
                    + " · " + mImageFs + "\n\n" + getString(R.string.usb_virtual_bad_label) + "\n\n" + getString(R.string.usb_virtual_allocation));
            editEntry(mImageLabel, false); mNext.setText(R.string.usb_virtual_create);
        }
    }
    private void editEntry(String value, boolean numeric) {
        mEdit = new EditText(this); mEdit.setSingleLine(true); mEdit.setText(value);
        mEdit.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        mEdit.setShowSoftInputOnFocus(false);
        mEdit.setTag("entry");
        mEdit.setOnFocusChangeListener((v, focused) -> { if (focused) mFocus = "entry"; });
        mChoices.addView(mEdit, new LinearLayout.LayoutParams(-1, -2));
        row("edit", getString(R.string.usb_wizard_edit_text), getString(R.string.usb_wizard_edit_summary), true,
                () -> padText(mTitle.getText().toString(), mEdit.getText().toString(), numeric, text -> {
                    if (mPage == CREATE && mEdit != null) { mEdit.setText(text); saveEntry(); }
                }));
    }
    private void saveEntry() {
        if (mPage != CREATE || mEdit == null) return;
        String value = mEdit.getText().toString();
        if (mCreateIndex == 0) mImageName = value;
        else if (mCreateIndex == 1) mImageSize = value;
        else if (mCreateIndex == 3) mImageLabel = value;
    }
    private boolean validEntry() {
        int error = 0;
        if (!VirtualDrive.name(mImageName)) error = R.string.usb_virtual_bad_name;
        else if (mCreateIndex >= 1 && (VirtualDrive.size(mImageSize) == 0
                || VirtualDrive.size(mImageSize) > VirtualDrive.maximum(mImages.free, mImages.filesystem)))
            error = ("vfat".equals(mImages.filesystem) || "msdos".equals(mImages.filesystem)) && VirtualDrive.size(mImageSize) >= 4 * VirtualDrive.GB
                    ? R.string.usb_virtual_fat_limit : R.string.usb_virtual_bad_size;
        else if (mCreateIndex >= 3 && !VirtualDrive.label(mImageLabel, mImageFs)) error = R.string.usb_virtual_bad_label;
        if (error == 0) { mNote.setText(""); return true; }
        mNote.setText(error); return false;
    }
    private interface TextResult { void finished(String text); }
    private void padText(String title, String initial, boolean numeric, TextResult result) {
        showDialog(GamepadTextDialog.create(this, title, initial, numeric,
                value -> mHandler.post(() -> result.finished(value))));
    }
    private void showDialog(AlertDialog dialog) {
        if (mDialog != null) return;
        mDialog = dialog;
        dialog.setOnDismissListener(d -> { if (mDialog == dialog) mDialog = null; });
        dialog.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) dialog.dismiss();
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
                        (d, which) -> mHandler.post(() -> warn(offer, ro, cdrom)))
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

    private void share(UsbDrive.Offer offer, boolean ro, boolean cdrom) {
        if (mUsbMode.chargingBlocksDevice()) {
            Toast.makeText(this, R.string.charging_refused, Toast.LENGTH_LONG).show();
            return;
        }
        // Recheck after the dialog: a removed card or changed layout is a new offer.
        UsbDrive.Offer fresh = null;
        final List<UsbDrive.Offer> offers = UsbDriveControl.scan(this);
        offers.addAll(UsbDriveControl.images().offers);
        for (UsbDrive.Offer o : offers) {
            if (o.enabled() && o.identity().equals(offer.identity())) fresh = o;
        }
        if (fresh == null || UsbDriveControl.state().isEmpty() || UsbDriveControl.busy()) {
            Toast.makeText(this, R.string.usb_drive_failed, Toast.LENGTH_LONG).show();
            return;
        }
        mUsbMode.setConfirmedThisBoot();
        UsbDeviceControl.start(this, mFunctions, fresh, ro, cdrom);
        refresh();
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
