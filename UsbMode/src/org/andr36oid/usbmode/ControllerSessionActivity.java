package org.andr36oid.usbmode;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class ControllerSessionActivity extends Activity {
    private final Handler mHandler = new Handler();
    private TextView mStatus;
    private KeyboardModel mKeyboardModel;
    private KeyboardView mKeyboard;
    private KeyboardChannel mChannel;
    private boolean mResumed, mFocused;
    private boolean mLeaving;
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            dim(ControllerControl.brightness());
            final String state = UsbDriveControl.state();
            if (mKeyboardModel != null) synchronized (mKeyboardModel) {
                if (mKeyboardModel.stopRequested()) leave();
            }
            if (ControllerMode.finished(state, ControllerControl.request())) {
                mStatus.setText("error".equals(state) ? R.string.controller_failed : R.string.controller_stopped);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (mKeyboard != null) { closeKeyboard(); finish(); return; }
            } else {
                mStatus.setText("sharing".equals(state) ? connected()
                        ? R.string.controller_connected : R.string.controller_disconnected
                        : R.string.controller_preparing);
            }
            if (mKeyboard != null) mKeyboard.setStatus(mStatus.getText().toString());
            if (mLeaving && ControllerMode.finished(state, ControllerControl.request())) { finish(); return; }
            mHandler.postDelayed(this, 250);
        }
    };

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        dim(15);
        if ("keyboard".equals(ControllerControl.target())) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            mStatus = new TextView(this);
            final String layout = PreferenceManager.getDefaultSharedPreferences(this).getString(
                    "keyboard_layout", KeyboardModel.defaultLayout(java.util.Locale.getDefault()));
            mKeyboardModel = new KeyboardModel(layout);
            mKeyboard = new KeyboardView(this, mKeyboardModel);
            setContentView(mKeyboard);
            return;
        }
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        final int gap = (int) (16 * getResources().getDisplayMetrics().density);
        page.setPadding(gap, gap, gap, gap);
        final TextView title = text(getString(R.string.controller_active,
                getString(ControllerControl.TITLES[ControllerMode.index(ControllerControl.target())])));
        title.setTextSize(22);
        page.addView(title);
        mStatus = text(getString(R.string.controller_preparing));
        page.addView(mStatus);
        page.addView(text(getString(R.string.controller_exit)));
        page.addView(text(getString(R.string.controller_battery)));
        final Button stop = new Button(this);
        stop.setText(R.string.controller_stop);
        stop.setOnClickListener(v -> leave());
        page.addView(stop);
        page.addView(text(getString(R.string.controller_brightness)));
        final SeekBar brightness = new SeekBar(this);
        brightness.setMax(100); brightness.setProgress(15);
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) ControllerControl.setBrightness(value);
                dim(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        page.addView(brightness, new LinearLayout.LayoutParams(-1, -2));
        setContentView(page);
        stop.requestFocus();
    }

    private TextView text(String value) {
        final TextView text = new TextView(this);
        text.setText(value); text.setGravity(Gravity.CENTER); text.setPadding(0, 8, 0, 8);
        return text;
    }

    private void dim(int percent) {
        final WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = Math.max(0.02f, percent / 100f);
        getWindow().setAttributes(params);
    }

    private boolean connected() {
        try (BufferedReader in = new BufferedReader(new FileReader("/sys/class/udc/ff300000.usb/state"))) {
            return "configured".equals(in.readLine());
        } catch (IOException e) { return false; }
    }

    private void leave() { mLeaving = true; closeKeyboard(); UsbDriveControl.stop(); }

    private void closeKeyboard() {
        if (mChannel != null) { mChannel.close(); mChannel = null; }
    }
    private void updateKeyboard() {
        if (mResumed && mFocused && !mLeaving && mKeyboard != null) {
            if (mChannel == null) mChannel = new KeyboardChannel(mKeyboardModel, mKeyboard);
        } else closeKeyboard();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); mFocused = focused; updateKeyboard();
    }

    @Override public void onBackPressed() { leave(); }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mKeyboard != null) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_POWER && event.getAction() == KeyEvent.ACTION_DOWN) leave();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B || event.getKeyCode() == KeyEvent.KEYCODE_POWER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) leave();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onResume() {
        super.onResume(); mResumed = true; updateKeyboard(); mHandler.post(mPoll);
    }
    @Override public void onPause() {
        mResumed = false; closeKeyboard(); mHandler.removeCallbacks(mPoll); super.onPause();
    }
    @Override public void onStop() {
        if (!isChangingConfigurations()) UsbDriveControl.stop();
        super.onStop();
    }
}
