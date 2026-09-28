package org.andr36oid.gamecomfort;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Runs in the background while a feature needs it and does nothing but wait for broadcasts.
 * Low battery warning: watches the charge level the fuel gauge reports and shows the banner
 * once per threshold while on battery.
 */
public class ComfortService extends Service {

    private Banner mBanner;
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Comfort.isServiceNeeded(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        watchBattery(Comfort.isBatteryWarningOn(this));
        // Brought back if the system has to kill it for memory during a game.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        watchBattery(false);
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
