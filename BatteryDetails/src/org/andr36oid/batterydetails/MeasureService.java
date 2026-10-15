package org.andr36oid.batterydetails;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Runs while a capacity measurement waits or runs (Measure): follows the battery and the
 * charger, saves the count every minute and at shutdown, and shows how far it is.
 */
public class MeasureService extends Service {

    private static final long SAVE_MS = 60 * 1000L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Intent mLastBattery;
    private int mShownMah = -1;
    private int mShownState = -1;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                if (mLastBattery != null) {
                    Measure.save(context, level(mLastBattery), voltage(mLastBattery));
                }
                return;
            }
            mLastBattery = intent;
            onBattery(intent);
        }
    };

    private final Runnable mSave = new Runnable() {
        @Override
        public void run() {
            if (mLastBattery != null) {
                Measure.save(MeasureService.this, level(mLastBattery), voltage(mLastBattery));
                show();
            }
            mHandler.postDelayed(this, SAVE_MS);
        }
    };

    static void start(Context context) {
        context.startForegroundService(new Intent(context, MeasureService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Measure.createChannel(this);
        startForeground(Measure.NOTIFICATION_RUNNING, build());
        final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(mReceiver, filter);
        mHandler.postDelayed(mSave, SAVE_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int state = Measure.state(this);
        if (state != Measure.WAITING && state != Measure.RUNNING) {
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mSave);
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onBattery(Intent battery) {
        if (!Measure.update(this, battery)) {
            stopSelf();
            return;
        }
        show();
    }

    private void show() {
        final int state = Measure.state(this);
        final int mah = Measure.soFarMah(this);
        // Every 10 mAh is plenty
        if (state == mShownState && Math.abs(mah - mShownMah) < 10) {
            return;
        }
        mShownState = state;
        mShownMah = mah;
        getSystemService(NotificationManager.class).notify(Measure.NOTIFICATION_RUNNING, build());
    }

    private Notification build() {
        final String text;
        if (Measure.state(this) == Measure.RUNNING) {
            text = getString(R.string.measure_running, Measure.soFarMah(this));
        } else {
            text = getString(Measure.fullSeen(this) ? R.string.measure_unplug
                    : R.string.measure_charge);
        }
        return new Notification.Builder(this, Measure.CHANNEL)
                .setSmallIcon(R.drawable.ic_battery_details)
                .setContentTitle(getString(R.string.measure_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(Measure.openPage(this))
                .setOngoing(true)
                .build();
    }

    private static int level(Intent battery) {
        return battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
    }

    private static int voltage(Intent battery) {
        return battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
    }
}
