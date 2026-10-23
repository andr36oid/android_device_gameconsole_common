package org.andr36oid.usbmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.SystemClock;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

public class ControllerSessionActivity extends Activity {
    private final Handler mHandler = new Handler();
    private TextView mStatus, mFunctions, mRouting, mEject, mLink, mSerial;
    private FrameLayout mKeyboardHost;
    private ProgressBar mPreparing;
    private TextView mHint;
    private UsbAudio mAudio;
    private Soundboard mBoard;
    private AlertDialog mTextDialog;
    private volatile boolean mPickingFile, mDestroyed;
    private int mDialogButtons;
    private boolean mDiscHeld;
    private KeyboardModel mKeyboardModel;
    private KeyboardView mKeyboard;
    private KeyboardChannel mChannel;
    private ControllerInputChannel mInputChannel;
    private boolean mResumed, mFocused, mLeaving, mLocked, mHintQueued;
    private int mMask;
    private String mLastScreen = "on";
    private float mSavedBrightness = -1;
    private String mBattery = "";
    private long mNextBattery;
    private final Runnable mPoll = new Runnable() {
        @Override public void run() {
            mMask = UsbDeviceControl.functions();
            final String state = UsbDriveControl.state();
            if (ControllerMode.finished(state, ControllerControl.request())) {
                closeKeyboard(); unlock(); finish(); return;
            }
            final String screen = UsbDeviceControl.screen();
            final boolean dark = "dark".equals(screen), hint = "hint".equals(screen);
            if (hint || dark) {
                if (!lock()) { leave(); return; }
            } else {
                unlock();
                if (!"on".equals(mLastScreen)) UsbDeviceControl.clearDarkRequest();
            }
            synchronized (mKeyboardModel) { mKeyboardModel.setFunctions(mMask, canChangeDisc()
                    && "1".equals(SystemProperties.get(UsbDeviceControl.PREFIX + "cdrom"))); }
            configurePads();
            final boolean macro = mKeyboardModel.macro != null;
            final boolean board = mBoard != null;
            final boolean typing = (mMask & UsbFunctions.KEYBOARD) != 0 && UsbDeviceControl.keyboardScreen();
            if (mKeyboardModel != null) synchronized (mKeyboardModel) {
                if (mKeyboardModel.stopRequested()) leave();
            }
            if (mKeyboardModel != null) synchronized (mKeyboardModel) {
                if (typing && mKeyboardModel.discRequested() && canChangeDisc() && !mPickingFile) pickDisc();
                if (typing && mKeyboardModel.fileRequested() && !mPickingFile) pickText();
            }
            if (mBoard != null && mBoard.chooseRequested() && !mPickingFile) pickSound();
            final String functions = functionNames();
            final String connection = getString("stopping".equals(state) ? R.string.usb_emulator_stopping : "sharing".equals(state) ? connected()
                    ? R.string.controller_connected : R.string.controller_disconnected : R.string.usb_device_preparing);
            if (SystemClock.elapsedRealtime() >= mNextBattery) {
                mBattery = battery(); mNextBattery = SystemClock.elapsedRealtime() + 30000;
            }
            final String status = connection + "\n" + mBattery;
            mFunctions.setText(functions);
            mPreparing.setVisibility(!"sharing".equals(state) ? View.VISIBLE : View.GONE);
            mEject.setVisibility((mMask & UsbFunctions.STORAGE) != 0 ? View.VISIBLE : View.GONE);
            mEject.setText(canChangeDisc() && (!typing || macro) ? R.string.usb_disc_hint : R.string.usb_drive_eject);
            mStatus.setText(hint ? getString(R.string.usb_device_dark_hint) : dark ? getString(R.string.usb_device_wake) : status);
            final boolean routable = (mMask & UsbFunctions.KEYBOARD) != 0
                    && (mMask & (UsbFunctions.CONTROLLERS | UsbFunctions.MEDIA | UsbFunctions.MIDI)) != 0;
            mRouting.setVisibility(routable && !macro ? View.VISIBLE : View.GONE);
            mRouting.setText(typing ? R.string.usb_device_route_keyboard : R.string.usb_device_route_inputs);
            mKeyboardHost.setVisibility(typing && !macro && !hint ? View.VISIBLE : View.GONE);
            mFunctions.setVisibility(hint ? View.GONE : View.VISIBLE);
            mHint.setVisibility(hint ? View.GONE : View.VISIBLE);
            mStatus.setVisibility(typing && !macro && !hint ? View.GONE : View.VISIBLE);
            if (typing) {
                String typingStatus = status;
                synchronized (mKeyboardModel) {
                    if (mKeyboardModel.typer.active()) {
                        int countdown = mKeyboardModel.typer.countdown(SystemClock.uptimeMillis());
                        typingStatus = countdown > 0 ? getString(R.string.keyboard_type_countdown,countdown)
                                : getString(R.string.keyboard_type_progress,mKeyboardModel.typer.progress(),mKeyboardModel.typer.total());
                    }
                }
                mKeyboard.setStatus(typingStatus);
            }
            if (macro || board) {
                mRouting.setVisibility(hint ? View.GONE : View.VISIBLE);
                mRouting.setText(macro ? getString(R.string.usb_macro_legend,mKeyboardModel.macro.profile.name) : getString(R.string.usb_sound_legend));
            }
            if (hint) { mRouting.setVisibility(View.GONE); mEject.setVisibility(View.GONE); }
            mLink.setVisibility(!hint && (mMask & (UsbFunctions.NETWORK | UsbFunctions.RNDIS)) != 0 ? View.VISIBLE : View.GONE);
            mLink.setText(getString(R.string.usb_device_link_status,getString("up".equals(SystemProperties.get(
                    UsbDeviceControl.PREFIX + "link")) ? R.string.usb_device_link_up : R.string.usb_device_link_down),
                    SystemProperties.get(UsbDeviceControl.PREFIX + "peer",getString(R.string.usb_device_link_wait))));
            mSerial.setVisibility(!hint && (mMask & (UsbFunctions.SERIAL | UsbFunctions.SOUND)) != 0 ? View.VISIBLE : View.GONE);
            String extra = SystemProperties.get(UsbDeviceControl.PREFIX + "serial_text");
            if ((mMask & UsbFunctions.SOUND) != 0) {
                String audio = SystemProperties.get(UsbDeviceControl.PREFIX + "audio");
                extra += getString(R.string.usb_device_audio_status,getString("playing".equals(audio)
                        ? R.string.usb_device_audio_playing : "error".equals(audio)
                        ? R.string.usb_device_audio_error : R.string.usb_device_audio_wait));
                if (mAudio == null && mResumed) mAudio = new UsbAudio(mBoard);
            }
            mSerial.setText(extra);
            if (hint && !mHintQueued) {
                mHintQueued = true;
                // Give the locked page a frame to draw the hint before its two seconds start.
                mHandler.postDelayed(() -> {
                    if (mResumed && mLocked && UsbDeviceLock.locked(ControllerSessionActivity.this)
                            && "hint".equals(UsbDeviceControl.screen()))
                        SystemProperties.set(UsbDeviceControl.PREFIX + "hint_ready", "1");
                    else mHintQueued = false;
                }, 100);
            } else if (!hint && (mHintQueued || "hint".equals(mLastScreen))) {
                mHintQueued = false;
                SystemProperties.set(UsbDeviceControl.PREFIX + "hint_ready", "0");
            }
            getWindow().getDecorView().setAlpha(dark ? 0 : 1);
            if (dark) {
                if (!"dark".equals(mLastScreen)) mSavedBrightness = getWindow().getAttributes().screenBrightness;
                brightness(0);
            } else if ("dark".equals(mLastScreen)) brightness(mSavedBrightness);
            else if ("sharing".equals(state) && ControllerControl.brightness() != 15) brightness(ControllerControl.brightness() / 100f);
            mLastScreen = screen;
            updateKeyboard();
            mHandler.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().hide();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.usb_device_session);
        mFunctions = findViewById(R.id.session_functions); mStatus = findViewById(R.id.session_status);
        mEject = findViewById(R.id.session_eject); mRouting = findViewById(R.id.session_routing);
        mLink = findViewById(R.id.session_link); mSerial = findViewById(R.id.session_serial);
        mKeyboardHost = findViewById(R.id.session_keyboard); mPreparing = findViewById(R.id.session_progress);
        mHint = findViewById(R.id.session_hint);
        final String layout = PreferenceManager.getDefaultSharedPreferences(this).getString(
                "keyboard_layout", KeyboardModel.defaultLayout(java.util.Locale.getDefault()));
        mMask = UsbDeviceControl.functions();
        mKeyboardModel = new KeyboardModel(layout);
        if ((mMask & UsbFunctions.KEYBOARD) != 0 && "1".equals(SystemProperties.get(UsbDeviceControl.PREFIX + "macro"))) {
            MacroProfiles all = MacroSettings.load(this); mKeyboardModel.macro = new MacroProfiles.Player(all.profiles.get(MacroSettings.selected(this,all)));
        }
        if ((mMask & UsbFunctions.SOUNDBOARD) != 0) mBoard = new Soundboard(this);
        mKeyboard = new KeyboardView(this, mKeyboardModel);
        mKeyboardHost.addView(mKeyboard, new FrameLayout.LayoutParams(-1, -1));
    }
    private void configurePads() {
        if (mKeyboardModel.macro == null && (mMask & UsbFunctions.KEYBOARD) != 0 && "1".equals(SystemProperties.get(UsbDeviceControl.PREFIX + "macro"))) {
            MacroProfiles all = MacroSettings.load(this); mKeyboardModel.macro = new MacroProfiles.Player(all.profiles.get(MacroSettings.selected(this,all)));
        }
        if (mBoard == null && (mMask & UsbFunctions.SOUNDBOARD) != 0) mBoard = new Soundboard(this);
    }
    private TextView text(String value) {
        final TextView text = new TextView(this); text.setText(value);
        text.setGravity(Gravity.CENTER); text.setPadding(dp(8), dp(3), dp(8), dp(3)); return text;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void brightness(float value) {
        final WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = value; getWindow().setAttributes(params);
    }
    private String battery() {
        final Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        final int scale = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
        final int level = scale <= 0 ? 0 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale;
        return getString(level <= 15 ? R.string.usb_device_low_battery : R.string.usb_device_battery, level);
    }
    private String functionNames() {
        final ArrayList<String> names = new ArrayList<>();
        if ((mMask & UsbFunctions.STORAGE) != 0) names.add(getString(SystemProperties.getInt(
                UsbDeviceControl.PREFIX + "cdrom", 0) == 1 ? R.string.usb_device_cd : R.string.usb_device_disk,
                SystemProperties.get(UsbDeviceControl.PREFIX + "name")));
        if ((mMask & UsbFunctions.XINPUT) != 0) names.add(getString(R.string.controller_xinput));
        if ((mMask & UsbFunctions.GAMEPAD) != 0) names.add(getString(R.string.controller_gamepad));
        if ((mMask & UsbFunctions.KEYBOARD) != 0) names.add(getString(R.string.usb_device_keyboard));
        if ((mMask & UsbFunctions.MOUSE) != 0) names.add(getString(R.string.usb_device_mouse));
        if ((mMask & UsbFunctions.ADB) != 0) names.add(getString(R.string.usb_device_adb));
        final int[] bits = {UsbFunctions.MEDIA,UsbFunctions.SWITCH,UsbFunctions.MIDI,UsbFunctions.SERIAL,
                UsbFunctions.SOUND,UsbFunctions.NETWORK,UsbFunctions.PS3,UsbFunctions.GAMECUBE,UsbFunctions.XBOX,UsbFunctions.RNDIS,UsbFunctions.PS4,UsbFunctions.SOUNDBOARD};
        final int[] titles = {R.string.usb_device_media,R.string.usb_device_switch,R.string.usb_device_midi,
                R.string.usb_device_serial,R.string.usb_device_sound,R.string.usb_device_network,
                R.string.usb_device_ps3,R.string.usb_device_gamecube,R.string.usb_device_xbox,R.string.usb_device_rndis,R.string.usb_ds4_title,R.string.usb_sound_title};
        for (int i = 0; i < bits.length; i++) if ((mMask & bits[i]) != 0) names.add(getString(titles[i]));
        return android.text.TextUtils.join(" · ", names);
    }
    private boolean connected() {
        try (BufferedReader in = new BufferedReader(new FileReader("/sys/class/udc/ff300000.usb/state"))) {
            return "configured".equals(in.readLine());
        } catch (IOException e) { return false; }
    }
    private boolean lock() {
        if (mLocked) return true;
        mLocked = UsbDeviceLock.lock(this);
        if (!mLocked) leave();
        return mLocked;
    }
    private void unlock() {
        if (!mLocked) return;
        try { stopLockTask(); } catch (SecurityException e) { }
        UsbDeviceLock.restore(this);
        mLocked = false;
    }
    private void leave() { mLeaving = true; closeKeyboard(); UsbDriveControl.stop(); }
    private void closeKeyboard() {
        if (mChannel != null) { mChannel.close(); mChannel = null; }
        if (mInputChannel != null) { mInputChannel.close(); mInputChannel = null; }
    }
    private void updateKeyboard() {
        if (mResumed && (mFocused || mPickingFile) && !mLeaving && ((mMask & UsbFunctions.KEYBOARD) != 0 && UsbDeviceControl.keyboardScreen())
                && ("on".equals(UsbDeviceControl.screen()) || "dark".equals(UsbDeviceControl.screen()) && (mBoard != null || mKeyboardModel.macro != null))) {
            if (mInputChannel != null) { mInputChannel.close(); mInputChannel = null; }
            if (mChannel == null) mChannel = new KeyboardChannel(mKeyboardModel,mKeyboard,this::dialogFrame);
        } else {
            if (mChannel != null) { mChannel.close(); mChannel = null; }
            if (mResumed && (mFocused || mPickingFile) && !mLeaving && (mMask & UsbFunctions.KEYBOARD) == 0
                    && (mBoard != null || canChangeDisc() || mPickingFile)
                    && ("on".equals(UsbDeviceControl.screen()) || mBoard != null)) {
                if (mInputChannel == null) {
                    mInputChannel = new ControllerInputChannel() {
                        @Override byte[] reply(byte[] frame) {
                            synchronized (mKeyboardModel) {
                                final int action = dialogFrame(frame);
                                final byte[] packet = new byte[16]; packet[0] = 1;
                                packet[14] = (byte) (action == 2 ? 1 : 0);
                                return packet;
                            }
                        }
                        @Override void released() { }
                    };
                    mInputChannel.start();
                }
            } else if (mInputChannel != null) { mInputChannel.close(); mInputChannel = null; }
        }
    }
    private int dialogFrame(byte[] frame) {
        if (!mPickingFile) {
            int buttons = (frame[1] & 255) | ((frame[2] & 255) << 8);
            int discButtons = (1 << 5) | (1 << (mBoard == null ? 14 : 13));
            boolean disc = (buttons & discButtons) == discButtons;
            if (disc && !mDiscHeld && ((mMask & UsbFunctions.KEYBOARD) == 0 || mKeyboardModel.macro != null) && canChangeDisc()) mHandler.post(this::pickDisc);
            mDiscHeld = disc;
            if (mBoard != null) { mBoard.update(frame); return 1; }
            mDialogButtons = 0; return (mMask & UsbFunctions.KEYBOARD) == 0 ? 1 : 0;
        }
        navigateFrame(frame); return 2;
    }
    private void navigateFrame(byte[] frame) {
        int buttons = (frame[1] & 255) | ((frame[2] & 255) << 8) | ((frame[3] & 255) << 16);
        int previous = mDialogButtons; mDialogButtons = buttons;
        final int[] bits = {0,1,2,3,12,11,4};
        final int[] keys = {KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_DPAD_CENTER};
        for (int i = 0; i < bits.length; i++) if (((buttons ^ previous) & (1 << bits[i])) != 0) {
            final int key = keys[i], action = (buttons & (1 << bits[i])) != 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
            mHandler.post(() -> { if (mTextDialog != null) mTextDialog.dispatchKeyEvent(new KeyEvent(action,key)); });
        }
    }
    private void textDialog(AlertDialog dialog) {
        mTextDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (mTextDialog == dialog) {
                mTextDialog = null; mPickingFile = false;
                if (mBoard != null) mBoard.rearm();
                synchronized (mKeyboardModel) { mKeyboardModel.reset(); }
                updateKeyboard();
            }
        });
        dialog.show();
    }
    private boolean canChangeDisc() {
        return (mMask & UsbFunctions.STORAGE) != 0 && "file".equals(SystemProperties.get(UsbDeviceControl.PREFIX + "target"))
                && "1".equals(SystemProperties.get(UsbDeviceControl.PREFIX + "ro"));
    }
    private void pickDisc() {
        if (mPickingFile || !canChangeDisc()) return;
        mPickingFile = true; UsbDriveControl.scanImages();
        mHandler.postDelayed(() -> {
            if (!mResumed || mLeaving) { mPickingFile = false; return; }
            final boolean cd = "1".equals(SystemProperties.get(UsbDeviceControl.PREFIX + "cdrom"));
            new Thread(() -> {
                final ArrayList<UsbDrive.Offer> offers = new ArrayList<>();
                try {
                    for (UsbDrive.Offer offer : UsbDriveControl.images().offers) if (!cd || offer.cdromPossible()) offers.add(offer);
                } catch (RuntimeException e) { }
                if (mDestroyed) return;
                mHandler.post(() -> {
                    if (!mResumed || mLeaving || mDestroyed || !canChangeDisc()) { mPickingFile = false; return; }
                    final String[] names = new String[offers.size()]; for (int i = 0; i < names.length; i++) names[i] = offers.get(i).label;
                    textDialog(new AlertDialog.Builder(this).setTitle(R.string.usb_disc_change).setItems(names,(d,which) -> {
                        UsbDrive.Offer offer = offers.get(which);
                        SystemProperties.set(UsbDeviceControl.PREFIX + "next_image",offer.node);
                        SystemProperties.set(UsbDeviceControl.PREFIX + "next_stamp",offer.stamp);
                        SystemProperties.set(UsbDeviceControl.PREFIX + "change","start");
                        mHandler.postDelayed(() -> {
                            if (mResumed && "error".equals(SystemProperties.get(UsbDeviceControl.PREFIX + "change")))
                                android.widget.Toast.makeText(this,R.string.usb_disc_failed,android.widget.Toast.LENGTH_LONG).show();
                        },1500);
                    }).setNegativeButton(android.R.string.cancel,null).create());
                });
            },"USB disc list").start();
        },500);
    }
    private void pickSound() {
        if (mPickingFile || mBoard == null) return;
        if (mBoard.hasMicrophone()) {
            mPickingFile = true;
            textDialog(new AlertDialog.Builder(this).setItems(new String[] {getString(R.string.usb_sound_choose),getString(R.string.usb_sound_mic)},(d,which) -> {
                if (which == 0) { mTextDialog = null; soundPads(); }
                else if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) mBoard.microphone(!mBoard.microphone());
                else android.widget.Toast.makeText(this,R.string.usb_sound_mic,android.widget.Toast.LENGTH_LONG).show();
            }).setNegativeButton(android.R.string.cancel,null).create());
        } else soundPads();
    }
    private void soundPads() {
        mPickingFile = true;
        String[] pads = new String[12]; for (int i = 0; i < 12; i++) pads[i] = MacroProfiles.CAPS[i] + " → " + mBoard.labels[i];
        textDialog(new AlertDialog.Builder(this).setTitle(R.string.usb_sound_choose).setItems(pads,(d,pad) -> {
            mTextDialog = null;
            new Thread(() -> {
                String[] files;
                try { files = mBoard.choices(); } catch (IOException e) { files = new String[0]; }
                final String[] choices = new String[files.length + 1]; choices[0] = getString(R.string.usb_sound_default); System.arraycopy(files,0,choices,1,files.length);
                mHandler.post(() -> {
                    if (!mResumed) { mPickingFile = false; return; }
                    textDialog(new AlertDialog.Builder(this).setTitle(MacroProfiles.CAPS[pad]).setItems(choices,(dialog,which) -> {
                        new Thread(() -> {
                            try { mBoard.load(pad,which == 0 ? "" : choices[which]); }
                            catch (IOException | RuntimeException e) { mHandler.post(() -> android.widget.Toast.makeText(this,R.string.usb_sound_failed,android.widget.Toast.LENGTH_LONG).show()); }
                        },"USB pad sound").start();
                    }).setNegativeButton(android.R.string.cancel,null).create());
                });
            },"USB sound list").start();
        }).setNegativeButton(android.R.string.cancel,null).create());
    }
    private void pickText() {
        mPickingFile = true;
        synchronized (mKeyboardModel) { mKeyboardModel.reset(); }
        new Thread(() -> {
            try {
                final String inventory = TextFiles.request("L\n" + getString(R.string.keyboard_type_readme));
                final String[] names = inventory.isEmpty() ? new String[0] : inventory.substring(1).split("\n");
                mHandler.post(() -> {
                    if (!mResumed || mLeaving) { mPickingFile = false; return; }
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(R.string.keyboard_type_file)
                            .setNegativeButton(android.R.string.cancel,null);
                    if (names.length == 0) dialog.setMessage(R.string.keyboard_type_empty);
                    else dialog.setItems(names,(d,which) -> { mTextDialog = null; previewText(names[which]); });
                    textDialog(dialog.create());
                });
            } catch (IOException e) { textError(); }
        },"USB text files").start();
    }
    private void previewText(String name) {
        new Thread(() -> {
            try {
                final String text = TextFiles.request("R\n" + name);
                final TextTyper.Plan plan = new TextTyper.Plan(text,mKeyboardModel.layout());
                mHandler.post(() -> {
                    if (!mResumed || mLeaving) { mPickingFile = false; return; }
                    final android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                    final String[] speeds = {getString(R.string.keyboard_type_slow),getString(R.string.keyboard_type_normal),
                            getString(R.string.keyboard_type_fast)};
                    final int[] speed = {prefs.getInt("type_speed",1)};
                    final LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
                    final TextView preview = text(getString(R.string.keyboard_type_preview,plan.characters,name,
                            plan.unsupported,text.substring(0,Math.min(400,text.length()))));
                    final android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(preview);
                    page.addView(scroll,new LinearLayout.LayoutParams(-1,220));
                    final Button rate = new Button(this); rate.setText(speeds[speed[0]]);
                    rate.setOnClickListener(v -> { speed[0] = (speed[0] + 1) % 3; rate.setText(speeds[speed[0]]); }); page.addView(rate);
                    final AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.keyboard_type_file).setView(page)
                            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.keyboard_type_confirm,null).create();
                    textDialog(dialog);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                        mTextDialog = null; dialog.dismiss(); mPickingFile = false;
                        prefs.edit().putInt("type_speed",speed[0]).apply();
                        synchronized (mKeyboardModel) { mKeyboardModel.reset(); mKeyboardModel.typer.start(plan,speed[0],SystemClock.uptimeMillis()); }
                        updateKeyboard();
                    });
                });
            } catch (IOException e) { textError(); }
        },"USB text preview").start();
    }
    private void textError() {
        mHandler.post(() -> {
            if (!mResumed || mLeaving) { mPickingFile = false; return; }
            textDialog(new AlertDialog.Builder(this).setTitle(R.string.keyboard_type_file)
                    .setMessage(R.string.keyboard_type_failed).setPositiveButton(android.R.string.ok,null).create());
        });
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); mFocused = focused; updateKeyboard();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        return !"on".equals(UsbDeviceControl.screen()) || super.dispatchTouchEvent(event);
    }
    @Override public void onBackPressed() { if ("on".equals(UsbDeviceControl.screen())) leave(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!"on".equals(UsbDeviceControl.screen())) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_POWER && event.getAction() == KeyEvent.ACTION_DOWN) leave();
        return true;
    }
    @Override public void onResume() {
        super.onResume(); SystemProperties.set(UsbDeviceControl.PREFIX + "companion","0"); mResumed = true; updateKeyboard(); mHandler.post(mPoll);
    }
    @Override public void onPause() {
        if (mBoard != null) mBoard.reset();
        mResumed = false; if (mAudio != null) { mAudio.close(); mAudio = null; }
        if (mTextDialog != null) mTextDialog.dismiss();
        SystemProperties.set(UsbDeviceControl.PREFIX + "hint_ready", "0"); closeKeyboard(); mHandler.removeCallbacks(mPoll); super.onPause();
    }
    @Override public void onStop() {
        if (!isChangingConfigurations() && !"1".equals(SystemProperties.get(UsbDeviceControl.PREFIX + "companion"))) { UsbDriveControl.stop(); unlock(); }
        super.onStop();
    }
    @Override public void onDestroy() {
        mDestroyed = true; mHandler.removeCallbacksAndMessages(null);
        if (mBoard != null) mBoard.close();
        if (!isChangingConfigurations()) unlock();
        super.onDestroy();
    }
}
