package org.andr36oid.usbmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

/**
 * Runs while the OTG port is a device for a computer. The moment a charger is plugged into the
 * other port it switches the port back to accessories and says why.
 *
 * The kernel does the switch itself within a few milliseconds (the usb2 phy watches the charger,
 * see rockchip,host-while-charging), so this service is the second line and the messenger: it
 * reacts to Android's battery status, which healthd updates from the charger's uevent, and
 * shows the toast and a notification. A manifest receiver for ACTION_POWER_CONNECTED would not
 * be woken for this app (background broadcast limits), so the watch lives in a foreground
 * service with a registered receiver.
 */
public class UsbModeService extends Service {

    private static final String TAG = "UsbModeService";

    private static final String CHANNEL_ON = "usb_device_mode";
    private static final String CHANNEL_ALERT = "usb_device_mode_alert";
    private static final int ID_ON = 1;
    private static final int ID_ALERT = 2;

    private UsbMode mUsbMode;
    private boolean mRegistered;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            check();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mUsbMode = new UsbMode(this);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(ID_ON, ongoing());
        if (!mRegistered) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            // Sticky: delivered right away, so a charger that is already in is caught too.
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            registerReceiver(mReceiver, filter);
            mRegistered = true;
        }
        check();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mRegistered) {
            unregisterReceiver(mReceiver);
            mRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void check() {
        final boolean device = mUsbMode.isDevice();
        if (mUsbMode.isCharging()) {
            // The kernel has usually switched the port back already; make sure, then tell.
            final boolean wasDevice = device || mUsbMode.wasDeviceThisBoot();
            if (wasDevice) {
                mUsbMode.setDevice(false);
                Log.w(TAG, "Charger plugged in while connected to a computer, switched back");
                Toast.makeText(this, R.string.charging_toast, Toast.LENGTH_LONG).show();
                getSystemService(NotificationManager.class).notify(ID_ALERT, alert());
            }
            stopSelf();
        } else if (!device) {
            // Switched back some other way (shell, or a restart of this app's process).
            mUsbMode.setDeviceMarker(false);
            stopSelf();
        }
    }

    private void createChannels() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        final NotificationChannel on = new NotificationChannel(CHANNEL_ON,
                getString(R.string.channel_on), NotificationManager.IMPORTANCE_LOW);
        final NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT,
                getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(on);
        nm.createNotificationChannel(alert);
    }

    private PendingIntent openScreen() {
        return PendingIntent.getActivity(this, 0, new Intent(this, UsbModeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification ongoing() {
        return new Notification.Builder(this, CHANNEL_ON)
                .setSmallIcon(R.drawable.ic_usb_mode)
                .setContentTitle(getString(R.string.notification_on_title))
                .setContentText(getString(R.string.charging_warning))
                .setStyle(new Notification.BigTextStyle()
                        .bigText(getString(R.string.charging_warning)))
                .setContentIntent(openScreen())
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private Notification alert() {
        return new Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_usb_mode)
                .setContentTitle(getString(R.string.notification_alert_title))
                .setContentText(getString(R.string.notification_alert_text))
                .setStyle(new Notification.BigTextStyle()
                        .bigText(getString(R.string.notification_alert_text)))
                .setContentIntent(openScreen())
                .setAutoCancel(true)
                .build();
    }
}
