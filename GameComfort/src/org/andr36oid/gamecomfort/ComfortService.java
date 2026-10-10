package org.andr36oid.gamecomfort;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Runs in the background and does nothing but wait for broadcasts. Low battery warning:
 * watches the charge level the fuel gauge reports and shows the banner once per threshold
 * while on battery. Continue playing and automatic game mode: watch which app is in front.
 * After a restart, optionally opens the game that was open when the console went off.
 */
public class ComfortService extends Service {

    static final String EXTRA_AFTER_BOOT = "after_boot";
    static final String EXTRA_GAME_WAS_OPEN = "game_was_open";
    /** Lets the home screen come up and settle first */
    private static final long CONTINUE_AFTER_BOOT_MS = 10000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private Banner mBanner;
    private GameWatcher mGameWatcher;
    private boolean mWatchingBattery;
    /** Level to warn about once the screen comes on, -1 for none. */
    private int mPendingPercent = -1;

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onBattery(intent);
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mPendingPercent >= 0) {
                showWarning(mPendingPercent);
                mPendingPercent = -1;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mBanner = new Banner(this);
        mGameWatcher = new GameWatcher(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Comfort.isServiceNeeded(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        watchBattery(Comfort.isBatteryWarningOn(this));
        mGameWatcher.start();
        if (intent != null && intent.getBooleanExtra(EXTRA_AFTER_BOOT, false)) {
            if (intent.getBooleanExtra(EXTRA_GAME_WAS_OPEN, false) && Continue.isOnBoot(this)) {
                mHandler.postDelayed(this::continueAfterBoot, CONTINUE_AFTER_BOOT_MS);
            } else {
                // For the tile and the power menu
                Continue.refreshSoon(this);
            }
        }
        // Brought back if the system has to kill it for memory during a game.
        return START_STICKY;
    }

    /** Opens the game again, unless the user started something else in the meantime. */
    private void continueAfterBoot() {
        if (!getSystemService(PowerManager.class).isInteractive()
                || !Continue.isHomeInFront(this)) {
            Continue.refreshSoon(this);
            return;
        }
        Continue.BACKGROUND.execute(() -> {
            final Continue.Result result = Continue.start(this);
            mHandler.post(() -> Continue.show(this, result));
        });
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        watchBattery(false);
        mGameWatcher.stop();
        mBanner.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void watchBattery(boolean watch) {
        if (watch == mWatchingBattery) {
            return;
        }
        mWatchingBattery = watch;
        if (watch) {
            // Sticky, so this also delivers the current level right away.
            registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            registerReceiver(mScreenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } else {
            unregisterReceiver(mBatteryReceiver);
            unregisterReceiver(mScreenReceiver);
            mPendingPercent = -1;
        }
    }

    private void onBattery(Intent intent) {
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final int percent = level < 0 || scale <= 0 ? -1 : level * 100 / scale;
        final boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;

        final int[] thresholds = Comfort.getThresholds(this);
        final int warned = Comfort.getWarned(this);
        final int warn = LowBattery.warnAbout(thresholds, warned, percent, plugged);
        final int next = LowBattery.nextWarned(thresholds, warned, percent, plugged);
        if (next != warned) {
            Comfort.setWarned(this, next);
        }

        if (plugged) {
            mPendingPercent = -1;
            mBanner.hide();
        } else if (warn != LowBattery.NONE) {
            Comfort.vibrate(this);
            if (getSystemService(PowerManager.class).isInteractive()) {
                showWarning(percent);
            } else {
                // Nobody would see it now.
                mPendingPercent = percent;
            }
        }
    }

    private void showWarning(int percent) {
        mBanner.show(getString(R.string.banner_text, percent));
    }
}
