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
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

public class ControllerSessionActivity extends Activity {
    private final Handler mHandler = new Handler();
    private TextView mStatus, mFunctions, mRouting, mEject, mLink, mSerial;
    private FunctionView mFunctionView;
    private LinearLayout mPage, mCompact;
    private UsbAudio mAudio;
    private AlertDialog mTextDialog;
    private volatile boolean mPickingFile;
    private int mDialogButtons;
    private LinearLayout mControls;
    private Button mDark;
    private KeyboardModel mKeyboardModel;
    private KeyboardView mKeyboard;
    private KeyboardChannel mChannel;
    private boolean mResumed, mFocused, mLeaving, mLocked, mHintQueued;
    private int mMask;
    private String mLastScreen = "on";
    private float mSavedBrightness = -1;
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
            final boolean typing = (mMask & UsbFunctions.KEYBOARD) != 0 && UsbDeviceControl.keyboardScreen();
            if (mKeyboardModel != null) synchronized (mKeyboardModel) {
                if (mKeyboardModel.stopRequested()) leave();
            }
            if (mKeyboardModel != null) synchronized (mKeyboardModel) {
                if (typing && mKeyboardModel.fileRequested() && !mPickingFile) pickText();
            }
            final String functions = functionNames();
            final String connection = getString("sharing".equals(state) ? connected()
                    ? R.string.controller_connected : R.string.controller_disconnected : R.string.usb_device_preparing);
            final String status = connection + "\n" + battery();
            mFunctions.setText(functions);
            mEject.setVisibility((mMask & UsbFunctions.STORAGE) != 0 ? View.VISIBLE : View.GONE);
            mStatus.setText(hint ? getString(R.string.usb_device_wake) : connection + "\n" + battery());
            final boolean routable = (mMask & UsbFunctions.KEYBOARD) != 0
                    && (mMask & (UsbFunctions.CONTROLLERS | UsbFunctions.MEDIA | UsbFunctions.MIDI)) != 0;
            mRouting.setVisibility(routable ? View.VISIBLE : View.GONE);
            mRouting.setText(typing ? R.string.usb_device_route_keyboard : R.string.usb_device_route_inputs);
            mKeyboard.setVisibility(typing && !hint ? View.VISIBLE : View.GONE);
            mFunctions.setVisibility(View.VISIBLE);
            mStatus.setVisibility(typing && !hint ? View.GONE : View.VISIBLE);
            mControls.setVisibility((typing || (mMask & (UsbFunctions.MEDIA | UsbFunctions.MIDI)) != 0) && !hint ? View.GONE : View.VISIBLE);
            mDark.setVisibility(typing || !"sharing".equals(state) ? View.GONE : View.VISIBLE);
            mDark.setEnabled(!hint);
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
            final boolean pads = !typing && !hint && (mMask & (UsbFunctions.MEDIA | UsbFunctions.MIDI)) != 0;
            if (pads && mFunctionView == null) {
                mFunctionView = new FunctionView(ControllerSessionActivity.this,(mMask & UsbFunctions.MIDI) != 0);
                mPage.addView(mFunctionView,4,new LinearLayout.LayoutParams(-1,0,1));
            }
            mCompact.setVisibility(pads ? View.VISIBLE : View.GONE);
            if (mFunctionView != null) { mFunctionView.setVisibility(pads ? View.VISIBLE : View.GONE); mFunctionView.invalidate(); }
            mLink.setVisibility((mMask & (UsbFunctions.NETWORK | UsbFunctions.RNDIS)) != 0 ? View.VISIBLE : View.GONE);
            mLink.setText(getString(R.string.usb_device_link_status,getString("up".equals(SystemProperties.get(
                    UsbDeviceControl.PREFIX + "link")) ? R.string.usb_device_link_up : R.string.usb_device_link_down),
                    SystemProperties.get(UsbDeviceControl.PREFIX + "peer",getString(R.string.usb_device_link_wait))));
            mSerial.setVisibility((mMask & (UsbFunctions.SERIAL | UsbFunctions.SOUND)) != 0 ? View.VISIBLE : View.GONE);
            String extra = SystemProperties.get(UsbDeviceControl.PREFIX + "serial_text");
            if ((mMask & UsbFunctions.SOUND) != 0) {
                String audio = SystemProperties.get(UsbDeviceControl.PREFIX + "audio");
                extra += getString(R.string.usb_device_audio_status,getString("playing".equals(audio)
                        ? R.string.usb_device_audio_playing : "error".equals(audio)
                        ? R.string.usb_device_audio_error : R.string.usb_device_audio_wait));
                if (mAudio == null && mResumed) mAudio = new UsbAudio();
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
            mHandler.postDelayed(this, 100);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().hide();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        final LinearLayout page = new LinearLayout(this); mPage = page;
        page.setOrientation(LinearLayout.VERTICAL); page.setGravity(Gravity.CENTER);
        mFunctions = text(""); mFunctions.setTextSize(18); page.addView(mFunctions);
        mStatus = text(""); page.addView(mStatus);
        mEject = text(getString(R.string.usb_drive_eject)); page.addView(mEject);
        mRouting = text(""); page.addView(mRouting);
        mLink = text(""); page.addView(mLink);
        mSerial = text(""); page.addView(mSerial);
        final String layout = PreferenceManager.getDefaultSharedPreferences(this).getString(
                "keyboard_layout", KeyboardModel.defaultLayout(java.util.Locale.getDefault()));
        mKeyboardModel = new KeyboardModel(layout);
        mKeyboard = new KeyboardView(this, mKeyboardModel);
        page.addView(mKeyboard, new LinearLayout.LayoutParams(-1, 0, 1));
        mControls = new LinearLayout(this); mControls.setOrientation(LinearLayout.VERTICAL);
        mControls.setGravity(Gravity.CENTER);
        mControls.addView(text(getString(R.string.usb_device_exit)));
        mControls.addView(text(getString(R.string.usb_device_dark_shortcut)));
        final Button stop = new Button(this); stop.setText(R.string.usb_device_stop);
        stop.setOnClickListener(v -> leave()); mControls.addView(stop);
        mDark = new Button(this); mDark.setText(R.string.usb_device_dark);
        mDark.setOnClickListener(v -> { if (lock()) UsbDeviceControl.dark(); }); mControls.addView(mDark);
        final SeekBar brightness = new SeekBar(this);
        brightness.setMax(100); brightness.setProgress(ControllerControl.brightness());
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) { ControllerControl.setBrightness(value); brightness(Math.max(0.02f, value / 100f)); }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        mControls.addView(text(getString(R.string.controller_brightness)));
        mControls.addView(brightness, new LinearLayout.LayoutParams(-1, -2));
        page.addView(mControls);
        mCompact = new LinearLayout(this); mCompact.setGravity(Gravity.CENTER);
        Button padStop = new Button(this); padStop.setText(R.string.usb_device_stop); padStop.setOnClickListener(v -> leave());
        Button padDark = new Button(this); padDark.setText(R.string.usb_device_dark);
        padDark.setOnClickListener(v -> { if (lock()) UsbDeviceControl.dark(); });
        mCompact.addView(padStop); mCompact.addView(padDark); mCompact.setVisibility(View.GONE); page.addView(mCompact);
        setContentView(page); stop.requestFocus();
    }
    private TextView text(String value) {
        final TextView text = new TextView(this); text.setText(value);
        text.setGravity(Gravity.CENTER); text.setPadding(8, 3, 8, 3); return text;
    }
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
                UsbFunctions.SOUND,UsbFunctions.NETWORK,UsbFunctions.PS3,UsbFunctions.GAMECUBE,UsbFunctions.XBOX,UsbFunctions.RNDIS};
        final int[] titles = {R.string.usb_device_media,R.string.usb_device_switch,R.string.usb_device_midi,
                R.string.usb_device_serial,R.string.usb_device_sound,R.string.usb_device_network,
                R.string.usb_device_ps3,R.string.usb_device_gamecube,R.string.usb_device_xbox,R.string.usb_device_rndis};
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
    private void closeKeyboard() { if (mChannel != null) { mChannel.close(); mChannel = null; } }
    private void updateKeyboard() {
        if (mResumed && (mFocused || mPickingFile) && !mLeaving && (mMask & UsbFunctions.KEYBOARD) != 0
                && UsbDeviceControl.keyboardScreen() && "on".equals(UsbDeviceControl.screen())) {
            if (mChannel == null) mChannel = new KeyboardChannel(mKeyboardModel,mKeyboard,this::dialogFrame);
        } else closeKeyboard();
    }
    private boolean dialogFrame(byte[] frame) {
        if (!mPickingFile) { mDialogButtons = 0; return false; }
        int buttons = (frame[1] & 255) | ((frame[2] & 255) << 8) | ((frame[3] & 255) << 16);
        int previous = mDialogButtons; mDialogButtons = buttons;
        final int[] bits = {0,1,2,3,12,11,4};
        final int[] keys = {KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_DPAD_CENTER};
        for (int i = 0; i < bits.length; i++) if (((buttons ^ previous) & (1 << bits[i])) != 0) {
            final int key = keys[i], action = (buttons & (1 << bits[i])) != 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
            mHandler.post(() -> { if (mTextDialog != null) mTextDialog.dispatchKeyEvent(new KeyEvent(action,key)); });
        }
        return true;
    }
    private void textDialog(AlertDialog dialog) {
        mTextDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (mTextDialog == dialog) {
                mTextDialog = null; mPickingFile = false;
                synchronized (mKeyboardModel) { mKeyboardModel.reset(); }
                updateKeyboard();
            }
        });
        dialog.show();
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
        super.onResume(); mResumed = true; updateKeyboard(); mHandler.post(mPoll);
    }
    @Override public void onPause() {
        mResumed = false; if (mAudio != null) { mAudio.close(); mAudio = null; }
        if (mTextDialog != null) mTextDialog.dismiss();
        SystemProperties.set(UsbDeviceControl.PREFIX + "hint_ready", "0"); closeKeyboard(); mHandler.removeCallbacks(mPoll); super.onPause();
    }
    @Override public void onStop() {
        if (!isChangingConfigurations()) { UsbDriveControl.stop(); unlock(); }
        super.onStop();
    }
    @Override public void onDestroy() {
        if (!isChangingConfigurations()) unlock();
        super.onDestroy();
    }
}
